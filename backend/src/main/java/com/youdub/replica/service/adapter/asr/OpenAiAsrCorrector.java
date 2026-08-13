package com.youdub.replica.service.adapter.asr;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** 纠错上下文窗口的字符预算（取当前批次前后句），~4 字符/token ≈ 1000 tokens */
    public static final int CONTEXT_WINDOW_CHARS = 4000;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    private static final AiChatRetry.RetryConfig RETRY_CONFIG = AiChatRetry.RetryConfig.builder().build();
    /** LLM 温度参数，较低值使输出更确定 */
    private static final double TEMPERATURE = 0.1;
    /** 纠错结果文件名 */
    private static final String CORRECTED_FILE = "asr_corrected.json";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    /** 每个批次的最大字符数（英文 ~4 字符/token ≈ 3000 tokens），防止 API 上下文超限 */
    private static final int BATCH_CHAR_LIMIT = 12000;
    /**
     * 每批次最大输出 token。prompt 要求每条修正返回完整句子（非 diff），
     * 165 条批次的完整输出可达数千 token，4096 偏低会触发 finish_reason=length 截断。
     * 16384 对整批全句输出留足余量。
     */
    private static final int MAX_OUTPUT_TOKENS = 16384;
    /** 修正文本与原文的单词重叠率下限，低于该值视为改写而非纠错，丢弃 */
    private static final double OVERLAP_MIN_RATIO = 0.5;

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
        String topic = readTopic(outputDir);
        String systemPrompt = buildSystemPrompt(topic);

        // 将 utterances 按字符数分批处理，避免 API 上下文超限
        List<List<UtteranceItem>> batches = splitIntoBatches(items);
        Map<Integer, String> corrections = new HashMap<>();

        for (int batchIdx = 0; batchIdx < batches.size(); batchIdx++) {
            List<UtteranceItem> batch = batches.get(batchIdx);
            log.info("ASR 纠错批次 {}/{}：{} 条 utterances", batchIdx + 1, batches.size(), batch.size());

            ArrayNode batchUtterances = objectMapper.createArrayNode();
            for (UtteranceItem item : batch) {
                ObjectNode u = objectMapper.createObjectNode();
                u.put("id", item.id);
                u.put("text", item.text);
                batchUtterances.add(u);
            }
            String batchUtterancesJson = objectMapper.writeValueAsString(batchUtterances);

            // 上下文 = 当前批次前后的句子窗口，帮助锚定领域术语（比全文截断更省 token、更贴题）
            String contextWindow = buildContextWindow(items, batch, CONTEXT_WINDOW_CHARS);
            String userPrompt = """
                    Sentences near the ones to correct (context for resolving domain terms):
                    ---
                    """ + contextWindow + """
                    ---

                    Correct the utterances below. Return ONLY the ones you changed, each with its FULL corrected sentence text:
                    """ + batchUtterancesJson;
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

            // 优先解析 diff-only 格式 {"corrections":[...]}，兼容旧 {"utterances":[...]}
            JsonNode correctedUtterances = correctedRoot.path("corrections");
            if (!correctedUtterances.isArray()) {
                correctedUtterances = correctedRoot.path("utterances");
            }
            if (!correctedUtterances.isArray()) {
                throw new RuntimeException("LLM 返回格式错误：缺少 corrections/utterances 数组，实际=" + correctedRoot);
            }
            Map<Integer, String> originals = new HashMap<>();
            for (UtteranceItem item : batch) originals.put(item.id, item.text);
            for (JsonNode cu : correctedUtterances) {
                int id = cu.path("id").asInt(-1);
                String text = cu.path("text").asText("").trim();
                if (id < 0 || text.isBlank()) continue;
                String orig = originals.get(id);
                if (orig == null) {
                    log.warn("ASR 修正 id={} 不在当前批次，忽略", id);
                    continue;
                }
                if (text.length() < orig.length() * 0.8) {
                    log.warn("ASR 修正文本过短，丢弃：'{}' → '{}' ({} vs {} chars)",
                            orig, text, text.length(), orig.length());
                    continue;
                }
                double overlap = wordOverlapRatio(orig, text);
                if (overlap < OVERLAP_MIN_RATIO) {
                    log.warn("ASR 修正内容不重叠，丢弃：'{}' → '{}' (重叠 {}%)",
                            orig, text, Math.round(overlap * 100));
                    continue;
                }
                corrections.put(id, text);
            }

            // 本批次结束后立即打印不一致的纠错结果
            for (UtteranceItem item : batch) {
                String corrected = corrections.get(item.id);
                if (corrected != null && !corrected.equals(item.text)) {
                    log.info("ASR 纠错：'{}' → '{}'", item.text, corrected);
                }
            }
        }
        // 根据纠错结构构建最终的数据，根据id匹配
        ObjectNode fixedRoot = asrRoot.deepCopy();
        ObjectNode resultObj = (ObjectNode) fixedRoot.path("result");
        StringBuilder correctedFullText = new StringBuilder();
        ArrayNode resultUtterances = (ArrayNode) resultObj.path("utterances");
        int itemIdx = 0;
        for (JsonNode u : resultUtterances) {
            String origText = u.path("text").asText("").trim();
            if (origText.isEmpty()) continue;

            String corrected = corrections.get(itemIdx);
            if (corrected != null && !corrected.equals(origText)) {
                ((ObjectNode) u).put("text", corrected);

                // 保存原始文本用于事后评估效果
                ((ObjectNode) u).put("original_text", origText);
            }

            if (!correctedFullText.isEmpty()) correctedFullText.append(" ");
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

    private String buildSystemPrompt(String topic) {
        String topicLine = (topic != null && !topic.isBlank())
                ? "\nVideo topic: " + topic
                : "\nThe video topic is not provided; infer domain terms from the context sentences.";
        return """
                You are a speech recognition correction assistant for a video dubbing pipeline. The video's transcription was produced by an ASR engine that occasionally mishears words into phonetically-similar but wrong ones. Fix ONLY those misrecognitions.
                """ + topicLine + """

                Rules:
                1. Change a word ONLY if the original and the correction SOUND similar (e.g. 'kub ernetes' and 'Kubernetes' sound alike). If they don't sound similar, it is NOT an ASR error.
                2. For domain or technical terms, resolve phonetically-similar variants to the correct spelling, guided by the video topic and the context sentences (e.g. 'intuitorator' → 'IntoIterator' in a Rust tutorial, 'cubelet' → 'kubelet' in a Kubernetes talk). Use your knowledge of the domain.
                3. Keep original grammar, style, punctuation, and sentence structure unchanged. Never rephrase or polish.
                4. Do NOT swap a word just because it makes more sense in context; the change must be phonetically justified.
                5. When unsure, leave the utterance unchanged and omit it from the output.
                6. Do NOT correct words that are already spelled correctly (no capitalization-only or grammar fixes).
                7. CRITICAL: each "text" in your output must be the ENTIRE corrected utterance — the full sentence, identical to the original except for the corrected word(s). NEVER return just the corrected word(s).
                8. The correction is a MINIMAL EDIT: change ONLY the misheard word(s). Do NOT add, remove, reorder, or replace any other words. The corrected utterance must start and end with the same words as the original (unless one of those words is itself the error). NEVER write a new or explanatory sentence.

                Word-level corrections that sound similar (phonetically valid):
                - 'kub ernetes' → 'Kubernetes'
                - 'cubelet' → 'kubelet'
                - 'trade' → 'trait'
                - 'base sixty four' → 'base64'

                Word-level corrections that are phonetically different (do NOT do these):
                - 'holds' → 'dives' (different sound, context-based guess)
                - 'Rusty' → 'Rust' (not a misrecognition)
                - 'come' → 'came' (grammar fix, not ASR)

                NOT a correction (do NOT replace the whole sentence). Original: "And finally, there is a single required method to implement called Intuitor." → CORRECT: "And finally, there is a single required method to implement called IntoIterator." → WRONG: "This just ensures consistency."

                Return ONLY valid JSON (no markdown, no extra text). Include ONLY the utterances you changed. Each entry's "text" is the COMPLETE corrected sentence, e.g. if the original utterance id 5 was "The first line creates an iterator via the intoIterator trade." return:
                {"corrections":[{"id":5,"text":"The first line creates an iterator via the IntoIterator trait."}]}
                If nothing needs correcting, return {"corrections":[]}""";
    }

    private ResolvedConfig resolveConfig() {
        var translate = settingsService.getProviderConfig(OPENAI, AppProperties.Translate.Openai.class);
        var corrector = settingsService.getProviderConfig(OPENAI_ASR_CORRECTOR,
                AppProperties.AsrCorrectorConfig.OpenaiAsrCorrector.class);

        String apiKey = firstNonBlank(corrector.getApiKey(), translate.getApiKey());
        String chatUrl = firstNonBlank(corrector.getChatUrl(), translate.getChatUrl());
        String model = firstNonBlank(corrector.getModel(), translate.getModel());

        if (apiKey.isBlank()) {
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
            JsonNode choice = root.path("choices").path(0);
            String content = choice.path("message").path("content").asText("").trim();

            if (content.isEmpty()) {
                throw new AiChatRetry.AiRetryableException("AI 返回空内容");
            }
            if ("length".equals(choice.path("finish_reason").asText(""))) {
                throw new AiChatRetry.AiRetryableException("AI 输出被 max_tokens 截断 (finish_reason=length)");
            }

            // 先提取并解析 JSON，再做拒绝判定：合法的 corrections JSON 不可能是拒绝，
            // 且 JSON 内嵌的转写文本可能包含 "i'm sorry" 等短语，直接子串匹配会误报
            String json = extractJson(content);
            if (json == null) {
                if (AiChatRetry.isRefusal(content, objectMapper)) {
                    throw new AiChatRetry.AiRetryableException("AI 拒绝回答：" + truncate(content, 100));
                }
                throw new AiChatRetry.AiRetryableException("AI 返回非 JSON：" + truncate(content, 100));
            }

            JsonNode parsed;
            try {
                parsed = objectMapper.readTree(json);
            } catch (JsonProcessingException e) {
                // 截断/损坏的 JSON 按可重试的 AI 响应问题上报，而不是被当成网络错误
                throw new AiChatRetry.AiRetryableException(
                        "AI 返回 JSON 解析失败（可能被截断）：" + truncate(json, 100), e);
            }
            if (!parsed.has("corrections") && !parsed.has("utterances")) {
                if (AiChatRetry.isRefusal(content, objectMapper)) {
                    throw new AiChatRetry.AiRetryableException("AI 拒绝回答：" + truncate(content, 100));
                }
                throw new AiChatRetry.AiRetryableException("AI 返回 JSON 缺少 corrections/utterances 数组");
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

    /**
     * 修正文本与原文的重叠率（小写、忽略标点）。大小写变化（VEC→Vec）和
     * 末尾术语替换（Intuiterator→IntoIterator）不影响命中，整句改写则命中率骤降。
     * <p>
     * 分词策略：拉丁字符按词切分，中日韩统一表意文字（CJK）按单字切分，
     * 避免整句中文被 `[^a-z0-9]+` 误判为单个 token 导致重叠率失真。
     */
    private static double wordOverlapRatio(String original, String corrected) {
        Set<String> correctedTokens = tokenize(corrected);
        List<String> originalTokens = tokenizeList(original);
        int total = originalTokens.size();
        if (total == 0) return 1.0;
        int matched = 0;
        for (String t : originalTokens) {
            if (correctedTokens.contains(t)) matched++;
        }
        return (double) matched / total;
    }

    private static Set<String> tokenize(String text) {
        return new HashSet<>(tokenizeList(text));
    }

    /** 拉丁词按空白/非字母数字切分；CJK 字符逐字输出（无词边界可依赖）。 */
    private static List<String> tokenizeList(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (isLatinTokenChar(c)) {
                latin.append(c);
            } else {
                if (latin.length() > 0) {
                    tokens.add(latin.toString());
                    latin.setLength(0);
                }
                if (isCjk(c)) {
                    tokens.add(String.valueOf(c));
                }
            }
        }
        if (latin.length() > 0) {
            tokens.add(latin.toString());
        }
        return tokens;
    }

    private static boolean isLatinTokenChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || b == Character.UnicodeBlock.HIRAGANA
                || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.HANGUL_SYLLABLES;
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

    /**
     * 读取任务标题（local_info.json），用于给 LLM 锚定领域术语。
     * 文件缺失或解析失败时返回 null，prompt 会退化为"从上下文推断"。
     */
    private String readTopic(Path outputDir) {
        Path localInfo = outputDir.resolve("local_info.json");
        try {
            if (Files.exists(localInfo)) {
                JsonNode root = objectMapper.readTree(Files.readString(localInfo));
                String title = root.path("title").asText("").trim();
                return title.isEmpty() ? null : title;
            }
        } catch (Exception e) {
            log.warn("读取 local_info.json 标题失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 截取当前批次前后句子的上下文窗口（双向累积，上限 maxChars）。
     * 只取紧邻的句子，比全文截断更省 token、更贴题。
     */
    private static String buildContextWindow(List<UtteranceItem> items, List<UtteranceItem> batch, int maxChars) {
        int minId = batch.get(0).id();
        int maxId = batch.get(batch.size() - 1).id();
        StringBuilder sb = new StringBuilder();
        int chars = 0;
        for (int i = minId - 1; i >= 0 && chars < maxChars; i--) {
            String t = items.get(i).text();
            if (chars + t.length() > maxChars) break;
            sb.insert(0, t + "\n");
            chars += t.length();
        }
        for (int i = maxId + 1; i < items.size() && chars < maxChars; i++) {
            String t = items.get(i).text();
            if (chars + t.length() > maxChars) break;
            sb.append(t).append("\n");
            chars += t.length();
        }
        return sb.toString().trim();
    }

    private record ResolvedConfig(String apiKey, String chatUrl, String model) {
    }
}
