import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ArrowLeft, Save, Loader2, RefreshCw, CheckCircle2 } from "lucide-react";
import { AppHeader } from "@/components/AppHeader";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Select } from "@/components/ui/select";
import { Badge } from "@/components/ui/badge";
import { useI18n } from "@/i18n/index";
import { getSettings, saveSettings, getOpenAIModels } from "@/api/client";
import { formatDateTime, formatFileSize } from "@/lib/utils";
import type { ProvidersData, Settings, SettingsRequest } from "@/types";

type SectionKey = "translate" | "tts" | "asr" | "separate";

const SAVED_API_KEY_MASK = "********";
const DEFAULT_BASE_URL = "https://api.openai.com/v1";
const DEFAULT_MODEL = "gpt-4o-mini";
const DEFAULT_CONCURRENCY = "50";

/** Render read-only config fields from providersData. */
function ProviderOptions({
  options,
  t,
}: {
  options: Record<string, string> | undefined;
  t: Record<string, string>;
}) {
  if (!options || Object.keys(options).length === 0) {
    return <p className="text-xs text-muted-foreground italic">No additional config</p>;
  }
  return (
    <div className="space-y-1.5">
      {Object.entries(options).map(([key, value]) => {
        const label = t[key] || key;
        if (key === "hasApiKey") {
          return (
            <div key={key} className="flex items-center gap-2 text-xs">
              <span className="text-muted-foreground">{t.hasApiKey}:</span>
              <span>{value === "true" ? "✓" : "✗"}</span>
            </div>
          );
        }
        return (
          <div key={key} className="text-xs">
            <span className="text-muted-foreground">{label}: </span>
            <span className="font-mono">{value || "—"}</span>
          </div>
        );
      })}
    </div>
  );
}

/** Provider selection card with dropdown and read-only options. */
function ProviderCard({
  sectionKey,
  providersData,
  current,
  onProviderChange,
  children,
  t,
}: {
  sectionKey: SectionKey;
  providersData: ProvidersData | null;
  current: string;
  onProviderChange: (value: string) => void;
  children?: React.ReactNode;
  t: Record<string, string>;
}) {
  const group = providersData?.[sectionKey];
  const options = group?.options?.[current];

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{t[`${sectionKey}Section`] || sectionKey}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="space-y-1.5">
          <Label className="text-xs">{t.currentProvider}</Label>
          <Select value={current} onChange={(e) => onProviderChange(e.target.value)}>
            {group?.options
              ? Object.keys(group.options).map((key) => (
                  <option key={key} value={key}>
                    {key}
                  </option>
                ))
              : <option value={current}>{current}</option>}
          </Select>
        </div>

        {children}

        <div className="border-t pt-2">
          <p className="text-xs font-medium text-muted-foreground mb-1">
            Config (read-only):
          </p>
          <ProviderOptions options={options} t={t} />
        </div>
      </CardContent>
    </Card>
  );
}

export default function SettingsPage() {
  const { t } = useI18n();
  const [settings, setSettings] = useState<Settings | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);
  const [providersData, setProvidersData] = useState<ProvidersData | null>(null);

  // Form state — provider selections
  const [translateProvider, setTranslateProvider] = useState("openai");
  const [ttsProvider, setTtsProvider] = useState("edge-tts");
  const [asrProvider, setAsrProvider] = useState("whisper-api");
  const [separateProvider, setSeparateProvider] = useState("ffmpeg-simple");

  // OpenAI translate overrides (savable)
  const [baseUrl, setBaseUrl] = useState(DEFAULT_BASE_URL);
  const [apiKey, setApiKey] = useState("");
  const [model, setModel] = useState(DEFAULT_MODEL);
  const [translateConcurrency, setTranslateConcurrency] = useState(DEFAULT_CONCURRENCY);

  // Proxy (savable)
  const [proxy, setProxy] = useState("");

  // Cookie
  const [cookieContent, setCookieContent] = useState("");
  const [cookieDirty, setCookieDirty] = useState(false);

  // Dirty tracking for apiKey
  const [apiKeyDirty, setApiKeyDirty] = useState(false);

  // Model list
  const [models, setModels] = useState<string[]>([]);
  const [loadingModels, setLoadingModels] = useState(false);

  useEffect(() => {
    let mounted = true;
    (async () => {
      try {
        const data = await getSettings();
        if (!mounted) return;
        setSettings(data);

        // Provider selections
        setTranslateProvider(data.providers?.translate?.current || "openai");
        setTtsProvider(data.providers?.tts?.current || "edge-tts");
        setAsrProvider(data.providers?.asr?.current || "whisper-api");
        setSeparateProvider(data.providers?.separate?.current || "ffmpeg-simple");
        setProvidersData(data.providers || null);

        // OpenAI translate overrides
        setBaseUrl(data.openai.baseUrl || DEFAULT_BASE_URL);
        setApiKey(data.openai.hasApiKey ? data.openai.apiKey || SAVED_API_KEY_MASK : "");
        setModel(data.openai.model || DEFAULT_MODEL);
        setTranslateConcurrency(data.openai.translateConcurrency || DEFAULT_CONCURRENCY);

        // Proxy
        setProxy(data.ytdlp.proxy || "");

        // Reset dirty flags
        setApiKeyDirty(false);
        setCookieDirty(false);
      } catch (err) {
        if (mounted) setError(err instanceof Error ? err.message : "Failed to load settings");
      } finally {
        if (mounted) setLoading(false);
      }
    })();
    return () => {
      mounted = false;
    };
  }, []);

  const handleGetModels = async () => {
    setLoadingModels(true);
    try {
      const response = await getOpenAIModels(baseUrl, apiKey === SAVED_API_KEY_MASK ? "" : apiKey);
      setModels(response.models);
    } catch (err) {
      setError(err instanceof Error ? err.message : t.settings.loadModelsError);
    } finally {
      setLoadingModels(false);
    }
  };

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    setSuccess(false);
    try {
      const request: SettingsRequest = {
        openai: {
          baseUrl,
          ...(apiKeyDirty
            ? { apiKey, clearApiKey: !apiKey }
            : { apiKey: "" }),
          model,
          translateConcurrency,
        },
        ytdlp: {
          proxy,
        },
        providers: {
          translate: translateProvider,
          tts: ttsProvider,
          asr: asrProvider,
          separate: separateProvider,
        },
      };
      if (cookieDirty) {
        request.youtubeCookie = { content: cookieContent };
      }
      const data = await saveSettings(request);
      setSettings(data);
      setProvidersData(data.providers || null);
      setApiKey(data.openai.hasApiKey ? data.openai.apiKey || SAVED_API_KEY_MASK : "");
      setCookieContent("");
      setApiKeyDirty(false);
      setCookieDirty(false);
      setSuccess(true);
      window.setTimeout(() => setSuccess(false), 3000);
    } catch (err) {
      setError(err instanceof Error ? err.message : t.settings.saveError);
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="min-h-screen bg-background">
        <AppHeader />
        <main className="mx-auto max-w-4xl px-4 py-6">
          <div className="flex items-center gap-2 text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            {t.common.loading}
          </div>
        </main>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-background">
      <AppHeader />
      <main className="mx-auto max-w-4xl space-y-6 px-4 py-6">
        <Link to="/" className={buttonVariants({ variant: "ghost", size: "sm" })}>
          <ArrowLeft className="h-4 w-4" />
          {t.common.back}
        </Link>

        <div className="space-y-1">
          <h1 className="text-2xl font-bold tracking-tight">{t.settings.title}</h1>
          <p className="text-sm text-muted-foreground">{t.settings.description}</p>
        </div>

        {/* Translate provider */}
        <ProviderCard
          sectionKey="translate"
          providersData={providersData}
          current={translateProvider}
          onProviderChange={setTranslateProvider}
          t={t.settings}
        >
          {translateProvider === "openai" && (
            <div className="space-y-3 border-t pt-2">
              <p className="text-xs font-medium text-muted-foreground">
                Runtime overrides:
              </p>

              <div className="space-y-1.5">
                <Label htmlFor="base-url">{t.settings.baseUrl}</Label>
                <Input
                  id="base-url"
                  value={baseUrl}
                  onChange={(e) => setBaseUrl(e.target.value)}
                  placeholder={DEFAULT_BASE_URL}
                />
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="api-key">{t.settings.apiKey}</Label>
                <Input
                  id="api-key"
                  type="password"
                  value={apiKey}
                  onFocus={(e) => {
                    if (!apiKeyDirty && apiKey === SAVED_API_KEY_MASK) {
                      e.currentTarget.select();
                    }
                  }}
                  onChange={(e) => {
                    setApiKeyDirty(true);
                    setApiKey(e.target.value.replace(SAVED_API_KEY_MASK, ""));
                  }}
                  placeholder={t.settings.apiKeyPlaceholder}
                />
                {settings?.openai.hasApiKey && !apiKeyDirty && apiKey === SAVED_API_KEY_MASK && (
                  <p className="text-xs text-muted-foreground">{t.settings.keySaved}</p>
                )}
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="model">{t.settings.model}</Label>
                <div className="flex gap-2">
                  {models.length > 0 ? (
                    <Select value={model} onChange={(e) => setModel(e.target.value)}>
                      {models.map((m) => (
                        <option key={m} value={m}>
                          {m}
                        </option>
                      ))}
                    </Select>
                  ) : (
                    <Input
                      id="model"
                      value={model}
                      onChange={(e) => setModel(e.target.value)}
                      placeholder={DEFAULT_MODEL}
                    />
                  )}
                  <Button
                    type="button"
                    variant="outline"
                    onClick={handleGetModels}
                    disabled={loadingModels || !baseUrl}
                  >
                    {loadingModels ? (
                      <Loader2 className="h-4 w-4 animate-spin" />
                    ) : (
                      <RefreshCw className="h-4 w-4" />
                    )}
                    {t.settings.getModels}
                  </Button>
                </div>
                {models.length > 0 && (
                  <p className="text-xs text-muted-foreground">
                    {models.length} {t.settings.modelsLoaded}
                  </p>
                )}
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="concurrency">{t.settings.translateConcurrency}</Label>
                <Input
                  id="concurrency"
                  type="number"
                  min="1"
                  max="200"
                  value={translateConcurrency}
                  onChange={(e) => setTranslateConcurrency(e.target.value)}
                />
                <p className="text-xs text-muted-foreground">{t.settings.concurrencyHelp}</p>
              </div>
            </div>
          )}
        </ProviderCard>

        {/* TTS provider */}
        <ProviderCard
          sectionKey="tts"
          providersData={providersData}
          current={ttsProvider}
          onProviderChange={setTtsProvider}
          t={t.settings}
        />

        {/* ASR provider */}
        <ProviderCard
          sectionKey="asr"
          providersData={providersData}
          current={asrProvider}
          onProviderChange={setAsrProvider}
          t={t.settings}
        />

        {/* Separate provider */}
        <ProviderCard
          sectionKey="separate"
          providersData={providersData}
          current={separateProvider}
          onProviderChange={setSeparateProvider}
          t={t.settings}
        />

        {/* yt-dlp Proxy */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{t.settings.ytdlpSection}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="space-y-1.5">
              <Label htmlFor="proxy">{t.settings.proxy}</Label>
              <Input
                id="proxy"
                value={proxy}
                onChange={(e) => setProxy(e.target.value)}
                placeholder="http://127.0.0.1:7890"
              />
              <p className="text-xs text-muted-foreground">{t.settings.proxyHelp}</p>
            </div>
          </CardContent>
        </Card>

        {/* YouTube Cookie */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{t.settings.cookieSection}</CardTitle>
            {settings && (
              <CardDescription>
                <div className="flex flex-wrap items-center gap-3 text-xs">
                  <Badge variant={settings.youtubeCookie.exists ? "success" : "secondary"}>
                    {t.settings.cookieExists}: {settings.youtubeCookie.exists ? "✓" : "✗"}
                  </Badge>
                  {settings.youtubeCookie.exists && (
                    <>
                      <span>
                        {t.settings.cookieSize}: {formatFileSize(settings.youtubeCookie.size)}
                      </span>
                      <span>
                        {t.settings.cookieUpdatedAt}:{" "}
                        {formatDateTime(
                          settings.youtubeCookie.updatedAt
                            ? new Date(settings.youtubeCookie.updatedAt).toISOString()
                            : null
                        )}
                      </span>
                    </>
                  )}
                </div>
              </CardDescription>
            )}
          </CardHeader>
          <CardContent className="space-y-2">
            <Label htmlFor="cookie-content">{t.settings.cookieContent}</Label>
            <Textarea
              id="cookie-content"
              value={cookieContent}
              onChange={(e) => {
                setCookieDirty(true);
                setCookieContent(e.target.value);
              }}
              placeholder={t.settings.cookiePlaceholder}
              className="min-h-[160px] font-mono text-xs"
            />
          </CardContent>
        </Card>

        {/* Save bar */}
        <div className="sticky bottom-4 flex items-center justify-end gap-3 rounded-lg border bg-background/95 p-4 shadow-lg backdrop-blur">
          {error && <span className="text-sm text-destructive">{error}</span>}
          {success && (
            <span className="flex items-center gap-1 text-sm text-success">
              <CheckCircle2 className="h-4 w-4" />
              {t.settings.saved}
            </span>
          )}
          <Button onClick={handleSave} disabled={saving}>
            {saving ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Save className="h-4 w-4" />
            )}
            {saving ? t.settings.saving : t.settings.save}
          </Button>
        </div>
      </main>
    </div>
  );
}
