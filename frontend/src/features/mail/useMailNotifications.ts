import { useNavigate } from 'react-router-dom';
import { useCallback } from 'react';
import { useI18n } from '../../i18n';
import { playNotificationSound, useMailNotifications as pollInbox } from './mailNotifications';

/**
 * Уведомление о новых письмах. Поллинг живёт в общем хуке и вызывается из `AppShell`,
 * поэтому уведомление приходит и из чата, и из календаря, а не только из почты.
 */
export function useMailNotifications(enabled: boolean, demoMode: boolean, inboxId: string | undefined) {
  const { t } = useI18n();
  const navigate = useNavigate();

  const onNewMail = useCallback((fresh: { id: string }[]) => {
    playNotificationSound();
    if (typeof window === 'undefined' || !('Notification' in window)) return;
    if (Notification.permission !== 'granted') return;
    const notice = new Notification(t('Новое письмо'), {
      body: t('Новых входящих писем: {count}', { count: fresh.length }),
      tag: 'corvex-new-mail',
    });
    notice.onclick = () => {
      window.focus();
      navigate(`/mail?mailId=${encodeURIComponent(fresh[0].id)}`);
      notice.close();
    };
  }, [navigate, t]);

  pollInbox(enabled, demoMode, inboxId, onNewMail);
}
