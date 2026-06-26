import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "../utils";
import { LogViewer } from "@/components/LogViewer";

describe("LogViewer", () => {
  it("renders log content", () => {
    const logContent = "2026-06-25 10:00:00 [INFO] Starting task\n2026-06-25 10:01:00 [INFO] Download complete";
    renderWithProviders(<LogViewer log={logContent} />);
    expect(screen.getByText(/Starting task/)).toBeInTheDocument();
    expect(screen.getByText(/Download complete/)).toBeInTheDocument();
  });

  it("shows empty message when no log", () => {
    renderWithProviders(<LogViewer log="" />);
    expect(screen.getByText("任务开始后会显示日志。")).toBeInTheDocument();
  });

  it("renders title", () => {
    renderWithProviders(<LogViewer log="test" />);
    expect(screen.getByText("运行日志")).toBeInTheDocument();
  });
});
