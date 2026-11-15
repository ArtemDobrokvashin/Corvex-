import { useEffect } from 'react';
import { useI18n } from '../../i18n';
import type { CalendarEventItem } from '../../store/calendarStore';

/** Deleting a recurring occurrence removes the entire locally stored series. */
export default function ConfirmEventDelete({
  event,
  onCancel,
  onConfirm,
}: {
  event: CalendarEventItem;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const { t } = useI18n();

  useEffect(() => {
    const onKeyDown = (keyboardEvent: KeyboardEvent) => {
      if (keyboardEvent.key === 'Escape') onCancel();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onCancel]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 p-4 backdrop-blur-[1px]" onClick={onCancel}>
      <div
        role="alertdialog"
        aria-modal="true"
        aria-label={t('Удалить событие')}
        aria-describedby="delete-event-description"
        className="w-full max-w-sm rounded-xl border border-line-control bg-surface p-5 shadow-pop"
        onClick={(clickEvent) => clickEvent.stopPropagation()}
      >
        <h2 className="text-base font-semibold text-ink">{t('Удалить событие')}</h2>
        <p id="delete-event-description" className="mt-2 text-sm text-ink-soft">
          {event.recurrence === 'none' ? t('Удалить это событие?') : t('Удалить всю серию повторяющихся событий?')}
        </p>
        <p className="mt-2 break-words text-sm font-medium text-ink">{event.title}</p>
        <div className="mt-5 flex justify-end gap-2">
          <button type="button" autoFocus className="btn-secondary" onClick={onCancel}>{t('Отмена')}</button>
          <button type="button" className="btn-danger-ghost border border-red-200" onClick={onConfirm}>{t('Удалить')}</button>
        </div>
      </div>
    </div>
  );
}
