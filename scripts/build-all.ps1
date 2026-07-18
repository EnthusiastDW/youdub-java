# ============================================================
# YouDub Replica — 全量构建脚本
# 步骤：基础镜像 -> 服务镜像
# 用法：.\scripts\build-all.ps1
# ============================================================
$ErrorActionPreference = "Stop"

Write-Host "=== Step 1/4: 构建 Backend 基础镜像 (youdub-backend-base:local) ===" -ForegroundColor Cyan
docker build -t youdub-backend-base:local ./docker/backend-base
if ($LASTEXITCODE -ne 0) { throw "Backend base 构建失败" }

Write-Host "`n=== Step 2/4: 构建 Python 基础镜像 (youdub-python-base:local) ===" -ForegroundColor Cyan
docker build -t youdub-python-base:local ./docker/youdub-python-base
if ($LASTEXITCODE -ne 0) { throw "Python base 构建失败" }

Write-Host "`n=== Step 3/4: 构建 Python 服务镜像 ===" -ForegroundColor Cyan
docker compose build python-services
if ($LASTEXITCODE -ne 0) { throw "Python 服务构建失败" }

Write-Host "`n=== Step 4/4: 构建 Backend 服务镜像 ===" -ForegroundColor Cyan
docker compose build backend
if ($LASTEXITCODE -ne 0) { throw "Backend 服务构建失败" }

Write-Host "`n=== 全部构建完成 ===" -ForegroundColor Green
