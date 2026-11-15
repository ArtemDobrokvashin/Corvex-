import { type ReactNode } from 'react';
import { SendHorizontal, X } from 'lucide-react';
import { useI18n } from '../i18n';

/** A thread stays beside the timeline; on narrower screens it becomes an overlay. */
export default function ChatThreadPanel({
  root,
  children,
  draft,
  onDraftChange,
  onSend,
  onClose,
  canWrite = true,
}: {
  root: ReactNode;
  children: ReactNode;
  draft: string;
  onDraftChange: (value: string) => void;
  onSend: () => void;
  onClose: () => void;
  canWrite?: boolean;
}) {
  const { t } = useI18n();
  return (
    <aside aria-label={t('Ветка обсуждения')} className="absolute inset-0 z-30 flex min-h-0 w-full flex-col border-l border-line bg-surface xl:static xl:w-[320px] xl:shrink-0">
      <header className="flex h-14 shrink-0 items-center border-b border-line px-4">
        <h2 className="flex-1 text-sm font-semibold text-ink">{t('Ветка обсуждения')}</h2>
        <button type="button" className="icon-btn-sm" onClick={onClose} aria-label={t('Закрыть ветку')}><X size={17} /></button>
      </header>
      <div className="scroll-area min-h-0 flex-1 px-4 py-4">
        <div className="border-b border-line pb-4">{root}</div>
        <div className="space-y-4 pt-4">{children}</div>
      </div>
      <form className="shrink-0 border-t border-line p-3" onSubmit={(event) => { event.preventDefault(); if (canWrite && draft.trim()) onSend(); }}>
        <div className="rounded-lg border border-line-control bg-surface focus-within:border-accent">
          <textarea
            aria-label={t('Ответ в ветке')}
            placeholder={t('Ответ в ветке…')}
            value={draft}
            onChange={(event) => onDraftChange(event.target.value)}
            disabled={!canWrite}
            maxLength={32000}
            rows={2}
            className="w-full resize-none border-0 bg-transparent px-3 pt-2 text-sm text-ink outline-none placeholder:text-faint focus:ring-0"
            onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); if (canWrite && draft.trim()) onSend(); } }}
          />
          <div className="flex justify-end px-2 pb-2">
            <button type="submit" disabled={!canWrite || !draft.trim()} className="btn-primary h-8 px-2.5" aria-label={t('Отправить ответ')}><SendHorizontal size={15} /></button>
          </div>
        </div>
      </form>
    </aside>
  );
}
