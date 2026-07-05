export type TaskStatus =
  | "queued"
  | "running"
  | "paused"
  | "succeeded"
  | "failed"
  | "cancelled";

export type StageStatus = "pending" | "running" | "succeeded" | "failed";

export type ExecutionMode = "auto" | "manual";

export type LocalDirection = "en-zh" | "zh-en";

export interface TaskStage {
  name: string;
  label: string;
  status: StageStatus;
  progress: number;
  startedAt: string | null;
  completedAt: string | null;
  lastMessage: string;
  errorMessage: string | null;
}

export interface Task {
  id: string;
  url: string;
  title: string;
  status: TaskStatus;
  currentStage: string | null;
  sessionPath: string;
  finalVideoPath: string | null;
  errorMessage: string | null;
  executionMode: string;
  sourceType: string;
  asrLanguage: string;
  targetLanguage: string;
  progress: number;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  notes: string;
  summary?: string;
  youtubeVideoId: string;
  stages: TaskStage[];
}

export interface TaskListResponse {
  tasks: Task[];
}

export interface YtdlpSettings {
  proxy: string;
}

export interface YouTubeCookieInfo {
  exists: boolean;
  size: number;
  updatedAt: string | null;
  content: string;
}

export type ProviderOptions = Record<string, string>;

export interface ProviderGroup {
  current: string;
  options: Record<string, ProviderOptions>;
}

export interface ProvidersData {
  asr: ProviderGroup;
  tts: ProviderGroup;
  translate: ProviderGroup;
  separate: ProviderGroup;
}

export interface Settings {
  ytdlp: YtdlpSettings;
  youtubeCookie: YouTubeCookieInfo;
  providers: ProvidersData;
  notesTemplate?: string;
}

export interface SettingsRequest {
  ytdlp?: {
    proxy?: string;
  };
  youtubeCookie?: {
    content?: string;
  };
  providers?: {
    asr?: string;
    tts?: string;
    translate?: string;
    separate?: string;
  };
  providerConfigs?: Record<string, string>;
  notesTemplate?: string;
}

export interface OpenAIModelsResponse {
  models: string[];
}

export interface HealthResponse {
  status: string;
}

export interface ContinueTaskRequest {
  executionMode?: ExecutionMode;
}

export interface CreateTaskRequest {
  url: string;
  executionMode: ExecutionMode;
  notes?: string;
  youtubeVideoId?: string;
}
