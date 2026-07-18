#!/bin/bash
set -e

echo "=== Step 1/4: 构建 Backend 基础镜像 (youdub-backend-base:local) ==="
docker build -t youdub-backend-base:local ./docker/backend-base

echo -e "\n=== Step 2/4: 构建 Python 基础镜像 (youdub-python-base:local) ==="
docker build -t youdub-python-base:local ./docker/youdub-python-base

echo -e "\n=== Step 3/4: 构建 Python 服务镜像 ==="
docker compose build python-services

echo -e "\n=== Step 4/4: 构建 Backend 服务镜像 ==="
docker compose build backend

echo -e "\n=== 全部构建完成 ==="
