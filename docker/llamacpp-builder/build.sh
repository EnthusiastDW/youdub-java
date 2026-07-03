#!/bin/bash
# ============================================================
# Build the standalone llama.cpp-omni voxcpm2-cli image.
#
# Usage:
#   ./build.sh                       # 无代理
#   ./build.sh --proxy               # 使用环境变量中的 HTTP_PROXY / HTTPS_PROXY
#   ./build.sh --branch dev          # 指定分支（默认 master）
#
# 构建完成后，主服务 Dockerfile 直接引用此镜像：
#   COPY --from=youdub-llamacpp-builder:latest /app/llama.cpp-omni/build/bin/voxcpm2-cli /app/
#   (无需 docker cp)
#
# 然后构建主服务镜像：
#   docker compose build python-services
# ============================================================

set -euo pipefail

PROXY=""
BRANCH="master"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --proxy) PROXY="1" ;;
        --branch) BRANCH="$2"; shift ;;
        *) echo "Unknown arg: $1"; exit 1 ;;
    esac
    shift
done

PROXY_ARGS=""
if [[ -n "$PROXY" ]]; then
    PROXY_ARGS="--build-arg HTTP_PROXY=${HTTP_PROXY:-} --build-arg HTTPS_PROXY=${HTTPS_PROXY:-} --build-arg NO_PROXY=${NO_PROXY:-}"
    echo "Proxy enabled: HTTP_PROXY=${HTTP_PROXY:-}, HTTPS_PROXY=${HTTPS_PROXY:-}"
fi

echo "Building youdub-llamacpp-builder:latest (branch=$BRANCH)..."
docker build \
    -t youdub-llamacpp-builder:latest \
    --build-arg LLAMACPP_OMNI_COMMIT="$BRANCH" \
    $PROXY_ARGS \
    -f Dockerfile \
    .

echo ""
echo "=== Verification ==="

BINARY="/app/llama.cpp-omni/build/bin/voxcpm2-cli"
SO="/app/llama.cpp-omni/build/bin/libllama-common.so.0"

# 1. Check files exist
echo "Checking build artifacts..."
docker run --rm youdub-llamacpp-builder:latest sh -c "
  set -e
  echo '[1/3] Binary:'; ls -lh $BINARY
  echo '[2/3] libllama-common.so.0:'; ls -lh $SO
"

# 2. Check dynamic linker can resolve all shared libs
echo "[3/3] Checking shared library dependencies..."
docker run --rm youdub-llamacpp-builder:latest sh -c "
  LD_LIBRARY_PATH=/app/llama.cpp-omni/build/bin ldd $BINARY | grep -E '(not found|libllama)'
  echo '---'
  LD_LIBRARY_PATH=/app/llama.cpp-omni/build/bin ldd $BINARY | grep 'not found' && exit 1 || echo 'All dependencies satisfied!'
"

echo ""
echo "=== Build & verification passed ==="
echo "Image size:"
docker images youdub-llamacpp-builder:latest
