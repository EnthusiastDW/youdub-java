import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../utils";
import { TaskCard } from "@/components/TaskCard";
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
    stages: [],
    ...overrides,
  };
}

describe("TaskCard", () => {
  it("renders task title", () => {
    const task = createMockTask();
    renderWithProviders(<TaskCard task={task} />);
    expect(screen.getByText("Test Video")).toBeInTheDocument();
  });

  it("renders status badge", () => {
    const task = createMockTask({ status: "failed" });
    renderWithProviders(<TaskCard task={task} />);
    expect(screen.getByText("失败")).toBeInTheDocument();
  });

  it("shows progress percentage", () => {
    const task = createMockTask({ progress: 45 });
    renderWithProviders(<TaskCard task={task} />);
    expect(screen.getByText("45%")).toBeInTheDocument();
  });

  it("shows error message when present", () => {
    const task = createMockTask({
      status: "failed",
      errorMessage: "Download failed",
    });
    renderWithProviders(<TaskCard task={task} />);
    expect(screen.getByText("Download failed")).toBeInTheDocument();
  });

  it("calls onResume when resume button clicked", async () => {
    const user = userEvent.setup();
    const onResume = vi.fn();
    const task = createMockTask({ status: "failed" });
    renderWithProviders(<TaskCard task={task} onResume={onResume} />);
    const resumeButton = screen.getByText("继续任务");
    await user.click(resumeButton);
    expect(onResume).toHaveBeenCalledWith(task);
  });

  it("calls onDelete when delete button clicked", async () => {
    const user = userEvent.setup();
    const onDelete = vi.fn();
    const task = createMockTask();
    renderWithProviders(<TaskCard task={task} onDelete={onDelete} />);
    const deleteButton = screen.getByText("删除任务");
    await user.click(deleteButton);
    expect(onDelete).toHaveBeenCalledWith(task);
  });

  it("disables action buttons when running", () => {
    const onRerun = vi.fn();
    const onDelete = vi.fn();
    const task = createMockTask({ status: "running" });
    renderWithProviders(<TaskCard task={task} onRerun={onRerun} onDelete={onDelete} />);
    expect(screen.getByText("重跑任务").closest("button")).toBeDisabled();
    expect(screen.getByText("删除任务").closest("button")).toBeDisabled();
  });
});
