import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "./utils";
import { useI18n, LANGUAGE_OPTIONS } from "@/i18n/index";

function LanguageConsumer() {
  const { language, t } = useI18n();
  return (
    <div>
      <span data-testid="current-language">{language}</span>
      <span data-testid="home-title">{t.home.title}</span>
      <span data-testid="status-running">{t.status.running}</span>
      <span data-testid="stage-download">{t.stages.download}</span>
    </div>
  );
}

describe("i18n", () => {
  it("provides default Chinese translations", () => {
    renderWithProviders(<LanguageConsumer />);
    expect(screen.getByTestId("current-language")).toHaveTextContent("zh");
    expect(screen.getByTestId("home-title")).toHaveTextContent("YouDub — 视频本地化工具");
    expect(screen.getByTestId("status-running")).toHaveTextContent("运行中");
  });

  it("has English and Chinese language options", () => {
    expect(LANGUAGE_OPTIONS).toHaveLength(2);
    expect(LANGUAGE_OPTIONS.map((o) => o.value)).toEqual(["en", "zh"]);
  });

  it("provides stage label translations", () => {
    renderWithProviders(<LanguageConsumer />);
    expect(screen.getByTestId("stage-download")).toHaveTextContent("下载视频");
  });
});
