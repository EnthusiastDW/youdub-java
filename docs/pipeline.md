# 管线阶段文档

## 会话目录结构

```
{sessionDir}/
├── media/                          # 媒体文件
│   ├── video_source.mp4            # 原始视频
│   ├── audio_vocals.wav            # 分离后的人声
│   └── audio_bgm.wav               # 分离后的背景音乐
├── metadata/                       # 元数据
│   ├── ytdlp_info.json             # 下载元数据
│   ├── asr.json                    # ASR 识别结果
│   ├── asr_corrected.json          # ASR 纠错结果（可选，asr_correct 阶段生成）
│   ├── asr_fixed.json              # ASR 时间修正结果
│   ├── translation.{lang}.json     # 翻译结果
│   ├── title_bilingual.json        # 标题翻译（翻译阶段生成）
│   └── summary.md                  # 结构化小结（翻译阶段生成，可选）
├── segments/                       # 片段
│   ├── vocals/                     # 按时间裁剪的人声片段
│   │   ├── 0000.wav
│   │   ├── 0001.wav
│   │   └── ...
│   └── tts/                        # TTS 生成的配音片段
│       ├── 0000.wav
│       ├── 0001.wav
│       └── ...
└── tmp/                            # 合成临时文件
    ├── audio_dubbing.wav           # 拼接后的完整配音
    ├── audio_mixed.m4a             # 配音 + BGM 混音
    ├── timings.json                # 实际配音时间戳
    ├── subtitles.srt               # 字幕文件
    └── video_final.mp4             # 最终合成视频
```

---

## 阶段 1：download（下载）

**适配器**: `YtDlpDownloader` / `LocalFileDownloader`

| 项目 | 内容 |
|------|------|
| **输入** | 视频 URL（`task.url`） |
| **输出** | `media/video_source.mp4`、`metadata/ytdlp_info.json` |
| **操作** | 调用 yt-dlp 下载视频，支持 YouTube/Bilibili 等平台。提取视频标题保存到 `ytdlp_info.json`。支持 cookie 和代理。 |

**数据格式** (`ytdlp_info.json`):
```json
{ "url": "...", "title": "...", "output": "..." }
```

---

## 阶段 2：separate（人声分离）

**适配器**: `FfmpegSimpleSeparator` / `DemucsSeparator` / `AudioSeparatorApiSeparator`

| 项目 | 内容 |
|------|------|
| **输入** | `media/video_source.mp4` |
| **输出** | `media/audio_vocals.wav`、`media/audio_bgm.wav` |
| **操作** | 支持 3 种分离方法：**FFmpeg**（频率滤波，快速轻量）、**Demucs**（本地 Python 模型，质量最高）、**audio-separator API**（Docker 容器服务）。分离结果复制到 media 目录。 |

---

## 阶段 3：asr（语音识别）

**适配器**: `WhisperCppRecognizer` / `WhisperApiRecognizer`

| 项目 | 内容 |
|------|------|
| **输入** | `media/audio_vocals.wav` |
| **输出** | `metadata/asr.json` |
| **操作** | 调用 whisper.cpp 或 OpenAI Whisper API 对人声进行语音识别。输出 JSON 包含每句话的文字、起止时间戳、说话人 ID（固定 "1"）和词级别时间戳。 |

**输出格式** (`asr.json`):
```json
{
  "audio_info": { "source": "...", "duration": 123456 },
  "result": {
    "text": "全文转写文本",
    "utterances": [
      {
        "text": "句子文本",
        "start_time": 1000,
        "end_time": 3000,
        "speaker": "1",
        "words": [
          { "text": "word", "start_time": 1000, "end_time": 1200 }
        ]
      }
    ]
  }
}
```

**字段说明**:
- `start_time` / `end_time`: 毫秒，相对于原视频时间轴
- `speaker`: 说话人 ID（默认为 "1"）
- `words`: 词级别时间戳（仅 Whisper API 支持）

---

## 阶段 4：asr_fix（时间修正）

**适配器**: `PipelineOrchestrator.executeAsrFix()`（内置逻辑）

| 项目 | 内容 |
|------|------|
| **输入** | `metadata/asr.json` |
| **输出** | `metadata/asr_fixed.json` |
| **操作** | 复制 ASR 结果，对每句话的起止时间做 padding：**起始提前 100ms，结束延后 300ms**。目的是补偿 ASR 时间戳的边缘误差，确保语音裁剪时不会切掉首尾音素。 |

**时间修正**:
```
start_time_fixed = max(0, start_time - 100)
end_time_fixed   = end_time + 300
```

---

## 阶段 5：translate（翻译）

**适配器**: `OpenAiTranslator` / `LocalLlmTranslator`

| 项目 | 内容 |
|------|------|
| **输入** | `metadata/asr_fixed.json`（回退: `asr.json`） |
| **输出** | `metadata/translation.{lang}.json` |
| **操作** | 两阶段翻译：阶段 A 对全文做预处理（生成摘要和热词）；阶段 B 并发逐句翻译。保留原始 ASR 时间戳和说话人 ID。 |

**输出格式** (`translation.{lang}.json`):
```json
{
  "translation": [
    {
      "src": "原文",
      "dst": "译文",
      "src_lang": "zh",
      "dst_lang": "en",
      "start_time": 1000,
      "end_time": 3000,
      "speaker": "1"
    }
  ]
}
```

**字段说明**:
- `src`: 原始语言文本
- `dst`: 目标语言译文（空字符串表示无声段/跳过）
- `start_time` / `end_time`: 从 asr_fixed 继承的修正时间戳

**附加产物**（翻译阶段结束前生成）:
| 文件 | 格式 | 说明 |
|------|------|------|
| `metadata/title_bilingual.json` | `{"original":"...", "translated":"...", "original_lang":"...", "translated_lang":"..."}` | 视频标题的双语翻译结果，用于最终文件命名。源语言为空时不生成。 |
| `metadata/summary.md` | Markdown | LLM 对英文原文生成的结构化中文小结（仅源语言为英文时有意义）。 |

---

## 阶段 6：split_audio（切分音频）

**适配器**: `FfmpegAudioProcessor.splitAudio()`

| 项目 | 内容 |
|------|------|
| **输入** | `media/audio_vocals.wav`、`metadata/translation.{lang}.json` |
| **输出** | `segments/vocals/XXXX.wav`（按 0000, 0001, ... 顺次编号） |
| **操作** | 按 translation 中每句话的时间戳，从原始人声中裁剪对应的音频片段。裁剪时在起止加 padding（起始 +80ms，结束 +160ms）确保不切掉音素。片段按 translation 顺序编号，跳过 `end_time <= start_time` 的无效条目。 |

**裁剪范围**:
```
start = max(0, start_time - 80ms)    // 起始 padding
end   = end_time + 160ms              // 结束 padding
```

**注意**: 裁剪的是**原始人声**（`audio_vocals.wav`），作为后续 TTS 的声音克隆参考音频。

---

## 阶段 7：tts（语音合成）

**适配器**: `VoxCpmTtsProvider` / `OpenAiTtsProvider` / `EdgeTtsProvider`

| 项目 | 内容 |
|------|------|
| **输入** | `metadata/translation.{lang}.json`、`segments/vocals/XXXX.wav`（作为参考音频） |
| **输出** | `segments/tts/XXXX.wav`（与 translation 非空条目一一对应） |
| **操作** | 遍历 translation 中的非空 `dst` 条目，对每句话调用 TTS API 生成配音音频。 |

**参考音频策略**（VoxCPM 声音克隆）:

| 策略 | 说明 |
|------|------|
| **之前** | 每句话使用 `vocals/{index}.wav` 作为参考 → 音色不一致 |
| **现在** | 按 speaker 分组，同一 speaker 的所有句子共用第一个 vocal 片段作为固定参考 → 音色一致 |

**编号说明**: TTS 文件编号与 `vocals/` 目录编号**不对齐** —— TTS 只对有译文的非空条目生成文件（编号按非空条目顺序），而 `vocals/` 包含所有段。VoxCPM 的参考音频查找使用 speaker 分组固定索引而非 TTS 文件编号。

---

## 阶段 8：merge_audio（混合音频）

**适配器**: `FfmpegAudioProcessor.mergeAudio()`

| 项目 | 内容 |
|------|------|
| **输入** | `segments/tts/XXXX.wav`、`metadata/translation.{lang}.json` |
| **输出** | `tmp/audio_dubbing.wav`、`tmp/timings.json` |
| **操作** | 将 TTS 片段按原始时间轴拼接成完整配音音频，输出 `actual_start_time` / `actual_end_time` 实际时间戳。 |

**拼接逻辑**:

1. 按 translation 顺序遍历，`currentMs` 跟踪当前音频位置
2. 若 `dst` 为空（无声段）：跳过，后续非空段自动填充静音（**不再使用 asr_fix 膨胀后的 end_time**）
3. 若 `dst` 非空：取对应 TTS 文件
   - 计算与上一段的间隙，填充静音
   - 若上一段超时，将本段开始时间后移
   - 解码 TTS 音频（16-bit mono 24000Hz PCM）
   - **时间压缩**：若 TTS 时长与原始时间槽位偏差超过 50ms，通过 FFmpeg atempo 自动变速适配（0.5x–2.0x）
   - 写入 PCM，更新 `currentMs`
4. 记录 `actual_start_time` / `actual_end_time` 写入 `timings.json`

**输出格式** (`timings.json`):
```json
{
  "translation": [
    {
      "src": "原文",
      "dst": "译文",
      "start_time": 900,
      "end_time": 3300,
      "speaker": "1",
      "actual_start_time": 900,
      "actual_end_time": 3400
    }
  ]
}
```

**字段说明**:
- `actual_start_time`: TTS 音频在配音中的实际开始时间（毫秒）
- `actual_end_time`: TTS 音频在配音中的实际结束时间（毫秒）
- 两者基于拼接后的时间轴，可能因 TTS 时长偏差与原始时间戳略有差异

---

## 阶段 9：merge_video（合成视频）

**适配器**: `FfmpegVideoProcessor.mergeVideo()`

| 项目 | 内容 |
|------|------|
| **输入** | `media/video_source.mp4`、`tmp/audio_dubbing.wav`、`media/audio_bgm.wav`（可选）、`tmp/timings.json` |
| **输出** | `media/video_final.mp4` |

> **注意**：管线所有阶段完成后（在更新任务状态为 SUCCEEDED 之前），
> 会自动执行**最终视频重命名**：读取 `metadata/title_bilingual.json`，
> 将 `video_final.mp4` 重命名为 `{中文名} - {英文名}.mp4`（含中文语言对时）
> 或 `{原语言名} - {目标语言名}.mp4`（不含中文时）。
> 新路径写入 `task.final_video_path` 字段。
| **操作** | 三步合成最终视频：生成 SRT 字幕、混音、编码输出。 |

**步骤**:

1. **生成 SRT 字幕**：读取 `timings.json`，用 `actual_start_time` / `actual_end_time` 生成 SRT 文件到 `tmp/subtitles.srt`
   - 优先使用 `actual_start_time` / `actual_end_time`，不存在时回退 `start_time` / `end_time`
   - 跳过空 `dst` 条目

2. **混音**：若有 BGM 文件，将配音和 BGM 通过 FFmpeg amix 混合，输出 `tmp/audio_mixed.m4a`
   - 配音音量 1.0，BGM 音量 0.30
   - 淡入 30ms 消除爆音

3. **编码**：将原始视频画面 + 混音音频 + 字幕滤镜合成最终视频
   - 自动检测硬件编码器（NVENC/QSV/AMF/VideoToolbox），回退 libx264
   - 音频编码 AAC
   - `-shortest` 以最短流长度截断
   - `+faststart` 优化 Web 播放

---

## 关键时间戳字段对比

| 字段 | 产生阶段 | 含义 |
|------|---------|------|
| `start_time` / `end_time` | ASR → asr_fix → translate | 基于 ASR 的时间戳，asr_fix 做了 padding（起始-100ms，结束+300ms） |
| `actual_start_time` / `actual_end_time` | merge_audio | TTS 音频实际拼接后的时间位置，因 TTS 时长偏差可能与原始时间戳不同 |
| SRT 时间 | merge_video | 字幕显示时间，优先使用 `actual_*`，回退 `start_time` / `end_time` |

## 时间同步风险点

| 问题 | 原因 | 修复 |
|------|------|------|
| 累积延迟 | TTS 音频时长 > 原始时间段，后续段被推后 | 时间压缩（atempo 变速适配原始时间段） |
| 空白段膨胀 | asr_fix 的 end_time +300ms 被空白段用于填充静音 | 空白段跳过，间隙由后续非空段自动处理 |
| 音色不一致 | VoxCPM 每句用不同的 vocal 片段作参考 | 按 speaker 分组，使用固定参考音频 |
