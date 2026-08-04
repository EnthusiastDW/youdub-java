# 一场被"假内存"骗过的 OOM：ZGC 在容器里的 5.7GB 幻觉

## 一、症状：一个"三无"事故

2026 年 8 月 4 日下午，YouDub Replica 的 Java 后端在处理一段 60 分钟长音频的人声分离时，容器在"分块 3"之后直接重启了。

这个事故有三个让人抓狂的特征：**无报错、无终止、无痕迹**。

Java 日志里干干净净，没有任何异常堆栈。任务既没有正常结束，也没有抛出任何错误。它就像被一只看不见的手从进程表里抹掉了。

YouDub Replica 是一条视频自动配音管线：Java 21 + Spring Boot 3.4 后端，FastAPI Python 微服务，React 前端，Docker Compose 编排。服务器是一台 12GB 物理内存、无 GPU 的 Linux 机器（hostname `win-server`）。Java 后端进程内用 ONNX Runtime 跑 `UVR-MDX-NET-Inst_HQ_3.onnx` 做音乐人声分离，走的是 STFT→ONNX→iFFT 的滑窗推理。Python 微服务里还挂着 VoxCPM2 TTS（峰值约 7.8GB）、faster-whisper 和 audio-separator。

一台 12GB 的机器，跑着这么多吃内存的东西，出事似乎不意外。但真正诡异的是，我们一开始完全找错了方向。

## 二、排查：错误假设的连环翻车

### 第一反应：堆不够，调大

看到 Java 进程被"杀掉"，第一直觉是堆内存不够。于是我们把 `-Xmx` 从默认值一路调到了 **10g**。

结果更糟了。容器重启得更频繁，内存压力反而更大。

### 第二反应：docker stats 的误导

`docker stats` 显示 java 容器的 MEM USAGE 曾达到 **5.7GB**。看起来 Java 确实很能吃内存，5.7GB 在 12GB 的机器上确实危险。

但注意，docker stats 给的是容器的**汇总快照**（anon + shmem + page cache），而且是分时采样——它没有告诉我们内存到底花在哪几项上。我们当时没意识到，真正致命的那 7~8GB，藏在这个汇总数的视线之外。

### 破案：dmesg 里的四条 OOM 记录

真正让真相浮出水面的，是 `dmesg`。它记录了当天下午的四次 OOM 事件：

| 时间 | 触发者 | 类型 | task=java 的 anon-rss | shmem-rss | 当时的 -Xmx |
|------|--------|------|----------------------|-----------|-------------|
| 16:51 | chronyd | global_oom | 2.7GB | **7.1GB** | 10g |
| 17:08 | navidrome | global_oom | 2.8GB | **8.1GB** | 10g |
| 17:26 | navidrome | global_oom | 2.8GB | **8.3GB** | 10g |
| 17:45 | task-1 | CONSTRAINT_MEMCG | 2.4GB | **2.45GB** | 4g |

关键观察来了：**shmem-rss 随 -Xmx 线性变化**。`-Xmx10g` 时 shmem 高达 7~8GB，`-Xmx4g` 时降到 2.45GB。

也就是说，Java 进程的"真实占用"根本不是 dmesg 里那个看起来很舒服的 **2.6~2.8GB anon-rss**，而是 **anon(2.8) + shmem(7~8) = 10~11GB**。在 12GB 的机器上，这必然触发全局 OOM。

我们被 docker stats 骗了。它只显示了匿名内存，而 Java 的堆，被 ZGC 映射成了共享内存。

回头看这三次假设，每一次都错得有道理，也正因为错得有道理才可怕：`docker stats` 给的数据是真实的，`-Xmx` 确实是堆上限，日志也确实没有异常。数据没错，错的是我们对"这些数据各代表什么"的理解。排障最难的地方从来不是读数据，而是知道该信哪些数据。

## 三、根因：ZGC 的 memfd 机制

### 堆为什么变成了"共享内存"

ZGC（包括 Java 21 的 `-XX:+ZGenerational`）的堆，不是用普通的匿名内存映射的，而是通过 `memfd_create()` + `mmap(MAP_SHARED)` 来映射。内核因此把它计为 **RssShmem（共享内存）**，而不是 RssAnon。

这个结论不是猜的，是用 OpenJDK 源码验证过的：

- `openjdk/jdk` 的 `zPhysicalMemoryBacking_linux.cpp` 第 355 行，用的是 `mmap(MAP_FIXED|MAP_SHARED)`；
- 而 `os_linux.cpp` 里，G1 走的是 `MAP_PRIVATE|MAP_ANONYMOUS`。

所以"java 只占 2.6GB 匿名内存"是个彻头彻尾的假象。真实占用 = anon + shmem，两者加起来才是进程真正吃掉的物理内存。

### 三重映射：同一物理页被数三次

ZGC 还有更坑的地方：它用着色指针维护了 **3 个 heap view**（多重映射）。这意味着 `/proc` 可能把同一块物理页重复计算 3 次。内存账目进一步失真。

### 容器场景的恶化点

在容器里，问题被放大了。cgroup v2 会把 ZGC 的 memfd 计入 `memory.working_set`（mapped_file active），导致 working set 虚高，容器更容易被 OOM-kill。这不是我们独有的问题，zgc-dev 邮件列表 2024 年就有 GKE 生产案例，症状完全一样。

### 两个次要原因

- **-Xmx10g 远超实际存活数据**：300s 分块下，峰值存活数据只有约 1.5GB。ZGC 白白 committed 了 7-8GB，而且默认 `ZUncommit` 才会缓慢归还，内存被长期占着不放。
- **17:45 的 CONSTRAINT_MEMCG**：这次是 cgroup 内 OOM，杀在约 4.9GB，和容器 mem_limit（5g/6g 边缘）直接相关，暴露了"限制太紧"的问题。

## 四、解决方案：逐条拆解

### 1. 换 GC，换堆大小

```
-XX:+UseZGC -XX:+ZGenerational  →  -XX:+UseG1GC
-Xmx10g                          →  -Xmx4g
追加：-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/data/logs/java-oom.hprof
```

G1 的堆是匿名私有内存，RssShmem 归零，物理占用变得真实可测。而且这是批处理场景，G1 的吞吐优于 ZGC 的低延迟，选型反而更合适。

### 2. 收紧容器 mem_limit

```
backend:         6g（配 -Xmx4g，留 native 余量）
python-services: 8g（VoxCPM 硬顶）
```

这里要澄清一个误区：mem_limit 是**隔离保险**，不是元凶。如果去掉它，问题不会消失，只会从"单容器被杀"退化成"全局 OOM 随机杀受害者"，更不可控。

### 3. 去掉 uvicorn --reload

Python 微服务在生产环境开着 `--reload` 是反模式，去掉后省掉一个 reloader 进程。

### 4. 人声分离分块 600s → 300s

单块峰值内存从 2.4GB 降到 1.2GB，直接减半。

### 5. 顺带发现的速度优化

分离滑窗重叠率从 0.5 调到 0.25，对齐了 Python audio-separator 库的默认值。窗口数减少 1.5 倍，推理提速约 1.5 倍，RTF 从 2.03 降到约 1.35。这是排查过程中顺带发现的速度优化，和内存无关，但值得记一笔。

## 五、方法论沉淀：诊断清单

这次排障踩了不少坑，沉淀成清单，下次能少走弯路：

**1. 读懂 dmesg 的 OOM 记录**

- `global_oom`（CONSTRAINT_NONE）：全局内存不足，内核随机挑受害者；
- `CONSTRAINT_MEMCG`：cgroup 内 OOM，是容器自己的限制触发的。
- `anon-rss` 是匿名内存，`shmem-rss` 是共享内存。**两个都要看**，只看一个会漏掉真相。

**2. "Java 日志没有异常"恰恰是证据**

进程被外部 SIGKILL 时，是瞬间蒸发的，根本没有机会打印任何东西。日志干净，反而说明是外部杀掉的，不是 JVM 自己崩的。

**3. docker inspect 的 oomkilled=false 陷阱**

```
docker inspect <container> | grep -E "OOMKilled|RestartCount|ExitCode"
```

容器重启后，`State` 会被重置，`oomkilled` 会显示 false。要结合 `RestartCount` 和 dmesg 一起判断，单看 `oomkilled` 会被骗。

**4. 退出码速查**

| 退出码 | 含义 |
|--------|------|
| 137 | SIGKILL（通常是 OOM） |
| 139 | 段错误 |
| 143 | 优雅停止 |

**5. 看 /proc 区分内存构成**

```
cat /proc/<pid>/status | grep -E "RssAnon|RssShmem|RssFile"
```

这三个字段能帮你分清内存到底花在哪。

**6. 两条经验**

- `-Xmx` 不是越大越好。堆大小要匹配实际存活数据，而不是"能塞多少塞多少"。这次 10g 的堆，实际存活数据只有 1.5g，剩下的 7-8g 全是被 ZGC 白白 committed 又迟迟不归还的。
- 容器里 ZGC 的 memory accounting 是误导源。选 GC 时，要考虑容器怎么记账，而不只是看延迟和吞吐。G1 的匿名私有内存，在容器里反而更透明、更好排查。

## 六、结尾

这场事故的教训，说到底是一句话：**在容器里，你看到的 Java 内存占用，可能不是它真正的内存占用。**

ZGC 用 memfd 把堆映射成共享内存，让 docker stats 和 /proc 都给出了一个"看起来很小"的假象。我们被这个假象骗着把 -Xmx 越调越大，结果越调越糟。直到 dmesg 里的 shmem-rss 揭开了真相，才明白问题从来不是"堆不够"，而是"堆的记账方式在容器里失真"。

最终，换回 G1、把堆调到 4g、收紧 mem_limit、缩小分块，问题彻底解决。顺带还白捡了一个 1.5 倍的推理加速。

把这次事故的完整链条串起来：dmesg 里 shmem-rss 随 -Xmx 线性变化这个细节，是整条线索的转折点。它第一次把"内存占用"和"JVM 配置"建立了联系，逼着我们去查 ZGC 的底层映射方式，而不是继续在 docker stats 的表面数字上打转。

如果你也在容器里跑 ZGC，或者你的 Java 进程"莫名被杀"却查不到日志，不妨先看一眼 dmesg，再翻一翻 /proc 的 RssShmem。真相，往往藏在你没看的那一列里。