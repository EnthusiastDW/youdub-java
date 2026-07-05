package com.youdub.replica.service.adapter.video;

import com.youdub.replica.model.entity.Task;
import java.nio.file.Path;

/**
 * 视频处理适配器接口。
 * 处理最终视频合成（配音+背景音乐+字幕）。
 */
public interface VideoProcessor {
    void mergeVideo(Task task, Path videoPath, Path dubbingPath, Path bgmPath, Path timingsPath, Path outputDir) throws Exception;
}
