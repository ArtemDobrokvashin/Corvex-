import { FormEvent, useCallback, useEffect, useState } from 'react';
import { RefreshCw, Save, Settings2 } from 'lucide-react';
import { adminApi, type AdminSettings } from '../../api/adminApi';
import { errorText } from '../../api/authApi';
import { Notice } from '../../components/ui';
import { useI18n } from '../../i18n';

const DEFAULT_SETTINGS: AdminSettings = {
  'mail.maxMessageSizeMb': '25',
  'mail.defaultQuotaBytes': '5368709120',
  'mail.spamThreshold': '5.0',
  'mail.pop3.enabled': 'true',
  'security.passwordMinLength': '10',
  'security.requireTotp': 'false',
  'chat.maxFileSizeMb': '25',
};

type SettingDefinition = {
  key: string;
  section: 'Почта' | 'Безопасность' | 'Сообщения';
  label: string;
  description: string;
  kind: 'integer' | 'decimal' | 'boolean';
  min?: number;
  max?: number;
  step?: string;
  unit?: string;
};

const DEFINITIONS: SettingDefinition[] = [
  { key: 'mail.maxMessageSizeMb', section: 'Почта', label: 'Максимальный размер письма', description: 'Значение реестра в MiB, от 1 до 1024.', kind: 'integer', min: 1, max: 1024, unit: 'MiB' },
  { key: 'mail.defaultQuotaBytes', section: 'Почта', label: 'Квота нового ящика по умолчанию', description: 'Значение в bytes; 5368709120 = 5 ГиБ, 0 — без лимита.', kind: 'integer', min: 0, max: 1_000_000_000_000_000, unit: 'bytes' },
  { key: 'mail.spamThreshold', section: 'Почта', label: 'Порог спам-оценки', description: 'Десятичное значение реестра от −100 до 100.', kind: 'decimal', min: -100, max: 100, step: '0.1' },
  { key: 'mail.pop3.enabled', section: 'Почта', label: 'POP3', description: 'Сохранённый признак доступности POP3.', kind: 'boolean' },
  { key: 'security.passwordMinLength', section: 'Безопасность', label: 'Минимальная длина пароля', description: 'Значение реестра от 8 до 128 символов.', kind: 'integer', min: 8, max: 128, unit: 'символов' },
  { key: 'security.requireTotp', section: 'Безопасность', label: 'Обязательная 2FA', description: 'Сохранённый признак политики TOTP.', kind: 'boolean' },
  { key: 'chat.maxFileSizeMb', section: 'Сообщения', label: 'Максимальный размер файла', description: 'Значение реестра в MiB, от 1 до 1024.', kind: 'integer', min: 1, max: 1024, unit: 'MiB' },
];

export default function SettingsTab({ demoMode }: { demoMode: boolean }) {
  const { locale, t } = useI18n();
  const [settings, setSettings] = useState<AdminSettings | null>(demoMode ? DEFAULT_SETTINGS : null);
  const [draft, setDraft] = useState<AdminSettings>(demoMode ? DEFAULT_SETTINGS : {});
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);

  const loadSettings = useCallback(async () => {
    setLoading(true);
    setError('');
    setSaved(false);
    try {
      const result = demoMode ? DEFAULT_SETTINGS : await adminApi.settings();
      setSettings(result);
      setDraft(result);
    } catch (loadError) {
      setError(errorText(loadError, locale));
    } finally {
      setLoading(false);
    }
  }, [demoMode, locale]);

  useEffect(() => {
    void loadSettings();
  }, [loadSettings]);

  const changedKeys = settings
    ? DEFINITIONS.map((definition) => definition.key).filter((key) => draft[key] !== settings[key])
    : [];

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!settings || changedKeys.length === 0) return;
    setSaving(true);
    setError('');
    setSaved(false);
    const patch = Object.fromEntries(changedKeys.map((key) => [key, (draft[key] ?? '').trim()]));
    try {
      const result = demoMode ? { ...settings, ...patch } : await adminApi.patchSettings(patch);
      setSettings(result);
      setDraft(result);
      setSaved(true);
    } catch (saveError) {
      setError(errorText(saveError, locale));
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="panel animate-rise-in overflow-hidden" aria-labelledby="settings-registry-title">
      <header className="flex flex-wrap items-start gap-3 border-b border-line px-5 py-5 sm:px-6">
        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-accent-soft text-accent-dark"><Settings2 size={19} strokeWidth={1.8} /></span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h2 id="settings-registry-title" className="text-[20px] font-semibold tracking-[-0.025em]">{t('Реестр серверных настроек')}</h2>
            <span className="chip">{t('Не runtime-политика')}</span>
          </div>
          <p className="mt-1 max-w-3xl text-sm leading-relaxed text-muted">{t('Просмотр и сохранение существующих ключей server_settings через ADMIN_SERVER_CONFIG.')}</p>
        </div>
        <button type="button" className="btn-secondary" onClick={() => void loadSettings()} disabled={loading || saving}>
          <RefreshCw size={15} className={loading ? 'animate-spin' : ''} />{t('Обновить')}
        </button>
      </header>

      <div className="px-5 py-5 sm:px-6">
        <Notice tone="warning">
          {t('Сохранение записывает значение в БД и аудит, но сейчас работающие сервисы не читают эти ключи автоматически. Изменение реестра не меняет текущие лимиты, POP3, спам-фильтр, парольную политику или 2FA.')}
        </Notice>
        {error && <Notice tone="error" className="mt-4">{error}</Notice>}
        {saved && <Notice tone="success" className="mt-4">{t('Сохранено в реестре и записано в аудит. Runtime-конфигурация сервисов этим действием не изменена.')}</Notice>}
        {loading && !settings && <p className="mt-5 text-sm text-muted" role="status">{t('Загружаем настройки…')}</p>}

        {settings && (
          <form onSubmit={save} className="mt-6">
            <div className="space-y-7">
              {(['Почта', 'Безопасность', 'Сообщения'] as const).map((section) => (
                <fieldset key={section} disabled={saving || loading}>
                  <legend className="mb-3 text-sm font-semibold text-ink">{t(section)}</legend>
                  <div className="grid gap-4 lg:grid-cols-2">
                    {DEFINITIONS.filter((definition) => definition.section === section).map((definition) => (
                      <label key={definition.key} className="block rounded-xl border border-line p-4">
                        <span className="block text-sm font-medium text-ink-soft">{t(definition.label)}</span>
                        <span className="mt-0.5 block font-mono text-[10px] text-faint">{definition.key}</span>
                        <span className="mt-2 block text-xs leading-relaxed text-muted">{t(definition.description)}</span>
                        <span className="mt-3 flex items-center gap-2">
                          {definition.kind === 'boolean' ? (
                            <select
                              value={draft[definition.key] ?? 'false'}
                              onChange={(event) => { setDraft((current) => ({ ...current, [definition.key]: event.target.value })); setSaved(false); }}
                              className="field h-10 py-0"
                              aria-label={t(definition.label)}
                            >
                              <option value="true">{t('Включено')}</option>
                              <option value="false">{t('Выключено')}</option>
                            </select>
                          ) : (
                            <input
                              type="number"
                              required
                              inputMode={definition.kind === 'decimal' ? 'decimal' : 'numeric'}
                              min={definition.min}
                              max={definition.max}
                              step={definition.step ?? '1'}
                              value={draft[definition.key] ?? ''}
                              onChange={(event) => { setDraft((current) => ({ ...current, [definition.key]: event.target.value })); setSaved(false); }}
                              className="field h-10 py-0"
                              aria-label={t(definition.label)}
                            />
                          )}
                          {definition.unit && <span className="shrink-0 text-xs text-faint">{t(definition.unit)}</span>}
                        </span>
                      </label>
                    ))}
                  </div>
                </fieldset>
              ))}
            </div>
            <footer className="mt-6 flex flex-wrap items-center gap-3 border-t border-line pt-5">
              <span className="mr-auto text-xs leading-relaxed text-faint">{t('Изменённые ключи: {count}', { count: changedKeys.length })}</span>
              <button type="submit" className="btn-primary" disabled={saving || loading || changedKeys.length === 0}>
                <Save size={15} />{saving ? t('Сохраняем…') : t('Сохранить в реестр')}
              </button>
            </footer>
          </form>
        )}
      </div>
    </section>
  );
}
