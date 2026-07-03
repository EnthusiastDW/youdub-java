# ============================================================
# Unified Python Services Docker Image
# Combines: audio-separator, faster-whisper, voxcpm
# Base: python:3.11-slim (CPU), GPU optional via runtime
#
# voxcpm2-cli 使用预构建的 llamacpp-builder 镜像：
#   先运行 docker/llamacpp-builder/build.sh --proxy
#   然后在此 Dockerfile 中 COPY --from 直接引用
# ============================================================

FROM python:3.11-slim

# -----------------------------------------------------------
# System Dependencies
#   - ffmpeg: required by faster-whisper and audio processing
#   - gcc, g++: required for building some Python packages
#   - git: required for modelscope downloads
#   - libgomp1, libopenblas0: runtime libs for llama.cpp-omni
# -----------------------------------------------------------
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        ffmpeg \
        gcc \
        g++ \
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
RUN mkdir -p /app/models /app/data/modelscope /app/voxcpm2

# -----------------------------------------------------------
# Copy voxcpm2-cli + shared libraries from builder image
# -----------------------------------------------------------
COPY --from=youdub-llamacpp-builder:latest /app/llama.cpp-omni/build/bin/voxcpm2-cli /app/voxcpm2/voxcpm2-cli
COPY --from=youdub-llamacpp-builder:latest /app/llama.cpp-omni/build/bin/*.so* /app/voxcpm2/
ENV LD_LIBRARY_PATH=/app/voxcpm2:${LD_LIBRARY_PATH}

# -----------------------------------------------------------
# Python Dependencies (cached layer)
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
COPY server.py entrypoint.sh .
RUN chmod +x /app/entrypoint.sh

# -----------------------------------------------------------
# Expose Port
# -----------------------------------------------------------
EXPOSE 8001

# -----------------------------------------------------------
# Entrypoint
# -----------------------------------------------------------
ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8001", "--log-level", "info"]
