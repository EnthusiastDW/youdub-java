# VoxCPM2 C++（voxcpm-cpp）开发指南

> youdub-java 仓库中 VoxCPM2 C++ TTS 引擎（llama.cpp-omni `voxcpm2-cli`）的集成方案与踩坑记录。
> 参考 whisper-cpp 的集成模式（CLI 子进程 + 编译进 base 容器 + 模型懒下载）。

## 1. 架构与选型

| 组件 | 选型 | 理由 |
|------|------|------|
| 推理引擎 | **llama.cpp-omni** (`tc-mb/llama.cpp-omni`) | VoxCPM 官方 README 推荐的 C++ 实现，生产级，支持 VoxCPM2 全功能（TTS / 声音克隆 / 音色设计 / 流式） |
| CLI | `voxcpm2-cli` | 与 whisper-cli 相同的**子进程**模式，天然故障隔离（C++ 崩溃不影响 JVM） |
| 权重格式 | GGUF（BaseLM + Acoustic 两个文件） | CPU/CUDA/Metal/Vulkan 通用，无需 Python/PyTorch |
| 权重仓库 | `DennisHuang648/VoxCPM2-GGUF` (HuggingFace) | 社区转换好的 GGUF 权重 |
| 后端 | Java 子进程（`CommandRunner`） | 与 `WhisperCppRecognizer` / `EdgeTtsProvider` 完全一致的集成模式 |
| 前端 | 设置页可选 provider（`voxcpm-cpp`） | 与 whisper-cpp 模型下拉框同款交互 |

**调用链**：
```
VoxCpmCppTtsProvider → CommandRunner → voxcpm2-cli (子进程) → segments/tts/XXXX.wav
```

## 2. 关键版本约束（重要坑）

- **`voxcpm2-cli` 自 `b262` 起才合并进 llama.cpp-omni 仓库**。
  `v1.0.22` 及其之前的 tag **均无此 CMake 目标**，clone 后 `--target voxcpm2-cli` 会直接报错。
  Dockerfile 固定 `--branch b262`（= master HEAD `09f5c3f1b484759f17b06fc63574f749c89c8761`）。
- **构建镜像无 `libssl-dev`**：llama.cpp-omni 的 `LLAMA_OPENSSL` 默认 `ON`，会执行
  `find_package(OpenSSL REQUIRED)` 失败 → 必须 `-DLLAMA_OPENSSL=OFF`。
- **静态链接**：与 whisper.cpp 相同的坑——Linux 下默认 `BUILD_SHARED_LIBS=ON`，
  产物动态链接 `libggml.so` 等，JRE 基础镜像中缺失 → 必须 `-DBUILD_SHARED_LIBS=OFF`。

## 3. 构建（Dockerfile `voxcpm-build` 阶段）

```dockerfile
FROM ubuntu:22.04 AS voxcpm-build
WORKDIR /src
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        cmake \
        git \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch b262 \
        https://github.com/tc-mb/llama.cpp-omni.git
RUN cmake -B build -S llama.cpp-omni \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=OFF \
        -DLLAMA_CURL=OFF \
        -DLLAMA_OPENSSL=OFF \
        -DLLAMA_BUILD_TESTS=OFF \
        -DLLAMA_BUILD_EXAMPLES=OFF \
        -DLLAMA_BUILD_SERVER=OFF \
    && cmake --build build --config Release -j$(nproc) --target voxcpm2-cli
```

产物路径：`/src/build/bin/voxcpm2-cli`（CMake `CMAKE_RUNTIME_OUTPUT_DIRECTORY` 固定输出到 `bin/`）。
基础层注入：

```dockerfile
COPY --from=voxcpm-build /src/build/bin/voxcpm2-cli /usr/local/bin/voxcpm2-cli
```

## 4. 权重下载（懒下载，幂等）

- **BaseLM**：`VoxCPM2-BaseLM-Q8_0.gguf`（~1.6GB，推荐量化）
- **Acoustic**：`VoxCPM2-Acoustic-F16.gguf`（~1.7GB）
- 可选：`VoxCPM2-BaseLM-F16.gguf`（~3.0GB，精度更高、更慢）

下载源：`https://huggingface.co/DennisHuang648/VoxCPM2-GGUF/resolve/main/{文件名}`

由 `VoxCpmCppModels`（`service/adapter/tts/`）管理，与 `WhisperCppModels` 同款：
`Models.download()` 支持代理（`HTTPS_PROXY` 环境变量），文件已存在则跳过。
模型目录默认 `data/voxcpm-models`（Docker 挂载 `/mnt/e/youdub-data/voxcpm-models`）。

> 注意：服务器访问 HuggingFace 需代理（`-x http://dengwei.local:7890`），与 whisper-cpp 相同。

## 5. CLI 参数（b262 源码实证）

```
voxcpm2-cli [options] <BaseLM.gguf> <Acoustic.gguf>
```

| 参数 | 说明 | 本项目默认 |
|------|------|-----------|
| `-t, --text` | 待合成文本（必填） | 翻译条目 `dst` |
| `-o, --output` | 输出 WAV 路径 | `segments/tts/%04d.wav` |
| `-r, --reference` | 声音克隆参考 WAV | `segments/vocals/%04d.wav`（存在时） |
| `--cfg` | CFG guidance scale | `2.0`（设置页可调） |
| `--timesteps` | CFM 推理步数 | `10`（设置页可调） |
| `--seed` | 随机种子 | `42`（设置页可调） |
| `--cpu` | **强制 CPU 后端**（容器无 GPU，必须加） | 固定 |
| `--steps` | 最大解码步数 | 默认 200（未暴露） |
| `--temperature` | 噪声温度 | 默认 1.0（未暴露） |

位置参数顺序：**BaseLM 在前，Acoustic 在后**（解析时先填 base_lm_path 再填 acoustic_path）。

## 6. 配置项

`application.yml`：

```yaml
app:
  tts:
    voxcpm-cpp:
      path: voxcpm2-cli                    # CLI 可执行文件
      model-dir: ${APP_TTS_VOXCPM_CPP_MODEL_DIR:data/voxcpm-models}
      base-lm-model: VoxCPM2-BaseLM-Q8_0.gguf
      acoustic-model: VoxCPM2-Acoustic-F16.gguf
      cfg-value: 2.0
      timesteps: 10
      seed: 42
      timeout-ms: 600000                   # 单句合成超时
      concurrency: 1                       # 每个 voxcpm2-cli 进程加载 ~3.3GB 权重，串行最稳
```

设置页可改字段：`baseLmModel`（下拉，来自 `/api/settings/voxcpm-cpp/models`）、
`acousticModel`、`cfgValue`、`timesteps`、`seed`。DB 设置优先于 yml 默认值。

## 7. 服务器部署与验证

```bash
# 服务器（/opt/youdub-java）
git pull
docker compose up -d --build backend     # 重建镜像（含 voxcpm-build 阶段）

# 验证 CLI 已注入
docker exec youdub-backend voxcpm2-cli --help | head -5

# 验证模型清单 API
curl http://localhost:8000/api/settings/voxcpm-cpp/models

# 首次合成会触发 ~3.3GB 权重下载（走代理），耗时取决于网络
```

## 8. 注意事项

- **性能**：CPU 推理 + 3.3GB 权重逐句加载，单句耗时数秒级，长视频整体 TTS 阶段明显慢于 HTTP voxcpm。
  默认 `concurrency=1` 防 OOM（容器 `mem_limit: 10g`）；如需加速可调大 `timesteps` 之外的参数并观察内存。
- **内存**：每个 `voxcpm2-cli` 进程峰值内存约 4-5GB（Q8 BaseLM + F16 Acoustic 全量加载），
  并发 >1 时请确认容器内存上限足够。
- **声音克隆**：`-r` 参考音频必须是合法 RIFF/WAVE 文件（`segments/vocals/` 由 FFmpeg 切分产生，满足要求）；
  无参考音频时退化为普通 TTS（音色设计写法 `(描述)文本` 仍可用）。
- **输出格式**：`voxcpm2-cli` 固定输出 **16-bit mono PCM WAV**（48kHz，`runtime.sample_rate()`）。
  下游 `merge_audio` 以 24kHz 解码并 atempo 变速适配时间轴，与其他 TTS 方案行为一致。
