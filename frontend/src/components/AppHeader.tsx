import { Link } from "react-router-dom";
import { Settings, Globe } from "lucide-react";
import { useI18n, LANGUAGE_OPTIONS, type UiLanguage } from "@/i18n/index";
import { buttonVariants } from "@/components/ui/button";
import { Select } from "@/components/ui/select";

export function AppHeader() {
  const { language, setLanguage, t } = useI18n();

  return (
    <header className="sticky top-0 z-30 border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-4">
        <Link to="/" className="flex items-center gap-2">
          <div className="flex h-8 w-8 items-center justify-center rounded-md bg-primary text-primary-foreground">
            <span className="text-sm font-bold">Y</span>
          </div>
          <span className="text-lg font-semibold">YouDub</span>
        </Link>

        <div className="flex items-center gap-2">
          <div className="flex items-center gap-1.5">
            <Globe className="h-4 w-4 text-muted-foreground" />
            <Select
              value={language}
              onChange={(e) => setLanguage(e.target.value as UiLanguage)}
              className="h-9 w-32"
            >
              {LANGUAGE_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </Select>
          </div>
          <Link
            to="/settings"
            aria-label={t.settings.button}
            className={buttonVariants({ variant: "ghost", size: "icon" })}
          >
            <Settings className="h-5 w-5" />
          </Link>
        </div>
      </div>
    </header>
  );
}
