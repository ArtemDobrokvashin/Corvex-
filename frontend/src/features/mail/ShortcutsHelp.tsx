import { useEffect, useRef } from 'react';
import { X } from 'lucide-react';
import { useModalFocus } from '../../components/useModalFocus';
import { useI18n } from '../../i18n';

/**
 * Справка по клавишам. Открывается по `?` и из меню «Ещё действия».
 *
 * Зачем окно, а не список где-то в настройках: `?` — привычный вход, и человек
 * ждёт ответа сразу. Набор клавиш продублирован здесь целиком: подсказка в пустой
 * правой панели показывает только четыре из них.
 */
const GROUPS: { title: string; items: { keys: string[]; label: string }[] }[] = [
  {
    title: 'Навигация',
    items: [
      { keys: ['J'], label: 'Следующее письмо' },
      { keys: ['K'], label: 'Предыдущее письмо' },
      { keys: ['O', 'Enter'], label: 'Открыть письмо' },
      { keys: ['U'], label: 'Вернуться к списку' },
      { keys: ['/'], label: 'Поиск' },
    ],
  },
  {
    title: 'Действия',
    items: [
      { keys: ['C'], label: 'Написать письмо' },
      { keys: ['R'], label: 'Ответить' },
      { keys: ['A'], label: 'Ответить всем' },
      { keys: ['E'], label: 'В архив' },
      { keys: ['#'], label: 'В корзину' },
      { keys: ['S'], label: 'Отметить важным' },
      { keys: ['Esc'], label: 'Закрыть письмо или снять выделение' },
    ],
  },
  {
    title: 'В письме',
    items: [
      { keys: ['Ctrl', 'Enter'], label: 'Отправить' },
      { keys: ['Ctrl', 'P'], label: 'Печать письма' },
      { keys: ['Ctrl', 'K'], label: 'Быстрый переход' },
      { keys: ['?'], label: 'Эта справка' },
    ],
  },
];

export default function ShortcutsHelp({ onClose }: { onClose: () => void }) {
  const { t } = useI18n();
  const dialogRef = useRef<HTMLElement>(null);
  useModalFocus(dialogRef);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-[75] flex items-center justify-center bg-ink/40 p-4 backdrop-blur-[2px]" onClick={onClose}>
      <section
        ref={dialogRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-labelledby="shortcuts-title"
        onClick={(event) => event.stopPropagation()}
        className="w-full max-w-[560px] overflow-hidden rounded-2xl border border-line bg-surface shadow-pop outline-none"
      >
        <header className="flex items-start gap-3 border-b border-line px-5 py-4">
          <div className="min-w-0 flex-1">
            <h2 id="shortcuts-title" className="text-base font-semibold text-ink">{t('Горячие клавиши')}</h2>
            <p className="mt-0.5 text-xs text-muted">{t('Работают, когда курсор не в поле ввода. Кириллица поддерживается: J = О, K = Л.')}</p>
          </div>
          <button type="button" className="icon-btn" onClick={onClose} aria-label={t('Закрыть')}><X size={18} /></button>
        </header>
        <div className="scroll-area max-h-[70vh] overflow-y-auto px-5 py-4">
          {GROUPS.map((group) => (
            <section key={group.title} className="mb-4 last:mb-0">
              <h3 className="text-[11px] font-semibold uppercase tracking-[0.05em] text-faint">{t(group.title)}</h3>
              <dl className="mt-2 space-y-1.5">
                {group.items.map((item) => (
                  <div key={item.label} className="flex items-center justify-between gap-3">
                    <dt className="text-[13px] text-ink-soft">{t(item.label)}</dt>
                    <dd className="flex shrink-0 items-center gap-1">
                      {item.keys.map((key) => <span key={key} className="kbd">{key}</span>)}
                    </dd>
                  </div>
                ))}
              </dl>
            </section>
          ))}
        </div>
        <footer className="flex justify-end border-t border-line px-5 py-3">
          <button type="button" className="btn-secondary" onClick={onClose}>{t('Понятно')}</button>
        </footer>
      </section>
    </div>
  );
}
