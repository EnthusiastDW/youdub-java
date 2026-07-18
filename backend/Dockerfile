# ============================================================
# YouDub Replica Backend — Spring Boot Docker 镜像
# 多阶段构建：tools 层缓存长期有效，最终层仅含 jar
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY settings.xml pom.xml ./
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -s settings.xml dependency:go-offline -B

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -s settings.xml package -DskipTests -B

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

COPY --from=build /app/target/replica-backend-*.jar /app/youdub-backend.jar

EXPOSE 8000

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Xms256m", "-Xmx2g", "-jar", "/app/youdub-backend.jar"]
