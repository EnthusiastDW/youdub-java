# YouDub Replica

> 将 YouTube / Bilibili 视频自动配音为目标语言的完整管线工具。  
> Java/Spring Boot 重写版，保留原 Python YouDub 的管线流程，核心差异见下文。

---

## 项目简介

YouDub Replica 是一个视频自动配音管线系统。给定一个视频（YouTube URL、Bilibili URL 或本地上传），按以下 **9 个阶段** 依次处理：

1. **下载** — yt-dlp 下载视频
2. **人声分离** — 去除背景音乐，提取人声
3. **语音识别 (ASR)** — 将人声转写为文字
4. **时间修正** — 对 ASR 时间戳做 padding
5. **翻译** — 将原文翻译为目标语言
6. **音频切分** — 按句子时间戳裁剪人声片段
7. **语音合成 (TTS)** — 用目标语言生成配音（支持声音克隆）
8. **音频混合** — 将 TTS 片段拼接，与原始时间轴对齐
9. **视频合成** — 将配音 + 背景音乐 + 字幕合成最终视频

最终产物：一个带目标语言配音的字幕视频。

---

## 与原始 YouDub 的关键差异

本项目源自 [YouDub WebUI](https://github.com/)，但以 Java/Spring Boot 3 重写后端，并在架构上与 Python 原版有显著不同：

| 维度 | 原始 YouDub (Python) | YouDub Replica (Java) |
|------|---------------------|----------------------|
| **后端语言** | Python (FastAPI / Gradio) | Java 21 + Spring Boot 3.4 |
| **数据存储** | JSON 文件 / 轻量 DB | SQLite + JdbcTemplate |
| **任务管理** | 同步阻塞 / Celery | 线程池异步执行 + Future 追踪 |
| **Pipeline 编排** | Python 脚本顺序调用 | `PipelineOrchestrator` 阶段式编排，支持暂停/取消/继续/重做单阶段 |
| **适配器架构** | 无统一接口（直接 import Python 库） | 统一接口 + 策略注入：`TtsProvider`、`SpeechRecognizer`、`SourceSeparator`、`Downloader`、`Translator` 等 |
| **Python 模型调用** | 直接 in-process import（Whisper / Demucs / VoxCPM） | **封装为 HTTP API（微服务容器）**，Java 通过 HTTP 调用 |
| **前端** | Gradio | React 19 + TypeScript + Vite + Tailwind CSS 4 |
| **构建工具** | pip / conda | Maven + Docker Compose |
| **ASR** | faster-whisper（Python 进程内） | Whisper API（HTTP，可对接 OpenAI 或自建 faster-whisper 服务）|
| **人声分离** | Demucs（Python 进程内） | 3 种方案可选：FFmpeg(轻量) / Demucs子进程 / audio-separator Docker 服务 |
| **TTS** | VoxCPM（Python 进程内） | 3 种方案可选：VoxCPM(HTTP) / Edge-TTS(子进程) / OpenAI TTS(API) |
| **翻译** | OpenAI / 本地 LLM | OpenAI Chat API / Ollama（可插拔适配器） |
| **运行模式** | 单一 Python 进程 | 多容器微服务（Java 后端 + Python 服务 + 前端） |
| **失败恢复** | 有限支持 | 逐阶段状态持久化 + 启动恢复 + 手动模式（每阶段暂停确认）|

### 核心架构决策

#### 1. Python 模型 → HTTP API 封装

原版 YouDub 的所有 AI 模型（Demucs、Whisper、VoxCPM）都在 Python 进程中直接加载。本项目的 Python 模型全部运行在独立的 Docker 容器中 (`youdub-python-services`)，通过 HTTP 接口对 Java 后端暴露：

```
┌─────────────────────────────────────────────────────────┐
│  youdub-python-services (FastAPI)                       │
│  ┌──────────────────────────────────────────────────┐   │
│  │  POST /api/v1/separate          — audio-separator │   │
│  │  POST /v1/audio/transcriptions  — faster-whisper  │   │
│  │  POST /v1/tts                   — VoxCPM / llama  │   │
│  │  GET  /health                   — 统一健康检查     │   │
│  └──────────────────────────────────────────────────┘   │
└──────────────────────┬──────────────────────────────────┘
                       │ HTTP
┌──────────────────────▼──────────────────────────────────┐
│  Java Backend (Spring Boot)                             │
│  ┌──────────────────────────────────────────────────┐   │
│  │  VoxCpmTtsProvider → http://python:8001/v1/tts   │   │
│  │  WhisperApiRecognizer → http://.../transcriptions│   │
│  │  AudioSeparatorApiSeparator → http://.../separate│   │
│  └──────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

这样做的好处：
- **语言边界清晰**：Java 处理业务逻辑、管线编排、文件管理；Python 只负责模型推理
- **独立扩缩容**：Python 容器的 CUDA 资源与 Java 后端解耦
- **可替换性**：只要 HTTP 接口不变，Python 侧可以自由切换实现（如 whisper.cpp ↔ faster-whisper）
- **故障隔离**：Python 容器 OOM 不会波及 Java 进程

#### 2. 适配器模式

每个管线阶段都定义了 Java 接口，不同实现通过 Spring bean 名称注入：

```java
// 所有 TTS 实现都实现同一个接口
public interface TtsProvider {
    void synthesize(Task task, Path textPath, Path outputDir);
}

// Spring 运行时根据配置选择实现
private final Map<String, TtsProvider> ttsProviders; // key: bean name
```

当前可用的适配器：

| 阶段 | 接口 | 内置实现 |
|------|------|---------|
| 下载 | `Downloader` | `YtDlpDownloader`（yt-dlp 子进程）、`LocalFileDownloader`（本地上传）|
| 人声分离 | `SourceSeparator` | `FfmpegSimpleSeparator`（FFmpeg 频率滤波）、`DemucsSeparator`（本地 Python 进程）、`AudioSeparatorApiSeparator`（Docker API）|
| ASR | `SpeechRecognizer` | `WhisperApiRecognizer`（OpenAI 兼容 API）、`WhisperCppRecognizer`（whisper.cpp 子进程）|
| 翻译 | `Translator` | `OpenAiTranslator`（OpenAI Chat API）、`OllamaTranslator`（本地 Ollama）|
| TTS | `TtsProvider` | `VoxCpmTtsProvider`（VoxCPM API）、`EdgeTtsProvider`（edge-tts 子进程）、`OpenAiTtsProvider`（OpenAI TTS API）|
| 音频处理 | `AudioProcessor` | `FfmpegAudioProcessor`（FFmpeg 子进程）|
| 视频合成 | `VideoProcessor` | `FfmpegVideoProcessor`（FFmpeg 子进程）|

#### 3. 任务生命周期系统

与 Python 原版的同步/简单队列不同，本项目实现了完整的状态机：

```
QUEUED → RUNNING → SUCCEEDED
                  → FAILED   → QUEUED (resume)
                  → PAUSED   → QUEUED (continue)
                  → CANCELLED
```

- **手动模式**：每阶段完成后自动暂停，等待用户确认再继续
- **重做单阶段**：从指定阶段开始重置，保留之前阶段的结果
- **启动恢复**：服务重启时自动标记孤立任务为失败，重新入队排队中的任务
- **状态持久化**：每个阶段的进度、开始时间、完成时间、错误信息均写入 SQLite

---

## 技术栈

### 后端
- **Java 21** + **Spring Boot 3.4**
- SQLite (JDBC + JdbcTemplate，无 JPA)
- Lombok
- Jackson
- Maven

### 前端
- **React 19** + **TypeScript**
- Vite 6
- Tailwind CSS 4
- lucide-react (图标)

### Python 微服务
- **FastAPI** (uvicorn)
- **faster-whisper** (ASR)
- **audio-separator** (人声分离)
- **VoxCPM2** + **OpenVoice V2** (TTS 声音克隆)

### 基础设施
- Docker Compose (3 个容器：后端 + Python 服务 + 前端)
- yt-dlp (视频下载)
- FFmpeg (音频处理、视频合成)

---

## 快速开始

### 前置条件

- Docker & Docker Compose
- NVIDIA GPU + CUDA（可选，用于 GPU 加速。CPU 也可运行但较慢）
- OpenAI API Key（用于翻译）

### 方式一：Docker Compose（推荐）

```bash
# 1. 克隆仓库
git clone <repo-url>
cd youdub-java

# 2. 配置环境变量
cp .env.example .env
# 编辑 .env，填入 OPENAI_API_KEY 等配置

# 3. 启动所有服务
docker compose up -d

# 4. 访问 http://localhost:3000
```

### 方式二：本地开发

```bash
# 后端
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 前端
cd frontend
npm install
npm run dev

# Python 服务（需要单独启动）
cd docker/youdub-python-services
pip install -r requirements.txt
python server.py
```

---

## 配置

所有配置项通过环境变量或 `application.yml` 管理。关键项：

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| `OPENAI_API_KEY` | — | OpenAI API Key（翻译用，必填）|
| `TRANSLATE_MODEL` | `gpt-4o-mini` | 翻译模型 |
| `TTS_PROVIDER` | `voxcpm` | TTS 提供商：`voxcpm` / `edge-tts` / `openai-tts` |
| `APP_SEPARATE_PROVIDER` | `audio-separator-api` | 人声分离方案：`ffmpeg` / `demucs` / `audio-separator-api` |
| `APP_ASR_PROVIDER` | `whisper-api` | ASR 方案：`whisper-api` / `whisper-cpp` |
| `VOXCPM_SERVICE_URL` | `http://python-services:8001` | VoxCPM 服务地址 |

完整配置项见 [backend/src/main/resources/application.yml](backend/src/main/resources/application.yml)。

---

## 项目结构

```
youdub-java/
├── backend/                          # Java/Spring Boot 后端
│   ├── src/main/java/com/youdub/replica/
│   │   ├── config/                   # 配置类（AppProperties, AdapterConfig 等）
│   │   ├── controller/               # REST API 控制器
│   │   ├── dto/                      # 请求/响应 DTO
│   │   ├── logging/                  # 日志（MDC taskId）
│   │   ├── model/entity/             # 领域模型（Task, TaskStage）
│   │   ├── model/enums/              # 枚举（TaskStatus, StageStatus）
│   │   ├── repository/               # SQLite 数据访问层（JdbcTemplate）
│   │   ├── service/adapter/          # 适配器接口与实现
│   │   │   ├── download/             # 下载（yt-dlp, 本地上传）
│   │   │   ├── separate/             # 人声分离（FFmpeg, Demucs, API）
│   │   │   ├── asr/                  # 语音识别（Whisper API, whisper.cpp）
│   │   │   ├── translate/            # 翻译（OpenAI, Ollama）
│   │   │   ├── tts/                  # TTS（VoxCPM, Edge-TTS, OpenAI TTS）
│   │   │   ├── audio/                # 音频处理（FFmpeg 切分/混合）
│   │   │   └── video/                # 视频合成（FFmpeg 字幕+混音+编码）
│   │   ├── service/                  # 核心服务（TaskService, PipelineOrchestrator, WorkerService）
│   │   └── util/                     # 工具类（CommandRunner, DeviceResolver 等）
│   └── src/test/                     # 集成测试
├── docker/
│   └── youdub-python-services/       # Python 微服务（FastAPI + faster-whisper + audio-separator + VoxCPM/OpenVoice）
├── frontend/                         # React 前端
│   └── src/
│       ├── api/                      # API 调用
│       ├── components/               # UI 组件
│       ├── hooks/                    # React Hooks
│       ├── i18n/                     # 国际化
│       ├── pages/                    # 页面
│       └── types/                    # TypeScript 类型
├── docs/
│   └── pipeline.md                   # 管线各阶段详细文档
├── docker-compose.yml                # 主编排文件（3 个容器）
└── .env.example                      # 环境变量模板
```

---

## 详细文档

- [管线各阶段详细说明](docs/pipeline.md) — 每个阶段的输入/输出、数据格式、时间戳处理策略

---

## 许可

Apache-2.0
