package site.dengwei.onnxruntime.whisper;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * GPT-2 BPE 分词器（与 Whisper 共用）。
 * <p>从 {@code vocab.json} + {@code merges.txt} 加载词表和合并规则，
 * 支持 {@link #encode(String)} 和 {@link #decode(int[])}。</p>
 */
public final class WhisperTokenizer {

    private static final Logger log = LoggerFactory.getLogger(WhisperTokenizer.class);

    // 编码 / 解码表
    private final Map<String, Integer> encoder;
    private final Map<Integer, String> decoder;
    // BPE 合并优先级 (rank 越小优先级越高)
    private final Map<String, Integer> bpeRanks;
    // 缓存
    private final Map<String, List<String>> cache;

    // GPT-2 字节 ↔ Unicode 映射
    private final Map<Byte, Character> byteEncoder;
    private final Map<Character, Byte> byteDecoder;

    private static final Pattern WORD_PATTERN = Pattern.compile("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+");

    /**
     * @param modelDir 包含 {@code vocab.json} 和 {@code merges.txt} 的目录
     */
    public WhisperTokenizer(Path modelDir) {
        this.encoder = loadEncoder(modelDir.resolve("vocab.json"));
        this.decoder = new HashMap<>(encoder.size());
        for (var entry : encoder.entrySet()) {
            decoder.put(entry.getValue(), entry.getKey());
        }

        this.bpeRanks = loadMerges(modelDir.resolve("merges.txt"));
        this.cache = new HashMap<>();

        var mapping = buildByteEncoder();
        this.byteEncoder = mapping.enc;
        this.byteDecoder = mapping.dec;

        log.info("WhisperTokenizer 加载: vocab={} merges={}", encoder.size(), bpeRanks.size());
    }

    // ──────────────────────────── 公开 API ────────────────────────────

    /** 将文本编码为 token ID 序列。 */
    public int[] encode(String text) {
        List<Integer> tokens = new ArrayList<>();
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(byteEncoder.get(b));
        }
        String encoded = sb.toString();

        java.util.regex.Matcher matcher = WORD_PATTERN.matcher(encoded);
        while (matcher.find()) {
            String word = matcher.group();
            List<String> bpeTokens = bpe(word);
            for (String t : bpeTokens) {
                Integer id = encoder.get(t);
                if (id != null) {
                    tokens.add(id);
                }
            }
        }
        return tokens.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 将 token ID 序列解码为文本。 */
    public String decode(int[] tokens) {
        StringBuilder sb = new StringBuilder();
        for (int id : tokens) {
            String s = decoder.get(id);
            if (s != null) {
                sb.append(s);
            }
        }
        byte[] bytes = new byte[sb.length()];
        for (int i = 0; i < sb.length(); i++) {
            Character c = sb.charAt(i);
            Byte b = byteDecoder.get(c);
            bytes[i] = b != null ? b : (byte) c.charValue();
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 查询 token ID 对应的字符串表示（用于特殊 token 识别）。 */
    public String tokenToString(int id) {
        return decoder.getOrDefault(id, "<unknown:" + id + ">");
    }

    /** 查询字符串对应的 token ID。 */
    public Integer stringToToken(String s) {
        return encoder.get(s);
    }

    // ──────────────────────────── BPE ────────────────────────────

    private List<String> bpe(String word) {
        if (cache.containsKey(word)) {
            return cache.get(word);
        }

        // 初始：每个字符作为一个 token
        List<String> symbols = new ArrayList<>();
        for (int i = 0; i < word.length(); i++) {
            symbols.add(String.valueOf(word.charAt(i)));
        }

        while (symbols.size() > 1) {
            // 找到 rank 最低的可合并 pair
            int bestRank = Integer.MAX_VALUE;
            int bestIdx = -1;
            for (int i = 0; i < symbols.size() - 1; i++) {
                String pair = symbols.get(i) + " " + symbols.get(i + 1);
                Integer rank = bpeRanks.get(pair);
                if (rank != null && rank < bestRank) {
                    bestRank = rank;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) break; // 没有可合并的 pair

            String merged = symbols.get(bestIdx) + symbols.get(bestIdx + 1);
            List<String> next = new ArrayList<>(symbols.size() - 1);
            for (int i = 0; i < symbols.size(); i++) {
                if (i == bestIdx) {
                    next.add(merged);
                    i++; // 跳过下一个
                } else {
                    next.add(symbols.get(i));
                }
            }
            symbols = next;
        }

        cache.put(word, symbols);
        return symbols;
    }

    // ──────────────────────────── 加载词表 / 合并规则 ────────────────────────────

    private static Map<String, Integer> loadEncoder(Path path) {
        try {
            String content = Files.readString(path);
            JSONObject json = new JSONObject(content);
            Map<String, Integer> map = new LinkedHashMap<>();
            for (String key : json.keySet()) {
                map.put(key, json.getInt(key));
            }
            return map;
        } catch (IOException e) {
            throw new RuntimeException("加载 vocab.json 失败: " + path, e);
        }
    }

    private static Map<String, Integer> loadMerges(Path path) {
        try {
            List<String> lines = Files.readAllLines(path);
            Map<String, Integer> ranks = new LinkedHashMap<>();
            int idx = 0;
            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#version")) continue;
                ranks.put(line.trim(), idx++);
            }
            return ranks;
        } catch (IOException e) {
            throw new RuntimeException("加载 merges.txt 失败: " + path, e);
        }
    }

    // ──────────────────────────── 字节映射 ────────────────────────────

    /**
     * GPT-2 字节到 Unicode 字符的映射。
     * <p>可见 ASCII 和 Latin-1 字符映射到自身，其余字节映射到 256+ 的范围。</p>
     */
    private static ByteMapping buildByteEncoder() {
        List<Integer> bs = new ArrayList<>();
        List<Integer> cs = new ArrayList<>();
        // 可见 ASCII + Latin-1 字符映射到自身
        for (int i = '!'; i <= '~'; i++) { bs.add(i); cs.add(i); }
        for (int i = 0xA1; i <= 0xBF; i++) { bs.add(i); cs.add(i); }
        // 剩余字节映射到 256+ 范围
        int n = 0;
        for (int b = 0; b < 256; b++) {
            if (!bs.contains(b)) {
                bs.add(b);
                cs.add(256 + n);
                n++;
            }
        }

        Map<Byte, Character> enc = new HashMap<>();
        Map<Character, Byte> dec = new HashMap<>();
        for (int i = 0; i < bs.size(); i++) {
            enc.put((byte) (int) bs.get(i), (char) (int) cs.get(i));
            dec.put((char) (int) cs.get(i), (byte) (int) bs.get(i));
        }
        return new ByteMapping(enc, dec);
    }

    private record ByteMapping(Map<Byte, Character> enc, Map<Character, Byte> dec) {}
}
