# ============================================================
# YouDub Python Services Base Image
# Pre-bakes all apt system libs and Python ML dependencies
# so that the service Dockerfile only needs to copy server.py.
# ============================================================
FROM python:3.11-slim

# ---- 系统库层（几乎不变） ----
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ffmpeg \
        libgomp1 \
        libopenblas0 \
    && rm -rf /var/lib/apt/lists/*

# ---- 模型缓存目录 ----
RUN mkdir -p /app/data/separator-models /app/data/modelscope

# ---- Python ML 依赖层（几乎不变） ----
COPY requirements.txt .
RUN mkdir -p /root/.pip && \
    printf '%s\n' '[global]' \
      'index-url = http://dengwei.local:8081/repository/pypi-group/simple' \
      'trusted-host = dengwei.local' \
      > /root/.pip/pip.conf && \
    pip install --default-timeout=300 -r /app/requirements.txt

# 使该镜像可作为 Compose 依赖项启动（立即退出）
CMD ["true"]
