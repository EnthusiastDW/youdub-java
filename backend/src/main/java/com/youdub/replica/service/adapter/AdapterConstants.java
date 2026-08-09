package com.youdub.replica.service.adapter;

/**
 * 适配器标识常量。
 * <p>
 * 所有适配器的 {@code @Component}、{@code getName()} 以及 {@code SettingsService} 中
 * provider name / settings key 均使用此处的常量，确保三处值绝对一致。
 */
public final class AdapterConstants {

    // ────────────────────────────── ASR ──────────────────────────────
    /** Whisper API（OpenAI 兼容）语音识别 */
    public static final String WHISPER_API = "whisper-api";
    /** whisper.cpp 本地语音识别 */
    public static final String WHISPER_CPP = "whisper-cpp";
    /** ONNX Runtime 本地 Whisper 语音识别（Java 进程内） */
    public static final String WHISPER_ONNX = "whisper-onnx";
    /** OpenAI LLM ASR 纠错 */
    public static final String OPENAI_ASR_CORRECTOR = "openai-asr-corrector";

    // ────────────────────────────── TTS ──────────────────────────────
    /** Edge-TTS 语音合成（子进程） */
    public static final String EDGE_TTS = "edge-tts";
    /** VoxCPM 语音合成（HTTP API） */
    public static final String VOXCPM = "voxcpm";
    /** VoxCPM2 C++ 语音合成（voxcpm2-cli 子进程，llama.cpp-omni） */
    public static final String VOXCPM_CPP = "voxcpm-cpp";
    /** OpenAI TTS 语音合成 */
    public static final String OPENAI_TTS = "openai-tts";

    // ────────────────────────────── Translate ────────────────────────
    /** OpenAI Chat API 翻译 */
    public static final String OPENAI = "openai";
    /** Ollama 本地模型翻译 */
    public static final String OLLAMA = "ollama";

    // ────────────────────────────── Separate ─────────────────────────
    /** Demucs 人声分离 */
    public static final String DEMUCS = "demucs";
    /** ONNX Runtime 本地人声分离（MDX-NET 模型） */
    public static final String ONNX = "onnx";

    // ────────────────────────────── Download ─────────────────────────
    /** yt-dlp 下载 */
    public static final String YTDLP = "ytdlp";
    /** 本地文件导入 */
    public static final String LOCAL = "local";

    // ────────────────────────────── Other ────────────────────────────
    /** FFmpeg 简易频率分离 */
    public static final String FFMPEG_SIMPLE = "ffmpeg-simple";
    /** FFmpeg 音频处理 */
    public static final String FFMPEG_AUDIO = "ffmpeg-audio";
    /** FFmpeg 视频合成 */
    public static final String FFMPEG_VIDEO = "ffmpeg-video";

    private AdapterConstants() {
    }
}
