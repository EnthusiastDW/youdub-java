# ============================================================
# YouDub Backend Base Image
# Pre-bakes all apt and pip runtime tools so that the
# application Dockerfile only needs to copy the jar.
# ============================================================
FROM eclipse-temurin:21-jre

# ---- 系统工具层（几乎不变） ----
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl \
        ffmpeg \
        nodejs \
        npm \
        python3 \
        python3-pip \
        python3-venv \
        fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

# ---- Python 工具层（几乎不变） ----
RUN pip3 install --no-cache-dir --break-system-packages \
        --upgrade --ignore-installed \
        pip setuptools wheel \
        "yt-dlp[default]" \
        edge-tts

# ---- 目录结构 ----
RUN mkdir -p /app/data/cookies /app/data/logs /app/workfolder

# 使该镜像可作为 Compose 依赖项启动（立即退出）
CMD ["true"]
