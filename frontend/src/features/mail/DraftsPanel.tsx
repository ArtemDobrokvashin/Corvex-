import { useEffect, useState } from 'react';
import { FilePenLine, Trash2, X } from 'lucide-react';
import { useI18n } from '../../i18n';
import { DraftSummary, mailApi } from '../../api/mailApi';

function formatBytes(locale: string, bytes: number): string {
  if (bytes <= 0) return '—';
  const units = locale === 'en' ? ['B', 'KB', 'MB'] : ['Б', 'КБ', 'МБ'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit += 1; }
  return `${new Intl.NumberFormat(locale, { maximumFractionDigits: value < 10 && unit > 0 ? 1 : 0 }).format(value)} ${units[unit]}`;
}

/**
 * Список серверных черновиков. Тела не запрашиваются: для списка достаточно
 * сводки, а полный черновик загружается только при открытии в композере.
 */
export default function DraftsPanel({ onOpen, onClose, onChanged }: {
  onOpen: (draftId: string) => void;
  onClose: () => void;
  onChanged?: () => void;
}) {
  const { t, locale } = useI18n();
  const [drafts, setDrafts] = useState<DraftSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const list = await mailApi.drafts();
        if (alive) setDrafts(list);
      } catch (failure) {
        if (alive) setError(failure instanceof Error ? failure.message : String(failure));
      }
    })();
    return () => { alive = false; };
  }, []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  const remove = async (draft: DraftSummary) => {
    if (busyId) return;
    setBusyId(draft.id);
    setError(null);
    try {
      await mailApi.deleteDraft(draft.id, draft.revision);
      setDrafts((prev) => (prev ?? []).filter((item) => item.id !== draft.id));
      onChanged?.();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center bg-ink/35 px-4 pt-[8vh] backdrop-blur-[2px]" onClick={onClose}>
      <section
        role="dialog"
        aria-modal="true"
        aria-label={t('Черновики')}
        className="panel flex max-h-[76vh] w-full max-w-2xl flex-col overflow-hidden shadow-pop"
        onClick={(event) => event.stopPropagation()}
      >
        <header className="flex items-center gap-2 border-b border-line px-4 py-3">
          <FilePenLine size={17} strokeWidth={1.8} className="text-muted" />
          <h2 className="flex-1 text-[15px] font-semibold">{t('Черновики')}</h2>
          <button type="button" onClick={onClose} className="icon-btn-sm" aria-label={t('Закрыть')}><X size={16} /></button>
        </header>

        {error && <p className="border-b border-line px-4 py-2 text-xs text-danger" role="alert">{error}</p>}

        <div className="scroll-area min-h-0 flex-1 overflow-y-auto">
          {drafts === null && !error && <p className="px-4 py-6 text-sm text-muted">{t('Загрузка…')}</p>}
          {drafts !== null && drafts.length === 0 && (
            <p className="px-4 py-8 text-center text-sm text-muted">{t('Сохранённых черновиков нет.')}</p>
          )}
          {drafts?.map((draft) => (
            <div key={draft.id} className="flex items-center gap-3 border-b border-line px-4 py-3 last:border-b-0">
              <button type="button" className="min-w-0 flex-1 text-left" onClick={() => onOpen(draft.id)}>
                <span className="block truncate text-sm font-medium text-ink">{draft.subject || t('(без темы)')}</span>
                <span className="mt-0.5 block truncate text-xs text-muted">
                  {draft.to || t('Получатель не указан')} · {formatBytes(locale, draft.sizeBytes)}
                  {draft.attachmentCount > 0 ? ` · ${draft.attachmentCount} ${t('влож.')}` : ''}
                </span>
              </button>
              <span className="shrink-0 text-[11px] text-faint">
                {new Intl.DateTimeFormat(locale, { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }).format(new Date(draft.updatedAt))}
              </span>
              <button
                type="button"
                onClick={() => void remove(draft)}
                disabled={busyId === draft.id}
                className="icon-btn-sm text-muted hover:text-danger"
                aria-label={`${t('Удалить черновик')}: ${draft.subject || t('(без темы)')}`}
              >
                <Trash2 size={15} />
              </button>
            </div>
          ))}
        </div>

        <footer className="border-t border-line px-4 py-2 text-[11px] text-faint">
          {t('Черновики хранятся на сервере и доступны только вам.')}
        </footer>
      </section>
    </div>
  );
}
