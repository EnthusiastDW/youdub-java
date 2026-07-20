"""
Unified Python Services API Server
Combines Audio Separator, Faster-Whisper ASR, and native VoxCPM TTS into one FastAPI application.

Endpoints:
  POST /api/v1/separate          - Audio separation (from audio-separator)
  POST /v1/audio/transcriptions  - Whisper ASR (from faster-whisper)
  POST /v1/tts                   - VoxCPM TTS (native voxcpm PyTorch)
  POST /v1/tts/batch             - VoxCPM Batch TTS
  GET  /health                   - Unified health check
  GET  /                         - Service info
"""

import io
import os
import re
import json
import time
import shutil
import logging
import tempfile
import traceback
import asyncio
import zipfile
import subprocess
from pathlib import Path
from typing import Optional, List, Dict, Any, Tuple
from dataclasses import dataclass
from contextlib import asynccontextmanager

from fastapi import FastAPI, APIRouter, File, Form, HTTPException, UploadFile, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response as FastAPIResponse, StreamingResponse

# ============================================================
# Logging Configuration
# ============================================================
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | %(name)s | %(message)s",
)
logger = logging.getLogger("unified-python-services")

# ============================================================
# Audio Separator Chunking Patch
# 原因：audio-separator 内置 AudioChunker 用 pydub 全量加载输入音频再分块，
# 对 3 小时以上的长音频会直接占满内存。这里用 ffmpeg 做分块/合并，全程磁盘 I/O。
# ============================================================
try:
    from audio_separator.separator.audio_chunking import AudioChunker

    def _ffmpeg_split_audio(self, input_path: str, output_dir: str) -> List[str]:
        if not os.path.exists(input_path):
            raise FileNotFoundError(f"Input file not found: {input_path}")
        os.makedirs(output_dir, exist_ok=True)

        chunk_seconds = self.chunk_duration_ms / 1000
        overlap_seconds = 2.0

        probe = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", input_path],
            capture_output=True, text=True, check=True
        )
        total_duration = float(probe.stdout.strip())

        chunk_paths = []
        start = 0.0
        idx = 0
        while start < total_duration:
            # 每块后缘多取 overlap_seconds，给下一块做 crossfade；
            # 最后一块直接到文件末尾。
            end = min(start + chunk_seconds + overlap_seconds, total_duration)
            if end >= total_duration:
                end = total_duration

            out_path = os.path.join(output_dir, f"chunk_{idx:04d}.wav")
            cmd = [
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-i", input_path,
                "-ss", str(start), "-to", str(end),
                "-c:a", "pcm_s16le",
                "-ar", "44100",
                "-ac", "2",
                out_path,
            ]
            self.logger.info(
                "Splitting chunk %d: [%.3f, %.3f] (overlap=%.1fs) from %s",
                idx, start, end, overlap_seconds, input_path
            )
            subprocess.run(cmd, check=True)
            chunk_paths.append(out_path)

            start += chunk_seconds
            idx += 1

        self.logger.info("Created %d chunks with %.1fs crossfade overlap", len(chunk_paths), overlap_seconds)
        return chunk_paths

    def _ffmpeg_merge_chunks(self, chunk_paths: List[str], output_path: str) -> str:
        if not chunk_paths:
            raise ValueError("Cannot merge empty list of chunks")
        for p in chunk_paths:
            if not os.path.exists(p):
                raise FileNotFoundError(f"Chunk file not found: {p}")

        overlap_seconds = 2.0
        if len(chunk_paths) == 1:
            shutil.copy(chunk_paths[0], output_path)
            return output_path

        inputs = []
        for p in chunk_paths:
            inputs.extend(["-i", p])

        # 相邻 chunk 之间做 2 秒 crossfade，避免简单拼接产生切痕。
        filter_parts = []
        prev = "[0]"
        for i in range(1, len(chunk_paths)):
            out = f"[out{i}]" if i < len(chunk_paths) - 1 else "[out]"
            filter_parts.append(
                f"{prev}[{i}]acrossfade=d={overlap_seconds}:c1=tri:c2=tri{out}"
            )
            prev = out

        cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error"] + inputs + [
            "-filter_complex", ";".join(filter_parts),
            "-map", "[out]",
            output_path,
        ]
        self.logger.info(
            "Merging %d chunks into %s with %.1fs crossfade using ffmpeg",
            len(chunk_paths), output_path, overlap_seconds
        )
        subprocess.run(cmd, check=True)
        return output_path

    AudioChunker.split_audio = _ffmpeg_split_audio
    AudioChunker.merge_chunks = _ffmpeg_merge_chunks
except Exception as _chunking_patch_err:
    logger.warning("Failed to patch AudioChunker with ffmpeg implementation: %s", _chunking_patch_err)

# ============================================================
# Environment Variables & Configuration
# ============================================================

# Audio Separator Config
MODEL_DIR = Path(os.environ.get("MODEL_DIR", "/app/data/separator-models"))
MODEL_DIR.mkdir(parents=True, exist_ok=True)
SEPARATOR_MODEL = os.environ.get("SEPARATOR_MODEL", "UVR-MDX-NET-Inst_HQ_3.onnx")
SEPARATOR_NUM_PROCESSES = int(os.environ.get("SEPARATOR_NUM_PROCESSES", str(os.cpu_count() or 4)))

# Whisper ASR Config (raw env, auto-detected later)
WHISPER_MODEL = os.environ.get("WHISPER_MODEL", "base")
_whisper_device_env = os.environ.get("WHISPER_DEVICE", "")
_whisper_compute_env = os.environ.get("WHISPER_COMPUTE_TYPE", "")
WHISPER_CPU_THREADS = int(os.environ.get("WHISPER_CPU_THREADS", str(os.cpu_count() or 4)))

# VoxCPM TTS Config
VOXCPM_MODEL = os.environ.get("VOXCPM_MODEL", "OpenBMB/VoxCPM2")
VOXCPM_MODEL_DIR = os.environ.get("VOXCPM_MODEL_DIR", "")
VOXCPM_LOAD_DENOISER = os.environ.get("VOXCPM_LOAD_DENOISER", "false").lower() == "true"
VOXCPM_CFG_VALUE = float(os.environ.get("VOXCPM_CFG_VALUE", "2.0"))
VOXCPM_INFERENCE_TIMESTEPS = int(os.environ.get("VOXCPM_INFERENCE_TIMESTEPS", "10"))
VOXCPM_SERVICE_PORT = int(os.environ.get("VOXCPM_SERVICE_PORT", "8001"))
MODEL_CACHE_DIR = os.environ.get("MODEL_CACHE_DIR", "/app/data/modelscope")
CUDA_DEVICE = os.environ.get("CUDA_DEVICE", "")

VOXCPM_BACKEND = os.environ.get("VOXCPM_BACKEND", "auto").lower()

# ============================================================
# Auto-detect devices for all services
# Priority: explicit env var > CUDA_DEVICE > torch/onnxruntime
# ============================================================

def _is_cuda_available() -> bool:
    """Check CUDA availability via torch or onnxruntime (whichever service uses)."""
    if CUDA_DEVICE:
        return True
    try:
        import torch
        if torch.cuda.is_available():
            return True
    except Exception:
        pass
    try:
        import onnxruntime
        return 'CUDAExecutionProvider' in onnxruntime.get_available_providers()
    except Exception:
        pass
    return False

_HAS_CUDA = _is_cuda_available()

_ACTIVE_TTS_BACKEND = "voxcpm"

# Audio Separator
SEPARATOR_DEVICE = os.environ.get("SEPARATOR_DEVICE", "") or ("cuda" if _HAS_CUDA else "cpu")

# Whisper ASR
WHISPER_DEVICE = _whisper_device_env or ("cuda" if _HAS_CUDA else "cpu")
WHISPER_COMPUTE_TYPE = _whisper_compute_env or ("float16" if WHISPER_DEVICE == "cuda" else "int8")

# VoxCPM native backend device
if not CUDA_DEVICE and _HAS_CUDA:
    CUDA_DEVICE = "cuda:0"
VOXCPM_DEVICE = "cuda" if CUDA_DEVICE else "cpu"

logger.info("Devices: separator=%s, whisper=%s(%s), voxcpm_device=%s, tts_backend=%s",
            SEPARATOR_DEVICE, WHISPER_DEVICE, WHISPER_COMPUTE_TYPE, VOXCPM_DEVICE, _ACTIVE_TTS_BACKEND)

SERVICE_PORT = int(os.environ.get("SERVICE_PORT", "8001"))

# ============================================================
# Global Model Instances (Lazy Loading)
# ============================================================
_separator_instance: Optional[object] = None
_whisper_model_instance: Optional[object] = None
_voxcpm_pytorch_model_instance: Optional[object] = None


# Concurrency locks — Semaphore(1) ensures only one CPU-heavy task runs at a time
_transcribe_lock = asyncio.Lock()
_separate_sem = asyncio.Semaphore(1)
_tts_sem = asyncio.Semaphore(1)
_voxcpm_load_lock = asyncio.Lock()

# ============================================================
# Helper Functions
# ============================================================

def _safe_filename(name: str) -> str:
    """Sanitize filename for safe filesystem usage."""
    name = Path(name).name
    name = re.sub(r"[^\w.\-]", "_", name)
    return name or "upload"


def _resolve_voxcpm_model_path() -> str:
    """Resolve VoxCPM model path: prefer local dir, else download from ModelScope."""
    if VOXCPM_MODEL_DIR:
        path = Path(VOXCPM_MODEL_DIR).expanduser()
        if path.exists():
            logger.info("Using local VoxCPM model directory: %s", path)
            return str(path)
        logger.warning("VOXCPM_MODEL_DIR=%s does not exist, falling back to ModelScope", path)

    model_id = VOXCPM_MODEL
    local_dir = Path(MODEL_CACHE_DIR) / model_id.replace("/", "__")
    if local_dir.exists() and any(local_dir.iterdir()):
        logger.info("ModelScope cache hit: %s", local_dir)
        return str(local_dir)

    logger.info("Downloading VoxCPM model from ModelScope: %s -> %s", model_id, local_dir)
    from modelscope import snapshot_download
    downloaded = snapshot_download(model_id, local_dir=str(local_dir))
    return str(Path(downloaded))





# ============================================================
# Audio Separator Router (/api/v1)
# ============================================================

separator_router = APIRouter(prefix="/api/v1", tags=["Audio Separation"])


def _get_separator(model_filename: str):
    """Get or create audio separator instance (lazy loading)."""
    global _separator_instance
    from audio_separator.separator import Separator

    if _separator_instance is None or getattr(_separator_instance, "_loaded_model", None) != model_filename:
        logger.info("Loading audio separator model: %s (model_dir=%s)", model_filename, MODEL_DIR)
        _separator_instance = Separator(
            model_file_dir=str(MODEL_DIR),
            output_format="WAV",
            # 10 分钟一大块，避免整段加载爆内存，同时把磁盘 I/O 开销控制在 43 块
            chunk_duration=600,
            use_soundfile=True,
        )
        _separator_instance.load_model(model_filename=model_filename)
        _separator_instance._loaded_model = model_filename
    return _separator_instance


@separator_router.get("/models")
async def list_separator_models():
    """List available separator models in MODEL_DIR."""
    files = [f.name for f in sorted(MODEL_DIR.iterdir()) if f.suffix in (".onnx", ".pth", ".ckpt")]
    return {"model_dir": str(MODEL_DIR), "models": files}


@separator_router.post("/separate", response_class=StreamingResponse)
async def separate_audio(
    file: UploadFile = File(...),
    model_filename: str = Form(SEPARATOR_MODEL),
):
    """
    Upload an audio file and receive a ZIP containing:
      - vocals.wav        — extracted vocal stem
      - instrumental.wav  — everything else (background)
    """
    if not file.filename or not file.file:
        raise HTTPException(400, "No file provided.")

    t_total = time.time()
    input_size = 0

    tmp = tempfile.mkdtemp(prefix="audio-sep-")
    try:
        input_path = Path(tmp) / _safe_filename(file.filename)
        try:
            # 流式写入磁盘，避免大文件全量加载到内存
            with open(input_path, 'wb') as f:
                while chunk := await file.read(64 * 1024):
                    f.write(chunk)
        except Exception as exc:
            raise HTTPException(400, f"Failed to read upload: {exc}")

        input_size = input_path.stat().st_size
        if input_size == 0:
            raise HTTPException(400, "Uploaded file is empty.")

        logger.info("Separate request: file=%s, size=%dMB, model=%s, device=%s",
                    file.filename, input_size / (1024 * 1024), model_filename, SEPARATOR_DEVICE)

        try:
            t_sep = time.time()
            async with _separate_sem:
                separator = _get_separator(model_filename)
                output_files = await asyncio.to_thread(separator.separate, str(input_path))
            sep_elapsed = time.time() - t_sep
            logger.info("Separation inference done: duration=%.2fs, device=%s", sep_elapsed, SEPARATOR_DEVICE)
        except Exception as exc:
            raise HTTPException(500, f"Separation failed: {exc}")

        if not output_files:
            raise HTTPException(500, "Separation produced no output files.")

        vocal_path = instrumental_path = None
        for f in map(Path, output_files):
            name_lower = f.stem.lower()
            if "vocal" in name_lower:
                vocal_path = f
            else:
                instrumental_path = f

        # Fallback: first output is instrumental, second is vocal
        if vocal_path is None and len(output_files) >= 2:
            instrumental_path, vocal_path = Path(output_files[0]), Path(output_files[1])
        elif vocal_path is None:
            vocal_path = Path(output_files[0])

        t_zip = time.time()
        zip_path = os.path.join(tmp, "separated.zip")
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
            if vocal_path and vocal_path.exists():
                zf.write(vocal_path, "vocals.wav")
            if instrumental_path and instrumental_path.exists():
                zf.write(instrumental_path, "instrumental.wav")

        zip_size = os.path.getsize(zip_path)
        zip_elapsed = time.time() - t_zip
        total_elapsed = time.time() - t_total
        logger.info("Separate done: file=%s, total=%.2fs, zip=%.2fs, response=%dbytes, device=%s",
                    file.filename, total_elapsed, zip_elapsed, zip_size, SEPARATOR_DEVICE)

        def zip_stream():
            # 流式读取 ZIP，响应结束后清理临时目录和分离输出文件
            try:
                with open(zip_path, "rb") as f:
                    while chunk := f.read(4 * 1024 * 1024):
                        yield chunk
            finally:
                for p in (vocal_path, instrumental_path):
                    if p:
                        try:
                            os.unlink(p)
                        except Exception:
                            pass
                shutil.rmtree(tmp, ignore_errors=True)

        return StreamingResponse(
            zip_stream(),
            media_type="application/zip",
            headers={
                "Content-Disposition": f'attachment; filename="separated_{Path(file.filename).stem}.zip"'
            },
        )
    except Exception:
        shutil.rmtree(tmp, ignore_errors=True)
        raise


# ============================================================
# Faster-Whisper ASR Router (/v1/audio)
# ============================================================

whisper_router = APIRouter(prefix="/v1/audio", tags=["Speech Recognition"])


def _get_whisper_model():
    """Get or initialize WhisperModel singleton (lazy loading)."""
    global _whisper_model_instance
    if _whisper_model_instance is None:
        from faster_whisper import WhisperModel

        logger.info(
            "Loading Whisper model: model=%s, device=%s, compute_type=%s, cpu_threads=%d",
            WHISPER_MODEL, WHISPER_DEVICE, WHISPER_COMPUTE_TYPE, WHISPER_CPU_THREADS,
        )
        t0 = time.time()
        _whisper_model_instance = WhisperModel(
            WHISPER_MODEL,
            device=WHISPER_DEVICE,
            compute_type=WHISPER_COMPUTE_TYPE,
            cpu_threads=WHISPER_CPU_THREADS,
            num_workers=1,
        )
        elapsed = time.time() - t0
        logger.info("Whisper model loaded in %.2f seconds", elapsed)
    return _whisper_model_instance


_LANG_PUNCTUATION_PROMPTS = {
    "zh": "大家好，欢迎收听今天的节目。今天我们邀请了一位非常特别的嘉宾。让我们开始吧。",
    "en": "Hello everyone, welcome to today's show. We have a very special guest with us today. Let's get started.",
    "ja": "皆さん、こんにちは。今日の番組へようこそ。特別なゲストをお迎えしています。始めましょう。",
    "ko": "안녕하세요. 오늘 프로그램에 오신 것을 환영합니다. 특별한 게스트를 모셨습니다. 시작하겠습니다.",
}


def _punctuation_prompt(language: Optional[str]) -> Optional[str]:
    """Return an initial_prompt with proper punctuation for the given language.

    Helps guide Whisper out of 'no-punctuation mode' by priming it with
    correctly punctuated text. Falls back to a generic prompt for
    unrecognised languages.
    """
    if not language:
        return _LANG_PUNCTUATION_PROMPTS.get("en")
    lang = language.strip().lower()
    # Match common ISO codes and also partial prefixes (e.g. "zh-CN" → "zh")
    if lang in _LANG_PUNCTUATION_PROMPTS:
        return _LANG_PUNCTUATION_PROMPTS[lang]
    short = lang.split("-")[0].split("_")[0]
    if short in _LANG_PUNCTUATION_PROMPTS:
        return _LANG_PUNCTUATION_PROMPTS[short]
    return "Hello. Welcome! Is this working? Let's test, please."


# ============================================================
# ASR Audio Chunking — silence-based split to reduce peak memory
# ============================================================

SILENCE_MIN_DURATION = 2.0
SILENCE_OVERLAP = 2.0
SILENCE_NOISE_THRESHOLD = "-30dB"
MIN_CHUNK_DURATION = 30.0


def _detect_silence_splits(audio_path: str) -> List[float]:
    """
    Use ffmpeg silencedetect to find long silences and return split points
    (midpoints of each silence) in seconds. Returns empty list if no suitable
    split point is found or if ffmpeg fails.
    """
    try:
        cmd = [
            "ffmpeg", "-i", audio_path,
            "-af", f"silencedetect=noise={SILENCE_NOISE_THRESHOLD}:d={SILENCE_MIN_DURATION}",
            "-f", "null", "-",
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        stderr = proc.stderr

        silence_starts: List[float] = []
        silence_ends: List[float] = []
        for line in stderr.split("\n"):
            if "silence_start:" in line:
                try:
                    silence_starts.append(float(line.split("silence_start:")[1].strip()))
                except (IndexError, ValueError):
                    pass
            elif "silence_end:" in line:
                try:
                    val = line.split("silence_end:")[1].split("|")[0].strip()
                    silence_ends.append(float(val))
                except (IndexError, ValueError):
                    pass

        if not silence_starts or not silence_ends:
            return []

        split_points = []
        for ss, se in zip(silence_starts, silence_ends):
            if se - ss >= SILENCE_MIN_DURATION:
                split_points.append((ss + se) / 2.0)

        if not split_points:
            return []

        result = subprocess.run(
            [
                "ffprobe", "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", audio_path,
            ],
            capture_output=True, text=True,
        )
        total_dur = float(result.stdout.strip())
    except Exception:
        logger.warning("Failed to detect silence splits for %s, transcribing whole file", audio_path)
        return []

    return [
        sp for sp in split_points
        if sp > 60.0 and (total_dur - sp) > 60.0
    ]


def _build_chunk_boundaries(
    split_points: List[float], total_duration: float, overlap: float = SILENCE_OVERLAP,
) -> List[Tuple[float, float]]:
    """
    Build chunk (start, end) boundaries from silence midpoints.
    Each chunk straddles the split point with *overlap* seconds on each side,
    so adjacent chunks share a 2*overlap region centered on the silence.
    """
    if not split_points:
        return [(0.0, total_duration)]

    boundaries: List[Tuple[float, float]] = []
    prev_split = 0.0
    for sp in split_points:
        chunk_start = max(0.0, prev_split - overlap) if prev_split > 0 else 0.0
        chunk_end = min(total_duration, sp + overlap)
        if chunk_end > chunk_start:
            boundaries.append((chunk_start, chunk_end))
        prev_split = sp
    last_start = max(0.0, split_points[-1] - overlap)
    boundaries.append((last_start, total_duration))
    return boundaries


def _split_audio_chunks(
    audio_path: str, boundaries: List[Tuple[float, float]], output_dir: str,
) -> List[Tuple[str, float]]:
    """
    Extract each chunk as a 16kHz mono WAV using ffmpeg.
    Returns list of (chunk_path, chunk_start_offset).
    """
    os.makedirs(output_dir, exist_ok=True)
    chunk_files: List[Tuple[str, float]] = []
    for i, (cs, ce) in enumerate(boundaries):
        chunk_path = os.path.join(output_dir, f"chunk_{i:04d}.wav")
        cmd = [
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-i", audio_path,
            "-ss", str(cs), "-to", str(ce),
            "-c:a", "pcm_s16le",
            "-ar", "16000",
            "-ac", "1",
            chunk_path,
        ]
        subprocess.run(cmd, check=True, capture_output=True)
        chunk_files.append((chunk_path, cs))
    return chunk_files


def _offset_segments(
    segments: List[dict], offset: float,
) -> List[dict]:
    """
    Deep-copy a list of segment dicts and shift their timestamps by *offset*
    seconds. Handles both top-level start/end and nested word timestamps.
    """
    out = []
    for seg in segments:
        s = dict(seg)
        s["start"] = round(s["start"] + offset, 3)
        s["end"] = round(s["end"] + offset, 3)
        if s.get("words"):
            s["words"] = [
                {
                    "word": w["word"],
                    "start": round(w["start"] + offset, 3),
                    "end": round(w["end"] + offset, 3),
                }
                for w in s["words"]
            ]
        out.append(s)
    return out


def _dedup_merged_segments(
    segments: List[dict], split_points: List[float], overlap: float = SILENCE_OVERLAP,
) -> List[dict]:
    """
    Remove duplicate transcriptions in overlap regions between adjacent chunks.
    
    For each split point, the overlap region is [sp - overlap, sp + overlap].
    Segments whose *center* falls in the left half come from the earlier chunk;
    those in the right half come from the later chunk. If both halves have
    segments, keep the longer one (more confident transcription).
    """
    if not segments or not split_points:
        return segments

    cleaned = list(segments)

    for sp in split_points:
        left = sp - overlap
        right = sp + overlap
        keep: List[dict] = []
        i = 0
        while i < len(cleaned):
            seg = cleaned[i]
            seg_center = (seg["start"] + seg["end"]) / 2.0

            # Seg entirely outside overlap region → always keep
            if seg["end"] <= left or seg["start"] >= right:
                keep.append(seg)
                i += 1
                continue

            # Seg inside overlap region — look ahead for overlapping segs from other side
            # Find all segs that overlap this region
            overlap_group = [seg]
            j = i + 1
            while j < len(cleaned) and cleaned[j]["start"] < right:
                overlap_group.append(cleaned[j])
                j += 1

            # Within the group, keep segs from different "sides"
            left_segs = [s for s in overlap_group if (s["start"] + s["end"]) / 2.0 <= sp]
            right_segs = [s for s in overlap_group if (s["start"] + s["end"]) / 2.0 > sp]

            # Dedup: if left and right segs overlap in time, keep the longer one
            for ls in left_segs:
                for rs in right_segs:
                    if ls["end"] > rs["start"]:
                        # They overlap — keep the longer segment
                        dur_ls = ls["end"] - ls["start"]
                        dur_rs = rs["end"] - rs["start"]
                        if dur_ls >= dur_rs:
                            right_segs.remove(rs)
                        else:
                            left_segs.remove(ls)
                        break

            keep.extend(left_segs)
            keep.extend(right_segs)
            i = j

        cleaned = keep

    # Final sort by start time
    cleaned.sort(key=lambda s: s["start"])
    return cleaned


def _merge_chunk_transcriptions(
    chunk_results: List[Tuple[dict, float]],
    split_points: List[float],
    total_duration: float,
) -> dict:
    """
    Merge multiple chunk transcription results into one OpenAI-compatible dict.
    
    *chunk_results*: list of (result_dict, chunk_start_offset)
    *split_points*: the original silence midpoints used to split
    """
    all_segments: List[dict] = []
    text_parts: List[str] = []

    for result, offset in chunk_results:
        segs = result.get("segments", [])
        all_segments.extend(_offset_segments(segs, offset))
        text_parts.append(result.get("text", ""))

    all_segments.sort(key=lambda s: s["start"])
    all_segments = _dedup_merged_segments(all_segments, split_points)
    full_text = " ".join(s["text"].strip() for s in all_segments)

    dur = max(s["end"] for s in all_segments) if all_segments else total_duration

    return {
        "text": full_text,
        "duration": round(dur, 3),
        "segments": all_segments,
    }


def _convert_to_openai_format(segments, response_format: str) -> dict:
    """Convert faster-whisper segments to OpenAI-compatible format."""
    full_text_parts = []
    duration = 0.0
    openai_segments = []

    for seg in segments:
        full_text_parts.append(seg.text.strip())
        if seg.end > duration:
            duration = seg.end

        seg_dict = {
            "start": round(seg.start, 3),
            "end": round(seg.end, 3),
            "text": seg.text.strip(),
        }

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
        return {"text": full_text}
    else:
        return {
            "text": full_text,
            "duration": round(duration, 3),
            "segments": openai_segments,
        }


def _probe_duration(audio_path: str) -> Optional[float]:
    try:
        result = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", audio_path],
            capture_output=True, text=True, check=True,
        )
        return float(result.stdout.strip())
    except Exception:
        return None


def _build_seg_kwargs(
    audio_path: str, language: Optional[str], word_timestamps: bool,
) -> dict:
    kwargs: dict = {
        "audio": audio_path,
        "beam_size": 5,
        "condition_on_previous_text": False,
        "vad_filter": True,
    }
    prompt = _punctuation_prompt(language)
    if prompt:
        kwargs["initial_prompt"] = prompt
    if language:
        kwargs["language"] = language
    if word_timestamps:
        kwargs["word_timestamps"] = True
    return kwargs


async def _transcribe_chunked(
    audio_path: str,
    whisper_model: Any,
    word_timestamps: bool,
    language: Optional[str],
) -> dict:
    """
    Transcribe audio by splitting at silence points.
    Each chunk is transcribed independently, then results are merged
    with time offset and overlap dedup.
    """
    total_dur = _probe_duration(audio_path)
    if total_dur is None:
        raise RuntimeError("Cannot probe audio duration")

    split_points = _detect_silence_splits(audio_path)
    if not split_points:
        kwargs = _build_seg_kwargs(audio_path, language, word_timestamps)
        async with _transcribe_lock:
            segments, info = await asyncio.to_thread(
                whisper_model.transcribe, **kwargs
            )
            return _convert_to_openai_format(segments, "verbose_json")

    boundaries = _build_chunk_boundaries(split_points, total_dur)
    if len(boundaries) < 2:
        kwargs = _build_seg_kwargs(audio_path, language, word_timestamps)
        async with _transcribe_lock:
            segments, info = await asyncio.to_thread(
                whisper_model.transcribe, **kwargs
            )
            return _convert_to_openai_format(segments, "verbose_json")

    chunk_dir = os.path.join(os.path.dirname(audio_path), "asr_chunks")
    chunk_files = _split_audio_chunks(audio_path, boundaries, chunk_dir)
    logger.info(
        "Using silence-based chunking: %d chunks, splits at %s",
        len(chunk_files),
        [round(sp, 1) for sp in split_points],
    )

    chunk_results: List[Tuple[dict, float]] = []
    for chunk_path, chunk_offset in chunk_files:
        t_chunk = time.time()
        kwargs = _build_seg_kwargs(chunk_path, language, word_timestamps)
        async with _transcribe_lock:
            segments, info = await asyncio.to_thread(
                whisper_model.transcribe, **kwargs
            )
        chunk_result = _convert_to_openai_format(segments, "verbose_json")
        chunk_results.append((chunk_result, chunk_offset))
        logger.info(
            "Chunk done: offset=%.1fs, dur=%.2fs",
            chunk_offset, time.time() - t_chunk,
        )

    merged = _merge_chunk_transcriptions(chunk_results, split_points, total_dur)
    shutil.rmtree(chunk_dir, ignore_errors=True)
    return merged


@whisper_router.post("/transcriptions")
async def transcribe_audio(request: Request):
    """
    OpenAI-compatible speech-to-text endpoint.

    Supports two request formats:
      1. multipart/form-data: file + optional model, language, response_format
      2. Raw binary (application/octet-stream): audio bytes in body, params as query string
    """
    content_type = request.headers.get("content-type", "").lower()

    language = request.query_params.get("language")
    response_format = request.query_params.get("response_format", "verbose_json")
    audio_bytes = None
    audio_name = "audio"

    if "multipart/form-data" in content_type:
        form = await request.form()
        file_field = form.get("file")
        if not file_field:
            raise HTTPException(status_code=400, detail="No audio file provided (file field)")
        if not hasattr(file_field, "read"):
            raise HTTPException(status_code=400, detail="Invalid file field format")
        audio_bytes = await file_field.read()
        audio_name = getattr(file_field, "filename", "audio") or "audio"
        language = language or form.get("language")
        response_format = response_format or form.get("response_format", "verbose_json")
    else:
        # Raw binary upload
        chunks = []
        async for chunk in request.stream():
            chunks.append(chunk)
        audio_bytes = b"".join(chunks)
        if not audio_bytes:
            raise HTTPException(status_code=400, detail="Empty request body, please send audio data")

    if response_format not in ("json", "verbose_json"):
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported response_format: {response_format}. Only 'json' and 'verbose_json' supported.",
        )

    logger.info(
        "Transcription request: file=%s, size=%d, language=%s, format=%s, device=%s",
        audio_name, len(audio_bytes), language or "auto", response_format, WHISPER_DEVICE,
    )

    suffix = Path(audio_name).suffix if Path(audio_name).suffix else ".wav"
    tmp_file = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
            tmp_file = tmp.name
            tmp.write(audio_bytes)

        file_size_mb = len(audio_bytes) / (1024 * 1024)
        logger.info("Temp file saved: %s (%.2f MB)", tmp_file, file_size_mb)

        t0 = time.time()
        whisper_model = _get_whisper_model()
        word_timestamps = request.query_params.get("word_timestamps", "").lower() in ("1", "true")

        # 音频 >0.5h 走静音分片减少内存，否则全量转录
        total_dur = _probe_duration(tmp_file)
        if total_dur is not None and total_dur >= 1800.0:
            result = await _transcribe_chunked(
                tmp_file, whisper_model, word_timestamps, language,
            )
        else:
            seg_kwargs = _build_seg_kwargs(tmp_file, language, word_timestamps)
            async with _transcribe_lock:
                t_transcribe = time.time()
                segments, info = await asyncio.to_thread(
                    whisper_model.transcribe, **seg_kwargs
                )
                logger.info("Transcribing: file=%s, audio_duration=%.2fs", audio_name, info.duration)
                result = _convert_to_openai_format(segments, "verbose_json")
                transcribe_elapsed = time.time() - t_transcribe
                logger.info(
                    "Request completed: file=%s, audio_dur=%.2fs, transcribe=%.2fs, total=%.2fs, device=%s(%s)",
                    audio_name, info.duration, transcribe_elapsed, time.time() - t0,
                    WHISPER_DEVICE, WHISPER_COMPUTE_TYPE,
                )

        if response_format == "json":
            result = {"text": result["text"]}

        return result

    except HTTPException:
        raise
    except Exception as e:
        logger.error("Transcription failed: %s\n%s", e, traceback.format_exc())
        raise HTTPException(status_code=500, detail=f"Transcription failed: {str(e)}")
    finally:
        if tmp_file and os.path.exists(tmp_file):
            try:
                os.unlink(tmp_file)
            except Exception as e:
                logger.warning("Failed to delete temp file: %s, %s", tmp_file, e)


async def _get_voxcpm_pytorch_model():
    """Load the native voxcpm (PyTorch) model."""
    global _voxcpm_pytorch_model_instance
    if _voxcpm_pytorch_model_instance is not None:
        return _voxcpm_pytorch_model_instance

    async with _voxcpm_load_lock:
        if _voxcpm_pytorch_model_instance is not None:
            return _voxcpm_pytorch_model_instance

        model_path = _resolve_voxcpm_model_path()
        logger.info("Loading VoxCPM PyTorch model: path=%s, device=%s, load_denoiser=%s",
                    model_path, VOXCPM_DEVICE, VOXCPM_LOAD_DENOISER)

        if CUDA_DEVICE:
            os.environ["CUDA_VISIBLE_DEVICES"] = CUDA_DEVICE.replace("cuda:", "")

        t0 = time.time()
        from voxcpm import VoxCPM
        import torch

        # Limit PyTorch CPU threads to avoid contention with other services
        torch.set_num_threads(int(os.environ.get("OMP_NUM_THREADS", "2")))
        torch.set_num_interop_threads(1)

        _voxcpm_pytorch_model_instance = await asyncio.to_thread(
            VoxCPM.from_pretrained,
            model_path,
            load_denoiser=VOXCPM_LOAD_DENOISER,
        )
        elapsed = time.time() - t0
        logger.info("VoxCPM PyTorch model loaded in %.2f seconds", elapsed)
        return _voxcpm_pytorch_model_instance


async def _get_voxcpm_model():
    """Get the active TTS model (lazy loading, thread-safe)."""
    return await _get_voxcpm_pytorch_model()


def _get_sample_rate() -> int:
    model = _voxcpm_pytorch_model_instance
    if model is not None:
        return getattr(model.tts_model, "sample_rate", 48000)
    return 48000


async def _generate_tts_audio_pytorch(text: str, ref_audio_path: Optional[str]) -> bytes:
    """Generate TTS audio using native voxcpm, return WAV bytes."""
    import soundfile as sf

    model = await _get_voxcpm_pytorch_model()

    if ref_audio_path and Path(ref_audio_path).exists():
        wav = model.generate(
            text=text,
            reference_wav_path=ref_audio_path,
            cfg_value=VOXCPM_CFG_VALUE,
            inference_timesteps=VOXCPM_INFERENCE_TIMESTEPS,
        )
    else:
        wav = model.generate(
            text=text,
            cfg_value=VOXCPM_CFG_VALUE,
            inference_timesteps=VOXCPM_INFERENCE_TIMESTEPS,
        )

    tmp = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
    try:
        sf.write(tmp.name, wav, samplerate=_get_sample_rate())
        with open(tmp.name, "rb") as f:
            return f.read()
    finally:
        Path(tmp.name).unlink(missing_ok=True)





async def _generate_tts_audio(text: str, ref_audio_path: Optional[str]) -> bytes:
    """Generate TTS audio using the native VoxCPM backend, return WAV bytes."""
    return await _generate_tts_audio_pytorch(text, ref_audio_path)


# ============================================================
# VoxCPM TTS Router (/v1/tts)
# ============================================================

tts_router = APIRouter(prefix="/v1/tts", tags=["Text-to-Speech"])


@tts_router.post("")
async def tts_single(
    text: str = Form(..., description="Target text to synthesize"),
    ref_audio: Optional[UploadFile] = File(None, description="Reference audio WAV (optional)"),
):
    """
    Generate TTS audio for a single text.

    Multipart form-data:
      - text: target text (required)
      - ref_audio: reference audio WAV file (optional, uses default speaker if omitted)

    Returns: audio/wav binary
    """
    if not text or not text.strip():
        raise HTTPException(status_code=422, detail="text cannot be empty")

    ref_path: Optional[str] = None
    if ref_audio and ref_audio.filename:
        suffix = Path(ref_audio.filename).suffix or ".wav"
        tmp_ref = tempfile.NamedTemporaryFile(delete=False, suffix=suffix)
        try:
            content = await ref_audio.read()
            if len(content) == 0:
                raise HTTPException(status_code=422, detail="Reference audio file is empty")
            tmp_ref.write(content)
            tmp_ref.close()
            ref_path = tmp_ref.name
        except HTTPException:
            Path(tmp_ref.name).unlink(missing_ok=True)
            raise
        except Exception as e:
            Path(tmp_ref.name).unlink(missing_ok=True)
            raise HTTPException(status_code=500, detail=f"Reference audio processing failed: {e}")

    logger.info("TTS request: text='%s', ref=%s, backend=%s", text[:50], "yes" if ref_path else "no", _ACTIVE_TTS_BACKEND)

    t0 = time.time()
    try:
        async with _tts_sem:
            wav_bytes = await _generate_tts_audio(text.strip(), ref_path)
        elapsed = time.time() - t0
        logger.info("TTS generated: duration=%.2fs, size=%d bytes, backend=%s",
                    elapsed, len(wav_bytes), _ACTIVE_TTS_BACKEND)
        return FastAPIResponse(content=wav_bytes, media_type="audio/wav")
    except Exception as e:
        logger.error("TTS generation failed: %s\n%s", e, traceback.format_exc())
        raise HTTPException(status_code=500, detail=f"TTS generation failed: {e}")
    finally:
        if ref_path:
            Path(ref_path).unlink(missing_ok=True)


@tts_router.post("/batch")
async def tts_batch(
    texts: str = Form(..., description="JSON array: [{\"text\":\"...\", \"index\":0}, ...]"),
    ref_audio: Optional[UploadFile] = File(None, description="Reference audio WAV (shared for all texts)"),
):
    """
    Batch TTS generation.

    All texts share the same reference audio. Returns ZIP with 0001.wav, 0002.wav, ...
    """
    try:
        items = json.loads(texts)
    except json.JSONDecodeError as e:
        raise HTTPException(status_code=422, detail=f"texts JSON parse failed: {e}")

    if not isinstance(items, list) or not items:
        raise HTTPException(status_code=422, detail="texts must be a non-empty JSON array")

    ref_path: Optional[str] = None
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
            raise HTTPException(status_code=500, detail=f"Reference audio processing failed: {e}")

    logger.info("Batch TTS: %d items, ref=%s, backend=%s", len(items), "yes" if ref_path else "no", _ACTIVE_TTS_BACKEND)
    t_batch = time.time()

    zip_buffer = io.BytesIO()
    zip_size = 0
    try:
        async with _tts_sem:
            with zipfile.ZipFile(zip_buffer, "w", zipfile.ZIP_DEFLATED) as zf:
                for item in items:
                    text = item.get("text", "").strip()
                    idx = item.get("index", 0)
                    if not text:
                        continue

                    t_item = time.time()
                    wav_bytes = await _generate_tts_audio(text, ref_path)
                    item_elapsed = time.time() - t_item

                    tmp_wav = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
                    try:
                        tmp_wav.write(wav_bytes)
                        tmp_wav.close()
                        zf.write(tmp_wav.name, f"{idx:04d}.wav")
                    finally:
                        Path(tmp_wav.name).unlink(missing_ok=True)

                    logger.info("Batch TTS item %d: duration=%.2fs, backend=%s", idx, item_elapsed, _ACTIVE_TTS_BACKEND)
                    zip_size += 1
    finally:
        if ref_path:
            Path(ref_path).unlink(missing_ok=True)

    batch_elapsed = time.time() - t_batch
    zip_buffer.seek(0)
    logger.info("Batch TTS done: items=%d, total=%.2fs, backend=%s", zip_size, batch_elapsed, _ACTIVE_TTS_BACKEND)
    return FastAPIResponse(content=zip_buffer.read(), media_type="application/zip")


# ============================================================
# Unified Health & Root Endpoints
# ============================================================

health_router = APIRouter(tags=["Health"])


@health_router.get("/health")
async def health_check():
    """Unified health check for all services."""
    separator_loaded = _separator_instance is not None
    whisper_loaded = _whisper_model_instance is not None
    voxcpm_loaded = _voxcpm_pytorch_model_instance is not None

    all_loaded = separator_loaded and whisper_loaded and voxcpm_loaded
    any_loaded = separator_loaded or whisper_loaded or voxcpm_loaded

    return {
        "status": "ok" if all_loaded else ("partial" if any_loaded else "loading"),
        "services": {
            "audio_separator": {
                "status": "ok" if separator_loaded else "loading",
                "model": SEPARATOR_MODEL,
                "device": SEPARATOR_DEVICE,
            },
            "whisper_asr": {
                "status": "ok" if whisper_loaded else "loading",
                "model": WHISPER_MODEL,
                "device": WHISPER_DEVICE,
                "compute_type": WHISPER_COMPUTE_TYPE,
            },
            "voxcpm_tts": {
                "status": "ok" if voxcpm_loaded else "loading",
                "model": VOXCPM_MODEL,
                "device": VOXCPM_DEVICE,
                "backend": "voxcpm",
            },
        },
    }


@health_router.get("/")
async def root():
    """Service information endpoint."""
    return {
        "service": "Unified Python Services",
        "version": "1.1.0",
        "description": "Unified API for Audio Separation, Speech Recognition, and Text-to-Speech",
        "endpoints": {
            "audio_separation": {
                "separate": "POST /api/v1/separate",
                "models": "GET /api/v1/models",
            },
            "speech_recognition": {
                "transcribe": "POST /v1/audio/transcriptions",
            },
            "text_to_speech": {
                "tts": "POST /v1/tts",
                "batch_tts": "POST /v1/tts/batch",
            },
            "health": "GET /health",
        },
        "config": {
            "separator_model": SEPARATOR_MODEL,
            "whisper_model": WHISPER_MODEL,
            "whisper_device": WHISPER_DEVICE,
            "voxcpm_model": VOXCPM_MODEL,
            "voxcpm_backend": _ACTIVE_TTS_BACKEND,
        },
    }


# ============================================================
# FastAPI Application Factory
# ============================================================

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan: startup and shutdown."""
    logger.info("=" * 60)
    logger.info("Unified Python Services starting up...")
    logger.info("  Port: %d", SERVICE_PORT)
    logger.info("  Audio Separator Model: %s", SEPARATOR_MODEL)
    logger.info("  Whisper Model: %s (%s, %s)", WHISPER_MODEL, WHISPER_DEVICE, WHISPER_COMPUTE_TYPE)
    logger.info("  VoxCPM Model: %s", VOXCPM_MODEL)
    logger.info("=" * 60)

    # Pre-warm models on startup (optional - can be disabled via env)
    # 12GB 机器无法同时加载三个大模型，默认只懒加载需要用的
    preload = os.environ.get("PRELOAD_MODELS", "false").lower() == "true"
    if preload:
        logger.info("Pre-loading models on startup...")
        try:
            _get_separator(SEPARATOR_MODEL)
            logger.info("Audio separator model loaded")
        except Exception as e:
            logger.warning("Audio separator pre-load failed: %s", e)

        try:
            _get_whisper_model()
            logger.info("Whisper model loaded")
        except Exception as e:
            logger.warning("Whisper pre-load failed: %s", e)

        try:
            await _get_voxcpm_model()
            logger.info("TTS backend ready")
        except Exception as e:
            logger.warning("TTS pre-load failed: %s", e)
    else:
        logger.info("Lazy loading enabled (PRELOAD_MODELS=false)")

    yield

    # Shutdown cleanup
    logger.info("Shutting down, releasing model resources...")
    global _separator_instance, _whisper_model_instance
    global _voxcpm_pytorch_model_instance
    _separator_instance = None
    _whisper_model_instance = None
    _voxcpm_pytorch_model_instance = None
    logger.info("Shutdown complete")


app = FastAPI(
    title="Unified Python Services",
    version="1.1.0",
    description="Unified API for Audio Separation (audio-separator), Speech Recognition (faster-whisper), and TTS (VoxCPM)",
    lifespan=lifespan,
)

# CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Include routers
app.include_router(health_router)
app.include_router(separator_router)
app.include_router(whisper_router)
app.include_router(tts_router)


# ============================================================
# Entry Point
# ============================================================

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("server:app", host="0.0.0.0", port=SERVICE_PORT, workers=1, log_level="info")
