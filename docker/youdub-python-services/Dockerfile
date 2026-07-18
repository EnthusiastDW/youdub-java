# ============================================================
# Unified Python Services Docker Image
# Combines: audio-separator, faster-whisper, VoxCPM
# 多阶段构建：base 层缓存长期有效，最终层仅含 server.py
# ============================================================

# ── 基础依赖层（层缓存持久化） ────────────────────────────
FROM python:3.11-slim AS youdub-python-base

WORKDIR /app

RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        gcc \
        g++ \
        ffmpeg \
        libgomp1 \
        libopenblas0 \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /app/data/separator-models /app/data/modelscope

COPY requirements.txt .
RUN mkdir -p /root/.pip && \
    printf '%s\n' '[global]' \
      'index-url = http://dengwei.local:8081/repository/pypi-group/simple' \
      'trusted-host = dengwei.local' \
      > /root/.pip/pip.conf && \
    pip install --default-timeout=3000 -r requirements.txt

# ── 最终服务 ──────────────────────────────────────────────
FROM youdub-python-base
# WORKDIR /app 继承自基础阶段

COPY server.py .

EXPOSE 8001

CMD ["python", "-m", "uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8001", "--log-level", "info"]
