"""
faster-whisper ASR 服务 —— 基于 FastAPI 的 OpenAI 兼容语音识别 API。

该服务提供了与 OpenAI Whisper API 完全兼容的端点：
  POST /v1/audio/transcriptions  — 语音转写（OpenAI verbose_json/json 格式）
  GET  /health                    — 健康检查
  GET  /                          — 欢迎页

环境变量配置（全部有默认值，零配置即可运行）：
  WHISPER_MODEL        - 模型名称，默认 "base"（tiny/base/small/medium/large-v3）
  WHISPER_DEVICE       - 推理设备，默认 "cpu"
  WHISPER_COMPUTE_TYPE - 计算类型，默认 "int8"
  WHISPER_CPU_THREADS  - CPU 线程数，默认 4
  WHISPER_NUM_WORKERS  - 工作进程数，默认 1（uvicorn workers）
  ASR_SERVICE_PORT     - 服务端口，默认 9000
"""

import os
import time
import logging
import tempfile
import traceback
import asyncio
from pathlib import Path
from contextlib import asynccontextmanager

from fastapi import FastAPI, UploadFile, File, Form, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware

# 日志配置
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | %(name)s | %(message)s",
)
logger = logging.getLogger("whisper-service")

# ------------------------------------------------------------
# 环境变量 & 全局配置
# ------------------------------------------------------------
WHISPER_MODEL: str = os.environ.get("WHISPER_MODEL", "base")
WHISPER_DEVICE: str = os.environ.get("WHISPER_DEVICE", "cpu")
WHISPER_COMPUTE_TYPE: str = os.environ.get("WHISPER_COMPUTE_TYPE", "int8")
WHISPER_CPU_THREADS: int = int(os.environ.get("WHISPER_CPU_THREADS", "4"))
ASR_SERVICE_PORT: int = int(os.environ.get("ASR_SERVICE_PORT", "9000"))

# 模型全局单例（懒加载）
_model_instance = None

# 全局并发锁：同一时间只处理一个音频，其余排队
_transcribe_lock = asyncio.Lock()


def get_model():
    """获取或初始化 WhisperModel 单例。"""
    global _model_instance
    if _model_instance is None:
        from faster_whisper import WhisperModel

        logger.info(
            "加载 Whisper 模型: model=%s, device=%s, compute_type=%s, cpu_threads=%d",
            WHISPER_MODEL, WHISPER_DEVICE, WHISPER_COMPUTE_TYPE, WHISPER_CPU_THREADS,
        )
        t0 = time.time()
        _model_instance = WhisperModel(
            WHISPER_MODEL,
            device=WHISPER_DEVICE,
            compute_type=WHISPER_COMPUTE_TYPE,
            cpu_threads=WHISPER_CPU_THREADS,
            num_workers=1,
        )
        elapsed = time.time() - t0
        logger.info("模型加载完成，耗时 %.2f 秒", elapsed)
    return _model_instance


# ------------------------------------------------------------
# 生命周期管理
# ------------------------------------------------------------
@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用启动/关闭时的生命周期钩子。"""
    logger.info("=" * 60)
    logger.info("Whisper ASR 服务启动中 ...")
    logger.info("  模型: %s", WHISPER_MODEL)
    logger.info("  设备: %s", WHISPER_DEVICE)
    logger.info("  计算类型: %s", WHISPER_COMPUTE_TYPE)
    logger.info("  CPU 线程数: %d", WHISPER_CPU_THREADS)
    logger.info("  端口: %d", ASR_SERVICE_PORT)
    logger.info("=" * 60)

    # 启动时预热加载模型
    get_model()

    yield

    # 关闭时清理
    global _model_instance
    if _model_instance is not None:
        logger.info("释放模型资源 ...")
        _model_instance = None


# ------------------------------------------------------------
# FastAPI 应用
# ------------------------------------------------------------
app = FastAPI(
    title="Whisper ASR Service",
    version="1.0.0",
    description="基于 faster-whisper 的 OpenAI 兼容语音识别服务",
    lifespan=lifespan,
)

# CORS：允许所有来源（容器内跨域调用）
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ------------------------------------------------------------
# 工具函数
# ------------------------------------------------------------
def convert_to_openai_format(segments, response_format: str) -> dict:
    """
    将 faster-whisper 的 segments 迭代器转为 OpenAI 标准响应格式。

    OpenAI verbose_json 格式：
    {
        "text": "完整文本",
        "duration": 12.34,           // 秒
        "segments": [
            {
                "start": 0.0,        // 秒
                "end": 5.2,          // 秒
                "text": "Hello world",
                "words": [
                    {"word": "Hello", "start": 0.0, "end": 1.2},
                    {"word": "world", "start": 1.2, "end": 2.5}
                ]
            }
        ]
    }
    """
    full_text_parts = []
    duration = 0.0
    openai_segments = []

    for seg in segments:
        # 积累全文
        full_text_parts.append(seg.text.strip())

        # 更新最晚结束时间作为总时长
        if seg.end > duration:
            duration = seg.end

        # 构建 segment
        seg_dict = {
            "start": round(seg.start, 3),  # 保留3位小数 ~ 毫秒精度
            "end": round(seg.end, 3),
            "text": seg.text.strip(),
        }

        # 如果模型返回了单词级时间戳，添加到 segment
        if seg.words:
            words_list = []
            for word in seg.words:
                words_list.append({
                    "word": word.word,
                    "start": round(word.start, 3),
                    "end": round(word.end, 3),
                })
            seg_dict["words"] = words_list

        openai_segments.append(seg_dict)

    full_text = " ".join(full_text_parts)

    if response_format == "json":
        # OpenAI json 格式：只返回 text
        return {"text": full_text}
    else:
        # OpenAI verbose_json 格式：返回完整结构
        return {
            "text": full_text,
            "duration": round(duration, 3),
            "segments": openai_segments,
        }


# ------------------------------------------------------------
# 端点：POST /v1/audio/transcriptions
# ------------------------------------------------------------
@app.post("/v1/audio/transcriptions")
async def transcribe(request: Request):
    """
    OpenAI 兼容的语音转写端点。

    支持两种请求方式：
      方式一（标准 multipart）：file + model + language + response_format 等 form 字段
      方式二（原始二进制）：https://localhost:9000/v1/audio/transcriptions?language=zh&response_format=verbose_json
                           Content-Type: application/octet-stream
                           请求体为原始音频字节

    返回：OpenAI 标准格式 JSON
    """
    content_type = request.headers.get("content-type", "").lower()

    # ---------- 读取请求体 ----------
    language = request.query_params.get("language")
    response_format = request.query_params.get("response_format", "verbose_json")
    audio_bytes = None
    audio_name = "audio"

    if "multipart/form-data" in content_type:
        # 方式一：标准 multipart 上传
        form = await request.form()
        file_field = form.get("file")
        if not file_field:
            raise HTTPException(status_code=400, detail="未提供音频文件 (file field)")
        if not hasattr(file_field, "read"):
            raise HTTPException(status_code=400, detail="file 字段格式不正确，请使用文件上传")
        audio_bytes = await file_field.read()
        audio_name = getattr(file_field, "filename", "audio") or "audio"
        language = language or form.get("language")
        response_format = response_format or form.get("response_format", "verbose_json")
    else:
        # 方式二：原始二进制上传（唯一 body 读取方式，无回退避免 Stream consumed）
        chunks = []
        async for chunk in request.stream():
            chunks.append(chunk)
        audio_bytes = b"".join(chunks)
        if not audio_bytes or len(audio_bytes) == 0:
            raise HTTPException(status_code=400, detail="请求体为空，请发送音频数据")

    # 校验 response_format
    if response_format not in ("json", "verbose_json"):
        raise HTTPException(
            status_code=400,
            detail=f"不支持的 response_format: {response_format}，仅支持 json 和 verbose_json",
        )

    logger.info(
        "收到转写请求: file=%s, size=%d, language=%s, response_format=%s",
        audio_name, len(audio_bytes), language or "auto", response_format,
    )

    # ---------- 保存临时文件 ----------
    suffix = Path(audio_name).suffix if Path(audio_name).suffix else ".wav"
    tmp_file = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
            tmp_file = tmp.name
            tmp.write(audio_bytes)

        file_size_mb = len(audio_bytes) / (1024 * 1024)
        logger.info("临时文件已保存: %s (%.2f MB)", tmp_file, file_size_mb)

        # ---------- 调用 faster-whisper ----------
        t0 = time.time()
        whisper_model = get_model()

        seg_kwargs = {"audio": tmp_file, "beam_size": 5}
        if language:
            seg_kwargs["language"] = language

        # 全局锁：同一时间只处理一个音频请求，其余排队等待
        async with _transcribe_lock:
            # transcribe() 返回 segments 生成器 + info 元数据
            # 注意：segments 是惰性的，实际转录发生在其被遍历时
            t_transcribe = time.time()
            segments, info = whisper_model.transcribe(**seg_kwargs)
            logger.info("开始转录: file=%s, 音频时长=%.2fs", audio_name, info.duration)

            # 遍历生成器 → 真正进行转录，结果格式化为 OpenAI 格式
            result = convert_to_openai_format(segments, response_format)
            elapsed = time.time() - t0
            transcribe_elapsed = time.time() - t_transcribe

            logger.info(
                "请求处理完成: file=%s, 音频时长=%.2fs, 转录耗时=%.2fs, 总计耗时=%.2fs",
                audio_name, info.duration, transcribe_elapsed, elapsed,
            )
            return result

    except HTTPException:
        raise
    except Exception as e:
        logger.error("转写失败: %s\n%s", e, traceback.format_exc())
        raise HTTPException(status_code=500, detail=f"转写失败: {str(e)}")
    finally:
        if tmp_file and os.path.exists(tmp_file):
            try:
                os.unlink(tmp_file)
            except Exception as e:
                logger.warning("临时文件删除失败: %s, %s", tmp_file, e)


# ------------------------------------------------------------
# 端点：GET /health — 健康检查
# ------------------------------------------------------------
@app.get("/health")
async def health():
    """健康检查端点。"""
    model_loaded = _model_instance is not None
    return {
        "status": "ok" if model_loaded else "loading",
        "model": WHISPER_MODEL,
        "device": WHISPER_DEVICE,
    }


# ------------------------------------------------------------
# 端点：POST /debug/echo — 调试（用于排查 body 读取问题）
# ------------------------------------------------------------
@app.post("/debug/echo")
async def debug_echo(request: Request):
    """调试端点：原样返回请求信息，用于排查 body 读取问题。"""
    chunks = []
    async for chunk in request.stream():
        chunks.append(chunk)
    body = b"".join(chunks)
    return {
        "method": request.method,
        "url": str(request.url),
        "headers": dict(request.headers),
        "body_size": len(body),
        "body_hex_preview": body[:200].hex() if body else "",
    }


# ------------------------------------------------------------
# 端点：GET / — 欢迎页
# ------------------------------------------------------------
@app.get("/")
async def root():
    """服务首页，显示当前配置状态。"""
    return {
        "service": "Whisper ASR Service",
        "version": "1.0.0",
        "status": "running" if _model_instance is not None else "starting",
        "config": {
            "model": WHISPER_MODEL,
            "device": WHISPER_DEVICE,
            "compute_type": WHISPER_COMPUTE_TYPE,
            "cpu_threads": WHISPER_CPU_THREADS,
        },
        "endpoints": {
            "transcribe": "POST /v1/audio/transcriptions",
            "health": "GET /health",
        },
    }


# ------------------------------------------------------------
# 直接运行入口
# ------------------------------------------------------------
if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app:app",
        host="0.0.0.0",
        port=ASR_SERVICE_PORT,
        workers=1,
        log_level="info",
    )
