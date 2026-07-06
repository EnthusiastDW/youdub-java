package com.youdub.replica.service.adapter.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youdub.replica.config.AppProperties;
import com.youdub.replica.model.entity.Task;
import com.youdub.replica.service.SettingsService;
import com.youdub.replica.service.adapter.AdapterSkipTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

import static com.youdub.replica.service.adapter.AdapterConstants.OPENAI;
import static com.youdub.replica.service.adapter.AdapterConstants.OPENAI_ASR_CORRECTOR;

/**
 * OpenAI LLM ASR 纠错适配器。
 * <p>
 * 读取 ASR 转写结果，将完整转录文本作为上下文发给 LLM，
 * 让 LLM 根据全文语境自动纠正领域特定术语的误识别。
 * <p>
 * 配置为空时回退到翻译服务的 API Key / Chat URL / 模型配置。
 * 重试策略：最多 {@link #MAX_RETRIES} 次，拒绝/空/非 JSON 响应均视为无效。
 */
@Slf4j
@Component("openai-asr-corrector")
@RequiredArgsConstructor
public class OpenAiAsrCorrector implements AsrCorrector {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;
    private final AdapterSkipTracker skipTracker;

    /** 重试次数上限 */
    private static final int MAX_RETRIES = 3;
    /** LLM 温度参数，较低值使输出更确定 */
    private static final double TEMPERATURE = 0.1;
    /** 纠错结果文件名 */
    private static final String CORRECTED_FILE = "asr_corrected.json";

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

    @Override
    public void correct(Task task, Path asrPath, Path outputDir) throws Exception {
        if (asrPath == null || !Files.exists(asrPath)) {
            throw new IllegalArgumentException("ASR 文件不存在：" + asrPath);
        }
        Files.createDirectories(outputDir);

        Path correctedFile = outputDir.resolve(CORRECTED_FILE);
        if (Files.exists(correctedFile)) {
            log.info("ASR 纠错结果已存在，跳过：{}", correctedFile);
            skipTracker.markSkipped();
            return;
        }

        JsonNode asrRoot = objectMapper.readTree(Files.readString(asrPath));
        JsonNode utterancesNode = asrRoot.path("result").path("utterances");
        if (!utterancesNode.isArray() || utterancesNode.isEmpty()) {
            log.warn("ASR 结果中没有 utterances，跳过纠错");
            Files.writeString(correctedFile, objectMapper.writeValueAsString(asrRoot));
            return;
        }

        List<UtteranceItem> items = new ArrayList<>();
        StringBuilder fullTextBuilder = new StringBuilder();
        int idx = 0;
        for (JsonNode u : utterancesNode) {
            String text = u.path("text").asText("").trim();
            if (text.isEmpty()) continue;
            items.add(new UtteranceItem(idx, text));
            if (fullTextBuilder.length() > 0) fullTextBuilder.append("\n");
            fullTextBuilder.append(text);
            idx++;
        }
        String fullText = fullTextBuilder.toString();

        if (items.isEmpty()) {
            log.warn("ASR 结果中无有效文本，跳过纠错");
            Files.writeString(correctedFile, objectMapper.writeValueAsString(asrRoot));
            return;
        }

        var resolved = resolveConfig();

        String systemPrompt = buildSystemPrompt();
        ArrayNode inputUtterances = objectMapper.createArrayNode();
        for (UtteranceItem item : items) {
            ObjectNode u = objectMapper.createObjectNode();
            u.put("id", item.id);
            u.put("text", item.text);
            inputUtterances.add(u);
        }
        String utterancesJson = objectMapper.writeValueAsString(inputUtterances);

        String userPrompt = "Full transcription for context:\n---\n"
                + fullText + "\n---\n\n"
                + "Utterances to correct (preserve the id mapping):\n"
                + utterancesJson;
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", resolved.model());
        requestBody.put("temperature", TEMPERATURE);
        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(objectMapper.createObjectNode().put("role", "system").put("content", systemPrompt));
        messages.add(objectMapper.createObjectNode().put("role", "user").put("content", userPrompt));
        requestBody.set("messages", messages);

        String correctedJson = callWithRetry(resolved.apiKey(), resolved.chatUrl(), resolved.model(), requestBody);
        JsonNode correctedRoot = objectMapper.readTree(correctedJson);

        JsonNode correctedUtterances = correctedRoot.path("utterances");
        if (!correctedUtterances.isArray()) {
            throw new RuntimeException("LLM 返回格式错误：缺少 utterances 数组");
        }
        java.util.Map<Integer, String> corrections = new java.util.HashMap<>();
        for (JsonNode cu : correctedUtterances) {
            int id = cu.path("id").asInt(-1);
            String text = cu.path("text").asText("");
            if (id >= 0 && !text.isBlank()) {
                corrections.put(id, text.trim());
            }
        }

        ObjectNode fixedRoot = asrRoot.deepCopy();
        ObjectNode resultObj = (ObjectNode) fixedRoot.path("result");
        StringBuilder correctedFullText = new StringBuilder();
        ArrayNode resultUtterances = (ArrayNode) resultObj.path("utterances");
        int itemIdx = 0;
        for (int i = 0; i < resultUtterances.size(); i++) {
            JsonNode u = resultUtterances.get(i);
            String origText = u.path("text").asText("").trim();
            if (origText.isEmpty()) continue;

            // 保存原始文本用于事后评估效果
            ((ObjectNode) u).put("original_text", origText);

            String corrected = corrections.get(itemIdx);
            if (corrected != null && !corrected.equals(origText)) {
                log.info("ASR 纠错：'{}' → '{}'", origText, corrected);
                ((ObjectNode) u).put("text", corrected);
            }

            if (correctedFullText.length() > 0) correctedFullText.append(" ");
            correctedFullText.append(corrected != null ? corrected : origText);
            itemIdx++;
        }
        resultObj.put("text", correctedFullText.toString());

        Files.writeString(correctedFile, objectMapper.writeValueAsString(fixedRoot));
        int correctedCount = (int) corrections.values().stream()
                .filter(c -> {
                    for (UtteranceItem item : items) {
                        if (item.text.equals(c)) return false;
                    }
                    return true;
                }).count();
        log.info("ASR 纠错完成：task={}, total={}, corrected={}, file={}",
                task.getId(), items.size(), correctedCount, correctedFile);
    }

    private String buildSystemPrompt() {
        return "You are a speech recognition correction assistant. "
                + "Fix domain-specific terminology misrecognized by the ASR engine.\n\n"
                + "The FULL transcription is provided below as context. Read it carefully to "
                + "understand the topic, technical domain, and correct terminology.\n\n"
                + "Rules:\n"
                + "1. Only fix words that are CLEARLY misrecognized due to ASR error.\n"
                + "2. Use surrounding context to determine the correct term.\n"
                + "3. Do NOT paraphrase, rephrase, or 'improve' the text.\n"
                + "4. Do NOT fix grammar, style, or punctuation.\n"
                + "5. Do NOT add or remove words unless correcting an ASR error.\n"
                + "6. Keep the sentence structure and length the same.\n"
                + "7. When unsure, leave the original text unchanged.\n\n"
                + "Examples of ASR errors to correct:\n"
                + "- Technical terms: 'trades' → 'traits' in a Rust context\n"
                + "- Proper nouns: 'kub ernetes' → 'Kubernetes'\n"
                + "- Acronyms: 'eff ell' → 'EFL' or 'g ee pee tee' → 'GPT'\n\n"
                + "Return ONLY valid JSON in the following format "
                + "(no markdown, no extra text):\n"
                + "{\"utterances\":[{\"id\":0,\"text\":\"corrected text\"},...]}";
    }

    private ResolvedConfig resolveConfig() {
        var translate = settingsService.getProviderConfig(OPENAI, AppProperties.Translate.Openai.class);
        var corrector = settingsService.getProviderConfig(OPENAI_ASR_CORRECTOR,
                AppProperties.AsrCorrectorConfig.OpenaiAsrCorrector.class);

        String apiKey = firstNonBlank(corrector.getApiKey(), translate.getApiKey());
        String chatUrl = firstNonBlank(corrector.getChatUrl(), translate.getChatUrl());
        String model = firstNonBlank(corrector.getModel(), translate.getModel());

        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("未配置 API Key，无法进行 ASR 纠错");
        }
        return new ResolvedConfig(apiKey, chatUrl, model);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private String callWithRetry(String apiKey, String chatUrl, String model,
                                  ObjectNode requestBody) throws Exception {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String response = callChatApi(apiKey, chatUrl, model, requestBody);
                JsonNode root = objectMapper.readTree(response);
                String content = root.path("choices").path(0).path("message").path("content").asText("").trim();

                if (content.isEmpty()) {
                    log.warn("ASR 纠错返回空内容（第 {}/{} 次）", attempt, MAX_RETRIES);
                    continue;
                }

                if (isRefusal(content)) {
                    log.warn("ASR 纠错被拒绝（第 {}/{} 次）：{}", attempt, MAX_RETRIES, content);
                    continue;
                }

                String json = extractJson(content);
                if (json == null) {
                    log.warn("ASR 纠错返回非 JSON 内容（第 {}/{} 次）：{}", attempt, MAX_RETRIES, truncate(content, 100));
                    continue;
                }

                JsonNode parsed = objectMapper.readTree(json);
                if (!parsed.has("utterances") || !parsed.path("utterances").isArray()) {
                    log.warn("ASR 纠错返回的 JSON 缺少 utterances 数组（第 {}/{} 次）", attempt, MAX_RETRIES);
                    continue;
                }

                return json;

            } catch (Exception e) {
                log.warn("ASR 纠错调用异常（第 {}/{} 次）：{}", attempt, MAX_RETRIES, e.getMessage());
                if (attempt == MAX_RETRIES) {
                    throw e;
                }
            }
        }

        throw new RuntimeException("ASR 纠错失败：已重试 " + MAX_RETRIES + " 次，均未获得有效结果");
    }

    private String callChatApi(String apiKey, String chatUrl, String model,
                                ObjectNode requestBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(chatUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("Chat API 调用失败 [" + response.statusCode() + "]：" + response.body());
        }
        return response.body();
    }

    private static boolean isRefusal(String content) {
        String lower = content.toLowerCase();
        return REFUSAL_PHRASES.stream().anyMatch(lower::contains);
    }

    private static String extractJson(String content) {
        if (content == null || content.isBlank()) return null;
        String trimmed = content.trim();
        if (trimmed.startsWith("{")) return trimmed;

        int start = trimmed.indexOf("```json");
        if (start < 0) start = trimmed.indexOf("```");
        if (start >= 0) {
            int end = trimmed.indexOf("```", start + 3);
            if (end > start) {
                String code = trimmed.substring(start + 3, end).trim();
                if (code.startsWith("json")) code = code.substring(4).trim();
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

    private static String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private record UtteranceItem(int id, String text) {
    }

    private record ResolvedConfig(String apiKey, String chatUrl, String model) {
    }
}
