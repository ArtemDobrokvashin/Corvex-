import { useEffect, useRef } from 'react';
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
  return <label className="inline-flex items-center gap-2 text-sm text-muted">
    <input ref={ref} type="checkbox" className="h-4 w-4 accent-[#3157d5]" checked={visibleCount > 0 && selectedCount === visibleCount}
      disabled={disabled || visibleCount === 0} onChange={onChange} aria-label={t('Выбрать все показанные письма')} />
    <span>{t('Показанные письма')}</span>
  </label>;
}

export function SelectMessage({ subject, checked, disabled, onChange }: {
  subject: string; checked: boolean; disabled?: boolean; onChange: () => void;
}) {
  const { t } = useI18n();
  return <input type="checkbox" className="h-4 w-4 shrink-0 accent-[#3157d5]" checked={checked} disabled={disabled}
    aria-label={t('Выбрать письмо: {subject}', { subject })} onClick={(event) => event.stopPropagation()} onChange={onChange} />;
}
