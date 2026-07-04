package com.youdub.replica.service.adapter.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youdub.replica.config.AppProperties;
import com.youdub.replica.model.entity.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * Ollama 翻译适配器。
 * 调用本地 Ollama API（/api/chat）进行翻译。
 */
@Slf4j
@Component("ollama")
@RequiredArgsConstructor
public class OllamaTranslator extends AbstractTranslator {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AppProperties.Translate.Ollama config;

    @Qualifier("virtualExecutor")
    private final ExecutorService virtualExecutor;

    @Override
    public String getName() {
        return "ollama";
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

        String chatUrl = resolveChatUrl();
        int useConcurrency = config.getConcurrency() <= 0 ? 1 : config.getConcurrency();

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

        // 用英文原文生成中文小结
        String fullText = items.stream().map(Utterance::text).reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
        generateSummary(fullText, outputDir, dstLang, (text, lang) ->
                callOllamaSummary(text, chatUrl, config.getModel(), lang));

        log.info("Ollama 并发翻译：共 {} 句，并发数={}", items.size(), useConcurrency);
        Semaphore semaphore = new Semaphore(useConcurrency);
        AtomicInteger completed = new AtomicInteger(0);
        int total = items.size();

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (Utterance item : items) {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        String translated = callOllama(chatUrl, config.getModel(), srcLang, dstLang, item.text());
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

        List<String> translations = new ArrayList<>();
        for (CompletableFuture<String> f : futures) {
            translations.add(f.join());
        }

        ArrayNode translationArray = objectMapper.createArrayNode();
        for (int i = 0; i < items.size(); i++) {
            Utterance item = items.get(i);
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("src", item.text());
            entry.put("dst", translations.get(i));
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
     * 调用 Ollama /api/chat 对英文原文生成结构化中文 Markdown 小结。
     */
    private String callOllamaSummary(String fullText, String chatUrl, String model,
                                     String targetLang) throws Exception {
        String systemPrompt = "You are a professional summarizer. Read the following English transcript " +
                "and create a structured summary in " + targetLang + " (Markdown format). " +
                "Include:\n" +
                "- ## 内容概要 (2-3 sentences overview)\n" +
                "- ## 核心要点 (bullet points of key ideas)\n" +
                "- ## 关键术语 (important terms mentioned, if any)";
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("stream", false);
        ObjectNode options = objectMapper.createObjectNode();
        options.put("temperature", 0.3);
        requestBody.set("options", options);
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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(chatUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama API 调用失败 [" + response.statusCode() + "]：" + response.body());
        }
        JsonNode root = objectMapper.readTree(response.body());
        return root.path("message").path("content").asText("").trim();
    }

    /**
     * 调用 Ollama /api/chat 翻译单句。
     */
    private String callOllama(String chatUrl, String model, String srcLang, String dstLang, String text) throws Exception {
        String systemPrompt = "You are a professional translator. Translate the user-provided text from " + srcLang +
                " to " + dstLang + " LITERALLY. Even if the input is an incomplete sentence fragment, " +
                "translate it literally without adding any explanation, context, or elaboration. " +
                "Respond with ONLY the exact translated text — no extra words, no explanations, no notes.";

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("stream", false);

        ObjectNode options = objectMapper.createObjectNode();
        options.put("temperature", 0.2);
        requestBody.set("options", options);

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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(chatUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama API 调用失败 [" + response.statusCode() + "]：" + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        String content = root.path("message").path("content").asText(text).trim();
        return content.isEmpty() ? text : content;
    }

    private String resolveChatUrl() {
        String base = config.getBaseUrl();
        if (base == null || base.isBlank()) {
            return "http://localhost:11434/api/chat";
        }
        return base + "/api/chat";
    }

}
