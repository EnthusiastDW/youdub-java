# ============================================================
# YouDub Replica Backend — Spring Boot Docker 镜像
# 整仓 reactor 构建：onnxruntime-* 与 backend 一起本地编译，
# 不依赖私服上的 onnxruntime-* 构件
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY . .

RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -s backend/settings.xml -pl backend -am package -DskipTests -B

# ── 基础工具层（层缓存持久化，除非改动 apt/pip 命令） ────
FROM eclipse-temurin:21-jre AS youdub-backend-base

RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    apt-get update && \
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

RUN pip3 install --no-cache-dir --break-system-packages \
        --upgrade --ignore-installed \
        pip setuptools wheel \
        "yt-dlp[default]" \
        edge-tts

RUN mkdir -p /app/data/cookies /app/data/logs /app/workfolder

# ── 最终运行时 ────────────────────────────────────────────
FROM youdub-backend-base
WORKDIR /app

COPY --from=build /app/backend/target/replica-backend-*.jar /app/youdub-backend.jar

EXPOSE 8000

ENTRYPOINT ["java", "-XX:+UseG1GC", "-Xms1g", "-Xmx4g", "-XX:MaxMetaspaceSize=512m", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/app/data/logs/java-oom.hprof", "-XX:+ShowCodeDetailsInExceptionMessages", "-jar", "/app/youdub-backend.jar"]
