# ============================================================
# Unified Python Services Docker Image
# Combines: audio-separator, faster-whisper, VoxCPM
# ============================================================

FROM python:3.11-slim

# -----------------------------------------------------------
# System Dependencies
# -----------------------------------------------------------
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ffmpeg \
        gcc \
        g++ \
        pkg-config \
        libavformat-dev \
        libavcodec-dev \
        libavdevice-dev \
        libavutil-dev \
        libswscale-dev \
        libswresample-dev \
        git \
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
