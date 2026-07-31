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
- [x] 内存优化：`setMemoryPatternOptimization(false)`、try-with-resources 关闭 Result、fp16 decoder 优先
- [x] 长音频分片：`transcribeChunked()` 30s 窗口 + 5s 重叠 + 多线程并行 + 去重合并
- [x] 默认模型改为 `whisper-base`，默认语言 auto-detect
- [x] Repetition penalty（subtractive 1.5，窗口 20）
- [x] 修复 `OnnxTensor` 双重复用导致的大量 WARN 日志
- [x] HuggingFace 模型下载 401 修复（`HF_TOKEN` + 自动降级 `{name}-ONNX` 回退仓库）
- [x] `forkWorker()` — 多线程安全的工作实例创建
- [x] Beam search 解码（BEAM_SIZE=5，长度归一化）
- [x] 温度回退（`[0.0, 0.2, 0.4, 0.6, 0.8, 1.0]` + quality score）
- [x] VAD 静音点分片（能量检测 + 自适应阈值）
- [x] Initial prompt（中文 `"请添加标点符号"`）
- [x] 时间戳解码路径（`initialTokensWithTimestamps` → 真实时间戳 token）
- [x] Compression ratio 质量检查（阈值 2.4/1.8 两级）
- [x] 短分片自动扩展（< 40% 窗口时向后补齐）
- [x] No speech 检测（输出词数 + 音频时长）
- [x] 贪心优先解码策略：贪心达标直接返回，跳过 beam search × 6 温度 ~67000 次 decoder 调用
- [x] **修复多语言模型 no_timestamps token 偏移 bug**：whisper-base 等多语言模型
      token 布局为 transcribe(50359)/translate(50360)/notimestamps(50361)，
      原代码用 `transcribe+1` 得到 translate token（50360），导致 initialTokens
      告诉模型"翻译"而非"转写"→ 贪心输出 "."。修复：多语言模型用 `transcribe+2`

---

## ❌ 已取消（明确跳过）

- ~~推理耗时统计集成到 backend 监控~~（已取消）
- ~~`AudioSeparator` 分块处理测试~~（已取消）
- ~~`OnnxSeparator` 集成测试（Spring 上下文）~~（已取消）
- ~~手动运行下载脚本下载模型~~（已取消，`OnnxWhisperRecognizer` 首次运行时自动下载）

---

## 📋 待实现：对齐 Python server.py (faster-whisper) 效果

### 差异分析

Python `server.py`（faster-whisper）与当前 Java ONNX 实现的差异：

| 维度 | Python faster-whisper | Java ONNX (当前) | 状态 |
|------|----------------------|-------------------|------|
| 解码策略 | **beam search** (beam_size=5) | beam search ✅ | 已对齐 |
| 温度回退 | `[0.0, 0.2, 0.4, 0.6, 0.8, 1.0]` | 支持 ✅ | 已对齐 |
| VAD 滤波 | `vad_filter=True` (Silero VAD) | 能量检测 + 自适应阈值 | 算法不同，功能对齐 |
| initial_prompt | 语言特定标点提示 | 中文提示 `"请添加标点符号"` ✅ | 已对齐 |
| 时间戳解码 | 默认启用 | 默认启用 ✅ | 已对齐 |
| Compression ratio | 每温度检查，阈值 2.4 | 每温度检查，2.4/1.8 两级 ✅ | 已对齐 |
| Length normalization | score / len^penalty | score / sqrt(len) ✅ | 已对齐 |
| 重复惩罚 | divisive 1.1 | subtractive 1.5 ✅ | 调优对齐 |
| No speech 检测 | `no_speech_prob > threshold` | 输出词数 + 音频时长检测 ✅ | 功能对齐 |

### 剩余差异（与 faster-whisper 仍不一致）

| 维度 | faster-whisper | Java ONNX | 影响 | 优先级 |
|------|---------------|-----------|------|--------|
| 平均 log-prob 检查 | avg_logprob < -1.0 → 回退 | 无 | 低置信度输出可能漏检 | 低 |
| 单词级时间戳 | cross-attention + alignment heads | 字符比例分配 | 单词时间戳精度 | 低 |
| VAD 模型 | Silero VAD (神经网络) | RMS 能量 + 自适应阈值 | 低信噪比场景误判 | 低 |
| 重采样 | SciPy sinc 插值 | 线性插值 | 高频保真度 | 忽略 |
| STFT | PyTorch torch.stft | JTransforms DoubleFFT | 浮点精度差异 | 忽略 |

注释：
- **平均 log-prob 检查**：需要解码循环中累加每个 token 的 log-softmax 值，涉及修改各解码方法返回值，工作量中等但收益有限。当前 scoreTranscriptionQuality + compression ratio 已覆盖大部分退化场景。
- **单词级时间戳**：依赖 ONNX 模型导出 cross-attention weights（当前 onnx-community 导出版本不含），需额外模型处理 pipeline，非代码可解决。
- **VAD 模型**：可集成 silero_vad.onnx（~1.7MB），但当前能量检测在现有音频上效果可接受。

### 实现顺序

#### 1️⃣ Beam Search 解码 ✅
- [x] 新增 `computeLogits(int[], float[])` — 返回完整 logits 向量
- [x] 新增 `runDecoderBeamSearch(float[], int)` — beam search 解码器
- [x] beam 状态管理：topK 展开 + log-softmax 评分 + 全局剪枝
- [x] EOS 差异化处理：finished beam 不扩展，其他 beam 继续
- [x] 复用已有的 repetition_penalty + suppress_tokens
- [x] `runDecoder()` 根据 BEAM_SIZE > 1 自动选择 beam search
- [x] BEAM_SIZE 默认为 5
- [ ] （待优化）length normalization — 目前 pure log-prob，未做长度归一化
- [ ] （待优化）KV-cache beam search — 当前用 full decoder，未用 decoder_with_past

#### 2️⃣ VAD 滤波 ✅
- [x] 能量检测：滑动窗口 RMS + 自适应阈值（最低 20% 帧的均值 × 2.5）
- [x] 300ms 间隙合并 + 最小 100ms 语音段过滤
- [x] 在 `transcribeChunked` 前过滤静音段
- [x] 空音频保护（全部静音时返回空结果）

#### 3️⃣ 温度回退 ✅
- [x] `TEMPERATURES = [0.0, 0.2, 0.4, 0.6, 0.8, 1.0]` 对齐 faster-whisper
- [x] `scoreTranscriptionQuality()` 质量评分——基于非标点 token 占比
- [x] 评分 ≥0.7 即停止回退，否则取最高分结果
- [x] `topKTokens` / `logSoftmax` 均接受 temperature 参数

#### 4️⃣ Initial Prompt（标点提示） ✅
- [x] 多语言提示字典：zh / ja / ko / en / fr / pt
- [x] 提示 token 编码后追加到 `initialTokens` 末尾
- [x] 支持 auto-detect 模式（检测语言后选用对应提示）

### ⚠️ 运行期关键问题记录

| 现象 | 根因 | 修复 |
|------|------|------|
| 1. 时间戳偏移 | VAD 压缩音频 → chunkOffsets 基于压缩后位置计算 | 移除 `transcribeChunked` 开头的 VAD 调用 |
| 2. 静音段输出 "RintiCoding." 幻觉 | chunk 重叠区噪音被转录为幻觉词 | `filterGarbageSegments` 过滤纯标点单 word + 重复文本 |
| 3. 句子重复 ("I'll see you next time." ×2) | 5s 重叠 + dedup 因时间戳错位失效 | `isLikelyDuplicate` 检查时间接近的重复文本 |
| 4. 末尾多出不完整句子 "I'll see you" | chunk 边界截断 | `filterGarbageSegments` 移除末尾无句尾标点 segment |
| **5. ~31s 后全篇幻觉 "dubbing voice" 重复** | **English initial prompt 泄漏**——Whisper Decoder attention 把 prompt tokens 当"已生成文本"来续写，长 prompt 直接支配模型输出 | **删掉英/日/韩/法/葡的长 prompt，仅保留中文 `"请添加标点符号"`** |
| 6. 静音间隙幻觉/上下文污染 | 固定 30s 窗口含说话间隙，模型用 prompt 填空；chunk 间无隔离 | **静音点分片** → 每片 = 连续语音（无静音），同时每片由独立 `forkWorker` 处理 → 天然"重置 decoder context" |

### 分片策略对齐

| 维度 | 旧：固定窗口 | 新：静音点分片 |
|------|------------|--------------|
| 分片方式 | 30s 固定窗口，5s 重叠 | 检测 ≥2s 静音，在其中点切分 |
| 重叠 | 固定 5s | 分片边界 ±2s（对齐 Python SILENCE_OVERLAP）|
| 最小分片 | 30s（固定） | 30s（不足则与邻居合并）|
| 跳过边界分片 | 无 | 距开头/末尾 <60s 的不切（避免过小分片）|
| 每片音频 | 可能含静音间隙 → 诱使模型填空 | 连续语音段 → 模型有清晰输入 |
| 上下文隔离 | 无（initialTokens 跨分片传播） | 每片用独立 forkWorker → 天然隔离 |
| 回退机制 | — | 无有效静音点时自动回退到固定 30s 窗口 |

### Lessons Learned

**Initial Prompt 使用守则：**
1. prompt 文本会被模型当成"前面说过的话"，必须与音频内容无关
2. 提示越长、越具体→模型在音频含糊时越倾向续写 prompt
3. 含 `dubbing voice`、`read punctuation marks` 等具体词语→直接泄漏到输出
4. 非中文 Whisper 标点能力已够强，不需要 prompt
5. 中文 prompt 也必须极简短（≤5 字），避免"配音公司"等具体名词

---

## 🟡 可做但暂无计划

- **VR 架构模型**（时域处理，无需 FFT）— 架构差异大，需独立开发
- **多模型并发管理（`OrtSession` 池）**— 需要时再引入
- **Silero VAD 集成** — 替换能量检测，提升低信噪比表现
- **单词级对齐时间戳** — 需要导出含 cross-attention 的 ONNX 模型
- **平均 log-prob 温度回退检查** — 当前质量评分 + compression ratio 已足够
