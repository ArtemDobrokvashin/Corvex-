import { Archive, FilePenLine, Folder, Inbox, OctagonAlert, Send, Trash2 } from 'lucide-react';
import { useI18n } from '../../i18n';

export interface FolderListItem { id: string; name: string }
const ICONS: Record<string, typeof Inbox> = {
  INBOX: Inbox, SENT: Send, DRAFTS: FilePenLine, ARCHIVE: Archive, SPAM: OctagonAlert, TRASH: Trash2,
};

/** A bounded vertical folder navigation at every viewport size. */
export default function FolderList({ folders, selected, counts, onSelect, onDelete, deleting, compact = false }: {
  folders: FolderListItem[];
  selected: string;
  counts: Record<string, number>;
  onSelect: (id: string) => void;
  onDelete?: (folder: FolderListItem) => void;
  deleting?: boolean;
  compact?: boolean;
}) {
  const { t } = useI18n();
  return (
    <nav aria-label={t('Папки почты')} className={compact
      ? 'scroll-area mt-3 max-h-[min(26vh,12rem)] min-w-0 space-y-0.5 overflow-x-hidden pr-1 xl:hidden'
      : 'scroll-area min-h-0 min-w-0 flex-1 space-y-0.5 overflow-x-hidden pr-1'}>
      {folders.map((folder) => {
        const Icon = ICONS[folder.id] ?? Folder;
        const count = counts[folder.id] ?? 0;
        const active = folder.id === selected;
        return (
          <div key={folder.id} className="flex min-w-0 items-center">
          <button type="button" onClick={() => onSelect(folder.id)}
            aria-current={active ? 'page' : undefined} title={folder.name}
            className={`nav-row min-w-0 flex-1 ${active ? 'nav-row-active' : ''}`}>
            <Icon size={16} strokeWidth={1.8} className="shrink-0" aria-hidden="true" />
            <span className="min-w-0 flex-1 truncate">{folder.name}</span>
            {count > 0 && <span className={`shrink-0 text-xs ${active ? 'font-semibold text-accent' : 'font-medium text-faint'}`}>{count}</span>}
          </button>
          {onDelete && !ICONS[folder.id] && <button type="button" className="icon-btn shrink-0" disabled={deleting}
            aria-label={t('Удалить папку {name}', { name: folder.name })} title={t('Удалить папку {name}', { name: folder.name })}
            onClick={() => onDelete(folder)}><Trash2 size={16} aria-hidden="true" /></button>}
          </div>
        );
      })}
    </nav>
  );
}
