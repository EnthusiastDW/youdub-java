# ============================================================
# Unified Python Services Docker Image
# Combines: audio-separator, faster-whisper, voxcpm
# Base: python:3.11-slim (CPU), GPU optional via runtime
# ============================================================

# -----------------------------------------------------------
# Stage 1: Build llama.cpp-omni for CPU fallback
# -----------------------------------------------------------
FROM python:3.11-slim AS llamacpp-builder

# Build-time proxy support (apt / git)
ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY
ENV HTTP_PROXY=${HTTP_PROXY} \
    HTTPS_PROXY=${HTTPS_PROXY} \
    NO_PROXY=${NO_PROXY}

RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        build-essential \
        cmake \
        git \
        libopenblas-dev \
        && \
    rm -rf /var/lib/apt/lists/*

# Clone and build llama.cpp-omni (VoxCPM2 CLI target)
# Use a fixed commit-ish for reproducibility; update if upstream changes.
ARG LLAMACPP_OMNI_COMMIT=master
RUN if [ -n "${HTTP_PROXY}" ] || [ -n "${HTTPS_PROXY}" ]; then \
        git config --global http.proxy "${HTTP_PROXY:-${HTTPS_PROXY}}" && \
        git config --global https.proxy "${HTTPS_PROXY:-${HTTP_PROXY}}"; \
    fi && \
    git clone --depth 1 --branch ${LLAMACPP_OMNI_COMMIT} https://github.com/tc-mb/llama.cpp-omni.git /app/llama.cpp-omni && \
    cd /app/llama.cpp-omni && \
    cmake -B build -DCMAKE_BUILD_TYPE=Release && \
    cmake --build build --target voxcpm2-cli -j$(nproc)

# Verify the binary was produced
RUN ls -lh /app/llama.cpp-omni/build/bin/voxcpm2-cli

# -----------------------------------------------------------
# Stage 2: Final Python services image
# -----------------------------------------------------------
FROM python:3.11-slim

# -----------------------------------------------------------
# System Dependencies
#   - ffmpeg: required by faster-whisper and audio processing
#   - gcc, g++: required for building some Python packages (voxcpm deps)
#   - git: required for modelscope downloads
#   - libgomp1, libopenblas0: runtime libs for llama.cpp-omni
#   - Clean apt cache to reduce image size
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
RUN mkdir -p /app/models /app/data/modelscope

# -----------------------------------------------------------
# Copy only the voxcpm2-cli binary from builder stage (not the whole source tree)
RUN mkdir -p /app/llama.cpp-omni/build/bin
COPY --from=llamacpp-builder /app/llama.cpp-omni/build/bin/voxcpm2-cli /app/tools/voxcpm2-cli

# -----------------------------------------------------------
# Python Dependencies (cached layer)
# -----------------------------------------------------------

COPY requirements.txt .

# 构建时安装 Python 依赖（使用内网 Nexus 镜像）
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
# Entrypoint: install dependencies at runtime, then start server
# -----------------------------------------------------------
ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8001", "--log-level", "info"]
