import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../utils";
import HomePage from "@/pages/HomePage";

vi.mock("@/hooks/useTasks", () => ({
  useTasks: vi.fn(() => ({
    tasks: [],
    loading: false,
    error: null,
    refresh: vi.fn(),
  })),
}));

vi.mock("@/api/client", () => ({
  createTask: vi.fn(),
  uploadLocalTask: vi.fn(),
  rerunTask: vi.fn(),
  resumeTask: vi.fn(),
  deleteTask: vi.fn(),
  listTasks: vi.fn(),
  getTask: vi.fn(),
  getTaskLog: vi.fn(),
  getSettings: vi.fn(() => Promise.resolve({ notesTemplate: "" })),
  finalVideoUrl: vi.fn((id: string) => `/api/tasks/${id}/artifact/final-video`),
  finalVideoDownloadUrl: vi.fn((id: string) => `/api/tasks/${id}/artifact/final-video?download=true`),
}));

import { useTasks } from "@/hooks/useTasks";
import type { Task } from "@/types";

function createMockTask(overrides: Partial<Task> = {}): Task {
  return {
    id: "task-1",
    url: "https://www.youtube.com/watch?v=abc123",
    title: "Test Video",
    status: "succeeded",
    currentStage: null,
    sessionPath: "/workfolder/test",
    finalVideoPath: "/workfolder/test/video_final.mp4",
    errorMessage: null,
    executionMode: "auto",
    sourceType: "youtube",
    asrLanguage: "en",
    targetLanguage: "zh",
    progress: 100,
    createdAt: "2026-06-25T10:00:00Z",
    startedAt: "2026-06-25T10:01:00Z",
    completedAt: "2026-06-25T10:30:00Z",
    notes: "",
    youtubeVideoId: "",
    stages: [],
    ...overrides,
  };
}

describe("HomePage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders page title and subtitle", () => {
    renderWithProviders(<HomePage />);
    expect(screen.getByText("YouDub — 视频本地化工具")).toBeInTheDocument();
    expect(
      screen.getByText("使用声音克隆技术，将视频翻译并配音为目标语言")
    ).toBeInTheDocument();
  });

  it("renders create task section", () => {
    renderWithProviders(<HomePage />);
    expect(screen.getByText("创建任务")).toBeInTheDocument();
  });

  it("opens dialog with URL and upload tabs when button clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<HomePage />);
    const createButtons = screen.getAllByText("创建任务");
    await user.click(createButtons[0]);
    await waitFor(() => {
      expect(screen.getByText("链接")).toBeInTheDocument();
      expect(screen.getByText("本地上传")).toBeInTheDocument();
    });
  });

  it("renders task history heading", () => {
    renderWithProviders(<HomePage />);
    expect(screen.getByText("任务历史")).toBeInTheDocument();
  });

  it("shows empty state when no tasks", () => {
    renderWithProviders(<HomePage />);
    expect(
      screen.getByText("暂无任务。输入链接或上传本地视频后即可开始。")
    ).toBeInTheDocument();
  });

  it("renders task cards when tasks exist", () => {
    vi.mocked(useTasks).mockReturnValue({
      tasks: [createMockTask()],
      total: 1,
      loading: false,
      error: null,
      page: 0,
      pageSize: 20,
      totalPages: 1,
      setPage: vi.fn(),
      refresh: vi.fn(),
    });
    renderWithProviders(<HomePage />);
    expect(screen.getByText("Test Video")).toBeInTheDocument();
  });

  it("shows loading skeleton when loading", () => {
    vi.mocked(useTasks).mockReturnValue({
      tasks: [],
      total: 0,
      loading: true,
      error: null,
      page: 0,
      pageSize: 20,
      totalPages: 1,
      setPage: vi.fn(),
      refresh: vi.fn(),
    });
    renderWithProviders(<HomePage />);
    const skeletons = document.querySelectorAll(".animate-pulse");
    expect(skeletons.length).toBeGreaterThan(0);
  });

  it("opens create task dialog when button clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<HomePage />);
    const createButtons = screen.getAllByText("创建任务");
    await user.click(createButtons[0]);
    await waitFor(() => {
      expect(screen.getByText("视频链接（YouTube / Bilibili）")).toBeInTheDocument();
    });
  });
});
