import { useEffect, useState } from 'react';
import { Clock as ClockIcon } from 'lucide-react';
import { formatTime, useI18n } from '../i18n';

/**
 * Компактные часы в оболочке Corvex.
 * Показывают локальное время устройства (ЧЧ:MM) без секунд, чтобы не создавать
 * визуальный шум. Обновляются каждую минуту; таймер очищается при размонтировании.
 * Никаких внешних вызовов — только Intl.DateTimeFormat с текущей локалью.
 */
export function Clock({ className = '' }: { className?: string }) {
  const { locale, t } = useI18n();
  const [now, setNow] = useState(new Date());

  useEffect(() => {
    setNow(new Date());
    const timer = setInterval(() => setNow(new Date()), 60_000);
    return () => clearInterval(timer);
  }, [locale]);

  const time = formatTime(locale, now);
  return (
    <div
      className={`flex items-center gap-1.5 ${className}`}
      aria-label={t('Текущее время')}
      title={t('Текущее время')}
    >
      <ClockIcon size={13} strokeWidth={1.6} />
      <span className="text-[13px] font-semibold text-ink tracking-tight">{time}</span>
    </div>
  );
}
