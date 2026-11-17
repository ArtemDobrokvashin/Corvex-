import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ArrowRight, Check, Loader2, MailPlus, Pause, Play, ShieldCheck, TriangleAlert, X,
} from 'lucide-react';
import { ApiError } from '../../api/httpClient';
import {
  formatBytes, migrationApi,
  type MigrationJob, type MigrationPreview,
} from '../../api/migrationApi';
import {
  externalMailApi, type ExternalMailOAuthProvider, type ExternalMailOAuthProviderId,
  type ExternalMailProvider, type ExternalMailSecurity,
} from '../../api/externalMailApi';
import { IconButton, Notice } from '../../components/ui';
import { useModalFocus } from '../../components/useModalFocus';
import { useI18n } from '../../i18n';

type Step = 'source' | 'access' | 'scope' | 'running';

const PROVIDERS: { id: ExternalMailProvider; title: string; hint: string }[] = [
  { id: 'MAIL_RU', title: 'Mail.ru', hint: 'Пароль приложения при включённой двухфакторной защите' },
  { id: 'YANDEX', title: 'Яндекс 360', hint: 'Отдельный пароль для почтовых программ' },
  { id: 'GMAIL', title: 'Gmail', hint: 'Вход через Google или пароль приложения при 2FA' },
  { id: 'OUTLOOK', title: 'Microsoft 365', hint: 'Только вход через Microsoft: пароль приложения не работает' },
  { id: 'CUSTOM', title: 'Хостинг или свой сервер', hint: 'Адрес IMAP-сервера, порт и пароль ящика' },
];

const FINAL: MigrationJob['status'][] = ['DONE', 'PARTIAL', 'FAILED', 'CANCELLED'];

function errorText(error: unknown, fallback: string): string {
  if (error instanceof ApiError) return error.detail || fallback;
  return error instanceof Error ? error.message : fallback;
}

/**
 * Мастер переноса почвы после первого входа.
 *
 * Шаги: откуда → доступ → что переносим → запуск. Изменение источника сохраняется как
 * обычное внешнее подключение, поэтому новые письма начинают собираться сразу, даже если
 * архив решили перенести позже. Задание выполняется на сервере и переживает закрытие окна.
 */
export default function MigrationWizard({
  existingAccount, suggestedHost, suggestedLogin, onClose, onDismiss, onNotNeeded, onOpenContacts,
  oauthReturn, oauthError,
}: {
  existingAccount?: { id: string; emailAddress: string } | null;
  suggestedHost?: string | null;
  suggestedLogin?: string | null;
  onClose: () => void;
  onDismiss: () => void;
  onNotNeeded: () => void;
  onOpenContacts?: () => void;
  /** Возврат из Google/Microsoft: согласие уже получено, осталось создать ящик. */
  oauthReturn?: { id: string; provider: ExternalMailOAuthProviderId; email: string } | null;
  /** Причина отказа провайдера, переданная в адресе возврата. */
  oauthError?: string | null;
}) {
  const { t } = useI18n();
  const dialogRef = useRef<HTMLElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  useModalFocus(dialogRef, headingRef);

  const [step, setStep] = useState<Step>('source');
  const [provider, setProvider] = useState<ExternalMailProvider>('MAIL_RU');
  const [email, setEmail] = useState('');
  const [secret, setSecret] = useState('');
  const [advanced, setAdvanced] = useState(Boolean(suggestedHost));
  const [host, setHost] = useState(suggestedHost ?? '');
  const [port, setPort] = useState('993');
  const [security, setSecurity] = useState<ExternalMailSecurity>('SSL_TLS');
  const [accountId, setAccountId] = useState<string | null>(existingAccount?.id ?? null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [preview, setPreview] = useState<MigrationPreview | null>(null);
  const [include, setInclude] = useState<Set<string>>(new Set());
  const [includeAllMail, setIncludeAllMail] = useState(false);
  const [job, setJob] = useState<MigrationJob | null>(null);
  const [oauthProviders, setOauthProviders] = useState<ExternalMailOAuthProvider[]>([]);
  const [oauthConnection, setOauthConnection] = useState<{ id: string; provider: ExternalMailOAuthProviderId; email: string } | null>(null);

  useEffect(() => {
    // Провайдеры OAuth показываются только если администратор их настроил.
    void externalMailApi.oauthProviders().then(setOauthProviders).catch(() => setOauthProviders([]));
  }, []);

  useEffect(() => {
    if (!oauthReturn) return;
    setProvider(oauthReturn.provider === 'GOOGLE' ? 'GMAIL' : 'OUTLOOK');
    setEmail(oauthReturn.email);
    setOauthConnection(oauthReturn);
    setStep('access');
  }, [oauthReturn]);

  useEffect(() => {
    if (oauthError) setError(oauthError);
  }, [oauthError]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  const loadPreview = useCallback(async (id: string, skip: string[] = [], allMail = false) => {
    setBusy(true);
    setError('');
    try {
      const result = await migrationApi.preview({ accountId: id, skipFolders: skip, includeAllMail: allMail });
      setPreview(result);
      setInclude(new Set(result.folders.map((folder) => folder.remoteFolder)));
      setStep('scope');
    } catch (previewError) {
      setError(errorText(previewError, t('Не удалось оценить объём архива')));
    } finally {
      setBusy(false);
    }
  }, [t]);

  useEffect(() => {
    if (!job || FINAL.includes(job.status)) return;
    const timer = window.setInterval(() => {
      void migrationApi.get(job.id).then(setJob).catch(() => undefined);
    }, 4000);
    return () => window.clearInterval(timer);
  }, [job]);

  const connect = async () => {
    setBusy(true);
    setError('');
    try {
      const account = await externalMailApi.create({
        ownerKind: 'PERSONAL',
        label: email.trim(),
        emailAddress: email.trim(),
        provider,
        authType: oauthConnection ? 'OAUTH2' : 'APP_PASSWORD',
        incomingProtocol: 'IMAP',
        incomingUsername: email.trim(),
        ...(oauthConnection ? { oauthConnectionId: oauthConnection.id } : { secret }),
        ...(provider === 'CUSTOM'
          ? { incomingHost: host.trim(), incomingPort: Number(port || 993), incomingSecurity: security }
          : {}),
        smtpEnabled: false,
        enabled: true,
        syncEnabled: true,
        sendEnabled: false,
        initialSyncMode: 'NEW_ONLY',
        syncScope: 'ALL_FOLDERS',
        testBeforeSave: true,
      });
      setAccountId(account.id);
      await loadPreview(account.id, [], includeAllMail);
    } catch (createError) {
      setError(errorText(createError, t('Не удалось подключить ящик')));
    } finally {
      setBusy(false);
    }
  };

  const oauthProviderId: ExternalMailOAuthProviderId | null =
    provider === 'GMAIL' ? 'GOOGLE' : provider === 'OUTLOOK' ? 'MICROSOFT' : null;
  const oauthProvider = oauthProviderId
    ? oauthProviders.find((item) => item.provider === oauthProviderId)
    : undefined;

  const startOAuth = async () => {
    if (!oauthProviderId) return;
    setBusy(true);
    setError('');
    try {
      const response = await externalMailApi.oauthAuthorize({
        provider: oauthProviderId,
        returnPath: window.location.pathname,
      });
      // Уходим на страницу согласия провайдера; возврат придёт на callback и обратно в почту.
      window.location.assign(response.authorizationUrl);
    } catch (oauthStartError) {
      setError(errorText(oauthStartError, t('Не удалось начать вход через провайдера')));
      setBusy(false);
    }
  };

  const start = async () => {
    if (!accountId || !preview) return;
    setBusy(true);
    setError('');
    try {
      const skipFolders = preview.folders
        .map((folder) => folder.remoteFolder)
        .filter((folder) => !include.has(folder));
      const created = await migrationApi.create({ accountId, includeAllMail, skipFolders });
      setJob(created);
      setStep('running');
    } catch (startError) {
      setError(errorText(startError, t('Не удалось создать задание переноса')));
    } finally {
      setBusy(false);
    }
  };

  const action = async (kind: 'pause' | 'resume' | 'cancel') => {
    if (!job) return;
    setBusy(true);
    try {
      setJob(await migrationApi[kind](job.id));
    } catch (actionError) {
      setError(errorText(actionError, t('Действие не выполнено')));
    } finally {
      setBusy(false);
    }
  };

  const toggleFolder = (folder: string) => {
    setInclude((current) => {
      const next = new Set(current);
      if (next.has(folder)) next.delete(folder); else next.add(folder);
      return next;
    });
  };

  const selectedFolders = preview?.folders.filter((folder) => include.has(folder.remoteFolder)) ?? [];
  const selectedMessages = selectedFolders.reduce((sum, folder) => sum + folder.messageCount, 0);
  const selectedBytes = selectedFolders.reduce((sum, folder) => sum + folder.estimatedBytes, 0);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 p-3 backdrop-blur-[3px]">
      <section
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="migration-title"
        className="flex max-h-[92vh] w-full max-w-[820px] animate-scale-in flex-col overflow-hidden rounded-[20px] border border-line bg-surface shadow-pop"
      >
        <header className="flex shrink-0 items-start justify-between gap-4 border-b border-line px-5 py-4 sm:px-6">
          <div className="min-w-0">
            <h2 id="migration-title" ref={headingRef} tabIndex={-1} className="flex items-center gap-2 text-[19px] font-semibold tracking-[-0.02em] outline-none">
              <MailPlus size={18} className="text-accent" /> {t('Перенести почту')}
            </h2>
            <p className="mt-1 text-[13px] leading-relaxed text-muted">
              {t('Скопируем письма и папки из старого ящика. Старый ящик продолжит работать — мы ничего не удаляем.')}
            </p>
          </div>
          <IconButton icon={X} label={t('Закрыть')} onClick={onClose} />
        </header>

        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4 sm:px-6">
          {error && <Notice tone="error" className="mb-3">{error}</Notice>}

          {step === 'source' && (
            <div>
              {existingAccount && (
                <div className="mb-4 flex flex-wrap items-center gap-3 rounded-[14px] border border-line bg-canvas px-4 py-3">
                  <span className="min-w-0 flex-1 text-[13px] text-ink">
                    {t('Уже подключён ящик {email}.', { email: existingAccount.emailAddress })}
                  </span>
                  <button
                    type="button"
                    onClick={() => void loadPreview(existingAccount.id, [], includeAllMail)}
                    disabled={busy}
                    className="btn-secondary h-8 px-3 text-[12.5px]"
                  >
                    {t('Продолжить с ним')}
                  </button>
                </div>
              )}
              <p className="mb-2 text-[12.5px] font-medium text-ink">{t('Откуда переносим?')}</p>
              <div className="grid gap-2 sm:grid-cols-2">
                {PROVIDERS.map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => { setProvider(item.id); setStep('access'); }}
                    className="rounded-[14px] border border-line bg-canvas px-4 py-3 text-left transition hover:border-accent hover:bg-accent/5"
                  >
                    <span className="block text-[13.5px] font-medium text-ink">{t(item.title)}</span>
                    <span className="mt-0.5 block text-[11.5px] leading-relaxed text-muted">{t(item.hint)}</span>
                  </button>
                ))}
              </div>
              <p className="mt-3 text-[11.5px] leading-relaxed text-faint">
                {t('Microsoft 365 подключается только после входа через Microsoft, Gmail — через Google или паролем приложения. Кнопка входа появляется, когда администратор настроил приложение.')}
              </p>
            </div>
          )}

          {step === 'access' && (
            <div className="max-w-[520px]">
              <button type="button" onClick={() => setStep('source')} className="mb-3 text-[12px] text-accent hover:underline">
                {t('← Выбрать другой источник')}
              </button>
              <label className="block text-[12px] text-muted">
                {t('Адрес старого ящика')}
                <input
                  type="email"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  className="field mt-1 h-9"
                  placeholder="ivan@old-company.ru"
                  autoComplete="username"
                />
              </label>
              {oauthProvider && (
                <div className="mt-3 rounded-[12px] border border-line bg-surface-soft/50 p-3">
                  {oauthConnection ? (
                    <p className="text-[12.5px] text-ink">
                      {t('Вход через {title} выполнен: {email}', {
                        title: oauthProvider.title,
                        email: oauthConnection.email,
                      })}
                    </p>
                  ) : (
                    <>
                      <p className="text-[12px] leading-relaxed text-ink-soft">
                        {t('{title} требует вход через браузер: пароль приложения не подойдёт.', {
                          title: oauthProvider.title,
                        })}
                      </p>
                      <button
                        type="button"
                        onClick={() => void startOAuth()}
                        disabled={busy || !oauthProvider.enabled}
                        className="btn-secondary mt-2 h-9 px-3 text-[13px]"
                        title={oauthProvider.enabled ? undefined : oauthProvider.hint}
                      >
                        <ShieldCheck size={14} />
                        {t('Войти через {title}', { title: oauthProvider.title })}
                      </button>
                      {!oauthProvider.enabled && (
                        <p className="mt-1.5 text-[11.5px] leading-relaxed text-faint">{oauthProvider.hint}</p>
                      )}
                    </>
                  )}
                </div>
              )}
              {!oauthConnection && (
                <label className="mt-3 block text-[12px] text-muted">
                  {oauthProvider ? t('Или пароль приложения') : t('Пароль приложения')}
                  <input
                    type="password"
                    value={secret}
                    onChange={(event) => setSecret(event.target.value)}
                    className="field mt-1 h-9"
                    placeholder="••••••••"
                    autoComplete="current-password"
                  />
                </label>
              )}
              {suggestedHost && (
                <p className="mt-1.5 text-[11.5px] leading-relaxed text-ink-soft">
                  {t('Администратор указал источник: {host}{login}.', {
                    host: suggestedHost,
                    login: suggestedLogin ? `, ${t('логин {login}', { login: suggestedLogin })}` : '',
                  })}
                </p>
              )}
              <p className="mt-1.5 text-[11.5px] leading-relaxed text-faint">
                {t('Обычный пароль часто не подходит: Mail.ru, Яндекс и Gmail требуют отдельный пароль для почтовых программ. Пароль шифруется и недоступен администратору.')}
              </p>

              <button type="button" onClick={() => setAdvanced((value) => !value)} className="mt-3 text-[12px] text-accent hover:underline">
                {advanced ? t('Скрыть сервер и порт') : t('Указать сервер и порт вручную')}
              </button>
              {advanced && (
                <div className="mt-2 grid gap-2 sm:grid-cols-3">
                  <label className="block text-[12px] text-muted sm:col-span-2">
                    {t('Сервер IMAP')}
                    <input value={host} onChange={(event) => setHost(event.target.value)} className="field mt-1 h-9" placeholder="imap.mail.ru" />
                  </label>
                  <label className="block text-[12px] text-muted">
                    {t('Порт')}
                    <input value={port} onChange={(event) => setPort(event.target.value)} className="field mt-1 h-9" inputMode="numeric" />
                  </label>
                  <label className="block text-[12px] text-muted sm:col-span-3">
                    {t('Шифрование')}
                    <select value={security} onChange={(event) => setSecurity(event.target.value as ExternalMailSecurity)} className="field mt-1 h-9">
                      <option value="SSL_TLS">SSL/TLS</option>
                      <option value="STARTTLS">STARTTLS</option>
                    </select>
                  </label>
                </div>
              )}

              <div className="mt-4 flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => void connect()}
                  disabled={busy || !email.trim() || (!oauthConnection && !secret)
                    || (provider === 'CUSTOM' && advanced && !host.trim())}
                  className="btn-accent h-9 px-3 text-[13px]"
                >
                  {busy ? <Loader2 size={14} className="animate-spin" /> : <ShieldCheck size={14} />}
                  {oauthConnection ? t('Продолжить настройку') : t('Проверить и продолжить')}
                </button>
                <span className="text-[11.5px] text-faint">{t('Подключение идёт с сервера, пароль не сохраняется в браузере')}</span>
              </div>
            </div>
          )}

          {step === 'scope' && preview && (
            <div>
              <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
                <p className="text-[12.5px] font-medium text-ink">{t('Что переносим')}</p>
                <p className="text-[12px] text-muted">
                  {t('{messages} писем · {size}', {
                    messages: selectedMessages,
                    size: formatBytes(selectedBytes),
                  })}
                  {preview.quotaBytes > 0 && ` · ${t('квота {size}', { size: formatBytes(preview.quotaBytes) })}`}
                </p>
              </div>

              {preview.warnings.map((warning) => (
                <Notice key={warning} tone="warning" className="mb-2">{warning}</Notice>
              ))}

              <div className="max-h-[260px] overflow-y-auto rounded-[14px] border border-line">
                {preview.folders.map((folder) => (
                  <label key={folder.remoteFolder} className="flex cursor-pointer items-start gap-3 border-b border-line px-3 py-2 last:border-b-0">
                    <input
                      type="checkbox"
                      checked={include.has(folder.remoteFolder)}
                      onChange={() => toggleFolder(folder.remoteFolder)}
                      className="mt-0.5"
                    />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[13px] text-ink">{folder.remoteFolder}</span>
                      <span className="block text-[11.5px] text-faint">
                        {t('{count} писем · {size}', {
                          count: folder.messageCount,
                          size: formatBytes(folder.estimatedBytes),
                        })}
                      </span>
                    </span>
                  </label>
                ))}
              </div>

              {provider === 'GMAIL' && (
                <label className="mt-3 flex items-start gap-2 text-[12px] text-muted">
                  <input type="checkbox" checked={includeAllMail} onChange={(event) => setIncludeAllMail(event.target.checked)} className="mt-0.5" />
                  <span>{t('Переносить All Mail, Important и Starred (иначе письма не задвоятся, но метки не сохранятся)')}</span>
                </label>
              )}

              <p className="mt-3 text-[11.5px] leading-relaxed text-faint">
                {t('Контакты в IMAP не хранятся. Их можно импортировать отдельно: vCard или CSV из старого сервиса.')}
                {onOpenContacts && (
                  <button type="button" onClick={onOpenContacts} className="ml-1 text-accent hover:underline">
                    {t('Открыть адресную книгу')}
                  </button>
                )}
              </p>

              <div className="mt-4 flex flex-wrap items-center gap-2">
                <button type="button" onClick={() => void start()} disabled={busy || selectedFolders.length === 0} className="btn-accent h-9 px-3 text-[13px]">
                  {busy ? <Loader2 size={14} className="animate-spin" /> : <ArrowRight size={14} />}
                  {t('Запустить перенос')}
                </button>
                <button type="button" onClick={() => setStep('access')} className="btn-secondary h-9 px-3 text-[13px]">{t('Назад')}</button>
                <span className="text-[11.5px] text-faint">{t('Задание выполняется на сервере: вкладку можно закрыть')}</span>
              </div>
            </div>
          )}

          {step === 'running' && job && (
            <div>
              <div className="flex flex-wrap items-center justify-between gap-2 rounded-[14px] border border-line bg-canvas px-4 py-3">
                <div>
                  <p className="text-[13px] font-medium text-ink">{t(statusText(job.status))}</p>
                  <p className="mt-0.5 text-[11.5px] text-muted">
                    {t('{migrated} из {total} писем · оценка {size}', {
                      migrated: job.migratedMessages,
                      total: job.estimatedMessages,
                      size: formatBytes(job.estimatedBytes),
                    })}
                  </p>
                </div>
                <div className="flex gap-1.5">
                  {job.status === 'RUNNING' && (
                    <button type="button" onClick={() => void action('pause')} disabled={busy} className="btn-secondary h-8 px-3 text-[12.5px]">
                      <Pause size={13} /> {t('Пауза')}
                    </button>
                  )}
                  {(job.status === 'PAUSED' || job.status === 'FAILED') && (
                    <button type="button" onClick={() => void action('resume')} disabled={busy} className="btn-secondary h-8 px-3 text-[12.5px]">
                      <Play size={13} /> {t('Продолжить')}
                    </button>
                  )}
                  {(job.status === 'RUNNING' || job.status === 'PAUSED' || job.status === 'QUEUED') && (
                    <button type="button" onClick={() => void action('cancel')} disabled={busy} className="btn-secondary h-8 px-3 text-[12.5px]">
                      {t('Отменить')}
                    </button>
                  )}
                </div>
              </div>

              {job.error && <Notice tone="error" className="mt-3">{job.error}</Notice>}
              {job.status === 'DONE' && (
                <Notice tone="success" className="mt-3">
                  {t('Перенос завершён. Новые письма из старого ящика продолжат приходить, пока подключение включено.')}
                </Notice>
              )}
              {job.status === 'PAUSED' && (
                <Notice tone="info" className="mt-3">
                  {t('Пауза. Продолжение начнётся с последнего перенесённого письма.')}
                </Notice>
              )}

              <ul className="mt-3 max-h-[240px] divide-y divide-line overflow-y-auto rounded-[14px] border border-line">
                {job.folders.map((folder) => (
                  <li key={folder.id} className="flex items-center gap-3 px-3 py-2">
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[13px] text-ink">{folder.remoteFolder}</span>
                      {folder.error && <span className="block truncate text-[11.5px] text-red-600">{folder.error}</span>}
                    </span>
                    <span className="shrink-0 text-[11.5px] text-muted">
                      {folder.migratedMessages} / {folder.estimatedMessages}
                    </span>
                    {folder.state === 'DONE' && <Check size={14} className="shrink-0 text-emerald-600" />}
                    {folder.state === 'FAILED' && <TriangleAlert size={14} className="shrink-0 text-amber-600" />}
                    {folder.state === 'RUNNING' && <Loader2 size={14} className="shrink-0 animate-spin text-accent" />}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>

        <footer className="flex shrink-0 flex-wrap items-center gap-2 border-t border-line px-5 py-3 sm:px-6">
          {step !== 'running' ? (
            <>
              <button type="button" onClick={onDismiss} className="btn-secondary h-8 px-3 text-[12.5px]">{t('Позже')}</button>
              <button type="button" onClick={onNotNeeded} className="btn-secondary h-8 px-3 text-[12.5px]">{t('Мне нечего переносить')}</button>
              <span className="ml-auto text-[11.5px] text-faint">
                {t('Отложенное предложение вернётся баннером в почте.')}
              </span>
            </>
          ) : (
            <button type="button" onClick={onClose} className="btn-secondary ml-auto h-8 px-3 text-[12.5px]">{t('Закрыть')}</button>
          )}
        </footer>
      </section>
    </div>
  );
}

function statusText(status: MigrationJob['status']): string {
  switch (status) {
    case 'QUEUED': return 'Задание в очереди';
    case 'RUNNING': return 'Перенос идёт';
    case 'PAUSED': return 'Перенос приостановлен';
    case 'DONE': return 'Перенос завершён';
    case 'PARTIAL': return 'Перенос завершён с замечаниями';
    case 'FAILED': return 'Перенос остановился с ошибкой';
    case 'CANCELLED': return 'Перенос отменён';
  }
}
