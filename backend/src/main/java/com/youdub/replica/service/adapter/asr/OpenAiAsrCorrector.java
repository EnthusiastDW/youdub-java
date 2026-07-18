package com.youdub.replica.service.adapter.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youdub.replica.config.AppProperties;
import com.youdub.replica.model.entity.Task;
import com.youdub.replica.service.SettingsService;
import com.youdub.replica.util.AiChatRetry;
import com.youdub.replica.util.HttpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

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
 * 重试策略：最多 3 次（可配置），拒绝/空/非 JSON 响应均视为无效。
 */
@Slf4j
@Component("openai-asr-corrector")
@RequiredArgsConstructor
public class OpenAiAsrCorrector implements AsrCorrector {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    private static final AiChatRetry.RetryConfig RETRY_CONFIG = AiChatRetry.RetryConfig.builder().build();
    /** LLM 温度参数，较低值使输出更确定 */
    private static final double TEMPERATURE = 0.1;
    /** 纠错结果文件名 */
    private static final String CORRECTED_FILE = "asr_corrected.json";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    /** 每个批次的最大字符数（~4000 tokens），防止硅基流动等 API 上下文超限 */
    private static final int BATCH_CHAR_LIMIT = 8000;
    /** 每批次的最大输出 token，不再用 65536 这种激进值 */
    private static final int MAX_OUTPUT_TOKENS = 8192;

    @Override
    public void correct(Task task, Path asrPath, Path outputDir) throws Exception {
        if (asrPath == null || !Files.exists(asrPath)) {
            throw new IllegalArgumentException("ASR 文件不存在：" + asrPath);
        }
        Files.createDirectories(outputDir);

        Path correctedFile = outputDir.resolve(CORRECTED_FILE);
        if (Files.exists(correctedFile)) {
            log.info("ASR 纠错结果已存在，跳过：{}", correctedFile);
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
        int idx = 0;
        for (JsonNode u : utterancesNode) {
            String text = u.path("text").asText("").trim();
            if (text.isEmpty()) continue;
            items.add(new UtteranceItem(idx, text));
            idx++;
        }

        if (items.isEmpty()) {
            log.warn("ASR 结果中无有效文本，跳过纠错");
            Files.writeString(correctedFile, objectMapper.writeValueAsString(asrRoot));
            return;
        }

        var resolved = resolveConfig();
        String systemPrompt = buildSystemPrompt();

        // 将 utterances 按字符数分批处理，避免 API 上下文超限
        List<List<UtteranceItem>> batches = splitIntoBatches(items);
        java.util.Map<Integer, String> corrections = new java.util.HashMap<>();

        for (int batchIdx = 0; batchIdx < batches.size(); batchIdx++) {
            List<UtteranceItem> batch = batches.get(batchIdx);
            log.info("ASR 纠错批次 {}/{}：{} 条 utterances", batchIdx + 1, batches.size(), batch.size());

            // 本批次的拼接文本作为上下文
            StringBuilder batchContextBuilder = new StringBuilder();
            for (UtteranceItem item : batch) {
                if (batchContextBuilder.length() > 0) batchContextBuilder.append("\n");
                batchContextBuilder.append(item.text);
            }
            String batchContext = batchContextBuilder.toString();

            ArrayNode batchUtterances = objectMapper.createArrayNode();
            for (UtteranceItem item : batch) {
                ObjectNode u = objectMapper.createObjectNode();
                u.put("id", item.id);
                u.put("text", item.text);
                batchUtterances.add(u);
            }
            String batchUtterancesJson = objectMapper.writeValueAsString(batchUtterances);

            String userPrompt = "Full transcription for context:\n---\n"
                    + batchContext + "\n---\n\n"
                    + "Utterances to correct (preserve the id mapping):\n"
                    + batchUtterancesJson;
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", resolved.model());
            requestBody.put("temperature", TEMPERATURE);
            requestBody.put("max_tokens", MAX_OUTPUT_TOKENS);
            ArrayNode messages = objectMapper.createArrayNode();
            messages.add(objectMapper.createObjectNode().put("role", "system").put("content", systemPrompt));
            messages.add(objectMapper.createObjectNode().put("role", "user").put("content", userPrompt));
            requestBody.set("messages", messages);

            String correctedJson = callAsrApi(resolved.apiKey(), resolved.chatUrl(), resolved.model(), requestBody);
            JsonNode correctedRoot = objectMapper.readTree(correctedJson);

            JsonNode correctedUtterances = correctedRoot.path("utterances");
            if (!correctedUtterances.isArray()) {
                throw new RuntimeException("LLM 返回格式错误：缺少 utterances 数组");
            }
            for (JsonNode cu : correctedUtterances) {
                int id = cu.path("id").asInt(-1);
                String text = cu.path("text").asText("");
                if (id >= 0 && !text.isBlank()) {
                    corrections.put(id, text.trim());
                }
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

    private String callAsrApi(String apiKey, String chatUrl, String model,
                               ObjectNode requestBody) throws Exception {
        return AiChatRetry.execute(() -> {
            Request request = new Request.Builder()
                    .url(chatUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(
                            objectMapper.writeValueAsString(requestBody), JSON_MEDIA_TYPE))
                    .build();

            Response response = HttpUtil.sendInterruptible(httpClient, request);
            int code = response.code();
            String body = response.body() != null ? response.body().string() : "";

            if (code != 200) {
                if (code == 429 || code >= 500) {
                    throw new AiChatRetry.HttpRetryableException("HTTP " + code + "：" + truncate(body, 200));
                }
                throw new RuntimeException("ASR 纠错 API 调用失败 [" + code + "]：" + truncate(body, 200));
            }

            JsonNode root = objectMapper.readTree(body);
            String content = root.path("choices").path(0).path("message").path("content").asText("").trim();

            if (content.isEmpty()) {
                throw new AiChatRetry.AiRetryableException("AI 返回空内容");
            }
            if (AiChatRetry.isRefusal(content)) {
                throw new AiChatRetry.AiRetryableException("AI 拒绝回答：" + truncate(content, 100));
            }

            String json = extractJson(content);
            if (json == null) {
                throw new AiChatRetry.AiRetryableException("AI 返回非 JSON：" + truncate(content, 100));
            }

            JsonNode parsed = objectMapper.readTree(json);
            if (!parsed.has("utterances") || !parsed.path("utterances").isArray()) {
                throw new AiChatRetry.AiRetryableException("AI 返回 JSON 缺少 utterances 数组");
            }

            return json;
        }, RETRY_CONFIG);
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

    /**
     * 将 utterances 按字符数分批，每批不超过 BATCH_CHAR_LIMIT。
     * 保证单条超长的 utterance 独立成批（不会跨批切断）。
     */
    private List<List<UtteranceItem>> splitIntoBatches(List<UtteranceItem> items) {
        List<List<UtteranceItem>> batches = new ArrayList<>();
        List<UtteranceItem> currentBatch = new ArrayList<>();
        int currentChars = 0;
        for (UtteranceItem item : items) {
            int itemChars = item.text.length();
            // 单条已超限 → 独自成批
            if (itemChars > BATCH_CHAR_LIMIT) {
                if (!currentBatch.isEmpty()) {
                    batches.add(currentBatch);
                }
                batches.add(List.of(item));
                currentBatch = new ArrayList<>();
                currentChars = 0;
                continue;
            }
            // 加上这条会超限 → 先结算当前批，再开始新批
            if (currentChars + itemChars > BATCH_CHAR_LIMIT && !currentBatch.isEmpty()) {
                batches.add(currentBatch);
                currentBatch = new ArrayList<>();
                currentChars = 0;
            }
            currentBatch.add(item);
            currentChars += itemChars;
        }
        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }
        return batches;
    }

    private record ResolvedConfig(String apiKey, String chatUrl, String model) {
    }
}
