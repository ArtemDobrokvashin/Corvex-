import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import {
  Archive,
  ArrowLeft,
  ArrowUpDown,
  Calendar,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  FilePenLine,
  Folder,
  Forward,
  Inbox,
  MailOpen,
  Minus,
  MoreHorizontal,
  OctagonAlert,
  Paperclip,
  PenSquare,
  Reply,
  Send,
  Settings2,
  SlidersHorizontal,
  Star,
  Trash2,
  X,
} from 'lucide-react';
import { demoFolders, demoMails, DemoMail } from '../../api/demoData';
import { externalMailApi, ExternalMailAccount } from '../../api/externalMailApi';
import { mailApi, MailboxQuota, MailFolderView, MailMessageListItem, MailAttachmentView, SendAttachment } from '../../api/mailApi';
import ExternalMailAccountsModal from './ExternalMailAccountsModal';
import MailboxUsage from './MailboxUsage';
import MailAttachments from './MailAttachments';
import MailSidebarActions from './MailSidebarActions';
import { canAdministerExternalMail, canUseExternalMail } from './externalMailAccess';
import {
  applyMailListControls,
  hasActiveMailFilters,
  type MailReadFilter,
  type MailSortOrder,
} from './mailListControls';
import { formatMailRecipients } from './mailRecipients';
import { mailHtmlDocument } from './mailHtml';
import { activeMailAfterRefresh, replaceFolderMessages } from './mailRefresh';
import { Avatar, EmptyState, IconButton, cleanPersonName, emailFromHeader } from '../../components/ui';
import { countLabel, formatDateTime, useI18n } from '../../i18n';
import { truncateGraphemes } from '../../lib/text';
import { useSession } from '../../store/sessionStore';

interface ComposeDraft {
  to: string;
  cc?: string;
  subject: string;
  body: string;
  externalAccountId?: string | null;
}

type FolderTab = ReturnType<typeof demoFolders>[number];

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function splitAddresses(value?: string): string[] {
  return (value ?? '').split(/[;,]/).map((part) => part.trim()).filter(Boolean);
}

async function fileToAttachment(file: File): Promise<SendAttachment> {
  const bytes = new Uint8Array(await file.arrayBuffer());
  let binary = '';
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize));
  }
  return {
    fileName: file.name,
    contentType: file.type || 'application/octet-stream',
    contentBase64: btoa(binary),
  };
}

const FOLDER_ICONS: Record<string, typeof Inbox> = {
  INBOX: Inbox,
  SENT: Send,
  DRAFTS: FilePenLine,
  ARCHIVE: Archive,
  SPAM: OctagonAlert,
  TRASH: Trash2,
};

const MAIL_REFRESH_INTERVAL_MS = 15_000;

/** Почта Quiet Work: папки → сканируемый список → письмо как документ, composer поверх контекста. */
export default function MailPage() {
  const { locale, t } = useI18n();
  const navigate = useNavigate();
  const demoMode = useSession((s) => s.demoMode);
  const sessionUser = useSession((s) => s.user);
  const demoReadMailIds = useSession((s) => s.demoReadMailIds);
  const markDemoMailRead = useSession((s) => s.markDemoMailRead);
  const [realFolders, setRealFolders] = useState<MailFolderView[]>([]);
  const [mailboxQuota, setMailboxQuota] = useState<MailboxQuota | null>(null);
  const [mailboxQuotaUnavailable, setMailboxQuotaUnavailable] = useState(false);
  const folders = useMemo<FolderTab[]>(() => {
    const localized = demoFolders(locale);
    if (demoMode) return localized;
    return realFolders.map((f) => {
      const key = f.specialUse ?? f.id;
      return { id: key, name: localized.find((d) => d.id === key)?.name ?? f.name, unread: f.unread };
    });
  }, [demoMode, locale, realFolders]);
  const [searchParams, setSearchParams] = useSearchParams();
  const [folder, setFolder] = useState('INBOX');
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState<MailReadFilter>('all');
  const [onlyFlagged, setOnlyFlagged] = useState(false);
  const [onlyAttachments, setOnlyAttachments] = useState(false);
  const [sortOrder, setSortOrder] = useState<MailSortOrder>('date-desc');
  const [openListPanel, setOpenListPanel] = useState<'sort' | 'filters' | null>(null);
  const [mails, setMails] = useState<DemoMail[]>(() => (
    demoMode
      ? demoMails(locale).map((mail) => (
        demoReadMailIds.includes(mail.id) ? { ...mail, seen: true } : mail
      ))
      : []
  ));
  const [activeId, setActiveId] = useState<string>(demoMode ? 'm1' : '');
  const [mobileReading, setMobileReading] = useState(false);
  const [composeDraft, setComposeDraft] = useState<ComposeDraft | null>(null);
  const [externalAccounts, setExternalAccounts] = useState<ExternalMailAccount[]>([]);
  const [externalOpen, setExternalOpen] = useState(false);
  const canExternalAccounts = canUseExternalMail(sessionUser?.permissions);
  const canAdminExternalAccounts = canAdministerExternalMail(sessionUser?.permissions);
  const [serverFolderCounts, setServerFolderCounts] = useState<Record<string, number>>({});
  const demoFolderCounts = useMemo<Record<string, number>>(() => {
    const counts: Record<string, number> = {};
    for (const mail of mails) {
      if (!mail.seen) counts[mail.folder] = (counts[mail.folder] ?? 0) + 1;
    }
    return counts;
  }, [mails]);
  const folderCounts = demoMode ? demoFolderCounts : serverFolderCounts;
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loadedHtml, setLoadedHtml] = useState<Record<string, string | null>>({});
  const [showPlainText, setShowPlainText] = useState(false);
  const [loadedBodies, setLoadedBodies] = useState<Record<string, string>>({});
  const [loadedAttachments, setLoadedAttachments] = useState<Record<string, MailAttachmentView[]>>({});
  const [downloadBusy, setDownloadBusy] = useState<string | null>(null);
  const [loadedRecipients, setLoadedRecipients] = useState<Record<string, { to: string[]; cc: string[] }>>({});
  const sortPanelRef = useRef<HTMLDivElement>(null);
  const filtersPanelRef = useRef<HTMLDivElement>(null);
  const seenPendingRef = useRef<Set<string>>(new Set());

  // Синхронизация поиска из верхней панели (?mailSearch=...)
  useEffect(() => {
    const urlSearch = searchParams.get('mailSearch');
    if (urlSearch && urlSearch !== search) {
      setSearch(urlSearch);
    }
  }, [searchParams, search]);

  const visible = useMemo(() => applyMailListControls(mails, {
    folder,
    search,
    readFilter: filter,
    onlyFlagged,
    onlyAttachments,
    sortOrder,
  }, locale), [mails, folder, search, filter, onlyFlagged, onlyAttachments, sortOrder, locale]);

  const filtersActive = hasActiveMailFilters({
    readFilter: filter,
    onlyFlagged,
    onlyAttachments,
  });
  const sortActive = sortOrder !== 'date-desc';

  useEffect(() => {
    if (!openListPanel) return undefined;
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!sortPanelRef.current?.contains(target) && !filtersPanelRef.current?.contains(target)) {
        setOpenListPanel(null);
      }
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpenListPanel(null);
    };
    window.addEventListener('pointerdown', onPointerDown);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('pointerdown', onPointerDown);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [openListPanel]);

  const resetMailFilters = () => {
    setFilter('all');
    setOnlyFlagged(false);
    setOnlyAttachments(false);
  };

  const active = mails.find((m) => m.id === activeId && m.folder === folder) ?? visible[0] ?? null;
  const activeBody = active ? (loadedBodies[active.id] ?? active.body) : '';
  const activeHtml = active ? loadedHtml[active.id] : null;
  useEffect(() => { setShowPlainText(false); }, [active?.id]);
  const downloadAttachment = async (messageId: string, attachment: MailAttachmentView) => {
    const key = `${messageId}:${attachment.partIndex}`;
    if (downloadBusy) return;
    setDownloadBusy(key);
    let url = '';
    try {
      const blob = await mailApi.downloadAttachment(messageId, attachment.partIndex);
      url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = attachment.fileName || 'attachment';
      document.body.appendChild(link);
      link.click();
      link.remove();
    } catch (error) {
      setLoadError(`${t('Не удалось скачать вложение.')}: ${errorMessage(error)}`);
    } finally {
      if (url) window.setTimeout(() => URL.revokeObjectURL(url), 1000);
      setDownloadBusy(null);
    }
  };
  const activeIndex = active ? visible.findIndex((m) => m.id === active.id) : -1;
  const currentFolder = folders.find((f) => f.id === folder);
  const activeRecipientData = active ? loadedRecipients[active.id] : undefined;
  const recipientIdentity = {
    email: sessionUser?.email,
    displayName: sessionUser?.displayName,
  };
  const recipientFallbacks = {
    loading: t('получатели загружаются'),
    empty: t('получатель не указан'),
  };
  const recipientText = active ? formatMailRecipients(
    demoMode ? [sessionUser?.email ?? ''] : activeRecipientData?.to,
    recipientIdentity,
    recipientFallbacks,
  ) : '';
  const ccText = active && !demoMode && activeRecipientData?.cc.length
    ? formatMailRecipients(activeRecipientData.cc, recipientIdentity, recipientFallbacks)
    : '';

  const toMailView = (item: MailMessageListItem, folderKey: string): DemoMail => {
    const timestamp = Date.parse(item.date);
    return {
      id: item.id,
      folder: folderKey,
      from: item.sender,
      subject: item.subject,
      snippet: item.snippet ?? '',
      body: item.snippet ?? '',
      date: formatDateTime(locale, item.date),
      timestamp: Number.isFinite(timestamp) ? timestamp : 0,
      seen: item.seen,
      flagged: item.flagged,
      hasAttachments: item.hasAttachments,
    };
  };

  useEffect(() => {
    if (!demoMode) return;
    const localized = demoMails(locale);
    setMails((prev) => prev.map((mail) => {
      const translated = localized.find((candidate) => candidate.id === mail.id);
      return translated ? { ...translated, folder: mail.folder, seen: mail.seen, flagged: mail.flagged } : mail;
    }));
  }, [locale, demoMode]);

  useEffect(() => {
    if (demoMode) return;
    let cancelled = false;
    mailApi.folders()
      .then((items) => {
        if (cancelled) return;
        setRealFolders(items);
        setServerFolderCounts(Object.fromEntries(items.map((f) => [f.specialUse ?? f.id, f.unread])));
        const inbox = items.find((f) => f.specialUse === 'INBOX') ?? items[0];
        if (inbox) setFolder(inbox.specialUse ?? inbox.id);
      })
      .catch((error) => { if (!cancelled) setLoadError(errorMessage(error)); });
    return () => { cancelled = true; };
  }, [demoMode]);

  useEffect(() => {
    if (demoMode) {
      setMailboxQuota({ usedBytes: 3.1 * 1024 ** 3, quotaBytes: 5 * 1024 ** 3, messageCount: 5 });
      setMailboxQuotaUnavailable(false);
      return;
    }
    let cancelled = false;
    let refreshing = false;
    setMailboxQuota(null);
    setMailboxQuotaUnavailable(false);
    const refresh = async () => {
      if (cancelled || refreshing || document.visibilityState === 'hidden') return;
      refreshing = true;
      try {
        const quota = await mailApi.quota();
        if (cancelled) return;
        setMailboxQuota(quota);
        setMailboxQuotaUnavailable(false);
      } catch {
        if (!cancelled) setMailboxQuotaUnavailable(true);
      } finally {
        refreshing = false;
      }
    };
    void refresh();
    const timer = window.setInterval(() => { void refresh(); }, MAIL_REFRESH_INTERVAL_MS);
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') void refresh();
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, [demoMode]);

  useEffect(() => {
    if (demoMode || !canExternalAccounts) return;
    let cancelled = false;
    externalMailApi.list()
      .then((items) => { if (!cancelled) setExternalAccounts(items); })
      .catch(() => {
        // Ошибку конфигурации коннектора показывает его собственный экран; обычная почта не должна падать.
        if (!cancelled) setExternalAccounts([]);
      });
    return () => { cancelled = true; };
  }, [demoMode, canExternalAccounts]);

  useEffect(() => {
    if (!canAdminExternalAccounts) setExternalOpen(false);
  }, [canAdminExternalAccounts]);

  const selectedRealFolder = realFolders.find((f) => (f.specialUse ?? f.id) === folder);
  const selectedRealFolderId = selectedRealFolder?.id;

  useEffect(() => {
    if (demoMode || !selectedRealFolderId) return;
    let cancelled = false;
    setLoadError(null);
    mailApi.messages(selectedRealFolderId)
      .then((items) => {
        if (cancelled) return;
        const mapped = items.map((item) => toMailView(item, folder));
        setMails((prev) => [...prev.filter((m) => m.folder !== folder), ...mapped]);
        if (mapped[0]) setActiveId(mapped[0].id);
      })
      .catch((error) => { if (!cancelled) setLoadError(errorMessage(error)); });
    return () => { cancelled = true; };
    // toMailView зависит только от locale; folder/selectedRealFolderId — триггер загрузки.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [demoMode, folder, selectedRealFolderId, locale]);

  useEffect(() => {
    if (demoMode || !selectedRealFolderId) return;
    let cancelled = false;
    let refreshing = false;

    const refresh = async () => {
      if (cancelled || refreshing || document.visibilityState === 'hidden') return;
      refreshing = true;
      try {
        const [folderItems, messageItems] = await Promise.all([
          mailApi.folders(),
          mailApi.messages(selectedRealFolderId),
        ]);
        if (cancelled) return;
        const mapped = messageItems.map((item) => toMailView(item, folder));
        setRealFolders(folderItems);
        setServerFolderCounts(Object.fromEntries(folderItems.map((f) => [f.specialUse ?? f.id, f.unread])));
        setMails((prev) => replaceFolderMessages(prev, folder, mapped));
        setActiveId((current) => activeMailAfterRefresh(current, mapped));
        setLoadError(null);
      } catch (error) {
        if (!cancelled) setLoadError(errorMessage(error));
      } finally {
        refreshing = false;
      }
    };

    const timer = window.setInterval(() => { void refresh(); }, MAIL_REFRESH_INTERVAL_MS);
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') void refresh();
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
    // toMailView зависит только от locale; snapshot обновляется для выбранной папки.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [demoMode, folder, selectedRealFolderId, locale]);

  const activeRealId = !demoMode && active ? active.id : null;
  useEffect(() => {
    if (!activeRealId || loadedBodies[activeRealId] != null) return;
    let cancelled = false;
    mailApi.message(activeRealId)
      .then((detail) => {
        if (cancelled) return;
        setLoadedHtml((prev) => ({ ...prev, [activeRealId]: detail.bodyHtml ?? null }));
        setLoadedAttachments((prev) => ({ ...prev, [activeRealId]: detail.attachments ?? [] }));
        setLoadedBodies((prev) => ({ ...prev, [activeRealId]: detail.bodyText ?? '' }));
        setLoadedRecipients((prev) => ({
          ...prev,
          [activeRealId]: { to: detail.to ?? [], cc: detail.cc ?? [] },
        }));
      })
      .catch((error) => { if (!cancelled) setLoadError(errorMessage(error)); });
    return () => { cancelled = true; };
  }, [activeRealId, loadedBodies]);

  useEffect(() => {
    const openCompose = searchParams.get('compose') === '1';
    const openExternalAccounts = searchParams.get('externalAccounts') === '1';
    if (openCompose) {
      setComposeDraft({ to: '', subject: '', body: '' });
    }
    if (openExternalAccounts && canAdminExternalAccounts) {
      setExternalOpen(true);
    }
    if (openCompose || openExternalAccounts) {
      setSearchParams({}, { replace: true });
    }
  }, [canAdminExternalAccounts, searchParams, setSearchParams]);

  const markSeen = (mail: DemoMail) => {
    if (mail.seen || seenPendingRef.current.has(mail.id)) return;
    seenPendingRef.current.add(mail.id);
    setMails((prev) => prev.map((x) => (x.id === mail.id ? { ...x, seen: true } : x)));
    if (demoMode) {
      markDemoMailRead(mail.id);
      return;
    }
    setServerFolderCounts((prev) => ({
      ...prev,
      [mail.folder]: Math.max(0, (prev[mail.folder] ?? 0) - 1),
    }));
    void mailApi.flags(mail.id, { seen: true }).catch((error) => setLoadError(errorMessage(error)));
  };

  const open = (m: DemoMail, fromKeyboard = false) => {
    setActiveId(m.id);
    if (!fromKeyboard) setMobileReading(true);
    markSeen(m);
  };

  useEffect(() => {
    if (!active || active.seen) return;
    const narrowLayout = typeof window !== 'undefined'
      && typeof window.matchMedia === 'function'
      && window.matchMedia('(max-width: 767px)').matches;
    // На мобильном первое письмо выбрано для десктопной панели, но ещё не открыто.
    if (narrowLayout && !mobileReading) return;
    // Если active получен как fallback visible[0], закрепляем его id: иначе после
    // отметки seen при фильтре «Непрочитанные» эффект каскадно прочитал бы следующее.
    setActiveId(active.id);
    markSeen(active);
    // markSeen намеренно не является зависимостью: эффект должен срабатывать
    // только при смене фактически показанного в панели чтения письма.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, mobileReading, demoMode]);

  const selectFolder = (id: string) => {
    setFolder(id);
    setOpenListPanel(null);
    setMobileReading(false);
    const first = mails.find((m) => m.folder === id);
    if (first) setActiveId(first.id);
  };

  const moveActive = (targetFolder: string) => {
    if (!active) return;
    const next = visible[activeIndex + 1] ?? visible[activeIndex - 1];
    if (!demoMode) {
      const target = realFolders.find((f) => (f.specialUse ?? f.id) === targetFolder);
      if (!target) return;
      void mailApi.move(active.id, target.id)
        .then((item) => {
          setMails((prev) => prev.map((m) => (
            m.id === active.id
              ? { ...toMailView(item, targetFolder), body: loadedBodies[active.id] ?? m.body }
              : m
          )));
          if (next && next.id !== active.id) setActiveId(next.id);
          setMobileReading(false);
        })
        .catch((error) => setLoadError(errorMessage(error)));
      return;
    }
    setMails((prev) => prev.map((m) => (m.id === active.id ? { ...m, folder: targetFolder } : m)));
    if (next && next.id !== active.id) setActiveId(next.id);
    setMobileReading(false);
  };

  const toggleFlag = (mail: DemoMail) => {
    setMails((prev) => prev.map((m) => (m.id === mail.id ? { ...m, flagged: !m.flagged } : m)));
    if (!demoMode) {
      void mailApi.flags(mail.id, { flagged: !mail.flagged }).catch((error) => setLoadError(errorMessage(error)));
    }
  };

  const step = (delta: number) => {
    if (visible.length === 0) return;
    const nextIndex = activeIndex < 0 ? 0 : Math.min(visible.length - 1, Math.max(0, activeIndex + delta));
    open(visible[nextIndex], true);
  };

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      const typing = target?.tagName === 'INPUT' || target?.tagName === 'TEXTAREA' || target?.tagName === 'SELECT' || target?.isContentEditable;
      if (e.key === 'Escape' && composeDraft) {
        setComposeDraft(null);
        return;
      }
      if (externalOpen) return;
      if (typing || composeDraft || e.ctrlKey || e.metaKey || e.altKey) return;
      const key = e.key.toLowerCase();
      if (key === '/' ) {
        e.preventDefault();
      } else if (key === 'j' || key === 'о' || e.key === 'ArrowDown') {
        e.preventDefault();
        step(1);
      } else if (key === 'k' || key === 'л' || e.key === 'ArrowUp') {
        e.preventDefault();
        step(-1);
      } else if (key === 'c' || key === 'с') {
        e.preventDefault();
        setComposeDraft({ to: '', subject: '', body: '' });
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  });

  const addSentMail = async (draft: ComposeDraft, files: File[]) => {
    if (demoMode) {
      setMails((prev) => [{
        id: `sent-${Date.now()}`,
        folder: 'SENT',
        from: t('Вы'),
        subject: draft.subject || t('(без темы)'),
        snippet: truncateGraphemes(draft.body, 110),
        body: draft.body,
        date: t('Сейчас'),
        timestamp: Date.now(),
        seen: true,
        flagged: false,
        hasAttachments: files.length > 0,
      }, ...prev]);
      return;
    }

    const attachments = await Promise.all(files.map(fileToAttachment));
    await mailApi.send({
      to: splitAddresses(draft.to),
      cc: splitAddresses(draft.cc),
      subject: draft.subject,
      bodyText: draft.body,
      attachments,
      saveToSent: true,
      externalAccountId: draft.externalAccountId ?? null,
    });

    const refreshed = await mailApi.folders();
    setRealFolders(refreshed);
    setServerFolderCounts(Object.fromEntries(refreshed.map((f) => [f.specialUse ?? f.id, f.unread])));
    const sentFolder = refreshed.find((f) => f.specialUse === 'SENT');
    if (sentFolder && folder === 'SENT') {
      const items = await mailApi.messages(sentFolder.id);
      setMails((prev) => [...prev.filter((m) => m.folder !== 'SENT'), ...items.map((item) => toMailView(item, 'SENT'))]);
    }
  };

  return (
    <div className="h-full lg:p-3 xl:p-4">
      <div className="flex h-full min-h-0 overflow-hidden bg-white lg:rounded-[18px] lg:border lg:border-[#e5e5ea] lg:shadow-soft">
        {/* Папки */}
        <aside className="hidden w-[238px] shrink-0 flex-col border-r border-[#e5e5ea] bg-chrome p-3 xl:flex">
          <div className="flex shrink-0 items-center justify-between px-2 pb-3 pt-1">
            <h1 className="text-[17px] font-semibold tracking-[-0.02em]">{t('Почта')}</h1>
            <span className="text-xs text-ink-soft">{countLabel(locale, folderCounts.INBOX ?? 0, ['новое', 'новых', 'новых'], ['new', 'new'])}</span>
          </div>
          <MailSidebarActions
            canAdminExternalAccounts={canAdminExternalAccounts}
            externalAccountCount={externalAccounts.length}
            onCompose={() => setComposeDraft({ to: '', subject: '', body: '' })}
            onOpenExternal={() => setExternalOpen(true)}
          />

          <p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-ink-soft mb-1.5 mt-6 shrink-0">{t('Папки')}</p>
          <nav className="scroll-area min-h-0 space-y-0.5 pr-1" aria-label={t('Папки почты')}>
            {folders.map((f) => {
              const Icon = FOLDER_ICONS[f.id] ?? Folder;
              const count = folderCounts[f.id] ?? 0;
              return (
                <button key={f.id} type="button" onClick={() => selectFolder(f.id)} className={`nav-row shrink-0 ${folder === f.id ? 'nav-row-active' : ''}`}>
                  <Icon size={16} strokeWidth={1.8} />
                  <span className="min-w-0 flex-1 truncate">{f.name}</span>
                  {count > 0 && <span className={`text-xs ${folder === f.id ? 'font-semibold text-[#0071e3]' : 'font-medium text-ink-soft'}`}>{count}</span>}
                </button>
              );
            })}
          </nav>

          <MailboxUsage quota={mailboxQuota} unavailable={mailboxQuotaUnavailable} />
        </aside>

        {/* Список писем */}
        <section className={`${mobileReading ? 'hidden md:flex' : 'flex'} relative min-w-0 flex-1 flex-col border-r border-[#e5e5ea] bg-white md:w-[322px] md:flex-none lg:w-[356px] xl:w-[392px]`}>
          <header className="shrink-0 border-b border-[#e5e5ea] px-4 pb-3 pt-4">
            <div className="flex items-center gap-2">
              <h2 className="min-w-0 flex-1 truncate text-[17px] font-semibold tracking-[-0.02em]">{currentFolder?.name ?? t('Почта')}</h2>
              <span className="text-xs text-ink-soft">{visible.length}</span>
              <div ref={sortPanelRef} className="relative">
                <IconButton
                  icon={ArrowUpDown}
                  label={t('Сортировка')}
                  className={`h-8 w-8 ${sortActive || openListPanel === 'sort' ? 'bg-[#e3f2fd] text-[#004c9a] hover:bg-[#e3f2fd]' : ''}`}
                  size={16}
                  onClick={() => setOpenListPanel((panel) => (panel === 'sort' ? null : 'sort'))}
                  aria-expanded={openListPanel === 'sort'}
                  aria-haspopup="dialog"
                />
                {sortActive && <span className="pointer-events-none absolute right-0.5 top-0.5 h-2 w-2 rounded-full bg-[#0071e3] ring-2 ring-[#000000]/10" aria-hidden="true" />}
                {openListPanel === 'sort' && (
                  <div role="dialog" aria-label={t('Сортировка')} className="absolute right-0 top-10 z-40 w-56 max-w-[calc(100vw-32px)] rounded-2xl border border-[#e5e5ea] bg-white p-1.5 shadow-[0_10px_20px_rgba(0,113,227,0.3)]">
                    {([
                      { value: 'date-desc', label: t('Сначала новые') },
                      { value: 'date-asc', label: t('Сначала старые') },
                      { value: 'sender-asc', label: t('По отправителю А–Я') },
                      { value: 'subject-asc', label: t('По теме А–Я') },
                    ] as const).map((option) => (
                      <button
                        key={option.value}
                        type="button"
                        onClick={() => {
                          setSortOrder(option.value);
                          setOpenListPanel(null);
                        }}
                        className={`nav-row ${sortOrder === option.value ? 'nav-row-active' : ''}`}
                        aria-pressed={sortOrder === option.value}
                      >
                        <span className="min-w-0 flex-1 truncate">{option.label}</span>
                        {sortOrder === option.value && <Check size={15} />}
                      </button>
                    ))}
                  </div>
                )}
              </div>
              {canAdminExternalAccounts && <IconButton icon={Settings2} label={t('Внешние ящики')} className="h-8 w-8 xl:hidden" size={16} onClick={() => setExternalOpen(true)} />}
              <div ref={filtersPanelRef} className="relative">
                <IconButton
                  icon={SlidersHorizontal}
                  label={t('Фильтры')}
                  className={`h-8 w-8 ${filtersActive || openListPanel === 'filters' ? 'bg-[#e3f2fd] text-[#004c9a] hover:bg-[#e3f2fd]' : ''}`}
                  size={16}
                  onClick={() => setOpenListPanel((panel) => (panel === 'filters' ? null : 'filters'))}
                  aria-expanded={openListPanel === 'filters'}
                  aria-haspopup="dialog"
                />
                {filtersActive && <span className="pointer-events-none absolute right-0.5 top-0.5 h-2 w-2 rounded-full bg-[#0071e3] ring-2 ring-[#000000]/10" aria-hidden="true" />}
                {openListPanel === 'filters' && (
                  <div role="dialog" aria-label={t('Фильтры')} className="absolute right-0 top-10 z-40 w-72 max-w-[calc(100vw-32px)] rounded-2xl border border-[#e5e5ea] bg-white p-3 shadow-[0_10px_20px_rgba(0,113,227,0.3)]">
                    <p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-ink-soft">{t('Состояние')}</p>
                    <div className="mt-2 space-y-1" role="group" aria-label={t('Фильтр писем')}>
                      {([
                        { value: 'all', label: t('Все') },
                        { value: 'unread', label: t('Непрочитанные') },
                        { value: 'read', label: t('Прочитанные') },
                      ] as const).map((option) => (
                        <button
                          key={option.value}
                          type="button"
                          onClick={() => setFilter(option.value)}
                          className={`nav-row ${filter === option.value ? 'nav-row-active' : ''}`}
                          aria-pressed={filter === option.value}
                        >
                          <span className="min-w-0 flex-1 truncate">{option.label}</span>
                          {filter === option.value && <Check size={15} className="shrink-0" />}
                        </button>
                      ))}
                    </div>
                    <div className="mt-4 space-y-2.5">
                      <label className="flex cursor-pointer items-center gap-2.5 text-sm text-ink-soft">
                        <input type="checkbox" checked={onlyFlagged} onChange={(event) => setOnlyFlagged(event.target.checked)} className="h-4 w-4 accent-[#0071e3]" />
                        <span>{t('Только важные')}</span>
                      </label>
                      <label className="flex cursor-pointer items-center gap-2.5 text-sm text-ink-soft">
                        <input type="checkbox" checked={onlyAttachments} onChange={(event) => setOnlyAttachments(event.target.checked)} className="h-4 w-4 accent-[#0071e3]" />
                        <span>{t('Только с вложениями')}</span>
                      </label>
                    </div>
                    <button
                      type="button"
                      onClick={resetMailFilters}
                      disabled={!filtersActive}
                      className="btn-ghost mt-4 w-full"
                    >
                      {t('Сбросить фильтры')}
                    </button>
                  </div>
                )}
              </div>
            </div>
            <div className="mt-3 flex rounded-full border border-[#e5e5ea] bg-white p-0.5" role="tablist" aria-label={t('Фильтр писем')}>
              {(['all', 'unread', 'read'] as const).map((value) => (
                <button key={value} type="button" role="tab" aria-selected={filter === value} onClick={() => setFilter(value)} className={`h-8 min-w-0 flex-1 truncate rounded-lg px-1 text-xs font-medium transition sm:text-[12px] ${filter === value ? 'bg-[#f2f2f7] text-ink font-medium shadow-[0_1px_0_rgba(0,0,0,0.04)]' : 'text-ink-soft hover:text-ink hover:bg-[#f2f2f7]'}`}>
                  {value === 'all' ? t('Все') : value === 'unread' ? t('Непрочитанные') : t('Прочитанные')}
                </button>
              ))}
            </div>
            {(onlyFlagged || onlyAttachments || sortActive) && (
              <div className="mt-2 flex flex-wrap gap-1.5">
                {onlyFlagged && (
                  <button type="button" onClick={() => setOnlyFlagged(false)} className="chip chip-accent" title={t('Сбросить фильтры')}>
                    {t('Только важные')} <X size={12} />
                  </button>
                )}
                {onlyAttachments && (
                  <button type="button" onClick={() => setOnlyAttachments(false)} className="chip chip-accent" title={t('Сбросить фильтры')}>
                    {t('Только с вложениями')} <X size={12} />
                  </button>
                )}
                {sortActive && (
                  <button type="button" onClick={() => setSortOrder('date-desc')} className="chip chip-accent" title={t('Сначала новые')}>
                    {sortOrder === 'date-asc' ? t('Сначала старые') : sortOrder === 'sender-asc' ? t('По отправителю А–Я') : t('По теме А–Я')} <X size={12} />
                  </button>
                )}
              </div>
            )}
            <div className="-mb-1 mt-3 flex gap-1.5 overflow-x-auto pb-1 xl:hidden">
              {folders.map((f) => (
                <button key={f.id} type="button" onClick={() => selectFolder(f.id)} className={`chip shrink-0 ${folder === f.id ? 'chip-accent' : ''}`}>{f.name}{(folderCounts[f.id] ?? 0) > 0 && ` · ${folderCounts[f.id]}`}</button>
              ))}
            </div>
          </header>

          <div className="scroll-area min-h-0 flex-1">
            {loadError && (
              <div className="m-4 rounded-xl border border-[#fecaca] bg-[#fef2f2] px-4 py-3 text-sm text-[#dc2626]" role="alert">{loadError}</div>
            )}
            {visible.map((m) => {
              const selected = active?.id === m.id;
              return (
                <div
                  key={m.id}
                  // Клик по строке — удобство для мыши. Семантический клавиатурный/
                  // screen-reader путь — настоящая кнопка темы ниже: role=button на
                  // контейнере создал бы недопустимую вложенную интерактивность со звездой.
                  onClick={() => open(m)}
                  className={`group relative flex cursor-pointer gap-3 border-b border-[#e5e5ea] px-4 py-3 transition duration-150 ${selected ? 'bg-[#f2f2f7]' : 'hover:bg-[#f2f2f7]'}`}
                >
                  {selected && <span className="absolute inset-y-3 left-0 w-[3px] rounded-full bg-[#0071e3]" />}
                  <Avatar name={m.from} size={38} />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      {!m.seen && <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-[#0071e3]" aria-label={t('Непрочитано')} />}
                      <span className={`min-w-0 flex-1 truncate text-sm ${m.seen ? 'font-medium text-ink-soft' : 'font-semibold text-ink'} tracking-tight`}>{cleanPersonName(m.from)}</span>
                      <span className="shrink-0 text-[11px] text-ink-soft">{m.date}</span>
                    </div>
                    <div className="mt-1 flex items-center gap-1.5">
                      <button type="button" onClick={(e) => { e.stopPropagation(); open(m); }} className={`min-w-0 flex-1 truncate text-left text-sm ${m.seen ? 'text-ink-soft' : 'font-semibold text-ink'} tracking-tight`}>{m.subject}</button>
                      {m.hasAttachments && <Paperclip size={13} className="shrink-0 text-ink-soft" />}
                      <button
                        type="button"
                        title={m.flagged ? t('Убрать из важных') : t('Отметить важным')}
                        aria-label={m.flagged ? t('Убрать из важных') : t('Отметить важным')}
                        onClick={(e) => { e.stopPropagation(); toggleFlag(m); }}
                        className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full transition ${m.flagged ? 'text-[#0071e3] bg-[#fdf2f2]' : 'text-ink-soft hover:bg-[#f2f2f7]'}`}
                      >
                        <Star size={13} fill={m.flagged ? 'currentColor' : 'none'} strokeWidth={m.flagged ? 0 : undefined} />
                      </button>
                    </div>
                    <p className="mt-0.5 line-clamp-2 text-[12.5px] leading-snug text-ink-soft">{m.snippet}</p>
                  </div>
                </div>
              );
            })}
            {visible.length === 0 && (
              <EmptyState icon={MailOpen} title={t('Здесь пока тихо')} description={search ? t('По этому запросу писем не нашлось. Попробуйте другое слово или сбросьте фильтр.') : t('В этой папке нет писем для выбранного фильтра.')} />
            )}
          </div>

          <button type="button" onClick={() => setComposeDraft({ to: '', subject: '', body: '' })} className="btn-primary absolute bottom-4 right-4 z-30 rounded-full shadow-[0_10px_20px_rgba(0,113,227,0.3)] md:hidden">
            <PenSquare size={16} /> {t('Написать')}
          </button>
        </section>

        {/* Чтение */}
        <main className={`${mobileReading ? 'flex' : 'hidden md:flex'} min-w-0 flex-1 flex-col bg-white`}>
          {active ? (
            <>
              <header className="flex h-14 shrink-0 items-center gap-1 border-b border-[#e5e5ea] bg-white px-4 sm:px-5 shadow-[0_1px_0_rgba(0,0,0,0.04)]">
                <button type="button" onClick={() => setMobileReading(false)} className="btn-ghost px-2 md:hidden"><ArrowLeft size={16} strokeWidth={1.6} /> {t('К списку')}</button>
                <IconButton icon={Archive} label={t('В архив')} onClick={() => moveActive('ARCHIVE')} size={17} strokeWidth={1.6} />
                <IconButton icon={Trash2} label={t('Удалить')} onClick={() => moveActive('TRASH')} size={17} strokeWidth={1.6} />
                <button type="button" onClick={() => toggleFlag(active)} className={`icon-btn ${active.flagged ? 'text-[#0071e3] bg-[#fdf2f2]' : ''}`} aria-label={t('Важное')}>
                  <Star size={17} fill={active.flagged ? 'currentColor' : 'none'} strokeWidth={active.flagged ? 0 : undefined} />
                </button>
                <IconButton icon={MoreHorizontal} label={t('Ещё действия')} size={17} strokeWidth={1.6} />
                <div className="ml-auto hidden items-center gap-1 sm:flex">
                  <span className="mr-1 hidden text-[11px] text-ink-soft xl:inline font-medium">{t('{current} из {total}', { current: Math.max(activeIndex + 1, 1), total: visible.length })}</span>
                  <IconButton icon={ChevronUp} label={t('Предыдущее письмо')} onClick={() => step(-1)} disabled={activeIndex <= 0} size={17} strokeWidth={1.6} />
                  <IconButton icon={ChevronDown} label={t('Следующее письмо')} onClick={() => step(1)} disabled={activeIndex < 0 || activeIndex >= visible.length - 1} size={17} strokeWidth={1.6} />
                </div>
              </header>

              <div className="scroll-area min-h-0 flex-1">
                <article className="mx-auto w-full max-w-[760px] animate-fade-in px-5 py-7 sm:px-8 sm:py-9">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="chip chip-accent">{currentFolder?.name ?? active.folder}</span>
                    {active.flagged && <span className="chip">{t('Важное')}</span>}
                  </div>
                  <h2 className="mt-5 text-[26px] font-semibold leading-tight tracking-[-0.03em] text-ink sm:text-[30px]">{active.subject}</h2>

                  <div className="mt-8 flex items-start gap-3 border-b border-[#e5e5ea] pb-6">
                    <Avatar name={active.from} size={44} />
                    <div className="min-w-0 flex-1">
                      <div className="truncate text-[15px] font-semibold text-ink tracking-tight">{cleanPersonName(active.from)}</div>
                      <div
                        className="mt-0.5 truncate text-[12.5px] text-ink-soft"
                        title={`${t('Кому')}: ${recipientText}${ccText ? ` · ${t('Копия')}: ${ccText}` : ''}`}
                      >
                        {emailFromHeader(active.from) || t('внутренний отправитель')} · {t('Кому')}: {recipientText}
                        {ccText ? ` · ${t('Копия')}: ${ccText}` : ''}
                      </div>
                    </div>
                    <time className="shrink-0 text-[12.5px] text-ink-soft">{active.date}</time>
                  </div>

                  {active.hasAttachments && <MailAttachments
                    demoMode={demoMode}
                    attachments={loadedAttachments[active.id]}
                    busy={downloadBusy !== null}
                    onDownload={(attachment) => void downloadAttachment(active.id, attachment)}
                  />}

                  {activeHtml && <div className="mt-5 flex flex-wrap items-center gap-2 text-[11px] text-ink-soft">
                    <span>{t('Внешние изображения и ссылки отключены для безопасности.')}</span>
                    <button type="button" className="font-medium text-[#0071e3] underline decoration-accent/30" onClick={() => setShowPlainText(!showPlainText)}>{t(showPlainText ? 'Показать HTML' : 'Показать обычный текст')}</button>
                  </div>}
                  {activeHtml && !showPlainText ? <iframe title={t('Содержимое HTML-письма')} sandbox="" referrerPolicy="no-referrer" srcDoc={mailHtmlDocument(activeHtml)} className="mt-4 h-[420px] w-full rounded-xl border border-[#e5e5ea] bg-white" />
                    : <div className="mt-7 text-[15px] leading-[1.7] text-ink-soft">
                      {activeBody.split('\n').map((paragraph, i) => paragraph.trim() ? <p key={i} className="mb-4 whitespace-pre-wrap">{paragraph}</p> : null)}
                    </div>}

                  <div className="mt-10 flex flex-wrap gap-2 border-t border-[#e5e5ea] pt-6">
                    <button type="button" className="btn-primary" onClick={() => setComposeDraft({ to: emailFromHeader(active.from), subject: `Re: ${active.subject}`, body: '' })}>
                      <Reply size={17} strokeWidth={1.6} /> {t('Ответить')}
                    </button>
                    <button type="button" className="btn-secondary" onClick={() => setComposeDraft({ to: '', subject: `Fwd: ${active.subject}`, body: `\n\n--- ${t('Пересылаемое письмо')} ---\n${activeBody}` })}>
                      <Forward size={17} strokeWidth={1.6} /> {t('Переслать')}
                    </button>
                    <button type="button" className="btn-ghost" onClick={() => {
                      const date = new Date(active.timestamp).toISOString().split('T')[0];
                      navigate(`/calendar?createEvent=1&title=${encodeURIComponent(active.subject)}&date=${date}&description=${encodeURIComponent(active.body)}`);
                    }}>
                      <Calendar size={17} strokeWidth={1.6} /> {t('Создать событие')}
                    </button>
                    <button type="button" className="btn-ghost ml-auto" onClick={() => moveActive('ARCHIVE')}>
                      <Archive size={17} strokeWidth={1.6} /> {t('В архив')}
                    </button>
                  </div>
                </article>
              </div>
            </>
          ) : (
            <div className="flex min-h-0 flex-1 flex-col">
              <EmptyState icon={Inbox} title={t('Выберите письмо')} description={t('Откройте письмо из списка или двигайтесь по нему клавишами J и K.')} />
              <div className="-mt-20 flex items-center justify-center gap-4 pb-12 text-[11px] text-ink-soft">
                <span className="flex items-center gap-1.5"><span className="kbd">J</span><span className="kbd">K</span> {t('навигация')}</span>
                <span className="flex items-center gap-1.5"><span className="kbd">C</span> {t('написать')}</span>
                <span className="flex items-center gap-1.5"><span className="kbd">/</span> {t('поиск')}</span>
              </div>
            </div>
          )}
        </main>
      </div>

      {externalOpen && canAdminExternalAccounts && (
        <ExternalMailAccountsModal
          demoMode={demoMode}
          canAdmin={canAdminExternalAccounts}
          currentOwner={sessionUser ? {
            id: sessionUser.id,
            username: sessionUser.username ?? '',
            email: sessionUser.email,
            displayName: sessionUser.displayName,
            department: sessionUser.department ?? null,
            status: sessionUser.status ?? 'ACTIVE',
            externalAccountCount: externalAccounts.length,
          } : null}
          onClose={() => setExternalOpen(false)}
          onAccountsChanged={setExternalAccounts}
        />
      )}

      {composeDraft && (
        <ComposePanel
          key={`${composeDraft.to}-${composeDraft.subject}`}
          initial={composeDraft}
          currentEmail={sessionUser?.email ?? ''}
          externalAccounts={externalAccounts}
          onClose={() => setComposeDraft(null)}
          onSend={addSentMail}
        />
      )}
    </div>
  );
}

export function ComposePanel({ initial, currentEmail, externalAccounts, onClose, onSend }: {
  initial: ComposeDraft;
  currentEmail: string;
  externalAccounts: ExternalMailAccount[];
  onClose: () => void;
  onSend: (draft: ComposeDraft, files: File[]) => Promise<void>;
}) {
  const { t } = useI18n();
  const [draft, setDraft] = useState(initial);
  const [showCc, setShowCc] = useState(Boolean(initial.cc));
  const [files, setFiles] = useState<File[]>([]);
  const [draggingFiles, setDraggingFiles] = useState(false);
  const [sent, setSent] = useState(false);
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const bodyRef = useRef<HTMLTextAreaElement>(null);
  const sendAsAccounts = externalAccounts.filter((account) => account.enabled && account.sendEnabled && account.smtpEnabled);

  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    void (async () => {
      setSending(true);
      setSendError(null);
      try {
        await onSend(draft, files);
        setSent(true);
        window.setTimeout(onClose, 900);
      } catch (error) {
        setSendError(errorMessage(error));
      } finally {
        setSending(false);
      }
    })();
  };

  return (
    <section className="fixed inset-x-0 bottom-0 z-50 flex max-h-[94dvh] animate-rise-in flex-col overflow-hidden border border-[#e5e5ea] bg-white shadow-[0_10px_40px_rgba(0,0,0,0.10)] sm:inset-x-auto sm:bottom-4 sm:right-4 sm:h-[min(640px,92dvh)] sm:w-[min(590px,calc(100vw-2rem))] sm:rounded-2xl" aria-label={t('Новое письмо')}>
      <header className="flex h-12 shrink-0 items-center justify-between border-b border-[#e5e5ea] bg-white px-4 py-2">
        <h3 className="text-[14px] font-medium text-ink tracking-tight">{initial.subject ? t('Ответ / пересылка') : t('Новое письмо')}</h3>
        <button type="button" onClick={onClose} className="h-8 w-8 flex items-center justify-center rounded-lg text-ink-soft hover:bg-[#f2f2f7] hover:text-ink transition" aria-label={t('Закрыть черновик')}>
          <X size={15} strokeWidth={1.7} />
        </button>
      </header>

      {sent ? (
        <div className="flex flex-1 flex-col items-center justify-center px-8 text-center">
          <span className="flex h-14 w-14 items-center justify-center rounded-full bg-[#f2f2f7] text-[#0071e3]"><CheckCircle2 size={27} strokeWidth={2} /></span>
          <h4 className="mt-4 text-lg font-semibold text-ink">{t('Письмо отправлено')}</h4>
          <p className="mt-1 text-sm text-ink-soft">{t('Копия сохранена в папке «Отправленные».')}</p>
        </div>
      ) : (
        <form onSubmit={submit} className={`flex min-h-0 flex-1 flex-col ${draggingFiles ? 'ring-2 ring-inset ring-accent' : ''}`}
          onDragOver={(event) => { if (Array.from(event.dataTransfer.types).includes('Files')) { event.preventDefault(); setDraggingFiles(true); } }}
          onDragLeave={(event) => { if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDraggingFiles(false); }}
          onDrop={(event) => { event.preventDefault(); setDraggingFiles(false); const dropped = Array.from(event.dataTransfer.files); if (dropped.length) setFiles((previous) => [...previous, ...dropped]); }}>
          {draggingFiles && <div className="bg-[#f2f2f7] px-4 py-2 text-sm text-[#0071e3]">{t('Отпустите файлы, чтобы прикрепить')}</div>}
          {sendAsAccounts.length > 0 && (
            <div className="flex h-11 shrink-0 items-center gap-3 border-b border-[#e5e5ea] px-4">
              <label htmlFor="compose-from" className="w-12 shrink-0 text-[12px] font-medium text-ink-soft">{t('От кого')}</label>
              <select
                id="compose-from"
                value={draft.externalAccountId ?? ''}
                onChange={(e) => setDraft({ ...draft, externalAccountId: e.target.value || null })}
                className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none focus:ring-0 text-ink"
              >
                <option value="">{currentEmail} · Corvex</option>
                {sendAsAccounts.map((account) => (
                  <option key={account.id} value={account.id}>{account.emailAddress} · {account.label}</option>
                ))}
              </select>
            </div>
          )}
          <div className="flex h-11 shrink-0 items-center gap-3 border-b border-[#e5e5ea] px-4">
            <label htmlFor="compose-to" className="w-12 shrink-0 text-[12px] font-medium text-ink-soft">{t('Кому')}</label>
            <input id="compose-to" required type="text" inputMode="email" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} placeholder="user@company.local" className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none placeholder:text-ink-soft text-ink" />
            {!showCc && <button type="button" onClick={() => setShowCc(true)} className="shrink-0 text-[11px] font-medium text-ink-soft hover:text-ink">{t('Копия')}</button>}
          </div>
          {showCc && (
            <div className="flex h-11 shrink-0 items-center gap-3 border-b border-[#e5e5ea] px-4">
              <label htmlFor="compose-cc" className="w-12 shrink-0 text-[12px] font-medium text-ink-soft">{t('Копия')}</label>
              <input id="compose-cc" type="text" inputMode="email" value={draft.cc ?? ''} onChange={(e) => setDraft({ ...draft, cc: e.target.value })} placeholder="user@company.local" className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none placeholder:text-ink-soft text-ink" />
            </div>
          )}
          <div className="flex h-11 shrink-0 items-center gap-3 border-b border-[#e5e5ea] px-4">
            <label htmlFor="compose-subject" className="w-12 shrink-0 text-[12px] font-medium text-ink-soft">{t('Тема')}</label>
            <input id="compose-subject" required value={draft.subject} onChange={(e) => setDraft({ ...draft, subject: e.target.value })} placeholder={t('О чём письмо?')} className="min-w-0 flex-1 border-0 bg-transparent text-sm font-medium outline-none placeholder:font-normal placeholder:text-ink-soft text-ink" />
          </div>
          <textarea
            ref={bodyRef}
            required
            value={draft.body}
            onChange={(e) => setDraft({ ...draft, body: e.target.value })}
            placeholder={t('Напишите сообщение…')}
            className="min-h-[160px] flex-1 resize-none border-0 bg-transparent px-4 py-4 text-[14px] leading-relaxed outline-none placeholder:text-ink-soft text-ink focus:ring-0"
          />
          {files.length > 0 && (
            <div className="flex shrink-0 flex-wrap gap-2 px-4 pb-3">
              {files.map((file, index) => <span key={`${file.name}-${index}`} className="chip"><Paperclip size={11} strokeWidth={1.5} />{file.name}<button type="button" className="ml-1 rounded-full p-0 hover:bg-[#f2f2f7] hover:text-[#ff3b30] transition" aria-label={`${t('Удалить вложение')}: ${file.name}`} onClick={() => setFiles((current) => current.filter((_, i) => i !== index))}><X size={11} strokeWidth={1.5} /></button></span>)}
            </div>
          )}
          {sendError && (
            <div className="mx-4 mb-3 shrink-0 rounded-xl border border-[#e5e5ea] bg-[#f2f2f7] px-4 py-3 text-sm text-ink" role="alert">{sendError}</div>
          )}
          <footer className="flex shrink-0 items-center justify-between gap-2 border-t border-[#e5e5ea] px-4 py-3 bg-[#f2f2f7]">
            <div className="flex items-center gap-2">
              <input ref={fileRef} type="file" multiple className="hidden" onChange={(e) => { const picked = Array.from(e.target.files ?? []); setFiles((previous) => [...previous, ...picked]); e.target.value = ''; }} />
              <IconButton icon={Paperclip} label={t('Прикрепить файлы')} onClick={() => fileRef.current?.click()} size={17} strokeWidth={1.6} />
              <span className="hidden sm:inline text-[11px] text-ink-soft">{t('Подпись добавится автоматически')}</span>
            </div>
            <button className="btn-accent" disabled={sending}><Send size={15} strokeWidth={1.7} /> {t('Отправить')}</button>
            <IconButton icon={Trash2} label={t('Удалить черновик')} className="ml-auto" onClick={onClose} size={17} strokeWidth={1.6} />
          </footer>
        </form>
      )}
    </section>
  );
}
