# ============================================================
# Unified Python Services Docker Image
# Combines: audio-separator, faster-whisper, VoxCPM
# ============================================================

FROM python:3.11-slim

# -----------------------------------------------------------
# update太慢，分开使其有缓存
# -----------------------------------------------------------
RUN apt-get update

RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    apt-get install -y --no-install-recommends \
        ffmpeg \
        libgomp1 \
        libopenblas0 \
        && \
    rm -rf /var/lib/apt/lists/*

# -----------------------------------------------------------
# Working Directory
# -----------------------------------------------------------
WORKDIR /app

# -----------------------------------------------------------
# Model Cache Directories
# -----------------------------------------------------------
RUN mkdir -p /app/data/separator-models /app/data/modelscope

# -----------------------------------------------------------
# Python Dependencies
# -----------------------------------------------------------
COPY requirements.txt .

RUN --mount=type=cache,target=/root/.cache/pip \
    mkdir -p /root/.pip && \
    printf '%s\n' '[global]' \
      'index-url = http://dengwei.local:8081/repository/pypi-group/simple' \
      'trusted-host = dengwei.local' \
      > /root/.pip/pip.conf && \
    pip install --default-timeout=300 -r /app/requirements.txt

# -----------------------------------------------------------
# Application Code
# -----------------------------------------------------------
COPY server.py .

EXPOSE 8001

CMD ["python", "-m", "uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8001", "--log-level", "info"]
