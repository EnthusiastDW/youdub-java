"""
Audio Separator — HTTP API Server
Wraps beveradb/audio-separator behind a REST API.
Returns separated stems (vocals + instrumental) as a ZIP download.
"""

import io
import os
import re
import tempfile
import zipfile
from pathlib import Path
from typing import Optional

from audio_separator.separator import Separator
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import Response

app = FastAPI(title="Audio Separator API", version="1.0.0")

MODEL_DIR = Path(os.environ.get("MODEL_DIR", "/app/models"))
MODEL_DIR.mkdir(parents=True, exist_ok=True)

DEFAULT_MODEL = os.environ.get(
    "SEPARATOR_MODEL",
    "UVR-MDX-NET-Inst_HQ_3.onnx",
)

_separator: Optional[Separator] = None


def _get_separator(model_filename: str) -> Separator:
    global _separator
    if _separator is None or _separator.model_filename != model_filename:
        _separator = Separator(
            model_filename=model_filename,
            model_file_dir=str(MODEL_DIR),
            output_format="WAV",
        )
    return _separator


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.get("/models")
async def list_models():
    files = [f.name for f in sorted(MODEL_DIR.iterdir()) if f.suffix in (".onnx", ".pth", ".ckpt")]
    return {"model_dir": str(MODEL_DIR), "models": files}


@app.post("/separate", response_class=Response)
async def separate(
    file: UploadFile = File(...),
    model_filename: str = Form(DEFAULT_MODEL),
):
    """
    Upload an audio file and receive a ZIP containing:

      * vocals.wav        — extracted vocal stem
      * instrumental.wav  — everything else (background)
    """
    if not file.filename or not file.file:
        raise HTTPException(400, "No file provided.")

    with tempfile.TemporaryDirectory(prefix="audio-sep-") as tmp:
        input_path = Path(tmp) / _safe_filename(file.filename)
        try:
            content = await file.read()
            input_path.write_bytes(content)
        except Exception as exc:
            raise HTTPException(400, f"Failed to read upload: {exc}")

        if input_path.stat().st_size == 0:
            raise HTTPException(400, "Uploaded file is empty.")

        try:
            separator = _get_separator(model_filename)
            output_files = separator.separate(str(input_path))
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
        # fallback: first output is instrumental, second is vocal
        if vocal_path is None and len(output_files) >= 2:
            instrumental_path, vocal_path = Path(output_files[0]), Path(output_files[1])
        elif vocal_path is None:
            vocal_path = Path(output_files[0])

        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
            if vocal_path and vocal_path.exists():
                zf.write(vocal_path, "vocals.wav")
            if instrumental_path and instrumental_path.exists():
                zf.write(instrumental_path, "instrumental.wav")

        buf.seek(0)
        return Response(
            content=buf.read(),
            media_type="application/zip",
            headers={
                "Content-Disposition": f'attachment; filename="separated_{Path(file.filename).stem}.zip"'
            },
        )


def _safe_filename(name: str) -> str:
    name = Path(name).name
    name = re.sub(r"[^\w.\-]", "_", name)
    return name or "upload"


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("server:app", host="0.0.0.0", port=8001, log_level="info")
