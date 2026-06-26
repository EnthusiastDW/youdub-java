package com.youdub.replica.service.adapter.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youdub.replica.config.AppProperties;
import com.youdub.replica.model.entity.Task;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenAI 兼容翻译适配器。
 * 两阶段翻译：阶段A 全文预处理（生成摘要和热词），阶段B 并发逐句翻译。
 */
@Slf4j
@Component("openai")
@RequiredArgsConstructor
public class OpenAiTranslator implements Translator {

    @Data
    @Component
    @ConfigurationProperties(prefix = "translate.openai")
    static class TranslateOpenAiConfig {
        private String url ;
        private String apiKey;
        private String model;
        private int concurrency;
    }

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TranslateOpenAiConfig config;

    @Qualifier("virtualExecutor")
    private final ExecutorService virtualExecutor;

    @Override
    public String getName() {
        return "openai";
    }

    @Override
    public void translate(Task task, Path asrPath, Path outputDir, String model, String srcLang, String dstLang) throws Exception {
        if (asrPath == null || !Files.exists(asrPath)) {
            throw new IllegalArgumentException("ASR 文件不存在：" + asrPath);
        }
        Files.createDirectories(outputDir);

        Path translationFile = outputDir.resolve("translation." + dstLang + ".json");
        if (Files.exists(translationFile)) {
            log.info("翻译结果已存在，跳过：{}", translationFile);
            return;
        }

        String apiKey = config.getApiKey();
        String chatUrl = config.getUrl();
        String useModel = config.getModel();
        int useConcurrency = config.getConcurrency() <= 0 ? 1 : config.getConcurrency();

        if (apiKey.isBlank()) {
            throw new RuntimeException("未配置 OPENAI_API_KEY 环境变量");
        }

        // 读取 ASR 结果，提取所有句子
        JsonNode asrRoot = objectMapper.readTree(Files.readString(asrPath));
        JsonNode utterances = asrRoot.path("result").path("utterances");
        if (!utterances.isArray() || utterances.isEmpty()) {
            log.warn("ASR 结果中没有 utterances，写入空翻译");
            Files.writeString(translationFile, "{\"translation\":[]}");
            return;
        }

        List<Utterance> items = new ArrayList<>();
        for (JsonNode u : utterances) {
            String text = u.path("text").asText("").trim();
            if (text.isEmpty()) {
                continue;
            }
            items.add(new Utterance(
                    text,
                    u.path("start_time").asLong(0),
                    u.path("end_time").asLong(0),
                    u.path("speaker").asText("1")
            ));
        }

        if (items.isEmpty()) {
            Files.writeString(translationFile, "{\"translation\":[]}");
            return;
        }

        // 阶段A：全文预处理
        Path preprocessFile = outputDir.resolve("translation_preprocess.json");
        PreprocessResult preprocess;
        if (Files.exists(preprocessFile)) {
            log.info("使用缓存的预处理结果：{}", preprocessFile);
            preprocess = loadPreprocess(preprocessFile);
        } else {
            log.info("阶段A：全文预处理，共 {} 句", items.size());
            String fullText = items.stream().map(Utterance::text).reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
            preprocess = callPreprocess(apiKey, chatUrl, useModel, srcLang, dstLang, fullText, task);
            savePreprocess(preprocessFile, preprocess);
        }

        // 阶段B：并发逐句翻译
        log.info("阶段B：并发翻译，并发数={}", useConcurrency);
        Semaphore semaphore = new Semaphore(useConcurrency);
        AtomicInteger completed = new AtomicInteger(0);
        int total = items.size();

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (Utterance item : items) {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        String translated = callTranslate(apiKey, chatUrl, useModel, srcLang, dstLang, item.text, preprocess);
                        int done = completed.incrementAndGet();
                        if (done % 10 == 0 || done == total) {
                            log.info("翻译进度：{}/{}", done, total);
                        }
                        return translated;
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("翻译被中断", e);
                } catch (Exception e) {
                    throw new RuntimeException("翻译失败：" + item.text, e);
                }
            }, virtualExecutor);
            futures.add(future);
        }

        // 等待所有翻译完成
        List<String> translations = new ArrayList<>();
        for (CompletableFuture<String> f : futures) {
            translations.add(f.join());
        }

        // 合并结果
        ArrayNode translationArray = objectMapper.createArrayNode();
        for (int i = 0; i < items.size(); i++) {
            Utterance item = items.get(i);
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("src", item.text);
            entry.put("dst", translations.get(i));
            entry.put("src_lang", srcLang);
            entry.put("dst_lang", dstLang);
            entry.put("start_time", item.startTime);
            entry.put("end_time", item.endTime);
            entry.put("speaker", item.speaker);
            translationArray.add(entry);
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.set("translation", translationArray);
        Files.writeString(translationFile, objectMapper.writeValueAsString(root));
        log.info("翻译完成：task={}, file={}", task.getId(), translationFile);
    }

    /**
     * 调用 OpenAI Chat Completions API 进行预处理。
     */
    private PreprocessResult callPreprocess(String apiKey, String chatUrl, String model, String srcLang, String dstLang,
                                            String fullText, Task task) throws Exception {
        String systemPrompt = "You are a translation assistant. Analyze the following text and provide a JSON response " +
                "with summary, hotwords (src/dst pairs), and corrections (wrong/correct pairs). " +
                "Source language: " + srcLang + ", target language: " + dstLang + ".";
        String userPrompt = "Text to analyze:\n" + fullText +
                "\n\nVideo title: " + (task.getTitle() == null ? "" : task.getTitle()) +
                "\n\nRespond in JSON format: {\"summary\": \"...\", \"hotwords\": [{\"src\":\"...\",\"dst\":\"...\"}], " +
                "\"corrections\": [{\"wrong\":\"...\",\"correct\":\"...\"}]}";

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("temperature", 0.2);
        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode sysMsg = objectMapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        messages.add(sysMsg);
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(userMsg);
        requestBody.set("messages", messages);

        String response = callChatApi(apiKey, chatUrl, model, requestBody);
        JsonNode root = objectMapper.readTree(response);
        String content = root.path("choices").path(0).path("message").path("content").asText("");

        // 尝试解析 JSON（可能包裹在 markdown 代码块中）
        String json = extractJson(content);
        PreprocessResult result = new PreprocessResult();
        if (json != null) {
            try {
                JsonNode parsed = objectMapper.readTree(json);
                result.summary = parsed.path("summary").asText("");
                result.hotwords = parsed.path("hotwords").asText("");
                result.corrections = parsed.path("corrections").asText("");
            } catch (Exception e) {
                log.warn("解析预处理 JSON 失败：{}", e.getMessage());
                result.summary = content;
            }
        } else {
            result.summary = content;
        }
        return result;
    }

    /**
     * 调用 OpenAI Chat Completions API 翻译单句。
     */
    private String callTranslate(String apiKey, String chatUrl, String model, String srcLang, String dstLang,
                                 String text, PreprocessResult preprocess) throws Exception {
        String systemPrompt = "You are a professional translator. Translate the user-provided text from " + srcLang +
                " to " + dstLang + ". Respond with only the translated text, no explanations. " +
                "Context summary: " + preprocess.summary;
        if (!preprocess.hotwords.isBlank()) {
            systemPrompt += " Hotwords: " + preprocess.hotwords;
        }

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("temperature", 0.2);
        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode sysMsg = objectMapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        messages.add(sysMsg);
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", text);
        messages.add(userMsg);
        requestBody.set("messages", messages);

        String response = callChatApi(apiKey, chatUrl, model, requestBody);
        JsonNode root = objectMapper.readTree(response);
        return root.path("choices").path(0).path("message").path("content").asText(text).trim();
    }

    /**
     * 调用 Chat Completions API。
     */
    private String callChatApi(String apiKey, String chatUrl, String model, ObjectNode requestBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(chatUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("Chat API 调用失败 [" + response.statusCode() + "]：" + response.body());
        }
        return response.body();
    }

    /**
     * 从可能包含 markdown 代码块的文本中提取 JSON。
     */
    private String extractJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        int start = trimmed.indexOf("```json");
        if (start < 0) {
            start = trimmed.indexOf("```");
        }
        if (start >= 0) {
            int end = trimmed.indexOf("```", start + 3);
            if (end > start) {
                String code = trimmed.substring(start + 3, end).trim();
                if (code.startsWith("json")) {
                    code = code.substring(4).trim();
                }
                return code;
            }
        }
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1);
        }
        return null;
    }

    private void savePreprocess(Path file, PreprocessResult result) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("summary", result.summary);
        node.put("hotwords", result.hotwords);
        node.put("corrections", result.corrections);
        Files.writeString(file, objectMapper.writeValueAsString(node));
    }

    private PreprocessResult loadPreprocess(Path file) throws Exception {
        JsonNode node = objectMapper.readTree(Files.readString(file));
        PreprocessResult result = new PreprocessResult();
        result.summary = node.path("summary").asText("");
        result.hotwords = node.path("hotwords").asText("");
        result.corrections = node.path("corrections").asText("");
        return result;
    }

    private record Utterance(String text, long startTime, long endTime, String speaker) {}

    private static class PreprocessResult {
        String summary = "";
        String hotwords = "";
        String corrections = "";
    }
}