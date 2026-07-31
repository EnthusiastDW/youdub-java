# ASR 语音识别架构

> 本文档描述 YouDub Replica 中语音识别（ASR）模块的完整架构：从 Spring Boot 后端适配器到 ONNX Runtime Java 模型推理的全程数据流，以及胶水代码与 Whisper ONNX 模型的配合方式。

---

## 目录

1. [架构总览](#1-架构总览)
2. [模块依赖关系](#2-模块依赖关系)
3. [外部胶水代码层](#3-外部胶水代码层)
4. [Whisper 模型层](#4-whisper-模型层)
5. [音频预处理 — MelSpectrogram](#5-音频预处理--melspectrogram)
6. [模型配置 — WhisperConfig](#6-模型配置--whisperconfig)
7. [分词器 — WhisperTokenizer](#7-分词器--whispertokenizer)
8. [解码策略详解](#8-解码策略详解)
9. [长音频分片机制](#9-长音频分片机制)
10. [时间戳处理](#10-时间戳处理)
11. [数据流全景图](#11-数据流全景图)
12. [配置与扩展](#12-配置与扩展)

---

## 1. 架构总览

```
┌──────────────────────────────────────────────────────────────────┐
│  Spring Boot Backend                                            │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  OnnxWhisperRecognizer          (SpeechRecognizer 接口)     ││
│  │  ├── 模型生命周期管理（懒加载、语言切换、自动下载）         ││
│  │  ├── WAV 读取 → WhisperModel 调用                         ││
│  │  └── ASR JSON 构建（utterance 分组、单词时间戳）          ││
│  └──────────────────┬──────────────────────────────────────────┘│
│                     │ 依赖注入（Site.dengwei.onnxruntime）       │
│                     ▼                                           │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  onnxruntime 模块                                           ││
│  │  ┌─────────────────────────────────────────────────────────┐││
│  │  │  WhisperModel              (核心推理引擎)               │││
│  │  │  ├── transcribeChunked()   (长音频分片转录)            │││
│  │  │  ├── transcribeDetailed()  (单段 ≤30s 转录)           │││
│  │  │  ├── runEncoder()          → encoder_model.onnx        │││
│  │  │  └── runDecoder()          → decoder_model.onnx        │││
│  │  └──────────┬──────────────────────────────────────────────┘││
│  │             │                                               ││
│  │  ┌──────────▼──────────────────────────────────────────────┐││
│  │  │  MelSpectrogram      (log-mel 频谱预处理)              │││
│  │  │  WhisperConfig       (config.json 解析)                │││
│  │  │  WhisperTokenizer    (BPE 分词器)                      │││
│  │  │  WhisperModels       (模型自动下载)                    │││
│  │  └─────────────────────────────────────────────────────────┘││
│  └─────────────────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                   ONNX Runtime (Java)
              ┌───────────────────────────┐
              │  encoder_model.onnx       │
              │  decoder_model_fp16.onnx  │
              │  (HuggingFace onnx-community)│
              └───────────────────────────┘
```

### 设计原则

- **语言边界清晰**：Java 处理业务逻辑、管线编排、文件管理；ONNX 模型以 ONNX Runtime Java API 在 Java 进程内推理，不依赖 Python 微服务
- **适配器隔离**：`OnnxWhisperRecognizer` 实现 `SpeechRecognizer` 接口，可无侵入替换为其他 ASR 实现（如 OpenAI Whisper API、whisper.cpp）
- **模型与代码分离**：模型配置从 `config.json` 动态读取，不硬编码 token ID，兼容不同 Whisper 模型变体

---

## 2. 模块依赖关系

```
backend (com.youdub.replica)
  └── SpeechRecognizer (接口)
        └── OnnxWhisperRecognizer
              │
              ├── site.dengwei.onnxruntime.whisper.WhisperModel
              ├── site.dengwei.onnxruntime.audio.WavAudio
              └── com.fasterxml.jackson.databind.ObjectMapper

onnxruntime (site.dengwei.onnxruntime)
  ├── whisper/
  │     ├── WhisperModel.java          (核心推理引擎)
  │     ├── WhisperConfig.java         (模型配置)
  │     ├── WhisperTokenizer.java      (BPE 分词器)
  │     ├── WhisperModels.java         (模型下载管理)
  │     └── MelSpectrogram.java        (log-mel 频谱)
  └── audio/
        └── WavAudio.java              (WAV 读写)
```

### 外部依赖

| 依赖 | 用途 |
|------|------|
| `com.microsoft.onnxruntime:onnxruntime` | ONNX Runtime Java API，加载和运行 ONNX 模型 |
| `org.jtransforms:jtransforms` | FFT（MelSpectrogram 的 STFT 计算） |
| `org.json:json` | JSON 解析（config.json、vocab.json） |
| `org.slf4j:slf4j-api` | 日志 |

---

## 3. 外部胶水代码层

### 3.1 `SpeechRecognizer` 接口

```java
public interface SpeechRecognizer {
    void transcribe(Task task, Path audioPath, Path outputDir, String language) throws Exception;
}
```

单一方法契约：给定 Task、音频路径、输出目录、语言代码，执行转录并将结果写入 `outputDir/asr.json`。

### 3.2 `OnnxWhisperRecognizer` — 胶水对接

**文件**：`backend/.../adapter/asr/OnnxWhisperRecognizer.java`

这是 Spring Boot 后端与 `onnxruntime` 模块的胶水层，职责：

#### 模型生命周期管理

```java
// @Component(WHISPER_ONNX) — 按 bean 名称注入
public class OnnxWhisperRecognizer implements SpeechRecognizer {

    private volatile WhisperModel model;     // 懒加载，线程安全
    private String currentLanguage;          // 跟踪当前语言

    private WhisperModel getOrCreateModel(String language) {
        // 1. 语言没变且模型已加载 → 复用
        if (model != null && lang.equals(currentLanguage)) return model;

        // 2. 语言变了 → 关闭旧模型，创建新模型
        synchronized (initLock) { ... }

        // 3. 从环境变量/默认值获取模型变体
        String modelVariant = System.getenv("WHISPER_ONNX_MODEL");
        // 默认 whisper-base，可选 whisper-small / whisper-medium / whisper-large-v3

        // 4. 自动下载并加载
        Path modelDir = Paths.get("data", "whisper-models", modelVariant);
        this.model = WhisperModel.loadOrDownload(modelDir, lang);

        // 语言为 null/blank/"auto" 时启用自动检测
    }
}
```

关键设计点：
- **懒加载**：第一次调用 `transcribe()` 时才初始化模型，避免启动加载
- **语言切换**：切换语言时自动关闭旧模型创建新模型
- **线程安全**：`volatile` + `synchronized` 双检锁
- **自动下载**：首次调用时若模型文件不存在，`WhisperModel.loadOrDownload()` 自动从 HuggingFace 下载

#### 转录流程

```java
public void transcribe(Task task, Path audioPath, Path outputDir, String language) {
    // 1. 跳过已完成的
    if (Files.exists(asrFile)) return;

    // 2. 读取音频（任意采样率，内部由 MelSpectrogram 重采样到 16kHz）
    WavAudio wav = WavAudio.read(audioPath);
    float[] audio = wav.channels() > 1 ? wav.toMono().samples() : wav.samples();

    // 3. 获取/创建模型
    WhisperModel whisper = getOrCreateModel(language);

    // 4. 转录（自动分片，支持任意时长）
    TranscriptionResult result = whisper.transcribeChunked(audio, wav.sampleRate());

    // 5. 构建 ASR JSON
    ObjectNode json = buildAsrJson(result, audioPath, durationMs);
    Files.writeString(asrFile, objectMapper.writeValueAsString(json));
}
```

#### ASR JSON 构建

`OnnxWhisperRecognizer` 将 `TranscriptionResult`（来自 WhisperModel 的内部表示）转换为标准化的 ASR JSON 格式：

```json
{
  "audio_info": {
    "source": "/path/to/audio.wav",
    "duration": 655000
  },
  "result": {
    "text": "full transcription text...",
    "utterances": [
      {
        "text": "sentence text",
        "start_time": 0,
        "end_time": 3500,
        "speaker": "1",
        "words": [
          { "text": "Hello",  "start_time": 0,    "end_time": 200 },
          { "text": "world",  "start_time": 200,  "end_time": 500 }
        ]
      }
    ]
  }
}
```

分组规则（`buildAsrJson` 方法）：
- 遍历所有单词
- 如果当前单词距上一个单词的间隔 ≤500ms 且上一个单词无句尾标点 → 归入同一个 utterance
- 否则 → 新起一个 utterance

---

## 4. Whisper 模型层

### 4.1 WhisperModel — 核心推理引擎

**文件**：`onnxruntime/.../whisper/WhisperModel.java`（1646 行）

该类加载 Whisper encoder + decoder ONNX 模型，封装从音频到文本的完整推理流程。

#### 模型目录结构

```
model-dir/
  ├── config.json                 — 模型配置（架构参数、token ID、suppress 规则）
  ├── generation_config.json      — 语言映射（lang_to_id），可选
  ├── encoder_model.onnx          — 编码器：mel → hidden states
  ├── decoder_model_fp16.onnx     — 解码器：自回归文本生成（fp16，优先）
  ├── decoder_model.onnx          — 解码器（fp32 回退）
  ├── vocab.json                  — BPE 词表
  └── merges.txt                  — BPE 合并规则
```

#### ONNX 会话发现

构造器自动从 ONNX 模型元数据发现输入/输出名称：

```java
this.encoderInputName   = discoverInputName(encoderSession, "input_features", "mel");
this.encoderOutputName  = discoverOutputName(encoderSession, "last_hidden_state");
this.decoderInputIdsName = discoverInputName(decoderSession, "input_ids");
this.decoderEncoderStateName = discoverInputName(decoderSession, "encoder_hidden_states");
this.decoderLogitsName  = discoverOutputName(decoderSession, "logits");
```

同时自动发现 KV-cache 接口（`past_key_values.*` / `present.*`），在 `decoder_model_merged.onnx`（含 KV-cache 分支的模型）上启用增量推理。

#### 公开 API 层次

| 方法 | 音频长度 | 返回 | 说明 |
|------|---------|------|------|
| `transcribe()` | ≤30s | `String` | 纯文本，无时间戳 |
| `transcribeDetailed()` | ≤30s | `TranscriptionResult` | 带单词级时间戳的分段结果 |
| `transcribeChunked()` | 任意 | `TranscriptionResult` | 自动分片，等效于 faster-whisper 的长音频支持 |

#### `transcribeDetailed()` 完整流程

```
transcribeDetailed(audio, sampleRate)
  │
  ├── MelSpectrogram.compute(audio, sampleRate)
  │     ├── resample → 16kHz（若需要）
  │     ├── STFT (Hann, fft=400, hop=160) → 幅度平方 [201][frames]
  │     ├── 80 通道 Mel 滤波器组 → [80][frames]
  │     ├── log10 + 动态范围裁剪(80dB) + 归一化 → [80][3000]
  │     └── pad/trim 到 3000 帧（=30s @ 100fps）
  │
  ├── runEncoder(mel)  → encoder_model.onnx 推理
  │     └── 输出 [1, 1500, d_model] hidden states
  │
  ├── ensureLanguageDetected(encoderOutput)
  │     └── 首次运行 auto-detect 模式时：送 [SOT] token 到 decoder，
  │         从 logits 中取 langTokens 里概率最高的语言 token
  │
  ├── runDecoder(encoderState)  → decoder_model.onnx 推理
  │     ├── 温度回退循环（0.0 → 0.2 → 0.4 → 0.6 → 0.8 → 1.0）
  │     ├── 每温度运行贪心/beam search/KV-cache 解码
  │     ├── 质量评分（非标点 token 占比），达标则提前跳出
  │     └── 返回最佳温度下的 token 序列
  │
  └── parseSegments(tokens, durationMs)
        ├── 按时间戳 token 分割文本 → [Segment]
        ├── 跳过纯标点段
        ├── 按字符比例分配单词起止时间
        └── 返回 TranscriptionResult(fullText, segments)
```

### 4.2 模型加载与下载

**文件**：`WhisperModels.java`

```java
public static void ensureFiles(Path dir, String modelName) throws IOException {
    // 模型名从目录名推断，从 HuggingFace onnx-community/{modelName} 下载
    // 文件列表：
    //   config.json, generation_config.json
    //   encoder_model.onnx, decoder_model.onnx, decoder_model_merged.onnx
    //   vocab.json, merges.txt
}
```

下载支持回退仓库（`REPO_FALLBACKS` 映射旧仓库名到新仓库名），适应 HuggingFace 上的仓库重命名。

---

## 5. 音频预处理 — MelSpectrogram

**文件**：`MelSpectrogram.java`

严格对齐 OpenAI Whisper 的 `log_mel_spectrogram()` 实现。

### 处理管线

```
float[] audio (任意采样率)
  │
  ├── resample → 16kHz（线性插值）
  │
  ├── STFT
  │     ├── center=True → 前后 pad N_FFT/2 零样本
  │     ├── Hann 窗（N_FFT=400）
  │     ├── hop length=160（=10ms @ 16kHz）
  │     ├── JTransforms DoubleFFT_1D
  │     └── 输出：幅度平方 [freqBins=201][frames]
  │
  ├── 80 通道 Mel 滤波器组
  │     ├── 频率范围：0Hz ~ 8kHz（Nyquist）
  │     ├── Mel 刻度映射：htzToMel = 1127 * ln(1 + hz/700)
  │     ├── Slaney 归一化
  │     └── 输出：[80][frames]
  │
  └── log + 裁剪
        ├── log10(clip(min=1e-10))
        ├── 动态范围裁剪：maxVal - 8.0（80dB）
        └── 归一化：(logMel + 4.0) / 4.0 → 值域 ≈ [0, 1.5]
```

### 关键参数

| 参数 | 值 | 说明 |
|------|----|------|
| `SAMPLE_RATE` | 16000 | 输入音频目标采样率 |
| `N_FFT` | 400 | FFT 窗口大小（25ms @ 16kHz） |
| `HOP_LENGTH` | 160 | 帧移（10ms @ 16kHz） |
| `N_MELS` | 80 | Mel 通道数 |

### `normalizeInPlace()` — 长音频标准化

对长音频按 30s 窗口逐窗口做 z-score 标准化（减去均值除以标准差），消除窗口间音量差异对模型的影响。

---

## 6. 模型配置 — WhisperConfig

**文件**：`WhisperConfig.java`

从 HuggingFace Whisper ONNX 模型的 `config.json` 解析所有架构参数和特殊 token ID。

### 解析的配置项

| 配置 | 来源 | 用途 |
|------|------|------|
| `num_mel_bins` | config.json | Mel 通道数验证 |
| `d_model` | config.json | Encoder hidden size |
| `encoder_layers / decoder_layers` | config.json | 模型深度 |
| `encoder_attention_heads / decoder_attention_heads` | config.json | 注意力头数 |
| `vocab_size` | config.json | 词表大小（用于 logits 索引） |
| `max_source_positions / max_target_positions` | config.json | 最大序列长度 |
| `forced_decoder_ids` | config.json | 强制 decoder token 序列 |
| `begin_suppress_tokens` | config.json | 第一步禁止生成的 token |
| `suppress_tokens` | config.json | 任何步禁止生成的 token |
| `eos_token_id` | config.json | 结束 token ID |

### 特殊 Token ID 推导（⚠️ 关键）

**`forced_decoder_ids`** 是理解 Whisper decoder 行为的核心。它定义了 decoder 在固定位置必须生成哪些 token：

```
.en 模型（whisper-tiny.en, whisper-base.en 等）：
  forcedDecoderIds = [[1, 50362]]
  → 位置 1 强制为 50362（transcribe token）
  → noTimestampsToken = 50363（= 50362 + 1）
  → 初始 token 序列: [SOT(50257), 50362, 50363]

多语言模型（whisper-base, whisper-small 等）：
  forcedDecoderIds = [[1, LANG], [2, TASK]]
  → 位置 1 为语言 token（如 50259=en, 50260=zh）
  → 位置 2 为任务 token（如 50359=transcribe, 50358=translate）
  → noTimestampsToken = transcribeToken + 1
  → 初始 token 序列: [SOT(50257), LANG, TASK, noTimestamps]
```

### 语言切换

多语言模型通过 `overrideLanguage(int langTokenId)` 替换 `forced_decoder_ids[0][1]` 来切换目标语言：

```java
public void overrideLanguage(int langTokenId) {
    if (forcedDecoderIds.length < 2) {
        // .en 模型无法切换语言
        return;
    }
    int old = forcedDecoderIds[0][1];
    forcedDecoderIds[0][1] = langTokenId;
}
```

### 初始 Token 序列构建

```java
// 带 no_timestamps（标准转录）：
initialDecoderTokens() → [SOT, ...forcedIds, noTimestampsToken]

// 不带 no_timestamps（时间戳解码）：
initialDecoderTokensWithTimestamps() → [SOT, ...forcedIds]
// 模型会自然输出时间戳 token

// 最终序列（含 initial prompt）：
buildInitialTokens(baseTokens, promptTokens)
```

### Initial Prompt

对中文等模型天然缺少标点的语言，可添加简短 initial prompt 引导模型输出标点：

```java
Map<String, String> INITIAL_PROMPTS = Map.of("zh", "请添加标点符号");
```

⚠️ **风险**：Prompt 必须极简短（≤5 词）且不含具体内容词，否则会泄漏到输出中产生幻觉。

---

## 7. 分词器 — WhisperTokenizer

**文件**：`WhisperTokenizer.java`

实现 GPT-2 BPE 分词器（与 Whisper 共用同一词表）。

### 编码流程

```
text → UTF-8 bytes → byte-to-unicode 映射
     → WORD_PATTERN 正则分词
     → BPE 合并（按 merges.txt 的 rank 优先级合并字符对）
     → vocab.json 查找 → token IDs
```

### 解码流程

```
token IDs → vocab.json 反向查找 → unicode 字符串
     → unicode-to-byte 映射 → UTF-8 bytes → text
```

### 字节映射

GPT-2 的特殊字节到 Unicode 映射：可见 ASCII 和 Latin-1 映射到自身，其余 0-255 字节映射到 256+ 的 Unicode 码位。

---

## 8. 解码策略详解

### 8.1 解码入口：`runDecoder()`

温度回退循环 + 自动质量评分：

```java
float[] TEMPERATURES = {0.0f, 0.2f, 0.4f, 0.6f, 0.8f, 1.0f};

private int[] runDecoder(float[] encoderState) {
    int[] bestTokens = null;
    float bestScore = Float.NEGATIVE_INFINITY;

    for (float temp : TEMPERATURES) {
        int[] tokens;
        if (BEAM_SIZE > 1)           // beam search
            tokens = runDecoderBeamSearch(encoderState, BEAM_SIZE, temp);
        else if (usePastCache)       // KV-cache 增量推理
            tokens = runDecoderCache(encoderState);
        else                         // 全量贪心
            tokens = runDecoderFull(encoderState);

        float quality = scoreTranscriptionQuality(tokens);
        if (quality >= 0.7f) break;  // 质量达标，提前跳出
    }
    return bestTokens;
}
```

### 8.2 贪心解码：`runDecoderFull()`

最简单的策略，每步从 logits 中选概率最高的 token：

```
输入: initialTokens + encoderState

循环:
  1. 拼接所有历史 token → decoder ONNX
  2. 从输出 logits 取最后一步
  3. 应用 suppress 规则 + 重复惩罚
  4. 选最高分 token
  5. 若为 EOT 或超出最大长度 → 退出
  6. token 追加到序列

输出: tokens（不含 initialTokens 和 EOT）
```

**重复惩罚**：最近 20 个 token 的 score 除以 1.2（`REPEAT_PENALTY`），抑制循环重复。

### 8.3 KV-cache 增量解码：`runDecoderCache()`

利用 `decoder_model_merged.onnx` 的 KV-cache 输出，避免每步重新计算全部历史：

```
第一步: 送完整 initialTokens + 空 past tensors (shape [1, heads, 0, dim])
         → 获取 logits + present KV values

后续步: 只送 1 个新 token + 上一步的 present KV values
         → 获取 logits + 新的 present KV values
```

**优势**：从 O(n²) 降为 O(n)，大幅减少计算量。
**前提**：模型必须是 `decoder_model_merged.onnx`（含 `use_cache_branch` 输入和 `present.*` 输出）。

### 8.4 Beam Search 解码：`runDecoderBeamSearch()`

```
参数: beamSize=5, temperature=0.0~1.0

初始化: 1 个 beam 含 initialTokens，score=0

每步:
  ┌── for each unfinished beam ──────────────────────┐
  │  1. computeLogits(beam_tokens) → logits           │
  │  2. topK(logits, beamSize) → 候选 token 列表       │
  │  3. 每个候选: newScore = beamScore + logProb(token)│
  │  4. 添加到全局候选列表                            │
  └──────────────────────────────────────────────────┘
  全局排序 → 取 top beamSize 个 → 形成新 beam 列表
  若全部 beam 已结束 → 退出

选最优 beam:
  ┌── 优先选已结束（含 EOT）的 beam 中分数最高的
  └── 若无已结束 beam → 选分数最高的 beam
```

### 8.5 温度回退与质量评分

**动机**：贪心解码（temperature=0.0）可能陷入次优解，增加温度引入随机性可能跳出局部最优。

**质量评分** `scoreTranscriptionQuality()`：

```java
// 计算非标点 token 的占比
quality = meaningfulTokens / totalTokens  // [0, 1]
```

- quality ≥ 0.7 → 认定为优质转录，不再尝试更高温度
- 取所有温度中 quality 最高的结果

### 8.6 Token 选择逻辑：`getLogitsToken()`

```java
private int getLogitsToken(OrtSession.Result result, int seqLen,
                           Collection<Integer> recentTokens, float repeatPenalty) {
    // 1. 从 logits tensor 中提取最后一步
    // 2. 遍历全部 vocabSize 个 token
    // 3. 跳过 begin_suppress_tokens（第一步）和 suppress_tokens（所有步）
    // 4. 对 recentTokens 中的 token 应用重复惩罚（score /= penalty）
    // 5. 返回最高分 token
}
```

**suppress token 来源**：直接从 `config.json` 解析，不同模型可能不同。

---

## 9. 长音频分片机制

### 9.1 入口：`transcribeChunked()`

```
transcribeChunked(audio, sampleRate)
  │
  ├── ≤30s → transcribeDetailed() 直接处理
  │
  └── >30s → 分片处理
        │
        ├── detectSilenceRegions() → 能量检测找 ≥2s 静音段
        │
        ├── buildSilenceChunks() → 按静音中点分割
        │     └── 回退：固定 30s 窗口（5s 重叠）
        │
        ├── 串行转录每个分片（各调 transcribeDetailed）
        │     └── 二分片间 reloadDecoderSession() 释放 native 内存
        │
        ├── mergeSegments() → 按时间戳去重合并
        │
        ├── filterGarbageSegments() → 过滤纯标点/重复/不完整段
        │
        └── mergeFragments() → 合并 ≤3 词的短碎片
```

### 9.2 静音检测

```
SILENCE_MIN_SEC = 2.0s    // 最小静音时长
CHUNK_OVERLAP_SEC = 2.0s  // 分片重叠
MIN_CHUNK_SEC = 5.0s      // 最小分片

detectSilenceRegions():
  ├── 10ms 帧 RMS 能量检测
  ├── 自适应阈值（最低 20% 帧均值 × 2.5）
  └── 返回 [startSample, endSample] 列表

buildSilenceChunks():
  ├── 取每个静音区域中点作为 split point
  ├── 跳过距两端不足 60s 的 split point
  ├── split point ± 2s overlap → 分片边界
  ├── 不足 5s 的分片与相邻合并
  └── 无有效静音点 → 返回 false（触发固定窗口回退）
```

### 9.3 结果合并与后处理

**`mergeSegments()`**：按分片偏移调整时间戳，跳过 `coveredUntilMs` 之前的单词（去重）。

**`filterGarbageSegments()`**：
- 单单词纯标点段 → 删除（模型静音处偶发输出 "."）
- 与之前段文本相同且时间接近 → 去重
- 末尾不完整段（无句尾标点） → 删除

**`mergeFragments()`**：≤3 词且无句尾标点的段并入前一段。

---

## 10. 时间戳处理

### 10.1 时间戳解码

Whisper 模型使用特殊的时间戳 token 表示时间边界：

```
时间戳 token ID 范围:
  firstTs = noTimestampsTokenId + 1
  lastTs  = vocabSize - 1

时间 → token 转换:
  token = firstTs + round(seconds / 30.0 * (vocabSize - firstTs))
```

### 10.2 分段解析：`parseSegments()`

```
token 序列遍历:
  跳过时间戳 token → 记录 textStart
  读取文本 token → 记录 textEnd
  前后时间戳 token → 解码为 startSec / endSec
  文本 token → decode → 单词
  单词按字符比例分配起止时间

无时间戳输出时的回退:
  若解码器用了 no_timestamps token（标准转录），
  时间戳通过前后时间戳 token 确定：
  - 段首前一个 token 是时间戳 → 解码为 start
  - 段尾后一个 token 是时间戳 → 解码为 end
  - 否则 → start=0, end=30s（整个音频）
```

---

## 11. 数据流全景图

### 单段音频（≤30s）

```
                         OnnxWhisperRecognizer
                              │
                    WavAudio.read(audioPath)
                    float[] audio + sampleRate
                              │
                              ▼
                    WhisperModel.transcribeDetailed()
                              │
                    ┌─────────┴──────────┐
                    ▼                    ▼
           MelSpectrogram.compute()   WhisperConfig
                    │                    │
                    ▼                    ▼
           float[][] mel [80][3000]    config.json
                    │
                    ▼
           runEncoder(mel)
                    │
                    ▼
           float[] encoderState [1500×d_model]
                    │
                    ▼
           runDecoder(encoderState)
              ┌─────┼─────┐
              │     │     │
              ▼     ▼     ▼
         greedy  cache  beam
              │     │     │
              └─────┼─────┘
                    ▼
           int[] tokens (不含 init/EOT)
                    │
                    ▼
           parseSegments(tokens)
                    │
                    ▼
           TranscriptionResult
              ├── fullText
              └── segments[].words[]
                    │
                    ▼
            OnnxWhisperRecognizer
                    │
                    ▼
            buildAsrJson() → asr.json
```

### 长音频（>30s）

```
transcribeChunked(audio, sampleRate)
                    │
         detectSilenceRegions()
                    │
         buildSilenceChunks() / 固定窗口
                    │
         ┌─────┬─────┼─────┬─────┐
         │     │     │     │     │
         ▼     ▼     ▼     ▼     ▼
      chunk[0] chunk[1] ... chunk[N-1]
         │     │     │     │     │
         │     └──reloadDecoderSession()──┐
         ▼              ▼                 ▼
  transcribeDetailed  transcribeDetailed  ...
         │              │
         ▼              ▼
   chunkResults[0]   chunkResults[1]  ...
         │              │
         └──────┬───────┘
                ▼
          mergeSegments()
                │
          filterGarbageSegments()
                │
          mergeFragments()
                │
                ▼
          TranscriptionResult (跨分片合并)
```

---

## 12. 配置与扩展

### 模型变体选择

通过环境变量 `WHISPER_ONNX_MODEL` 指定，默认 `whisper-base`：

| 环境变量值 | 模型 | 特点 |
|-----------|------|------|
| `whisper-tiny.en` | tiny（英文专用） | 最快，~39M 参数 |
| `whisper-base` | base（多语言） | 默认，~74M 参数 |
| `whisper-small` | small | ~244M 参数 |
| `whisper-medium` | medium | ~769M 参数 |
| `whisper-large-v3` | large-v3 | 最准确，~1.5B 参数 |

所有模型自动从 HuggingFace [`onnx-community/{modelName}`](https://huggingface.co/onnx-community) 下载。

### 语言设置

| 调用方式 | 行为 |
|---------|------|
| `language="en"` | 强制英文，跳过语言检测 |
| `language="zh"` | 强制中文，添加 initial prompt "请添加标点符号" |
| `language=null/"auto"` | 自动检测语言（首次转录时从 encoder 输出推断） |

### 扩展：添加新的 ASR 实现

实现 `SpeechRecognizer` 接口，注册为 Spring Bean：

```java
@Component("my-custom-asr")
public class MyAsrRecognizer implements SpeechRecognizer {
    public void transcribe(Task task, Path audioPath, Path outputDir, String language) {
        // 自定义 ASR 实现
    }
}
```

在配置中切换到新实现。

### 已知限制

- **GPU 加速**：当前使用 `onnxruntime`（CPU 版），未集成 `onnxruntime-gpu`。可通过替换 Maven 依赖和配置 CUDA provider 启用 GPU 推理
- **模型内存**：decoder 的 ONNX 会话在长音频分片转录后会累积 native 内存，`reloadDecoderSession()` 通过重建 session 释放
- **分片并行**：当前分片串行执行，可改为线程池并行以利用多核 CPU/GPU
