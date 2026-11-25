import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Bell, Check, Keyboard, LayoutList, Mail, PenLine, Settings2, ShieldCheck, X } from 'lucide-react';
import { useModalFocus } from '../../components/useModalFocus';
import { useI18n } from '../../i18n';
import type { MailPreferences } from './mailPreferences';

export default function MailSettingsModal({
  initial, email, demoMode, onClose, onSave, onOpenSecurity, onOpenExternal, onOpenShortcuts,
}: {
  initial: MailPreferences;
  email: string;
  demoMode: boolean;
  onClose: () => void;
  onSave: (preferences: MailPreferences) => boolean;
  onOpenSecurity?: () => void;
  onOpenExternal?: () => void;
  onOpenShortcuts?: () => void;
}) {
  const { t } = useI18n();
  const notificationsAvailable = typeof window !== 'undefined' && 'Notification' in window;
  const [permission, setPermission] = useState<NotificationPermission | 'unavailable'>(
    () => notificationsAvailable ? Notification.permission : 'unavailable',
  );
  const [draft, setDraft] = useState(() => ({ ...initial, notifications: initial.notifications && permission === 'granted' }));
  const [error, setError] = useState('');
  const [notificationError, setNotificationError] = useState(
    () => initial.notifications && permission !== 'granted' && notificationsAvailable
      ? t('Браузер больше не разрешает уведомления. Проверьте разрешения сайта.') : '',
  );
  const [requesting, setRequesting] = useState(false);
  const mountedRef = useRef(true);
  const requestingRef = useRef(false);
  const dialogRef = useRef<HTMLElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  useModalFocus(dialogRef, headingRef);

  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  const toggleNotifications = async (checked: boolean) => {
    if (requestingRef.current) return;
    setNotificationError('');
    if (!checked) { setDraft((current) => ({ ...current, notifications: false })); return; }
    if (!notificationsAvailable || demoMode) return;
    requestingRef.current = true;
    setRequesting(true);
    try {
      const result = Notification.permission === 'granted' ? 'granted' : await Notification.requestPermission();
      if (!mountedRef.current) return;
      setPermission(result);
      if (result === 'granted') setDraft((current) => ({ ...current, notifications: true }));
      else setNotificationError(t('Браузер не разрешил уведомления. Проверьте разрешения сайта.'));
    } catch {
      if (mountedRef.current) setNotificationError(t('Не удалось включить уведомления в этом браузере.'));
    } finally {
      requestingRef.current = false;
      if (mountedRef.current) setRequesting(false);
    }
  };

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (requestingRef.current) return;
    if (onSave(draft)) onClose();
    else setError(t('Браузер не сохранил настройки. Они будут действовать только до закрытия экрана почты.'));
  };

  const sectionHeading = (Icon: typeof Mail, label: string, description: string) => (
    <div className="mb-4 flex items-start gap-3">
      <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-canvas text-accent"><Icon size={17} /></span>
      <div><h3 className="text-sm font-semibold text-ink">{label}</h3><p className="mt-0.5 text-xs leading-relaxed text-muted">{description}</p></div>
    </div>
  );

  return (
    <div className="fixed inset-0 z-[70] flex justify-end bg-ink/40 backdrop-blur-[2px]" onClick={onClose}>
      <section ref={dialogRef} tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby="mail-settings-title" className="flex h-full w-full max-w-[500px] animate-rise-in flex-col bg-surface shadow-pop outline-none" onClick={(event) => event.stopPropagation()}>
        <header className="flex shrink-0 items-center gap-3 border-b border-line px-5 py-4 sm:px-6">
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-ink text-white"><Settings2 size={19} /></span>
          <div className="min-w-0 flex-1"><h2 ref={headingRef} tabIndex={-1} id="mail-settings-title" className="text-[17px] font-semibold text-ink outline-none">{t('Настройки почты')}</h2><p className="truncate text-xs text-muted">{email}</p></div>
          <button type="button" className="icon-btn" onClick={onClose} aria-label={t('Закрыть настройки')}><X size={19} /></button>
        </header>
        <form onSubmit={submit} className="flex min-h-0 flex-1 flex-col">
          <div className="scroll-area min-h-0 flex-1 px-5 py-5 sm:px-6">
            <div className="rounded-xl border border-[#BFDBFE] bg-[#EFF6FF] px-3.5 py-3 text-xs leading-relaxed text-[#1E40AF]">
              {demoMode
                ? t('В демо-режиме настройки сохраняются только в этом браузере.')
                : t('Настройки и подпись хранятся на сервере за вашим адресом: они доступны на всех устройствах, где вы входите в Corvex.')}
            </div>

            <section className="border-b border-line py-6">
              {sectionHeading(LayoutList, t('Список писем'), t('Выберите удобный вид и порядок писем.'))}
              <div className="space-y-4">
                <label className="block text-[13px] font-medium text-ink" htmlFor="mail-density">{t('Плотность списка')}
                  <select id="mail-density" className="field mt-1.5" value={draft.density} onChange={(event) => setDraft({ ...draft, density: event.target.value as MailPreferences['density'] })}>
                    <option value="comfortable">{t('Удобная')}</option><option value="compact">{t('Компактная')}</option>
                  </select>
                </label>
                <label className="block text-[13px] font-medium text-ink" htmlFor="mail-default-sort">{t('Порядок по умолчанию')}
                  <select id="mail-default-sort" className="field mt-1.5" value={draft.defaultSort} onChange={(event) => setDraft({ ...draft, defaultSort: event.target.value as MailPreferences['defaultSort'] })}>
                    <option value="date-desc">{t('Сначала новые')}</option><option value="date-asc">{t('Сначала старые')}</option><option value="sender-asc">{t('По отправителю А–Я')}</option><option value="subject-asc">{t('По теме А–Я')}</option>
                  </select>
                </label>
                <label className="flex cursor-pointer items-start gap-3 text-[13px] text-ink-soft"><input type="checkbox" className="mt-0.5 h-4 w-4 accent-[#1D63ED]" checked={draft.showPreview} onChange={(event) => setDraft({ ...draft, showPreview: event.target.checked })} /><span>{t('Показывать текст предпросмотра в списке')}</span></label>
                <label className="flex cursor-pointer items-start gap-3 text-[13px] text-ink-soft"><input type="checkbox" className="mt-0.5 h-4 w-4 accent-[#1D63ED]" checked={draft.conversations} onChange={(event) => setDraft({ ...draft, conversations: event.target.checked })} /><span>{t('Собирать письма в цепочки')}</span>
                  <span className="block text-xs text-muted">{t('Ответы на одно письмо будут свёрнуты в одну строку. Группировка идёт по теме и отправителю.')}</span>
                </label>
              </div>
            </section>

            <section className="border-b border-line py-6">
              {sectionHeading(Mail, t('Чтение писем'), t('Настройте отметку прочтения.'))}
              <label className="block text-[13px] font-medium text-ink" htmlFor="mail-mark-read">{t('Отмечать как прочитанное')}
                <select id="mail-mark-read" className="field mt-1.5" value={draft.markRead} onChange={(event) => setDraft({ ...draft, markRead: event.target.value as MailPreferences['markRead'] })}>
                  <option value="automatic">{t('При открытии письма')}</option><option value="manual">{t('Только вручную')}</option>
                </select>
              </label>
            </section>

            <section className="border-b border-line py-6">
              {sectionHeading(PenLine, t('Подпись'), t('Обычный текст будет добавлен в редактор и его можно изменить перед отправкой.'))}
              <label htmlFor="mail-signature" className="block text-[13px] font-medium text-ink">{t('Текст подписи')}</label>
              <textarea id="mail-signature" className="field mt-1.5 min-h-[112px] resize-y py-2.5" maxLength={5000} value={draft.signature} onChange={(event) => setDraft({ ...draft, signature: event.target.value })} placeholder={t('Имя, должность, контакты…')} />
              <div className="mt-3 space-y-2.5">
                <label className="flex cursor-pointer items-start gap-3 text-[13px] text-ink-soft"><input type="checkbox" className="mt-0.5 h-4 w-4 accent-[#1D63ED]" checked={draft.signatureForNew} onChange={(event) => setDraft({ ...draft, signatureForNew: event.target.checked })} /><span>{t('Добавлять в новые письма')}</span></label>
                <label className="flex cursor-pointer items-start gap-3 text-[13px] text-ink-soft"><input type="checkbox" className="mt-0.5 h-4 w-4 accent-[#1D63ED]" checked={draft.signatureForReplies} onChange={(event) => setDraft({ ...draft, signatureForReplies: event.target.checked })} /><span>{t('Добавлять в ответы и пересылки')}</span></label>
              </div>
              <label className="mt-3 flex cursor-pointer items-start gap-3 text-[13px] text-ink-soft"><input type="checkbox" className="mt-0.5 h-4 w-4 accent-[#1D63ED]" checked={draft.signatureForSubmission} disabled={demoMode} onChange={(event) => setDraft({ ...draft, signatureForSubmission: event.target.checked })} /><span>{t('Добавлять подпись и в письма из Thunderbird и Outlook')}</span></label>
              <p className="mt-3 text-xs leading-relaxed text-muted">
                {demoMode
                  ? t('Подпись применяется к письмам, созданным в этом веб-клиенте.')
                  : t('Подпись применяется к письмам, созданным в этом веб-клиенте. Для писем из других клиентов включите отдельную галочку — без неё их текст не меняется.')}
              </p>
            </section>

            <section className="border-b border-line py-6">
              {sectionHeading(Bell, t('Уведомления'), t('Новые письма проверяются, пока открыт раздел «Почта». Нужны разрешения браузера.'))}
              <label className="flex items-start gap-3 text-[13px] text-ink-soft"><input type="checkbox" className="mt-0.5 h-4 w-4 accent-[#1D63ED]" checked={draft.notifications && notificationsAvailable && !demoMode} disabled={!notificationsAvailable || demoMode || requesting} onChange={(event) => void toggleNotifications(event.target.checked)} /><span>{t('Уведомлять о новых входящих письмах')}</span></label>
              {demoMode && <p className="mt-2 text-xs text-muted">{t('Уведомления о новых письмах доступны при подключении к серверу.')}</p>}
              {!notificationsAvailable && !demoMode && <p className="mt-2 text-xs text-muted">{t('Этот браузер не поддерживает уведомления.')}</p>}
              {requesting && <p className="mt-2 text-xs text-muted" role="status">{t('Ожидается разрешение браузера…')}</p>}
              {notificationError && <p className="mt-2 text-xs text-red-700" role="alert">{notificationError}</p>}
            </section>

            {/* Справка по клавишам раньше висела пунктом в меню «…» рядом с
                «В спам»: место для неё — настройки, а не ежедневный тулбар. */}
            {onOpenShortcuts && <section className="border-b border-line py-6">
              {sectionHeading(Keyboard, t('Горячие клавиши'), t('Полный список сочетаний. Справка также открывается клавишей «?».'))}
              <button type="button" className="btn-secondary w-full justify-start" onClick={onOpenShortcuts}>
                <Keyboard size={15} />{t('Показать горячие клавиши')}
              </button>
            </section>}

            {(onOpenSecurity || onOpenExternal) && <section className="py-6">
              {sectionHeading(ShieldCheck, t('Учётная запись'), t('Безопасность и подключённые адреса настраиваются отдельно.'))}
              <div className="space-y-2">
                {onOpenSecurity && <button type="button" className="btn-secondary w-full justify-start" onClick={onOpenSecurity}><ShieldCheck size={15} />{t('Безопасность и 2FA')}</button>}
                {onOpenExternal && <button type="button" className="btn-secondary w-full justify-start" onClick={onOpenExternal}><Mail size={15} />{t('Внешние ящики')}</button>}
              </div>
            </section>}
            <p className="pb-2 text-xs leading-relaxed text-muted">{t('Серверные правила и автоответ пока не поддерживаются.')}</p>
            {error && <p role="alert" className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-xs text-red-800">{error}</p>}
          </div>
          <footer className="flex shrink-0 items-center justify-end gap-2 border-t border-line bg-surface px-5 py-3.5 sm:px-6">
            <button type="button" className="btn-secondary" onClick={onClose}>{t('Отмена')}</button>
            <button type="submit" className="btn-primary" disabled={requesting}><Check size={15} />{t('Сохранить настройки')}</button>
          </footer>
        </form>
      </section>
    </div>
  );
}
