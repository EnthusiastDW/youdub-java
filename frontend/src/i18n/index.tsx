import { createContext, ReactNode, useContext, useEffect, useMemo, useState } from "react";

export type UiLanguage = "en" | "zh";

const STORAGE_KEY = "youdub-ui-language";

export const LANGUAGE_OPTIONS: { value: UiLanguage; label: string }[] = [
  { value: "en", label: "English" },
  { value: "zh", label: "中文" },
];

type Messages = {
  common: {
    back: string;
    cancel: string;
    close: string;
    confirm: string;
    loading: string;
    waiting: string;
    delete: string;
    retry: string;
    edit: string;
    total: string;
  };
  home: Record<string, string>;
  task: Record<string, string>;
  settings: Record<string, string>;
  status: Record<string, string>;
  stages: Record<string, string>;
};

const messages: Record<UiLanguage, Messages> = {
  en: {
    common: {
      back: "Back",
      cancel: "Cancel",
      close: "Close",
      confirm: "Confirm",
      loading: "Loading",
      waiting: "Waiting",
      delete: "Delete",
      retry: "Retry",
      edit: "Edit",
      total: "Total",
    },
    home: {
      title: "YouDub — Video Localization",
      subtitle: "Translate and dub videos into your language with voice cloning",
      createTitle: "Create new task",
      urlLabel: "Video URL (YouTube / Bilibili)",
      urlPlaceholder: "https://www.youtube.com/watch?v=...",
      executionModeLabel: "Execution mode",
      executionAuto: "Auto (run all stages)",
      executionManual: "Manual (step by step)",
      executionSubtitleOnly: "Subtitles only (no TTS, no audio replacement)",
      createTask: "Create task",
      submitting: "Submitting",
      uploadTitle: "Upload local video",
      localVideoLabel: "Local video file",
      localSubtitleLabel: "Translated SRT subtitles (optional)",
      localSubtitleHelp:
        "When provided, the SRT is used for TTS and burned subtitles, and Whisper/OpenAI translation are skipped.",
      localDirectionLabel: "Translation direction",
      localEnZh: "English -> Chinese",
      localZhEn: "Chinese -> English",
      uploadTask: "Upload and create",
      uploading: "Uploading",
      taskHistory: "Task history",
      empty: "No tasks yet. Submit a URL or upload a local video above to start.",
      loadError: "Failed to load tasks",
      urlTab: "URL",
      uploadTab: "Local file",
      createError: "Failed to create task",
      uploadError: "Failed to upload task",
      notesLabel: "Notes",
      notesPlaceholder: "Video source, author info, etc.",
      youtubeCoverLabel: "YouTube cover",
      youtubeCoverHelp: "Auto-fetch cover from YouTube",
      youtubeUrlLabel: "YouTube video link (optional)",
      youtubeUrlPlaceholder: "https://www.youtube.com/watch?v=...",
    },
    task: {
      overview: "Task overview",
      title: "Title",
      taskId: "Task ID",
      url: "Source URL",
      status: "Status",
      created: "Created",
      started: "Started",
      completed: "Completed",
      session: "Session",
      executionMode: "Execution mode",
      sourceType: "Source type",
      progress: "Progress",
      loading: "Loading task...",
      finalVideo: "Final video",
      download: "Download",
      stages: "Stages",
      resumeHelp: "Resume from the failed stage. Already-succeeded stages will be reused from cache.",
      resumeTask: "Resume task",
      resuming: "Resuming",
      continueHelp: "Run the next stage. Completed stages stay cached.",
      continueTask: "Run next stage",
      continueAutoTask: "Run remaining automatically",
      continuing: "Continuing",
      runLog: "Run log",
      emptyLog: "Logs will appear once the task starts.",
      dangerZone: "Danger zone",
      rerunHelp: "Wipe the session directory and run this URL again from scratch.",
      rerunTask: "Rerun task",
      rerunTitle: "Rerun this task? All generated files (audio, video, subtitles, etc.) will be deleted.",
      rerunDescription:
        "Existing log, session directory and final video will be deleted, then the same URL is re-queued under the same task id.",
      rerunning: "Rerunning",
      confirmRerun: "Confirm rerun",
      deleteHelp: "Delete this task, its run log, and the entire session directory.",
      deleteTask: "Delete task",
      deleteTitle: "Delete this task?",
      deleteDescription:
        "This action cannot be undone. The following will be permanently deleted:",
      deleting: "Deleting",
      confirmDelete: "Confirm delete",
      deleteItemDb: "Task record in database",
      deleteItemSession: "Session directory (artifacts, subtitles, audio, video)",
      deleteItemUpload: "Uploaded video files",
      deleteItemLog: "Task log file",
      downloadCover: "Download cover",
      runningLocked: "Running tasks cannot be rerun or deleted. Wait until it finishes or fails.",
      loadError: "Failed to load task",
      deleteError: "Failed to delete task",
      rerunError: "Failed to rerun task",
      resumeError: "Failed to resume task",
      continueError: "Failed to continue task",
      stopTask: "Stop",
      stopping: "Stopping",
      stopError: "Failed to stop task",
      stopConfirm: "Stop this task? The current stage will fail and the pipeline exits immediately.",
      redoStage: "Redo",
      redoingStage: "Redoing",
      redoStageError: "Failed to redo stage",
      redoStageHelp: "Re-run this stage and clear downstream artifacts. Earlier stages stay cached.",
      redoStageTitle: "Redo this stage?",
      redoStageDescription: "This clears this stage and all downstream artifacts, then re-queues from",
      confirmRedoStage: "Confirm redo",
      duration: "Duration",
      errorMessage: "Error",
      lastMessage: "Message",
      notes: "Notes",
      noNotes: "No notes",
      youtubeVideoId: "YouTube Video ID",
      noYoutubeVideoId: "Not set",
      summary: "Video summary",
      noSummary: "Summary will be generated during the translate stage.",
    },
    settings: {
      button: "Settings",
      title: "Runtime settings",
      description: "Stored locally by the backend.",
      language: "Interface language",
      openaiSection: "OpenAI settings",
      baseUrl: "Base URL",
      apiKey: "API Key",
      apiKeyPlaceholder: "Leave blank to keep existing key",
      model: "Model",
      selectModel: "Select model",
      getModels: "Get models",
      loadingModels: "Loading",
      translateConcurrency: "Translate concurrency",
      concurrency: "Concurrency",
      concurrencyHelp: "Parallel OpenAI requests during the translate stage.",
      ytdlpSection: "yt-dlp settings",
      proxy: "Proxy",
      proxyHelp: "HTTP proxy used by yt-dlp for downloading.",
      cookieSection: "YouTube cookie",
      cookieExists: "Cookie exists",
      cookieSize: "Size",
      cookieUpdatedAt: "Last updated",
      cookieContent: "Cookie content (Netscape format)",
      cookiePlaceholder: "Paste Netscape cookie content",
      translateSection: "Translate provider",
      ttsSection: "TTS provider",
      asrSection: "ASR provider",
      separateSection: "Source separation",
      separateFfmpegNote: "FFmpeg simple filter: vocals only (no BGM — frequency-based BGM is noisy). Use Demucs or audio-separator-api for full separation with BGM.",
      "asr-correctorSection": "ASR Corrector",
      asrCorrectorFallbackNote: "Fields left empty fall back to the translate provider configuration.",
      currentProvider: "Provider",
      url: "URL",
      voice: "Voice",
      serviceUrl: "Service URL",
      path: "Path",
      hasApiKey: "API key configured",
      save: "Save settings",
      saving: "Saving",
      saved: "Settings saved.",
      saveError: "Failed to save settings",
      noModels: "No models returned.",
      loadModelsError: "Failed to load models",
      modelsLoaded: "models loaded",
      keySaved: "OpenAI key is saved.",
      notesTemplateTitle: "Notes template",
      notesTemplateHelp: "This text will be auto-filled into the Notes field when creating a new task.",
      notesTemplatePlaceholder: "Video source: ...\nOriginal author: ...",
    },
    status: {
      queued: "queued",
      running: "running",
      paused: "paused",
      succeeded: "succeeded",
      failed: "failed",
      cancelled: "cancelled",
      pending: "pending",
    },
    stages: {
      download: "Download",
      separate: "Demucs",
      asr: "Whisper",
      asr_correct: "ASR Correct",
      asr_fix: "Split sentences",
      translate: "Translate",
      split_audio: "Split audio",
      tts: "VoxCPM",
      merge_audio: "Merge audio",
      merge_video: "Merge video",
      done: "Done",
    },
  },
  zh: {
    common: {
      back: "返回",
      cancel: "取消",
      close: "关闭",
      confirm: "确认",
      loading: "加载中",
      waiting: "等待中",
      delete: "删除",
      retry: "重试",
      edit: "编辑",
      total: "共",
    },
    home: {
      title: "YouDub — 视频本地化工具",
      subtitle: "使用声音克隆技术，将视频翻译并配音为目标语言",
      createTitle: "新建任务",
      urlLabel: "视频链接（YouTube / Bilibili）",
      urlPlaceholder: "https://www.youtube.com/watch?v=...",
      executionModeLabel: "执行模式",
      executionAuto: "自动（连续执行全部阶段）",
      executionManual: "手动（逐步执行）",
      executionSubtitleOnly: "仅字幕（不做配音，不替换音频）",
      createTask: "创建任务",
      submitting: "提交中",
      uploadTitle: "上传本地视频",
      localVideoLabel: "本地视频文件",
      localSubtitleLabel: "已翻译 SRT 字幕（可选）",
      localSubtitleHelp: "上传后会直接用于配音和压制字幕，并跳过 Whisper 识别与 OpenAI 翻译。",
      localDirectionLabel: "翻译方向",
      localEnZh: "英文 -> 中文",
      localZhEn: "中文 -> 英文",
      uploadTask: "上传并创建",
      uploading: "上传中",
      taskHistory: "任务历史",
      empty: "暂无任务。输入链接或上传本地视频后即可开始。",
      loadError: "加载任务失败",
      urlTab: "链接",
      uploadTab: "本地上传",
      createError: "创建任务失败",
      uploadError: "上传任务失败",
      notesLabel: "备注",
      notesPlaceholder: "视频来源、原作者信息等",
      youtubeCoverLabel: "YouTube 封面",
      youtubeCoverHelp: "自动从 YouTube 获取封面",
      youtubeUrlLabel: "YouTube 视频链接（可选）",
      youtubeUrlPlaceholder: "https://www.youtube.com/watch?v=...",
    },
    task: {
      overview: "任务概览",
      title: "标题",
      taskId: "任务 ID",
      url: "来源链接",
      status: "状态",
      created: "创建时间",
      started: "开始时间",
      completed: "完成时间",
      session: "会话目录",
      executionMode: "执行模式",
      sourceType: "来源类型",
      progress: "进度",
      loading: "正在加载任务...",
      finalVideo: "最终视频",
      download: "下载",
      stages: "处理阶段",
      resumeHelp: "从失败阶段继续执行。已经成功的阶段会复用缓存结果。",
      resumeTask: "继续任务",
      resuming: "继续中",
      continueHelp: "执行下一个阶段。已完成的阶段会保留缓存。",
      continueTask: "执行下一阶段",
      continueAutoTask: "自动执行剩余阶段",
      continuing: "继续中",
      runLog: "运行日志",
      emptyLog: "任务开始后会显示日志。",
      dangerZone: "危险操作",
      rerunHelp: "清空会话目录，并从头重新运行这个链接。",
      rerunTask: "重跑任务",
      rerunTitle: "确认重跑？所有已生成文件（音频、视频、字幕等）将被删除。",
      rerunDescription:
        "现有日志、会话目录和最终视频会被删除，然后使用同一个任务 ID 重新排队处理相同链接。",
      rerunning: "重跑中",
      confirmRerun: "确认重跑",
      deleteHelp: "删除这个任务、运行日志，以及对应的整个会话目录。",
      deleteTask: "删除任务",
      deleteTitle: "确认删除这个任务？",
      deleteDescription: "此操作无法撤销。删除后将清除以下所有内容：",
      deleting: "删除中",
      confirmDelete: "确认删除",
      deleteItemDb: "数据库中的任务记录",
      deleteItemSession: "工作空间目录（音频、字幕、视频片段等所有产物）",
      deleteItemUpload: "已上传的原始视频文件",
      deleteItemLog: "任务运行日志",
      downloadCover: "下载封面",
      runningLocked: "运行中的任务不能重跑或删除，请等待任务完成或失败。",
      loadError: "加载任务失败",
      deleteError: "删除任务失败",
      rerunError: "重跑任务失败",
      resumeError: "继续任务失败",
      continueError: "执行下一阶段失败",
      stopTask: "停止",
      stopping: "停止中",
      stopError: "停止任务失败",
      stopConfirm: "确认停止任务？当前阶段将标记失败，管线立即退出。",
      redoStage: "重做",
      redoingStage: "重做中",
      redoStageError: "重做阶段失败",
      redoStageHelp: "重新执行该阶段并清除下游产物，更早的阶段会保留缓存。",
      redoStageTitle: "确认重做这个阶段？",
      redoStageDescription: "这会清除该阶段及所有下游产物，并从这里重新排队执行：",
      confirmRedoStage: "确认重做",
      duration: "耗时",
      errorMessage: "错误信息",
      lastMessage: "消息",
      notes: "备注",
      noNotes: "暂无备注",
      youtubeVideoId: "YouTube 视频 ID",
      noYoutubeVideoId: "未设置",
      summary: "视频摘要",
      noSummary: "摘要将在翻译阶段生成。",
    },
    settings: {
      button: "设置",
      title: "运行设置",
      description: "设置会由后端保存在本机。",
      language: "界面语言",
      openaiSection: "OpenAI 设置",
      baseUrl: "Base URL",
      apiKey: "API Key",
      apiKeyPlaceholder: "留空则保留现有 key",
      model: "模型",
      selectModel: "选择模型",
      getModels: "获取模型",
      loadingModels: "加载中",
      translateConcurrency: "翻译并发数",
      concurrency: "并发数",
      concurrencyHelp: "翻译阶段并行发起的 OpenAI 请求数。",
      ytdlpSection: "yt-dlp 设置",
      proxy: "代理",
      proxyHelp: "yt-dlp 下载时使用的 HTTP 代理。",
      cookieSection: "YouTube Cookie",
      cookieExists: "Cookie 是否存在",
      cookieSize: "文件大小",
      cookieUpdatedAt: "最后更新",
      cookieContent: "Cookie 内容（Netscape 格式）",
      cookiePlaceholder: "粘贴 Netscape 格式 Cookie 内容",
      translateSection: "翻译服务商",
      ttsSection: "配音服务商",
      asrSection: "语音识别服务商",
      separateSection: "音源分离",
      separateFfmpegNote: "FFmpeg 简单滤波：仅提取人声（不含背景音乐——频率滤波产生的 BGM 噪声大）。如需带 BGM 的完整分离，请选择 Demucs 或 audio-separator-api。",
      "asr-correctorSection": "ASR 纠错",
      asrCorrectorFallbackNote: "留空的字段将沿用翻译服务的配置。",
      currentProvider: "服务商",
      url: "地址",
      voice: "音色",
      serviceUrl: "服务地址",
      path: "路径",
      hasApiKey: "已配置 API key",
      save: "保存设置",
      saving: "保存中",
      saved: "设置已保存。",
      saveError: "保存设置失败",
      noModels: "没有返回可用模型。",
      loadModelsError: "加载模型失败",
      modelsLoaded: "个模型已加载",
      keySaved: "OpenAI API key 已保存。",
      notesTemplateTitle: "备注模板",
      notesTemplateHelp: "创建新任务时，该内容会自动填入备注字段。",
      notesTemplatePlaceholder: "视频来源：...\n原作者：...",
    },
    status: {
      queued: "排队中",
      running: "运行中",
      paused: "已暂停",
      succeeded: "已完成",
      failed: "失败",
      cancelled: "已取消",
      pending: "等待中",
    },
    stages: {
      download: "下载视频",
      separate: "分离人声与背景音",
      asr: "语音识别",
      asr_correct: "ASR 纠错",
      asr_fix: "切分句子",
      translate: "翻译字幕",
      split_audio: "切分音频",
      tts: "生成配音",
      merge_audio: "混合音频",
      merge_video: "合成视频",
      done: "已完成",
    },
  },
};

type LanguageContextValue = {
  language: UiLanguage;
  setLanguage: (language: UiLanguage) => void;
  t: Messages;
  statusLabel: (status?: string | null) => string;
  stageLabel: (name?: string | null, fallback?: string | null) => string;
};

const LanguageContext = createContext<LanguageContextValue | null>(null);

function isLanguage(value: string | null): value is UiLanguage {
  return value === "en" || value === "zh";
}

function setDocumentLanguage(language: UiLanguage) {
  document.documentElement.lang = language === "zh" ? "zh-CN" : "en";
}

export function LanguageProvider({ children }: { children: ReactNode }) {
  const [language, setLanguageState] = useState<UiLanguage>("zh");

  useEffect(() => {
    const saved = window.localStorage.getItem(STORAGE_KEY);
    if (isLanguage(saved)) {
      setLanguageState(saved);
    }
  }, []);

  useEffect(() => {
    setDocumentLanguage(language);
  }, [language]);

  const value = useMemo<LanguageContextValue>(() => {
    const t = messages[language];
    return {
      language,
      setLanguage: (next) => {
        setLanguageState(next);
        window.localStorage.setItem(STORAGE_KEY, next);
        setDocumentLanguage(next);
      },
      t,
      statusLabel: (status) => {
        if (!status) return t.common.loading;
        return t.status[status as keyof typeof t.status] || status;
      },
      stageLabel: (name, fallback) => {
        if (name && name in t.stages) return t.stages[name as keyof typeof t.stages];
        if (fallback && fallback in t.stages) return t.stages[fallback as keyof typeof t.stages];
        return fallback || name || t.common.waiting;
      },
    };
  }, [language]);

  return <LanguageContext.Provider value={value}>{children}</LanguageContext.Provider>;
}

export function useI18n() {
  const context = useContext(LanguageContext);
  if (!context) {
    throw new Error("useI18n must be used inside LanguageProvider");
  }
  return context;
}
