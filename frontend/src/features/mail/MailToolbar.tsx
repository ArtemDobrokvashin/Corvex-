import { Archive, ArrowLeft, ChevronDown, ChevronUp, Forward, Reply, Star, Trash2 } from 'lucide-react';
import { IconButton } from '../../components/ui';
import { useI18n } from '../../i18n';

export default function MailToolbar({ flagged, composing, index, total, onBack, onArchive, onDelete, onFlag, onReply, onForward, onStep }: {
  flagged: boolean; composing: boolean; index: number; total: number;
  onBack: () => void; onArchive: () => void; onDelete: () => void; onFlag: () => void;
  onReply: () => void; onForward: () => void; onStep: (direction: -1 | 1) => void;
}) {
  const { t } = useI18n();
  return <header className="flex min-h-14 shrink-0 flex-wrap items-center gap-0.5 border-b border-line px-2 py-1 sm:px-3">
    <IconButton icon={ArrowLeft} label={t('К списку')} className="md:hidden" onClick={onBack} />
    <IconButton icon={Reply} label={t('Ответить')} onClick={onReply} disabled={composing} />
    <IconButton icon={Forward} label={t('Переслать')} onClick={onForward} disabled={composing} />
    <IconButton icon={Archive} label={t('В архив')} onClick={onArchive} />
    <IconButton icon={Trash2} label={t('Удалить')} onClick={onDelete} />
    <button type="button" onClick={onFlag} className={`icon-btn ${flagged ? 'text-amber-500 hover:text-amber-600' : ''}`} aria-label={t('Важное')} aria-pressed={flagged} title={t('Важное')}>
      <Star size={18} fill={flagged ? 'currentColor' : 'none'} />
    </button>
    <div className="ml-auto hidden items-center gap-1 sm:flex">
      <span className="mr-1 hidden text-xs text-faint xl:inline">{t('{current} из {total}', { current: Math.max(index + 1, 1), total })}</span>
      <IconButton icon={ChevronUp} label={t('Предыдущее письмо')} onClick={() => onStep(-1)} disabled={index <= 0} />
      <IconButton icon={ChevronDown} label={t('Следующее письмо')} onClick={() => onStep(1)} disabled={index < 0 || index >= total - 1} />
    </div>
  </header>;
}
