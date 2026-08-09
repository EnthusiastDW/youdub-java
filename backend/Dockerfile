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
# 可选：构建代理（如 http://dengwei.local:7890）。服务器侧经代理访问 GitHub 更稳，
# 传入方式：docker compose build --build-arg GIT_PROXY=http://dengwei.local:7890 backend
ARG GIT_PROXY=""
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        cmake \
        git \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*
# 大仓库克隆必须：HTTP/1.1（避免 HTTP/2 CANCEL/early EOF）+ blob:none（按需拉取，
# 拆小传输单元）+ 代理（可选）；失败自动重试 3 次
RUN if [ -n "$GIT_PROXY" ]; then \
        git config --global http.proxy "$GIT_PROXY" && \
        git config --global https.proxy "$GIT_PROXY"; \
    fi && \
    for i in 1 2 3; do \
        rm -rf llama.cpp-omni && \
        git -c http.version=HTTP/1.1 clone --depth 1 --filter=blob:none \
            --single-branch --branch b262 \
            https://github.com/tc-mb/llama.cpp-omni.git && break; \
        echo "clone attempt $i failed, retrying..." && sleep 5; \
    done && \
    test -f llama.cpp-omni/tools/omni/voxcpm2/voxcpm2_cli.cpp
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
