import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import {
  ChevronRight,
  Download,
  File as FileIcon,
  Folder,
  FolderInput,
  FolderPlus,
  Link2,
  Loader2,
  Pencil,
  RotateCcw,
  Search,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import SidebarSlot from '../../components/SidebarSlot';
import { useI18n } from '../../i18n';
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

function errorText(error: unknown): string {
  return error instanceof Error && error.message ? error.message : String(error);
}

export default function FilesPage() {
  const { t, locale } = useI18n();
  const [view, setView] = useState<DriveView>('files');
  const [parentId, setParentId] = useState<string | null>(null);
  const [trail, setTrail] = useState<{ id: string | null; name: string }[]>([]);
  const [nodes, setNodes] = useState<DriveNode[]>([]);
  const [usage, setUsage] = useState<DriveUsage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [query, setQuery] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const [uploading, setUploading] = useState(0);
  const [dragging, setDragging] = useState(false);
  const [dialog, setDialog] = useState<Dialog>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedQuery(query), 250);
    return () => clearTimeout(timer);
  }, [query]);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      const [items, currentUsage] = await Promise.all([
        driveApi.list(parentId, view, debouncedQuery),
        driveApi.usage(),
      ]);
      setNodes(items);
      setUsage(currentUsage);
      setError('');
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [parentId, view, debouncedQuery]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const run = async (action: () => Promise<unknown>, message?: string) => {
    setError('');
    setNotice('');
    try {
      await action();
      if (message) setNotice(message);
      await reload();
      return true;
    } catch (e) {
      setError(errorText(e));
      return false;
    }
  };

  const uploadFiles = async (files: FileList | File[]) => {
    const list = Array.from(files);
    if (list.length === 0) return;
    setUploading(list.length);
    setError('');
    try {
      for (const file of list) {
        await driveApi.upload(parentId, file);
      }
      setNotice(t('Файлы загружены'));
      await reload();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setUploading(0);
    }
  };

  const openFolder = (folder: DriveNode) => {
    setTrail((current) => [...current, { id: folder.id, name: folder.name }]);
    setParentId(folder.id);
    setQuery('');
  };

  const goTo = (index: number) => {
    const next = trail.slice(0, index);
    setTrail(next);
    setParentId(next.length ? next[next.length - 1].id : null);
    setQuery('');
  };

  const switchView = (next: DriveView) => {
    setView(next);
    setTrail([]);
    setParentId(null);
    setQuery('');
  };

  const download = async (node: DriveNode) => {
    await run(async () => {
      const blob = await driveApi.download(node.id);
      await saveBlob(blob, node.name);
    });
  };

  const usedPercent = usage && usage.quotaBytes > 0
    ? Math.min(100, Math.round((usage.usedBytes / usage.quotaBytes) * 100))
    : 0;

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
              title={t('Мои файлы')}
              className={`sidebar-item w-full ${view === 'files' && !parentId ? 'sidebar-item-active' : ''}`}
            >
              <Folder size={16} strokeWidth={1.8} className="shrink-0" />
              <span className={`truncate ${collapsed ? 'md:hidden' : ''}`}>{t('Мои файлы')}</span>
            </button>
            <button
              type="button"
              onClick={() => switchView('trash')}
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
                  <span className="tabular-nums">{usedPercent}%</span>
                </div>
                <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-white/10">
                  <div
                    className="h-full rounded-full bg-accent"
                    style={{ width: `${usedPercent}%` }}
                    aria-hidden="true"
                  />
                </div>
                <p className="mt-1.5 text-[11px] text-sidebar-muted tabular-nums">
                  {formatBytes(usage.usedBytes, locale)} / {formatBytes(usage.quotaBytes, locale)}
                </p>
              </div>
            )}
          </div>
        )}
      </SidebarSlot>

      <section className="flex min-h-0 flex-1 flex-col bg-canvas">
        <header className="flex flex-wrap items-center gap-2 border-b border-line bg-chrome px-4 py-3 sm:px-6">
          <nav aria-label={t('Путь')} className="flex min-w-0 flex-1 items-center gap-1 text-[13px]">
            <button
              type="button"
              onClick={() => switchView(view)}
              className="truncate rounded px-1.5 py-1 font-medium text-ink hover:bg-line-subtle"
            >
              {view === 'trash' ? t('Корзина') : t('Мои файлы')}
            </button>
            {trail.map((crumb, index) => (
              <span key={crumb.id ?? 'root'} className="flex min-w-0 items-center gap-1">
                <ChevronRight size={14} className="shrink-0 text-faint" aria-hidden="true" />
                <button
                  type="button"
                  onClick={() => goTo(index + 1)}
                  className="truncate rounded px-1.5 py-1 text-muted hover:bg-line-subtle hover:text-ink"
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
              className="h-9 w-full rounded-lg border border-line-control bg-surface pl-8 pr-8 text-[13px] text-ink placeholder:text-faint focus:border-accent focus:outline-none"
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

          {view === 'files' && (
            <>
              <button
                type="button"
                onClick={() => setDialog({ kind: 'folder' })}
                className="inline-flex h-9 items-center gap-1.5 rounded-lg border border-line-control bg-surface px-3 text-[13px] font-medium text-ink hover:border-accent hover:text-accent"
              >
                <FolderPlus size={16} strokeWidth={1.8} />
                <span className="hidden sm:inline">{t('Новая папка')}</span>
              </button>
              <button
                type="button"
                onClick={() => fileInput.current?.click()}
                disabled={uploading > 0}
                className="inline-flex h-9 items-center gap-1.5 rounded-lg bg-accent px-3 text-[13px] font-medium text-white hover:bg-accent-dark disabled:opacity-60"
              >
                {uploading > 0 ? <Loader2 size={16} className="animate-spin" /> : <Upload size={16} strokeWidth={1.8} />}
                {uploading > 0 ? t('Загрузка…') : t('Загрузить')}
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
            </>
          )}
        </header>

        {(error || notice) && (
          <div className="px-4 pt-3 sm:px-6">
            {error && (
              <p role="alert" className="rounded-lg border border-badge-critical-bg bg-badge-critical-bg px-3 py-2 text-[13px] text-badge-critical-text">
                {error}
              </p>
            )}
            {notice && (
              <p className="rounded-lg border border-badge-positive-bg bg-badge-positive-bg px-3 py-2 text-[13px] text-badge-positive-text">
                {notice}
              </p>
            )}
          </div>
        )}

        <div
          className={`min-h-0 flex-1 overflow-y-auto px-2 py-3 sm:px-4 ${dragging ? 'bg-accent-soft' : ''}`}
          onDragOver={(event) => {
            if (view !== 'files') return;
            event.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(event) => {
            event.preventDefault();
            setDragging(false);
            if (view === 'files' && event.dataTransfer.files.length) {
              void uploadFiles(event.dataTransfer.files);
            }
          }}
        >
          {loading ? (
            <p className="px-3 py-6 text-[13px] text-muted">{t('Загружаем файлы…')}</p>
          ) : nodes.length === 0 ? (
            <div className="mx-auto mt-10 max-w-md rounded-xl border border-dashed border-line-control bg-surface px-6 py-10 text-center">
              <Folder size={28} strokeWidth={1.5} className="mx-auto text-faint" aria-hidden="true" />
              <p className="mt-3 text-[15px] font-medium text-ink">
                {view === 'trash' ? t('Корзина пуста') : t('Здесь пока нет файлов')}
              </p>
              <p className="mt-1 text-[13px] text-muted">
                {view === 'trash'
                  ? t('Удалённые файлы хранятся здесь, пока вы не очистите корзину')
                  : t('Перетащите файлы сюда или нажмите «Загрузить»')}
              </p>
            </div>
          ) : (
            <ul className="space-y-0.5" aria-label={t('Содержимое папки')}>
              {nodes.map((node) => (
                <li key={node.id}>
                  <div className="group flex items-center gap-3 rounded-lg px-2 py-2 hover:bg-surface">
                    <button
                      type="button"
                      onClick={() => (node.kind === 'FOLDER' ? openFolder(node) : void download(node))}
                      className="flex min-w-0 flex-1 items-center gap-3 text-left"
                    >
                      {node.kind === 'FOLDER' ? (
                        <Folder size={18} strokeWidth={1.7} className="shrink-0 text-accent" aria-hidden="true" />
                      ) : (
                        <FileIcon size={18} strokeWidth={1.7} className="shrink-0 text-faint" aria-hidden="true" />
                      )}
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-[13.5px] font-medium text-ink">{node.name}</span>
                        <span className="block text-[11.5px] text-faint">
                          {node.kind === 'FOLDER'
                            ? t('Папка')
                            : `${formatBytes(node.sizeBytes, locale)} · ${new Date(node.updatedAt).toLocaleDateString(locale === 'en' ? 'en-GB' : 'ru-RU')}`}
                        </span>
                      </span>
                    </button>

                    <div className="flex shrink-0 items-center gap-0.5 opacity-100 sm:opacity-0 sm:group-hover:opacity-100 sm:group-focus-within:opacity-100">
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
                            onClick={() => void run(() => driveApi.trash(node.id), t('Файл перемещён в корзину'))}
                          >
                            <Trash2 size={15} />
                          </IconButton>
                        </>
                      )}
                      {view === 'trash' && (
                        <>
                          <IconButton
                            label={t('Восстановить')}
                            onClick={() => void run(() => driveApi.restore(node.id), t('Файл восстановлен'))}
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
              ))}
            </ul>
          )}

          {view === 'files' && dragging && (
            <p className="pointer-events-none fixed inset-x-8 bottom-8 rounded-xl border border-accent bg-surface px-4 py-3 text-center text-[13px] text-accent shadow-lg">
              {t('Отпустите файлы, чтобы загрузить')}
            </p>
          )}
        </div>
      </section>

      {dialog && (
        <DialogShell onClose={() => setDialog(null)}>
          {dialog.kind === 'folder' && (
            <NameDialog
              title={t('Новая папка')}
              submitLabel={t('Создать')}
              initial=""
              onClose={() => setDialog(null)}
              onSubmit={async (name) => {
                if (await run(() => driveApi.createFolder(parentId, name), t('Папка создана'))) {
                  setDialog(null);
                }
              }}
            />
          )}

          {dialog.kind === 'rename' && (
            <NameDialog
              title={t('Переименовать')}
              submitLabel={t('Сохранить')}
              initial={dialog.node.name}
              onClose={() => setDialog(null)}
              onSubmit={async (name) => {
                if (await run(() => driveApi.rename(dialog.node.id, name), t('Имя изменено'))) {
                  setDialog(null);
                }
              }}
            />
          )}

          {dialog.kind === 'move' && (
            <MoveDialog
              node={dialog.node}
              onClose={() => setDialog(null)}
              onMoved={async (targetId) => {
                if (await run(() => driveApi.move(dialog.node.id, targetId), t('Файл перемещён'))) {
                  setDialog(null);
                }
              }}
            />
          )}

          {dialog.kind === 'share' && (
            <ShareDialog node={dialog.node} onClose={() => setDialog(null)} onChanged={reload} />
          )}

          {dialog.kind === 'purge' && (
            <ConfirmDialog
              title={t('Удалить навсегда?')}
              text={t('Файл будет удалён без возможности восстановления, выданные ссылки перестанут работать.')}
              confirmLabel={t('Удалить навсегда')}
              onClose={() => setDialog(null)}
              onConfirm={async () => {
                if (await run(() => driveApi.purge(dialog.node.id), t('Удалено навсегда'))) {
                  setDialog(null);
                }
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
      className={`inline-flex h-8 w-8 items-center justify-center rounded-md text-muted transition hover:bg-line-subtle ${
        danger ? 'hover:text-danger' : 'hover:text-ink'
      }`}
    >
      {children}
    </button>
  );
}

function DialogShell({ children, onClose }: { children: ReactNode; onClose: () => void }) {
  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 p-4" role="presentation" onMouseDown={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        className="w-full max-w-md rounded-xl border border-line bg-surface p-5 shadow-xl"
        onMouseDown={(event) => event.stopPropagation()}
      >
        {children}
      </div>
    </div>
  );
}

function NameDialog({
  title,
  initial,
  submitLabel,
  onSubmit,
  onClose,
}: {
  title: string;
  initial: string;
  submitLabel: string;
  onSubmit: (name: string) => Promise<void>;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const [name, setName] = useState(initial);
  const [busy, setBusy] = useState(false);

  return (
    <form
      onSubmit={async (event) => {
        event.preventDefault();
        if (!name.trim() || busy) return;
        setBusy(true);
        try {
          await onSubmit(name.trim());
        } finally {
          setBusy(false);
        }
      }}
    >
      <h2 className="text-[15px] font-semibold text-ink">{title}</h2>
      <input
        autoFocus
        value={name}
        maxLength={200}
        onChange={(event) => setName(event.target.value)}
        aria-label={title}
        className="mt-3 h-10 w-full rounded-lg border border-line-control px-3 text-[13.5px] text-ink focus:border-accent focus:outline-none"
      />
      <div className="mt-4 flex justify-end gap-2">
        <button type="button" onClick={onClose} className="h-9 rounded-lg border border-line-control px-3 text-[13px] text-ink hover:bg-line-subtle">
          {t('Отмена')}
        </button>
        <button
          type="submit"
          disabled={!name.trim() || busy}
          className="h-9 rounded-lg bg-accent px-3 text-[13px] font-medium text-white hover:bg-accent-dark disabled:opacity-60"
        >
          {submitLabel}
        </button>
      </div>
    </form>
  );
}

function MoveDialog({
  node,
  onMoved,
  onClose,
}: {
  node: DriveNode;
  onMoved: (targetId: string | null) => Promise<void>;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const [folders, setFolders] = useState<DriveNode[]>([]);
  const [target, setTarget] = useState<string>('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    driveApi
      .list(null, 'files')
      .then((items) => setFolders(items.filter((item) => item.kind === 'FOLDER' && item.id !== node.id)))
      .catch(() => setFolders([]));
  }, [node.id]);

  return (
    <div>
      <h2 className="text-[15px] font-semibold text-ink">{t('Переместить')}</h2>
      <p className="mt-1 text-[12.5px] text-muted">{node.name}</p>
      <label className="mt-3 block text-[12.5px] font-medium text-ink" htmlFor="move-target">
        {t('Папка назначения')}
      </label>
      <select
        id="move-target"
        value={target}
        onChange={(event) => setTarget(event.target.value)}
        className="mt-1 h-10 w-full rounded-lg border border-line-control bg-surface px-2 text-[13.5px] text-ink focus:border-accent focus:outline-none"
      >
        <option value="">{t('Корень диска')}</option>
        {folders.map((folder) => (
          <option key={folder.id} value={folder.id}>
            {folder.name}
          </option>
        ))}
      </select>
      <div className="mt-4 flex justify-end gap-2">
        <button type="button" onClick={onClose} className="h-9 rounded-lg border border-line-control px-3 text-[13px] text-ink hover:bg-line-subtle">
          {t('Отмена')}
        </button>
        <button
          type="button"
          disabled={busy}
          onClick={async () => {
            setBusy(true);
            try {
              await onMoved(target || null);
            } finally {
              setBusy(false);
            }
          }}
          className="h-9 rounded-lg bg-accent px-3 text-[13px] font-medium text-white hover:bg-accent-dark disabled:opacity-60"
        >
          {t('Переместить')}
        </button>
      </div>
    </div>
  );
}

function ShareDialog({
  node,
  onChanged,
  onClose,
}: {
  node: DriveNode;
  onChanged: () => Promise<void>;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const [shares, setShares] = useState<DriveShare[]>([]);
  const [created, setCreated] = useState<string | null>(null);
  const [expires, setExpires] = useState<string>('');
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);

  const load = useCallback(async () => {
    try {
      setShares(await driveApi.shares(node.id));
    } catch {
      setShares([]);
    }
  }, [node.id]);

  useEffect(() => {
    void load();
  }, [load]);

  const active = shares.filter((share) => !share.revokedAt && (!share.expiresAt || new Date(share.expiresAt) > new Date()));

  return (
    <div>
      <h2 className="text-[15px] font-semibold text-ink">{t('Ссылка на файл')}</h2>
      <p className="mt-1 text-[12.5px] text-muted">
        {t('Пока ссылка не создана, файл видите только вы. Ссылка даёт просмотр и скачивание любому, у кого она есть.')}
      </p>

      <div className="mt-3 flex items-end gap-2">
        <label className="flex-1 text-[12.5px] font-medium text-ink">
          {t('Срок действия')}
          <select
            value={expires}
            onChange={(event) => setExpires(event.target.value)}
            className="mt-1 h-9 w-full rounded-lg border border-line-control bg-surface px-2 text-[13px] font-normal text-ink focus:border-accent focus:outline-none"
          >
            <option value="">{t('Без ограничения')}</option>
            <option value="7">{t('7 дней')}</option>
            <option value="30">{t('30 дней')}</option>
          </select>
        </label>
        <button
          type="button"
          disabled={busy}
          onClick={async () => {
            setBusy(true);
            setCopied(false);
            try {
              const result = await driveApi.createShare(node.id, expires ? Number(expires) : null);
              setCreated(absoluteShareUrl(result.path));
              await load();
              await onChanged();
            } finally {
              setBusy(false);
            }
          }}
          className="inline-flex h-9 items-center gap-1.5 rounded-lg bg-accent px-3 text-[13px] font-medium text-white hover:bg-accent-dark disabled:opacity-60"
        >
          <Link2 size={15} />
          {t('Создать ссылку')}
        </button>
      </div>

      {created && (
        <div className="mt-3 rounded-lg border border-accent-muted bg-accent-soft p-3">
          <p className="text-[12px] font-medium text-accent-dark">{t('Ссылка создана. Скопируйте её сейчас — позже токен не показывается.')}</p>
          <div className="mt-2 flex gap-2">
            <input
              readOnly
              value={created}
              aria-label={t('Ссылка на файл')}
              onFocus={(event) => event.currentTarget.select()}
              className="h-9 min-w-0 flex-1 rounded-lg border border-line-control bg-surface px-2 text-[12px] text-ink"
            />
            <button
              type="button"
              onClick={async () => {
                try {
                  await navigator.clipboard.writeText(created);
                  setCopied(true);
                } catch {
                  setCopied(false);
                }
              }}
              className="h-9 shrink-0 rounded-lg border border-line-control bg-surface px-3 text-[12.5px] text-ink hover:border-accent hover:text-accent"
            >
              {copied ? t('Скопировано') : t('Копировать')}
            </button>
          </div>
        </div>
      )}

      <div className="mt-4">
        <p className="text-[12px] font-semibold uppercase tracking-wide text-faint">{t('Активные ссылки')}</p>
        {active.length === 0 ? (
          <p className="mt-1 text-[12.5px] text-muted">{t('Ссылок нет')}</p>
        ) : (
          <ul className="mt-1 divide-y divide-line-subtle">
            {active.map((share) => (
              <li key={share.id} className="flex items-center justify-between gap-2 py-2 text-[12.5px]">
                <span className="text-muted">
                  {new Date(share.createdAt).toLocaleDateString()}
                  {share.expiresAt ? ` · ${t('до')} ${new Date(share.expiresAt).toLocaleDateString()}` : ` · ${t('бессрочная')}`}
                </span>
                <button
                  type="button"
                  onClick={async () => {
                    await driveApi.revokeShare(share.id);
                    await load();
                    await onChanged();
                  }}
                  className="rounded-md px-2 py-1 text-[12.5px] font-medium text-danger hover:bg-badge-critical-bg"
                >
                  {t('Отозвать')}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="mt-4 flex justify-end">
        <button type="button" onClick={onClose} className="h-9 rounded-lg border border-line-control px-3 text-[13px] text-ink hover:bg-line-subtle">
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
  onConfirm: () => Promise<void>;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const [busy, setBusy] = useState(false);
  return (
    <div>
      <h2 className="text-[15px] font-semibold text-ink">{title}</h2>
      <p className="mt-2 text-[13px] text-muted">{text}</p>
      <div className="mt-4 flex justify-end gap-2">
        <button type="button" onClick={onClose} className="h-9 rounded-lg border border-line-control px-3 text-[13px] text-ink hover:bg-line-subtle">
          {t('Отмена')}
        </button>
        <button
          type="button"
          disabled={busy}
          onClick={async () => {
            setBusy(true);
            try {
              await onConfirm();
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
