# Whisper ONNX 模型说明

Java 进程内 Whisper 语音识别（`OnnxWhisperRecognizer`）使用 HuggingFace [`onnx-community`](https://huggingface.co/onnx-community) 发布的 ONNX 格式模型。
首次使用时自动下载到 `data/whisper-models/{模型名}/` 目录，后续复用。

---

## 快速选择

| 你的场景 | 推荐模型 | 原因 |
|---------|---------|------|
| 英文字幕、开发测试、资源受限 | **`whisper-tiny.en`** | 75MB，秒级加载，任一 CPU 流畅运行 |
| 英文字幕、质量优先 | **`whisper-base.en`** | 150MB，速度与准确率的良好平衡 |
| 中英文混合、多语言 | **`whisper-small`** | 470MB，质量在线，多语言可用 |
| 极致准确率（英文） | **`whisper-medium.en`** | 1.5GB，需较多内存/显存 |
| 纯中文内容 | **`whisper-small`** 或 `whisper-medium` 或 Python `faster-whisper` | 多语言模型含中文；注意 ONNX 当前仅 English-only 模型加载优化好 |
| Docker / 生产环境 | **`whisper-tiny.en`** 或 `whisper-base.en` | 内存占用可控，无需 GPU |

---

## 可用模型一览

| 模型 ID | 参数量 | 磁盘 | 语言 | WER (LibriSpeech) | 加载速度 | 推理速度 (1s 音频) | 内存占用 |
|---------|--------|------|------|------------------|---------|-----------------|---------|
| `whisper-tiny.en` | 39M | ~75 MB | 仅英文 | ~7.5% | ~1s | ~50ms | ~150 MB |
| `whisper-tiny` | 39M | ~75 MB | 多语言 | ~7.5% | ~1s | ~50ms | ~150 MB |
| `whisper-base.en` | 74M | ~150 MB | 仅英文 | ~5.8% | ~2s | ~80ms | ~250 MB |
| `whisper-base` | 74M | ~150 MB | 多语言 | ~5.8% | ~2s | ~80ms | ~250 MB |
| `whisper-small.en` | 244M | ~470 MB | 仅英文 | ~4.3% | ~5s | ~200ms | ~600 MB |
| `whisper-small` | 244M | ~470 MB | 多语言 | ~4.3% | ~5s | ~200ms | ~600 MB |
| `whisper-medium.en` | 769M | ~1.5 GB | 仅英文 | ~3.7% | ~15s | ~500ms | ~1.8 GB |
| `whisper-medium` | 769M | ~1.5 GB | 多语言 | ~3.7% | ~15s | ~500ms | ~1.8 GB |
| `whisper-large-v3` | 1.5B | ~2.9 GB | 多语言 | ~3.2% | ~30s | ~1s | ~3.5 GB |
| `whisper-large-v3-turbo` | 809M | ~1.6 GB | 多语言 | ~3.4% | ~15s | ~300ms | ~1.8 GB |

> **WER (Word Error Rate)**: LibriSpeech clean 测试集，值越低越好。
> **加载速度**: 机械硬盘参考值，SSD 上快 2-3 倍。
> **推理速度**: CPU (4 核) 参考值，GPU 可快 5-10 倍。实际因音频长度和机器配置而异。
> **内存占用**: 峰值 RSS，含模型权重 + ONNX Runtime 临时 buffer。

---

## 英文专用 vs 多语言

| 维度 | `whisper-{size}.en`（英文专用） | `whisper-{size}`（多语言） |
|------|-------------------------------|---------------------------|
| 词表大小 | 51,865 | 51,865+（含多语言 token） |
| 中文识别 | ❌ 不支持 | ✅ 支持 |
| 准确率（英文） | 略高（~0.3% 优势） | 略低 |
| 模型大小 | 相同 | 相同 |
| 速度 | 相同 | 同大小相同 |

**建议**：如果只做英文内容，用 `.en` 后缀的版本；如果涉及中文或其他语言，用无后缀的多语言版本。

---

## 选择建议

### 按场景

**开发调试**
```
WHISPER_ONNX_MODEL=whisper-tiny.en
```
加载最快，75MB 磁盘，验证管线流程是否跑通。

**普通英文字幕**
```
WHISPER_ONNX_MODEL=whisper-base.en
```
150MB，英文 WER 5.8%，质量与速度的甜点。

**中文字幕**
```
WHISPER_ONNX_MODEL=whisper-small
```
470MB，多语言模型含中文。如需更高中文准确率可改用 `whisper-medium`。

**生产环境（Docker，CPU）**
```
WHISPER_ONNX_MODEL=whisper-tiny.en
```
占用最低，避免 Docker 容器 OOM。如需质量提升到 `base`。

### 按资源

| 可用内存 | 最大推荐模型 |
|---------|------------|
| < 512 MB | `whisper-tiny.en` / `whisper-tiny` |
| 512 MB - 1 GB | `whisper-base.en` / `whisper-base` |
| 1 GB - 2 GB | `whisper-small.en` / `whisper-small` |
| 2 GB - 4 GB | `whisper-medium.en` / `whisper-medium` |
| 4 GB+ | `whisper-large-v3` 或 `whisper-large-v3-turbo` |

### 按 CPU 核心数

| 核心数 | 推荐模型 |
|-------|---------|
| 2 核 | `whisper-tiny.en` |
| 4 核 | `whisper-base.en` ~ `whisper-small.en` |
| 8 核+ | 任意 |

---

## 环境变量配置

```bash
# 选择模型（默认 whisper-tiny.en）
export WHISPER_ONNX_MODEL=whisper-base.en

# ONNX Runtime 的 CPU 线程数（默认 2，由 WhisperModel 内配置）
export ONNX_NUM_THREADS=4
```

Docker Compose 中：

```yaml
backend:
  environment:
    - WHISPER_ONNX_MODEL=${WHISPER_ONNX_MODEL:-whisper-tiny.en}
  volumes:
    - /mnt/e/youdub-data/whisper-models:/app/data/whisper-models
```

---

## 模型存储目录

模型下载后存放在以下路径（搜索顺序即此）：

1. `data/whisper-models/{model-name}/` — Docker 挂载目录，持久化
2. `models/{model-name}/` — 当前工作目录
3. `onnxruntime/models/{model-name}/` — Maven 模块内目录（开发用）

每个模型目录包含 5 个文件：
```
{model-name}/
├── config.json          — 模型架构配置（自动读取）
├── encoder_model.onnx   — 编码器（mel → hidden states，~45% 推理时间）
├── decoder_model.onnx   — 解码器（自回归文本生成，~55% 推理时间）
├── vocab.json           — 词表（GPT-2 BPE）
└── merges.txt           — BPE 合并规则
```

---

## 性能注意事项

1. **首次加载慢**：模型下载速度取决于网络。Docker 环境下建议将 `data/whisper-models/` 挂载到持久卷，避免每次重启重新下载。

2. **30 秒限制**：当前 Whisper ONNX 实现仅支持 ≤30s 的音频片段。超过 30s 会自动截断（取前 30s）。完整长音频的切分推理待后续实现。

3. **ONNX CPU vs GPU**：使用 `onnxruntime_gpu` 依赖，运行时自动检测 CUDA。GPU 对大模型（medium/large）加速明显（5-10x），小模型（tiny/base）提升有限。

4. **内存建议**：模型加载峰值约为模型文件大小的 2-3 倍。例如 `whisper-small` (470MB) 加载时需约 1.2GB 可用内存。
