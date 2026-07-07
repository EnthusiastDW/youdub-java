# audio-separator 长音频分离内存超限调研报告

## 1. 问题概述

`docker/youdub-python-services/server.py` 中 `/api/v1/separate` 接口在输入音频较长时，内存占用会超过 **12 GB**。该接口使用 `audio-separator>=0.44.0` 库，通过 `chunk_duration=900`（15 分钟）进行分块处理，但仍然出现内存暴涨。

## 2. 关键代码路径

### 2.1 Python 服务入口

文件：`docker/youdub-python-services/server.py`（第 170–274 行）

```python
def _get_separator(model_filename: str):
    _separator_instance = Separator(
        model_file_dir=str(MODEL_DIR),
        output_format="WAV",
        chunk_duration=900,   # 15 分钟分块
    )
    _separator_instance.load_model(model_filename=model_filename)

@separator_router.post("/separate")
async def separate_audio(...):
    # 1. 流式写入磁盘，避免上传文件进内存（已做得很好）
    with tempfile.TemporaryDirectory(prefix="audio-sep-") as tmp:
        # ...
        output_files = await asyncio.to_thread(separator.separate, str(input_path))

        # 2. 把整个输出 ZIP 读进内存
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
            zf.write(vocal_path, "vocals.wav")
            zf.write(instrumental_path, "instrumental.wav")
        buf.seek(0)
        return FastAPIResponse(content=buf.read(), media_type="application/zip")
```

### 2.2 Java 后端调用

文件：`backend/src/main/java/com/youdub/replica/service/adapter/separate/AudioSeparatorApiSeparator.java`

- 请求：流式上传文件，避免大文件进 JVM 堆（已做得很好）。
- 响应：`HttpResponse.BodyHandlers.ofByteArray()` **把整个 ZIP 响应体读进 `byte[]`**，再解压。

```java
response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
byte[] responseBody = response.body();
extractFromZip(responseBody, vocalsOut, bgmOut);
```

### 2.3 audio-separator 内部 chunking 逻辑

文件：`audio_separator/separator/separator.py`（第 1002–1156 行）和 `audio_separator/separator/audio_chunking.py`。

分块逻辑本身存在两个**全量加载**内存瓶颈：

#### (1) 输入文件全量加载

`AudioChunker.split_audio()` 使用 `pydub.AudioSegment.from_file(input_path)` 一次性把整个音频读进内存：

```python
audio = AudioSegment.from_file(input_path)
# 然后遍历导出 chunk
for i in range(num_chunks):
    chunk = audio[start_ms:end_ms]
    chunk.export(chunk_path, ...)
```

**问题**：即使 `chunk_duration=900` 分块，原始文件也已经被完整加载到内存。对 2 小时 44.1kHz 立体声 WAV（约 1.2 GB），`pydub` 内部会保存未压缩的 PCM 数据，实际内存占用通常是文件大小的 **2–3 倍**。

#### (2) 输出合并全量加载

`AudioChunker.merge_chunks()` 逐个加载 chunk 并做 `+=` 拼接，结果仍保存在一个 `AudioSegment` 对象里：

```python
combined = AudioSegment.empty()
for chunk_path in chunk_paths:
    chunk = AudioSegment.from_file(chunk_path)
    combined += chunk
combined.export(output_path, ...)
```

`+=` 会创建新对象并复制数据，最终 `combined` 同样会持有一整轨输出音频（约 1.2 GB）。

### 2.4 MDX 模型推理内存

文件：`audio_separator/separator/architectures/mdx_separator.py`（第 293–412 行）

`demix()` 方法为每个 chunk 分配：

- `result` 数组：`np.zeros((1, 2, mixture.shape[-1]))`
- `divider` 数组：`np.zeros((1, 2, mixture.shape[-1]))`
- STFT 中间张量、模型输入/输出张量
- `prepare_mix()` 加载的 `mix` 数组

以 15 分钟（900 秒）44.1kHz 立体声 chunk 为例：

- 音频数据：`900 × 44100 × 2 × 4 ≈ 300 MB`（float32）
- result + divider：约 `600 MB`
- 输出源：约 `300 MB`
- STFT + 模型中间张量：数百 MB 到 1 GB+

所以**单个 15 分钟 chunk** 峰值就会占 **2–3 GB**。

## 3. 为什么 12 GB 会被打满

假设输入是一段 **2 小时 44.1kHz 16-bit 立体声 WAV**（约 1.2 GB 文件）：

| 内存占用点 | 估算内存 | 说明 |
|------------|----------|------|
| `pydub` 加载输入 | 2–3 GB | 全量加载到 `AudioSegment` |
| 两个输出 stem（vocals + instrumental） | 2.4 GB | WAV 未压缩 |
| `merge_chunks` 合并输出 | 1.2 GB | `combined` 对象持有完整音频 |
| server.py ZIP 缓冲区 | 1.5–2 GB | 把两个 WAV 读进内存再压缩 |
| Java 后端 `byte[]` | 1.5–2 GB | 整个 ZIP 响应体 |
| ONNX/PyTorch 模型 + 推理张量 | 1–2 GB | 模型权重 + 单 chunk 峰值 |
| librosa / numpy 临时对象 | 数百 MB | 归一化、转置等 |
| **合计** | **11–14 GB** | 很容易超过 12 GB |

如果音频更长（3 小时+）、采样率更高或并发请求，会更快突破。

## 4. 根因总结

1. **`audio-separator` 的 `AudioChunker` 使用 `pydub` 全量加载输入和输出**，分块仅限制模型单次推理时长，但 I/O 阶段仍是 O(n) 内存。
2. **`chunk_duration=900` 过大**，15 分钟一个 chunk 导致单段推理峰值内存已经很高；注释写的“5 分钟”和实际代码不一致。
3. **server.py 把完整输出 ZIP 读进 `io.BytesIO`**，再返回给客户端，属于二次全量内存拷贝。
4. **Java 后端用 `BodyHandlers.ofByteArray()`**，把响应 ZIP 也完整读进 JVM 堆内存。
5. **Docker 未设置内存限制**，容器可以无限吃宿主机内存，直到被 OOM kill。

## 5. 优化方案

### 5.1 短期可立即生效的改动

#### (1) 减小 `chunk_duration`

建议改为 **300 秒（5 分钟）** 或 **600 秒（10 分钟）**，降低单段推理峰值。

```python
Separator(
    model_file_dir=str(MODEL_DIR),
    output_format="WAV",
    chunk_duration=300,          # 或 600
    use_soundfile=True,          # 减少写文件时内存占用
)
```

#### (2) 启用 `use_soundfile`

`audio-separator` 写文件时默认使用 `pydub`（依赖 ffmpeg），长音频会额外占内存。启用 `use_soundfile=True` 可直接用 `soundfile` 写文件，更轻量。

#### (3) Python 端改为流式 ZIP 返回

避免把完整 ZIP 读进 `io.BytesIO`：

```python
from fastapi.responses import StreamingResponse
from starlette.responses import StreamingResponse

@separator_router.post("/separate")
async def separate_audio(...):
    # ... 执行分离后得到 vocal_path / instrumental_path ...

    def zip_generator():
        with tempfile.NamedTemporaryFile(suffix=".zip", delete=False) as tmp_zip:
            tmp_zip_path = tmp_zip.name
            with zipfile.ZipFile(tmp_zip, "w", zipfile.ZIP_DEFLATED) as zf:
                zf.write(vocal_path, "vocals.wav")
                zf.write(instrumental_path, "instrumental.wav")
        try:
            with open(tmp_zip_path, "rb") as f:
                while chunk := f.read(4 * 1024 * 1024):
                    yield chunk
        finally:
            os.unlink(tmp_zip_path)

    return StreamingResponse(
        zip_generator(),
        media_type="application/zip",
        headers={"Content-Disposition": f'attachment; filename="separated_{...}.zip"'}
    )
```

#### (4) Java 后端流式接收 ZIP

把 `ofByteArray` 改成 `ofFile` 或自定义 `BodyHandler` 写入磁盘：

```java
Path responseZip = outputDir.resolve("separated.zip");
HttpResponse<Path> response = httpClient.send(
    request,
    HttpResponse.BodyHandlers.ofFile(responseZip)
);
// 再从磁盘文件解压，避免 byte[] 进堆
extractFromZip(responseZip, vocalsOut, bgmOut);
```

### 5.2 中期根治方案

#### (5) 用 ffmpeg 替代 `pydub` 做 chunking

`audio-separator` 内部的 `AudioChunker` 无法避免 `pydub` 全量加载。可以在 `server.py` 里先自己用 ffmpeg 把输入切到磁盘，再逐 chunk 调用模型，最后用 ffmpeg 拼接输出。

伪代码：

```python
# 1. 先 probe 时长
# 2. ffmpeg 切分输入到多个 chunk 文件（仅磁盘 I/O，不加载到内存）
# 3. 循环对每个 chunk 调用 separator.separate()
# 4. ffmpeg concat 拼接每个 stem 的 chunk 结果
```

这样可以彻底避免 `pydub` 全量加载输入和输出。

#### (6) 按需只返回需要的 stem

如果后续流程只需要 vocals，可以在 `output_single_stem="Vocals"` 时只输出 vocals，减少一半输出和 ZIP 体积。

#### (7) 给 Docker 容器设置内存限制

在 `docker-compose.yml` 增加：

```yaml
services:
  python-services:
    deploy:
      resources:
        limits:
          memory: 8G
        reservations:
          memory: 4G
```

让 Python 服务在内存达到上限前触发 OOM，而不是把宿主机拖垮。可以配合监控判断是否需要继续优化。

### 5.3 方案优先级建议

| 优先级 | 方案 | 预期收益 | 改动量 |
|--------|------|----------|--------|
| P0 | 减小 `chunk_duration` + 启用 `use_soundfile` | 单 chunk 峰值降低 50% 以上 | 很小 |
| P0 | Python 端流式 ZIP 返回 | 避免 1–2 GB 内存拷贝 | 中等 |
| P0 | Java 后端流式接收 | 避免 1–2 GB JVM 堆内存 | 中等 |
| P1 | 用 ffmpeg 替代 `pydub` chunking | 根治输入/输出全量加载 | 较大 |
| P1 | Docker 内存限制 | 防止拖垮宿主机 | 很小 |
| P2 | 只输出 vocals | 减少一半输出体积 | 小，取决于业务 |

## 6. 验证建议

1. 用 `memory_profiler` 或 `psutil` 在 `server.py` 的 `separate_audio` 中打印 RSS 峰值：

```python
import os, psutil
process = psutil.Process(os.getpid())
logger.info("RSS: %.2f GB", process.memory_info().rss / 1024 / 1024 / 1024)
```

2. 测试不同 `chunk_duration`（300 / 600 / 900）在同一长音频上的 RSS 曲线，找出内存-速度平衡点。

3. 在 Docker 中设置 `memory: 8G` 跑相同测试，观察是否仍被 OOM kill。

## 7. 结论

`server.py` 接口本身上传流式处理是合理的，但 **audio-separator 的 `pydub` 分块实现、server.py 的 ZIP 内存缓冲、Java 端的 `ofByteArray` 响应** 三个环节都会把长音频完整保留在内存。三者叠加后，2 小时 WAV 即可达到 12 GB 以上。

**最快见效的改动**：
- `chunk_duration` 从 900 降到 300–600；
- `use_soundfile=True`；
- Python 返回 `StreamingResponse`，Java 用 `ofFile` 接收。

**根治方案**：在 `server.py` 中自己用 ffmpeg 分块/合并，绕开 `audio-separator` 内部 `pydub` 的全量加载。

---

## 8. 补充：为什么 370 MB、3.5 小时音频还会爆内存？

这是一个关键疑问。文件只有 370 MB，但内存和 swap 在**分块开始前**就满了。

根本原因是 **audio-separator 的 `AudioChunker.split_audio()` 用 `pydub.AudioSegment.from_file()` 先把整个音频解压并加载到内存，再分块**。音频文件大小 ≠ 内存占用：

- 3.5 小时 = 12 600 秒
- 即使按 Java 后端对大音频的降级参数 **16 kHz 单声道 16-bit** 估算：
  - 解压后 PCM：`12600 × 16000 × 1 × 2 ≈ 403 MB`
- 如果实际仍是 **44.1 kHz 立体声 16-bit**：
  - 解压后 PCM：`12600 × 44100 × 2 × 2 ≈ 2.1 GB`

而 `pydub` 的 `AudioSegment` 会保存未压缩 PCM 字节数组，并可能在 `from_file()` 和后续导出时创建额外拷贝。再加上：

- 模型权重（MDX ONNX 模型约数百 MB）
- 每个 chunk 在 `librosa.load()` 和 `MDXSeparator.demix()` 中的 `result` / `divider` 数组
- 输出 stem 写文件时的 buffer
- Python 进程本身的开销

叠加之后，即使原始文件 370 MB，也很容易占满 12 GB 内存 + swap。这也说明**只调 `chunk_duration` 不够**，因为分块前的全量加载是内存大户。

---

## 9. 已落地改动

- **2026-07-07**：已将 `docker/youdub-python-services/server.py` 中的 `Separator` 配置改为 `chunk_duration=600`（10 分钟分块）并启用 `use_soundfile=True`。
- **2026-07-07**：已将 `/api/v1/separate` 的返回从 `FastAPIResponse(content=io.BytesIO().read())` 改为 `StreamingResponse`，ZIP 先写入磁盘临时文件，再以 4 MB 块流式返回，响应结束后清理临时目录。避免把整个 ZIP 读进内存。
- **2026-07-07**：在 `server.py` 中 monkey-patch 了 `audio_separator.separator.audio_chunking.AudioChunker`，把 `split_audio` 和 `merge_chunks` 从 `pydub` 实现替换为 `ffmpeg` 实现：
  - 分块：每个 chunk 目标 600 秒，后缘多取 2 秒作为 overlap；`ffmpeg -ss START -to END ...` 逐个截取，不预加载整段音频。
  - 合并：相邻 chunk 之间用 `ffmpeg acrossfade=d=2` 做 2 秒交叉淡化，避免固定时间切分把词/句切两半。
- **2026-07-07**：Java 端 `AudioSeparatorApiSeparator` 从 `HttpResponse.BodyHandlers.ofByteArray()` 改为 `HttpResponse.BodyHandlers.ofFile(zipTemp)`，响应体直接流式写入磁盘临时文件，ZIP 解压后再删除临时文件。避免整个 ZIP 进入 JVM 堆内存。
- **2026-07-07**：流式返回的 `finally` 中也删除了 `vocal_path` / `instrumental_path` 两个输出文件，避免在 `/app` 工作目录中堆积中间文件。
