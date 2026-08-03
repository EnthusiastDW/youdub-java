# ============================================================
# YouDub Replica Backend — Spring Boot Docker 镜像
# 多阶段构建：tools 层缓存长期有效，最终层仅含 jar
# 整仓 reactor 构建：onnxruntime-common / whisper / separator
# 与 backend 一起本地编译，不依赖私服上的 onnxruntime-* 构件
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# 先只拷贝 pom 与 settings，触发依赖下载层（可缓存）
COPY pom.xml settings.xml ./
COPY backend/pom.xml backend/pom.xml
COPY onnxruntime-common/pom.xml onnxruntime-common/pom.xml
COPY onnxruntime-whisper/pom.xml onnxruntime-whisper/pom.xml
COPY onnxruntime-separator/pom.xml onnxruntime-separator/pom.xml

# 整仓 reactor 预下载依赖（onnxruntime-* 在 reactor 内解析，不从私服拉取）
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -s settings.xml dependency:go-offline -pl backend -am -B

# 再拷贝全部源码
COPY backend/src/ backend/src/
COPY onnxruntime-common/src/ onnxruntime-common/src/
COPY onnxruntime-whisper/src/ onnxruntime-whisper/src/
COPY onnxruntime-separator/src/ onnxruntime-separator/src/

# reactor 编译：本地构建 onnxruntime-*，backend 依赖由 reactor 产物满足
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -s settings.xml package -pl backend -am -DskipTests -B

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

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Xms256m", "-Xmx2g", "-XX:+ShowCodeDetailsInExceptionMessages", "-jar", "/app/youdub-backend.jar"]
