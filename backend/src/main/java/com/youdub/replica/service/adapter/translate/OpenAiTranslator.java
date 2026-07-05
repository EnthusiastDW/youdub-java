package com.youdub.replica.service.adapter.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youdub.replica.config.AppProperties;
import com.youdub.replica.model.entity.Task;
import com.youdub.replica.service.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import static com.youdub.replica.service.adapter.AdapterConstants.OPENAI;

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
@Component(OPENAI)
@RequiredArgsConstructor
public class OpenAiTranslator extends AbstractTranslator {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    @Qualifier("virtualExecutor")
    private final ExecutorService virtualExecutor;

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

        var cfg = settingsService.getProviderConfig(OPENAI, AppProperties.Translate.Openai.class);
        String apiKey = cfg.getApiKey();
        String chatUrl = cfg.getChatUrl();
        String useModel = cfg.getModel();
        int useConcurrency = cfg.getConcurrency() <= 0 ? 1 : cfg.getConcurrency();

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

        // 合并从句片段，防止 LLM 对不完整句子脑补扩展
        items = mergeFragments(items);

        // 阶段A：全文预处理
        Path preprocessFile = outputDir.resolve("translation_preprocess.json");
        PreprocessResult preprocess;
        String fullText = items.stream().map(Utterance::text).reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
        if (Files.exists(preprocessFile)) {
            log.info("使用缓存的预处理结果：{}", preprocessFile);
            preprocess = loadPreprocess(preprocessFile);
        } else {
            log.info("阶段A：全文预处理，共 {} 句", items.size());
            preprocess = callPreprocess(apiKey, chatUrl, useModel, srcLang, dstLang, fullText, task);
            savePreprocess(preprocessFile, preprocess);
        }

        // 用英文原文生成中文小结
        generateSummary(fullText, outputDir, dstLang, (text, lang) ->
                callSummary(text, apiKey, chatUrl, useModel, lang));

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
                        String translated = callTranslate(apiKey, chatUrl, useModel, srcLang, dstLang, item.text(), preprocess);
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
                    throw new RuntimeException("翻译失败：" + item.text(), e);
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
            String dst = translations.get(i);
            if (dst.isBlank()) {
                dst = item.text();
                log.warn("翻译结果为空，使用原文：{}", item.text());
            }
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("src", item.text());
            entry.put("dst", dst);
            entry.put("src_lang", srcLang);
            entry.put("dst_lang", dstLang);
            entry.put("start_time", item.startTime());
            entry.put("end_time", item.endTime());
            entry.put("speaker", item.speaker());
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

    private static final List<String> REFUSAL_PHRASES = List.of(
            "很抱歉",
            "没有足够的上下文",
            "无法回答",
            "无法提供",
            "i'm sorry",
            "i apologize",
            "don't have enough context",
            "cannot answer",
            "cannot provide"
    );

    private static boolean isRefusal(String content) {
        String lower = content.toLowerCase();
        return REFUSAL_PHRASES.stream().anyMatch(lower::contains);
    }

    /**
     * 调用 OpenAI Chat Completions API 翻译单句。
     */
    private String callTranslate(String apiKey, String chatUrl, String model, String srcLang, String dstLang,
                                 String text, PreprocessResult preprocess) throws Exception {
        String systemPrompt = "You are a professional translator. Translate the user-provided text from " + srcLang +
                " to " + dstLang + " LITERALLY. Even if the input is an incomplete sentence fragment, " +
                "translate it literally without adding any explanation, context, or elaboration. " +
                "Respond with ONLY the exact translated text — no extra words, no explanations, no notes. " +
                "Context summary: " + preprocess.summary + "." +
                " This context is for reference only — do NOT include it in your translation.";
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

        // 重试最多 3 次，避免 LLM 偶发返回空内容或拒绝回答
        for (int attempt = 1; attempt <= 3; attempt++) {
            String response = callChatApi(apiKey, chatUrl, model, requestBody);
            JsonNode root = objectMapper.readTree(response);
            String content = root.path("choices").path(0).path("message").path("content").asText("").trim();
            if (!content.isEmpty() && !isRefusal(content)) {
                return content;
            }
            log.warn("翻译结果无效（第 {}/3 次），原文='{}'，返回='{}'", attempt, text, content);
        }
        // 3 次重试均无效，回退到原文
        return text;
    }

    /**
     * 调用 OpenAI Chat 对英文原文生成结构化中文 Markdown 小结。
     * 单独的方法，便于以后调整 prompt 和参数。
     */
    private String callSummary(String fullText, String apiKey, String chatUrl, String model,
                               String targetLang) throws Exception {
        String systemPrompt = "You are a professional summarizer. Read the following English transcript " +
                "and create a structured summary in " + targetLang + " (Markdown format). " +
                "Include:\n" +
                "- ## 内容概要 (2-3 sentences overview)\n" +
                "- ## 核心要点 (bullet points of key ideas)\n" +
                "- ## 关键术语 (important terms mentioned, if any)";
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("temperature", 0.3);
        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode sysMsg = objectMapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        messages.add(sysMsg);
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", fullText);
        messages.add(userMsg);
        requestBody.set("messages", messages);
        String response = callChatApi(apiKey, chatUrl, model, requestBody);
        JsonNode root = objectMapper.readTree(response);
        return root.path("choices").path(0).path("message").path("content").asText("").trim();
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

    private static class PreprocessResult {
        String summary = "";
        String hotwords = "";
        String corrections = "";
    }
}