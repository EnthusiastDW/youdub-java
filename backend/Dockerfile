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

# ── whisper.cpp 编译阶段（whisper-cli 打进 base 层）─────────
FROM ubuntu:22.04 AS whisper-build
WORKDIR /src
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        cmake \
        git \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch v1.9.2 \
        https://github.com/ggml-org/whisper.cpp.git
RUN cmake -B build -S whisper.cpp \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=OFF \
        -DWHISPER_BUILD_EXAMPLES=ON \
        -DWHISPER_BUILD_SERVER=OFF \
        -DWHISPER_BUILD_TESTS=OFF \
    && cmake --build build --config Release -j$(nproc) --target whisper-cli

# ── VoxCPM2 编译阶段（voxcpm2-cli 打进 base 层）────────────
# llama.cpp-omni：官方推荐的 VoxCPM2 C++ 推理引擎（CPU/Metal/CUDA/Vulkan）
# 注意：voxcpm2-cli 自 b262 起才合并进仓库，v1.0.22 及其之前均无此目标
FROM ubuntu:22.04 AS voxcpm-build
WORKDIR /src
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        cmake \
        git \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch b262 \
        https://github.com/tc-mb/llama.cpp-omni.git
# GGML 默认 CPU 后端；静态链接保证产物可在 JRE 基础镜像中直接运行
# LLAMA_OPENSSL=OFF：构建镜像未安装 libssl-dev，且 voxcpm2-cli 不需要 HTTPS
RUN cmake -B build -S llama.cpp-omni \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=OFF \
        -DLLAMA_CURL=OFF \
        -DLLAMA_OPENSSL=OFF \
        -DLLAMA_BUILD_TESTS=OFF \
        -DLLAMA_BUILD_EXAMPLES=OFF \
        -DLLAMA_BUILD_SERVER=OFF \
    && cmake --build build --config Release -j$(nproc) --target voxcpm2-cli

# ── 基础工具层（层缓存持久化，除非改动 apt/pip 命令） ────
FROM eclipse-temurin:21-jre AS youdub-backend-base

COPY --from=whisper-build /src/build/bin/whisper-cli /usr/local/bin/whisper-cli
COPY --from=voxcpm-build /src/build/bin/voxcpm2-cli /usr/local/bin/voxcpm2-cli

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
