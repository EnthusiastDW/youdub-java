"""
VoxCPM TTS 微服务 —— 基于 FastAPI 的声音克隆 TTS API。

端点：
  POST /v1/tts         — 生成 TTS 音频（multipart: text + ref_audio）
  GET  /health          — 健康检查
  GET  /                — 服务信息

环境变量：
  VOXCPM_MODEL       - 模型名称或路径，默认 "OpenBMB/VoxCPM2"
  VOXCPM_MODEL_DIR   - 本地模型目录（非空时优先）
  VOXCPM_LOAD_DENOISER - 是否加载降噪器 ("true"/"false")
  VOXCPM_CFG_VALUE   - CFG 值，默认 2.0
  VOXCPM_INFERENCE_TIMESTEPS - 推理步数，默认 10
  VOXCPM_SERVICE_PORT - 服务端口，默认 8001
  MODEL_CACHE_DIR    - 模型缓存目录
  CUDA_DEVICE        - CUDA 设备（例如 "cuda:0"），默认由 voxcpm 自行选择
"""

from __future__ import annotations

import logging
import os
import tempfile
import time
import traceback
from contextlib import asynccontextmanager
from pathlib import Path

import soundfile as sf
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | %(name)s | %(message)s",
)
log = logging.getLogger("voxcpm-service")

# ── 环境变量 ──────────────────────────────────────────────
VOXCPM_MODEL: str = os.environ.get("VOXCPM_MODEL", "OpenBMB/VoxCPM2")
VOXCPM_MODEL_DIR: str = os.environ.get("VOXCPM_MODEL_DIR", "")
VOXCPM_LOAD_DENOISER: bool = os.environ.get("VOXCPM_LOAD_DENOISER", "false").lower() == "true"
VOXCPM_CFG_VALUE: float = float(os.environ.get("VOXCPM_CFG_VALUE", "2.0"))
VOXCPM_INFERENCE_TIMESTEPS: int = int(os.environ.get("VOXCPM_INFERENCE_TIMESTEPS", "10"))
SERVICE_PORT: int = int(os.environ.get("VOXCPM_SERVICE_PORT", "8001"))
MODEL_CACHE_DIR: str = os.environ.get("MODEL_CACHE_DIR", "/app/data/modelscope")
CUDA_DEVICE: str = os.environ.get("CUDA_DEVICE", "")

_model_instance = None


def _resolve_model_path() -> str:
    """解析模型路径：优先 VOXCPM_MODEL_DIR，否则从 ModelScope 下载。"""
    if VOXCPM_MODEL_DIR:
        path = Path(VOXCPM_MODEL_DIR).expanduser()
        if path.exists():
            log.info("使用本地模型目录: %s", path)
            return str(path)
        log.warning("VOXCPM_MODEL_DIR=%s 不存在，回退到 ModelScope", path)

    model_id = VOXCPM_MODEL
    local_dir = Path(MODEL_CACHE_DIR) / model_id.replace("/", "__")
    if local_dir.exists():
        log.info("缓存命中: %s", local_dir)
        return str(local_dir)

    log.info("从 ModelScope 下载模型: %s -> %s", model_id, local_dir)
    from modelscope import snapshot_download

    downloaded = snapshot_download(model_id, local_dir=str(local_dir))
    return str(Path(downloaded))


def _load_model():
    global _model_instance
    if _model_instance is not None:
        return _model_instance

    model_path = _resolve_model_path()
    log.info("加载 VoxCPM 模型: path=%s, load_denoiser=%s", model_path, VOXCPM_LOAD_DENOISER)

    if CUDA_DEVICE:
        os.environ["CUDA_VISIBLE_DEVICES"] = CUDA_DEVICE.replace("cuda:", "")

    t0 = time.time()
    from voxcpm import VoxCPM

    _model_instance = VoxCPM.from_pretrained(
        model_path,
        load_denoiser=VOXCPM_LOAD_DENOISER,
    )
    elapsed = time.time() - t0
    log.info("VoxCPM 模型加载完成，耗时 %.2f 秒", elapsed)
    return _model_instance


# ── FastAPI 应用 ────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    log.info("=" * 60)
    log.info("VoxCPM TTS 服务启动中 ...")
    log.info("  模型: %s", VOXCPM_MODEL)
    log.info("  denoiser: %s", VOXCPM_LOAD_DENOISER)
    log.info("  cfg: %s, steps: %s", VOXCPM_CFG_VALUE, VOXCPM_INFERENCE_TIMESTEPS)
    log.info("  端口: %d", SERVICE_PORT)
    log.info("=" * 60)

    # 启动时预热加载模型
    try:
        _load_model()
    except Exception as e:
        log.error("模型加载失败: %s\n%s", e, traceback.format_exc())
        raise

    yield

    global _model_instance
    if _model_instance is not None:
        log.info("释放 VoxCPM 模型资源 ...")
        _model_instance = None


app = FastAPI(title="VoxCPM TTS Service", version="1.0.0", lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_credentials=True, allow_methods=["*"], allow_headers=["*"])

# ── 工具 ────────────────────────────────────────────────────

def _generate(text: str, ref_audio_path: str | None) -> bytes:
    """调用 VoxCPM 生成 TTS 音频，返回 WAV 字节。"""
    model = _load_model()

    if ref_audio_path and Path(ref_audio_path).exists():
        wav = model.generate(
            text=text,
            reference_wav_path=ref_audio_path,
            cfg_value=VOXCPM_CFG_VALUE,
            inference_timesteps=VOXCPM_INFERENCE_TIMESTEPS,
        )
    else:
        # 无参考音频时使用默认说话人
        from voxcpm import VoxCPM as _VoxCPM  # noqa: F811
        wav = model.generate(
            text=text,
            cfg_value=VOXCPM_CFG_VALUE,
            inference_timesteps=VOXCPM_INFERENCE_TIMESTEPS,
        )

    # wav 是 numpy array，写入临时字节缓冲区
    tmp = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
    try:
        sf.write(tmp.name, wav, samplerate=model.tts_model.sample_rate)
        with open(tmp.name, "rb") as f:
            return f.read()
    finally:
        Path(tmp.name).unlink(missing_ok=True)


# ── 端点 ────────────────────────────────────────────────────

@app.post("/v1/tts")
async def tts(
    text: str = Form(..., description="目标文本"),
    ref_audio: UploadFile | None = File(None, description="参考音频 WAV（可选）"),
):
    """
    生成 TTS 音频。

    上传方式：multipart/form-data
      - text: 要合成的目标文本（必填）
      - ref_audio: 参考音频 WAV 文件（可选，留空则使用默认说话人）

    返回：audio/wav 二进制
    """
    if not text or not text.strip():
        raise HTTPException(status_code=422, detail="text 不能为空")

    ref_path: str | None = None
    if ref_audio and ref_audio.filename:
        suffix = Path(ref_audio.filename).suffix or ".wav"
        tmp_ref = tempfile.NamedTemporaryFile(delete=False, suffix=suffix)
        try:
            content = await ref_audio.read()
            if len(content) == 0:
                raise HTTPException(status_code=422, detail="参考音频文件为空")
            tmp_ref.write(content)
            tmp_ref.close()
            ref_path = tmp_ref.name
        except HTTPException:
            Path(tmp_ref.name).unlink(missing_ok=True)
            raise
        except Exception as e:
            Path(tmp_ref.name).unlink(missing_ok=True)
            raise HTTPException(status_code=500, detail=f"参考音频处理失败: {e}")

    log.info("TTS 请求: text='%s', ref=%s", text[:50], "yes" if ref_path else "no")

    try:
        wav_bytes = _generate(text.strip(), ref_path)
        log.info("TTS 生成成功: %d bytes", len(wav_bytes))
        from fastapi.responses import Response
        return Response(content=wav_bytes, media_type="audio/wav")
    except Exception as e:
        log.error("TTS 生成失败: %s\n%s", e, traceback.format_exc())
        raise HTTPException(status_code=500, detail=f"TTS 生成失败: {e}")
    finally:
        if ref_path:
            Path(ref_path).unlink(missing_ok=True)


@app.post("/v1/tts/batch")
async def tts_batch(
    texts: str = Form(..., description="JSON 数组: [{\"text\":\"...\", \"index\":0}, ...]"),
    ref_audio: UploadFile | None = File(None, description="参考音频 WAV（所有文本共享）"),
):
    """
    批量 TTS 生成。

    所有文本共享同一个参考音频。返回 ZIP 文件，内含 0001.wav, 0002.wav, ...
    """
    import json
    import zipfile
    from io import BytesIO

    try:
        items = json.loads(texts)
    except json.JSONDecodeError as e:
        raise HTTPException(status_code=422, detail=f"texts JSON 解析失败: {e}")

    if not isinstance(items, list) or not items:
        raise HTTPException(status_code=422, detail="texts 必须是非空 JSON 数组")

    ref_path: str | None = None
    if ref_audio and ref_audio.filename:
        suffix = Path(ref_audio.filename).suffix or ".wav"
        tmp_ref = tempfile.NamedTemporaryFile(delete=False, suffix=suffix)
        try:
            content = await ref_audio.read()
            tmp_ref.write(content)
            tmp_ref.close()
            ref_path = tmp_ref.name
        except Exception as e:
            Path(tmp_ref.name).unlink(missing_ok=True)
            raise HTTPException(status_code=500, detail=f"参考音频处理失败: {e}")

    log.info("批量 TTS: %d 句, ref=%s", len(items), "yes" if ref_path else "no")
    model = _load_model()

    zip_buffer = BytesIO()
    try:
        with zipfile.ZipFile(zip_buffer, "w", zipfile.ZIP_DEFLATED) as zf:
            for item in items:
                text = item.get("text", "").strip()
                idx = item.get("index", 0)
                if not text:
                    continue

                wav = model.generate(
                    text=text,
                    reference_wav_path=ref_path,
                    cfg_value=VOXCPM_CFG_VALUE,
                    inference_timesteps=VOXCPM_INFERENCE_TIMESTEPS,
                )

                tmp_wav = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
                try:
                    sf.write(tmp_wav.name, wav, samplerate=model.tts_model.sample_rate)
                    zf.write(tmp_wav.name, f"{idx:04d}.wav")
                finally:
                    Path(tmp_wav.name).unlink(missing_ok=True)
    finally:
        if ref_path:
            Path(ref_path).unlink(missing_ok=True)

    zip_buffer.seek(0)
    from fastapi.responses import Response
    return Response(content=zip_buffer.read(), media_type="application/zip")


@app.get("/health")
async def health():
    model_loaded = _model_instance is not None
    return {
        "status": "ok" if model_loaded else "loading",
        "model": VOXCPM_MODEL,
        "service": "voxcpm-tts",
    }


@app.get("/")
async def root():
    return {
        "service": "VoxCPM TTS Service",
        "version": "1.0.0",
        "model": VOXCPM_MODEL,
        "denoiser": VOXCPM_LOAD_DENOISER,
        "endpoints": {
            "tts": "POST /v1/tts (multipart: text + ref_audio)",
            "batch_tts": "POST /v1/tts/batch (multipart: texts JSON + ref_audio)",
            "health": "GET /health",
        },
    }


# ── 入口 ────────────────────────────────────────────────────

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app:app", host="0.0.0.0", port=SERVICE_PORT, workers=1, log_level="info")
