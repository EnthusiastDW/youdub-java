# ============================================================
# VoxCPM TTS 服务 Docker 镜像
# ============================================================
FROM python:3.12-slim

# ── 系统依赖 ────────────────────────────────────────────────
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ffmpeg \
        gcc \
        g++ \
        && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# ── Python 依赖 ─────────────────────────────────────────────
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# ── 应用代码 ────────────────────────────────────────────────
COPY app.py .

# ── 模型缓存目录 ────────────────────────────────────────────
VOLUME ["/app/data/modelscope"]

EXPOSE 8001

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8001"]
