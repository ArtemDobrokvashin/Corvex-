import { useEffect, useRef, useState } from 'react';
import { Palette, ShieldCheck, X } from 'lucide-react';
import { LanguageSwitcher, useI18n } from '../i18n';

export type WorkspaceBackground = 'soft' | 'plain';
const KEY = 'corvex.workspaceBackground';

export function readWorkspaceBackground(): WorkspaceBackground {
  try { return localStorage.getItem(KEY) === 'plain' ? 'plain' : 'soft'; }
  catch { return 'soft'; }
}
export function saveWorkspaceBackground(value: WorkspaceBackground): boolean {
  try { localStorage.setItem(KEY, value); return true; }
  catch { return false; }
}

export default function PersonalSettings({ background, onBackground, onSecurity, onClose }: {
  background: WorkspaceBackground;
  onBackground: (value: WorkspaceBackground) => boolean;
  onSecurity: () => void;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const [saveFailed, setSaveFailed] = useState(false);
  const dialogRef = useRef<HTMLElement>(null);
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    dialogRef.current?.querySelector<HTMLButtonElement>('button')?.focus();
    return () => previous?.focus();
  }, []);

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-ink/35 p-4" onClick={onClose}>
      <section ref={dialogRef} role="dialog" aria-modal="true" aria-labelledby="personal-settings-title"
        className="flex max-h-[90vh] w-full max-w-xl flex-col overflow-hidden rounded-2xl border border-line bg-surface shadow-pop"
        onClick={(event) => event.stopPropagation()}
        onKeyDown={(event) => {
          if (event.key === 'Escape') { event.stopPropagation(); onClose(); }
          if (event.key !== 'Tab') return;
          const controls = Array.from(dialogRef.current?.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]), select:not([disabled]), [href]') ?? []);
          if (!controls.length) return;
          if (event.shiftKey && document.activeElement === controls[0]) { event.preventDefault(); controls[controls.length - 1].focus(); }
          else if (!event.shiftKey && document.activeElement === controls[controls.length - 1]) { event.preventDefault(); controls[0].focus(); }
        }}>
        <header className="flex items-center gap-3 border-b border-line px-5 py-4">
          <div className="min-w-0 flex-1"><h2 id="personal-settings-title" className="text-lg font-semibold">{t('Личные настройки')}</h2><p className="text-xs text-muted">{t('Настройки вашего рабочего пространства')}</p></div>
          <button type="button" className="icon-btn" onClick={onClose} aria-label={t('Закрыть')}><X size={19} /></button>
        </header>
        <div className="scroll-area space-y-6 overflow-y-auto p-5">
          <section><h3 className="text-sm font-semibold">{t('Язык интерфейса')}</h3><div className="mt-3"><LanguageSwitcher /></div></section>
          <section>
            <h3 className="flex items-center gap-2 text-sm font-semibold"><Palette size={17} />{t('Внешний вид')}</h3>
            <p className="mt-1 text-xs leading-5 text-muted">{t('Фон почты и чата сохраняется в этом браузере для всех пользователей устройства и не синхронизируется.')}</p>
            <div className="mt-3 flex flex-wrap gap-2" role="group" aria-label={t('Фон рабочего пространства')}>
              {(['soft', 'plain'] as const).map((value) => <button key={value} type="button" aria-pressed={background === value}
                className={`rounded-xl border px-4 py-2 text-sm font-medium ${background === value ? 'border-accent bg-accent-soft text-accent-dark' : 'border-line bg-surface text-ink hover:bg-chrome'}`}
                onClick={() => setSaveFailed(!onBackground(value))}>{t(value === 'soft' ? 'Мягкий градиент' : 'Однотонный')}</button>)}
            </div>
            {saveFailed && <p role="alert" className="mt-2 text-xs text-danger">{t('Не удалось сохранить настройку в браузере. Она действует только до обновления страницы.')}</p>}
          </section>
          <section className="border-t border-line pt-5">
            <h3 className="flex items-center gap-2 text-sm font-semibold"><ShieldCheck size={17} />{t('Безопасность')}</h3>
            <p className="mt-1 text-xs leading-5 text-muted">{t('Пароль, 2FA и пароли приложений')}</p>
            <button type="button" onClick={onSecurity} className="mt-3 rounded-xl border border-line px-4 py-2 text-sm font-medium hover:bg-chrome">{t('Открыть настройки безопасности')}</button>
          </section>
        </div>
      </section>
    </div>
  );
}
