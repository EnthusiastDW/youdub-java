# ============================================================
# Unified Python Services Docker Image
# Combines: audio-separator, faster-whisper, VoxCPM
# ============================================================

# 使用预烘焙的基础镜像（apt + pip 依赖已安装）
FROM youdub-python-base:local

# -----------------------------------------------------------
# Working Directory
# -----------------------------------------------------------
WORKDIR /app

# -----------------------------------------------------------
# Application Code
# -----------------------------------------------------------
COPY server.py .

EXPOSE 8001

CMD ["python", "-m", "uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8001", "--log-level", "info"]
