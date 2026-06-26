# ============================================================
# YouDub Replica Backend — Spring Boot Docker 镜像
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src/ src/
RUN mvn package -DskipTests -B

# ── Runtime ────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    ffmpeg \
    yt-dlp \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /app/data/cookies /app/data/logs /app/workfolder

COPY --from=build /app/target/replica-backend-*.jar /app/youdub-backend.jar

EXPOSE 8000

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Xms256m", "-Xmx2g", "-jar", "/app/youdub-backend.jar"]
