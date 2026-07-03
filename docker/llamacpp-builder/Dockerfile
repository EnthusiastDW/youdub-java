# ============================================================
# llama.cpp-omni Builder — Standalone Docker Image
#
# 单独构建 voxcpm2-cli 二进制，不依赖主服务镜像。
# 主服务 Dockerfile 通过 COPY --from 引用此镜像。
#
# 构建命令:
#   docker build \
#     -t youdub-llamacpp-builder:latest \
#     --build-arg HTTP_PROXY=$HTTP_PROXY \
#     --build-arg HTTPS_PROXY=$HTTPS_PROXY \
#     -f docker/llamacpp-builder/Dockerfile \
#     docker/llamacpp-builder
#
# 如果不需要代理，省略 --build-arg 即可。
# 如果仓库有更新，可重新构建此镜像（无需重建主服务镜像）。
# ============================================================

FROM python:3.11-slim

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
# Use a fixed branch for reproducibility; update ARG when needed.
ARG LLAMACPP_OMNI_COMMIT=master
RUN if [ -n "${HTTP_PROXY}" ] || [ -n "${HTTPS_PROXY}" ]; then \
        git config --global http.proxy "${HTTP_PROXY:-${HTTPS_PROXY}}" && \
        git config --global https.proxy "${HTTPS_PROXY:-${HTTP_PROXY}}"; \
    fi && \
    git clone --depth 1 --branch ${LLAMACPP_OMNI_COMMIT} \
        https://github.com/tc-mb/llama.cpp-omni.git /app/llama.cpp-omni && \
    cd /app/llama.cpp-omni && \
    cmake -B build -DCMAKE_BUILD_TYPE=Release && \
    cmake --build build --target voxcpm2-cli -j$(nproc)

# Result: keep full build tree so main Dockerfile can COPY directly.
# 保留完整 build tree，主 Dockerfile 直接引用：
#   COPY --from=youdub-llamacpp-builder:latest /app/llama.cpp-omni/build/bin/voxcpm2-cli /app/
