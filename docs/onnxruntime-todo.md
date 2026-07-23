# onnxruntime 模块

## ✅ 已完成

### 音频分离（MDX-NET）
- [x] 模块创建：`site.dengwei:onnxruntime:1.0.0`
- [x] 依赖：`com.microsoft.onnxruntime:onnxruntime_gpu:1.20.0` + `JTransforms:3.1`
      （GPU 版内置 CPU+GPU backend，无 CUDA 环境自动降级到 CPU）
- [x] `WavAudio` — WAV 读写（16-bit PCM，自动声道转换）
- [x] `SpectralProcessor` — STFT/iFFT（Hann 窗 + overlap-add）
- [x] `MdxNetModel` — MDX-NET ONNX 模型加载与推理
- [x] `AudioSeparator` — 音频分离全链路编排 + 长音频分块（600s/块，15s crossfade）
- [x] `OnnxSeparator` Spring 适配器集成到 backend pipeline
- [x] Python `audio-separator-api` 适配器已移除
- [x] 模型自动下载（从 HuggingFace，首次缺失时自动拉取）
- [x] GPU 自动检测（nvidia-smi 探测，`OnnxSeparator.initModel()` 自动启用 CUDA）
- [x] 模型路径硬编码（搜索 `data/separator-models/` → `models/` → `onnxruntime/models/`）
- [x] 单元测试 + 集成测试（STFT 可逆性、端到端分离）
- [x] Demo.java — 对称/周期 Hann 窗配置对照（A/B 两组）
- [x] 移除 `synthesisWindow=false` 方案（MDX-NET 频谱修改导致 Gibbs 振铃噪声）
- [x] CPU 线程数通过环境变量 `ONNX_NUM_THREADS` 覆盖（默认 `availableProcessors()/3`）
- [x] 模型预热重试（3 次指数退避 1s/2s/4s）

### 构建与部署
- [x] GPU 通过配置解决：始终依赖 `onnxruntime_gpu`（含 CPU+GPU backend），运行时自动检测 CUDA，无需 Maven profile
- [x] 新增依赖 `org.json:json`（Whisper 配置/词表解析）

### 语音识别（Whisper ONNX） — `site.dengwei.onnxruntime.whisper` + Spring 适配器
- [x] `WhisperConfig` — 从 `config.json` 读取模型超参数
- [x] `MelSpectrogram` — 80 通道 log-mel 频谱（STFT → Mel 滤波 → log10 → 80dB 裁剪 → 标准化）
- [x] `WhisperTokenizer` — GPT-2 BPE 分词器（`vocab.json` + `merges.txt`）
- [x] `WhisperModel` — Encoder/Decoder ONNX 加载 + 自回归推理（greedy decoding）
- [x] `OnnxWhisperRecognizer` Spring 适配器（`@Component("whisper-onnx")`）
- [x] 模型自动下载（HuggingFace `onnx-community`，首次缺失时自动拉取 5 个文件）
- [x] 模型选择通过环境变量 `WHISPER_ONNX_MODEL`（默认 `whisper-tiny.en`）
- [x] Docker compose: 添加 `WHISPER_ONNX_MODEL` 环境变量 + `whisper-models` 持久卷
- [x] `docs/whisper-onnx.md` — 模型对比文档（所有可用模型的大小、WER、速度、内存占用）

---

## ❌ 已取消（明确跳过）

- ~~推理耗时统计集成到 backend 监控~~（已取消）
- ~~`AudioSeparator` 分块处理测试~~（已取消）
- ~~`OnnxSeparator` 集成测试（Spring 上下文）~~（已取消）
- ~~手动运行下载脚本下载模型~~（已取消，`OnnxWhisperRecognizer` 首次运行时自动下载）

---

## 🟡 可做但暂无计划

- **VR 架构模型**（时域处理，无需 FFT）— 架构差异大，需独立开发
- **多模型并发管理（`OrtSession` 池）**— 需要时再引入
- **Whisper 支持 timestamp 输出**— 当前只输出纯文本，可扩展 timestamp token 解析
