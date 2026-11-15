import { FormEvent, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Archive,
  ArrowLeft,
  ArrowUpDown,
  Calendar as CalendarIcon,
  CalendarPlus,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  FilePenLine,
  Folder,
  FolderInput,
  FolderPlus,
  Forward,
  Inbox,
  Mail,
  MailOpen,
  Minus,
  OctagonAlert,
  Paperclip,
  PenSquare,
  Reply,
  ReplyAll,
  Search,
  Send,
  Settings2,
  SlidersHorizontal,
  Sparkles,
  Star,
  Trash2,
  X,
} from 'lucide-react';
import { demoFolders, demoMails, DemoMail } from '../../api/demoData';
import { externalMailApi, ExternalMailAccount } from '../../api/externalMailApi';
import { ApiError } from '../../api/httpClient';
import {
  mailApi,
  MailboxQuota,
  MailFolderView,
  MailMessageListItem,
  MailAttachmentView,
  SendAttachment,
} from '../../api/mailApi';
import { CORVEX_MAIL_DND_MIME } from '../../components/CalendarSidebarWidget';
import { EmojiPickerButton } from '../../components/EmojiPicker';
import SidebarSlot, { useSidebarSlotContext } from '../../components/SidebarSlot';
import { Avatar, EmptyState, IconButton, cleanPersonName, emailFromHeader } from '../../components/ui';
import WorkspaceUtilityBar from '../../components/WorkspaceUtilityBar';
import { formatDateTime, useI18n } from '../../i18n';
import { insertTextAtSelection, truncateGraphemes } from '../../lib/text';
import {
  buildEventDraftFromMail,
  CalendarEventDraft,
  useCalendarStore,
} from '../../store/calendarStore';
import { demoMailIsSeen, useSession } from '../../store/sessionStore';
import EventModal from '../calendar/EventModal';
import ExternalMailAccountsModal from './ExternalMailAccountsModal';
import CreateFolderModal from './CreateFolderModal';
import MailSettingsModal from './MailSettingsModal';
import { bodyWithSignature, loadMailPreferences, mailPreferencesKey, saveMailPreferences, type MailPreferences } from './mailPreferences';
import { useMailNotifications } from './useMailNotifications';
import { canAdministerExternalMail, canUseExternalMail } from './externalMailAccess';
import MailAttachments from './MailAttachments';
import MailboxUsage from './MailboxUsage';
import { mailHtmlDocument } from './mailHtml';
import {
  applyMailListControls,
  hasActiveMailFilters,
  type MailReadFilter,
  type MailSortOrder,
} from './mailListControls';
import { formatMailRecipients, replyAllCc } from './mailRecipients';
import { activeMailAfterRefresh, replaceFolderMessages } from './mailRefresh';
import MailSidebarActions from './MailSidebarActions';

export interface ComposeDraft {
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

function MoveToFolder({ folders, current, onMove, disabled = false }: { folders: FolderTab[]; current: string; onMove: (folder: string) => void; disabled?: boolean }) {
  const { t } = useI18n();
  return (
    <label title={t('Переместить в папку')} className={`icon-btn relative inline-flex shrink-0 focus-within:ring-2 focus-within:ring-accent ${disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'}`}>
      <FolderInput size={17} strokeWidth={1.8} aria-hidden="true" />
      <select
        aria-label={t('Переместить в папку')}
        value=""
        disabled={disabled}
        onChange={(event) => { if (event.target.value) onMove(event.target.value); }}
        className="absolute inset-0 h-full w-full cursor-pointer opacity-0 disabled:cursor-not-allowed"
      >
        <option value="" disabled>{t('Переместить в папку')}</option>
        {folders.map((item) => <option key={item.id} value={item.id} disabled={item.id === current}>{item.name}</option>)}
      </select>
    </label>
  );
}

export default function MailPage() {
  const { locale, t } = useI18n();
  const navigate = useNavigate();
  const { onOpenCommandPalette, onOpenCalendarWidget, onOpenSecurity, onCloseNavigation } = useSidebarSlotContext();

  const demoMode = useSession((s) => s.demoMode);
  const sessionUser = useSession((s) => s.user);
  const demoReadMailIds = useSession((s) => s.demoReadMailIds);
  const demoUnreadMailIds = useSession((s) => s.demoUnreadMailIds);
  const markDemoMailRead = useSession((s) => s.markDemoMailRead);
  const markDemoMailUnread = useSession((s) => s.markDemoMailUnread);
  const preferenceKey = mailPreferencesKey(sessionUser?.id, demoMode);
  const [preferenceState, setPreferenceState] = useState(() => ({ key: preferenceKey, value: loadMailPreferences(preferenceKey) }));
  const preferences = preferenceState.key === preferenceKey ? preferenceState.value : loadMailPreferences(preferenceKey);

  useEffect(() => {
    setPreferenceState({ key: preferenceKey, value: loadMailPreferences(preferenceKey) });
  }, [preferenceKey]);

  const calendarEvents = useCalendarStore((s) => s.events);
  const upsertCalendarEvent = useCalendarStore((s) => s.upsertEvent);
  const setCalendarSelectedDate = useCalendarStore((s) => s.setSelectedDate);

  const [realFolders, setRealFolders] = useState<MailFolderView[]>([]);
  const [demoCustomFolders, setDemoCustomFolders] = useState<FolderTab[]>([]);
  const [foldersStatus, setFoldersStatus] = useState<'loading' | 'ready' | 'error'>(demoMode ? 'ready' : 'loading');
  const [mailboxQuota, setMailboxQuota] = useState<MailboxQuota | null>(null);
  const [mailboxQuotaUnavailable, setMailboxQuotaUnavailable] = useState(false);

  const folders = useMemo<FolderTab[]>(() => {
    const localized = demoFolders(locale);
    if (demoMode) return [...localized, ...demoCustomFolders];
    return realFolders.map((f) => {
      const key = f.specialUse ?? f.id;
      return { id: key, name: localized.find((d) => d.id === key)?.name ?? f.name, unread: f.unread };
    });
  }, [demoMode, demoCustomFolders, locale, realFolders]);
  useMailNotifications(preferences.notifications, demoMode, realFolders.find((item) => item.specialUse === 'INBOX')?.id);

  const [searchParams, setSearchParams] = useSearchParams();
  const [folder, setFolder] = useState('INBOX');
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState<MailReadFilter>('all');
  const [onlyFlagged, setOnlyFlagged] = useState(false);
  const [onlyAttachments, setOnlyAttachments] = useState(false);
  const [sortOrder, setSortOrder] = useState<MailSortOrder>(() => preferences.defaultSort);
  const [openListPanel, setOpenListPanel] = useState<'sort' | 'filters' | null>(null);
  const [mails, setMails] = useState<DemoMail[]>(() =>
    demoMode
      ? demoMails(locale).map((mail) => ({ ...mail, seen: demoMailIsSeen(mail, demoReadMailIds, demoUnreadMailIds) }))
      : [],
  );
  const [activeId, setActiveId] = useState<string>(demoMode ? 'm1' : '');
  const [mobileReading, setMobileReading] = useState(false);
  const [composeDraft, setComposeDraft] = useState<ComposeDraft | null>(null);
  const [externalAccounts, setExternalAccounts] = useState<ExternalMailAccount[]>([]);
  const [externalOpen, setExternalOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [createFolderOpen, setCreateFolderOpen] = useState(false);
  const [movingMailIds, setMovingMailIds] = useState<Set<string>>(() => new Set());
  const [eventModalDraft, setEventModalDraft] = useState<CalendarEventDraft | null>(null);
  const [calendarSavedNotice, setCalendarSavedNotice] = useState<string | null>(null);

  const canExternalAccounts = canUseExternalMail(sessionUser?.permissions);
  const canAdminExternalAccounts = canAdministerExternalMail(sessionUser?.permissions);
  const canManageFolders = sessionUser?.permissions.includes('MAIL_MANAGE_FOLDERS') ?? false;
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
  const searchRef = useRef<HTMLInputElement>(null);
  const sortPanelRef = useRef<HTMLDivElement>(null);
  const filtersPanelRef = useRef<HTMLDivElement>(null);
  const seenPendingRef = useRef<Set<string>>(new Set());
  const seenQueueRef = useRef<Map<string, Promise<void>>>(new Map());
  const seenDesiredRef = useRef<Map<string, boolean>>(new Map());
  const seenConfirmedRef = useRef<Map<string, boolean>>(new Map());
  const seenVersionRef = useRef<Map<string, number>>(new Map());
  const seenRevisionRef = useRef(0);
  const flagQueueRef = useRef<Map<string, Promise<void>>>(new Map());
  const flagDesiredRef = useRef<Map<string, boolean>>(new Map());
  const flagConfirmedRef = useRef<Map<string, boolean>>(new Map());
  const flagVersionRef = useRef<Map<string, number>>(new Map());
  const mutationQueueRef = useRef<Map<string, Promise<void>>>(new Map());
  const movingMailIdsRef = useRef<Set<string>>(new Set());
  const mailRevisionRef = useRef(0);
  const folderSnapshotRef = useRef(0);
  const messageSnapshotRef = useRef<Map<string, number>>(new Map());
  const suppressAutoReadRef = useRef<string | null>(null);
  const folderRevisionRef = useRef(0);
  const linkedMailRef = useRef<DemoMail | null>(null);
  const calendarDragTimerRef = useRef<number | null>(null);

  useEffect(() => () => {
    if (calendarDragTimerRef.current !== null) window.clearTimeout(calendarDragTimerRef.current);
  }, []);

  useEffect(() => {
    setSortOrder(loadMailPreferences(preferenceKey).defaultSort);
    setDemoCustomFolders([]);
    const onStorage = (event: StorageEvent) => {
      if (event.key !== preferenceKey) return;
      const value = loadMailPreferences(preferenceKey);
      setPreferenceState({ key: preferenceKey, value });
      setSortOrder(value.defaultSort);
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [preferenceKey]);

  const visible = useMemo(
    () =>
      applyMailListControls(
        mails,
        {
          folder,
          search,
          readFilter: filter,
          onlyFlagged,
          onlyAttachments,
          sortOrder,
        },
        locale,
      ),
    [mails, folder, search, filter, onlyFlagged, onlyAttachments, sortOrder, locale],
  );

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

  const openDraft = (draft: ComposeDraft, kind: 'new' | 'reply' | 'forward') => {
    setComposeDraft({ ...draft, body: bodyWithSignature(draft.body, preferences, kind) });
  };

  const active = mails.find((m) => m.id === activeId && m.folder === folder) ?? visible[0] ?? null;
  const currentViewRef = useRef<{ folder: string; activeId: string | null }>({ folder, activeId: active?.id ?? null });
  useLayoutEffect(() => {
    currentViewRef.current = { folder, activeId: active?.id ?? null };
  }, [folder, active?.id]);
  const activeBody = active ? (loadedBodies[active.id] ?? active.body) : '';
  const activeHtml = active ? loadedHtml[active.id] : null;
  useEffect(() => {
    setShowPlainText(false);
  }, [active?.id]);

  // Calendar events linked to active email
  const linkedCalendarEvents = useMemo(
    () => (active ? calendarEvents.filter((ev) => ev.sourceMailId === active.id) : []),
    [active, calendarEvents],
  );

  const scheduleFromMail = (mail: DemoMail) => {
    const bodyText = loadedBodies[mail.id] ?? mail.body ?? mail.snippet;
    const draft = buildEventDraftFromMail({
      id: mail.id,
      subject: mail.subject,
      from: mail.from,
      body: bodyText,
      flagged: mail.flagged,
    });
    setEventModalDraft(draft);
  };

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
  const recipientText = active
    ? formatMailRecipients(
        demoMode ? [sessionUser?.email ?? ''] : activeRecipientData?.to,
        recipientIdentity,
        recipientFallbacks,
      )
    : '';
  const ccText =
    active && !demoMode && activeRecipientData?.cc.length
      ? formatMailRecipients(activeRecipientData.cc, recipientIdentity, recipientFallbacks)
      : '';
  const replyAllReady = demoMode || activeRecipientData !== undefined;
  const replyCc = active
    ? replyAllCc(active.from, activeRecipientData?.to ?? [], activeRecipientData?.cc ?? [], sessionUser?.email)
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
      seen: seenDesiredRef.current.get(item.id) ?? item.seen,
      flagged: flagDesiredRef.current.get(item.id) ?? item.flagged,
      hasAttachments: item.hasAttachments,
    };
  };

  const beginFolderSnapshot = () => ++folderSnapshotRef.current;
  const beginMessageSnapshot = (folderId: string) => {
    const sequence = (messageSnapshotRef.current.get(folderId) ?? 0) + 1;
    messageSnapshotRef.current.set(folderId, sequence);
    return sequence;
  };

  // Changes to one message must reach the server in user order. This also prevents
  // a move and a flag update from overwriting each other in the same session.
  const enqueueMessageMutation = <T,>(id: string, task: () => Promise<T>): Promise<T> => {
    const previous = mutationQueueRef.current.get(id);
    const result = (previous ?? Promise.resolve()).then(task);
    const tail = result.then(() => undefined, () => undefined);
    mutationQueueRef.current.set(id, tail);
    void tail.then(() => {
      if (mutationQueueRef.current.get(id) === tail) mutationQueueRef.current.delete(id);
    });
    return result;
  };

  useEffect(() => {
    if (!demoMode) return;
    const localized = demoMails(locale);
    setMails((prev) =>
      prev.map((mail) => {
        const translated = localized.find((candidate) => candidate.id === mail.id);
        return translated ? { ...translated, folder: mail.folder, seen: mail.seen, flagged: mail.flagged } : mail;
      }),
    );
  }, [locale, demoMode]);

  useEffect(() => {
    if (demoMode) {
      setFoldersStatus('ready');
      return;
    }
    let cancelled = false;
    setFoldersStatus('loading');
    const load = () => {
      const sequence = beginFolderSnapshot();
      const folderRevision = folderRevisionRef.current;
      const seenRevision = seenRevisionRef.current;
      const mailRevision = mailRevisionRef.current;
      void mailApi.folders().then((items) => {
        if (cancelled) return;
        if (sequence !== folderSnapshotRef.current || folderRevision !== folderRevisionRef.current
            || seenRevision !== seenRevisionRef.current || mailRevision !== mailRevisionRef.current) {
          load();
          return;
        }
        setRealFolders(items);
        setFoldersStatus('ready');
        setServerFolderCounts(Object.fromEntries(items.map((f) => [f.specialUse ?? f.id, f.unread])));
        const inbox = items.find((f) => f.specialUse === 'INBOX') ?? items[0];
        if (inbox) setFolder(inbox.specialUse ?? inbox.id);
      }).catch((error) => {
        if (cancelled) return;
        if (sequence !== folderSnapshotRef.current) { load(); return; }
        setFoldersStatus('error');
        setLoadError(errorMessage(error));
      });
    };
    load();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
    const timer = window.setInterval(() => {
      void refresh();
    }, MAIL_REFRESH_INTERVAL_MS);
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
    externalMailApi
      .list()
      .then((items) => {
        if (!cancelled) setExternalAccounts(items);
      })
      .catch(() => {
        if (!cancelled) setExternalAccounts([]);
      });
    return () => {
      cancelled = true;
    };
  }, [demoMode, canExternalAccounts]);

  useEffect(() => {
    if (!canAdminExternalAccounts) setExternalOpen(false);
  }, [canAdminExternalAccounts]);

  const selectedRealFolder = realFolders.find((f) => (f.specialUse ?? f.id) === folder);
  const selectedRealFolderId = selectedRealFolder?.id;

  useEffect(() => {
    if (demoMode || !selectedRealFolderId || movingMailIds.size > 0) return;
    let cancelled = false;
    const load = () => {
      const seenRevision = seenRevisionRef.current;
      const mailRevision = mailRevisionRef.current;
      const sequence = beginMessageSnapshot(selectedRealFolderId);
      void mailApi.messages(selectedRealFolderId).then((items) => {
        if (cancelled || sequence !== messageSnapshotRef.current.get(selectedRealFolderId)) return;
        // Retry a snapshot invalidated by a completed local mutation. A move
        // in progress is retried when its busy state clears instead.
        if (seenRevision !== seenRevisionRef.current || mailRevision !== mailRevisionRef.current) {
          if (movingMailIdsRef.current.size === 0) load();
          return;
        }
        if (movingMailIdsRef.current.size > 0) return;
        const mapped = items.map((item) => toMailView(item, folder));
        const linked = linkedMailRef.current;
        if (linked?.folder === folder && !mapped.some((mail) => mail.id === linked.id)) mapped.unshift(linked);
        setMails((prev) => replaceFolderMessages(prev, folder, mapped));
        setActiveId((current) => linked?.folder === folder ? linked.id : mapped.some((mail) => mail.id === current) ? current : mapped[0]?.id ?? '');
      }).catch((error) => {
        if (!cancelled && sequence === messageSnapshotRef.current.get(selectedRealFolderId)) setLoadError(errorMessage(error));
      });
    };
    load();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [demoMode, folder, selectedRealFolderId, locale, movingMailIds.size]);

  useEffect(() => {
    if (demoMode || !selectedRealFolderId || movingMailIds.size > 0) return;
    let cancelled = false;
    let refreshing = false;

    const refresh = async () => {
      if (cancelled || refreshing || movingMailIdsRef.current.size > 0 || document.visibilityState === 'hidden') return;
      refreshing = true;
      const folderRevision = folderRevisionRef.current;
      const seenRevision = seenRevisionRef.current;
      const mailRevision = mailRevisionRef.current;
      const folderSequence = beginFolderSnapshot();
      const sequence = beginMessageSnapshot(selectedRealFolderId);
      try {
        const [folderItems, messageItems] = await Promise.all([
          mailApi.folders(),
          mailApi.messages(selectedRealFolderId),
        ]);
        if (cancelled || seenRevision !== seenRevisionRef.current || mailRevision !== mailRevisionRef.current
            || movingMailIdsRef.current.size > 0 || sequence !== messageSnapshotRef.current.get(selectedRealFolderId)) return;
        const mapped = messageItems.map((item) => toMailView(item, folder));
        const linked = linkedMailRef.current;
        if (linked?.folder === folder && !mapped.some((mail) => mail.id === linked.id)) mapped.unshift(linked);
        if (folderRevision === folderRevisionRef.current && folderSequence === folderSnapshotRef.current) {
          setRealFolders(folderItems);
          // The server snapshot may predate an in-flight optimistic flag change.
          if (seenQueueRef.current.size === 0) {
            setServerFolderCounts(Object.fromEntries(folderItems.map((f) => [f.specialUse ?? f.id, f.unread])));
          }
        }
        setMails((prev) => replaceFolderMessages(prev, folder, mapped));
        setActiveId((current) => activeMailAfterRefresh(current, mapped));
        setLoadError(null);
      } catch (error) {
        if (!cancelled && sequence === messageSnapshotRef.current.get(selectedRealFolderId)) setLoadError(errorMessage(error));
      } finally {
        refreshing = false;
      }
    };

    const timer = window.setInterval(() => {
      void refresh();
    }, MAIL_REFRESH_INTERVAL_MS);
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') void refresh();
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [demoMode, folder, selectedRealFolderId, locale, movingMailIds.size]);

  const activeRealId = !demoMode && active ? active.id : null;
  useEffect(() => {
    if (!activeRealId || loadedBodies[activeRealId] != null) return;
    let cancelled = false;
    mailApi
      .message(activeRealId)
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
      .catch((error) => {
        if (!cancelled) setLoadError(errorMessage(error));
      });
    return () => {
      cancelled = true;
    };
  }, [activeRealId, loadedBodies]);

  // Consume commands separately so a source-email link survives the initial folder load.
  useEffect(() => {
    const openCompose = searchParams.get('compose') === '1';
    const openExternalAccounts = searchParams.get('externalAccounts') === '1';
    const openSettings = searchParams.get('settings') === '1';
    if (openCompose) openDraft({ to: '', subject: '', body: '' }, 'new');
    if (openExternalAccounts && canAdminExternalAccounts) setExternalOpen(true);
    if (openSettings) setSettingsOpen(true);
    if (openCompose || openExternalAccounts || openSettings) {
      const next = new URLSearchParams(searchParams);
      next.delete('compose');
      next.delete('externalAccounts');
      next.delete('settings');
      setSearchParams(next, { replace: true });
    }
  }, [canAdminExternalAccounts, searchParams, setSearchParams]);

  useEffect(() => {
    const targetMailId = searchParams.get('mailId');
    if (!targetMailId) return undefined;
    // Let the other URL commands be consumed first so simultaneous replacements
    // cannot restore a previously removed query parameter.
    if (searchParams.get('compose') === '1' || searchParams.get('externalAccounts') === '1' || searchParams.get('settings') === '1') return undefined;
    let cancelled = false;
    const consumeLink = () => {
      const next = new URLSearchParams(searchParams);
      next.delete('mailId');
      setSearchParams(next, { replace: true });
    };
    if (demoMode) {
      const mail = mails.find((item) => item.id === targetMailId);
      if (mail) {
        linkedMailRef.current = mail;
        setSearch('');
        resetMailFilters();
        setFolder(mail.folder);
        setActiveId(mail.id);
        setMobileReading(true);
      } else setLoadError(t('Не удалось открыть исходное письмо.'));
      consumeLink();
      return undefined;
    }
    if (foldersStatus === 'loading') return undefined;
    if (foldersStatus === 'error' || realFolders.length === 0) {
      setLoadError(t('Не удалось открыть исходное письмо: папки почты недоступны.'));
      consumeLink();
      return undefined;
    }
    void mailApi.message(targetMailId).then((detail) => {
      if (cancelled) return;
      const sourceFolder = realFolders.find((item) => item.id === detail.summary.folderId);
      if (!sourceFolder) throw new Error(t('Папка исходного письма не найдена.'));
      const folderKey = sourceFolder.specialUse ?? sourceFolder.id;
      const mail = toMailView(detail.summary, folderKey);
      linkedMailRef.current = mail;
      setMails((prev) => [mail, ...prev.filter((item) => item.id !== mail.id)]);
      setLoadedBodies((prev) => ({ ...prev, [mail.id]: detail.bodyText ?? '' }));
      setLoadedHtml((prev) => ({ ...prev, [mail.id]: detail.bodyHtml ?? null }));
      setLoadedAttachments((prev) => ({ ...prev, [mail.id]: detail.attachments ?? [] }));
      setLoadedRecipients((prev) => ({ ...prev, [mail.id]: { to: detail.to ?? [], cc: detail.cc ?? [] } }));
      setSearch('');
      resetMailFilters();
      setFolder(folderKey);
      setActiveId(mail.id);
      setMobileReading(true);
      consumeLink();
    }).catch((error: unknown) => {
      if (!cancelled) {
        setLoadError(`${t('Не удалось открыть исходное письмо.')} ${errorMessage(error)}`);
        consumeLink();
      }
    });
    return () => { cancelled = true; };
    // Re-run once folder metadata arrives; do not restart while message state changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [demoMode, foldersStatus, realFolders, searchParams, setSearchParams, locale]);

  // Serialize writes per message. Keep the last confirmed value so a failed
  // write can be rolled back without undoing a newer queued user action.
  const writeSeen = (mail: DemoMail, seen: boolean): Promise<void> => {
    const { id } = mail;
    seenRevisionRef.current += 1;
    const previous = seenQueueRef.current.get(id);
    if (!previous) seenConfirmedRef.current.set(id, mail.seen);
    const version = (seenVersionRef.current.get(id) ?? 0) + 1;
    seenVersionRef.current.set(id, version);
    seenDesiredRef.current.set(id, seen);
    const result = enqueueMessageMutation(id, async () => {
      try {
        await mailApi.flags(id, { seen });
        seenConfirmedRef.current.set(id, seen);
      } catch (error) {
        if (seenVersionRef.current.get(id) !== version) return;
        const confirmed = seenConfirmedRef.current.get(id) ?? mail.seen;
        if (confirmed !== seen) {
          if (linkedMailRef.current?.id === id) linkedMailRef.current = { ...linkedMailRef.current, seen: confirmed };
          setMails((current) => current.map((item) => item.id === id ? { ...item, seen: confirmed } : item));
          setServerFolderCounts((current) => ({
            ...current,
            [mail.folder]: Math.max(0, (current[mail.folder] ?? 0) + (confirmed ? -1 : 1)),
          }));
        }
        // A failed automatic read must not start an endless retry loop.
        if (!confirmed) suppressAutoReadRef.current = id;
        setLoadError(errorMessage(error));
      }
    });
    seenQueueRef.current.set(id, result);
    void result.finally(() => {
      seenRevisionRef.current += 1;
      if (seenQueueRef.current.get(id) !== result) return;
      seenQueueRef.current.delete(id);
      seenDesiredRef.current.delete(id);
      seenConfirmedRef.current.delete(id);
      seenVersionRef.current.delete(id);
    });
    return result;
  };

  const markSeen = (mail: DemoMail) => {
    if (mail.seen || seenPendingRef.current.has(mail.id) || movingMailIdsRef.current.has(mail.id)) return;
    seenPendingRef.current.add(mail.id);
    if (linkedMailRef.current?.id === mail.id) linkedMailRef.current = { ...linkedMailRef.current, seen: true };
    setMails((prev) => prev.map((x) => (x.id === mail.id ? { ...x, seen: true } : x)));
    if (demoMode) {
      markDemoMailRead(mail.id);
      seenPendingRef.current.delete(mail.id);
      return;
    }
    setServerFolderCounts((prev) => ({
      ...prev,
      [mail.folder]: Math.max(0, (prev[mail.folder] ?? 0) - 1),
    }));
    void writeSeen(mail, true).finally(() => seenPendingRef.current.delete(mail.id));
  };

  const toggleSeen = (mail: DemoMail) => {
    if (movingMailIdsRef.current.has(mail.id)) return;
    const nextSeen = !mail.seen;
    // An explicit "mark unread" remains in effect until the selection changes.
    suppressAutoReadRef.current = nextSeen ? null : mail.id;
    if (linkedMailRef.current?.id === mail.id) linkedMailRef.current = { ...linkedMailRef.current, seen: nextSeen };
    setMails((prev) => prev.map((x) => (x.id === mail.id ? { ...x, seen: nextSeen } : x)));
    if (demoMode) {
      if (nextSeen) markDemoMailRead(mail.id);
      else markDemoMailUnread(mail.id);
      return;
    }
    setServerFolderCounts((previous) => ({
      ...previous,
      [mail.folder]: Math.max(0, (previous[mail.folder] ?? 0) + (nextSeen ? -1 : 1)),
    }));
    void writeSeen(mail, nextSeen);
  };

  const open = (m: DemoMail, fromKeyboard = false) => {
    if (linkedMailRef.current?.id !== m.id) linkedMailRef.current = null;
    if (active?.id !== m.id) suppressAutoReadRef.current = null;
    setActiveId(m.id);
    if (!fromKeyboard) setMobileReading(true);
    if (preferences.markRead === 'automatic' && suppressAutoReadRef.current !== m.id) markSeen(m);
  };

  useEffect(() => {
    if (!active || active.seen || preferences.markRead !== 'automatic' || suppressAutoReadRef.current === active.id) return;
    const narrowLayout =
      typeof window !== 'undefined'
      && typeof window.matchMedia === 'function'
      && window.matchMedia('(max-width: 1023px)').matches;
    if (narrowLayout && !mobileReading) return;
    setActiveId(active.id);
    markSeen(active);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, mobileReading, demoMode, preferences.markRead, movingMailIds]);

  const selectFolder = (id: string) => {
    setLoadError(null);
    onCloseNavigation?.();
    suppressAutoReadRef.current = null;
    linkedMailRef.current = null;
    setFolder(id);
    setOpenListPanel(null);
    setMobileReading(false);
    const first = mails.find((m) => m.folder === id);
    if (first) setActiveId(first.id);
  };

  const createFolder = async (name: string) => {
    if (!canManageFolders) throw new ApiError(403, '');
    let key: string;
    if (demoMode) {
      if (folders.some((item) => item.name.toLocaleLowerCase() === name.toLocaleLowerCase())) throw new ApiError(409, '');
      key = `demo-folder-${globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`}`;
      setDemoCustomFolders((current) => [...current, { id: key, name, unread: 0 }]);
    } else {
      const created = await mailApi.createFolder(name);
      folderRevisionRef.current += 1;
      setRealFolders((current) => [...current.filter((item) => item.id !== created.id), created]);
      setServerFolderCounts((current) => ({ ...current, [created.id]: created.unread }));
      key = created.id;
    }
    linkedMailRef.current = null;
    suppressAutoReadRef.current = null;
    setFolder(key);
    setActiveId('');
    setMobileReading(false);
    setLoadError(null);
  };

  const moveActive = (targetFolder: string) => {
    if (!active || active.folder === targetFolder || movingMailIdsRef.current.has(active.id)) return;
    const next = visible[activeIndex + 1] ?? visible[activeIndex - 1];
    if (!demoMode) {
      const target = realFolders.find((f) => (f.specialUse ?? f.id) === targetFolder);
      if (!target) return;
      const source = active;
      movingMailIdsRef.current.add(source.id);
      setMovingMailIds(new Set(movingMailIdsRef.current));
      mailRevisionRef.current += 1;
      void enqueueMessageMutation(source.id, () => mailApi.move(source.id, target.id))
        .then((item) => {
          if (linkedMailRef.current?.id === source.id) linkedMailRef.current = null;
          setMails((prev) => {
            const existing = prev.find((mail) => mail.id === source.id);
            const moved = { ...toMailView(item, targetFolder), body: loadedBodies[source.id] ?? existing?.body ?? item.snippet ?? '' };
            return existing ? prev.map((mail) => mail.id === source.id ? moved : mail) : [...prev, moved];
          });
          if (!item.seen) {
            setServerFolderCounts((current) => ({
              ...current,
              [source.folder]: Math.max(0, (current[source.folder] ?? 0) - 1),
              [targetFolder]: (current[targetFolder] ?? 0) + 1,
            }));
          }
          // A later choice of message/folder belongs to the user, not this request.
          if (currentViewRef.current.folder === source.folder && currentViewRef.current.activeId === source.id) {
            setActiveId((current) => current === source.id ? next?.id ?? '' : current);
            setMobileReading(false);
          }
        })
        .catch((error) => setLoadError(errorMessage(error)))
        .finally(() => {
          mailRevisionRef.current += 1;
          movingMailIdsRef.current.delete(source.id);
          setMovingMailIds(new Set(movingMailIdsRef.current));
        });
      return;
    }
    if (linkedMailRef.current?.id === active.id) linkedMailRef.current = null;
    setMails((prev) => prev.map((m) => (m.id === active.id ? { ...m, folder: targetFolder } : m)));
    if (next && next.id !== active.id) setActiveId(next.id);
    setMobileReading(false);
  };

  const toggleFlag = (mail: DemoMail) => {
    if (movingMailIdsRef.current.has(mail.id)) return;
    const flagged = !mail.flagged;
    if (linkedMailRef.current?.id === mail.id) linkedMailRef.current = { ...linkedMailRef.current, flagged };
    setMails((prev) => prev.map((m) => (m.id === mail.id ? { ...m, flagged } : m)));
    if (demoMode) return;

    const { id } = mail;
    mailRevisionRef.current += 1;
    if (!flagQueueRef.current.has(id)) flagConfirmedRef.current.set(id, mail.flagged);
    const version = (flagVersionRef.current.get(id) ?? 0) + 1;
    flagVersionRef.current.set(id, version);
    flagDesiredRef.current.set(id, flagged);
    const result = enqueueMessageMutation(id, async () => {
      try {
        await mailApi.flags(id, { flagged });
        flagConfirmedRef.current.set(id, flagged);
      } catch (error) {
        if (flagVersionRef.current.get(id) !== version) return;
        const confirmed = flagConfirmedRef.current.get(id) ?? mail.flagged;
        if (linkedMailRef.current?.id === id) linkedMailRef.current = { ...linkedMailRef.current, flagged: confirmed };
        setMails((current) => current.map((item) => item.id === id ? { ...item, flagged: confirmed } : item));
        setLoadError(errorMessage(error));
      }
    });
    flagQueueRef.current.set(id, result);
    void result.finally(() => {
      mailRevisionRef.current += 1;
      if (flagQueueRef.current.get(id) !== result) return;
      flagQueueRef.current.delete(id);
      flagDesiredRef.current.delete(id);
      flagConfirmedRef.current.delete(id);
      flagVersionRef.current.delete(id);
    });
  };

  const step = (delta: number) => {
    if (visible.length === 0) return;
    const nextIndex = activeIndex < 0 ? 0 : Math.min(visible.length - 1, Math.max(0, activeIndex + delta));
    open(visible[nextIndex], true);
  };

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      const typing =
        target?.tagName === 'INPUT'
        || target?.tagName === 'TEXTAREA'
        || target?.tagName === 'SELECT'
        || target?.isContentEditable;
      if (externalOpen || eventModalDraft || settingsOpen || createFolderOpen) return;
      if (e.key === 'Escape' && composeDraft) {
        setComposeDraft(null);
        return;
      }
      if (typing || composeDraft || e.ctrlKey || e.metaKey || e.altKey) return;
      const key = e.key.toLowerCase();
      if (key === '/') {
        e.preventDefault();
        searchRef.current?.focus();
      } else if (key === 'j' || key === 'о' || e.key === 'ArrowDown') {
        e.preventDefault();
        step(1);
      } else if (key === 'k' || key === 'л' || e.key === 'ArrowUp') {
        e.preventDefault();
        step(-1);
      } else if (key === 'c' || key === 'с') {
        e.preventDefault();
        openDraft({ to: '', subject: '', body: '' }, 'new');
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  });

  const addSentMail = async (draft: ComposeDraft, files: File[]) => {
    if (demoMode) {
      setMails((prev) => [
        {
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
        },
        ...prev,
      ]);
      return true;
    }

    const attachments = await Promise.all(files.map(fileToAttachment));
    const sendResult = await mailApi.send({
      to: splitAddresses(draft.to),
      cc: splitAddresses(draft.cc),
      subject: draft.subject,
      bodyText: draft.body,
      attachments,
      saveToSent: true,
      externalAccountId: draft.externalAccountId ?? null,
    });

    // Sending has succeeded. Never turn a later refresh failure into a send
    // failure: a retry would risk delivering the same message twice.
    mailRevisionRef.current += 1;
    const folderRevision = folderRevisionRef.current;
    const seenRevision = seenRevisionRef.current;
    const mailRevision = mailRevisionRef.current;
    const folderSequence = beginFolderSnapshot();
    void (async () => {
      try {
        const refreshed = await mailApi.folders();
        if (folderRevision === folderRevisionRef.current && folderSequence === folderSnapshotRef.current) setRealFolders(refreshed);
        if (folderRevision === folderRevisionRef.current && folderSequence === folderSnapshotRef.current
            && seenRevision === seenRevisionRef.current && mailRevision === mailRevisionRef.current
            && seenQueueRef.current.size === 0 && movingMailIdsRef.current.size === 0) {
          setServerFolderCounts(Object.fromEntries(refreshed.map((f) => [f.specialUse ?? f.id, f.unread])));
        }
        const sentFolder = refreshed.find((f) => f.specialUse === 'SENT');
        if (!sentFolder || currentViewRef.current.folder !== 'SENT' || movingMailIdsRef.current.size > 0) return;
        const listRevision = mailRevisionRef.current;
        const listSeenRevision = seenRevisionRef.current;
        const sequence = beginMessageSnapshot(sentFolder.id);
        const items = await mailApi.messages(sentFolder.id);
        if (listRevision !== mailRevisionRef.current || listSeenRevision !== seenRevisionRef.current
            || movingMailIdsRef.current.size > 0 || currentViewRef.current.folder !== 'SENT'
            || sequence !== messageSnapshotRef.current.get(sentFolder.id)) return;
        const mapped = items.map((item) => toMailView(item, 'SENT'));
        const linked = linkedMailRef.current;
        if (linked?.folder === 'SENT' && !mapped.some((mail) => mail.id === linked.id)) mapped.unshift(linked);
        setMails((prev) => replaceFolderMessages(prev, 'SENT', mapped));
      } catch (error) {
        setLoadError(`${t('Письмо отправлено, но список почты не обновлён.')}: ${errorMessage(error)}`);
      }
    })();
    return sendResult?.sentMessageId != null;
  };

  return (
    <div className="flex h-full min-h-0 min-w-0 flex-1 overflow-hidden bg-surface">
      {/* Dark SidebarSlot (#0B1221): Actions + Folders + Quota */}
      <SidebarSlot>
        <div className="flex min-h-full flex-col justify-between">
          <div className="space-y-4 px-3 py-3">
            <MailSidebarActions
              canAdminExternalAccounts={canAdminExternalAccounts}
              externalAccountCount={externalAccounts.length}
              onCompose={() => { onCloseNavigation?.(); openDraft({ to: '', subject: '', body: '' }, 'new'); }}
              onOpenExternal={() => { onCloseNavigation?.(); setExternalOpen(true); }}
            />

            <div>
              <p className="sidebar-section-label mb-1.5 px-1.5">{t('Папки')}</p>
              {!demoMode && foldersStatus === 'ready' && folders.length === 0 && (
                <p className="px-1.5 py-2 text-xs text-sidebar-muted" role="status">{t('Папки почты не найдены.')}</p>
              )}
              <nav className="space-y-0.5" aria-label={t('Папки почты')}>
                {folders.map((f) => {
                  const Icon = FOLDER_ICONS[f.id] ?? Folder;
                  const count = folderCounts[f.id] ?? 0;
                  const isCurrent = folder === f.id;
                  return (
                    <button
                      key={f.id}
                      type="button"
                      onClick={() => selectFolder(f.id)}
                      className={`sidebar-subitem w-full ${isCurrent ? 'sidebar-subitem-active' : ''}`}
                    >
                      <Icon size={15} strokeWidth={1.85} className="shrink-0" />
                      <span className="min-w-0 flex-1 truncate text-left">{f.name}</span>
                      {count > 0 && (
                        <span className="rounded-full bg-accent px-1.5 py-0.5 text-[10.5px] font-semibold leading-none text-white tabular-nums">
                          {count}
                        </span>
                      )}
                    </button>
                  );
                })}
              </nav>
              <button
                type="button"
                onClick={() => { onCloseNavigation?.(); setCreateFolderOpen(true); }}
                disabled={!canManageFolders || (!demoMode && foldersStatus !== 'ready')}
                title={!canManageFolders ? t('Нет права на создание папок.') : !demoMode && foldersStatus !== 'ready' ? t('Сначала загрузите список папок.') : undefined}
                className="sidebar-subitem mt-1 w-full text-sidebar-muted disabled:cursor-not-allowed disabled:opacity-50"
              >
                <FolderPlus size={15} strokeWidth={1.85} className="shrink-0" />
                <span className="min-w-0 flex-1 truncate text-left">{t('Создать папку')}</span>
              </button>
              {!canManageFolders && <p className="px-2.5 pb-1 text-[11px] text-sidebar-muted">{t('Нет права на создание папок.')}</p>}
            </div>
            <button type="button" onClick={() => { onCloseNavigation?.(); setSettingsOpen(true); }} className="sidebar-subitem w-full text-sidebar-muted">
              <Settings2 size={15} strokeWidth={1.85} className="shrink-0" /><span className="min-w-0 flex-1 truncate text-left">{t('Настройки почты')}</span>
            </button>
          </div>

          <MailboxUsage quota={mailboxQuota} unavailable={mailboxQuotaUnavailable} variant="dark" />
        </div>
      </SidebarSlot>

      {/* Средняя колонка: Список писем (388px на >= 1024px) */}
      <section
        aria-label={t('Список писем')}
        className={`${
          mobileReading ? 'hidden lg:flex' : 'flex'
        } relative min-w-0 flex-1 flex-col border-r border-line bg-surface lg:w-[388px] lg:flex-none`}
      >
        {/* 56px Search + Sort + Filter Header */}
        <header className="z-10 flex h-14 shrink-0 items-center gap-1.5 border-b border-line bg-surface px-3.5">
          <div className="relative min-w-0 flex-1">
            <Search size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-faint" />
            <input
              ref={searchRef}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={t('Поиск по теме, отправителю, тексту')}
              aria-label={t('Поиск по теме, отправителю, тексту')}
              className="field h-9 bg-canvas pl-8 pr-12 text-[13px]"
            />
            {onOpenCommandPalette ? (
              <button
                type="button"
                onClick={onOpenCommandPalette}
                title={t('Быстрый переход')}
                className="absolute right-2 top-1/2 hidden -translate-y-1/2 rounded border border-line-control bg-surface px-1.5 py-0.5 font-mono text-[10px] text-faint hover:text-ink sm:inline-flex"
              >
                ⌘K
              </button>
            ) : (
              <span className="kbd absolute right-2.5 top-1/2 hidden -translate-y-1/2 sm:inline-flex">/</span>
            )}
          </div>

          <div ref={sortPanelRef} className="relative">
            <IconButton
              icon={ArrowUpDown}
              label={t('Сортировка')}
              className={`h-8 w-8 ${sortActive || openListPanel === 'sort' ? 'bg-accent-soft text-accent-dark' : ''}`}
              size={16}
              onClick={() => setOpenListPanel((panel) => (panel === 'sort' ? null : 'sort'))}
              aria-expanded={openListPanel === 'sort'}
              aria-haspopup="dialog"
            />
            {sortActive && (
              <span
                className="pointer-events-none absolute right-0.5 top-0.5 h-2 w-2 rounded-full bg-accent ring-2 ring-surface"
                aria-hidden="true"
              />
            )}
            {openListPanel === 'sort' && (
              <div
                role="dialog"
                aria-label={t('Сортировка')}
                className="absolute right-0 top-10 z-30 w-56 max-w-[calc(100vw-32px)] rounded-xl border border-line-control bg-surface p-1.5 shadow-pop"
              >
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

          {canAdminExternalAccounts && (
            <IconButton
              icon={Settings2}
              label={t('Внешние ящики')}
              className="h-8 w-8 md:hidden"
              size={16}
              onClick={() => setExternalOpen(true)}
            />
          )}

          <div ref={filtersPanelRef} className="relative">
            <IconButton
              icon={SlidersHorizontal}
              label={t('Фильтры')}
              className={`h-8 w-8 ${filtersActive || openListPanel === 'filters' ? 'bg-accent-soft text-accent-dark' : ''}`}
              size={16}
              onClick={() => setOpenListPanel((panel) => (panel === 'filters' ? null : 'filters'))}
              aria-expanded={openListPanel === 'filters'}
              aria-haspopup="dialog"
            />
            {filtersActive && (
              <span
                className="pointer-events-none absolute right-0.5 top-0.5 h-2 w-2 rounded-full bg-accent ring-2 ring-surface"
                aria-hidden="true"
              />
            )}
            {openListPanel === 'filters' && (
              <div
                role="dialog"
                aria-label={t('Фильтры')}
                className="absolute right-0 top-10 z-30 w-72 max-w-[calc(100vw-32px)] rounded-xl border border-line-control bg-surface p-3 shadow-pop"
              >
                <p className="section-label">{t('Состояние')}</p>
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
                <div className="mt-3 space-y-2">
                  <label className="flex cursor-pointer items-center gap-2.5 text-[13px] text-ink-soft">
                    <input
                      type="checkbox"
                      checked={onlyFlagged}
                      onChange={(event) => setOnlyFlagged(event.target.checked)}
                      className="h-4 w-4 accent-[#1D63ED]"
                    />
                    <span>{t('Только важные')}</span>
                  </label>
                  <label className="flex cursor-pointer items-center gap-2.5 text-[13px] text-ink-soft">
                    <input
                      type="checkbox"
                      checked={onlyAttachments}
                      onChange={(event) => setOnlyAttachments(event.target.checked)}
                      className="h-4 w-4 accent-[#1D63ED]"
                    />
                    <span>{t('Только с вложениями')}</span>
                  </label>
                </div>
                <button
                  type="button"
                  onClick={resetMailFilters}
                  disabled={!filtersActive}
                  className="btn-ghost mt-3 w-full"
                >
                  {t('Сбросить фильтры')}
                </button>
              </div>
            )}
          </div>
          <IconButton icon={Settings2} label={t('Настройки почты')} size={16} className="h-8 w-8 shrink-0" onClick={() => setSettingsOpen(true)} />
        </header>

        {/* Subheader: folder name + quick tabs */}
        <div className="flex items-center justify-between gap-2 border-b border-line-subtle bg-canvas/70 px-4 py-2">
          <div className="flex min-w-0 items-center gap-2">
            <h2 className="truncate text-[13px] font-semibold text-ink">{currentFolder?.name ?? t('Почта')}</h2>
            <span className="rounded-full bg-surface px-1.5 py-0.5 text-[11px] font-medium text-muted tabular-nums">
              {visible.length}
            </span>
          </div>
          <div className="flex shrink-0 items-center rounded-lg border border-line-control bg-surface p-0.5" role="tablist" aria-label={t('Фильтр писем')}>
            {(['all', 'unread', 'read'] as const).map((value) => (
              <button
                key={value}
                type="button"
                role="tab"
                aria-selected={filter === value}
                onClick={() => setFilter(value)}
                className={`h-6 rounded-md px-2 text-[11.5px] font-medium transition ${
                  filter === value ? 'bg-accent-soft text-accent-dark' : 'text-muted hover:text-ink'
                }`}
              >
                {value === 'all' ? t('Все') : value === 'unread' ? t('Непрочитанные') : t('Прочитанные')}
              </button>
            ))}
          </div>
        </div>

        {(onlyFlagged || onlyAttachments || sortActive) && (
          <div className="flex flex-wrap gap-1.5 border-b border-line-subtle px-4 py-2">
            {onlyFlagged && (
              <button type="button" onClick={() => setOnlyFlagged(false)} className="chip chip-accent">
                {t('Только важные')} <X size={12} />
              </button>
            )}
            {onlyAttachments && (
              <button type="button" onClick={() => setOnlyAttachments(false)} className="chip chip-accent">
                {t('Только с вложениями')} <X size={12} />
              </button>
            )}
            {sortActive && (
              <button type="button" onClick={() => setSortOrder('date-desc')} className="chip chip-accent">
                {sortOrder === 'date-asc'
                  ? t('Сначала старые')
                  : sortOrder === 'sender-asc'
                    ? t('По отправителю А–Я')
                    : t('По теме А–Я')}{' '}
                <X size={12} />
              </button>
            )}
          </div>
        )}

        {/* Message List */}
        <div className="scroll-area min-h-0 flex-1">
          {loadError && (
            <div className="m-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800" role="alert">
              {loadError}
            </div>
          )}

          {visible.map((m) => {
            const selected = active?.id === m.id;
            return (
              <div
                key={m.id}
                draggable
                aria-busy={movingMailIds.has(m.id)}
                onDragStart={(e) => {
                  e.dataTransfer.effectAllowed = 'copy';
                  e.dataTransfer.setData(
                    CORVEX_MAIL_DND_MIME,
                    JSON.stringify({
                      id: m.id,
                      subject: m.subject,
                      from: m.from,
                      body: loadedBodies[m.id] ?? m.body ?? m.snippet,
                      flagged: m.flagged,
                    }),
                  );
                  // Opening a fixed overlay synchronously inside dragstart can cancel
                  // the browser's native drag before it has initialized.
                  if (onOpenCalendarWidget) {
                    if (calendarDragTimerRef.current !== null) window.clearTimeout(calendarDragTimerRef.current);
                    calendarDragTimerRef.current = window.setTimeout(() => {
                      calendarDragTimerRef.current = null;
                      onOpenCalendarWidget();
                    }, 100);
                  }
                }}
                onDragEnd={() => {
                  if (calendarDragTimerRef.current !== null) window.clearTimeout(calendarDragTimerRef.current);
                  calendarDragTimerRef.current = null;
                }}
                onClick={() => open(m)}
                className={`group relative flex cursor-pointer border-b border-line-subtle px-4 transition duration-150 ${
                  preferences.density === 'compact' ? 'gap-2 py-1.5' : 'gap-3 py-3'
                } ${selected ? 'bg-accent-soft' : 'bg-surface hover:bg-canvas'}`}
              >
                {selected && <span className="absolute inset-y-0 left-0 w-[3px] bg-accent" />}
                {movingMailIds.has(m.id) && <span className="sr-only" role="status">{t('Перемещение письма…')}</span>}
                <Avatar name={m.from} size={preferences.density === 'compact' ? 30 : 36} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    {!m.seen && (
                      <span
                        className="h-2 w-2 shrink-0 rounded-full bg-[#2563EB]"
                        aria-label={t('Непрочитано')}
                      />
                    )}
                    <span
                      className={`min-w-0 flex-1 truncate text-[13.5px] ${
                        m.seen ? 'font-medium text-ink-soft' : 'font-semibold text-ink'
                      }`}
                    >
                      {cleanPersonName(m.from)}
                    </span>
                    <span className="shrink-0 text-[11.5px] text-faint tabular-nums">{m.date}</span>
                  </div>

                  <div className="mt-0.5 flex items-center gap-1.5">
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        open(m);
                      }}
                      className={`min-w-0 flex-1 truncate rounded text-left text-[13px] focus:outline-none focus-visible:ring-2 focus-visible:ring-accent/50 ${
                        m.seen ? 'text-ink-soft' : 'font-semibold text-ink'
                      }`}
                    >
                      {m.subject}
                    </button>

                    {m.hasAttachments && <Paperclip size={13} className="shrink-0 text-faint" />}

                    <button
                      type="button"
                      title={t('Запланировать событие в календаре')}
                      aria-label={`${t('В календарь')}: ${m.subject}`}
                      onClick={(e) => {
                        e.stopPropagation();
                        scheduleFromMail(m);
                      }}
                      className="flex h-6 w-6 shrink-0 items-center justify-center rounded-md text-faint transition hover:bg-surface hover:text-accent md:opacity-0 md:group-hover:opacity-100"
                    >
                      <CalendarPlus size={13.5} />
                    </button>

                    <button
                      type="button"
                      title={m.flagged ? t('Убрать из важных') : t('Отметить важным')}
                      aria-label={m.flagged ? t('Убрать из важных') : t('Отметить важным')}
                      disabled={movingMailIds.has(m.id)}
                      onClick={(e) => {
                        e.stopPropagation();
                        toggleFlag(m);
                      }}
                      className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-md transition disabled:cursor-not-allowed disabled:opacity-50 ${
                        m.flagged
                          ? 'text-amber-500'
                          : 'text-faint hover:bg-surface hover:text-ink md:opacity-0 md:group-hover:opacity-100'
                      }`}
                    >
                      <Star size={14} fill={m.flagged ? 'currentColor' : 'none'} />
                    </button>
                  </div>

                  {preferences.showPreview && <p className="mt-0.5 truncate text-xs text-faint">{m.snippet}</p>}
                </div>
              </div>
            );
          })}

          {visible.length === 0 && (
            <EmptyState
              icon={MailOpen}
              title={t('Здесь пока тихо')}
              description={
                search
                  ? t('По этому запросу писем не нашлось. Попробуйте другое слово или сбросьте фильтр.')
                  : t('В этой папке нет писем для выбранного фильтра.')
              }
            />
          )}
        </div>

        <button
          type="button"
          onClick={() => openDraft({ to: '', subject: '', body: '' }, 'new')}
          className="btn-primary absolute bottom-4 right-4 z-30 shadow-pop lg:hidden"
        >
          <PenSquare size={16} /> {t('Написать')}
        </button>
      </section>

      {/* Правая колонка: Чтение письма */}
      <section className={`${mobileReading ? 'flex' : 'hidden lg:flex'} min-w-0 flex-1 flex-col bg-surface`}>
        {/* 56px Top Action Toolbar (ТЗ §3.2) */}
        <header className="z-10 flex h-14 shrink-0 items-center justify-between gap-2 border-b border-line bg-surface px-4">
          {active ? (
            <div className="flex min-w-0 flex-1 items-center gap-1 overflow-x-auto">
              <button
                type="button"
                onClick={() => setMobileReading(false)}
                className="btn-ghost px-2 lg:hidden"
              >
                <ArrowLeft size={16} /> {t('К списку')}
              </button>

              <IconButton
                icon={Reply}
                label={t('Ответить')}
                size={17}
                onClick={() =>
                  openDraft({
                    to: emailFromHeader(active.from),
                    subject: `Re: ${active.subject}`,
                    body: '',
                  }, 'reply')
                }
              />
              <IconButton
                icon={ReplyAll}
                label={t('Ответить всем')}
                size={17}
                className="hidden 2xl:inline-flex"
                disabled={!replyAllReady}
                onClick={() =>
                  openDraft({
                    to: emailFromHeader(active.from),
                    cc: replyCc || undefined,
                    subject: `Re: ${active.subject}`,
                    body: '',
                  }, 'reply')
                }
              />
              <IconButton
                icon={Forward}
                label={t('Переслать')}
                size={17}
                className="hidden 2xl:inline-flex"
                onClick={() =>
                  openDraft({
                    to: '',
                    subject: `Fwd: ${active.subject}`,
                    body: `\n\n--- ${t('Пересылаемое письмо')} ---\n${activeBody}`,
                  }, 'forward')
                }
              />

              <span className="mx-1 h-4 w-px bg-line" aria-hidden="true" />
              <MoveToFolder folders={folders} current={folder} onMove={moveActive} disabled={movingMailIds.has(active.id)} />
              <IconButton icon={Archive} label={t('В архив')} size={16.5} className="hidden 2xl:inline-flex" onClick={() => moveActive('ARCHIVE')} disabled={movingMailIds.has(active.id)} />
              <IconButton icon={OctagonAlert} label={t('В спам')} size={16.5} className="hidden 2xl:inline-flex" onClick={() => moveActive('SPAM')} disabled={movingMailIds.has(active.id)} />
              <IconButton
                icon={Trash2}
                label={t('Удалить')}
                size={16.5}
                className="hover:bg-danger-soft hover:text-danger"
                disabled={movingMailIds.has(active.id)}
                onClick={() => moveActive('TRASH')}
              />
              <IconButton
                icon={active.seen ? Mail : MailOpen}
                label={active.seen ? t('Отметить непрочитанным') : t('Отметить прочитанным')}
                size={16.5}
                disabled={movingMailIds.has(active.id)}
                onClick={() => toggleSeen(active)}
              />

              <span className="mx-1 h-4 w-px bg-line" aria-hidden="true" />

              <button
                type="button"
                onClick={() => scheduleFromMail(active)}
                className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-accent/30 bg-[#EEF4FF] px-2.5 text-xs font-medium text-accent-dark transition hover:bg-[#DBEAFE]"
              >
                <CalendarPlus size={14.5} strokeWidth={1.9} />
                <span className="hidden xl:inline">{t('В календарь')}</span>
              </button>

              <div className="ml-2 hidden items-center gap-0.5 lg:flex">
                <IconButton
                  icon={ChevronUp}
                  label={t('Предыдущее письмо')}
                  size={16}
                  onClick={() => step(-1)}
                  disabled={activeIndex <= 0}
                />
                <IconButton
                  icon={ChevronDown}
                  label={t('Следующее письмо')}
                  size={16}
                  onClick={() => step(1)}
                  disabled={activeIndex < 0 || activeIndex >= visible.length - 1}
                />
              </div>
            </div>
          ) : (
            <div />
          )}

          <WorkspaceUtilityBar compact className="hidden xl:flex" />
        </header>

        {calendarSavedNotice && (
          <div className="flex items-center justify-between border-b border-[#BFDBFE] bg-[#EFF6FF] px-5 py-2 text-xs text-[#1D4ED8]">
            <span className="inline-flex items-center gap-1.5 font-medium">
              <Sparkles size={13} />
              {calendarSavedNotice}
            </span>
            <button
              type="button"
              onClick={() => {
                setCalendarSavedNotice(null);
                navigate('/calendar');
              }}
              className="font-semibold underline"
            >
              {t('Открыть полный календарь')}
            </button>
          </div>
        )}

        {active ? (
          <div className="scroll-area min-h-0 flex-1">
            <article className="mx-auto w-full max-w-[820px] animate-fade-in px-6 py-6 sm:px-8">
              <div className="flex flex-wrap items-center gap-2">
                <span className="chip chip-accent">{currentFolder?.name ?? active.folder}</span>
                {active.flagged && (
                  <span className="chip border-amber-200 bg-amber-50 text-amber-800">{t('Важное')}</span>
                )}
              </div>

              <h1 className="mt-3 text-[20px] font-semibold leading-[26px] tracking-[-0.015em] text-ink">
                {active.subject}
              </h1>

              {/* Linked Calendar Event Banner */}
              {linkedCalendarEvents.length > 0 && (
                <div className="mt-3 flex flex-wrap items-center justify-between gap-2 rounded-xl border border-[#BFDBFE] bg-[#EFF6FF] px-3.5 py-2.5 text-xs text-[#1D4ED8]">
                  <div className="flex items-center gap-2">
                    <CalendarIcon size={15} className="shrink-0" />
                    <span className="font-medium">
                      {t('Запланировано в календаре:')} {linkedCalendarEvents[0].title} ({linkedCalendarEvents[0].date},{' '}
                      {linkedCalendarEvents[0].allDay
                        ? t('Весь день')
                        : `${linkedCalendarEvents[0].startTime}–${linkedCalendarEvents[0].endTime}`}
                      )
                    </span>
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      setCalendarSelectedDate(linkedCalendarEvents[0].date);
                      navigate(`/calendar?date=${linkedCalendarEvents[0].date}`);
                    }}
                    className="font-semibold underline hover:text-accent-dark"
                  >
                    {t('Перейти к событию')}
                  </button>
                </div>
              )}

              <div className="mt-5 flex items-start gap-3 border-b border-line pb-5">
                <Avatar name={active.from} size={40} />
                <div className="min-w-0 flex-1">
                  <div className="truncate text-[14px] font-semibold text-ink">{cleanPersonName(active.from)}</div>
                  <div
                    className="mt-0.5 truncate text-xs text-muted"
                    title={`${t('Кому')}: ${recipientText}${ccText ? ` · ${t('Копия')}: ${ccText}` : ''}`}
                  >
                    {emailFromHeader(active.from) || t('внутренний отправитель')} · {t('Кому')}: {recipientText}
                    {ccText ? ` · ${t('Копия')}: ${ccText}` : ''}
                  </div>
                </div>
                <time className="shrink-0 text-xs text-faint tabular-nums">{active.date}</time>
              </div>

              {active.hasAttachments && (
                <MailAttachments
                  demoMode={demoMode}
                  attachments={loadedAttachments[active.id]}
                  busy={downloadBusy !== null}
                  onDownload={(attachment) => void downloadAttachment(active.id, attachment)}
                />
              )}

              {activeHtml && (
                <div className="mt-5 flex flex-wrap items-center gap-3 text-xs text-muted">
                  <span>{t('Внешние изображения и ссылки отключены для безопасности.')}</span>
                  <button
                    type="button"
                    className="font-medium text-accent-dark underline"
                    onClick={() => setShowPlainText(!showPlainText)}
                  >
                    {t(showPlainText ? 'Показать HTML' : 'Показать обычный текст')}
                  </button>
                </div>
              )}

              {activeHtml && !showPlainText ? (
                <iframe
                  title={t('Содержимое HTML-письма')}
                  sandbox=""
                  referrerPolicy="no-referrer"
                  srcDoc={mailHtmlDocument(activeHtml)}
                  className="mt-4 h-[420px] w-full rounded-xl border border-line bg-white"
                />
              ) : (
                <div className="mt-6 text-[14px] leading-[1.68] text-ink-soft">
                  {activeBody.split('\n').map((paragraph, i) =>
                    paragraph.trim() ? (
                      <p key={i} className="mb-3.5 whitespace-pre-wrap">
                        {paragraph}
                      </p>
                    ) : null,
                  )}
                </div>
              )}

              {/* Bottom 4-button action bar (ТЗ §3.2) */}
              <div className="mt-8 flex flex-wrap items-center gap-2 border-t border-line pt-5">
                <button
                  type="button"
                  className="btn-primary"
                  onClick={() =>
                    openDraft({
                      to: emailFromHeader(active.from),
                      subject: `Re: ${active.subject}`,
                      body: '',
                    }, 'reply')
                  }
                >
                  <Reply size={15} /> {t('Ответить')}
                </button>
                <button
                  type="button"
                  className="btn-secondary"
                  disabled={!replyAllReady}
                  onClick={() =>
                    openDraft({
                      to: emailFromHeader(active.from),
                      cc: replyCc || undefined,
                      subject: `Re: ${active.subject}`,
                      body: '',
                    }, 'reply')
                  }
                >
                  <ReplyAll size={15} /> {t('Ответить всем')}
                </button>
                <button
                  type="button"
                  className="btn-secondary"
                  onClick={() =>
                    openDraft({
                      to: '',
                      subject: `Fwd: ${active.subject}`,
                      body: `\n\n--- ${t('Пересылаемое письмо')} ---\n${activeBody}`,
                    }, 'forward')
                  }
                >
                  <Forward size={15} /> {t('Переслать')}
                </button>
                <button
                  type="button"
                  className="btn-secondary ml-auto border-accent/30 bg-[#EEF4FF] text-accent-dark hover:bg-[#DBEAFE]"
                  onClick={() => scheduleFromMail(active)}
                >
                  <CalendarPlus size={15} /> {t('Создать событие в календаре')}
                </button>
              </div>
            </article>
          </div>
        ) : (
          <div className="flex min-h-0 flex-1 flex-col">
            <EmptyState
              icon={Inbox}
              title={t('Выберите письмо')}
              description={t('Откройте письмо из списка или перетащите его в виджет «Мой день», чтобы запланировать встречу.')}
            />
            <div className="-mt-16 flex items-center justify-center gap-4 pb-10 text-xs text-faint">
              <span className="flex items-center gap-1.5">
                <span className="kbd">J</span>
                <span className="kbd">K</span> {t('навигация')}
              </span>
              <span className="flex items-center gap-1.5">
                <span className="kbd">C</span> {t('написать')}
              </span>
              <span className="flex items-center gap-1.5">
                <span className="kbd">/</span> {t('поиск')}
              </span>
            </div>
          </div>
        )}
      </section>

      {createFolderOpen && <CreateFolderModal demoMode={demoMode} onClose={() => setCreateFolderOpen(false)} onCreate={createFolder} />}
      {settingsOpen && <MailSettingsModal
        initial={preferences}
        email={sessionUser?.email ?? ''}
        demoMode={demoMode}
        onClose={() => setSettingsOpen(false)}
        onSave={(value: MailPreferences) => {
          setPreferenceState({ key: preferenceKey, value });
          setSortOrder(value.defaultSort);
          return saveMailPreferences(preferenceKey, value);
        }}
        onOpenSecurity={onOpenSecurity ? () => { setSettingsOpen(false); onOpenSecurity(); } : undefined}
        onOpenExternal={canAdminExternalAccounts ? () => { setSettingsOpen(false); setExternalOpen(true); } : undefined}
      />}

      {externalOpen && canAdminExternalAccounts && (
        <ExternalMailAccountsModal
          demoMode={demoMode}
          canAdmin={canAdminExternalAccounts}
          currentOwner={
            sessionUser
              ? {
                  id: sessionUser.id,
                  username: sessionUser.username ?? '',
                  email: sessionUser.email,
                  displayName: sessionUser.displayName,
                  department: sessionUser.department ?? null,
                  status: sessionUser.status ?? 'ACTIVE',
                  externalAccountCount: externalAccounts.length,
                }
              : null
          }
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

      {eventModalDraft && (
        <EventModal
          initial={eventModalDraft}
          onClose={() => setEventModalDraft(null)}
          onSave={(saved) => {
            const created = upsertCalendarEvent(saved);
            setCalendarSelectedDate(created.date);
            setEventModalDraft(null);
            setCalendarSavedNotice(
              `${t('Событие добавлено в календарь:')} «${created.title}» (${created.date})`,
            );
          }}
          onOpenFullCalendar={(currentDraft) => {
            setCalendarSelectedDate(currentDraft.date);
            setEventModalDraft(null);
            navigate(`/calendar?date=${currentDraft.date}`, { state: { eventDraft: currentDraft } });
          }}
        />
      )}
    </div>
  );
}

export function ComposePanel({
  initial,
  currentEmail,
  externalAccounts,
  onClose,
  onSend,
}: {
  initial: ComposeDraft;
  currentEmail: string;
  externalAccounts: ExternalMailAccount[];
  onClose: () => void;
  onSend: (draft: ComposeDraft, files: File[]) => Promise<boolean>;
}) {
  const { t } = useI18n();
  const [draft, setDraft] = useState(initial);
  const [showCc, setShowCc] = useState(Boolean(initial.cc));
  const [files, setFiles] = useState<File[]>([]);
  const [draggingFiles, setDraggingFiles] = useState(false);
  const [sent, setSent] = useState(false);
  const [sentCopySaved, setSentCopySaved] = useState(true);
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const bodyRef = useRef<HTMLTextAreaElement>(null);
  const sendingRef = useRef(false);
  const mountedRef = useRef(false);
  const closeTimerRef = useRef<number | null>(null);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      if (closeTimerRef.current !== null) window.clearTimeout(closeTimerRef.current);
    };
  }, []);
  const sendAsAccounts = externalAccounts.filter(
    (account) => account.enabled && account.sendEnabled && account.smtpEnabled,
  );

  const insertEmoji = (emoji: string) => {
    const element = bodyRef.current;
    const next = insertTextAtSelection(
      draft.body,
      emoji,
      element?.selectionStart ?? null,
      element?.selectionEnd ?? null,
    );
    setDraft({ ...draft, body: next.value });
    requestAnimationFrame(() => {
      element?.focus();
      element?.setSelectionRange(next.cursor, next.cursor);
    });
  };

  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (sendingRef.current) return;
    sendingRef.current = true;
    void (async () => {
      setSending(true);
      setSendError(null);
      try {
        const copySaved = await onSend(draft, files);
        if (!mountedRef.current) return;
        setSentCopySaved(copySaved);
        setSent(true);
        // Leave an unsaved-copy warning open until the user acknowledges it.
        if (copySaved) closeTimerRef.current = window.setTimeout(onClose, 900);
      } catch (error) {
        if (mountedRef.current) {
          // Network failures and server errors may occur after the server has
          // already queued some or all recipients. There is no idempotency key.
          const rejected = error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== 408;
          setSendError(rejected ? errorMessage(error) : `${t('Не удалось подтвердить отправку. Письмо могло попасть в очередь; перед повтором уточните статус у администратора или получателей.')} ${errorMessage(error)}`);
        }
      } finally {
        sendingRef.current = false;
        if (mountedRef.current) setSending(false);
      }
    })();
  };

  return (
    <section
      className="fixed inset-x-0 bottom-0 z-40 flex max-h-[94dvh] animate-slide-up flex-col overflow-hidden border border-line-control bg-surface shadow-composer sm:inset-x-auto sm:bottom-0 sm:right-4 sm:h-[min(620px,90dvh)] sm:w-[min(560px,calc(100vw-2rem))] sm:rounded-t-xl"
      aria-label={t('Новое письмо')}
    >
      <header className="flex h-11 shrink-0 items-center bg-sidebar px-4 text-white">
        <h3 className="flex-1 truncate text-[13.5px] font-semibold">
          {initial.subject ? t('Ответ / пересылка') : t('Новое письмо')}
        </h3>
        <button
          type="button"
          onClick={onClose}
          className="flex h-7 w-7 items-center justify-center rounded-lg text-sidebar-muted transition hover:bg-sidebar-hover hover:text-white"
          aria-label={t('Свернуть черновик')}
        >
          <Minus size={15} />
        </button>
        <button
          type="button"
          onClick={onClose}
          className="flex h-7 w-7 items-center justify-center rounded-lg text-sidebar-muted transition hover:bg-sidebar-hover hover:text-white"
          aria-label={t('Закрыть черновик')}
        >
          <X size={15} />
        </button>
      </header>

      {sent ? (
        <div className="flex flex-1 flex-col items-center justify-center px-8 text-center">
          <span className="flex h-12 w-12 items-center justify-center rounded-full bg-success-soft text-success">
            <CheckCircle2 size={24} />
          </span>
          <h4 className="mt-3 text-base font-semibold text-ink">{t('Письмо отправлено')}</h4>
          <p className={`mt-1 text-xs ${sentCopySaved ? 'text-muted' : 'text-danger'}`} role={sentCopySaved ? undefined : 'alert'}>
            {sentCopySaved ? t('Копия сохранена в папке «Отправленные».') : t('Копию не удалось сохранить в папке «Отправленные». Повторно отправлять письмо не нужно.')}
          </p>
        </div>
      ) : (
        <form
          onSubmit={submit}
          className={`flex min-h-0 flex-1 flex-col ${draggingFiles ? 'ring-2 ring-inset ring-accent' : ''}`}
          onDragOver={(event) => {
            if (Array.from(event.dataTransfer.types).includes('Files')) {
              event.preventDefault();
              setDraggingFiles(true);
            }
          }}
          onDragLeave={(event) => {
            if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDraggingFiles(false);
          }}
          onDrop={(event) => {
            event.preventDefault();
            setDraggingFiles(false);
            const dropped = Array.from(event.dataTransfer.files);
            if (dropped.length) setFiles((previous) => [...previous, ...dropped]);
          }}
        >
          {draggingFiles && (
            <div className="bg-accent-soft px-4 py-2 text-xs font-medium text-accent-dark">
              {t('Отпустите файлы, чтобы прикрепить')}
            </div>
          )}
          {sendAsAccounts.length > 0 && (
            <div className="flex h-11 shrink-0 items-center gap-3 border-b border-line-subtle px-4">
              <label htmlFor="compose-from" className="w-14 shrink-0 text-xs font-medium text-muted">
                {t('От кого')}
              </label>
              <select
                id="compose-from"
                value={draft.externalAccountId ?? ''}
                onChange={(e) => setDraft({ ...draft, externalAccountId: e.target.value || null })}
                className="min-w-0 flex-1 border-0 bg-transparent text-[13.5px] text-ink outline-none focus:ring-0"
              >
                <option value="">{currentEmail} · Corvex</option>
                {sendAsAccounts.map((account) => (
                  <option key={account.id} value={account.id}>
                    {account.emailAddress} · {account.label}
                  </option>
                ))}
              </select>
            </div>
          )}
          <div className="flex h-11 shrink-0 items-center gap-3 border-b border-line-subtle px-4">
            <label htmlFor="compose-to" className="w-14 shrink-0 text-xs font-medium text-muted">
              {t('Кому')}
            </label>
            <input
              id="compose-to"
              required
              type="text"
              inputMode="email"
              value={draft.to}
              onChange={(e) => setDraft({ ...draft, to: e.target.value })}
              placeholder="user@company.local"
              className="min-w-0 flex-1 border-0 bg-transparent text-[13.5px] text-ink outline-none placeholder:text-faint focus:ring-0"
            />
            {!showCc && (
              <button
                type="button"
                onClick={() => setShowCc(true)}
                className="shrink-0 text-xs font-medium text-muted hover:text-ink"
              >
                {t('Копия')}
              </button>
            )}
          </div>
          {showCc && (
            <div className="flex h-11 shrink-0 items-center gap-3 border-b border-line-subtle px-4">
              <label htmlFor="compose-cc" className="w-14 shrink-0 text-xs font-medium text-muted">
                {t('Копия')}
              </label>
              <input
                id="compose-cc"
                type="text"
                inputMode="email"
                value={draft.cc ?? ''}
                onChange={(e) => setDraft({ ...draft, cc: e.target.value })}
                placeholder="user@company.local"
                className="min-w-0 flex-1 border-0 bg-transparent text-[13.5px] text-ink outline-none placeholder:text-faint focus:ring-0"
              />
            </div>
          )}
          <div className="flex h-11 shrink-0 items-center gap-3 border-b border-line-subtle px-4">
            <label htmlFor="compose-subject" className="w-14 shrink-0 text-xs font-medium text-muted">
              {t('Тема')}
            </label>
            <input
              id="compose-subject"
              required
              value={draft.subject}
              onChange={(e) => setDraft({ ...draft, subject: e.target.value })}
              placeholder={t('О чём письмо?')}
              className="min-w-0 flex-1 border-0 bg-transparent text-[13.5px] font-medium text-ink outline-none placeholder:font-normal placeholder:text-faint focus:ring-0"
            />
          </div>
          <textarea
            ref={bodyRef}
            required
            value={draft.body}
            onChange={(e) => setDraft({ ...draft, body: e.target.value })}
            placeholder={t('Напишите сообщение…')}
            aria-label={t('Текст письма')}
            className="min-h-[180px] flex-1 resize-none border-0 bg-transparent px-4 py-3.5 text-[14px] leading-relaxed text-ink outline-none placeholder:text-faint focus:ring-0"
          />
          {files.length > 0 && (
            <div className="flex shrink-0 flex-wrap gap-1.5 border-t border-line-subtle bg-canvas px-4 py-2">
              {files.map((file, index) => (
                <span key={`${file.name}-${index}`} className="chip">
                  <Paperclip size={12} />
                  {file.name}
                  <button
                    type="button"
                    className="ml-1 rounded p-0.5 hover:text-danger"
                    aria-label={`${t('Удалить вложение')}: ${file.name}`}
                    onClick={() => setFiles((current) => current.filter((_, i) => i !== index))}
                  >
                    <X size={13} />
                  </button>
                </span>
              ))}
            </div>
          )}
          {sendError && (
            <div
              className="mx-4 mb-3 shrink-0 rounded-xl border border-red-200 bg-red-50 px-4 py-2.5 text-xs text-red-800"
              role="alert"
            >
              {sendError}
            </div>
          )}
          <footer className="flex shrink-0 items-center gap-2 border-t border-line bg-canvas px-3.5 py-2.5">
            <button className="btn-primary" disabled={sending}>
              <Send size={14} /> {t('Отправить')}
            </button>
            <input
              ref={fileRef}
              type="file"
              multiple
              className="hidden"
              onChange={(e) => {
                const picked = Array.from(e.target.files ?? []);
                setFiles((previous) => [...previous, ...picked]);
                e.target.value = '';
              }}
            />
            <IconButton icon={Paperclip} label={t('Прикрепить файлы')} onClick={() => fileRef.current?.click()} />
            <EmojiPickerButton onPick={insertEmoji} />
            <span className="ml-1 hidden text-[11px] text-faint sm:inline">
              {t('Подпись настраивается в почте')}
            </span>
            <IconButton icon={Trash2} label={t('Удалить черновик')} className="ml-auto" onClick={onClose} />
          </footer>
        </form>
      )}
    </section>
  );
}
