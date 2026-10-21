import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Archive,
  ArrowLeft,
  ArrowUpDown,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  FileCheck2,
  FilePenLine,
  Folder,
  Forward,
  HardDrive,
  Inbox,
  MailOpen,
  Minus,
  MoreHorizontal,
  OctagonAlert,
  Paperclip,
  PenSquare,
  Reply,
  Search,
  Send,
  Settings2,
  SlidersHorizontal,
  Star,
  Trash2,
  X,
} from 'lucide-react';
import { demoFolders, demoMails, DemoMail } from '../../api/demoData';
import { externalMailApi, ExternalMailAccount } from '../../api/externalMailApi';
import { mailApi, MailFolderView, MailMessageListItem, SendAttachment } from '../../api/mailApi';
import ExternalMailAccountsModal from './ExternalMailAccountsModal';
import { EmojiPickerButton } from '../../components/EmojiPicker';
import { Avatar, EmptyState, IconButton, cleanPersonName, emailFromHeader } from '../../components/ui';
import { countLabel, formatBytes, formatDateTime, useI18n } from '../../i18n';
import { insertTextAtSelection, truncateGraphemes } from '../../lib/text';
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

/** Почта Quiet Work: папки → сканируемый список → письмо как документ, composer поверх контекста. */
export default function MailPage() {
  const { locale, t } = useI18n();
  const demoMode = useSession((s) => s.demoMode);
  const sessionUser = useSession((s) => s.user);
  const demoReadMailIds = useSession((s) => s.demoReadMailIds);
  const markDemoMailRead = useSession((s) => s.markDemoMailRead);
  const [realFolders, setRealFolders] = useState<MailFolderView[]>([]);
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
  const [filter, setFilter] = useState<'all' | 'unread'>('all');
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
  const canExternalAccounts = Boolean(
    sessionUser?.permissions.includes('MAIL_EXTERNAL_ACCOUNTS')
    || sessionUser?.permissions.includes('ADMIN_MAIL_POLICY')
    || sessionUser?.permissions.includes('*'),
  );
  const canAdminExternalAccounts = Boolean(
    sessionUser?.permissions.includes('ADMIN_MAIL_POLICY') || sessionUser?.permissions.includes('*'),
  );
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
  const [loadedBodies, setLoadedBodies] = useState<Record<string, string>>({});
  const searchRef = useRef<HTMLInputElement>(null);
  const seenPendingRef = useRef<Set<string>>(new Set());

  const visible = useMemo(() => {
    const q = search.trim().toLowerCase();
    return mails.filter((m) => (
      m.folder === folder
      && (filter === 'all' || !m.seen)
      && (!q || `${m.subject} ${m.from} ${m.snippet} ${m.body}`.toLowerCase().includes(q))
    ));
  }, [mails, folder, search, filter]);

  const active = mails.find((m) => m.id === activeId && m.folder === folder) ?? visible[0] ?? null;
  const activeBody = active ? (loadedBodies[active.id] ?? active.body) : '';
  const activeIndex = active ? visible.findIndex((m) => m.id === active.id) : -1;
  const currentFolder = folders.find((f) => f.id === folder);

  const toMailView = (item: MailMessageListItem, folderKey: string): DemoMail => ({
    id: item.id,
    folder: folderKey,
    from: item.sender,
    subject: item.subject,
    snippet: item.snippet ?? '',
    body: item.snippet ?? '',
    date: formatDateTime(locale, item.date),
    seen: item.seen,
    flagged: item.flagged,
    hasAttachments: item.hasAttachments,
  });

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

  const activeRealId = !demoMode && active ? active.id : null;
  useEffect(() => {
    if (!activeRealId || loadedBodies[activeRealId] != null) return;
    let cancelled = false;
    mailApi.message(activeRealId)
      .then((detail) => {
        if (!cancelled) setLoadedBodies((prev) => ({ ...prev, [activeRealId]: detail.bodyText ?? '' }));
      })
      .catch((error) => { if (!cancelled) setLoadError(errorMessage(error)); });
    return () => { cancelled = true; };
  }, [activeRealId, loadedBodies]);

  useEffect(() => {
    if (searchParams.get('compose') === '1') {
      setComposeDraft({ to: '', subject: '', body: '' });
      setSearchParams({}, { replace: true });
    }
  }, [searchParams, setSearchParams]);

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
        searchRef.current?.focus();
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
      <div className="flex h-full min-h-0 overflow-hidden bg-surface lg:rounded-[18px] lg:border lg:border-line lg:shadow-soft">
        {/* Папки */}
        <aside className="hidden w-[238px] shrink-0 flex-col border-r border-line bg-chrome p-3 xl:flex">
          <div className="flex items-center justify-between px-2 pb-3 pt-1">
            <h1 className="text-[17px] font-semibold tracking-[-0.02em]">{t('Почта')}</h1>
            <span className="text-xs text-faint">{countLabel(locale, folderCounts.INBOX ?? 0, ['новое', 'новых', 'новых'], ['new', 'new'])}</span>
          </div>
          <button type="button" onClick={() => setComposeDraft({ to: '', subject: '', body: '' })} className="btn-primary w-full">
            <PenSquare size={16} /> {t('Написать')} <span className="ml-auto hidden xl:inline-flex"><span className="kbd border-white/20 bg-white/10 text-white/70 shadow-none">C</span></span>
          </button>
          {canExternalAccounts && (
            <button type="button" onClick={() => setExternalOpen(true)} className="btn-secondary mt-2 w-full">
              <Settings2 size={16} /> {t('Внешние ящики')}
              {externalAccounts.length > 0 && <span className="ml-auto text-xs text-faint">{externalAccounts.length}</span>}
            </button>
          )}

          <p className="section-label mb-1.5 mt-6">{t('Папки')}</p>
          <nav className="space-y-0.5" aria-label={t('Папки почты')}>
            {folders.map((f) => {
              const Icon = FOLDER_ICONS[f.id] ?? Folder;
              const count = folderCounts[f.id] ?? 0;
              return (
                <button key={f.id} type="button" onClick={() => selectFolder(f.id)} className={`nav-row ${folder === f.id ? 'nav-row-active' : ''}`}>
                  <Icon size={16} strokeWidth={1.8} />
                  <span className="min-w-0 flex-1 truncate">{f.name}</span>
                  {count > 0 && <span className={`text-xs ${folder === f.id ? 'font-semibold text-accent' : 'font-medium text-faint'}`}>{count}</span>}
                </button>
              );
            })}
          </nav>

          <div className="mt-auto rounded-xl bg-black/[0.035] p-3">
            <div className="flex items-center gap-2 text-xs font-medium text-ink-soft"><HardDrive size={14} className="text-muted" />{t('Ящик')}</div>
            <div className="mt-2.5 h-1.5 overflow-hidden rounded-full bg-black/[0.08]"><div className="h-full w-[62%] rounded-full bg-accent" /></div>
            <p className="mt-2 text-[11px] leading-relaxed text-muted">{t('Использовано {used} из {total}', { used: formatBytes(locale, 3.1 * 1024 ** 3), total: formatBytes(locale, 5 * 1024 ** 3) })}</p>
          </div>
        </aside>

        {/* Список писем */}
        <section className={`${mobileReading ? 'hidden md:flex' : 'flex'} relative min-w-0 flex-1 flex-col border-r border-line bg-surface md:w-[322px] md:flex-none lg:w-[356px] xl:w-[392px]`}>
          <header className="shrink-0 border-b border-line px-4 pb-3 pt-4">
            <div className="flex items-center gap-2">
              <h2 className="min-w-0 flex-1 truncate text-[17px] font-semibold tracking-[-0.02em]">{currentFolder?.name ?? t('Почта')}</h2>
              <span className="text-xs text-faint">{visible.length}</span>
              <IconButton icon={ArrowUpDown} label={t('Сортировка')} className="h-8 w-8" size={16} />
              {canExternalAccounts && <IconButton icon={Settings2} label={t('Внешние ящики')} className="h-8 w-8 xl:hidden" size={16} onClick={() => setExternalOpen(true)} />}
              <IconButton icon={SlidersHorizontal} label={t('Фильтры')} className="h-8 w-8" size={16} />
            </div>
            <div className="relative mt-3">
              <Search size={16} className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-faint" />
              <input ref={searchRef} value={search} onChange={(e) => setSearch(e.target.value)} placeholder={t('Поиск по теме, отправителю, тексту')} aria-label={t('Поиск по теме, отправителю, тексту')} className="search-field" />
              <span className="kbd absolute right-3 top-1/2 hidden -translate-y-1/2 sm:inline-flex">/</span>
            </div>
            <div className="mt-3 flex rounded-full bg-black/[0.04] p-1" role="tablist" aria-label={t('Фильтр писем')}>
              {(['all', 'unread'] as const).map((value) => (
                <button key={value} type="button" role="tab" aria-selected={filter === value} onClick={() => setFilter(value)} className={`h-8 flex-1 rounded-full text-[13px] font-medium transition ${filter === value ? 'bg-surface text-ink shadow-soft' : 'text-muted hover:text-ink'}`}>
                  {value === 'all' ? t('Все') : t('Непрочитанные')}
                </button>
              ))}
            </div>
            <div className="-mb-1 mt-3 flex gap-1.5 overflow-x-auto pb-1 xl:hidden">
              {folders.map((f) => (
                <button key={f.id} type="button" onClick={() => selectFolder(f.id)} className={`chip shrink-0 ${folder === f.id ? 'chip-accent' : ''}`}>{f.name}{(folderCounts[f.id] ?? 0) > 0 && ` · ${folderCounts[f.id]}`}</button>
              ))}
            </div>
          </header>

          <div className="scroll-area min-h-0 flex-1">
            {loadError && (
              <div className="m-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800" role="alert">{loadError}</div>
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
                  className={`group relative flex cursor-pointer gap-3 border-b border-line/70 px-4 py-3.5 transition duration-150 ${selected ? 'bg-accent-soft/70' : 'hover:bg-chrome'}`}
                >
                  {selected && <span className="absolute inset-y-3 left-0 w-[3px] rounded-full bg-accent" />}
                  <Avatar name={m.from} size={38} />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      {!m.seen && <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-accent" aria-label={t('Непрочитано')} />}
                      <span className={`min-w-0 flex-1 truncate text-sm ${m.seen ? 'font-medium text-ink-soft' : 'font-semibold text-ink'}`}>{cleanPersonName(m.from)}</span>
                      <span className="shrink-0 text-[11px] text-faint">{m.date}</span>
                    </div>
                    <div className="mt-1 flex items-center gap-1.5">
                      <button type="button" onClick={(e) => { e.stopPropagation(); open(m); }} className={`min-w-0 flex-1 truncate rounded text-left text-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-accent/50 ${m.seen ? 'text-ink-soft' : 'font-medium text-ink'}`}>{m.subject}</button>
                      {m.hasAttachments && <Paperclip size={13} className="shrink-0 text-faint" />}
                      <button
                        type="button"
                        title={m.flagged ? t('Убрать из важных') : t('Отметить важным')}
                        aria-label={m.flagged ? t('Убрать из важных') : t('Отметить важным')}
                        onClick={(e) => { e.stopPropagation(); toggleFlag(m); }}
                        className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-md transition ${m.flagged ? 'text-amber-500' : 'text-faint opacity-0 hover:bg-black/[0.05] hover:text-ink group-hover:opacity-100'}`}
                      >
                        <Star size={14} fill={m.flagged ? 'currentColor' : 'none'} />
                      </button>
                    </div>
                    <p className="mt-0.5 line-clamp-2 text-[13px] leading-snug text-muted">{m.snippet}</p>
                  </div>
                </div>
              );
            })}
            {visible.length === 0 && (
              <EmptyState icon={MailOpen} title={t('Здесь пока тихо')} description={search ? t('По этому запросу писем не нашлось. Попробуйте другое слово или сбросьте фильтр.') : t('В этой папке нет писем для выбранного фильтра.')} />
            )}
          </div>

          <button type="button" onClick={() => setComposeDraft({ to: '', subject: '', body: '' })} className="btn-primary absolute bottom-4 right-4 z-30 rounded-full shadow-pop md:hidden">
            <PenSquare size={16} /> {t('Написать')}
          </button>
        </section>

        {/* Чтение */}
        <main className={`${mobileReading ? 'flex' : 'hidden md:flex'} min-w-0 flex-1 flex-col bg-surface`}>
          {active ? (
            <>
              <header className="flex h-14 shrink-0 items-center gap-1 border-b border-line px-3 sm:px-4">
                <button type="button" onClick={() => setMobileReading(false)} className="btn-ghost px-2 md:hidden"><ArrowLeft size={16} /> {t('К списку')}</button>
                <IconButton icon={Archive} label={t('В архив')} onClick={() => moveActive('ARCHIVE')} />
                <IconButton icon={Trash2} label={t('Удалить')} onClick={() => moveActive('TRASH')} />
                <button type="button" onClick={() => toggleFlag(active)} className={`icon-btn ${active.flagged ? 'text-amber-500 hover:text-amber-600' : ''}`} aria-label={t('Важное')}>
                  <Star size={18} fill={active.flagged ? 'currentColor' : 'none'} />
                </button>
                <IconButton icon={MoreHorizontal} label={t('Ещё действия')} />
                <div className="ml-auto hidden items-center gap-1 sm:flex">
                  <span className="mr-1 hidden text-xs text-faint xl:inline">{t('{current} из {total}', { current: Math.max(activeIndex + 1, 1), total: visible.length })}</span>
                  <IconButton icon={ChevronUp} label={t('Предыдущее письмо')} onClick={() => step(-1)} disabled={activeIndex <= 0} />
                  <IconButton icon={ChevronDown} label={t('Следующее письмо')} onClick={() => step(1)} disabled={activeIndex < 0 || activeIndex >= visible.length - 1} />
                </div>
              </header>

              <div className="scroll-area min-h-0 flex-1">
                <article className="mx-auto w-full max-w-[760px] animate-fade-in px-5 py-7 sm:px-8 sm:py-9">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="chip chip-accent">{currentFolder?.name ?? active.folder}</span>
                    {active.flagged && <span className="chip border-amber-200 bg-amber-50 text-amber-800">{t('Важное')}</span>}
                  </div>
                  <h2 className="mt-4 text-[27px] font-semibold leading-tight tracking-[-0.025em] text-ink sm:text-[31px]">{active.subject}</h2>

                  <div className="mt-7 flex items-start gap-3 border-b border-line pb-6">
                    <Avatar name={active.from} size={44} />
                    <div className="min-w-0 flex-1">
                      <div className="truncate text-[15px] font-semibold">{cleanPersonName(active.from)}</div>
                      <div className="mt-0.5 truncate text-[13px] text-muted">{emailFromHeader(active.from) || t('внутренний отправитель')} · {t('мне')}</div>
                    </div>
                    <time className="shrink-0 text-[13px] text-faint">{active.date}</time>
                  </div>

                  {active.hasAttachments && (
                    <div className="mt-6 flex items-center gap-3 rounded-xl border border-line bg-chrome px-4 py-3">
                      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-accent-soft text-accent-dark"><FileCheck2 size={19} /></span>
                      <div className="min-w-0 flex-1">
                        <div className="truncate text-sm font-medium">{t('Файл во вложении')}</div>
                        <div className="text-xs text-muted">{t('Проверено антивирусом · безопасно открыть')}</div>
                      </div>
                      <button type="button" className="btn-secondary h-9 px-3 text-[13px]">{t('Скачать')}</button>
                    </div>
                  )}

                  <div className="mt-7 text-[15.5px] leading-[1.75] text-ink-soft">
                    {activeBody.split('\n').map((paragraph, i) => paragraph.trim() ? <p key={i} className="mb-4 whitespace-pre-wrap">{paragraph}</p> : null)}
                  </div>

                  <div className="mt-9 flex flex-wrap gap-2 border-t border-line pt-5">
                    <button type="button" className="btn-primary" onClick={() => setComposeDraft({ to: emailFromHeader(active.from), subject: `Re: ${active.subject}`, body: '' })}>
                      <Reply size={16} /> {t('Ответить')}
                    </button>
                    <button type="button" className="btn-secondary" onClick={() => setComposeDraft({ to: '', subject: `Fwd: ${active.subject}`, body: `\n\n--- ${t('Пересылаемое письмо')} ---\n${activeBody}` })}>
                      <Forward size={16} /> {t('Переслать')}
                    </button>
                    <button type="button" className="btn-ghost ml-auto" onClick={() => moveActive('ARCHIVE')}>
                      <Archive size={16} /> {t('В архив')}
                    </button>
                  </div>
                </article>
              </div>
            </>
          ) : (
            <div className="flex min-h-0 flex-1 flex-col">
              <EmptyState icon={Inbox} title={t('Выберите письмо')} description={t('Откройте письмо из списка или двигайтесь по нему клавишами J и K.')} />
              <div className="-mt-16 flex items-center justify-center gap-4 pb-10 text-xs text-faint">
                <span className="flex items-center gap-1.5"><span className="kbd">J</span><span className="kbd">K</span> {t('навигация')}</span>
                <span className="flex items-center gap-1.5"><span className="kbd">C</span> {t('написать')}</span>
                <span className="flex items-center gap-1.5"><span className="kbd">/</span> {t('поиск')}</span>
              </div>
            </div>
          )}
        </main>
      </div>

      {externalOpen && canExternalAccounts && (
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

function ComposePanel({ initial, currentEmail, externalAccounts, onClose, onSend }: {
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
  const [sent, setSent] = useState(false);
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const bodyRef = useRef<HTMLTextAreaElement>(null);
  const sendAsAccounts = externalAccounts.filter((account) => account.enabled && account.sendEnabled && account.smtpEnabled);

  const insertEmoji = (emoji: string) => {
    const element = bodyRef.current;
    const next = insertTextAtSelection(draft.body, emoji, element?.selectionStart ?? null, element?.selectionEnd ?? null);
    setDraft({ ...draft, body: next.value });
    requestAnimationFrame(() => {
      element?.focus();
      element?.setSelectionRange(next.cursor, next.cursor);
    });
  };

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
    <section className="fixed inset-x-0 bottom-0 z-50 flex max-h-[94dvh] animate-rise-in flex-col overflow-hidden border border-line bg-surface shadow-composer sm:inset-x-auto sm:bottom-4 sm:right-4 sm:h-[min(640px,92dvh)] sm:w-[min(590px,calc(100vw-2rem))] sm:rounded-2xl" aria-label={t('Новое письмо')}>
      <header className="flex h-11 shrink-0 items-center bg-ink px-4 text-white">
        <h3 className="flex-1 text-sm font-medium">{initial.subject ? t('Ответ / пересылка') : t('Новое письмо')}</h3>
        <button type="button" onClick={onClose} className="flex h-8 w-8 items-center justify-center rounded-lg text-white/70 transition hover:bg-white/10 hover:text-white" aria-label={t('Свернуть черновик')}><Minus size={16} /></button>
        <button type="button" onClick={onClose} className="flex h-8 w-8 items-center justify-center rounded-lg text-white/70 transition hover:bg-white/10 hover:text-white" aria-label={t('Закрыть черновик')}><X size={16} /></button>
      </header>

      {sent ? (
        <div className="flex flex-1 flex-col items-center justify-center px-8 text-center">
          <span className="flex h-14 w-14 items-center justify-center rounded-full bg-emerald-50 text-emerald-600"><CheckCircle2 size={27} /></span>
          <h4 className="mt-4 text-lg font-semibold">{t('Письмо отправлено')}</h4>
          <p className="mt-1 text-sm text-muted">{t('Копия сохранена в папке «Отправленные».')}</p>
        </div>
      ) : (
        <form onSubmit={submit} className="flex min-h-0 flex-1 flex-col">
          {sendAsAccounts.length > 0 && (
            <div className="flex h-12 shrink-0 items-center gap-3 border-b border-line px-4">
              <label htmlFor="compose-from" className="w-12 shrink-0 text-[13px] text-faint">{t('От кого')}</label>
              <select
                id="compose-from"
                value={draft.externalAccountId ?? ''}
                onChange={(e) => setDraft({ ...draft, externalAccountId: e.target.value || null })}
                className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none focus:ring-0"
              >
                <option value="">{currentEmail} · Corvex</option>
                {sendAsAccounts.map((account) => (
                  <option key={account.id} value={account.id}>{account.emailAddress} · {account.label}</option>
                ))}
              </select>
            </div>
          )}
          <div className="flex h-12 shrink-0 items-center gap-3 border-b border-line px-4">
            <label htmlFor="compose-to" className="w-12 shrink-0 text-[13px] text-faint">{t('Кому')}</label>
            <input id="compose-to" required type="text" inputMode="email" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} placeholder="user@company.local" className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none placeholder:text-faint focus:ring-0" />
            {!showCc && <button type="button" onClick={() => setShowCc(true)} className="shrink-0 text-xs font-medium text-muted hover:text-ink">{t('Копия')}</button>}
          </div>
          {showCc && (
            <div className="flex h-12 shrink-0 items-center gap-3 border-b border-line px-4">
              <label htmlFor="compose-cc" className="w-12 shrink-0 text-[13px] text-faint">{t('Копия')}</label>
              <input id="compose-cc" type="text" inputMode="email" value={draft.cc ?? ''} onChange={(e) => setDraft({ ...draft, cc: e.target.value })} placeholder="user@company.local" className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none placeholder:text-faint focus:ring-0" />
            </div>
          )}
          <div className="flex h-12 shrink-0 items-center gap-3 border-b border-line px-4">
            <label htmlFor="compose-subject" className="w-12 shrink-0 text-[13px] text-faint">{t('Тема')}</label>
            <input id="compose-subject" required value={draft.subject} onChange={(e) => setDraft({ ...draft, subject: e.target.value })} placeholder={t('О чём письмо?')} className="min-w-0 flex-1 border-0 bg-transparent text-sm font-medium outline-none placeholder:font-normal placeholder:text-faint focus:ring-0" />
          </div>
          <textarea
            ref={bodyRef}
            required
            value={draft.body}
            onChange={(e) => setDraft({ ...draft, body: e.target.value })}
            placeholder={t('Напишите сообщение…')}
            className="min-h-[180px] flex-1 resize-none border-0 bg-transparent px-4 py-4 text-[15px] leading-relaxed outline-none placeholder:text-faint focus:ring-0"
          />
          {files.length > 0 && (
            <div className="flex shrink-0 flex-wrap gap-2 px-4 pb-3">
              {files.map((file, index) => <span key={`${file.name}-${index}`} className="chip"><Paperclip size={12} />{file.name}</span>)}
            </div>
          )}
          {sendError && (
            <div className="mx-4 mb-3 shrink-0 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800" role="alert">{sendError}</div>
          )}
          <footer className="flex shrink-0 items-center gap-2 border-t border-line px-3 py-3">
            <button className="btn-accent" disabled={sending}><Send size={15} /> {t('Отправить')}</button>
            <input ref={fileRef} type="file" multiple className="hidden" onChange={(e) => setFiles(Array.from(e.target.files ?? []))} />
            <IconButton icon={Paperclip} label={t('Прикрепить файлы')} onClick={() => fileRef.current?.click()} />
            <EmojiPickerButton onPick={insertEmoji} />
            <span className="ml-1 hidden text-[11px] text-faint sm:inline">{t('Подпись добавится автоматически')}</span>
            <IconButton icon={Trash2} label={t('Удалить черновик')} className="ml-auto" onClick={onClose} />
          </footer>
        </form>
      )}
    </section>
  );
}
