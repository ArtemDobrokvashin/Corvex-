import { FormEvent, ReactNode, useCallback, useEffect, useState } from 'react';
import { BellRing, RefreshCw, Save, Send, ShieldCheck } from 'lucide-react';
import {
  adminApi,
  type AlertRuntimeStatus,
  type AlertSecretState,
  type AlertSettings,
  type AlertSettingsRequest,
} from '../../api/adminApi';
import { errorText } from '../../api/authApi';
import { Notice } from '../../components/ui';
import { useI18n } from '../../i18n';

const CATEGORY_LABELS: Record<string, { label: string; description: string }> = {
  ServiceDown: { label: 'Сервис недоступен', description: 'Падение Corvex service или инфраструктурной зависимости.' },
  HighHttp5xxRate: { label: 'Рост HTTP 5xx', description: 'Повышенная доля серверных ошибок в web-gateway.' },
  MailQueueBacklog: { label: 'Почтовая очередь переполнена', description: 'Накопление сообщений в inbound/outbound/DLQ.' },
  DiskSpaceLow: { label: 'Мало места на диске', description: 'Свободно менее 15% на Docker data root.' },
  JvmMemoryPressure: { label: 'Давление на память JVM', description: 'Heap сервиса занят более чем на 85%.' },
};

const DEMO_SETTINGS: AlertSettings = {
  revision: 1,
  updatedAt: '2026-11-04T10:00:00Z',
  source: 'DATABASE',
  repeatNormalMinutes: 240,
  repeatCriticalMinutes: 30,
  categories: Object.fromEntries(Object.keys(CATEGORY_LABELS).map((code) => [code, true])),
  internalEmailEnabled: true,
  internalRecipients: 'admin@company.local',
  internalFrom: 'corvex-alert@company.local',
  externalEmailEnabled: false,
  externalEmailRecipients: '',
  externalSmtpHost: '',
  externalSmtpPort: 587,
  externalSmtpTlsMode: 'starttls',
  externalSmtpUsername: '',
  externalSmtpPassword: { configured: false, source: 'NONE' },
  externalSmtpFrom: '',
  telegramEnabled: false,
  telegramChatId: '',
  telegramBotToken: { configured: false, source: 'NONE' },
  maxEnabled: false,
  maxChatId: '',
  maxUserId: '',
  maxBotToken: { configured: false, source: 'NONE' },
};

function formFromSettings(settings: AlertSettings): AlertSettingsRequest {
  return {
    repeatNormalMinutes: settings.repeatNormalMinutes,
    repeatCriticalMinutes: settings.repeatCriticalMinutes,
    categories: { ...settings.categories },
    internalEmailEnabled: settings.internalEmailEnabled,
    internalRecipients: settings.internalRecipients,
    internalFrom: settings.internalFrom,
    externalEmailEnabled: settings.externalEmailEnabled,
    externalEmailRecipients: settings.externalEmailRecipients,
    externalSmtpHost: settings.externalSmtpHost,
    externalSmtpPort: settings.externalSmtpPort,
    externalSmtpTlsMode: settings.externalSmtpTlsMode,
    externalSmtpUsername: settings.externalSmtpUsername,
    externalSmtpPassword: '',
    clearExternalSmtpPassword: false,
    externalSmtpFrom: settings.externalSmtpFrom,
    telegramEnabled: settings.telegramEnabled,
    telegramChatId: settings.telegramChatId,
    telegramBotToken: '',
    clearTelegramBotToken: false,
    maxEnabled: settings.maxEnabled,
    maxChatId: settings.maxChatId,
    maxUserId: settings.maxUserId,
    maxBotToken: '',
    clearMaxBotToken: false,
  };
}

function demoSecret(input: string, clear: boolean, previous: AlertSecretState): AlertSecretState {
  if (clear) return { configured: false, source: 'NONE' };
  return input ? { configured: true, source: 'UI' } : previous;
}

function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className="block text-sm font-medium text-ink-soft">{label}</span>
      {hint && <span className="mt-1 block text-xs leading-relaxed text-muted">{hint}</span>}
      <span className="mt-2 block">{children}</span>
    </label>
  );
}

function Toggle({ checked, onChange, label, description, name }: {
  checked: boolean;
  onChange: (value: boolean) => void;
  label: string;
  description?: string;
  name?: string;
}) {
  return (
    <label className="flex cursor-pointer items-start gap-3 rounded-xl border border-line p-3">
      <input type="checkbox" name={name} checked={checked} onChange={(event) => onChange(event.target.checked)} className="mt-1 h-4 w-4 accent-[#4f46e5]" aria-label={label} />
      <span>
        <span className="block text-sm font-medium text-ink-soft">{label}</span>
        {description && <span className="mt-0.5 block text-xs leading-relaxed text-muted">{description}</span>}
      </span>
    </label>
  );
}

function demoRuntime(revision: number): AlertRuntimeStatus {
  return {
    revision,
    consumers: ['notification-service', 'alert-relay'].map((consumer) => ({
      consumer: consumer as 'notification-service' | 'alert-relay',
      status: 'APPLIED', revision, source: 'ADMIN', refresh: 'SUCCESS', updatedAt: new Date().toISOString(),
    })),
  };
}

export default function AlertSettingsPanel({ demoMode }: { demoMode: boolean }) {
  const { locale, t } = useI18n();
  const [settings, setSettings] = useState<AlertSettings | null>(demoMode ? DEMO_SETTINGS : null);
  const [form, setForm] = useState<AlertSettingsRequest | null>(demoMode ? formFromSettings(DEMO_SETTINGS) : null);
  const [runtime, setRuntime] = useState<AlertRuntimeStatus | null>(demoMode ? demoRuntime(1) : null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [runtimeError, setRuntimeError] = useState('');
  const [notice, setNotice] = useState('');

  const loadRuntime = useCallback(async () => {
    if (demoMode) return;
    try {
      setRuntime(await adminApi.alertRuntimeStatus());
      setRuntimeError('');
    } catch (loadError) {
      setRuntimeError(errorText(loadError, locale));
    }
  }, [demoMode, locale]);

  const loadSettings = useCallback(async () => {
    if (demoMode) return;
    setLoading(true);
    setError('');
    try {
      const result = await adminApi.alertSettings();
      setSettings(result);
      setForm(formFromSettings(result));
    } catch (loadError) {
      setError(errorText(loadError, locale));
    } finally {
      setLoading(false);
    }
  }, [demoMode, locale]);

  useEffect(() => {
    void loadSettings();
    void loadRuntime();
  }, [loadRuntime, loadSettings]);

  useEffect(() => {
    if (demoMode) return undefined;
    const timer = window.setInterval(() => void loadRuntime(), 10_000);
    return () => window.clearInterval(timer);
  }, [demoMode, loadRuntime]);

  function patch(next: Partial<AlertSettingsRequest>) {
    setForm((current) => current ? { ...current, ...next } : current);
    setNotice('');
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!form || !settings) return;
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const result = demoMode ? {
        ...settings,
        ...form,
        revision: settings.revision + 1,
        updatedAt: new Date().toISOString(),
        source: 'DATABASE' as const,
        externalSmtpPassword: demoSecret(form.externalSmtpPassword, form.clearExternalSmtpPassword, settings.externalSmtpPassword),
        telegramBotToken: demoSecret(form.telegramBotToken, form.clearTelegramBotToken, settings.telegramBotToken),
        maxBotToken: demoSecret(form.maxBotToken, form.clearMaxBotToken, settings.maxBotToken),
      } : await adminApi.saveAlertSettings(form);
      setSettings(result);
      setForm(formFromSettings(result));
      setNotice(t('Настройки алертов сохранены: revision {revision}. Проверяем применение потребителями.', { revision: result.revision }));
      if (demoMode) setRuntime(demoRuntime(result.revision));
      else await loadRuntime();
    } catch (saveError) {
      setError(errorText(saveError, locale));
    } finally {
      setSaving(false);
    }
  }

  const secretHint = (state: AlertSecretState | undefined) => {
    if (!state?.configured) return t('Секрет будет записан один раз и не будет показан обратно.');
    return state.source === 'UI'
      ? t('Сохранённый UI-секрет скрыт. Оставьте поле пустым, чтобы не менять его.')
      : t('Используется секрет из .env. Оставьте поле пустым, чтобы не менять его.');
  };

  const clearSecret = (label: string, checked: boolean, onChange: (value: boolean) => void) => (
    <span className="mt-2 flex items-center gap-2 text-xs text-muted">
      <input type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)} className="h-4 w-4 accent-[#4f46e5]" aria-label={label} />
      <span>{label}</span>
    </span>
  );

  return (
    <section className="panel animate-rise-in overflow-hidden" aria-labelledby="alert-settings-title">
      <header className="flex flex-wrap items-start gap-3 border-b border-line px-5 py-5 sm:px-6">
        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-accent-soft text-accent-dark"><BellRing size={19} strokeWidth={1.8} /></span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h2 id="alert-settings-title" className="text-[20px] font-semibold tracking-[-0.025em]">{t('Алерты и доставка')}</h2>
            <span className="chip chip-accent">{t('Runtime')}</span>
            {settings && <span className="chip">revision {settings.revision}</span>}
          </div>
          <p className="mt-1 max-w-3xl text-sm leading-relaxed text-muted">{t('Куда отправлять сбои Corvex, какие категории включены и как часто повторять уведомления. Доступно только SUPER_ADMIN.')}</p>
        </div>
        <button type="button" className="btn-secondary" onClick={() => { void loadSettings(); void loadRuntime(); }} disabled={loading || saving || demoMode}>
          <RefreshCw size={15} className={loading ? 'animate-spin' : ''} />{t('Обновить')}
        </button>
      </header>

      <div className="px-5 py-5 sm:px-6">
        <Notice>
          {t('Alertmanager продолжает управлять grouping и silences. Новая policy применяется notification-service и alert-relay без перезапуска. Внешний relay хранит последний рабочий cache и не зависит от RabbitMQ или Corvex SMTP.')}
        </Notice>
        {settings?.source === 'ENVIRONMENT' && <Notice tone="warning" className="mt-4">{t('Сейчас действует конфигурация из .env. Первое сохранение создаст версионированную UI-конфигурацию; существующие env-секреты останутся fallback и не будут показаны.')}</Notice>}
        {error && <Notice tone="error" className="mt-4">{error}</Notice>}
        {notice && <Notice tone="success" className="mt-4">{notice}</Notice>}
        {loading && !form && <p className="mt-5 text-sm text-muted" role="status">{t('Загружаем настройки алертов…')}</p>}

        <div className="mt-4 rounded-xl border border-line bg-chrome p-4" aria-live="polite">
          <div className="flex flex-wrap items-center gap-2 text-xs font-medium text-ink-soft"><ShieldCheck size={15} className="text-accent" />{t('Фактическое применение')}</div>
          <div className="mt-3 grid gap-2 sm:grid-cols-2">
            {runtime?.consumers.map((consumer) => (
              <div key={consumer.consumer} className="rounded-lg border border-line bg-surface px-3 py-2 text-xs">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-mono text-ink-soft">{consumer.consumer}</span>
                  <span className={`chip ${consumer.status === 'APPLIED' ? 'chip-accent' : consumer.status === 'UNAVAILABLE' ? 'bg-red-50 text-red-700' : 'bg-amber-50 text-amber-800'}`}>{t(consumer.status)}</span>
                </div>
                <div className="mt-1 text-faint">revision {consumer.revision ?? '—'} · {consumer.source} · {consumer.refresh}</div>
              </div>
            )) ?? <p className="text-xs text-muted">{t('Проверяем runtime…')}</p>}
          </div>
          {runtimeError && <p className="mt-2 text-xs text-red-700">{t('Не удалось проверить runtime: {error}', { error: runtimeError })}</p>}
        </div>

        {form && settings && (
          <form onSubmit={save} className="mt-6 space-y-6">
            <fieldset disabled={saving || loading}>
              <legend className="mb-3 text-sm font-semibold text-ink">{t('Частота и категории')}</legend>
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label={t('Повтор обычного алерта, минуты')} hint={t('От 1 до 43200 минут. Новый инцидент не ждёт repeat-интервал; сначала действует grouping Alertmanager.')}>
                  <input type="number" name="repeatNormalMinutes" min={1} max={43200} required value={form.repeatNormalMinutes} onChange={(event) => patch({ repeatNormalMinutes: Number(event.target.value) })} className="field h-10" aria-label={t('Повтор обычного алерта, минуты')} />
                </Field>
                <Field label={t('Повтор critical, минуты')} hint={t('Отдельный, обычно более частый интервал для критических сбоев.')}>
                  <input type="number" name="repeatCriticalMinutes" min={1} max={43200} required value={form.repeatCriticalMinutes} onChange={event => patch({ repeatCriticalMinutes: Number(event.target.value) })} className="field h-10" aria-label={t('Повтор critical, минуты')} />
                </Field>
              </div>
              <div className="mt-4 grid gap-2 sm:grid-cols-2">
                {Object.entries(CATEGORY_LABELS).map(([code, category]) => (
                  <Toggle key={code} name={code} checked={form.categories[code] ?? true} onChange={(enabled) => patch({ categories: { ...form.categories, [code]: enabled } })} label={t(category.label)} description={t(category.description)} />
                ))}
              </div>
            </fieldset>

            <fieldset disabled={saving || loading}>
              <legend className="mb-3 text-sm font-semibold text-ink">{t('Внутренняя почта Corvex')}</legend>
              <Toggle checked={form.internalEmailEnabled} onChange={(internalEmailEnabled) => patch({ internalEmailEnabled })} label={t('Отправлять алерты внутри Corvex')} description={t('notification-service → RabbitMQ → SMTP. Для независимого резервного канала настройте также внешний relay.')} />
              {form.internalEmailEnabled && <div className="mt-4 grid gap-4 sm:grid-cols-2">
                <Field label={t('Получатели через запятую')}><input type="text" required value={form.internalRecipients} onChange={(event) => patch({ internalRecipients: event.target.value })} className="field h-10" /></Field>
                <Field label={t('Отправитель')}><input type="email" required value={form.internalFrom} onChange={(event) => patch({ internalFrom: event.target.value })} className="field h-10" /></Field>
              </div>}
            </fieldset>

            <details className="rounded-xl border border-line p-4" open={form.externalEmailEnabled}>
              <summary className="cursor-pointer text-sm font-semibold text-ink">{t('Внешний резервный SMTP')}</summary>
              <div className="mt-4">
                <Toggle checked={form.externalEmailEnabled} onChange={(externalEmailEnabled) => patch({ externalEmailEnabled })} label={t('Включить внешний SMTP')} description={t('Работает через alert-relay даже при недоступности RabbitMQ и почты Corvex. Только STARTTLS или SSL.') as string} />
                {form.externalEmailEnabled && <div className="mt-4 grid gap-4 sm:grid-cols-2">
                  <Field label={t('Получатели через запятую')}><input type="text" required value={form.externalEmailRecipients} onChange={(event) => patch({ externalEmailRecipients: event.target.value })} className="field h-10" /></Field>
                  <Field label={t('Отправитель')}><input type="email" required value={form.externalSmtpFrom} onChange={(event) => patch({ externalSmtpFrom: event.target.value })} className="field h-10" /></Field>
                  <Field label={t('SMTP host')}><input type="text" required value={form.externalSmtpHost} onChange={(event) => patch({ externalSmtpHost: event.target.value })} className="field h-10" /></Field>
                  <Field label={t('SMTP port')}><input type="number" min={1} max={65535} required value={form.externalSmtpPort} onChange={(event) => patch({ externalSmtpPort: Number(event.target.value) })} className="field h-10" /></Field>
                  <Field label={t('TLS mode')}><select value={form.externalSmtpTlsMode} onChange={(event) => patch({ externalSmtpTlsMode: event.target.value as 'starttls' | 'ssl' })} className="field h-10"><option value="starttls">STARTTLS</option><option value="ssl">SSL/TLS</option></select></Field>
                  <Field label={t('SMTP username')} hint={t('Оставьте пустым для SMTP без аутентификации.')}><input type="text" value={form.externalSmtpUsername} onChange={(event) => patch({ externalSmtpUsername: event.target.value })} className="field h-10" autoComplete="off" /></Field>
                  <Field label={t('SMTP password')} hint={secretHint(settings.externalSmtpPassword)}>
                    <input type="password" value={form.externalSmtpPassword} onChange={(event) => patch({ externalSmtpPassword: event.target.value, clearExternalSmtpPassword: false })} className="field h-10" autoComplete="new-password" aria-label={t('SMTP password')} />
                    {settings.externalSmtpPassword.source === 'UI' && clearSecret(t('Удалить UI-секрет и вернуться к .env'), form.clearExternalSmtpPassword, (clearExternalSmtpPassword) => patch({ clearExternalSmtpPassword, externalSmtpPassword: '' }))}
                  </Field>
                </div>}
              </div>
            </details>

            <details className="rounded-xl border border-line p-4" open={form.telegramEnabled}>
              <summary className="cursor-pointer text-sm font-semibold text-ink">Telegram</summary>
              <div className="mt-4">
                <Toggle checked={form.telegramEnabled} onChange={(telegramEnabled) => patch({ telegramEnabled })} label={t('Включить Telegram')} description={t('Токен передаётся только в Telegram API URL и не выводится в логи или public API.') as string} />
                {form.telegramEnabled && <div className="mt-4 grid gap-4 sm:grid-cols-2">
                  <Field label={t('Telegram chat ID')} hint={t('ID группы, канала или пользователя в строковом формате.')}><input type="text" required value={form.telegramChatId} onChange={(event) => patch({ telegramChatId: event.target.value })} className="field h-10" /></Field>
                  <Field label="Telegram bot token" hint={secretHint(settings.telegramBotToken)}>
                    <input type="password" value={form.telegramBotToken} onChange={(event) => patch({ telegramBotToken: event.target.value, clearTelegramBotToken: false })} className="field h-10" autoComplete="new-password" aria-label="Telegram bot token" />
                    {settings.telegramBotToken.source === 'UI' && clearSecret(t('Удалить UI-секрет и вернуться к .env'), form.clearTelegramBotToken, (clearTelegramBotToken) => patch({ clearTelegramBotToken, telegramBotToken: '' }))}
                  </Field>
                </div>}
              </div>
            </details>

            <details className="rounded-xl border border-line p-4" open={form.maxEnabled}>
              <summary className="cursor-pointer text-sm font-semibold text-ink">MAX</summary>
              <div className="mt-4">
                <Toggle checked={form.maxEnabled} onChange={(maxEnabled) => patch({ maxEnabled })} label={t('Включить MAX')} description={t('Укажите ровно один chat ID или user ID. Токен отправляется только в Authorization header.') as string} />
                {form.maxEnabled && <div className="mt-4 grid gap-4 sm:grid-cols-2">
                  <Field label={t('MAX chat ID')}><input type="text" value={form.maxChatId} onChange={(event) => patch({ maxChatId: event.target.value })} className="field h-10" /></Field>
                  <Field label={t('MAX user ID')}><input type="text" value={form.maxUserId} onChange={(event) => patch({ maxUserId: event.target.value })} className="field h-10" /></Field>
                  <Field label="MAX bot token" hint={secretHint(settings.maxBotToken)}>
                    <input type="password" value={form.maxBotToken} onChange={(event) => patch({ maxBotToken: event.target.value, clearMaxBotToken: false })} className="field h-10" autoComplete="new-password" aria-label="MAX bot token" />
                    {settings.maxBotToken.source === 'UI' && clearSecret(t('Удалить UI-секрет и вернуться к .env'), form.clearMaxBotToken, (clearMaxBotToken) => patch({ clearMaxBotToken, maxBotToken: '' }))}
                  </Field>
                </div>}
              </div>
            </details>

            <footer className="flex flex-wrap items-center gap-3 border-t border-line pt-5">
              <span className="mr-auto flex items-center gap-2 text-xs text-faint"><Send size={14} />{t('Сохранение меняет runtime policy; секреты в ответ не возвращаются.')}</span>
              <button type="submit" className="btn-primary" disabled={saving || loading}><Save size={15} />{saving ? t('Сохраняем…') : t('Применить настройки алертов')}</button>
            </footer>
          </form>
        )}
      </div>
    </section>
  );
}
