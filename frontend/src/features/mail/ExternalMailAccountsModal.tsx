import { FormEvent, ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertCircle,
  CheckCircle2,
  Download,
  KeyRound,
  Mail,
  Pause,
  Play,
  Plus,
  RefreshCw,
  Search,
  Send,
  Trash2,
  UserRound,
  X,
} from 'lucide-react';
import {
  externalMailApi,
  ExternalMailAccount,
  ExternalMailAccountRequest,
  ExternalMailOwner,
  ExternalMailPreset,
  ExternalMailProvider,
  ExternalMailSyncRun,
  ExternalMailTargetFolder,
  ExternalMailTestResult,
} from '../../api/externalMailApi';
import { formatDateTime, useI18n } from '../../i18n';

const FALLBACK_PRESETS: ExternalMailPreset[] = [
  {
    provider: 'MAIL_RU', imapHost: 'imap.mail.ru', imapPort: 993, pop3Host: 'pop.mail.ru', pop3Port: 995,
    smtpHost: 'smtp.mail.ru', smtpPort: 465, incomingSecurity: 'SSL_TLS', smtpSecurity: 'SSL_TLS',
    hint: 'Создайте пароль внешнего приложения Mail.ru; обычный пароль ящика не подходит.',
  },
  {
    provider: 'YANDEX', imapHost: 'imap.yandex.ru', imapPort: 993, pop3Host: 'pop.yandex.ru', pop3Port: 995,
    smtpHost: 'smtp.yandex.ru', smtpPort: 465, incomingSecurity: 'SSL_TLS', smtpSecurity: 'SSL_TLS',
    hint: 'Включите доступ по IMAP и создайте пароль приложения Яндекс ID для «Почты».',
  },
  {
    provider: 'GMAIL', imapHost: 'imap.gmail.com', imapPort: 993, pop3Host: 'pop.gmail.com', pop3Port: 995,
    smtpHost: 'smtp.gmail.com', smtpPort: 465, incomingSecurity: 'SSL_TLS', smtpSecurity: 'SSL_TLS',
    hint: 'Нужен App Password Google при включённой двухэтапной проверке либо политика Workspace.',
  },
  {
    provider: 'OUTLOOK', imapHost: 'outlook.office365.com', imapPort: 993, pop3Host: 'outlook.office365.com', pop3Port: 995,
    smtpHost: 'smtp-mail.outlook.com', smtpPort: 587, incomingSecurity: 'SSL_TLS', smtpSecurity: 'STARTTLS',
    hint: 'Microsoft часто требует Modern Auth/OAuth2; app-password доступен не для всех тенантов.',
  },
];

const PROVIDER_LABELS: Record<ExternalMailProvider, string> = {
  MAIL_RU: 'Mail.ru',
  YANDEX: 'Яндекс',
  GMAIL: 'Gmail',
  OUTLOOK: 'Outlook / Microsoft 365',
  CUSTOM: 'Другой сервер',
};

type FormState = {
  targetUserId: string;
  ownerKind: 'PERSONAL' | 'SHARED_MAILBOX';
  label: string;
  emailAddress: string;
  displayName: string;
  provider: ExternalMailProvider;
  authType: 'APP_PASSWORD' | 'PASSWORD';
  incomingProtocol: 'IMAP' | 'POP3';
  incomingHost: string;
  incomingPort: number;
  incomingSecurity: 'SSL_TLS' | 'STARTTLS' | 'PLAIN';
  incomingUsername: string;
  secret: string;
  smtpEnabled: boolean;
  smtpHost: string;
  smtpPort: number;
  smtpSecurity: 'SSL_TLS' | 'STARTTLS' | 'PLAIN';
  smtpUsername: string;
  targetFolderId: string;
  enabled: boolean;
  syncEnabled: boolean;
  sendEnabled: boolean;
  initialSyncMode: 'NEW_ONLY' | 'IMPORT_ALL';
  syncScope: 'INBOX_ONLY' | 'ALL_FOLDERS';
  pollIntervalMinutes: number;
  leaveOnServer: boolean;
  markSeenOnServer: boolean;
  deleteAfterFetch: boolean;
  saveSentOnServer: boolean;
  sentFolderName: string;
  testBeforeSave: boolean;
};

function presetFor(provider: ExternalMailProvider, presets: ExternalMailPreset[]): ExternalMailPreset | undefined {
  return presets.find((preset) => preset.provider === provider);
}

function withPreset(form: FormState, presets: ExternalMailPreset[]): FormState {
  const preset = presetFor(form.provider, presets);
  if (!preset) return form;
  const pop3 = form.incomingProtocol === 'POP3';
  return {
    ...form,
    incomingHost: pop3 ? preset.pop3Host : preset.imapHost,
    incomingPort: pop3 ? preset.pop3Port : preset.imapPort,
    incomingSecurity: preset.incomingSecurity,
    smtpHost: preset.smtpHost,
    smtpPort: preset.smtpPort,
    smtpSecurity: preset.smtpSecurity,
  };
}

function emptyForm(presets: ExternalMailPreset[], targetUserId = ''): FormState {
  return withPreset({
    targetUserId,
    ownerKind: 'PERSONAL',
    label: '',
    emailAddress: '',
    displayName: '',
    provider: 'MAIL_RU',
    authType: 'APP_PASSWORD',
    incomingProtocol: 'IMAP',
    incomingHost: 'imap.mail.ru',
    incomingPort: 993,
    incomingSecurity: 'SSL_TLS',
    incomingUsername: '',
    secret: '',
    smtpEnabled: true,
    smtpHost: 'smtp.mail.ru',
    smtpPort: 465,
    smtpSecurity: 'SSL_TLS',
    smtpUsername: '',
    targetFolderId: '',
    enabled: true,
    syncEnabled: true,
    sendEnabled: true,
    initialSyncMode: 'NEW_ONLY',
    syncScope: 'INBOX_ONLY',
    pollIntervalMinutes: 5,
    leaveOnServer: true,
    markSeenOnServer: false,
    deleteAfterFetch: false,
    saveSentOnServer: true,
    sentFolderName: '',
    testBeforeSave: true,
  }, presets);
}

function formFromAccount(account: ExternalMailAccount): FormState {
  return {
    targetUserId: account.ownerId,
    ownerKind: account.ownerKind,
    label: account.label,
    emailAddress: account.emailAddress,
    displayName: account.displayName ?? '',
    provider: account.provider,
    authType: account.authType === 'PASSWORD' ? 'PASSWORD' : 'APP_PASSWORD',
    incomingProtocol: account.incomingProtocol,
    incomingHost: account.incomingHost,
    incomingPort: account.incomingPort,
    incomingSecurity: account.incomingSecurity,
    incomingUsername: account.incomingUsername,
    secret: '',
    smtpEnabled: account.smtpEnabled,
    smtpHost: account.smtpHost ?? '',
    smtpPort: account.smtpPort ?? 465,
    smtpSecurity: account.smtpSecurity,
    smtpUsername: account.smtpUsername ?? '',
    targetFolderId: account.targetFolderId ?? '',
    enabled: account.enabled,
    syncEnabled: account.syncEnabled,
    sendEnabled: account.sendEnabled,
    initialSyncMode: account.initialSyncMode,
    syncScope: account.syncScope,
    pollIntervalMinutes: Math.max(1, Math.round(account.pollIntervalSeconds / 60)),
    leaveOnServer: account.leaveOnServer,
    markSeenOnServer: account.markSeenOnServer,
    deleteAfterFetch: account.deleteAfterFetch,
    saveSentOnServer: account.saveSentOnServer,
    sentFolderName: account.sentFolderName ?? '',
    testBeforeSave: false,
  };
}

function toRequest(form: FormState, selectedId: string | null): ExternalMailAccountRequest {
  return {
    existingAccountId: selectedId,
    targetUserId: form.targetUserId.trim() || null,
    ownerKind: form.ownerKind,
    label: form.label.trim() || form.emailAddress.trim(),
    emailAddress: form.emailAddress.trim(),
    displayName: form.displayName.trim() || null,
    provider: form.provider,
    authType: form.authType,
    incomingProtocol: form.incomingProtocol,
    incomingHost: form.incomingHost.trim(),
    incomingPort: Number(form.incomingPort),
    incomingSecurity: form.incomingSecurity,
    incomingUsername: form.incomingUsername.trim() || form.emailAddress.trim(),
    secret: form.secret,
    smtpEnabled: form.smtpEnabled,
    smtpHost: form.smtpHost.trim() || null,
    smtpPort: form.smtpEnabled ? Number(form.smtpPort) : null,
    smtpSecurity: form.smtpSecurity,
    smtpUsername: form.smtpUsername.trim() || null,
    targetFolderId: form.targetFolderId || null,
    enabled: form.enabled,
    syncEnabled: form.syncEnabled,
    sendEnabled: form.smtpEnabled && form.sendEnabled,
    initialSyncMode: form.initialSyncMode,
    syncScope: form.syncScope,
    pollIntervalSeconds: Math.max(1, Number(form.pollIntervalMinutes)) * 60,
    leaveOnServer: form.deleteAfterFetch ? false : form.leaveOnServer,
    markSeenOnServer: form.markSeenOnServer,
    deleteAfterFetch: form.deleteAfterFetch,
    saveSentOnServer: form.saveSentOnServer,
    sentFolderName: form.sentFolderName.trim() || null,
    testBeforeSave: form.testBeforeSave,
  };
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function ownerLabel(owner: ExternalMailOwner | null | undefined): string {
  if (!owner) return '';
  const name = owner.displayName?.trim() || owner.username?.trim() || owner.email;
  return owner.email && owner.email !== name ? `${name} · ${owner.email}` : name;
}

const inputCls = 'h-10 w-full rounded-xl border border-line bg-surface px-3 text-sm outline-none transition placeholder:text-faint focus:border-accent/60 focus:ring-2 focus:ring-accent/20 disabled:cursor-not-allowed disabled:bg-chrome disabled:text-muted';
const selectCls = inputCls;

function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) {
  return (
    <label className="block min-w-0">
      <span className="mb-1.5 block text-[13px] font-medium text-ink-soft">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-xs leading-relaxed text-faint">{hint}</span>}
    </label>
  );
}

function Check({ checked, onChange, children, disabled }: {
  checked: boolean;
  onChange: (value: boolean) => void;
  children: ReactNode;
  disabled?: boolean;
}) {
  return (
    <label className={`flex cursor-pointer items-start gap-2.5 text-sm text-ink-soft ${disabled ? 'cursor-not-allowed opacity-50' : ''}`}>
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(event) => onChange(event.target.checked)}
        className="mt-0.5 h-4 w-4 shrink-0 accent-[#4f46e5]"
      />
      <span>{children}</span>
    </label>
  );
}

export default function ExternalMailAccountsModal({
  demoMode,
  canAdmin,
  currentOwner,
  onClose,
  onAccountsChanged,
}: {
  demoMode: boolean;
  canAdmin: boolean;
  currentOwner: ExternalMailOwner | null;
  onClose: () => void;
  onAccountsChanged?: (accounts: ExternalMailAccount[]) => void;
}) {
  const { locale, t } = useI18n();
  const [presets, setPresets] = useState<ExternalMailPreset[]>(FALLBACK_PRESETS);
  const [accounts, setAccounts] = useState<ExternalMailAccount[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [ownerFilter, setOwnerFilter] = useState<ExternalMailOwner | null>(currentOwner);
  const [owners, setOwners] = useState<ExternalMailOwner[]>(currentOwner ? [currentOwner] : []);
  const [ownerQuery, setOwnerQuery] = useState('');
  const [ownersLoading, setOwnersLoading] = useState(false);
  const [ownerError, setOwnerError] = useState<string | null>(null);
  const [ownerFolders, setOwnerFolders] = useState<ExternalMailTargetFolder[]>([]);
  const [foldersLoading, setFoldersLoading] = useState(false);
  const [foldersError, setFoldersError] = useState<string | null>(null);
  const [form, setForm] = useState<FormState>(() => emptyForm(FALLBACK_PRESETS, currentOwner?.id ?? ''));
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<ExternalMailTestResult | null>(null);
  const [runs, setRuns] = useState<ExternalMailSyncRun[]>([]);
  const mountedRef = useRef(true);
  const loadRequestRef = useRef(0);
  const foldersRequestRef = useRef(0);

  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  const selected = useMemo(
    () => accounts.find((account) => account.id === selectedId) ?? null,
    [accounts, selectedId],
  );

  useEffect(() => {
    if (!ownerFilter && currentOwner) {
      setOwnerFilter(currentOwner);
      setForm((current) => (current.targetUserId ? current : { ...current, targetUserId: currentOwner.id }));
    }
  }, [currentOwner, ownerFilter]);

  const uniqueOwners = useMemo(() => {
    const byId = new Map<string, ExternalMailOwner>();
    for (const owner of [currentOwner, ownerFilter, ...owners]) {
      if (owner) byId.set(owner.id, owner);
    }
    return [...byId.values()];
  }, [currentOwner, ownerFilter, owners]);

  const formOwner = useMemo(
    () => uniqueOwners.find((owner) => owner.id === (selected?.ownerId ?? form.targetUserId))
      ?? ownerFilter
      ?? currentOwner,
    [currentOwner, form.targetUserId, ownerFilter, selected, uniqueOwners],
  );

  const loadRuns = useCallback(async (accountId: string | null) => {
    if (!accountId || demoMode) {
      setRuns([]);
      return;
    }
    try {
      const items = await externalMailApi.runs(accountId);
      if (mountedRef.current) setRuns(items);
    } catch {
      if (mountedRef.current) setRuns([]);
    }
  }, [demoMode]);

  useEffect(() => {
    if (demoMode || !canAdmin) return undefined;
    let cancelled = false;
    setOwnersLoading(true);
    setOwnerError(null);
    const timer = window.setTimeout(() => {
      void externalMailApi.searchOwners(ownerQuery, 25)
        .then((items) => {
          if (!cancelled && mountedRef.current) setOwners(items);
        })
        .catch((searchError) => {
          if (!cancelled && mountedRef.current) setOwnerError(errorMessage(searchError));
        })
        .finally(() => {
          if (!cancelled && mountedRef.current) setOwnersLoading(false);
        });
    }, ownerQuery.trim() ? 300 : 0);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [canAdmin, demoMode, ownerQuery]);

  const loadOwnerFolders = useCallback(async (ownerId: string | null | undefined) => {
    const requestId = ++foldersRequestRef.current;
    if (demoMode || !ownerId) {
      setOwnerFolders([]);
      setFoldersError(null);
      setFoldersLoading(false);
      return;
    }
    setFoldersLoading(true);
    setFoldersError(null);
    try {
      const items = await externalMailApi.ownerFolders(ownerId);
      if (mountedRef.current && foldersRequestRef.current === requestId) setOwnerFolders(items);
    } catch (folderError) {
      if (mountedRef.current && foldersRequestRef.current === requestId) {
        setOwnerFolders([]);
        setFoldersError(errorMessage(folderError));
      }
    } finally {
      if (mountedRef.current && foldersRequestRef.current === requestId) setFoldersLoading(false);
    }
  }, [demoMode]);

  useEffect(() => {
    void loadOwnerFolders(selected?.ownerId ?? ownerFilter?.id ?? currentOwner?.id);
  }, [currentOwner?.id, loadOwnerFolders, ownerFilter?.id, selected?.ownerId]);

  const load = useCallback(async (preferredId?: string | null, ownerIdOverride?: string | null) => {
    if (demoMode) return;
    const requestId = ++loadRequestRef.current;
    const effectiveOwnerId = ownerIdOverride !== undefined
      ? ownerIdOverride
      : (canAdmin ? ownerFilter?.id : currentOwner?.id) ?? null;
    setLoading(true);
    setError(null);
    try {
      const [accountItems, presetItems] = await Promise.all([
        externalMailApi.list(effectiveOwnerId),
        externalMailApi.presets().catch(() => FALLBACK_PRESETS),
      ]);
      if (!mountedRef.current || loadRequestRef.current !== requestId) return;
      setAccounts(accountItems);
      setPresets(presetItems.length ? presetItems : FALLBACK_PRESETS);
      if (!currentOwner || !effectiveOwnerId || effectiveOwnerId === currentOwner.id) {
        onAccountsChanged?.(accountItems);
      }
      const nextSelected = preferredId !== undefined ? preferredId : selectedId;
      const account = accountItems.find((item) => item.id === nextSelected) ?? null;
      if (account) {
        setSelectedId(account.id);
        setForm(formFromAccount(account));
        void loadRuns(account.id);
      } else if (preferredId === null) {
        setSelectedId(null);
        setForm(emptyForm(
          presetItems.length ? presetItems : FALLBACK_PRESETS,
          effectiveOwnerId ?? currentOwner?.id ?? '',
        ));
        setRuns([]);
      }
    } catch (loadError) {
      if (mountedRef.current && loadRequestRef.current === requestId) setError(errorMessage(loadError));
    } finally {
      if (mountedRef.current && loadRequestRef.current === requestId) setLoading(false);
    }
  }, [canAdmin, currentOwner, demoMode, loadRuns, onAccountsChanged, ownerFilter, selectedId]);

  useEffect(() => {
    void load(null);
    // Первичная загрузка при открытии; дальнейшие обновления делают действия пользователя.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [demoMode]);

  useEffect(() => {
    if (demoMode || !accounts.some((account) => account.syncRunning)) return undefined;
    const timer = window.setInterval(() => {
      const effectiveOwnerId = (canAdmin ? ownerFilter?.id : currentOwner?.id) ?? null;
      const requestAtStart = loadRequestRef.current;
      void externalMailApi.list(effectiveOwnerId)
        .then((items) => {
          if (!mountedRef.current || loadRequestRef.current !== requestAtStart) return;
          setAccounts(items);
          if (!currentOwner || !effectiveOwnerId || effectiveOwnerId === currentOwner.id) {
            onAccountsChanged?.(items);
          }
          if (selectedId) void loadRuns(selectedId);
        })
        .catch(() => undefined);
    }, 4000);
    return () => window.clearInterval(timer);
  }, [accounts, canAdmin, currentOwner, demoMode, loadRuns, onAccountsChanged, ownerFilter, selectedId]);

  const patch = (next: Partial<FormState>) => {
    setForm((current) => {
      const merged = { ...current, ...next };
      if (next.provider || next.incomingProtocol) return withPreset(merged, presets);
      return merged;
    });
    setTestResult(null);
  };

  const selectAccount = (account: ExternalMailAccount) => {
    setSelectedId(account.id);
    setForm(formFromAccount(account));
    setError(null);
    setNotice(null);
    setTestResult(null);
    void loadRuns(account.id);
  };

  const startNew = () => {
    setSelectedId(null);
    setForm(emptyForm(presets, ownerFilter?.id ?? currentOwner?.id ?? ''));
    setRuns([]);
    setError(null);
    setNotice(null);
    setTestResult(null);
  };

  const changeOwnerFilter = (owner: ExternalMailOwner | null) => {
    setOwnerFilter(owner);
    setSelectedId(null);
    setForm(emptyForm(presets, owner?.id ?? currentOwner?.id ?? ''));
    setRuns([]);
    setError(null);
    setNotice(null);
    setTestResult(null);
    void load(null, owner?.id ?? null);
  };

  const runAction = async (key: string, action: () => Promise<void>) => {
    setBusy(key);
    setError(null);
    try {
      await action();
    } catch (actionError) {
      setError(errorMessage(actionError));
    } finally {
      if (mountedRef.current) setBusy(null);
    }
  };

  const ensureOwnerSelected = (): boolean => {
    if (!canAdmin || selectedId || form.targetUserId.trim()) return true;
    setError(t('Выберите сотрудника Corvex, которому подключается внешний ящик.'));
    return false;
  };

  const handleTest = () => {
    if (!ensureOwnerSelected()) return;
    void runAction('test', async () => {
      const result = await externalMailApi.testDraft(toRequest(form, selectedId));
      setTestResult(result);
    });
  };

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!ensureOwnerSelected()) return;
    void runAction('save', async () => {
      const saved = selectedId
        ? await externalMailApi.update(selectedId, toRequest(form, selectedId))
        : await externalMailApi.create(toRequest(form, null));
      setNotice(t('Внешний ящик сохранён. Первая синхронизация поставлена в очередь.'));
      await load(saved.id, saved.ownerId);
    });
  };

  const handleSync = (migration: boolean) => {
    if (!selected) return;
    if (migration && !window.confirm(t('Запустить импорт архива всех папок? Уже импортированные письма повторно не создадутся.'))) return;
    void runAction(migration ? 'migrate' : 'sync', async () => {
      const run = migration ? await externalMailApi.migrate(selected.id) : await externalMailApi.sync(selected.id);
      setNotice(t('Синхронизация запущена: {status}', { status: run.status }));
      await load(selected.id, selected.ownerId);
    });
  };

  const handleToggleSync = () => {
    if (!selected) return;
    void runAction('toggle', async () => {
      await externalMailApi.setEnabled(selected.id, { syncEnabled: !selected.syncEnabled });
      await load(selected.id, selected.ownerId);
    });
  };

  const handleReset = () => {
    if (!selected) return;
    if (!window.confirm(t('Сбросить курсоры папок? Письма в Corvex и дедупликация сохранятся.'))) return;
    void runAction('reset', async () => {
      await externalMailApi.resetSync(selected.id);
      setNotice(t('Состояние синхронизации сброшено.'));
      await load(selected.id, selected.ownerId);
    });
  };

  const handleDelete = () => {
    if (!selected) return;
    if (!window.confirm(t('Отключить внешний ящик? Уже импортированные письма останутся в Corvex.'))) return;
    void runAction('delete', async () => {
      await externalMailApi.remove(selected.id);
      setNotice(t('Внешний ящик отключён.'));
      await load(null, selected.ownerId);
    });
  };

  const providerHint = presetFor(form.provider, presets)?.hint;

  return (
    <div className="fixed inset-0 z-[70] flex items-stretch justify-center bg-ink/35 p-0 backdrop-blur-[2px] sm:items-center sm:p-4" role="dialog" aria-modal="true" aria-label={t('Внешние ящики')}>
      <button type="button" aria-label={t('Закрыть')} onClick={onClose} className="absolute inset-0 cursor-default" />
      <section className="relative flex h-full w-full max-w-6xl flex-col overflow-hidden bg-surface shadow-composer sm:h-[min(820px,94dvh)] sm:rounded-[20px] sm:border sm:border-line">
        <header className="flex items-start gap-3 border-b border-line px-5 py-4">
          <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-accent-soft text-accent-dark"><Mail size={21} /></span>
          <div className="min-w-0 flex-1">
            <h3 className="text-lg font-semibold tracking-[-0.02em]">{t('Внешние ящики')}</h3>
            <p className="mt-0.5 text-sm leading-relaxed text-muted">{t('Старые адреса Mail.ru, Яндекса и других провайдеров рядом с почтой Corvex: сбор, миграция архива и отправка от имени внешнего адреса.')}</p>
          </div>
          <button type="button" onClick={onClose} className="icon-btn shrink-0" aria-label={t('Закрыть')}><X size={18} /></button>
        </header>

        {demoMode ? (
          <div className="scroll-area min-h-0 flex-1 px-5 py-6">
            <div className="rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm leading-relaxed text-amber-900">
              <b>{t('Нужен запущенный сервер Corvex.')}</b> {t('В демо-режиме нельзя вводить настоящий пароль приложения и подключать реальный ящик. Ниже — поддерживаемые сценарии; сама форма откроется после входа в настоящий Corvex.')}
            </div>
            <div className="mt-5 grid gap-3 sm:grid-cols-2">
              {FALLBACK_PRESETS.map((preset) => (
                <article key={preset.provider} className="rounded-2xl border border-line bg-chrome p-4">
                  <div className="font-semibold">{PROVIDER_LABELS[preset.provider]}</div>
                  <div className="mt-1 text-sm text-muted">IMAP {preset.imapHost}:{preset.imapPort} · SMTP {preset.smtpHost}:{preset.smtpPort}</div>
                  <p className="mt-2 text-sm leading-relaxed text-muted">{t(preset.hint)}</p>
                </article>
              ))}
            </div>
            <ul className="mt-5 space-y-2 text-sm leading-relaxed text-ink-soft">
              <li>• {t('IMAP собирает новые письма и может импортировать архив всех папок с флагами и датами.')}</li>
              <li>• {t('POP3 поддерживается как запасной вариант для старых провайдеров.')}</li>
              <li>• {t('Ответы можно отправлять от старого адреса через SMTP провайдера.')}</li>
              <li>• {t('Пароль приложения хранится только в зашифрованном виде и не показывается обратно.')}</li>
              <li>• {t('Администратор почты находит сотрудника по имени или email и подключает ему личный внешний ящик либо общий legacy-ящик на сервисном пользователе.')}</li>
            </ul>
          </div>
        ) : (
          <div className="grid min-h-0 flex-1 grid-rows-[minmax(128px,32%)_minmax(0,1fr)] md:grid-cols-[300px_minmax(0,1fr)] md:grid-rows-1">
            <aside className="scroll-area min-h-0 border-b border-line bg-chrome p-3 md:border-b-0 md:border-r">
              {canAdmin && (
                <div className="mb-3 rounded-2xl border border-line bg-surface p-3">
                  <div className="flex items-center gap-2 text-sm font-semibold">
                    <UserRound size={16} className="text-accent-dark" />
                    {t('Сотрудник Corvex')}
                  </div>
                  <p className="mt-1 text-xs leading-relaxed text-muted">
                    {t('Выберите владельца: список, новая форма и папка будут относиться к этому сотруднику.')}
                  </p>
                  <span className="relative mt-3 block">
                    <Search size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-faint" />
                    <input
                      value={ownerQuery}
                      onChange={(event) => setOwnerQuery(event.target.value)}
                      placeholder={t('Поиск по имени, email, логину или отделу')}
                      className={`${inputCls} pl-9`}
                      aria-label={t('Поиск сотрудника Corvex')}
                    />
                  </span>
                  <select
                    value={ownerFilter?.id ?? currentOwner?.id ?? ''}
                    onChange={(event) => {
                      const owner = uniqueOwners.find((candidate) => candidate.id === event.target.value) ?? null;
                      changeOwnerFilter(owner);
                    }}
                    className={`${selectCls} mt-2`}
                    disabled={ownersLoading && uniqueOwners.length === 0}
                    aria-label={t('Владелец внешних ящиков')}
                  >
                    {uniqueOwners.length === 0 && <option value="">{t('Сотрудники не найдены')}</option>}
                    {uniqueOwners.map((owner) => (
                      <option key={owner.id} value={owner.id}>
                        {ownerLabel(owner)}
                        {owner.externalAccountCount ? ` · ${t('ящиков: {count}', { count: owner.externalAccountCount })}` : ''}
                        {currentOwner?.id === owner.id ? ` · ${t('Вы')}` : ''}
                      </option>
                    ))}
                  </select>
                  {ownersLoading && <div className="mt-1.5 text-xs text-faint">{t('Ищем сотрудников…')}</div>}
                  {ownerError && <div className="mt-1.5 text-xs leading-relaxed text-red-700">{ownerError}</div>}
                </div>
              )}
              <button type="button" onClick={startNew} className="btn-primary w-full"><Plus size={16} />{t('Подключить ящик')}</button>
              <div className="mt-3 space-y-2">
                {loading && <div className="px-2 py-3 text-sm text-muted">{t('Загружаем…')}</div>}
                {!loading && accounts.length === 0 && (
                  <div className="rounded-2xl border border-dashed border-line px-4 py-5 text-sm leading-relaxed text-muted">
                    {t('Внешних ящиков пока нет. Подключите старый рабочий адрес — новые письма начнут появляться в Corvex.')}
                  </div>
                )}
                {accounts.map((account) => (
                  <button
                    key={account.id}
                    type="button"
                    onClick={() => selectAccount(account)}
                    className={`w-full rounded-2xl border px-3.5 py-3 text-left transition ${selectedId === account.id ? 'border-accent/40 bg-accent-soft/70' : 'border-line bg-surface hover:border-accent/30'}`}
                  >
                    <span className="flex items-center gap-2">
                      <span className={`h-2 w-2 shrink-0 rounded-full ${account.lastError ? 'bg-red-500' : account.syncRunning ? 'bg-amber-500' : account.enabled && account.syncEnabled ? 'bg-emerald-500' : 'bg-zinc-400'}`} />
                      <span className="min-w-0 flex-1 truncate text-sm font-semibold">{account.label}</span>
                    </span>
                    <span className="mt-1 block truncate pl-4 text-xs text-muted">{account.emailAddress}</span>
                    {canAdmin && ownerFilter && (
                      <span className="mt-1 block truncate pl-4 text-xs text-faint">
                        {t('Владелец')}: {ownerLabel(ownerFilter)}
                      </span>
                    )}
                    <span className="mt-1 block pl-4 text-xs text-faint">
                      {account.syncRunning ? t('Синхронизация выполняется…') : account.lastSuccessAt ? `${t('Успешно')}: ${formatDateTime(locale, account.lastSuccessAt)}` : t('Ещё не синхронизировался')}
                    </span>
                    {account.lastError && <span className="mt-2 line-clamp-2 block pl-4 text-xs leading-relaxed text-red-700">{account.lastError}</span>}
                  </button>
                ))}
              </div>
            </aside>

            <form onSubmit={handleSubmit} className="scroll-area min-h-0 px-5 py-5">
              <div className="flex flex-wrap items-start gap-3">
                <div className="min-w-0 flex-1">
                  <h4 className="text-base font-semibold">{selected ? selected.label : t('Новое подключение')}</h4>
                  <p className="mt-0.5 text-sm text-muted">{t('Используйте пароль приложения провайдера, а не обычный пароль от веб-почты.')}</p>
                </div>
                <button type="button" onClick={() => void load(selectedId)} className="btn-secondary h-9 px-3 text-[13px]" disabled={loading || busy !== null}>
                  <RefreshCw size={14} />{t('Обновить')}
                </button>
              </div>

              {error && <div className="mt-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800" role="alert">{error}</div>}
              {notice && <div className="mt-4 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900">{notice}</div>}
              {testResult && (
                <div className={`mt-4 rounded-xl border px-4 py-3 text-sm leading-relaxed ${testResult.incomingOk && (!testResult.smtpTested || testResult.smtpOk) ? 'border-emerald-200 bg-emerald-50 text-emerald-900' : 'border-amber-200 bg-amber-50 text-amber-900'}`}>
                  <div className="flex items-center gap-2 font-semibold">
                    {testResult.incomingOk ? <CheckCircle2 size={16} /> : <AlertCircle size={16} />}
                    {t('Входящие')}: {testResult.incomingMessage}
                  </div>
                  <div className="mt-1">{t('Папок найдено')}: {testResult.folderCount} · INBOX: {testResult.inboxMessageCount}</div>
                  {testResult.smtpTested && <div className="mt-1">SMTP: {testResult.smtpMessage}</div>}
                </div>
              )}

              {canAdmin && formOwner && currentOwner?.id !== formOwner.id && (
                <div className="mt-4 rounded-xl border border-indigo-200 bg-indigo-50 px-4 py-3 text-sm leading-relaxed text-indigo-950">
                  <b>{t('Вы настраиваете ящик для сотрудника')}:</b> {ownerLabel(formOwner)}.{' '}
                  {t('Собранная почта попадёт этому сотруднику, а отправлять от внешнего адреса сможет только он сам. Администратор управляет подключением, но пароль после сохранения не показывается.')}
                </div>
              )}

              {selected && (
                <div className="mt-5 grid gap-3 rounded-2xl border border-line bg-chrome p-4 text-sm sm:grid-cols-3">
                  <div><span className="text-faint">{t('Доставлено в Corvex')}</span><b className="block text-base">{selected.deliveredCount}</b></div>
                  <div><span className="text-faint">{t('В очереди проверки')}</span><b className="block text-base">{selected.queuedCount}</b></div>
                  <div><span className="text-faint">{t('Ошибок импорта')}</span><b className="block text-base">{selected.failedCount}</b></div>
                  <div className="sm:col-span-3 flex flex-wrap gap-2 pt-1">
                    <button type="button" onClick={() => handleSync(false)} disabled={busy !== null || selected.syncRunning} className="btn-secondary h-9 px-3 text-[13px]"><RefreshCw size={14} />{t('Синхронизировать сейчас')}</button>
                    <button type="button" onClick={() => handleSync(true)} disabled={busy !== null || selected.syncRunning} className="btn-secondary h-9 px-3 text-[13px]"><Download size={14} />{t('Импорт архива')}</button>
                    <button type="button" onClick={handleToggleSync} disabled={busy !== null} className="btn-secondary h-9 px-3 text-[13px]">
                      {selected.syncEnabled ? <Pause size={14} /> : <Play size={14} />}{selected.syncEnabled ? t('Поставить сбор на паузу') : t('Возобновить сбор')}
                    </button>
                    <button type="button" onClick={handleReset} disabled={busy !== null} className="btn-ghost h-9 px-3 text-[13px]">{t('Сбросить курсоры')}</button>
                  </div>
                </div>
              )}

              <div className="mt-6 grid gap-4 sm:grid-cols-2">
                <Field label={t('Провайдер')}>
                  <select value={form.provider} onChange={(event) => patch({ provider: event.target.value as ExternalMailProvider })} className={selectCls}>
                    {Object.entries(PROVIDER_LABELS).map(([value, label]) => <option key={value} value={value}>{t(label)}</option>)}
                  </select>
                </Field>
                <Field label={t('Название подключения')}>
                  <input value={form.label} onChange={(event) => patch({ label: event.target.value })} placeholder={t('Например: старый рабочий Mail.ru')} className={inputCls} />
                </Field>
                <Field label={t('Адрес внешнего ящика')}>
                  <input required type="email" value={form.emailAddress} onChange={(event) => patch({ emailAddress: event.target.value })} placeholder="name@mail.ru" className={inputCls} autoComplete="email" />
                </Field>
                <Field label={t('Имя отправителя')}>
                  <input value={form.displayName} onChange={(event) => patch({ displayName: event.target.value })} placeholder={t('Компания или ФИО')} className={inputCls} />
                </Field>
                <Field label={t('Логин у провайдера')} hint={t('Обычно совпадает с адресом ящика.')}>
                  <input value={form.incomingUsername} onChange={(event) => patch({ incomingUsername: event.target.value })} placeholder="name@mail.ru" className={inputCls} autoComplete="username" />
                </Field>
                <Field label={t('Пароль приложения')} hint={selected ? t('Оставьте пустым, чтобы сохранить текущий пароль.') : providerHint ? t(providerHint) : undefined}>
                  <span className="relative block">
                    <KeyRound size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-faint" />
                    <input required={!selected} type="password" value={form.secret} onChange={(event) => patch({ secret: event.target.value })} className={`${inputCls} pl-9`} autoComplete="new-password" />
                  </span>
                </Field>
              </div>

              <h5 className="mt-7 text-sm font-semibold">{t('Сбор входящей почты')}</h5>
              <div className="mt-3 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                <Field label={t('Протокол')}>
                  <select value={form.incomingProtocol} onChange={(event) => patch({ incomingProtocol: event.target.value as FormState['incomingProtocol'] })} className={selectCls}>
                    <option value="IMAP">IMAP — {t('рекомендуется')}</option>
                    <option value="POP3">POP3</option>
                  </select>
                </Field>
                <Field label={t('Сервер')}>
                  <input required value={form.incomingHost} disabled={form.provider !== 'CUSTOM'} onChange={(event) => patch({ incomingHost: event.target.value })} className={inputCls} />
                </Field>
                <Field label={t('Порт')}>
                  <input required type="number" min={1} max={65535} value={form.incomingPort} disabled={form.provider !== 'CUSTOM'} onChange={(event) => patch({ incomingPort: Number(event.target.value) })} className={inputCls} />
                </Field>
                <Field label={t('Защита соединения')}>
                  <select value={form.incomingSecurity} disabled={form.provider !== 'CUSTOM'} onChange={(event) => patch({ incomingSecurity: event.target.value as FormState['incomingSecurity'] })} className={selectCls}>
                    <option value="SSL_TLS">SSL/TLS</option>
                    <option value="STARTTLS">STARTTLS</option>
                    <option value="PLAIN">{t('Без TLS — только если разрешил администратор')}</option>
                  </select>
                </Field>
                <Field label={t('При первом подключении')}>
                  <select value={form.initialSyncMode} onChange={(event) => patch({ initialSyncMode: event.target.value as FormState['initialSyncMode'] })} className={selectCls}>
                    <option value="NEW_ONLY">{t('Только новые письма')}</option>
                    <option value="IMPORT_ALL">{t('Импортировать существующие письма')}</option>
                  </select>
                </Field>
                <Field label={t('Какие папки собирать')}>
                  <select value={form.syncScope} onChange={(event) => patch({ syncScope: event.target.value as FormState['syncScope'] })} className={selectCls} disabled={form.incomingProtocol === 'POP3'}>
                    <option value="INBOX_ONLY">{t('Только Входящие')}</option>
                    <option value="ALL_FOLDERS">{t('Все папки')}</option>
                  </select>
                </Field>
                <Field
                  label={t('Папка Corvex')}
                  hint={foldersError ?? (foldersLoading ? t('Загружаем папки владельца…') : t('Куда складывать собранные входящие письма.'))}
                >
                  <select
                    value={form.targetFolderId}
                    onChange={(event) => patch({ targetFolderId: event.target.value })}
                    className={selectCls}
                    disabled={foldersLoading}
                  >
                    <option value="">{t('Входящие Corvex')}</option>
                    {form.targetFolderId && !ownerFolders.some((folder) => folder.id === form.targetFolderId) && (
                      <option value={form.targetFolderId}>{t('Сохранённая папка владельца')}</option>
                    )}
                    {ownerFolders.map((folder) => <option key={folder.id} value={folder.id}>{folder.path}</option>)}
                  </select>
                </Field>
                <Field label={t('Проверять каждые, мин')}>
                  <input type="number" min={1} max={1440} value={form.pollIntervalMinutes} onChange={(event) => patch({ pollIntervalMinutes: Number(event.target.value) })} className={inputCls} />
                </Field>
              </div>

              <div className="mt-4 grid gap-3 sm:grid-cols-2">
                <Check checked={form.enabled} onChange={(value) => patch({ enabled: value })}>{t('Подключение включено')}</Check>
                <Check checked={form.syncEnabled} onChange={(value) => patch({ syncEnabled: value })}>{t('Собирать входящую почту автоматически')}</Check>
                <Check checked={form.leaveOnServer} disabled={form.deleteAfterFetch} onChange={(value) => patch({ leaveOnServer: value })}>{t('Оставлять письма на сервере провайдера')}</Check>
                <Check checked={form.markSeenOnServer} onChange={(value) => patch({ markSeenOnServer: value })}>{t('Помечать собранные письма прочитанными у провайдера')}</Check>
                <Check checked={form.deleteAfterFetch} onChange={(value) => patch({ deleteAfterFetch: value, leaveOnServer: value ? false : form.leaveOnServer })}>
                  {t('Удалять у провайдера после доставки в Corvex (при следующей проверке)')}
                </Check>
              </div>

              <h5 className="mt-7 flex items-center gap-2 text-sm font-semibold"><Send size={15} />{t('Отправка от имени внешнего адреса')}</h5>
              <div className="mt-3 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                <Field label={t('SMTP сервер')}>
                  <input value={form.smtpHost} disabled={!form.smtpEnabled || form.provider !== 'CUSTOM'} onChange={(event) => patch({ smtpHost: event.target.value })} className={inputCls} />
                </Field>
                <Field label={t('SMTP порт')}>
                  <input type="number" min={1} max={65535} value={form.smtpPort} disabled={!form.smtpEnabled || form.provider !== 'CUSTOM'} onChange={(event) => patch({ smtpPort: Number(event.target.value) })} className={inputCls} />
                </Field>
                <Field label={t('SMTP защита')}>
                  <select value={form.smtpSecurity} disabled={!form.smtpEnabled || form.provider !== 'CUSTOM'} onChange={(event) => patch({ smtpSecurity: event.target.value as FormState['smtpSecurity'] })} className={selectCls}>
                    <option value="SSL_TLS">SSL/TLS</option>
                    <option value="STARTTLS">STARTTLS</option>
                    <option value="PLAIN">{t('Без TLS — только если разрешил администратор')}</option>
                  </select>
                </Field>
                <Field label={t('SMTP логин')} hint={t('Пусто = тот же логин, что для входящих.')}>
                  <input value={form.smtpUsername} disabled={!form.smtpEnabled} onChange={(event) => patch({ smtpUsername: event.target.value })} className={inputCls} />
                </Field>
              </div>
              <div className="mt-4 grid gap-3 sm:grid-cols-2">
                <Check checked={form.smtpEnabled} onChange={(value) => patch({ smtpEnabled: value, sendEnabled: value && form.sendEnabled })}>{t('SMTP провайдера настроен')}</Check>
                <Check checked={form.sendEnabled} disabled={!form.smtpEnabled} onChange={(value) => patch({ sendEnabled: value })}>{t('Разрешить отправку от имени этого адреса')}</Check>
                <Check checked={form.saveSentOnServer} disabled={!form.smtpEnabled || form.incomingProtocol !== 'IMAP'} onChange={(value) => patch({ saveSentOnServer: value })}>{t('Сохранять отправленные также в Sent у провайдера')}</Check>
              </div>

              <details className="mt-7 rounded-2xl border border-line bg-chrome px-4 py-3">
                <summary className="cursor-pointer text-sm font-semibold">{t('Дополнительно')}</summary>
                <div className="mt-4 grid gap-4 sm:grid-cols-2">
                  <Field label={t('Тип секрета')}>
                    <select value={form.authType} onChange={(event) => patch({ authType: event.target.value as FormState['authType'] })} className={selectCls}>
                      <option value="APP_PASSWORD">{t('Пароль приложения')}</option>
                      <option value="PASSWORD">{t('Пароль почтового сервера')}</option>
                    </select>
                  </Field>
                  <Field label={t('Папка Sent у провайдера')} hint={t('Пусто = определить автоматически по IMAP.')}>
                    <input value={form.sentFolderName} onChange={(event) => patch({ sentFolderName: event.target.value })} className={inputCls} />
                  </Field>
                  {canAdmin && (
                    <>
                      <Field label={t('Тип владения')}>
                        <select value={form.ownerKind} onChange={(event) => patch({ ownerKind: event.target.value as FormState['ownerKind'] })} className={selectCls}>
                          <option value="PERSONAL">{t('Личный рабочий ящик')}</option>
                          <option value="SHARED_MAILBOX">{t('Общий legacy-ящик компании')}</option>
                        </select>
                      </Field>
                      <Field
                        label={t('Владелец Corvex')}
                        hint={selected
                          ? t('Владельца существующего подключения изменить нельзя; для другого сотрудника создайте новое подключение.')
                          : t('Выбирается поиском сотрудника в левой панели. Для общего ящика выберите отдельного сервисного пользователя.')}
                      >
                        <input value={ownerLabel(formOwner)} disabled className={inputCls} />
                      </Field>
                    </>
                  )}
                </div>
              </details>

              <div className="sticky bottom-0 mt-6 flex flex-wrap items-center gap-2 border-t border-line bg-surface/95 py-4 backdrop-blur">
                <button type="button" onClick={handleTest} disabled={busy !== null} className="btn-secondary">
                  <CheckCircle2 size={15} />{busy === 'test' ? t('Проверяем…') : t('Проверить подключение')}
                </button>
                <label className="flex items-center gap-2 text-sm text-muted">
                  <input type="checkbox" checked={form.testBeforeSave} onChange={(event) => patch({ testBeforeSave: event.target.checked })} className="h-4 w-4 accent-[#4f46e5]" />
                  {t('Проверять перед сохранением')}
                </label>
                <span className="flex-1" />
                {selected && (
                  <button type="button" onClick={handleDelete} disabled={busy !== null} className="btn-ghost text-red-700 hover:bg-red-50 hover:text-red-800">
                    <Trash2 size={15} />{t('Отключить')}
                  </button>
                )}
                <button type="submit" disabled={busy !== null} className="btn-primary">
                  {busy === 'save' ? t('Сохраняем…') : selected ? t('Сохранить изменения') : t('Подключить ящик')}
                </button>
              </div>

              {selected && runs.length > 0 && (
                <section className="mt-2 border-t border-line pt-5">
                  <h5 className="text-sm font-semibold">{t('История синхронизаций')}</h5>
                  <div className="mt-3 space-y-2">
                    {runs.slice(0, 8).map((run) => (
                      <div key={run.id} className="rounded-xl border border-line bg-chrome px-3.5 py-3 text-sm">
                        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                          <b>{run.status}</b>
                          <span className="text-muted">{formatDateTime(locale, run.startedAt)}</span>
                          <span className="text-muted">{run.trigger}</span>
                        </div>
                        <div className="mt-1 text-xs text-muted">
                          {t('Папок')}: {run.foldersCount} · {t('В очередь')}: {run.queuedCount} · {t('Пропущено')}: {run.skippedCount} · {t('Ошибок')}: {run.failedCount}
                        </div>
                        {run.error && <div className="mt-1 text-xs leading-relaxed text-red-700">{run.error}</div>}
                      </div>
                    ))}
                  </div>
                </section>
              )}
            </form>
          </div>
        )}
      </section>
    </div>
  );
}
