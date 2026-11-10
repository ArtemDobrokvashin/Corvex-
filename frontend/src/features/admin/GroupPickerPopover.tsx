import { useEffect, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useI18n } from '../../i18n';

/** Поповер вынесен за прокручиваемую таблицу, поэтому не раздвигает её строки. */
export default function GroupPickerPopover({ anchor, children, onSave, onClose }: {
  anchor: DOMRect;
  children: ReactNode;
  onSave: () => void;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const key = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', key);
    window.addEventListener('resize', onClose);
    const scroll = (event: Event) => { if (!(event.target instanceof Node) || !panel.current?.contains(event.target)) onClose(); };
    window.addEventListener('scroll', scroll, true);
    return () => { window.removeEventListener('keydown', key); window.removeEventListener('resize', onClose); window.removeEventListener('scroll', scroll, true); };
  }, [onClose]);
  const width = Math.min(300, window.innerWidth - 24);
  const left = Math.max(12, Math.min(anchor.right - width, window.innerWidth - width - 12));
  const top = Math.max(12, Math.min(anchor.bottom + 6, window.innerHeight - 200));
  return createPortal(<>
    <div className="fixed inset-0 z-[70]" onClick={onClose} aria-hidden="true" />
    <div ref={panel} role="dialog" aria-label={t('Группы пользователя')} className="fixed z-[71] rounded-xl border border-line bg-surface p-3 shadow-pop"
      style={{ left, top, width, maxHeight: `calc(100vh - ${top + 12}px)`, overflowY: 'auto' }} onScroll={(event) => event.stopPropagation()}>
      <p className="mb-2 text-xs font-medium text-muted">{t('Группы пользователя')}</p>
      {children}
      <div className="mt-3 flex gap-1.5">
        <button type="button" onClick={onSave} className="btn-primary h-8 px-3 text-xs">{t('Сохранить')}</button>
        <button type="button" onClick={onClose} className="btn-ghost h-8 px-3 text-xs">{t('Отмена')}</button>
      </div>
    </div>
  </>, document.body);
}
