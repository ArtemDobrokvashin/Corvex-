import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { mailApi } from '../../api/mailApi';
import { useI18n } from '../../i18n';

/** Polls only while MailPage is mounted; the first snapshot is a baseline, not a notification. */
export function useMailNotifications(enabled: boolean, demoMode: boolean, inboxId: string | undefined) {
  const { t } = useI18n();
  const navigate = useNavigate();

  useEffect(() => {
    if (!enabled || demoMode || !inboxId || typeof window === 'undefined' || !('Notification' in window)) return undefined;
    let cancelled = false;
    let running = false;
    let baselineReady = false;
    let newestUid = 0;

    const poll = async () => {
      if (cancelled || running || Notification.permission !== 'granted') return;
      running = true;
      try {
        const items = await mailApi.messages(inboxId);
        if (cancelled) return;
        const fresh = baselineReady ? items.filter((item) => item.uid > newestUid && !item.seen) : [];
        newestUid = Math.max(newestUid, ...items.map((item) => item.uid));
        baselineReady = true;
        if (fresh.length > 0) {
          const notice = new Notification(t('Новое письмо'), {
            body: t('Новых входящих писем: {count}', { count: fresh.length }),
            tag: 'corvex-new-mail',
          });
          notice.onclick = () => {
            window.focus();
            navigate(`/mail?mailId=${encodeURIComponent(fresh[0].id)}`);
            notice.close();
          };
        }
      } catch {
        // Ordinary mail refresh displays errors. A notification poll is best-effort.
      } finally {
        running = false;
      }
    };
    void poll();
    const timer = window.setInterval(() => { void poll(); }, 15_000);
    return () => { cancelled = true; window.clearInterval(timer); };
  }, [enabled, demoMode, inboxId, navigate, t]);
}
