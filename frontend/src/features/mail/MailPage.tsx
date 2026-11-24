import { FormEvent, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Archive,
  ArrowLeft,
  ArrowUpDown,
  Ellipsis,
  Calendar as CalendarIcon,
  CalendarPlus,
  Check,
  ChevronDown,
  ChevronUp,
  Clock,
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
  Printer,
  Reply,
  ReplyAll,
  Search,
  Send,
  Settings2,
  MailPlus,
  SlidersHorizontal,
  Users,
  Sparkles,
  Star,
  Trash2,
  UserPlus,
  X,
} from 'lucide-react';
import { demoFolders, demoMails, DemoMail } from '../../api/demoData';
import { externalMailApi, ExternalMailAccount } from '../../api/externalMailApi';
import { contactsApi } from '../../api/contactsApi';
import { onboardingApi, type OnboardingView } from '../../api/onboardingApi';
import { formatBytes } from '../../api/driveApi';
import { ApiError } from '../../api/httpClient';
import {
  mailApi,
  MailboxQuota,
  type SavedDraft,
  MailFolderView,
  MailMessageListItem,
  MailAttachmentView,
  SendAttachment,
  type ScheduledSend,
} from '../../api/mailApi';
import { CORVEX_MAIL_DND_MIME } from '../../components/CalendarSidebarWidget';
import SidebarSlot, { useSidebarSlotContext } from '../../components/SidebarSlot';
import { Avatar, EmptyState, IconButton, cleanPersonName, emailFromHeader } from '../../components/ui';
import { useToast } from '../../components/ToastProvider';
import WorkspaceUtilityBar from '../../components/WorkspaceUtilityBar';
import { formatDateTime, useI18n } from '../../i18n';
import { truncateGraphemes } from '../../lib/text';
import {
  buildEventDraftFromMail,
  CalendarEventDraft,
  useCalendarStore,
} from '../../store/calendarStore';
import { demoMailIsSeen, useSession } from '../../store/sessionStore';
import EventModal from '../calendar/EventModal';
import ExternalMailAccountsModal from './ExternalMailAccountsModal';
import ContactsModal from './ContactsModal';
import MigrationWizard from './MigrationWizard';
import CreateFolderModal from './CreateFolderModal';
import MailSettingsModal from './MailSettingsModal';
import { bodyWithSignature, loadMailPreferences, preferencesFromServer, mailPreferencesKey, preferencesToServer, saveMailPreferences, type MailPreferences } from './mailPreferences';
import { canAdministerExternalMail, canUseExternalMail } from './externalMailAccess';
import MailAttachments from './MailAttachments';
import { usePreview } from '../preview/PreviewProvider';
import MailboxUsage from './MailboxUsage';
import { hasRemoteImages } from './mailHtml';
import MailHtmlFrame from './MailHtmlFrame';
import { mailPrintDocument } from '../print/printDocument';
import { openPrintWindow, printHtmlDocument } from '../print/printing';
import { isMailPrintShortcut } from '../print/printShortcut';
import {
  applyMailListControls,
  groupMailByDate,
  hasActiveMailFilters,
  type MailDateGroup,
  type MailReadFilter,
  type MailSortOrder,
} from './mailListControls';
import { conversationCountLabel, groupIntoConversations } from './conversations';
import { formatMailRecipients, replyAllCc } from './mailRecipients';
import {
  activeMailAfterRefresh,
  hasMorePages,
  mergeRefreshedFolder,
  nextPageCursor,
  replaceFolderMessages,
} from './mailRefresh';
import MailSidebarActions from './MailSidebarActions';
import { BulkActionBar, SelectMessage, SelectVisibleMessages } from './BulkSelection';
import { ComposePanel, type ComposeDraft } from './ComposePanel';
import { buildReplyBody, replySubject } from './replyBody';
import { loadOfflineSnapshot, offlineBannerText, offlineSnapshotAge, saveOfflineSnapshot } from '../../lib/offlineCache';
import DraftsPanel from './DraftsPanel';
import ShortcutsHelp from './ShortcutsHelp';
import { moveSequentially } from './bulkMail';
import {
  buildForwardBody,
  forwardBlockLabelKey,
  forwardSubject,
  planForwardAttachments,
  type ForwardBlocked,
} from './forwardMail';

type FolderTab = ReturnType<typeof demoFolders>[number];
const READ_FILTERS = ['all', 'unread', 'read'] as const;
/** Совпадает с серверным значением по умолчанию: 50 строк на страницу папки. */
const MAIL_PAGE_SIZE = 50;

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
/** Метка о том, что возврат из OAuth относится к повторному входу, а не к новому ящику. */
const RELINK_STORAGE_KEY = 'corvex.oauth.relink';

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
  const filterId = useId();
  const navigate = useNavigate();
  const { onOpenCommandPalette, onOpenCalendarWidget, onOpenSecurity, onCloseNavigation } = useSidebarSlotContext();

  const { notify } = useToast();
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
  // Сервер отдаёт папку страницами по курсору UID; без этого списка видно ровно 50 писем.
  const [paging, setPaging] = useState<{ cursorUid?: number; hasMore: boolean; loading: boolean }>(
    { cursorUid: undefined, hasMore: false, loading: false },
  );
  const [mobileReading, setMobileReading] = useState(false);
  const [composeDraft, setComposeDraft] = useState<ComposeDraft | null>(null);
  const [contactsOpen, setContactsOpen] = useState(false);
  const [contactEmails, setContactEmails] = useState<string[]>([]);
  const [migrationOpen, setMigrationOpen] = useState(false);
  const [oauthReturn, setOauthReturn] = useState<
    { id: string; provider: 'GOOGLE' | 'MICROSOFT'; email: string } | null
  >(null);
  const [oauthError, setOauthError] = useState<string | null>(null);
  const [oauthNotice, setOauthNotice] = useState<string | null>(null);
  const [onboarding, setOnboarding] = useState<OnboardingView | null>(null);
  const migrationOfferedRef = useRef(false);

  // Предложение переноса показывается один раз за сессию: сервер решает, уместно ли оно,
  // а браузер не должен открывать мастер при каждом переходе в почту.
  useEffect(() => {
    if (demoMode) return;
    let cancelled = false;
    onboardingApi.state()
      .then((state) => {
        if (cancelled) return;
        setOnboarding(state);
        if (state.migrationSuggested && !migrationOfferedRef.current) {
          migrationOfferedRef.current = true;
          setMigrationOpen(true);
        }
      })
      .catch(() => undefined);
    return () => { cancelled = true; };
  }, [demoMode]);

  const dismissMigration = () => {
    onboardingApi.dismiss().then(setOnboarding).catch(() => undefined);
    setMigrationOpen(false);
  };

  const notNeededMigration = () => {
    onboardingApi.notNeeded().then(setOnboarding).catch(() => undefined);
    setMigrationOpen(false);
  };

  // Подсказки адресов в композере. Ошибка загрузки не должна мешать почте:
  // без адресной книги поле «Кому» просто остаётся обычным вводом.
  useEffect(() => {
    if (demoMode) return;
    let cancelled = false;
    contactsApi.list('', 1000)
      .then((items) => { if (!cancelled) setContactEmails(items.map((item) => item.email)); })
      .catch(() => undefined);
    return () => { cancelled = true; };
  }, [demoMode]);
  // Черновик, открытый из списка: композер продолжает его, а не создаёт новый.
  const [resumeDraft, setResumeDraft] = useState<SavedDraft | null>(null);
  const [draftsOpen, setDraftsOpen] = useState(false);
  // Каждое открытие композера — своя сессия: иначе повторное «Написать» с теми же
  // адресатом и темой переиспользовало бы старый экземпляр с прежним текстом.
  const [composeSession, setComposeSession] = useState(0);
  // Вложения групповой пересылки: их нельзя положить в ComposeDraft (там только текст).
  const [composeFiles, setComposeFiles] = useState<File[]>([]);

  useEffect(() => {
    if (!composeDraft) setResumeDraft(null);
  }, [composeDraft]);
  const [externalAccounts, setExternalAccounts] = useState<ExternalMailAccount[]>([]);
  const [externalOpen, setExternalOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [createFolderOpen, setCreateFolderOpen] = useState(false);
  const [movingMailIds, setMovingMailIds] = useState<Set<string>>(() => new Set());
  // Групповые действия работают только с явно выбранным снимком загруженных писем.
  const [bulkSelection, setBulkSelection] = useState<string[]>([]);
  const [bulkBusy, setBulkBusy] = useState(false);
  const [bulkNotice, setBulkNotice] = useState('');
  const [shortcutsHelpOpen, setShortcutsHelpOpen] = useState(false);
  const [dropTarget, setDropTarget] = useState<string | null>(null);
  const [moreMenuOpen, setMoreMenuOpen] = useState(false);
  const morePanelRef = useRef<HTMLDivElement>(null);
  const bulkBusyRef = useRef(false);
  // Внешние изображения в письме не загружаются, пока пользователь не разрешил.
  const [showRemoteImages, setShowRemoteImages] = useState(false);
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
  const [downloadAllBusy, setDownloadAllBusy] = useState(false);
  /**
   * Отложенные отправки (P2-6). Список нужен, чтобы человек мог отменить
   * письмо до его ухода: без этого «Отправить позже» — ловушка без выхода.
   */
  const [scheduledSends, setScheduledSends] = useState<ScheduledSend[]>([]);
  /** Синхронизация настроек с сервером: молчать о её сбое нельзя. */
  const [settingsSynced, setSettingsSynced] = useState<'idle' | 'saved' | 'error'>('idle');
  const { openPreview } = usePreview();
  const [loadedRecipients, setLoadedRecipients] = useState<Record<string, { to: string[]; cc: string[] }>>({});
  // Пока идёт первая загрузка папки, список не должен показывать «пусто».
  const [listLoading, setListLoading] = useState(!demoMode);
  // Офлайн-снимок: время сохранения, если список показан из кэша.
  const [offlineSavedAt, setOfflineSavedAt] = useState<number | null>(null);
  const [offline, setOffline] = useState(typeof navigator !== 'undefined' && navigator.onLine === false);
  // Повтор после ошибки: загрузчик папки вызывается из того же места, что и первый раз.
  const reloadRef = useRef<(() => void) | null>(null);
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

  // Выбор и разрешение на картинки относятся к конкретному списку/письму:
  // после смены папки, фильтра или открытого письма их нельзя переносить молча.
  useEffect(() => {
    setBulkSelection([]);
    setBulkNotice('');
  }, [folder, search, filter, onlyFlagged, onlyAttachments, sortOrder]);

  useEffect(() => {
    setShowRemoteImages(false);
  }, [activeId]);

  // Результат поиска по загруженному окну может быть большим, а DOM — нет:
  // показываем первые 50 совпадений и даём кнопку «Ещё». Счётчик совпадений
  // остаётся полным, поэтому «нашлось 700» не превращается в «нашлось 50».
  const SEARCH_INITIAL_LIMIT = 50;
  const [searchLimit, setSearchLimit] = useState(SEARCH_INITIAL_LIMIT);
  // Серверный поиск: локальный фильтр не видит письма за пределами загруженной
  // страницы, поэтому в реальном режиме ищем на сервере (с задержкой на ввод).
  const [serverHits, setServerHits] = useState<DemoMail[] | null>(null);
  const [serverSearchError, setServerSearchError] = useState<string | null>(null);
  const [searchPending, setSearchPending] = useState(false);
  const matched = useMemo(
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
  const searchSource = serverHits ?? matched;
  const visible = useMemo(
    () => (search ? searchSource.slice(0, searchLimit) : matched),
    [searchSource, matched, search, searchLimit],
  );
  /**
   * Поиск в реальном режиме идёт на сервер: письмо из 2023 года отсутствует в
   * загруженных 50, и локальная фильтрация честно не могла его найти. Пока
   * ответа нет, показываем прежнее поведение (фильтр по загруженным), чтобы
   * список не мигал пустотой на каждый символ.
   */
  useEffect(() => {
    if (demoMode || !search.trim()) {
      setServerHits(null);
      setServerSearchError(null);
      setSearchPending(false);
      return undefined;
    }
    const controller = new AbortController();
    setSearchPending(true);
    const timer = window.setTimeout(() => {
      mailApi.search(search.trim(), 100, controller.signal)
        .then((results) => {
          if (controller.signal.aborted) return;
          const mapped = results.map((hit) => {
            const folderKey = realFolders.find((f) => f.id === hit.folderId)?.specialUse
              ?? realFolders.find((f) => f.id === hit.folderId)?.id
              ?? folders[0]?.id ?? 'INBOX';
            const timestamp = hit.internalDate ? Date.parse(hit.internalDate) : 0;
            return {
              id: hit.messageId,
              uid: hit.uid,
              folder: folderKey,
              from: hit.sender,
              subject: hit.subject,
              snippet: hit.snippet,
              body: hit.snippet,
              date: hit.internalDate ? formatDateTime(locale, hit.internalDate) : '',
              timestamp: Number.isFinite(timestamp) ? timestamp : 0,
              seen: seenDesiredRef.current.get(hit.messageId) ?? true,
              flagged: flagDesiredRef.current.get(hit.messageId) ?? false,
              hasAttachments: false,
            } satisfies DemoMail;
          });
          setServerHits(mapped);
          setServerSearchError(null);
        })
        .catch((error) => {
          if (controller.signal.aborted) return;
          setServerHits(null);
          // 503 — индекс недоступен. Это не «ничего не найдено», и человек
          // должен видеть разницу.
          setServerSearchError(error instanceof ApiError && error.status === 503
            ? t('Поиск временно недоступен: индекс ещё не готов. Пока показываем совпадения среди загруженных писем.')
            : errorMessage(error));
        })
        .finally(() => { if (!controller.signal.aborted) setSearchPending(false); });
    }, 350);
    return () => { controller.abort(); window.clearTimeout(timer); };
  }, [search, demoMode, realFolders, folders, locale, t]);

  // Группы «Сегодня / Вчера / …» — только при сортировке по дате.
  /**
   * Цепочки (P2-1). Группировка идёт по `visible`, то есть по тому, что человек
   * уже видит: поиск, фильтры и папка применены. Выключить можно в настройках —
   * тогда остаётся прежний плоский список.
   */
  const conversationsEnabled = preferences.conversations;
  const [expandedThreads, setExpandedThreads] = useState<Set<string>>(() => new Set());
  const threads = useMemo(
    () => (conversationsEnabled ? groupIntoConversations(visible) : []),
    [conversationsEnabled, visible],
  );
  /** Что рисуется в списке: цепочка-строка или, в плоском режиме, само письмо. */
  const listRows = useMemo<DemoMail[]>(
    () => (conversationsEnabled
      ? threads.map((thread) => thread.latest)
      : visible),
    [conversationsEnabled, threads, visible],
  );
  const threadByLatestId = useMemo(
    () => new Map(threads.map((thread) => [thread.latest.id, thread])),
    [threads],
  );
  const mailGroups = useMemo(
    () => groupMailByDate(listRows, sortOrder),
    [listRows, sortOrder],
  );
  const toggleThread = (key: string) => {
    setExpandedThreads((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };
  /** Смещение каждой группы в общем списке — для Shift-стрелок. */
  const mailGroupStarts = useMemo(() => {
    let offset = 0;
    return mailGroups.map(({ mails: groupMails }) => {
      const start = offset;
      offset += groupMails.length;
      return start;
    });
  }, [mailGroups]);
  const dateGroupLabel = (group: MailDateGroup) => ({
    today: 'Сегодня',
    yesterday: 'Вчера',
    week: 'На этой неделе',
    month: 'В этом месяце',
    older: 'Ранее',
  } as const)[group];

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
      if (!morePanelRef.current?.contains(target)) setMoreMenuOpen(false);
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

  /**
   * Единственная точка открытия композера.
   *
   * Раньше второй вызов молча перезаписывал первый: человек писал письмо,
   * нажимал «Ответить» в списке — и текст исчезал вместе с вложениями.
   * Теперь при непустом черновике сначала спрашиваем; отказ оставляет как было.
   */
  const openDraft = (draft: ComposeDraft, kind: 'new' | 'reply' | 'forward') => {
    const busy = composeDraft && (
      composeDraft.to || composeDraft.cc || composeDraft.bcc || composeDraft.subject
      || composeDraft.body || composeFiles.length > 0
    );
    if (busy && !window.confirm(t('В другом письме уже есть текст. Открыть новое, сохранив текущее в черновиках?'))) {
      return;
    }
    setComposeSession((session) => session + 1);
    setComposeFiles([]);
    setResumeDraft(null);
    setComposeDraft({ ...draft, body: bodyWithSignature(draft.body, preferences, kind) });
  };

  /**
   * Ответ на открытое письмо: цитата и связь с оригиналом.
   * `inReplyToMessageId` уходит на сервер — он ставит In-Reply-To/References,
   * поэтому переписка собирается в цепочку у получателя.
   */
  const replyToActive = (all: boolean) => {
    if (!active) return;
    const body = buildReplyBody(
      {
        id: active.id,
        from: active.from,
        subject: active.subject,
        date: active.date,
        body: activeBody,
        to: activeRecipientData?.to,
        cc: activeRecipientData?.cc,
      },
      { senderLabel: t('От'), wroteLabel: t('писал(а)') },
    );
    openDraft({
      to: emailFromHeader(active.from),
      cc: all ? replyCc || undefined : undefined,
      subject: replySubject(active.subject),
      body,
      inReplyToMessageId: active.id,
    }, 'reply');
  };

  /** Открыть серверный черновик: он продолжается с той же ревизией и вложениями. */
  const openServerDraft = async (draftId: string) => {
    try {
      const saved = await mailApi.draft(draftId);
      setDraftsOpen(false);
      setComposeSession((session) => session + 1);
      setResumeDraft(saved);
      setComposeDraft({
        to: saved.payload.to,
        cc: saved.payload.cc,
        subject: saved.payload.subject,
        body: saved.payload.bodyText,
        bodyHtml: saved.payload.bodyHtml || undefined,
        externalAccountId: saved.payload.externalAccountId,
        // Без этих двух полей черновик ответа терял ветку, а отложенное
        // письмо — запланированное время.
        inReplyToMessageId: saved.payload.inReplyToMessageId ?? null,
        sendAt: saved.payload.sendAt ?? null,
      });
    } catch (error) {
      setLoadError(`${t('Не удалось открыть черновик.')} ${errorMessage(error)}`);
    }
  };

  // Явное «назад к списку» не должно снова открывать письмо само.
  const [readingClosed, setReadingClosed] = useState(false);
  useEffect(() => { setReadingClosed(false); }, [activeId, folder]);

  /**
   * `?mailId=…` — ссылка на письмо извне (уведомление, календарь, закладка).
   * Свой выбор письма в адрес не пишем: каждый шаг `j`/`k` менял бы URL и
   * перезапускал эффекты выше, которые сравнивают `searchParams` по ссылке.
   * Кнопка «назад» в браузере от этого не страдает — почта остаётся открытой.
   */
  // `u` убирает параметр, чтобы адрес и экран не расходились.
  const closeReading = () => {
    setMobileReading(false);
    setReadingClosed(true);
    const next = new URLSearchParams(searchParams);
    next.delete('mailId');
    setSearchParams(next, { replace: true });
    document.querySelector<HTMLElement>('[data-mail-list]')?.focus();
  };
  const active = readingClosed && !mobileReading
    ? null
    : mails.find((m) => m.id === activeId && m.folder === folder) ?? visible[0] ?? null;
  const currentViewRef = useRef<{ folder: string; activeId: string | null }>({ folder, activeId: active?.id ?? null });
  useLayoutEffect(() => {
    currentViewRef.current = { folder, activeId: active?.id ?? null };
  }, [folder, active?.id]);
  const activeBody = active ? (loadedBodies[active.id] ?? active.body) : '';
  const activeHtml = active ? loadedHtml[active.id] : null;
  // Проверяем до рендера: иначе счётчик запросов к отправителю уже был бы отправлен.
  const remoteImages = useMemo(() => (activeHtml ? hasRemoteImages(activeHtml) : false), [activeHtml]);
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

  const downloadAttachment = async (
    messageId: string,
    attachment: MailAttachmentView,
    options: { force?: boolean } = {},
  ) => {
    const key = `${messageId}:${attachment.partIndex}`;
    if (downloadBusy && !options.force) return;
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

  /**
   * «Скачать все»: по одному файлу подряд.
   *
   * ZIP сервер не отдаёт, а браузер (кроме Chrome) блокирует несколько
   * одновременных скачиваний. Поэтому файлы идут очередью, и каждый — с
   * задержкой, чтобы браузер не принял пачку за автоматическую загрузку.
   */
  const downloadAllAttachments = async () => {
    if (!active || downloadAllBusy) return;
    if (demoMode) {
      notify({ message: t('Вложение недоступно в демонстрационном режиме'), tone: 'info' });
      return;
    }
    const list = loadedAttachments[active.id] ?? [];
    if (list.length === 0) return;
    setDownloadAllBusy(true);
    try {
      for (const attachment of list) {
        // Файлы, заблокированные антивирусом, сервер всё равно не отдаст:
        // не тратим на них запрос и сразу говорим, что пропущено.
        if (attachment.avStatus === 'INFECTED') continue;
        await downloadAttachment(active.id, attachment, { force: true });
        await new Promise((resolve) => window.setTimeout(resolve, 250));
      }
      notify({ message: t('Скачивание вложений завершено.'), tone: 'success' });
    } finally {
      setDownloadAllBusy(false);
    }
  };

  /**
   * Отправитель письма → контакт. Кнопка в шапке письма: после первого
   * ответа человеку обычно и нужен этот адрес в книге, а искать его в
   * отдельном окне контактов — лишний шаг.
   */
  /**
   * «Отложить до…»: письмо исчезает из списка папки до срока, но остаётся на
   * сервере. Возврат — через обновление папки или вручную из списка «Отложенные»,
   * поэтому отменять snooze можно и раньше, повторным запросом.
   */
  const snoozeActiveMail = async (hours: number) => {
    if (!active) return;
    const snoozedId = active.id;
    if (demoMode) {
      notify({ message: t('Отложить письмо можно при подключении к серверу.'), tone: 'info' });
      return;
    }
    const wakeAt = new Date(Date.now() + hours * 3600_000);
    try {
      await mailApi.snooze(active.id, wakeAt.toISOString());
      setMails((prev) => prev.filter((mail) => mail.id !== active.id));
      setActiveId('');
      notify({
        message: t('Письмо вернётся {time}.', { time: wakeAt.toLocaleString() }),
        tone: 'success',
        action: { label: t('Вернуть сейчас'), onSelect: () => void unsnoozeMail(snoozedId) },
      });
    } catch (error) {
      notify({ message: `${t('Не удалось отложить письмо.')} ${errorMessage(error)}`, tone: 'error' });
    }
  };

  const unsnoozeMail = async (messageId: string) => {
    try {
      await mailApi.wake(messageId);
      notify({ message: t('Письмо возвращено в список.'), tone: 'success' });
      reloadRef.current?.();
    } catch (error) {
      notify({ message: `${t('Не удалось вернуть письмо.')} ${errorMessage(error)}`, tone: 'error' });
    }
  };

  const addSenderToContacts = async () => {
    if (!active) return;
    const email = emailFromHeader(active.from);
    if (!email) {
      notify({ message: t('У письма нет адреса отправителя.'), tone: 'error' });
      return;
    }
    if (contactEmails.some((item) => item.toLowerCase() === email.toLowerCase())) {
      setLoadError(t('Этот адрес уже есть в адресной книге.'));
      return;
    }
    if (demoMode) {
      setContactEmails((prev) => [...prev, email]);
      notify({ message: t('Адрес добавлен в контакты: {email}', { email }), tone: 'success' });
      return;
    }
    try {
      await contactsApi.create({ displayName: cleanPersonName(active.from) || email, email });
      setContactEmails((prev) => [...prev, email]);
      notify({ message: t('Адрес добавлен в контакты: {email}', { email }), tone: 'success' });
    } catch (error) {
      notify({ message: `${t('Не удалось добавить адрес в контакты.')} ${errorMessage(error)}`, tone: 'error' });
    }
  };

  // Вложения одного письма открываются списком: ←/→ листают их без закрытия
  // диалога, как в Gmail. Файл не скачивается целиком, пока не открыт предпросмотр.
  const openAttachmentPreview = (
    messageId: string,
    attachments: MailAttachmentView[],
    selected: MailAttachmentView,
  ) => {
    openPreview(
      attachments.map((attachment) => ({
        fileName: attachment.fileName || t('вложение'),
        contentType: attachment.contentType,
        sizeBytes: attachment.sizeBytes,
        avStatus: attachment.avStatus,
        load: (signal: AbortSignal) => mailApi.downloadAttachment(messageId, attachment.partIndex, signal),
        // Office конвертируется на сервере в PDF; при 501 диалог честно предложит скачать.
        loadPdf: (signal: AbortSignal) => mailApi.previewAttachment(messageId, attachment.partIndex, signal),
        download: () => downloadAttachment(messageId, attachment),
      })),
      Math.max(0, attachments.findIndex((attachment) => attachment.partIndex === selected.partIndex)),
    );
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
  // Печать письма целиком: отдельный документ, а не `window.print()` страницы.
  // Печатает шапку (от/кому/копия/дата/тема) и тело, вложения — только перечнем.
  const printActiveMail = async () => {
    if (!active) return;
    const html = mailPrintDocument({
      subject: active.subject,
      from: active.from,
      to: recipientText,
      cc: ccText || undefined,
      date: active.date,
      html: activeHtml && !showPlainText ? activeHtml : undefined,
      text: activeHtml && !showPlainText ? undefined : activeBody,
      attachments: (loadedAttachments[active.id] ?? []).map((attachment) => {
        // У вложений письма нет размера до загрузки: печатаем только имя.
        const size = (attachment as { sizeBytes?: number | null }).sizeBytes;
        return size != null ? `${attachment.fileName} (${formatBytes(size, locale)})` : attachment.fileName;
      }),
      allowRemoteImages: showRemoteImages,
      labels: {
        from: t('От'),
        to: t('Кому'),
        cc: t('Копия'),
        date: t('Дата'),
        attachments: t('Вложения'),
      },
    });
    const outcome = await printHtmlDocument(html);
    // Safari на iOS игнорирует print() у невидимого фрейма: тогда открываем
    // документ в новом табе, где печать вызывается самим пользователем.
    if (!outcome.printed) openPrintWindow(html);
  };

  const replyAllReady = demoMode || activeRecipientData !== undefined;
  const replyCc = active
    ? replyAllCc(active.from, activeRecipientData?.to ?? [], activeRecipientData?.cc ?? [], sessionUser?.email)
    : '';

  const toMailView = (item: MailMessageListItem, folderKey: string): DemoMail => {
    const timestamp = Date.parse(item.date);
    return {
      id: item.id,
      uid: item.uid,
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
  const selectedRealFolderIdRef = useRef(selectedRealFolderId);
  selectedRealFolderIdRef.current = selectedRealFolderId;

  // Новая папка — новый список: старый курсор к ней не относится.
  useEffect(() => {
    setPaging({ cursorUid: undefined, hasMore: false, loading: false });
  }, [selectedRealFolderId, demoMode]);

  useEffect(() => {
    if (demoMode || !selectedRealFolderId || movingMailIds.size > 0) {
      setListLoading(false);
      return undefined;
    }
    let cancelled = false;
    const load = () => {
      const seenRevision = seenRevisionRef.current;
      const mailRevision = mailRevisionRef.current;
      const sequence = beginMessageSnapshot(selectedRealFolderId);
      // Пустая папка и незавершённая загрузка не должны выглядеть одинаково.
      if (mails.length === 0) setListLoading(true);
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
        // Снимок для чтения без сети: только то, что владелец уже видел.
        if (sessionUser?.id) {
          saveOfflineSnapshot(sessionUser.id, `folder:${folder}`, mapped, Date.now());
        }
        setOfflineSavedAt(null);
        setPaging({
          cursorUid: nextPageCursor(mapped),
          hasMore: hasMorePages(items.length, MAIL_PAGE_SIZE),
          loading: false,
        });
        setActiveId((current) => linked?.folder === folder ? linked.id : mapped.some((mail) => mail.id === current) ? current : mapped[0]?.id ?? '');
        setListLoading(false);
      }).catch((error) => {
        if (cancelled || sequence !== messageSnapshotRef.current.get(selectedRealFolderId)) return;
        setListLoading(false);
        const ownerId = sessionUser?.id;
        const cached = ownerId ? loadOfflineSnapshot<DemoMail[]>(ownerId, `folder:${folder}`) : null;
        if (cached?.length && ownerId) {
          // Показать последний снимок честнее, чем пустой экран с ошибкой:
          // подпись говорит, что это копия и когда она сделана.
          setMails((prev) => replaceFolderMessages(prev, folder, cached));
          const age = offlineSnapshotAge(ownerId, `folder:${folder}`);
          setOfflineSavedAt(age == null ? Date.now() : Date.now() - age);
          setLoadError(null);
          return;
        }
        setLoadError(errorMessage(error));
      });
    };
    // Кнопка «Повторить» в сообщении об ошибке вызывает именно этот загрузчик.
    reloadRef.current = load;
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
        // Первая страница не должна стирать уже подгруженные старые страницы.
        setMails((prev) => mergeRefreshedFolder(prev, folder, mapped));
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

  const loadMoreSentinelRef = useRef<HTMLDivElement>(null);
  /** Следующая страница папки по курсору UID: сервер отдаёт письма старше курсора. */
  const loadMoreMessages = async () => {
    const folderId = selectedRealFolderId;
    const cursorUid = paging.cursorUid;
    if (!folderId || demoMode || paging.loading || cursorUid == null) return;
    setPaging((prev) => ({ ...prev, loading: true }));
    try {
      const items = await mailApi.messages(folderId, cursorUid, MAIL_PAGE_SIZE);
      if (selectedRealFolderIdRef.current !== folderId) return;
      const mapped = items.map((item) => toMailView(item, folder));
      setMails((prev) => {
        const shown = new Set(prev.filter((mail) => mail.folder === folder).map((mail) => mail.id));
        return [...prev, ...mapped.filter((mail) => !shown.has(mail.id))];
      });
      setPaging({
        cursorUid: nextPageCursor(mapped) ?? cursorUid,
        hasMore: hasMorePages(items.length, MAIL_PAGE_SIZE),
        loading: false,
      });
    } catch (error) {
      setLoadError(errorMessage(error));
      setPaging((prev) => ({ ...prev, loading: false }));
    }
  };

  // Бесконечная прокрутка. Наблюдатель пересоздаётся при изменении состояния
  // подгрузки: после успешной страницы нужно снова «поймать» нижнюю границу.
  useEffect(() => {
    const sentinel = loadMoreSentinelRef.current;
    if (!sentinel || demoMode || !paging.hasMore || paging.loading) return undefined;
    if (typeof IntersectionObserver === 'undefined') return undefined;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void loadMoreMessages();
    }, { rootMargin: '300px 0px' });
    observer.observe(sentinel);
    return () => observer.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [demoMode, paging.hasMore, paging.loading, paging.cursorUid, folder]);

  // Сеть вернулась — убираем подпись «показана копия» и даём эффекту загрузки
  // перезапросить папку.
  useEffect(() => {
    const update = () => setOffline(typeof navigator !== 'undefined' && navigator.onLine === false);
    window.addEventListener('online', update);
    window.addEventListener('offline', update);
    return () => {
      window.removeEventListener('online', update);
      window.removeEventListener('offline', update);
    };
  }, []);

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

  // Возврат из Google/Microsoft: провайдер приводит браузер на callback, а тот — сюда.
  useEffect(() => {
    const outcome = searchParams.get('oauth');
    if (!outcome) return;
    const connection = searchParams.get('connection');
    const provider = searchParams.get('provider');
    const email = searchParams.get('email');
    const reason = searchParams.get('reason');
    if (outcome === 'connected' && connection && (provider === 'GOOGLE' || provider === 'MICROSOFT') && email) {
      // Повторный вход уже подключённого ящика: заменяем токены, ничего не создавая заново.
      const relinkId = window.sessionStorage.getItem(RELINK_STORAGE_KEY);
      window.sessionStorage.removeItem(RELINK_STORAGE_KEY);
      if (relinkId) {
        void externalMailApi.update(relinkId, { authType: 'OAUTH2', oauthConnectionId: connection })
          .then(() => setOauthNotice(t('Вход восстановлен: ящик снова получает почту')))
          .catch((relinkError) => setOauthNotice(errorMessage(relinkError)));
      } else {
        setOauthError(null);
        setOauthReturn({ id: connection, provider, email });
        setMigrationOpen(true);
      }
    } else if (outcome === 'error') {
      const reasons: Record<string, string> = {
        access_denied: t('Доступ не выдан: вход отменён или согласие отозвано'),
        invalid_state: t('Ссылка входа устарела: начните вход заново'),
        state_expired: t('Ссылка входа уже использована или устарела: начните вход заново'),
        exchange_failed: t('Не удалось получить доступ у провайдера: повторите вход позже'),
        no_code: t('Провайдер не вернул код подтверждения: начните вход заново'),
      };
      setOauthReturn(null);
      setOauthError(reasons[reason ?? ''] ?? t('Вход через провайдера не завершён: попробуйте снова'));
      setMigrationOpen(true);
    }
    const next = new URLSearchParams(searchParams);
    ['oauth', 'connection', 'provider', 'email', 'reason'].forEach((key) => next.delete(key));
    setSearchParams(next, { replace: true });
  }, [searchParams, setSearchParams, t]);

  // Consume commands separately so a source-email link survives the initial folder load.
  useEffect(() => {
    const openCompose = searchParams.get('compose') === '1';
    const openExternalAccounts = searchParams.get('externalAccounts') === '1';
    const openSettings = searchParams.get('settings') === '1';
    // `?to=` приходит из адресной книги и из ⌘K: письмо уже начато
    // получателю, его не нужно искать в списке контактов заново.
    if (openCompose) openDraft({ to: (searchParams.get('to') ?? '').slice(0, 320), subject: '', body: '' }, 'new');
    if (openExternalAccounts && canAdminExternalAccounts) setExternalOpen(true);
    if (openSettings) setSettingsOpen(true);
    if (openCompose || openExternalAccounts || openSettings) {
      const next = new URLSearchParams(searchParams);
      next.delete('compose');
      next.delete('to');
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

  // Активное письмо всегда видно: при j/k длинный список раньше прокручивался
  // вручную, и курсор уходил за пределы экрана.
  useEffect(() => {
    if (!activeId) return;
    const row = document.getElementById(`mail-row-${activeId}`);
    // В jsdom (тесты) и в старых браузерах метода может не быть.
    if (typeof row?.scrollIntoView === 'function') row.scrollIntoView({ block: 'nearest' });
  }, [activeId]);

  /**
   * Свайп по строке на телефоне: вправо — в архив, влево — в корзину.
   *
   * Порог 64 px и требование горизонтального движения: без этого обычная
   * прокрутка списка пальцем превращалась бы в случайный архив. Свайп срабатывает
   * только на тач-событиях — мышь и клавиатура идут прежним путём.
   */
  const swipeStartRef = useRef<{ id: string; x: number; y: number; fired: boolean } | null>(null);
  const swipeHandlers = (mail: DemoMail) => ({
    onTouchStart: (event: React.TouchEvent<HTMLDivElement>) => {
      const touch = event.touches[0];
      if (!touch || event.touches.length > 1) { swipeStartRef.current = null; return; }
      swipeStartRef.current = { id: mail.id, x: touch.clientX, y: touch.clientY, fired: false };
    },
    onTouchEnd: (event: React.TouchEvent<HTMLDivElement>) => {
      const start = swipeStartRef.current;
      swipeStartRef.current = null;
      if (!start || start.id !== mail.id || start.fired) return;
      const touch = event.changedTouches[0];
      if (!touch) return;
      const dx = touch.clientX - start.x;
      const dy = touch.clientY - start.y;
      if (Math.abs(dx) < 64 || Math.abs(dx) < Math.abs(dy) * 1.5) return;
      if (movingMailIdsRef.current.has(mail.id)) return;
      swipeStartRef.current = { ...start, fired: true };
      moveActiveById(mail.id, dx > 0 ? 'ARCHIVE' : 'TRASH');
    },
  });

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

  /**
   * Перемещение одного письма с возможностью отмены.
   *
   * Исходная папка известна в момент вызова, поэтому «Отменить» — это обычный
   * обратный move. Раньше архив и корзина срабатывали мгновенно и необратимо.
   */
  const moveActive = (targetFolder: string) => {
    if (!active || active.folder === targetFolder || movingMailIdsRef.current.has(active.id)) return;
    const next = visible[activeIndex + 1] ?? visible[activeIndex - 1];
    const sourceFolder = active.folder;
    const targetName = folders.find((f) => f.id === targetFolder)?.name ?? targetFolder;
    const offerUndo = (movedId: string, movedBackFrom: string) => {
      notify({
        key: `move-${movedId}`,
        message: t('Письмо перемещено в «{folder}».', { folder: targetName }),
        durationMs: 8000,
        action: {
          label: t('Отменить'),
          onSelect: () => {
            // Тот же путь, что и обычное перемещение: локальное состояние, затем
            // сервер. Ошибка возвращается уведомлением, а не молчаливым расхождением.
            moveActiveById(movedId, movedBackFrom);
          },
        },
      });
    };
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
          offerUndo(source.id, source.folder);
        })
        .catch((error) => {
          setLoadError(errorMessage(error));
          notify({ message: t('Не удалось переместить письмо.'), tone: 'error', durationMs: 8000 });
        })
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
    offerUndo(active.id, sourceFolder);
  };

  /** Перемещение по id: нужно отмене, когда открытое письмо уже сменилось. */
  const moveActiveById = (mailId: string, targetFolderKey: string) => {
    const mail = mails.find((item) => item.id === mailId)
      ?? visible.find((item) => item.id === mailId);
    if (!mail) return;
    if (demoMode) {
      setMails((prev) => prev.map((item) => item.id === mailId ? { ...item, folder: targetFolderKey } : item));
      return;
    }
    const target = realFolders.find((f) => (f.specialUse ?? f.id) === targetFolderKey);
    if (!target) return;
    movingMailIdsRef.current.add(mailId);
    setMovingMailIds(new Set(movingMailIdsRef.current));
    mailRevisionRef.current += 1;
    void enqueueMessageMutation(mailId, () => mailApi.move(mailId, target.id))
      .then((item) => {
        setMails((prev) => {
          const existing = prev.find((candidate) => candidate.id === mailId);
          if (!existing) return prev;
          return prev.map((candidate) => candidate.id === mailId
            ? { ...toMailView(item, targetFolderKey), body: loadedBodies[mailId] ?? existing.body }
            : candidate);
        });
      })
      .catch((error) => {
        setLoadError(errorMessage(error));
        notify({ message: t('Не удалось отменить перемещение.'), tone: 'error', durationMs: 8000 });
      })
      .finally(() => {
        mailRevisionRef.current += 1;
        movingMailIdsRef.current.delete(mailId);
        setMovingMailIds(new Set(movingMailIdsRef.current));
      });
  };

  /** «Не спам»: письмо возвращается во «Входящие». Обратного действия к «В спам» не было. */
  const markNotSpam = () => {
    if (!active) return;
    moveActive(active.folder === 'SPAM' ? 'INBOX' : 'SPAM');
  };

  /** «Прочитать всё»: весь список показанных писем одним действием. */
  const markAllVisibleSeen = () => {
    const unread = visible.filter((mail) => !mail.seen);
    if (unread.length === 0) return;
    for (const mail of unread) markSeen(mail);
    notify({ message: t('Отмечено прочитанными: {count}', { count: unread.length }), tone: 'success', durationMs: 4000 });
  };

  const toggleBulkMail = (mail: DemoMail) => {
    setBulkSelection((prev) => prev.includes(mail.id) ? prev.filter((id) => id !== mail.id) : [...prev, mail.id]);
  };

  const toggleAllVisible = () => {
    setBulkSelection((prev) => visible.length > 0 && visible.every((mail) => prev.includes(mail.id))
      ? []
      : visible.map((mail) => mail.id));
  };

  /**
   * Групповое перемещение: снимок выбора, продолжение после явного отказа
   * и кнопка «Повторить» для тех писем, которые сервер отклонил.
   *
   * Нативное `window.confirm` убрано: подтверждение здесь ничего не защищает
   * (действие отменяемо), а блокировало вкладку и не поддавалось стилизации.
   */
  const moveSelected = async (targetFolder: string) => {
    if (bulkBusyRef.current || bulkSelection.length === 0 || targetFolder === folder) return;
    const target = realFolders.find((item) => (item.specialUse ?? item.id) === targetFolder);
    const targetName = folders.find((item) => item.id === targetFolder)?.name ?? targetFolder;
    if (!demoMode && !target) {
      setBulkNotice(t('Не удалось определить целевую папку. Обновите список.'));
      return;
    }
    const snapshot = [...bulkSelection];
    await runBulkMove(snapshot, targetFolder, targetName);
  };

  const runBulkMove = async (snapshot: string[], targetFolder: string, targetName: string) => {
    if (bulkBusyRef.current || snapshot.length === 0) return;
    const target = realFolders.find((item) => (item.specialUse ?? item.id) === targetFolder);
    if (!demoMode && !target) return;
    bulkBusyRef.current = true;
    setBulkBusy(true);
    setBulkNotice('');
    const result = await moveSequentially(snapshot, async (id) => {
      if (demoMode) {
        setMails((prev) => prev.map((mail) => mail.id === id ? { ...mail, folder: targetFolder } : mail));
        return;
      }
      if (movingMailIdsRef.current.has(id)) return;
      movingMailIdsRef.current.add(id);
      setMovingMailIds(new Set(movingMailIdsRef.current));
      mailRevisionRef.current += 1;
      try {
        await enqueueMessageMutation(id, () => mailApi.move(id, target!.id));
      } finally {
        mailRevisionRef.current += 1;
        movingMailIdsRef.current.delete(id);
        setMovingMailIds(new Set(movingMailIdsRef.current));
      }
    }, (error) => error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== 408);

    if (result.succeeded.length > 0) {
      const moved = new Set(result.succeeded);
      setMails((prev) => prev.filter((mail) => !moved.has(mail.id)));
      if (!demoMode && active && moved.has(active.id)) {
        setActiveId('');
        setMobileReading(false);
      }
    }
    const failed = [...result.rejected, ...result.uncertain];
    setBulkSelection([...failed, ...result.notAttempted]);
    const summary = result.uncertain.length > 0
      ? t('Перенесено: {done}. Результат одного письма неизвестен; не обработано: {remaining}. Проверьте папки перед повтором.',
        { done: result.succeeded.length, remaining: result.uncertain.length + result.notAttempted.length })
      : result.rejected.length > 0
        ? t('Перенесено: {done}, не удалось: {failed}. Остальные не тронуты.', {
          done: result.succeeded.length, failed: result.rejected.length,
        })
        : t('Перенесено писем: {count}', { count: result.succeeded.length });
    setBulkNotice(summary);
    notify({
      key: 'bulk-move',
      message: result.uncertain.length > 0 || result.rejected.length > 0
        ? summary
        : t('Писем перенесено в «{folder}»: {count}', { folder: targetName, count: result.succeeded.length }),
      tone: result.uncertain.length > 0 || result.rejected.length > 0 ? 'error' : 'success',
      durationMs: 8000,
      action: result.rejected.length > 0
        ? { label: t('Повторить'), onSelect: () => { void runBulkMove(result.rejected, targetFolder, targetName); } }
        : undefined,
    });
    bulkBusyRef.current = false;
    setBulkBusy(false);
  };

  /**
   * Пересылка оригиналов: тексты писем целиком, вложения — только проверенные
   * антивирусом и в пределах лимитов. Отказ по каждому файлу показывается
   * пользователю, а не проглатывается. Один и тот же путь используется и для
   * одного письма, и для группы, чтобы «Переслать» не теряла вложения.
   */
  const forwardMessages = async (ids: string[], options: { clearSelection?: boolean } = {}) => {
    if (bulkBusyRef.current || ids.length === 0) return;
    const snapshot = [...ids];
    bulkBusyRef.current = true;
    setBulkBusy(true);
    setBulkNotice('');
    try {
      const details = demoMode
        ? snapshot
            .map((id) => mails.find((mail) => mail.id === id))
            .filter((mail): mail is DemoMail => Boolean(mail))
            .map((mail) => ({ id: mail.id, sender: mail.from, subject: mail.subject, date: mail.date, bodyText: mail.body, attachments: [] }))
        : await Promise.all(snapshot.map(async (id) => {
            const detail = await mailApi.message(id);
            return {
              id,
              sender: detail.summary.sender,
              subject: detail.summary.subject,
              date: formatDateTime(locale, detail.summary.date),
              bodyText: detail.bodyText,
              attachments: detail.attachments,
            };
          }));

      if (details.length === 0) {
        setBulkNotice(t('Не удалось загрузить выбранные письма для пересылки.'));
        return;
      }

      const plan = planForwardAttachments(details.map((detail) => ({ id: detail.id, attachments: detail.attachments })));
      const files: File[] = [];
      const blocked: ForwardBlocked[] = [...plan.blocked];
      for (const candidate of plan.candidates) {
        try {
          const blob = await mailApi.downloadAttachment(candidate.messageId, candidate.attachment.partIndex);
          files.push(new File([blob], candidate.attachment.fileName || 'attachment',
            { type: candidate.attachment.contentType || 'application/octet-stream' }));
        } catch (error) {
          // 403 — антивирус запретил файл, 503 — проверка недоступна. В обоих
          // случаях файл не пересылается; причину покажем явно.
          const status = error instanceof ApiError ? error.status : 0;
          blocked.push({
            messageId: candidate.messageId,
            fileName: candidate.attachment.fileName,
            reason: status === 403 ? 'infected' : 'download-failed',
          });
        }
      }

      const body = buildForwardBody(
        details.map(({ sender, subject, date, bodyText }) => ({ sender, subject, date, bodyText })),
        t('Пересылаемое письмо'),
      );
      setComposeSession((session) => session + 1);
      setComposeFiles(files);
      setResumeDraft(null);
      setComposeDraft({ to: '', subject: forwardSubject(details, 'Fwd:'), body });
      if (options.clearSelection) setBulkSelection([]);

      if (blocked.length > 0) {
        const listed = blocked
          .map((item) => `${item.fileName} — ${t(forwardBlockLabelKey(item.reason))}`)
          .join('; ');
        setBulkNotice(t('Письма пересылаются без части вложений: {files}', { files: listed }));
      }
    } catch (error) {
      setBulkNotice(`${t('Не удалось загрузить выбранные письма для пересылки.')} ${errorMessage(error)}`);
    } finally {
      bulkBusyRef.current = false;
      setBulkBusy(false);
    }
  };

  const forwardSelected = () => forwardMessages(bulkSelection, { clearSelection: true });

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
    // Дошли до последней загруженной строки — подгружаем следующую страницу,
    // чтобы `j` не упирался в границу окна из 50 писем.
    if (delta > 0 && nextIndex >= visible.length - 1 && !demoMode && paging.hasMore && !paging.loading) {
      void loadMoreMessages();
    }
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
      // Пока открыта справка, Escape её закрывает, а остальные сочетания не
      // срабатывают: иначе `e` под ней архивировала бы письмо.
      if (shortcutsHelpOpen) {
        if (e.key === 'Escape') setShortcutsHelpOpen(false);
        return;
      }
      if (externalOpen || eventModalDraft || settingsOpen || createFolderOpen || draftsOpen) return;
      if (e.key === 'Escape' && composeDraft) {
        // Не обнуляем состояние напрямую: иначе Escape закрывал письмо без
        // подтверждения и без последнего автосохранения, пока «×» спрашивал.
        // Композер сам решает, спросить или закрыть пустой черновик.
        const panel = document.querySelector<HTMLElement>('[data-compose-panel]');
        panel?.dispatchEvent(new CustomEvent('corvex:close-compose', { bubbles: false }));
        return;
      }
      if (e.key === 'Escape' && bulkSelection.length > 0) {
        setBulkSelection([]);
        return;
      }
      // Ctrl/Cmd+P на открытом письме печатает письмо, а не интерфейс страницы.
      // Без открытого письма отдаём сочетание браузеру: печать списка — его дело.
      if (isMailPrintShortcut(e, { hasActiveMail: Boolean(active), composing: Boolean(composeDraft), typing: Boolean(typing) })) {
        e.preventDefault();
        void printActiveMail();
        return;
      }
      if (typing || composeDraft || e.ctrlKey || e.metaKey || e.altKey) return;
      const key = e.key.toLowerCase();
      // Набор как в Gmail: навигация, открыть, назад к списку, архив, корзина,
      // ответить, важное, поиск, справка. Кириллица продублирована для раскладки.
      if (key === '/') {
        e.preventDefault();
        searchRef.current?.focus();
      } else if (key === 'j' || key === 'о' || e.key === 'ArrowDown') {
        e.preventDefault();
        step(1);
      } else if (key === 'k' || key === 'л' || e.key === 'ArrowUp') {
        e.preventDefault();
        step(-1);
      } else if (key === 'o' || key === 'щ' || e.key === 'Enter') {
        if (!active) return;
        e.preventDefault();
        setMobileReading(true);
      } else if (key === 'u' || key === 'г') {
        e.preventDefault();
        closeReading();
      } else if (key === 'e' || key === 'у') {
        if (!active) return;
        e.preventDefault();
        moveActive('ARCHIVE');
      } else if (e.key === '#' || key === 'delete' || key === 'backspace') {
        if (!active) return;
        e.preventDefault();
        moveActive('TRASH');
      } else if (key === 'r' || key === 'к') {
        if (!active) return;
        e.preventDefault();
        replyToActive(false);
      } else if (key === 'a' || key === 'ф') {
        if (!active) return;
        e.preventDefault();
        replyToActive(true);
      } else if (key === 's' || key === 'ы') {
        if (!active) return;
        e.preventDefault();
        toggleFlag(active);
      } else if (key === '?') {
        e.preventDefault();
        setShortcutsHelpOpen(true);
      } else if (key === 'c' || key === 'с') {
        e.preventDefault();
        openDraft({ to: '', subject: '', body: '' }, 'new');
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  });

  /**
   * Настройки с сервера. Сервер — источник истины для подписи и внешнего вида:
   * иначе на новом устройстве человек видел бы другие настройки, чем на
   * первом. localStorage остаётся быстрым кэшем на случай недоступности API.
   */
  useEffect(() => {
    if (demoMode || !sessionUser?.id) return undefined;
    let cancelled = false;
    mailApi.settings()
      .then((remote) => {
        if (cancelled) return;
        const value = preferencesFromServer(remote);
        setPreferenceState({ key: mailPreferencesKey(sessionUser.id, false), value });
        setSortOrder(value.defaultSort);
        saveMailPreferences(mailPreferencesKey(sessionUser.id, false), value);
      })
      .catch(() => {
        // Сервер недоступен — работаем на локальной копии, ничего не теряя.
      });
    return () => { cancelled = true; };
  }, [demoMode, sessionUser?.id]);

  // Отложенные письма подтягиваются один раз на раздел: отменять их нужно и
  // после перезагрузки страницы, иначе очередь становится невидимой.
  useEffect(() => {
    if (demoMode) { setScheduledSends([]); return undefined; }
    let cancelled = false;
    mailApi.scheduledSends()
      .then((items) => { if (!cancelled) setScheduledSends(items); })
      .catch(() => { if (!cancelled) setScheduledSends([]); });
    return () => { cancelled = true; };
  }, [demoMode]);

  const cancelScheduledSend = async (id: string) => {
    try {
      await mailApi.cancelScheduledSend(id);
      setScheduledSends((prev) => prev.filter((item) => item.id !== id));
      notify({ message: t('Отложенная отправка отменена.'), tone: 'success' });
    } catch (error) {
      // 409 — письмо уже уходит. Это не сбой, а факт, который нужно назвать.
      notify({
        message: error instanceof ApiError && error.status === 409
          ? t('Письмо уже отправляется — отменить нельзя.')
          : `${t('Не удалось отменить отложенную отправку.')} ${errorMessage(error)}`,
        tone: 'error',
      });
    }
  };

  const addSentMail = async (draft: ComposeDraft, files: File[]) => {
    if (demoMode) {
      if (draft.sendAt) {
        // Демо-режим ничего не отправляет и не хранит очередь: обещать
        // «отправим позже» здесь было бы обманом.
        notify({ message: t('Отложенная отправка доступна при подключении к серверу.'), tone: 'info' });
        return true;
      }
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
      bcc: splitAddresses(draft.bcc ?? ''),
      subject: draft.subject,
      bodyText: draft.body,
      attachments,
      saveToSent: true,
      externalAccountId: draft.externalAccountId ?? null,
      // Без этого ответ уходил как новое письмо: у получателя он не попадал в тред.
      inReplyToMessageId: draft.inReplyToMessageId ?? null,
      sendAt: draft.sendAt ?? null,
    });

    if (sendResult.scheduledSendAt) {
      // Письмо ещё не ушло: это не «отправлено», и отменить его можно из
      // «Отправленных» до указанного времени.
      notify({
        message: t('Отправка запланирована на {time}.', {
          time: new Date(sendResult.scheduledSendAt).toLocaleString(),
        }),
        tone: 'info',
        durationMs: 6000,
      });
      setScheduledSends((prev) => [
        { id: sendResult.scheduledSendId ?? `s-${Date.now()}`, sendAt: sendResult.scheduledSendAt!, status: 'SCHEDULED' },
        ...prev,
      ]);
    }

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
              onOpenDrafts={demoMode ? undefined : () => { onCloseNavigation?.(); setDraftsOpen(true); }}
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
                      aria-current={isCurrent ? 'page' : undefined}
                      // Перетаскивание письма в папку: быстрее, чем меню «Переместить».
                      onDragOver={(event) => {
                        if (!event.dataTransfer.types.includes(CORVEX_MAIL_DND_MIME)) return;
                        if (f.id === folder) return;
                        event.preventDefault();
                        event.dataTransfer.dropEffect = 'move';
                        setDropTarget(f.id);
                      }}
                      onDragLeave={() => setDropTarget((current) => current === f.id ? null : current)}
                      onDrop={(event) => {
                        const raw = event.dataTransfer.getData(CORVEX_MAIL_DND_MIME);
                        setDropTarget(null);
                        if (!raw) return;
                        event.preventDefault();
                        try {
                          const payload = JSON.parse(raw) as { id?: string };
                          if (!payload.id || f.id === folder) return;
                          const mail = mails.find((item) => item.id === payload.id);
                          if (!mail) return;
                          // Используем тот же путь, что и кнопка: отмена и уведомление
                          // появляются и при перетаскивании.
                          moveActiveById(payload.id, f.id);
                          notify({
                            key: `move-${payload.id}`,
                            message: t('Письмо перемещено в «{folder}».', { folder: f.name }),
                            durationMs: 8000,
                            action: { label: t('Отменить'), onSelect: () => moveActiveById(payload.id!, mail.folder) },
                          });
                        } catch {
                          // Чужой drag-n-drop (файл, ссылка) не должен ломать папку.
                        }
                      }}
                      className={`sidebar-subitem w-full ${isCurrent ? 'sidebar-subitem-active' : ''} ${
                        dropTarget === f.id ? 'ring-2 ring-inset ring-accent' : ''
                      }`}
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
            {!demoMode && canExternalAccounts && (
              <button type="button" onClick={() => { onCloseNavigation?.(); setMigrationOpen(true); }} className="sidebar-subitem w-full text-sidebar-muted">
                <MailPlus size={15} strokeWidth={1.85} className="shrink-0" /><span className="min-w-0 flex-1 truncate text-left">{t('Перенести почту')}</span>
              </button>
            )}
            <button type="button" onClick={() => { onCloseNavigation?.(); setContactsOpen(true); }} className="sidebar-subitem w-full text-sidebar-muted">
              <Users size={15} strokeWidth={1.85} className="shrink-0" /><span className="min-w-0 flex-1 truncate text-left">{t('Адресная книга')}</span>
            </button>
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
        } mail-list-pane relative min-w-0 flex-1 flex-col border-r border-line bg-surface lg:w-[388px] lg:flex-none`}
      >
        {/* 56px Search + Sort + Filter Header */}
        <header className="z-10 flex h-14 shrink-0 items-center gap-1.5 border-b border-line bg-surface px-3.5">
          <div className="relative min-w-0 flex-1">
            <Search size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-faint" />
            <input
              ref={searchRef}
              value={search}
              onChange={(e) => { setSearch(e.target.value); setSearchLimit(SEARCH_INITIAL_LIMIT); }}
              onKeyDown={(event) => {
                // Esc в поле поиска возвращает полный список, как в Gmail.
                if (event.key === 'Escape' && search) {
                  event.preventDefault();
                  event.stopPropagation();
                  setSearch('');
                }
              }}
              placeholder={t('Поиск по загруженным письмам')}
              aria-label={t('Поиск по загруженным письмам: теме, отправителю, тексту')}
              type="search"
              className="field h-9 bg-canvas pl-8 pr-16 text-[13px] [&::-webkit-search-cancel-button]:hidden"
            />
            {search && (
              <button
                type="button"
                onClick={() => { setSearch(''); searchRef.current?.focus(); }}
                aria-label={t('Сбросить поиск')}
                title={t('Сбросить поиск')}
                className="absolute right-2 top-1/2 flex h-6 w-6 -translate-y-1/2 items-center justify-center rounded-md text-muted hover:bg-surface hover:text-ink"
              >
                <X size={14} />
              </button>
            )}
            {onOpenCommandPalette ? (
              <button
                type="button"
                onClick={onOpenCommandPalette}
                title={t('Быстрый переход')}
                className={`absolute top-1/2 hidden -translate-y-1/2 rounded border border-line-control bg-surface px-1.5 py-0.5 font-mono text-[10px] text-faint hover:text-ink sm:inline-flex ${search ? 'right-9' : 'right-2'}`}
              >
                ⌘K
              </button>
            ) : (
              <span className={`kbd absolute top-1/2 hidden -translate-y-1/2 sm:inline-flex ${search ? 'right-9' : 'right-2.5'}`}>/</span>
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
                  { value: 'unread-first', label: t('Сначала непрочитанные') },
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

        {/* The list is narrow even on desktop. Keep selection and filters on separate rows. */}
        <div className="shrink-0 border-b border-line bg-corvex-chrome px-4 py-2">
          <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
            <div className="flex min-w-[min(100%,7rem)] flex-1 items-center gap-2">
              <h2 title={currentFolder?.name ?? t('Почта')} className="min-w-0 truncate text-[13px] font-semibold text-ink">{currentFolder?.name ?? t('Почта')}</h2>
              <span
                title={paging.hasMore
                  ? t('Загружено {shown} из {total}').replace('{shown}', String(visible.length)).replace('{total}', String(selectedRealFolder?.total ?? visible.length))
                  : undefined}
                className="shrink-0 rounded-full bg-surface px-1.5 py-0.5 text-[11px] font-medium text-muted tabular-nums"
              >
                {paging.hasMore ? `${visible.length} / ${selectedRealFolder?.total ?? visible.length}` : visible.length}
              </span>
            </div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={markAllVisibleSeen}
                disabled={!visible.some((mail) => !mail.seen)}
                className="rounded-md px-1.5 py-1 text-[11.5px] font-medium text-muted hover:bg-surface hover:text-ink disabled:cursor-not-allowed disabled:opacity-40"
                title={t('Отметить прочитанными все письма в списке')}
              >
                {t('Прочитать все')}
              </button>
              <SelectVisibleMessages
                visibleCount={visible.length}
                selectedCount={bulkSelection.length}
                disabled={bulkBusy}
                onChange={toggleAllVisible}
              />
            </div>
          </div>
          <div className="mt-2 flex flex-wrap items-center rounded-lg border border-corvex-line bg-surface p-0.5" role="tablist" aria-label={t('Фильтр писем')}>
            {READ_FILTERS.map((value, index) => (
              <button
                key={value}
                id={`${filterId}-${value}`}
                type="button"
                role="tab"
                aria-selected={filter === value}
                aria-controls={`${filterId}-panel`}
                tabIndex={filter === value ? 0 : -1}
                onClick={() => setFilter(value)}
                onKeyDown={(event) => {
                  // Horizontal tabs own their arrow keys; don't let the mail shortcuts open a message.
                  if (event.key === 'ArrowUp' || event.key === 'ArrowDown') event.stopPropagation();
                  const next = event.key === 'ArrowRight' ? (index + 1) % READ_FILTERS.length
                    : event.key === 'ArrowLeft' ? (index + READ_FILTERS.length - 1) % READ_FILTERS.length
                      : event.key === 'Home' ? 0 : event.key === 'End' ? READ_FILTERS.length - 1 : -1;
                  if (next < 0) return;
                  event.preventDefault();
                  event.stopPropagation();
                  setFilter(READ_FILTERS[next]);
                  event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>('[role="tab"]')[next]?.focus();
                }}
                className={`h-8 flex-1 whitespace-nowrap rounded-md px-2 text-xs font-medium transition ${
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

        {bulkSelection.length > 0 && (
          <BulkActionBar
            count={bulkSelection.length}
            folders={folders}
            currentFolder={folder}
            busy={bulkBusy}
            onMove={(target) => void moveSelected(target)}
            onForward={() => void forwardSelected()}
            onClear={() => setBulkSelection([])}
          />
        )}
        {bulkNotice && (
          <p role="status" className="border-b border-line-subtle px-4 py-2 text-xs leading-5 text-muted">{bulkNotice}</p>
        )}

        {/* Message List */}
        <div
          id={`${filterId}-panel`}
          role="tabpanel"
          aria-labelledby={`${filterId}-${filter}`}
          data-mail-list
          tabIndex={-1}
          className="mail-list-scroll scroll-area min-h-0 flex-1 outline-none"
        >
          <div role="listbox" aria-label={t('Список писем')} aria-activedescendant={active ? `mail-row-${active.id}` : undefined}>
          {loadError && (
            <div className="m-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800" role="alert">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="min-w-0 flex-1">{loadError}</span>
                <button
                  type="button"
                  onClick={() => { setLoadError(null); reloadRef.current?.(); }}
                  className="shrink-0 rounded-md border border-red-300 bg-white px-2.5 py-1 text-xs font-semibold text-red-800 hover:bg-red-100"
                >
                  {t('Повторить')}
                </button>
                <button
                  type="button"
                  onClick={() => setLoadError(null)}
                  className="shrink-0 rounded-md px-2 py-1 text-xs text-red-700 hover:bg-red-100"
                >
                  {t('Закрыть')}
                </button>
              </div>
            </div>
          )}
          {oauthNotice && (
            <div className="m-4 rounded-xl border border-line bg-chrome px-4 py-3 text-sm leading-relaxed text-ink-soft" role="status">
              {oauthNotice}
            </div>
          )}

          {(offline || offlineSavedAt != null) && (
            <div className="border-b border-amber-200 bg-amber-50 px-4 py-2 text-[11.5px] leading-5 text-amber-900" role="status">
              {offlineBannerText(offlineSavedAt ?? Date.now(), locale)}
              {' '}
              <button
                type="button"
                onClick={() => { setOfflineSavedAt(null); setLoadError(null); reloadRef.current?.(); }}
                className="font-semibold underline underline-offset-2"
              >
                {t('Обновить')}
              </button>
            </div>
          )}

          {search && (searchPending || serverSearchError) && (
            <div className="flex items-start gap-2 border-b border-line-subtle bg-canvas px-4 py-2 text-[11.5px] leading-5 text-muted" role="status">
              {searchPending ? t('Ищем по всем письмам…') : serverSearchError}
            </div>
          )}
          {search && !searchPending && serverHits && (
            <div className="border-b border-line-subtle bg-canvas px-4 py-1.5 text-[11.5px] text-muted">
              {t('Найдено писем: {count}', { count: serverHits.length })}
            </div>
          )}

          {/* Отложенные отправки видны в папке «Отправленные»: письмо уже
              сохранено как копия, но до срока его можно отменить. */}
          {!demoMode && currentFolder?.id === 'SENT' && scheduledSends.length > 0 && (
            <div className="border-b border-line-subtle bg-canvas px-4 py-2" role="status">
              <div className="mb-1 text-[11px] font-semibold uppercase tracking-[0.04em] text-faint">
                {t('Отправятся позже')}
              </div>
              <ul className="space-y-1">
                {scheduledSends.map((item) => (
                  <li key={item.id} className="flex items-center gap-2 text-[12px] text-ink-soft">
                    <Clock size={12} className="shrink-0 text-faint" />
                    <span className="min-w-0 flex-1 truncate">
                      {new Date(item.sendAt).toLocaleString()}
                      {item.status === 'FAILED' ? ` · ${t('не удалось поставить в очередь')}` : ''}
                    </span>
                    <button
                      type="button"
                      onClick={() => void cancelScheduledSend(item.id)}
                      disabled={item.status !== 'SCHEDULED'}
                      className="shrink-0 rounded-md border border-line-control px-1.5 py-0.5 text-[11px] font-medium text-muted transition hover:bg-surface hover:text-ink disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      {t('Отменить отправку')}
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {mailGroups.map(({ group, mails: groupMails }, groupIndex) => (
            <div key={group} role="group" aria-label={t(dateGroupLabel(group))}>
              {/* Заголовок группы: без него список из 50 писем — сплошная стена дат. */}
              {sortOrder === 'date-desc' && (
                <div className="sticky top-0 z-10 border-b border-line-subtle bg-canvas/95 px-4 py-1 text-[11px] font-semibold uppercase tracking-[0.04em] text-faint backdrop-blur">
                  {t(dateGroupLabel(group))}
                </div>
              )}
              {groupMails.map((m, mailIndex) => {
                const selected = active?.id === m.id;
                // Тот же порядок, что и в видимом списке: Shift-стрелки идут по visible.
                const visibleIndex = (mailGroupStarts[groupIndex] ?? 0) + mailIndex;
                const rowThread = threadByLatestId.get(m.id);
                const threadExpanded = rowThread ? expandedThreads.has(rowThread.key) : false;
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
          id={`mail-row-${m.id}`}
          role="option"
          aria-selected={selected}
          tabIndex={selected ? 0 : -1}
          data-mail-id={m.id}
          onKeyDown={(event) => {
            // Enter/Space открывают письмо, не полагаясь на глобальный обработчик:
            // он не срабатывает, когда фокус на самой строке.
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault();
              open(m);
            }
          }}
          onClick={() => open(m)}
          {...swipeHandlers(m)}
          className={`group relative flex cursor-pointer border-b border-line-subtle px-4 transition duration-150 focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-accent/60 ${
            preferences.density === 'compact' ? 'gap-2 py-1.5' : 'gap-3 py-3'
          } ${selected ? 'bg-accent-soft' : 'bg-surface hover:bg-canvas'}`}
        >
          {selected && <span className="absolute inset-y-0 left-0 w-[3px] bg-accent" />}
          {movingMailIds.has(m.id) && <span className="sr-only" role="status">{t('Перемещение письма…')}</span>}
          <div className="flex shrink-0 items-center" onClick={(event) => event.stopPropagation()}>
            <SelectMessage
              subject={m.subject}
              checked={bulkSelection.includes(m.id)}
              disabled={bulkBusy}
              onChange={() => toggleBulkMail(m)}
            />
          </div>
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
                {rowThread ? rowThread.subject : m.subject}
              </button>

              {/* Счётчик цепочки: видно, что за строкой несколько писем, и
                  сколько из них ещё не прочитано. */}
              {rowThread && rowThread.mails.length > 1 && (
                <button
                  type="button"
                  aria-expanded={threadExpanded}
                  title={threadExpanded ? t('Свернуть цепочку') : t('Развернуть цепочку')}
                  onClick={(e) => {
                    e.stopPropagation();
                    toggleThread(rowThread.key);
                  }}
                  className="inline-flex shrink-0 items-center gap-1 rounded-full border border-line-control bg-surface px-1.5 py-0.5 text-[10.5px] font-medium text-muted transition hover:bg-canvas hover:text-ink"
                >
                  {conversationCountLabel(rowThread.mails.length, locale)}
                  {rowThread.unread > 0 && (
                    <span className="rounded-full bg-accent px-1 text-[10px] font-semibold text-white tabular-nums">
                      {rowThread.unread}
                    </span>
                  )}
                  <ChevronDown size={11} className={threadExpanded ? 'rotate-180 transition' : 'transition'} />
                </button>
              )}

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
              {/* Развёрнутая цепочка: письма старые сверху, чтобы читались как
                  переписка. Строка со свёрнутой цепочкой остаётся на месте, так
                  что выбор письма не сдвигает список. */}
              {conversationsEnabled && mailGroups[groupIndex].mails
                .map((rowMail) => threadByLatestId.get(rowMail.id))
                .filter((thread) => thread !== undefined
                  && thread.mails.length > 1 && expandedThreads.has(thread.key))
                .map((thread) => thread!)
                .map((thread) => (
                  <div key={`thread-${thread.key}`} className="border-b border-line-subtle bg-canvas/60 px-4 py-1">
                    {thread.mails.map((threadMail) => (
                      <button
                        key={threadMail.id}
                        type="button"
                        data-thread-mail-id={threadMail.id}
                        onClick={() => open(threadMail)}
                        className={`flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-[12px] transition hover:bg-surface ${
                          active?.id === threadMail.id ? 'bg-accent-soft text-accent-dark' : 'text-ink-soft'
                        }`}
                      >
                        <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${threadMail.seen ? 'bg-transparent' : 'bg-[#2563EB]'}`} />
                        <span className="w-[132px] shrink-0 truncate text-[11.5px] text-muted">{cleanPersonName(threadMail.from)}</span>
                        <span className={`min-w-0 flex-1 truncate ${threadMail.seen ? '' : 'font-semibold text-ink'}`}>
                          {threadMail.snippet || threadMail.subject}
                        </span>
                        <span className="shrink-0 text-[10.5px] text-faint tabular-nums">{threadMail.date}</span>
                      </button>
                    ))}
                  </div>
                ))}
            </div>
          ))}
          </div>

          {search && searchSource.length > searchLimit && (
            <div className="flex items-center justify-center gap-2 border-b border-line-subtle bg-canvas px-4 py-2 text-[11.5px] text-muted">
              <span>{t('Показаны первые {shown} из {found}', { shown: searchLimit, found: searchSource.length })}</span>
              <button
                type="button"
                onClick={() => setSearchLimit((limit) => limit + SEARCH_INITIAL_LIMIT)}
                className="font-semibold text-accent-dark underline-offset-2 hover:underline"
              >
                {t('Показать ещё')}
              </button>
            </div>
          )}

          {!demoMode && paging.hasMore && (
            <div ref={loadMoreSentinelRef} className="flex flex-col items-center gap-1 px-4 py-5">
              {/*
                Автоподгрузка: кнопка остаётся для тех, у кого нет
                IntersectionObserver (и как ручной повтор после ошибки), но
                в обычном сценарии до конца списка доезжают колесом.
              */}
              <button
                type="button"
                onClick={() => void loadMoreMessages()}
                disabled={paging.loading}
                className="btn-secondary h-9 text-xs"
              >
                {paging.loading ? t('Загружаем…') : t('Показать ещё письма')}
              </button>
              <span className="text-[11px] text-muted">
                {t('Показано {shown}, в папке {total}')
                  .replace('{shown}', String(visible.length))
                  .replace('{total}', String(selectedRealFolder?.total ?? visible.length))}
              </span>
            </div>
          )}

          {listLoading && visible.length === 0 && (
            // Скелетон вместо «Здесь пока тихо»: пустая папка и незавершённая
            // загрузка выглядели одинаково, и на медленной сети человек решал,
            // что писем нет.
            <div className="space-y-3 px-4 py-5" aria-busy="true" aria-label={t('Загружаем письма…')}>
              {[0, 1, 2, 3, 4].map((row) => (
                <div key={row} className="flex items-start gap-3">
                  <span className="h-9 w-9 shrink-0 animate-pulse rounded-full bg-canvas" />
                  <span className="min-w-0 flex-1 space-y-2">
                    <span className="block h-3 w-1/3 animate-pulse rounded bg-canvas" />
                    <span className="block h-3 w-4/5 animate-pulse rounded bg-canvas" />
                  </span>
                </div>
              ))}
            </div>
          )}

          {!listLoading && visible.length === 0 && (
            <EmptyState
              icon={MailOpen}
              title={t('Здесь пока тихо')}
              description={
                search
                  ? t('По этому запросу писем не нашлось. Попробуйте другое слово или сбросьте фильтр.')
                  : t('В этой папке нет писем для выбранного фильтра.')
              }
              action={search ? (
                <button type="button" className="btn-secondary h-9 text-xs" onClick={() => { setSearch(''); searchRef.current?.focus(); }}>
                  {t('Сбросить поиск')}
                </button>
              ) : undefined}
            />
          )}
        </div>

        <button
          type="button"
          onClick={() => openDraft({ to: '', subject: '', body: '' }, 'new')}
          className={`btn-primary absolute bottom-4 right-4 z-30 shadow-pop lg:hidden ${bulkSelection.length > 0 ? 'mail-compose-hide-when-selected' : ''}`}
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
                onClick={closeReading}
                className="btn-ghost px-2 lg:hidden"
              >
                <ArrowLeft size={16} /> {t('К списку')}
              </button>

              <IconButton
                icon={Reply}
                label={t('Ответить')}
                size={17}
                onClick={() => replyToActive(false)}
              />
              <IconButton
                icon={ReplyAll}
                label={t('Ответить всем')}
                size={17}
                disabled={!replyAllReady}
                onClick={() => replyToActive(true)}
              />
              <IconButton
                icon={Forward}
                label={t('Переслать')}
                size={17}
                disabled={bulkBusy}
                onClick={() => void forwardMessages([active.id])}
              />
              <IconButton
                icon={Printer}
                label={t('Печать')}
                size={17}
                onClick={() => void printActiveMail()}
              />

              <span className="mx-1 h-4 w-px bg-line" aria-hidden="true" />
              <MoveToFolder folders={folders} current={folder} onMove={moveActive} disabled={movingMailIds.has(active.id)} />
              <IconButton icon={Archive} label={t('В архив')} size={16.5} onClick={() => moveActive('ARCHIVE')} disabled={movingMailIds.has(active.id)} />
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
              {/* «Не спам» и остальные редкие действия — в меню «…»: до 1536 px
                  кнопки «В спам», «Ответить всем» и «Переслать» просто исчезали. */}
              <div ref={morePanelRef} className="relative">
                <IconButton
                  icon={Ellipsis}
                  label={t('Ещё действия')}
                  size={17}
                  aria-expanded={moreMenuOpen}
                  aria-haspopup="menu"
                  onClick={() => setMoreMenuOpen((open) => !open)}
                />
                {moreMenuOpen && (
                  <div role="menu" aria-label={t('Ещё действия')} className="absolute right-0 top-9 z-40 w-60 rounded-xl border border-line-control bg-surface p-1.5 shadow-pop">
                    <button
                      type="button"
                      role="menuitem"
                      disabled={movingMailIds.has(active.id)}
                      onClick={() => { setMoreMenuOpen(false); moveActive('SPAM'); }}
                      className="nav-row w-full disabled:opacity-50"
                    >
                      <OctagonAlert size={15} /> <span className="min-w-0 flex-1 truncate text-left">{t('В спам')}</span>
                    </button>
                    {folder === 'SPAM' && (
                      <button
                        type="button"
                        role="menuitem"
                        disabled={movingMailIds.has(active.id)}
                        onClick={() => { setMoreMenuOpen(false); markNotSpam(); }}
                        className="nav-row w-full disabled:opacity-50"
                      >
                        <Inbox size={15} /> <span className="min-w-0 flex-1 truncate text-left">{t('Не спам')}</span>
                      </button>
                    )}
                    <button
                      type="button"
                      role="menuitem"
                      disabled={bulkBusy}
                      onClick={() => { setMoreMenuOpen(false); void forwardMessages([active.id]); }}
                      className="nav-row w-full disabled:opacity-50"
                    >
                      <Forward size={15} /> <span className="min-w-0 flex-1 truncate text-left">{t('Переслать')}</span>
                    </button>
                    <button
                      type="button"
                      role="menuitem"
                      onClick={() => { setMoreMenuOpen(false); setShortcutsHelpOpen(true); }}
                      className="nav-row w-full"
                    >
                      <span className="w-4 shrink-0 text-center text-[11px] font-semibold text-muted">?</span>
                      <span className="min-w-0 flex-1 truncate text-left">{t('Горячие клавиши')}</span>
                    </button>
                  </div>
                )}
              </div>

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
                <div className="flex shrink-0 items-center gap-2">
                  <button
                    type="button"
                    onClick={() => void snoozeActiveMail(24)}
                    title={t('Отложить до завтра')}
                    className="inline-flex items-center gap-1 rounded-md border border-line-control px-1.5 py-1 text-[11px] font-medium text-muted transition hover:bg-canvas hover:text-ink"
                  >
                    <Clock size={12} /> <span className="hidden sm:inline">{t('Отложить')}</span>
                  </button>
                  <button
                    type="button"
                    onClick={() => void addSenderToContacts()}
                    title={t('Добавить отправителя в контакты')}
                    className="inline-flex items-center gap-1 rounded-md border border-line-control px-1.5 py-1 text-[11px] font-medium text-muted transition hover:bg-canvas hover:text-ink"
                  >
                    <UserPlus size={12} /> <span className="hidden sm:inline">{t('В контакты')}</span>
                  </button>
                  <time className="text-xs text-faint tabular-nums">{active.date}</time>
                </div>
              </div>

              {active.hasAttachments && (
                <MailAttachments
                  demoMode={demoMode}
                  attachments={loadedAttachments[active.id]}
                  busy={downloadBusy !== null || downloadAllBusy}
                  onDownload={(attachment) => void downloadAttachment(active.id, attachment)}
                  onDownloadAll={downloadAllBusy ? undefined : () => void downloadAllAttachments()}
                  onPreview={(attachment) =>
                    openAttachmentPreview(active.id, loadedAttachments[active.id] ?? [attachment], attachment)
                  }
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
                <>
                  {remoteImages && !showRemoteImages && (
                    <button
                      type="button"
                      onClick={() => setShowRemoteImages(true)}
                      className="mt-4 flex w-full items-center justify-between gap-3 rounded-lg border border-line-control bg-canvas px-3 py-2 text-left text-xs text-ink-soft transition hover:bg-chrome"
                    >
                      <span>{t('Внешние изображения заблокированы. Отправитель узнает, что вы открыли письмо.')}</span>
                      <span className="shrink-0 font-medium text-accent-dark">{t('Показать изображения')}</span>
                    </button>
                  )}
                  <MailHtmlFrame html={activeHtml} allowRemoteImages={showRemoteImages} />
                </>
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
                  onClick={() => replyToActive(false)}
                >
                  <Reply size={15} /> {t('Ответить')}
                </button>
                <button
                  type="button"
                  className="btn-secondary"
                  disabled={!replyAllReady}
                  onClick={() => replyToActive(true)}
                >
                  <ReplyAll size={15} /> {t('Ответить всем')}
                </button>
                <button
                  type="button"
                  className="btn-secondary"
                  disabled={bulkBusy}
                  onClick={() => void forwardMessages([active.id])}
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

      {shortcutsHelpOpen && <ShortcutsHelp onClose={() => setShortcutsHelpOpen(false)} />}

      {createFolderOpen && <CreateFolderModal demoMode={demoMode} onClose={() => setCreateFolderOpen(false)} onCreate={createFolder} />}
      {settingsOpen && <MailSettingsModal
        initial={preferences}
        email={sessionUser?.email ?? ''}
        demoMode={demoMode}
        onClose={() => setSettingsOpen(false)}
        onSave={(value: MailPreferences) => {
          setPreferenceState({ key: preferenceKey, value });
          setSortOrder(value.defaultSort);
          // Сначала локально: закрытие окна не должно ждать сети. Затем на
          // сервер — чтобы настройки переехали на другое устройство и
          // подпись попала в письма из Thunderbird. Ошибка сети не отменяет
          // сохранение: строка ниже честно говорит, что оно пока местное.
          const savedLocally = saveMailPreferences(preferenceKey, value);
          if (!demoMode) {
            void mailApi.saveSettings(preferencesToServer(value))
              .then(() => setSettingsSynced('saved'))
              .catch(() => setSettingsSynced('error'));
          }
          return savedLocally;
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

      {migrationOpen && !demoMode && (
        <MigrationWizard
          existingAccount={onboarding?.accounts[0] ?? null}
          suggestedHost={onboarding?.suggestedSourceHost ?? null}
          suggestedLogin={onboarding?.suggestedSourceLogin ?? null}
          oauthReturn={oauthReturn}
          oauthError={oauthError}
          onClose={() => {
            setMigrationOpen(false);
            setOauthReturn(null);
            setOauthError(null);
            onboardingApi.state().then(setOnboarding).catch(() => undefined);
          }}
          onDismiss={dismissMigration}
          onNotNeeded={notNeededMigration}
          onOpenContacts={() => { setMigrationOpen(false); setContactsOpen(true); }}
        />
      )}

      {contactsOpen && (
        <ContactsModal
          demoMode={demoMode}
          onClose={() => setContactsOpen(false)}
          onChanged={(items) => setContactEmails(items.map((item) => item.email).slice(0, 1000))}
        />
      )}

      {draftsOpen && (
        <DraftsPanel
          onOpen={(draftId) => void openServerDraft(draftId)}
          onClose={() => setDraftsOpen(false)}
        />
      )}

      {composeDraft && (
        <ComposePanel
          key={`compose-${composeSession}`}
          initial={composeDraft}
          initialDraft={resumeDraft}
          initialFiles={composeFiles}
          autoSave={!demoMode}
          currentEmail={sessionUser?.email ?? ''}
          externalAccounts={externalAccounts}
          contactSuggestions={contactEmails}
          onClose={() => {
            // Старый композер мог завершить отправку уже после открытия нового:
            // закрываем только собственный черновик.
            setComposeDraft((current) => (current === composeDraft ? null : current));
          }}
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
