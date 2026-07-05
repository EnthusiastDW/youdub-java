import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { ArrowLeft, Save, Loader2, CheckCircle2 } from "lucide-react";
import { AppHeader } from "@/components/AppHeader";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Select } from "@/components/ui/select";
import { Badge } from "@/components/ui/badge";
import { useI18n } from "@/i18n/index";
import { getSettings, saveSettings, getEdgeTtsVoices } from "@/api/client";
import { formatDateTime, formatFileSize } from "@/lib/utils";
import type { ProvidersData, Settings, SettingsRequest } from "@/types";

type SectionKey = "translate" | "tts" | "asr" | "separate";

type FieldType = "text" | "password" | "number" | "select";

interface FieldDef {
  key: string;
  type: FieldType;
  i18nKey?: string;
}

const PROVIDER_FIELDS: Record<string, Record<string, FieldDef[]>> = {
  asr: {
    "whisper-api": [
      { key: "baseUrl", type: "text" },
      { key: "url", type: "text" },
      { key: "apiKey", type: "password" },
      { key: "model", type: "text" },
    ],
    "whisper-cpp": [
      { key: "model", type: "text" },
    ],
  },
  tts: {
    "edge-tts": [
      { key: "path", type: "text" },
      { key: "voice", type: "select" },
    ],
    "openai-tts": [
      { key: "url", type: "text" },
      { key: "apiKey", type: "password" },
      { key: "model", type: "text" },
      { key: "voice", type: "text" },
    ],
    voxcpm: [
      { key: "serviceUrl", type: "text" },
    ],
  },
  translate: {
    "ollama": [
      { key: "baseUrl", type: "text" },
      { key: "model", type: "text" },
      { key: "concurrency", type: "number" },
    ],
    openai: [
      { key: "chatUrl", type: "text" },
      { key: "apiKey", type: "password" },
      { key: "model", type: "text" },
      { key: "concurrency", type: "number" },
    ],
  },
  separate: {
    "ffmpeg-simple": [],
    demucs: [
      { key: "model", type: "text" },
    ],
    "audio-separator-api": [
      { key: "serviceUrl", type: "text" },
    ],
  },
};

/** Provider selection card with dropdown and dynamic editable fields. */
function ProviderCard({
  sectionKey,
  providersData,
  current,
  onProviderChange,
  configValues,
  onConfigChange,
  selectOptions,
  t,
}: {
  sectionKey: SectionKey;
  providersData: ProvidersData | null;
  current: string;
  onProviderChange: (value: string) => void;
  configValues: Record<string, string>;
  onConfigChange: (key: string, value: string) => void;
  selectOptions?: Record<string, string[]>;
  t: Record<string, string>;
}) {
  const fields = PROVIDER_FIELDS[sectionKey]?.[current] ?? [];
  const group = providersData?.[sectionKey];
  const options = group?.options?.[current];

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{t[`${sectionKey}Section`] || sectionKey}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {/* Provider Dropdown */}
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

        {/* Editable Fields */}
        {fields.length > 0 && (
          <div className="space-y-3">
            {fields.map((field) => {
              const configKey = `${sectionKey}.${current}.${field.key}`;
              const value = configValues[configKey] ?? options?.[field.key] ?? "";
              return (
                <div key={configKey} className="space-y-1.5">
                  <Label htmlFor={configKey} className="text-xs">
                    {t[field.key] || field.key}
                  </Label>
                  {field.type === "select" ? (
                    <div className="relative">
                      <select
                        id={configKey}
                        value={value}
                        onChange={(e) => onConfigChange(configKey, e.target.value)}
                        className="flex h-10 w-full items-center justify-between rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        {selectOptions?.[configKey]?.length ? (
                          selectOptions[configKey].map((opt) => (
                            <option key={opt} value={opt}>
                              {opt}
                            </option>
                          ))
                        ) : (
                          <option value={value}>{value}</option>
                        )}
                      </select>
                    </div>
                  ) : field.type === "number" ? (
                    <Input
                      id={configKey}
                      type="number"
                      value={value}
                      onChange={(e) => onConfigChange(configKey, e.target.value)}
                    />
                  ) : (
                    <Input
                      id={configKey}
                      type={field.type}
                      value={value}
                      onChange={(e) => onConfigChange(configKey, e.target.value)}
                    />
                  )}
                </div>
              );
            })}
          </div>
        )}
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
  const [ttsProvider, setTtsProvider] = useState("voxcpm");
  const [asrProvider, setAsrProvider] = useState("whisper-api");
  const [separateProvider, setSeparateProvider] = useState("audio-separator-api");

  // Edge TTS voice options
  const [edgeTtsVoices, setEdgeTtsVoices] = useState<string[]>([]);

  // Provider config values (key format: "step.provider.field")
  const [configValues, setConfigValues] = useState<Record<string, string>>({});
  // ref 始终指向最新 configValues，避免 handleSave 闭包拿到旧值
  const configValuesRef = useRef(configValues);
  configValuesRef.current = configValues;

  // Proxy (savable)
  const [proxy, setProxy] = useState("");

  // Cookie
  const [cookieContent, setCookieContent] = useState("");
  const [cookieDirty, setCookieDirty] = useState(false);

  useEffect(() => {
    let mounted = true;
    (async () => {
      try {
        const data = await getSettings();
        if (!mounted) return;
        setSettings(data);

        // Provider selections
        setTranslateProvider(data.providers?.translate?.current || "openai");
        setTtsProvider(data.providers?.tts?.current || "voxcpm");
        setAsrProvider(data.providers?.asr?.current || "whisper-api");
        setSeparateProvider(data.providers?.separate?.current || "audio-separator-api");
        setProvidersData(data.providers || null);

        // Populate config values from backend data
        const initialConfigs: Record<string, string> = {};
        if (data.providers) {
          for (const [step, group] of Object.entries(data.providers)) {
            for (const [provider, fields] of Object.entries(group.options)) {
              for (const [field, value] of Object.entries(fields as Record<string, string>)) {
                initialConfigs[`${step}.${provider}.${field}`] = value;
              }
            }
          }
        }
        setConfigValues(initialConfigs);

        // Proxy
        setProxy(data.ytdlp.proxy || "");

        // Reset dirty flags
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

  // Fetch edge-tts voices when the TTS provider is edge-tts
  useEffect(() => {
    if (ttsProvider !== "edge-tts") return;
    let mounted = true;
    (async () => {
      try {
        const data = await getEdgeTtsVoices();
        if (mounted) {
          setEdgeTtsVoices(data.voices);
        }
      } catch {
        // If edge-tts is not installed or fails, keep empty list so UI falls back gracefully
      }
    })();
    return () => { mounted = false; };
  }, [ttsProvider]);

  const handleConfigChange = (key: string, value: string) => {
    setConfigValues((prev) => ({ ...prev, [key]: value }));
  };

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    setSuccess(false);
    try {
      // Build providerConfigs for currently selected providers
      const providerConfigs: Record<string, string> = {};
      const steps: { step: string; provider: string }[] = [
        { step: "asr", provider: asrProvider },
        { step: "tts", provider: ttsProvider },
        { step: "translate", provider: translateProvider },
        { step: "separate", provider: separateProvider },
      ];
      for (const { step, provider } of steps) {
        const fields = PROVIDER_FIELDS[step]?.[provider] ?? [];
        for (const field of fields) {
          const key = `${step}.${provider}.${field.key}`; // format: "asr.whisper-api.baseUrl"
          // 使用 ref 读取最新值，避免闭包拿到旧渲染的 configValues
          const value = configValuesRef.current[key] ?? "";
          // 密码字段为空时不发送，避免覆盖已保存在 DB 中的 key
          if (field.type === "password" && value === "") continue;
          providerConfigs[key] = value;
        }
      }

      const request: SettingsRequest = {
        ytdlp: { proxy },
        providers: {
          translate: translateProvider,
          tts: ttsProvider,
          asr: asrProvider,
          separate: separateProvider,
        },
        providerConfigs:
          Object.keys(providerConfigs).length > 0 ? providerConfigs : undefined,
      };
      if (cookieDirty) {
        request.youtubeCookie = { content: cookieContent };
      }
      const data = await saveSettings(request);
      setSettings(data);
      setProvidersData(data.providers || null);
      setCookieContent("");
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
          configValues={configValues}
          onConfigChange={handleConfigChange}
          t={t.settings}
        />

        {/* TTS provider */}
        <ProviderCard
          sectionKey="tts"
          providersData={providersData}
          current={ttsProvider}
          onProviderChange={setTtsProvider}
          configValues={configValues}
          onConfigChange={handleConfigChange}
          selectOptions={{
            "tts.edge-tts.voice": edgeTtsVoices,
          }}
          t={t.settings}
        />

        {/* ASR provider */}
        <ProviderCard
          sectionKey="asr"
          providersData={providersData}
          current={asrProvider}
          onProviderChange={setAsrProvider}
          configValues={configValues}
          onConfigChange={handleConfigChange}
          t={t.settings}
        />

        {/* Separate provider */}
        <ProviderCard
          sectionKey="separate"
          providersData={providersData}
          current={separateProvider}
          onProviderChange={setSeparateProvider}
          configValues={configValues}
          onConfigChange={handleConfigChange}
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
                    {t.settings.cookieExists}: {settings.youtubeCookie.exists ? "\u2713" : "\u2717"}
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
