import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import {
  ChevronRight,
  Download,
  File as FileIcon,
  Folder,
  FolderInput,
  FolderPlus,
  HardDrive,
  Link2,
  Loader2,
  Pencil,
  RotateCcw,
  Search,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import SidebarSlot, { useSidebarSlotContext } from '../../components/SidebarSlot';
import { Notice } from '../../components/ui';
import { useModalFocus } from '../../components/useModalFocus';
import { formatDate, localizeApiDetail, useI18n, type Locale } from '../../i18n';
import { ApiError } from '../../api/httpClient';
import {
  absoluteShareUrl,
  DriveNode,
  DriveShare,
  DriveUsage,
  DriveView,
  driveApi,
  formatBytes,
  saveBlob,
} from '../../api/driveApi';

/**
 * Личный диск: папки, файлы, корзина и ссылки «как в Google Drive».
 *
 * Файл приватный по умолчанию. Ссылка появляется только после явного действия,
 * показывается один раз (на сервере хранится лишь хеш токена) и отзывается
 * мгновенно — список активных ссылок виден владельцу в том же диалоге.
 */

type Dialog =
  | { kind: 'folder' }
  | { kind: 'rename'; node: DriveNode }
  | { kind: 'move'; node: DriveNode }
  | { kind: 'share'; node: DriveNode }
  | { kind: 'purge'; node: DriveNode }
  | null;

interface Crumb {
  id: string | null;
  name: string;
}

interface UploadProgress {
  total: number;
  done: number;
  failed: number;
}

/** Папки всегда выше файлов: так список читается одинаково после каждой мутации. */
export function sortNodes(nodes: DriveNode[]): DriveNode[] {
  return [...nodes].sort((left, right) => {
    if (left.kind !== right.kind) return left.kind === 'FOLDER' ? -1 : 1;
    return left.name.localeCompare(right.name, 'ru', { numeric: true, sensitivity: 'base' });
  });
}

/**
 * Исходный Problem Details остаётся главным источником текста: сервер знает
 * причину 409/413/422/423/503 лучше клиента. Общий текст — только запасной.
 */
export function driveErrorMessage(
  error: unknown,
  t: (key: string) => string,
  locale: Locale = 'ru',
): string {
  if (error instanceof ApiError) {
    const detail = error.detail?.trim();
    if (detail && detail !== `HTTP ${error.status}`) return localizeApiDetail(detail, locale);
    if (error.status === 0 || !error.status) return t('Сервер недоступен. Повторите позже.');
    return `${t('Сервер отклонил запрос.')} ${t('Код ответа')}: ${error.status}.`;
  }
  if (error instanceof Error && error.message) return error.message;
  return t('Сервер недоступен. Повторите позже.');
}

/**
 * Копирование адреса ссылки. Clipboard API доступен не всегда (нет secure
 * context, отказ разрешения), поэтому остаётся ручной путь через выделение.
 */
async function copyAddress(value: string, input: HTMLInputElement | null): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value);
      return true;
    }
  } catch {
    // Fall through to the selection-based fallback below.
  }
  try {
    input?.focus();
    input?.select();
    return typeof document.execCommand === 'function' && document.execCommand('copy');
  } catch {
    return false;
  }
}

// Действия видны на touch-устройствах постоянно и появляются при hover/focus
// только там, где hover действительно есть (широкие touch-экраны не страдают).
const ROW_ACTIONS =
  'flex shrink-0 items-center gap-0.5 opacity-100 [@media(hover:hover)]:opacity-0 [@media(hover:hover)]:group-hover:opacity-100 [@media(hover:hover)]:group-focus-within:opacity-100';

const INPUT_CLASS =
  'h-10 w-full rounded-lg border border-line-control bg-surface px-3 text-[13.5px] text-ink placeholder:text-faint focus:border-accent focus:ring-2 focus:ring-accent/15 disabled:cursor-not-allowed disabled:opacity-60';

const CARD_CLASS =
  'my-auto w-full max-w-md rounded-xl border border-line bg-surface p-5 shadow-pop';

export default function FilesPage() {
  const { t, locale } = useI18n();
  const { onCloseNavigation } = useSidebarSlotContext();
  const [view, setView] = useState<DriveView>('files');
  const [parentId, setParentId] = useState<string | null>(null);
  const [trail, setTrail] = useState<Crumb[]>([]);
  const [nodes, setNodes] = useState<DriveNode[]>([]);
  const [usage, setUsage] = useState<DriveUsage | null>(null);
  const [usageError, setUsageError] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [query, setQuery] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const [upload, setUpload] = useState<UploadProgress | null>(null);
  const [uploadFailures, setUploadFailures] = useState<string[]>([]);
  const [dragging, setDragging] = useState(false);
  const [dialog, setDialog] = useState<Dialog>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const dragDepth = useRef(0);
  const listSequence = useRef(0);
  const usageSequence = useRef(0);
  const uploading = useRef(false);

  const uploadingNow = upload !== null;

  useEffect(() => {
    // Пустой запрос совпадает с начальным состоянием: таймер не должен
    // очищать уже загруженный список через 250 мс после монтирования.
    if (query === debouncedQuery) return undefined;
    const timer = setTimeout(() => {
      // Старый список не должен мелькать под новым запросом.
      setNodes([]);
      setDebouncedQuery(query);
    }, 250);
    return () => clearTimeout(timer);
  }, [query, debouncedQuery]);

  const reloadList = useCallback(async () => {
    const sequence = ++listSequence.current;
    setLoading(true);
    try {
      const items = await driveApi.list(parentId, view, debouncedQuery);
      if (sequence !== listSequence.current) return;
      setNodes(sortNodes(items));
      setError('');
    } catch (e) {
      if (sequence !== listSequence.current) return;
      setError(driveErrorMessage(e, t, locale));
    } finally {
      if (sequence === listSequence.current) setLoading(false);
    }
  }, [parentId, view, debouncedQuery, t, locale]);

  // Квота грузится отдельно: сбой usage не должен прятать уже полученный список.
  const reloadUsage = useCallback(async () => {
    const sequence = ++usageSequence.current;
    try {
      const current = await driveApi.usage();
      if (sequence !== usageSequence.current) return;
      setUsage(current);
      setUsageError('');
    } catch (e) {
      if (sequence !== usageSequence.current) return;
      setUsageError(driveErrorMessage(e, t, locale));
    }
  }, [t, locale]);

  useEffect(() => {
    void reloadList();
  }, [reloadList]);

  useEffect(() => {
    void reloadUsage();
  }, [reloadUsage]);

  /**
   * Мутация возвращает текст ошибки (его показывает диалог) или null.
   * Успех объявляется только после ответа 2xx и обновления списка.
   */
  const run = useCallback(
    async (
      action: () => Promise<unknown>,
      message?: string,
      options?: { usage?: boolean },
    ): Promise<string | null> => {
      setError('');
      setNotice('');
      try {
        await action();
      } catch (e) {
        return driveErrorMessage(e, t, locale);
      }
      if (message) setNotice(message);
      await reloadList();
      if (options?.usage) await reloadUsage();
      return null;
    },
    [reloadList, reloadUsage, t, locale],
  );

  const uploadFiles = useCallback(
    async (files: FileList | File[]) => {
      if (view !== 'files' || uploading.current) return;
      const list = Array.from(files);
      if (list.length === 0) return;
      uploading.current = true;
      setError('');
      setNotice('');
      setUploadFailures([]);
      setUpload({ total: list.length, done: 0, failed: 0 });
      const failures: string[] = [];
      let done = 0;
      let uploaded = 0;
      for (const file of list) {
        try {
          await driveApi.upload(parentId, file);
          uploaded += 1;
        } catch (e) {
          failures.push(`${file.name}: ${driveErrorMessage(e, t, locale)}`);
        }
        done += 1;
        setUpload({ total: list.length, done, failed: failures.length });
      }
      uploading.current = false;
      setUpload(null);
      if (failures.length > 0) setUploadFailures(failures);
      if (uploaded === 1) setNotice(t('Файл загружен'));
      if (uploaded > 1) setNotice(t('Загружено файлов: {count}', { count: uploaded }));
      // Список обновляется и после частичного сбоя: успешные файлы уже на сервере.
      await reloadList();
      await reloadUsage();
    },
    [view, parentId, reloadList, reloadUsage, t, locale],
  );

  const openFolder = (folder: DriveNode) => {
    setTrail((current) => [...current, { id: folder.id, name: folder.name }]);
    setParentId(folder.id);
    setNodes([]);
    setQuery('');
    setNotice('');
  };

  const goTo = (index: number) => {
    const next = trail.slice(0, index);
    setTrail(next);
    setParentId(next.length ? next[next.length - 1].id : null);
    setNodes([]);
    setQuery('');
    setNotice('');
  };

  const switchView = (next: DriveView) => {
    // На мобильном список разделов живёт в выдвижном ящике: после выбора
    // раздела ящик должен закрыться, иначе он закрывает сам список файлов.
    onCloseNavigation?.();
    setView(next);
    setTrail([]);
    setParentId(null);
    setNodes([]);
    setQuery('');
    setNotice('');
    setUploadFailures([]);
  };

  const download = async (node: DriveNode) => {
    setError('');
    try {
      const blob = await driveApi.download(node.id);
      await saveBlob(blob, node.name);
    } catch (e) {
      setError(driveErrorMessage(e, t, locale));
    }
  };

  const usedPercent =
    usage && usage.quotaBytes > 0
      ? Math.min(100, Math.round((usage.usedBytes / usage.quotaBytes) * 100))
      : null;

  const rootLabel = view === 'trash' ? t('Корзина') : t('Мои файлы');

  return (
    <>
      <SidebarSlot ariaLabel={t('Файлы')}>
        {({ collapsed }) => (
          <div className="space-y-3 text-[13px]">
            {!collapsed && (
              <p className="px-2 text-[11px] font-semibold uppercase tracking-wide text-sidebar-muted">
                {t('Диск')}
              </p>
            )}
            <button
              type="button"
              onClick={() => switchView('files')}
              aria-current={view === 'files' ? 'page' : undefined}
              title={t('Мои файлы')}
              className={`sidebar-item w-full ${view === 'files' ? 'sidebar-item-active' : ''}`}
            >
              <Folder size={16} strokeWidth={1.8} className="shrink-0" />
              <span className={`truncate ${collapsed ? 'md:hidden' : ''}`}>{t('Мои файлы')}</span>
            </button>
            <button
              type="button"
              onClick={() => switchView('trash')}
              aria-current={view === 'trash' ? 'page' : undefined}
              title={t('Корзина')}
              className={`sidebar-item w-full ${view === 'trash' ? 'sidebar-item-active' : ''}`}
            >
              <Trash2 size={16} strokeWidth={1.8} className="shrink-0" />
              <span className={`truncate ${collapsed ? 'md:hidden' : ''}`}>{t('Корзина')}</span>
            </button>

            {!collapsed && usage && (
              <div className="mt-4 rounded-lg border border-sidebar-line bg-sidebar-sub px-3 py-2.5">
                <div className="flex items-baseline justify-between text-[11.5px] text-sidebar-muted">
                  <span>{t('Занято')}</span>
                  <span className="tabular-nums">{usedPercent === null ? t('без лимита') : `${usedPercent}%`}</span>
                </div>
                <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-white/10">
                  <div
                    className="h-full rounded-full bg-accent"
                    style={{ width: `${usedPercent ?? 0}%` }}
                    aria-hidden="true"
                  />
                </div>
                <p className="mt-1.5 text-[11px] text-sidebar-muted tabular-nums">
                  {formatBytes(usage.usedBytes, locale)} / {usage.quotaBytes > 0 ? formatBytes(usage.quotaBytes, locale) : t('без лимита')}
                </p>
              </div>
            )}
          </div>
        )}
      </SidebarSlot>

      <section className="flex min-h-0 flex-1 flex-col bg-canvas">
        <header className="border-b border-line bg-chrome px-3 py-2.5 sm:px-6">
          <div className="flex flex-wrap items-center gap-2">
            <nav aria-label={t('Путь')} className="flex min-w-0 flex-1 items-center gap-1 text-[13px]">
              <button
                type="button"
                onClick={() => goTo(0)}
                className="truncate rounded px-1.5 py-1 font-medium text-ink hover:bg-line-subtle"
              >
                {rootLabel}
              </button>
              {trail.map((crumb, index) => (
                <span key={crumb.id ?? 'root'} className="flex min-w-0 items-center gap-1">
                  <ChevronRight size={14} className="shrink-0 text-faint" aria-hidden="true" />
                  <button
                    type="button"
                    onClick={() => goTo(index + 1)}
                    aria-current={index === trail.length - 1 ? 'page' : undefined}
                    className={`truncate rounded px-1.5 py-1 hover:bg-line-subtle ${
                      index === trail.length - 1 ? 'font-medium text-ink' : 'text-muted hover:text-ink'
                    }`}
                  >
                    {crumb.name}
                  </button>
                </span>
              ))}
            </nav>

            <label className="relative flex h-9 w-full items-center sm:w-64">
              <Search size={15} className="pointer-events-none absolute left-2.5 text-faint" aria-hidden="true" />
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder={t('Поиск по файлам')}
                aria-label={t('Поиск по файлам')}
                className="h-9 w-full rounded-lg border border-line-control bg-surface pl-8 pr-8 text-[13px] text-ink placeholder:text-faint focus:border-accent focus:ring-2 focus:ring-accent/15"
              />
              {query && (
                <button
                  type="button"
                  onClick={() => setQuery('')}
                  aria-label={t('Очистить поиск')}
                  className="absolute right-1.5 rounded p-1 text-faint hover:bg-line-subtle hover:text-ink"
                >
                  <X size={14} />
                </button>
              )}
            </label>
          </div>

          <div className="mt-2 flex flex-wrap items-center gap-2">
            <div className="flex rounded-lg border border-line-control bg-surface p-0.5" role="group" aria-label={t('Разделы диска')}>
              <button
                type="button"
                onClick={() => switchView('files')}
                aria-pressed={view === 'files'}
                className={`inline-flex h-8 items-center gap-1.5 rounded-md px-2.5 text-[12.5px] font-medium transition ${
                  view === 'files' ? 'bg-accent-soft text-accent-dark' : 'text-muted hover:text-ink'
                }`}
              >
                <Folder size={15} strokeWidth={1.8} aria-hidden="true" />
                {t('Мои файлы')}
              </button>
              <button
                type="button"
                onClick={() => switchView('trash')}
                aria-pressed={view === 'trash'}
                className={`inline-flex h-8 items-center gap-1.5 rounded-md px-2.5 text-[12.5px] font-medium transition ${
                  view === 'trash' ? 'bg-accent-soft text-accent-dark' : 'text-muted hover:text-ink'
                }`}
              >
                <Trash2 size={15} strokeWidth={1.8} aria-hidden="true" />
                {t('Корзина')}
              </button>
            </div>

            {view === 'files' && (
              <div className="ml-auto flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => setDialog({ kind: 'folder' })}
                  aria-label={t('Новая папка')}
                  title={t('Новая папка')}
                  className="inline-flex h-9 items-center gap-1.5 rounded-lg border border-line-control bg-surface px-3 text-[13px] font-medium text-ink hover:border-accent hover:text-accent"
                >
                  <FolderPlus size={16} strokeWidth={1.8} aria-hidden="true" />
                  <span className="hidden sm:inline">{t('Новая папка')}</span>
                </button>
                <button
                  type="button"
                  onClick={() => fileInput.current?.click()}
                  disabled={uploadingNow}
                  className="inline-flex h-9 items-center gap-1.5 rounded-lg bg-accent px-3 text-[13px] font-medium text-white hover:bg-accent-dark disabled:opacity-60"
                >
                  {uploadingNow ? (
                    <Loader2 size={16} className="animate-spin" aria-hidden="true" />
                  ) : (
                    <Upload size={16} strokeWidth={1.8} aria-hidden="true" />
                  )}
                  {uploadingNow ? t('Загрузка…') : t('Загрузить')}
                </button>
                <input
                  ref={fileInput}
                  type="file"
                  multiple
                  className="hidden"
                  data-testid="file-input"
                  onChange={(event) => {
                    if (event.target.files) void uploadFiles(event.target.files);
                    event.target.value = '';
                  }}
                />
              </div>
            )}
          </div>
        </header>

        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-line bg-chrome px-3 py-1.5 text-[12px] text-muted sm:px-6">
          <HardDrive size={14} className="shrink-0" aria-hidden="true" />
          {usage ? (
            <>
              <span className="tabular-nums">
                {usage.quotaBytes > 0
                  ? t('Занято {used} из {all}', {
                      used: formatBytes(usage.usedBytes, locale),
                      all: formatBytes(usage.quotaBytes, locale),
                    })
                  : t('Использовано {used}', { used: formatBytes(usage.usedBytes, locale) })}
              </span>
              {usedPercent !== null && (
                <span
                  role="progressbar"
                  aria-label={t('Использовано места на диске')}
                  aria-valuemin={0}
                  aria-valuemax={100}
                  aria-valuenow={usedPercent}
                  className="h-1.5 w-28 overflow-hidden rounded-full bg-line-subtle"
                >
                  <span className="block h-full rounded-full bg-accent" style={{ width: `${usedPercent}%` }} />
                </span>
              )}
            </>
          ) : usageError ? (
            <>
              <span className="min-w-0 break-words">
                {t('Не удалось загрузить занятое место')}: {usageError}
              </span>
              <button
                type="button"
                onClick={() => void reloadUsage()}
                className="rounded px-1.5 py-0.5 font-medium text-accent hover:bg-accent-soft"
              >
                {t('Повторить')}
              </button>
            </>
          ) : (
            <span>{t('Загружаем…')}</span>
          )}
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto px-2 py-3 sm:px-4">
          {(error || notice || uploadFailures.length > 0 || upload) && (
            <div className="mb-3 space-y-2 px-1 sm:px-2">
              {upload && (
                <div>
                  <p role="status" className="text-[12.5px] text-muted">
                    {t('Загружено {done} из {total}', { done: upload.done, total: upload.total })}
                  </p>
                  <div
                    role="progressbar"
                    aria-label={t('Прогресс загрузки')}
                    aria-valuemin={0}
                    aria-valuemax={upload.total}
                    aria-valuenow={upload.done}
                    className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-line-subtle"
                  >
                    <div
                      className="h-full rounded-full bg-accent transition-all"
                      style={{ width: `${Math.round((upload.done / upload.total) * 100)}%` }}
                    />
                  </div>
                </div>
              )}
              {error && (
                <Notice tone="error">
                  <div className="flex items-start justify-between gap-2">
                    <p>{error}</p>
                    <button
                      type="button"
                      onClick={() => {
                        setError('');
                        void reloadList();
                      }}
                      className="shrink-0 rounded px-1.5 py-0.5 text-[12px] font-medium text-accent hover:bg-accent-soft"
                    >
                      {t('Повторить')}
                    </button>
                  </div>
                </Notice>
              )}
              {uploadFailures.length > 0 && (
                <Notice tone="error">
                  <div className="flex items-start justify-between gap-2">
                    <p>{t('Не удалось загрузить часть файлов.')}</p>
                    <button
                      type="button"
                      onClick={() => setUploadFailures([])}
                      aria-label={t('Скрыть ошибки загрузки')}
                      className="shrink-0 rounded p-1 text-accent hover:bg-accent-soft"
                    >
                      <X size={13} />
                    </button>
                  </div>
                  <ul aria-label={t('Файлы с ошибками')} className="mt-1 space-y-0.5 text-[12px]">
                    {uploadFailures.map((failure, index) => (
                      <li key={`${index}-${failure}`} className="break-words">{failure}</li>
                    ))}
                  </ul>
                </Notice>
              )}
              {notice && <Notice tone="success">{notice}</Notice>}
            </div>
          )}

          <div
            className={`min-h-full rounded-lg ${dragging ? 'bg-accent-soft ring-1 ring-accent/40' : ''}`}
            onDragEnter={(event) => {
              if (view !== 'files') return;
              event.preventDefault();
              dragDepth.current += 1;
              setDragging(true);
            }}
            onDragOver={(event) => {
              if (view !== 'files') return;
              event.preventDefault();
              if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy';
            }}
            onDragLeave={() => {
              dragDepth.current = Math.max(0, dragDepth.current - 1);
              if (dragDepth.current === 0) setDragging(false);
            }}
            onDrop={(event) => {
              event.preventDefault();
              dragDepth.current = 0;
              setDragging(false);
              if (view === 'files' && event.dataTransfer.files.length > 0) {
                void uploadFiles(event.dataTransfer.files);
              }
            }}
          >
            {loading && nodes.length === 0 ? (
              <p className="px-3 py-6 text-[13px] text-muted">{t('Загружаем файлы…')}</p>
            ) : nodes.length === 0 ? (
              <div className="mx-auto mt-10 max-w-md rounded-xl border border-dashed border-line-control bg-surface px-6 py-10 text-center">
                <Folder size={28} strokeWidth={1.5} className="mx-auto text-faint" aria-hidden="true" />
                <p className="mt-3 text-[15px] font-medium text-ink">
                  {debouncedQuery
                    ? t('Ничего не найдено')
                    : view === 'trash'
                      ? t('Корзина пуста')
                      : t('Здесь пока нет файлов')}
                </p>
                <p className="mt-1 text-[13px] text-muted">
                  {debouncedQuery
                    ? t('Измените запрос или очистите поиск')
                    : view === 'trash'
                      ? t('Удалённые файлы хранятся здесь, пока вы не очистите корзину')
                      : t('Перетащите файлы сюда или нажмите «Загрузить»')}
                </p>
              </div>
            ) : (
              <ul className="space-y-0.5" aria-label={t('Содержимое папки')} aria-busy={loading}>
                {nodes.map((node) => {
                  const meta =
                    node.kind === 'FOLDER'
                      ? t('Папка')
                      : `${formatBytes(node.sizeBytes, locale)} · ${formatDate(locale, node.updatedAt)}`;
                  const body = (
                    <>
                      {node.kind === 'FOLDER' ? (
                        <Folder size={18} strokeWidth={1.7} className="shrink-0 text-accent" aria-hidden="true" />
                      ) : (
                        <FileIcon size={18} strokeWidth={1.7} className="shrink-0 text-faint" aria-hidden="true" />
                      )}
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-[13.5px] font-medium text-ink">{node.name}</span>
                        <span className="block truncate text-[11.5px] text-faint">
                          {meta}
                          {view === 'trash' && node.trashedAt
                            ? ` · ${t('Удалено')} ${formatDate(locale, node.trashedAt)}`
                            : ''}
                        </span>
                      </span>
                    </>
                  );
                  return (
                    <li key={node.id}>
                      <div className="group flex items-center gap-2 rounded-lg px-2 py-2 hover:bg-surface focus-within:bg-surface">
                        {view === 'files' ? (
                          <button
                            type="button"
                            onClick={() => (node.kind === 'FOLDER' ? openFolder(node) : void download(node))}
                            className="flex min-w-0 flex-1 items-center gap-3 rounded-md text-left"
                          >
                            {body}
                          </button>
                        ) : (
                          <div className="flex min-w-0 flex-1 items-center gap-3">{body}</div>
                        )}

                        <div className={ROW_ACTIONS}>
                          {node.kind === 'FILE' && view === 'files' && (
                            <>
                              <IconButton label={t('Скачать')} onClick={() => void download(node)}>
                                <Download size={15} />
                              </IconButton>
                              <IconButton label={t('Ссылка')} onClick={() => setDialog({ kind: 'share', node })}>
                                <Link2 size={15} />
                              </IconButton>
                            </>
                          )}
                          {view === 'files' && (
                            <>
                              <IconButton label={t('Переименовать')} onClick={() => setDialog({ kind: 'rename', node })}>
                                <Pencil size={15} />
                              </IconButton>
                              <IconButton label={t('Переместить')} onClick={() => setDialog({ kind: 'move', node })}>
                                <FolderInput size={15} />
                              </IconButton>
                              <IconButton
                                label={t('В корзину')}
                                danger
                                onClick={() => void run(() => driveApi.trash(node.id), t('Файл перемещён в корзину'), { usage: true })}
                              >
                                <Trash2 size={15} />
                              </IconButton>
                            </>
                          )}
                          {view === 'trash' && (
                            <>
                              <IconButton
                                label={t('Восстановить')}
                                onClick={() => void run(() => driveApi.restore(node.id), t('Файл восстановлен'), { usage: true })}
                              >
                                <RotateCcw size={15} />
                              </IconButton>
                              <IconButton label={t('Удалить навсегда')} danger onClick={() => setDialog({ kind: 'purge', node })}>
                                <Trash2 size={15} />
                              </IconButton>
                            </>
                          )}
                        </div>
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}

            {view === 'files' && dragging && (
              <p
                role="status"
                className="pointer-events-none sticky bottom-2 mx-auto w-fit rounded-xl border border-accent bg-surface px-4 py-3 text-center text-[13px] text-accent shadow-lg"
              >
                {t('Отпустите файлы, чтобы загрузить')}
              </p>
            )}
          </div>
        </div>
      </section>

      {dialog && (
        <DialogShell label={
          dialog.kind === 'folder' ? t('Новая папка')
            : dialog.kind === 'rename' ? t('Переименовать')
              : dialog.kind === 'move' ? t('Переместить')
                : dialog.kind === 'share' ? t('Ссылка на файл')
                  : t('Удалить навсегда?')
        } onClose={() => setDialog(null)}>
          {dialog.kind === 'folder' && (
            <NameDialog
              title={t('Новая папка')}
              fieldLabel={t('Название папки')}
              submitLabel={t('Создать')}
              initial=""
              onClose={() => setDialog(null)}
              onSubmit={async (name) => {
                const failure = await run(() => driveApi.createFolder(parentId, name), t('Папка создана'));
                if (!failure) setDialog(null);
                return failure;
              }}
            />
          )}

          {dialog.kind === 'rename' && (
            <NameDialog
              title={t('Переименовать')}
              fieldLabel={t('Новое имя')}
              submitLabel={t('Сохранить')}
              initial={dialog.node.name}
              onClose={() => setDialog(null)}
              onSubmit={async (name) => {
                const failure = await run(() => driveApi.rename(dialog.node.id, name), t('Имя изменено'));
                if (!failure) setDialog(null);
                return failure;
              }}
            />
          )}

          {dialog.kind === 'move' && (
            <MoveDialog
              node={dialog.node}
              onClose={() => setDialog(null)}
              onSubmit={async (targetId) => {
                const failure = await run(() => driveApi.move(dialog.node.id, targetId), t('Файл перемещён'));
                if (!failure) setDialog(null);
                return failure;
              }}
            />
          )}

          {dialog.kind === 'share' && <ShareDialog node={dialog.node} onClose={() => setDialog(null)} />}

          {dialog.kind === 'purge' && (
            <ConfirmDialog
              title={t('Удалить навсегда?')}
              text={t('Файл будет удалён без возможности восстановления, выданные ссылки перестанут работать.')}
              confirmLabel={t('Удалить навсегда')}
              onClose={() => setDialog(null)}
              onConfirm={async () => {
                const failure = await run(() => driveApi.purge(dialog.node.id), t('Удалено навсегда'), { usage: true });
                if (!failure) setDialog(null);
                return failure;
              }}
            />
          )}
        </DialogShell>
      )}
    </>
  );
}

function IconButton({
  label,
  onClick,
  danger,
  children,
}: {
  label: string;
  onClick: () => void;
  danger?: boolean;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      onClick={onClick}
      className={`inline-flex h-8 w-8 items-center justify-center rounded-md text-muted transition hover:bg-line-subtle sm:h-8 sm:w-8 ${
        danger ? 'hover:text-danger' : 'hover:text-ink'
      }`}
    >
      {children}
    </button>
  );
}

function DialogShell({
  label,
  children,
  onClose,
}: {
  label: string;
  children: ReactNode;
  onClose: () => void;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  useModalFocus(containerRef);

  // useModalFocus ставит фокус в контейнер; конкретное поле получает его следом,
  // иначе autoFocus мгновенно перебивается фокусом диалога.
  useEffect(() => {
    containerRef.current?.querySelector<HTMLElement>('[data-autofocus]')?.focus();
  }, []);

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-[70] flex items-start justify-center overflow-y-auto bg-ink/40 p-4 backdrop-blur-[2px] sm:items-center"
      role="presentation"
      onMouseDown={onClose}
    >
      <div
        ref={containerRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        className={CARD_CLASS}
        onMouseDown={(event) => event.stopPropagation()}
      >
        {children}
      </div>
    </div>
  );
}

function DialogFooter({ onClose, submitLabel, busy, disabled }: {
  onClose: () => void;
  submitLabel: string;
  busy: boolean;
  disabled?: boolean;
}) {
  const { t } = useI18n();
  return (
    <div className="mt-4 flex justify-end gap-2">
      <button
        type="button"
        onClick={onClose}
        className="h-9 rounded-lg border border-line-control px-3 text-[13px] text-ink hover:bg-line-subtle"
      >
        {t('Отмена')}
      </button>
      <button
        type="submit"
        disabled={busy || disabled}
        className="h-9 rounded-lg bg-accent px-3 text-[13px] font-medium text-white hover:bg-accent-dark disabled:opacity-60"
      >
        {submitLabel}
      </button>
    </div>
  );
}

function errorForName(name: string, t: (key: string) => string): string {
  if (!name) return t('Укажите название.');
  if (name === '.' || name === '..' || name.includes('/') || name.includes('\\')) {
    return t('Название не может быть точкой и не может содержать косую черту.');
  }
  return '';
}

function NameDialog({
  title,
  fieldLabel,
  initial,
  submitLabel,
  onSubmit,
  onClose,
}: {
  title: string;
  fieldLabel: string;
  initial: string;
  submitLabel: string;
  onSubmit: (name: string) => Promise<string | null>;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const [name, setName] = useState(initial);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy) return;
    const value = name.trim();
    const problem = errorForName(value, t);
    if (problem) {
      setError(problem);
      return;
    }
    setError('');
    setBusy(true);
    try {
      const failure = await onSubmit(value);
      setError(failure ?? '');
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={(event) => void submit(event)} noValidate>
      <h2 className="text-[15px] font-semibold text-ink">{title}</h2>
      <label htmlFor="drive-name" className="mt-3 block text-[12.5px] font-medium text-ink-soft">
        {fieldLabel}
      </label>
      <input
        data-autofocus
        id="drive-name"
        name="name"
        value={name}
        maxLength={200}
        onChange={(event) => {
          setName(event.target.value);
          setError('');
        }}
        aria-invalid={Boolean(error)}
        aria-describedby={error ? 'drive-name-error' : undefined}
        className={`mt-1 ${INPUT_CLASS}`}
      />
      {error && (
        <p id="drive-name-error" role="alert" className="mt-2 rounded-lg bg-badge-critical-bg px-3 py-2 text-[12px] text-badge-critical-text">
          {error}
        </p>
      )}
      <DialogFooter onClose={onClose} submitLabel={submitLabel} busy={busy} disabled={!name.trim()} />
    </form>
  );
}

function MoveDialog({
  node,
  onSubmit,
  onClose,
}: {
  node: DriveNode;
  onSubmit: (targetId: string | null) => Promise<string | null>;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const [trail, setTrail] = useState<Crumb[]>([]);
  const [folders, setFolders] = useState<DriveNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [attempt, setAttempt] = useState(0);

  const current = trail.length > 0 ? trail[trail.length - 1] : null;
  const targetId = current ? current.id : null;

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setLoadError('');
    driveApi
      .list(targetId, 'files', '')
      .then((items) => {
        if (!alive) return;
        // Собственную папку в списке нет: внутрь себя переместиться нельзя,
        // а значит недоступны и её потомки.
        setFolders(sortNodes(items).filter((item) => item.kind === 'FOLDER' && item.id !== node.id));
      })
      .catch((e) => {
        if (!alive) return;
        setFolders([]);
        setLoadError(driveErrorMessage(e, t));
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [targetId, node.id, attempt, t]);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy) return;
    setError('');
    setBusy(true);
    try {
      const failure = await onSubmit(targetId);
      setError(failure ?? '');
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={(event) => void submit(event)}>
      <h2 className="text-[15px] font-semibold text-ink">{t('Переместить')}</h2>
      <p className="mt-1 break-words text-[12.5px] text-muted">{node.name}</p>

      <nav aria-label={t('Путь')} className="mt-3 flex flex-wrap items-center gap-1 text-[12px]">
        <button
          type="button"
          onClick={() => setTrail([])}
          aria-current={trail.length === 0 ? 'page' : undefined}
          className={`rounded px-1.5 py-0.5 hover:bg-line-subtle ${trail.length === 0 ? 'font-medium text-ink' : 'text-muted'}`}
        >
          {t('Корень диска')}
        </button>
        {trail.map((crumb, index) => (
          <span key={crumb.id ?? 'root'} className="flex items-center gap-1">
            <ChevronRight size={12} className="text-faint" aria-hidden="true" />
            <button
              type="button"
              onClick={() => setTrail(trail.slice(0, index + 1))}
              className={`max-w-[10rem] truncate rounded px-1.5 py-0.5 hover:bg-line-subtle ${
                index === trail.length - 1 ? 'font-medium text-ink' : 'text-muted'
              }`}
            >
              {crumb.name}
            </button>
          </span>
        ))}
      </nav>

      <div className="mt-2 max-h-56 overflow-y-auto rounded-lg border border-line">
        {loading ? (
          <p className="px-3 py-3 text-[12.5px] text-muted">{t('Загружаем папки…')}</p>
        ) : loadError ? (
          <div className="px-3 py-3 text-[12.5px]">
            <p role="alert" className="text-badge-critical-text">{loadError}</p>
            <button
              type="button"
              onClick={() => setAttempt((value) => value + 1)}
              className="mt-1 rounded px-1.5 py-0.5 font-medium text-accent hover:bg-accent-soft"
            >
              {t('Повторить')}
            </button>
          </div>
        ) : folders.length === 0 ? (
          <p className="px-3 py-3 text-[12.5px] text-muted">{t('Вложенных папок нет')}</p>
        ) : (
          <ul className="divide-y divide-line-subtle">
            {folders.map((folder) => (
              <li key={folder.id}>
                <button
                  type="button"
                  onClick={() => setTrail([...trail, { id: folder.id, name: folder.name }])}
                  className="flex w-full items-center gap-2 px-3 py-2 text-left text-[13px] text-ink hover:bg-canvas"
                >
                  <Folder size={16} className="shrink-0 text-accent" aria-hidden="true" />
                  <span className="min-w-0 flex-1 truncate">{folder.name}</span>
                  <ChevronRight size={14} className="shrink-0 text-faint" aria-hidden="true" />
                  <span className="sr-only">{t('Открыть папку')}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <p className="mt-2 text-[12.5px] text-muted">
        {t('Папка назначения')}: <span className="font-medium text-ink">{current ? current.name : t('Корень диска')}</span>
      </p>

      {error && (
        <p role="alert" className="mt-2 rounded-lg bg-badge-critical-bg px-3 py-2 text-[12px] text-badge-critical-text">
          {error}
        </p>
      )}

      <DialogFooter onClose={onClose} submitLabel={t('Переместить')} busy={busy} />
    </form>
  );
}

function ShareDialog({ node, onClose }: { node: DriveNode; onClose: () => void }) {
  const { t, locale } = useI18n();
  const [shares, setShares] = useState<DriveShare[]>([]);
  const [sharesLoading, setSharesLoading] = useState(true);
  const [sharesError, setSharesError] = useState('');
  const [created, setCreated] = useState('');
  const [expires, setExpires] = useState('');
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState('');
  const [revoking, setRevoking] = useState('');
  const [revokeError, setRevokeError] = useState('');
  const [copied, setCopied] = useState(false);
  const [copyFailed, setCopyFailed] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  const loadShares = useCallback(async () => {
    setSharesLoading(true);
    try {
      const items = await driveApi.shares(node.id);
      if (!alive.current) return;
      setShares(items);
      setSharesError('');
    } catch (e) {
      if (!alive.current) return;
      setShares([]);
      setSharesError(driveErrorMessage(e, t, locale));
    } finally {
      if (alive.current) setSharesLoading(false);
    }
  }, [node.id, t, locale]);

  useEffect(() => {
    void loadShares();
  }, [loadShares]);

  const now = Date.now();
  const active = shares.filter(
    (share) => !share.revokedAt && (!share.expiresAt || new Date(share.expiresAt).getTime() > now),
  );

  const create = async () => {
    if (creating) return;
    setCreating(true);
    setCreateError('');
    setCopied(false);
    setCopyFailed(false);
    setCreated('');
    try {
      const result = await driveApi.createShare(node.id, expires ? Number(expires) : null);
      if (!alive.current) return;
      // Адрес собирается из серверного path и origin сайта. Токен не логируется
      // и не попадает ни в name/alt, ни в query приложения.
      setCreated(absoluteShareUrl(result.path));
      await loadShares();
    } catch (e) {
      if (!alive.current) return;
      setCreateError(driveErrorMessage(e, t, locale));
    } finally {
      if (alive.current) setCreating(false);
    }
  };

  const revoke = async (shareId: string) => {
    setRevoking(shareId);
    setRevokeError('');
    try {
      await driveApi.revokeShare(shareId);
      if (!alive.current) return;
      // Отозванная ссылка больше не показывается: её адрес всё равно не работает.
      setShares((current) => current.map((share) =>
        share.id === shareId ? { ...share, revokedAt: new Date().toISOString() } : share));
      await loadShares();
    } catch (e) {
      if (!alive.current) return;
      setRevokeError(driveErrorMessage(e, t, locale));
    } finally {
      if (alive.current) setRevoking('');
    }
  };

  return (
    <div>
      <h2 className="text-[15px] font-semibold text-ink">{t('Ссылка на файл')}</h2>
      <p className="mt-1 break-words text-[12px] text-muted">{node.name}</p>
      <p className="mt-1 text-[12.5px] text-muted">
        {t('Пока ссылка не создана, файл видите только вы. Ссылка даёт просмотр и скачивание любому, у кого она есть.')}
      </p>

      <div className="mt-3 flex flex-wrap items-end gap-2">
        <label className="min-w-0 flex-1 text-[12.5px] font-medium text-ink">
          {t('Срок действия')}
          <select
            value={expires}
            onChange={(event) => setExpires(event.target.value)}
            className="mt-1 h-9 w-full rounded-lg border border-line-control bg-surface px-2 text-[13px] font-normal text-ink focus:border-accent focus:ring-2 focus:ring-accent/15"
          >
            <option value="">{t('Без ограничения')}</option>
            <option value="7">{t('7 дней')}</option>
            <option value="30">{t('30 дней')}</option>
          </select>
        </label>
        <button
          type="button"
          disabled={creating}
          onClick={() => void create()}
          className="inline-flex h-9 items-center gap-1.5 rounded-lg bg-accent px-3 text-[13px] font-medium text-white hover:bg-accent-dark disabled:opacity-60"
        >
          {creating ? <Loader2 size={15} className="animate-spin" aria-hidden="true" /> : <Link2 size={15} aria-hidden="true" />}
          {creating ? t('Создаём…') : t('Создать ссылку')}
        </button>
      </div>

      {createError && (
        <p role="alert" className="mt-2 rounded-lg bg-badge-critical-bg px-3 py-2 text-[12px] text-badge-critical-text">
          {createError}
        </p>
      )}

      {created && (
        <div className="mt-3 rounded-lg border border-accent-muted bg-accent-soft p-3">
          <p className="text-[12px] font-medium text-accent-dark">
            {t('Ссылка создана. Скопируйте её сейчас — позже токен не показывается.')}
          </p>
          <div className="mt-2 flex flex-wrap gap-2">
            <input
              ref={inputRef}
              readOnly
              value={created}
              aria-label={t('Ссылка на файл')}
              onFocus={(event) => event.currentTarget.select()}
              className="h-9 min-w-0 flex-1 rounded-lg border border-line-control bg-surface px-2 text-[12px] text-ink focus:border-accent focus:ring-2 focus:ring-accent/15"
            />
            <button
              type="button"
              onClick={async () => {
                const ok = await copyAddress(created, inputRef.current);
                setCopied(ok);
                setCopyFailed(!ok);
              }}
              className="h-9 shrink-0 rounded-lg border border-line-control bg-surface px-3 text-[12.5px] text-ink hover:border-accent hover:text-accent"
            >
              {copied ? t('Скопировано') : t('Копировать')}
            </button>
          </div>
          {copyFailed && (
            <p role="status" className="mt-1 text-[11.5px] text-accent-dark">
              {t('Скопируйте адрес вручную: выделите его и нажмите Ctrl+C.')}
            </p>
          )}
        </div>
      )}

      <div className="mt-4">
        <p className="text-[12px] font-semibold uppercase tracking-wide text-faint">{t('Активные ссылки')}</p>
        {sharesLoading ? (
          <p className="mt-1 text-[12.5px] text-muted">{t('Загружаем…')}</p>
        ) : sharesError ? (
          <div className="mt-1 text-[12.5px]">
            <p role="alert" className="text-badge-critical-text">{sharesError}</p>
            <button
              type="button"
              onClick={() => void loadShares()}
              className="mt-1 rounded px-1.5 py-0.5 font-medium text-accent hover:bg-accent-soft"
            >
              {t('Повторить')}
            </button>
          </div>
        ) : active.length === 0 ? (
          <p className="mt-1 text-[12.5px] text-muted">{t('Ссылок нет')}</p>
        ) : (
          <ul className="mt-1 divide-y divide-line-subtle">
            {active.map((share) => (
              <li key={share.id} className="flex flex-wrap items-center justify-between gap-2 py-2 text-[12.5px]">
                <span className="min-w-0 text-muted">
                  {formatDate(locale, share.createdAt)}
                  {share.expiresAt
                    ? ` · ${t('до')} ${formatDate(locale, share.expiresAt)}`
                    : ` · ${t('бессрочная')}`}
                </span>
                <button
                  type="button"
                  disabled={revoking === share.id}
                  onClick={() => void revoke(share.id)}
                  className="rounded-md px-2 py-1 text-[12.5px] font-medium text-danger hover:bg-badge-critical-bg disabled:opacity-60"
                >
                  {t('Отозвать')}
                </button>
              </li>
            ))}
          </ul>
        )}
        {revokeError && (
          <p role="alert" className="mt-2 rounded-lg bg-badge-critical-bg px-3 py-2 text-[12px] text-badge-critical-text">
            {revokeError}
          </p>
        )}
        <p className="mt-2 text-[11.5px] leading-relaxed text-faint">
          {t('После отзыва или истечения срока ссылка открывается как 404 — это нормально, доступ закрыт.')}
        </p>
      </div>

      <div className="mt-4 flex justify-end">
        <button
          type="button"
          onClick={onClose}
          className="h-9 rounded-lg border border-line-control px-3 text-[13px] text-ink hover:bg-line-subtle"
        >
          {t('Готово')}
        </button>
      </div>
    </div>
  );
}

function ConfirmDialog({
  title,
  text,
  confirmLabel,
  onConfirm,
  onClose,
}: {
  title: string;
  text: string;
  confirmLabel: string;
  onConfirm: () => Promise<string | null>;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  return (
    <div>
      <h2 className="text-[15px] font-semibold text-ink">{title}</h2>
      <p className="mt-2 text-[13px] text-muted">{text}</p>
      {error && (
        <p role="alert" className="mt-2 rounded-lg bg-badge-critical-bg px-3 py-2 text-[12px] text-badge-critical-text">
          {error}
        </p>
      )}
      <div className="mt-4 flex justify-end gap-2">
        <button
          type="button"
          onClick={onClose}
          className="h-9 rounded-lg border border-line-control px-3 text-[13px] text-ink hover:bg-line-subtle"
        >
          {t('Отмена')}
        </button>
        <button
          type="button"
          disabled={busy}
          onClick={async () => {
            if (busy) return;
            setBusy(true);
            setError('');
            try {
              const failure = await onConfirm();
              setError(failure ?? '');
            } finally {
              setBusy(false);
            }
          }}
          className="h-9 rounded-lg bg-danger px-3 text-[13px] font-medium text-white hover:opacity-90 disabled:opacity-60"
        >
          {confirmLabel}
        </button>
      </div>
    </div>
  );
}
