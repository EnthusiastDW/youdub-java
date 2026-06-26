import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../utils";
import { StageProgress } from "@/components/StageProgress";
import type { TaskStage } from "@/types";

const stages: TaskStage[] = [
  {
    name: "download",
    label: "Download",
    status: "succeeded",
    progress: 100,
    startedAt: "2026-06-25T10:00:00Z",
    completedAt: "2026-06-25T10:05:00Z",
    lastMessage: "Completed",
    errorMessage: null,
  },
  {
    name: "separate",
    label: "Demucs",
    status: "running",
    progress: 50,
    startedAt: "2026-06-25T10:05:00Z",
    completedAt: null,
    lastMessage: "Processing...",
    errorMessage: null,
  },
  {
    name: "asr",
    label: "Whisper",
    status: "failed",
    progress: 30,
    startedAt: "2026-06-25T10:10:00Z",
    completedAt: "2026-06-25T10:12:00Z",
    lastMessage: "Error",
    errorMessage: "Model not found",
  },
  {
    name: "translate",
    label: "Translate",
    status: "pending",
    progress: 0,
    startedAt: null,
    completedAt: null,
    lastMessage: "",
    errorMessage: null,
  },
];

describe("StageProgress", () => {
  it("renders all stages", () => {
    renderWithProviders(<StageProgress stages={stages} />);
    expect(screen.getByText("下载视频")).toBeInTheDocument();
    expect(screen.getByText("分离人声与背景音")).toBeInTheDocument();
    expect(screen.getByText("语音识别")).toBeInTheDocument();
    expect(screen.getByText("翻译字幕")).toBeInTheDocument();
  });

  it("shows status badges", () => {
    renderWithProviders(<StageProgress stages={stages} />);
    expect(screen.getAllByText("已完成").length).toBeGreaterThan(0);
    expect(screen.getAllByText("运行中").length).toBeGreaterThan(0);
    expect(screen.getAllByText("失败").length).toBeGreaterThan(0);
    expect(screen.getAllByText("等待中").length).toBeGreaterThan(0);
  });

  it("shows error message for failed stage", () => {
    renderWithProviders(<StageProgress stages={stages} />);
    expect(screen.getByText("Model not found")).toBeInTheDocument();
  });

  it("shows last message when present", () => {
    renderWithProviders(<StageProgress stages={stages} />);
    expect(screen.getByText("Processing...")).toBeInTheDocument();
  });

  it("shows redo button when canRedo is true", () => {
    renderWithProviders(<StageProgress stages={stages} canRedo={true} onRedoStage={vi.fn()} />);
    const redoButtons = screen.getAllByText("重做");
    expect(redoButtons.length).toBe(3);
  });

  it("hides redo button for pending stages", () => {
    renderWithProviders(<StageProgress stages={stages} canRedo={true} onRedoStage={vi.fn()} />);
    const pendingStage = screen.getByText("翻译字幕").closest("div");
    expect(pendingStage?.querySelector("button")).toBeNull();
  });

  it("calls onRedoStage when redo button clicked", async () => {
    const user = userEvent.setup();
    const onRedoStage = vi.fn();
    renderWithProviders(<StageProgress stages={stages} canRedo={true} onRedoStage={onRedoStage} />);
    const redoButtons = screen.getAllByText("重做");
    await user.click(redoButtons[0]);
    expect(onRedoStage).toHaveBeenCalledWith("download");
  });
});
