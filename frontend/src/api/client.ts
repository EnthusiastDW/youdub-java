import type {
  ContinueTaskRequest,
  CreateTaskRequest,
  ExecutionMode,
  HealthResponse,
  LocalDirection,
  OpenAIModelsResponse,
  Settings,
  SettingsRequest,
  Task,
  TaskListResponse,
} from "@/types";

const API_BASE = "";

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(options?.headers || {}),
    },
    cache: "no-store",
  });

  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    const detail =
      (body as { detail?: string; message?: string }).detail ||
      (body as { message?: string }).message ||
      `Request failed: ${response.status}`;
    throw new Error(detail);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("application/json")) {
    return response.json() as Promise<T>;
  }
  return (await response.text()) as unknown as T;
}

export function createTask(url: string, executionMode: ExecutionMode = "auto") {
  const body: CreateTaskRequest = { url, executionMode };
  return request<Task>("/api/tasks", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function uploadLocalTask(
  file: File,
  executionMode: ExecutionMode,
  direction: LocalDirection,
  subtitleFile: File | null = null
): Promise<Task> {
  const form = new FormData();
  form.append("file", file);
  form.append("executionMode", executionMode);
  form.append("direction", direction);
  if (subtitleFile) {
    form.append("subtitleFile", subtitleFile);
  }

  const response = await fetch(`${API_BASE}/api/tasks/upload`, {
    method: "POST",
    body: form,
    cache: "no-store",
  });

  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(
      (body as { detail?: string }).detail || `Upload failed: ${response.status}`
    );
  }
  return response.json() as Promise<Task>;
}

export function listTasks(limit = 20) {
  return request<TaskListResponse>(`/api/tasks?limit=${limit}`);
}

export function getTask(taskId: string) {
  return request<Task>(`/api/tasks/${taskId}`);
}

export function getCurrentTask() {
  return request<Task | null>("/api/tasks/current");
}

export function deleteTask(taskId: string) {
  return request<void>(`/api/tasks/${taskId}`, { method: "DELETE" });
}

export function rerunTask(taskId: string) {
  return request<Task>(`/api/tasks/${taskId}/rerun`, { method: "POST" });
}

export function resumeTask(taskId: string) {
  return request<Task>(`/api/tasks/${taskId}/resume`, { method: "POST" });
}

export function continueTask(taskId: string, executionMode?: ExecutionMode) {
  const body: ContinueTaskRequest = executionMode ? { executionMode } : {};
  return request<Task>(`/api/tasks/${taskId}/continue`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function redoStage(taskId: string, stageName: string) {
  return request<Task>(`/api/tasks/${taskId}/stages/${stageName}/redo`, {
    method: "POST",
  });
}

export async function getTaskLog(taskId: string, offset = 0): Promise<string> {
  const response = await fetch(`${API_BASE}/api/tasks/${taskId}/log?offset=${offset}`, {
    cache: "no-store",
  });
  if (!response.ok) {
    throw new Error(`Failed to load log: ${response.status}`);
  }
  return response.text();
}

export function finalVideoUrl(taskId: string) {
  return `${API_BASE}/api/tasks/${taskId}/artifact/final-video?download=false`;
}

export function finalVideoDownloadUrl(taskId: string) {
  return `${API_BASE}/api/tasks/${taskId}/artifact/final-video?download=true`;
}

export function getSettings() {
  return request<Settings>("/api/settings");
}

export function saveSettings(settings: SettingsRequest) {
  return request<Settings>("/api/settings", {
    method: "POST",
    body: JSON.stringify(settings),
  });
}

export function getOpenAIModels(baseUrl: string, apiKey: string) {
  return request<OpenAIModelsResponse>("/api/settings/openai/models", {
    method: "POST",
    body: JSON.stringify({ baseUrl, apiKey }),
  });
}

export function getEdgeTtsVoices() {
  return request<{ voices: string[] }>("/api/settings/edge-tts/voices");
}

export function getHealth() {
  return request<HealthResponse>("/api/health");
}
