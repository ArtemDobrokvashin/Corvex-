import { useEffect, useRef } from 'react';
import { Archive, ChevronDown, FolderInput, Forward, Trash2, X } from 'lucide-react';
import { useI18n } from '../../i18n';

/** Controlled selection for the loaded, visible page only. No action is performed by this component. */
export function SelectVisibleMessages({ visibleCount, selectedCount, disabled, onChange }: {
  visibleCount: number; selectedCount: number; disabled?: boolean; onChange: () => void;
}) {
  const { t } = useI18n();
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = selectedCount > 0 && selectedCount < visibleCount;
  }, [selectedCount, visibleCount]);
  return <label className={`inline-flex max-w-full shrink-0 items-center gap-2 text-[13px] font-medium text-muted ${disabled || visibleCount === 0 ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'}`}>
    <input ref={ref} type="checkbox" className="h-4 w-4 shrink-0 accent-[#3157d5]" checked={visibleCount > 0 && selectedCount === visibleCount}
      disabled={disabled || visibleCount === 0} onChange={onChange} aria-label={t('Выбрать все показанные письма')} />
    <span className="min-w-0">{t('Показанные письма')}</span>
  </label>;
}

export function SelectMessage({ subject, checked, disabled, onChange }: {
  subject: string; checked: boolean; disabled?: boolean; onChange: () => void;
}) {
  const { t } = useI18n();
  return <input type="checkbox" className="h-4 w-4 shrink-0 accent-[#3157d5]" checked={checked} disabled={disabled}
    aria-label={t('Выбрать письмо: {subject}', { subject })} onClick={(event) => event.stopPropagation()} onChange={onChange} />;
}

/** Moving to another folder is separate from forwarding to another recipient. */
export function BulkActionBar({ count, folders, currentFolder, busy, onMove, onForward, onClear }: {
  count: number;
  folders: readonly { id: string; name: string }[];
  currentFolder: string;
  busy: boolean;
  onMove: (folderId: string) => void;
  onForward: () => void;
  onClear: () => void;
}) {
  const { t } = useI18n();
  const unavailableReason = (id: string) => currentFolder === id ? t('Письма уже в этой папке')
    : !folders.some((folder) => folder.id === id) ? t('Папка недоступна') : undefined;
  const archiveReason = unavailableReason('ARCHIVE');
  const trashReason = unavailableReason('TRASH');
  // Archive and Trash have dedicated buttons, so do not duplicate them in the menu.
  const otherFolders = folders.filter((folder) => (
    folder.id !== currentFolder && folder.id !== 'ARCHIVE' && folder.id !== 'TRASH'
  ));
  const moveLabel = t('Переместить выбранные письма');
  const moveReason = otherFolders.length === 0 ? t('Нет других доступных папок') : undefined;

  return (
    <div className="shrink-0 border-b border-corvex-line bg-accent-soft px-4 py-2.5" role="group" aria-label={t('Групповые действия')} aria-busy={busy}>
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <span className="text-xs font-semibold text-ink">{t('Выбрано: {count}', { count })}</span>
        <button type="button" disabled={busy} onClick={onClear} className="inline-flex shrink-0 items-center gap-1 rounded-md text-xs font-medium text-accent-dark hover:underline disabled:cursor-not-allowed disabled:opacity-50">
          <X size={13} aria-hidden="true" /> {t('Снять выбор')}
        </button>
      </div>
      <div className="mt-2 flex flex-wrap gap-2">
        <button type="button" className="mail-bulk-action mail-bulk-cell" disabled={busy || Boolean(archiveReason)}
          title={archiveReason} aria-label={archiveReason ? `${t('В архив')}. ${archiveReason}` : undefined} onClick={() => onMove('ARCHIVE')}>
          <Archive size={15} className="shrink-0" aria-hidden="true" /> <span className="truncate">{t('В архив')}</span>
        </button>
        <button type="button" className="mail-bulk-action mail-bulk-cell" disabled={busy || Boolean(trashReason)}
          title={trashReason} aria-label={trashReason ? `${t('В корзину')}. ${trashReason}` : undefined} onClick={() => onMove('TRASH')}>
          <Trash2 size={15} className="shrink-0" aria-hidden="true" /> <span className="truncate">{t('В корзину')}</span>
        </button>
        <div className="mail-bulk-cell relative">
          <FolderInput size={15} className="pointer-events-none absolute left-3 top-1/2 z-10 -translate-y-1/2 text-muted" aria-hidden="true" />
          <select
            aria-label={moveReason ? `${moveLabel}. ${moveReason}` : moveLabel}
            title={moveReason ?? moveLabel}
            disabled={busy || Boolean(moveReason)}
            value=""
            className="mail-bulk-action appearance-none pl-9 pr-8 text-left"
            onChange={(event) => { if (event.target.value) onMove(event.target.value); }}
          >
            <option value="" disabled>{t('В папку')}</option>
            {otherFolders.map((folder) => <option key={folder.id} value={folder.id}>{folder.name}</option>)}
          </select>
          <ChevronDown size={14} className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-muted" aria-hidden="true" />
        </div>
        <button type="button" className="mail-bulk-action mail-bulk-cell" disabled={busy} aria-label={t('Переслать выбранные')} title={t('Переслать выбранные')} onClick={onForward}>
          <Forward size={15} className="shrink-0" aria-hidden="true" /> <span className="truncate">{t('Переслать')}</span>
        </button>
      </div>
    </div>
  );
}
