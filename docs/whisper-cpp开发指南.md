这是一份针对你当前硬件条件（AMD 显卡 + 纯 CPU 推理 + 追求高精度）量身定制的 Whisper.cpp 语音识别系统设计与开发指南。

🏗️ 一、 架构与选型设计说明

核心组件选型矩阵
组件层级   选型   选型理由 (Why)
推理引擎   whisper.cpp   纯 C/C++ 实现，无 Python/PyTorch 依赖。对 CPU 的 AVX2/AVX-512 指令集优化极佳，内存管理优秀，是 CPU 环境下的绝对性能王者。

核心模型   large-v3-turbo (GGML)   编码器与 large-v3 一致（保精度），解码器从 32 层压缩至 4 层（提速度）。在 CPU 上速度比 large-v3 快约 8 倍，且精度损失 <1%，是目前 CPU 跑高精度的唯一解。

量化格式   Q5_0 或 Q5_K_S   Q5 级别在 CPU 上平衡了内存占用和中文识别率。Q4 会导致中文同音字错误率上升，Q8 则会导致 CPU 推理速度过慢。

音频处理   FFmpeg (作为前置)   whisper.cpp 原生只支持 16kHz WAV。使用 FFmpeg 在外部将任意音视频转为标准 WAV，解耦核心引擎，提高稳定性。

语音活动检测   Silero VAD   剥离音频中的静音段，可减少 30%-50% 的 CPU 无效计算时间，对长音频/会议录音至关重要。

硬件调度策略
主力计算：CPU (开启 AVX2/AVX-512)。
辅助加速 (可选尝试)：AMD GPU (通过 Windows DirectML)注：DirectML 对 Whisper 的支持属于实验性，若遇兼容问题则退回纯 CPU 模式。

🛠️ 二、 环境准备与构建指南 (Build)

假设你在 Windows 环境下（AMD 显卡用户最常见），使用 CMake 和 Visual Studio 进行构建。

前置依赖安装
Git: 用于拉取代码。
CMake: 版本 >= 3.20。
Visual Studio 2022: 安装时需勾选 "使用 C++ 的桌面开发" (Desktop development with C++)。
FFmpeg: 下载 Windows 构建版，将 bin 目录加入系统环境变量 PATH。

拉取与构建 whisper.cpp

打开 x64 Native Tools Command Prompt for VS 2022 (VS 开发者命令提示符)，执行以下命令：

克隆最新代码
git clone https://github.com/ggerganov/whisper.cpp.git
cd whisper.cpp

配置 CMake (纯 CPU 极致优化版)
说明：开启 AVX2/AVX512，开启 LTO (链接期优化)，构建 Release 版本
cmake -B build -DCMAKE_BUILD_TYPE=Release -DWHISPER_AVX2=ON -DWHISPER_AVX512=ON -DWHISPER_BUILD_EXAMPLES=OFF -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=ON

编译项目
cmake --build build --config Release -j %NUMBER_OF_PROCESSORS%

(进阶) 尝试 AMD DirectML 加速构建
如果你想尝试让 AMD 显卡参与加速，需先安装 NuGet 和 Windows SDK，然后在 CMake 配置阶段加入：
cmake -B build -DWHISPER_DML=ON -DCMAKE_BUILD_TYPE=Release ...
⚠️ 警告：如果编译报错或运行崩溃，请立刻放弃 DML，使用上面的纯 CPU 构建方案。

📥 三、 组件下载与部署 (Download)

你需要下载两个核心文件放入 models/ 目录。

下载 large-v3-turbo 量化模型
whisper.cpp 官方提供了转换好的 GGML 格式模型。推荐使用 HuggingFace 镜像或官方脚本下载 Q5_0 版本。

进入 models 目录
cd models

使用官方脚本下载 (如果网络不畅，请使用下方的 HuggingFace 镜像链接手动下载)
bash ./download-ggml-model.sh large-v3-turbo-q5_0

或者手动下载链接 (使用 wget 或浏览器):
https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin
文件大小约 1.1GB，下载后重命名为 ggml-large-v3-turbo.bin 或直接使用原名。

下载 Silero VAD 模型
whisper.cpp 支持内置 VAD，需要下载 ONNX 格式的 Silero 模型。
在 models 目录下执行
wget https://models.ggerganov.com/silero-vad/silero_vad.onnx
或者手动从 HuggingFace 下载 silero_vad.onnx 放入 models 目录

⚙️ 四、 核心调用参数设计 (Parameter Design)

这是系统设计的核心。如何配置参数决定了精度和速度的平衡。

命令行调用模板 (CLI)
./build/bin/whisper-cli.exe \
-m models/ggml-large-v3-turbo-q5_0.bin \
-f input_audio.wav \
--vad --vad-model models/silero_vad.onnx \
-l zh \
-t 8 \
-oj

关键参数解析表
参数   推荐值   作用与设计考量
-m   .../ggml-large-v3-turbo-q5_0.bin   指定高精度 turbo 量化模型。

-f   input.wav   必须是 16kHz, 单声道, 16-bit PCM WAV。建议用 FFmpeg 提前转换。

--vad   (无值)   开启语音活动检测，跳过静音，大幅提升长音频处理速度。

--vad-model  silero_vad.onnx   指定 VAD 模型路径。

-l   zh   强制指定语言为中文。跳过语言检测步骤，节省时间并降低误判率。

-t   8 (或物理核心数)   线程数。设为 CPU 的物理核心数（非逻辑核心）。超线程对 Whisper 帮助不大，反而可能抢占缓存。

-oj   (无值)   Output JSON。输出结构化 JSON 文件（包含词级时间戳），方便后端解析。

--prompt   "以下是关于...的讲话"   初始提示词。如果音频包含特定专业术语（如医疗、法律），在此传入相关词汇，可显著提升专有名词识别率。

--beam-size  5   束搜索大小。默认是 5，追求极致精度可改为 10，但速度会慢 20%。

音频预处理规范 (FFmpeg)
不要直接把 mp4/mp3 丢给 whisper.cpp，必须在代码中加一层预处理：
ffmpeg -i input.mp4 -ar 16000 -ac 1 -c:a pcm_s16le -y output.wav

💻 五、 开发集成指南 (Integration)

在实际业务系统中，不建议用命令行 system() 调用，推荐使用 whisper.cpp 内置的 HTTP Server 或 C API 绑定。

方案 A：作为独立微服务 (推荐，最解耦)
whisper.cpp 编译时开启了 WHISPER_BUILD_SERVER，可以直接启动一个 HTTP 服务。

启动服务：
./build/bin/whisper-server.exe \
-m models/ggml-large-v3-turbo-q5_0.bin \
--vad --vad-model models/silero_vad.onnx \
-l zh -t 8 \
--host 127.0.0.1 --port 8080

后端调用 (Python/Node.js/Java 等)：
直接发送 HTTP POST 请求，将音频文件作为 multipart/form-data 上传。
import requests

url = "http://127.0.0.1:8080/inference"
files = {'file': open('output.wav', 'rb')}
data = {
'response_format': 'json',  # 要求返回 JSON
'temperature': '0.0'        # 降低随机性，提高确定性
}

response = requests.post(url, files=files, data=data)
print(response.json())
返回包含 text 和 segments (带时间戳) 的 JSON 数据

方案 B：进程内 C API 调用 (性能最高)
如果你使用 C++/Rust/Go，可以直接链接 whisper.h。核心流程伪代码：
whisper_init_from_file("model.bin") 加载模型到内存（启动时只做一次）。
读取 PCM 音频数据到 std::vector<float>。
配置 whisper_full_params (设置语言、线程数、VAD)。
调用 whisper_full(ctx, params, pcm_data, pcm_size)。
遍历 whisper_full_n_segments(ctx) 提取文本和时间戳。
程序退出时 whisper_free(ctx)。

🚀 六、 性能优化与避坑指南

内存泄漏与常驻：
坑：每次识别都重新加载 1.1GB 的模型，会导致极大的 IO 和内存开销。
解：必须使用 Server 模式 或在应用中单例化模型加载。模型加载后常驻内存，后续请求只传入音频数据。
长音频 OOM (内存溢出)：
坑：CPU 推理时，如果音频超过 30 分钟，可能会因为上下文累积导致内存暴涨。
解：在业务层将长音频按 10-15 分钟切片（使用 FFmpeg），分批次送入 whisper.cpp，最后再拼接结果。
中文标点符号问题：
坑：Whisper 对中文标点的预测有时不稳定（如全用逗号，或没有标点）。
解：在 whisper.cpp 输出后，串联一个轻量级的 NLP 标点恢复模型（如基于 BERT 的标点模型），或者使用 LLM（如本地跑个 Qwen2.5-1.5B）对文本进行二次润色和分段。
AMD 显卡的"余热利用"：
虽然 Whisper 跑在 CPU 上，但你可以利用 AMD 显卡做音频降噪（如使用基于 DirectML 的降噪模型预处理音频），或者在后续步骤中用显卡跑 LLM 进行文本摘要/翻译，实现 CPU+AMD GPU 的异构协同。

📋 实施 CheckList
[ ] 安装 VS2022 C++ 工具和 CMake。
[ ] 成功编译 whisper-cli 和 whisper-server。
[ ] 下载 ggml-large-v3-turbo-q5_0.bin 和 silero_vad.onnx。
[ ] 编写 FFmpeg 脚本，将测试视频转为 16kHz WAV。
[ ] 启动 Server 模式，用 Postman/Python 发送请求验证 JSON 返回。
[ ] 调整线程数 -t，观察任务管理器，找到 CPU 占用与速度的最佳平衡点。

---

📌 七、 实际实施记录（2026-08 于 youdub-java 仓库）

本文档前半部分为通用选型指南；以下为在本仓库（Java/Spring Boot + Docker）中落地 whisper-cpp 的真实方案与踩坑记录。

### 7.1 集成方式（与上文推荐不同：选子进程而非 Server 模式）

本仓库 ASR 阶段使用 **whisper-cli 子进程**（非 HTTP Server / C API），原因：

- ASR 并发锁已由 `PipelineOrchestrator`（pipeline.asr 并发=1）+ `WHISPER_CPP_MAX_CONCURRENT=1` 保证串行，常驻 Server 收益有限。
- 子进程天然故障隔离：whisper.cpp 崩溃不会影响 JVM。
- 模型懒下载逻辑与 whisper-onnx 对齐，镜像不随模型增大。

调用链：`WhisperCppRecognizer` → `CommandRunner` 执行 `whisper-cli` → 解析 `-ojf` 词级 JSON。

### 7.2 构建：静态链接（关键修复）

**坑**：Linux 下 whisper.cpp 默认 `BUILD_SHARED_LIBS=ON`（与 MINGW 不同），产物动态链接 `libwhisper.so.1`，基础镜像中缺失导致 `whisper-cli: error while loading shared libraries`。

**修复**：Dockerfile whisper-build 阶段加 `-DBUILD_SHARED_LIBS=OFF`，产物为 2.8MB 单静态二进制，直接 COPY 进基础镜像。

### 7.3 超时与线程

- **超时**：`WhisperCppRecognizer` 原实现把 `timeoutMs=0` 回退为硬性 600s，导致 10min 分片在 8 线程 CPU 上超时（每片实际 15-16min）。修复为 `timeoutMs=0` 时传 `0L`（无限等待）。
- **线程**：`threads` 默认 4→8（匹配服务器 8 物理线程）。

### 7.4 控制 token 污染（重要坑）

**现象**：`-ojf` 输出的 tokens 文本含 `[_BEG_]` 和 `[_TT_nnn]`（注意**带前导空格**，如 `" [_BEG_]"`）。未过滤时真实任务产出 548 utterances 中有 376 个 `[_TT_]`、73 个 `[_BEG_]`，文本被污染。

**修复**：`isWhisperCppControlToken()` —— `text.trim()` 后匹配 `[_BEG_]` 或正则 `[_TT_\d+]`，在 token 展平阶段过滤。单测：`WhisperCppRecognizerTest`（4 个用例）。

**效果**：同一任务重跑后 utterances 548→477，翻译句数 335→316，零 control token。

### 7.5 服务器基准数据（真实任务 27min 音频，E3-1231 v3 / 8 线程）

| Provider | 模型 | ASR 耗时 | RTF | 质量 |
|---|---|---|---|---|
| whisper-onnx | medium.en (244M) | 50m46s | 1.88x | 一般（误识别多） |
| **whisper-cpp（默认）** | large-v3-turbo Q5_0 (574M) | 57m23s | 2.11x | 好（术语/专名准确） |

- whisper-cpp 较 ONNX 慢约 13%，但模型大 2.3 倍，识别质量显著更高 → **默认 provider 定为 whisper-cpp**（ONNX 保留为降级选项）。
- 单片段（180s 音频，无并发干扰）实测 288s，RTF 1.6x。
- 长音频分片：FFmpeg 预切 10min/片 + 逐片 `whisper-cli -ojf`，片间时间戳通过分片偏移校正。

### 7.6 模型下载（服务器网络受限场景）

- huggingface.co 直连超时，需走代理：`-x http://dengwei.local:7890`。
- 模型路径：`/app/data/whisper-models/`（挂载 `/mnt/e/youdub-data/whisper-models/`）。
  - `ggml-large-v3-turbo-q5_0.bin`（574MB）
  - `ggml-silero-v6.2.0.bin`（885KB，VAD）

### 7.7 部署清单

- `backend/Dockerfile`：whisper-build 阶段（whisper.cpp v1.9.2 tag，`-DBUILD_SHARED_LIBS=OFF`）。
- `application.yml`：`asr.provider: whisper-cpp`（默认）；`whisper-cpp.threads: 8`。
- SQLite `settings` 表 `asr.provider` 键可在运行时覆盖默认值（优先级高于 yml）。
- ASR 配置项：`model` / `vad-model` / `vad` / `threads` / `beam-size`（settings 页可改）。