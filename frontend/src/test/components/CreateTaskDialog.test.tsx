import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../utils";
import { CreateTaskDialog } from "@/components/CreateTaskDialog";

vi.mock("@/api/client", () => ({
  createTask: vi.fn(),
}));

import { createTask } from "@/api/client";

describe("CreateTaskDialog", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("does not render when closed", () => {
    renderWithProviders(
      <CreateTaskDialog open={false} onClose={vi.fn()} onCreated={vi.fn()} />
    );
    expect(screen.queryByText("新建任务")).not.toBeInTheDocument();
  });

  it("renders form when open", () => {
    renderWithProviders(
      <CreateTaskDialog open={true} onClose={vi.fn()} onCreated={vi.fn()} />
    );
    expect(screen.getByText("新建任务")).toBeInTheDocument();
    expect(screen.getByText("视频链接（YouTube / Bilibili）")).toBeInTheDocument();
    expect(screen.getByText("执行模式")).toBeInTheDocument();
  });

  it("disables create button when URL is empty", () => {
    renderWithProviders(
      <CreateTaskDialog open={true} onClose={vi.fn()} onCreated={vi.fn()} />
    );
    expect(screen.getByText("创建任务").closest("button")).toBeDisabled();
  });

  it("calls createTask on submit", async () => {
    const user = userEvent.setup();
    const onCreated = vi.fn();
    vi.mocked(createTask).mockResolvedValue({
      id: "new-task-1",
      url: "https://www.youtube.com/watch?v=test",
      title: "Test",
      status: "queued",
      currentStage: null,
      sessionPath: "",
      finalVideoPath: null,
      errorMessage: null,
      executionMode: "auto",
      sourceType: "youtube",
      asrLanguage: "en",
      targetLanguage: "zh",
      progress: 0,
      createdAt: "2026-06-25T10:00:00Z",
      startedAt: null,
      completedAt: null,
      stages: [],
    });

    renderWithProviders(
      <CreateTaskDialog open={true} onClose={vi.fn()} onCreated={onCreated} />
    );

    const input = screen.getByPlaceholderText("https://www.youtube.com/watch?v=...");
    await user.type(input, "https://www.youtube.com/watch?v=test");
    await user.click(screen.getByText("创建任务"));

    await waitFor(() => {
      expect(createTask).toHaveBeenCalledWith(
        "https://www.youtube.com/watch?v=test",
        "auto"
      );
    });
    expect(onCreated).toHaveBeenCalledWith("new-task-1");
  });

  it("shows error on failure", async () => {
    const user = userEvent.setup();
    vi.mocked(createTask).mockRejectedValue(new Error("Invalid URL"));

    renderWithProviders(
      <CreateTaskDialog open={true} onClose={vi.fn()} onCreated={vi.fn()} />
    );

    const input = screen.getByPlaceholderText("https://www.youtube.com/watch?v=...");
    await user.type(input, "invalid");
    await user.click(screen.getByText("创建任务"));

    await waitFor(() => {
      expect(screen.getByText("Invalid URL")).toBeInTheDocument();
    });
  });
});
