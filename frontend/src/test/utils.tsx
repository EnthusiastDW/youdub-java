import { ReactNode } from "react";
import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { LanguageProvider } from "@/i18n/index";

interface RenderOptions {
  initialEntries?: string[];
}

export function renderWithProviders(ui: ReactNode, options: RenderOptions = {}) {
  const { initialEntries = ["/"] } = options;
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <LanguageProvider>{ui}</LanguageProvider>
    </MemoryRouter>
  );
}
