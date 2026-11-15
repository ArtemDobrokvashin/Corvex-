import { HardDrive } from 'lucide-react';
import type { MailboxQuota } from '../../api/mailApi';
import { formatBytes, useI18n } from '../../i18n';

export default function MailboxUsage({
  quota,
  unavailable,
  variant = 'dark',
}: {
  quota: MailboxQuota | null;
  unavailable: boolean;
  variant?: 'dark' | 'light';
}) {
  const { locale, t } = useI18n();
  const percent = quota && quota.quotaBytes > 0 ? Math.round((quota.usedBytes / quota.quotaBytes) * 100) : null;
  const isDark = variant === 'dark';

  return (
    <div
      className={
        isDark
          ? 'mt-auto shrink-0 border-t border-sidebar-line px-3 py-3 text-sidebar-muted'
          : 'mt-auto shrink-0 rounded-xl border border-line bg-canvas p-3 text-muted'
      }
    >
      <div className={`flex items-center justify-between gap-2 text-xs font-medium ${isDark ? 'text-sidebar-text' : 'text-ink-soft'}`}>
        <span className="flex items-center gap-1.5">
          <HardDrive size={14} className={isDark ? 'text-sidebar-muted' : 'text-muted'} />
          <span>{t('Ящик')}</span>
        </span>
        {percent !== null && (
          <span className="text-[11px] font-semibold tabular-nums">{Math.min(100, Math.max(0, percent))}%</span>
        )}
      </div>
      {!quota ? (
        <p className="mt-2 text-[11px] leading-relaxed">
          {unavailable ? t('Не удалось получить фактический объём ящика.') : t('Загрузка объёма ящика…')}
        </p>
      ) : (
        <>
          {percent !== null && (
            <div
              className={`mt-2 h-1.5 overflow-hidden rounded-full ${isDark ? 'bg-white/[0.12]' : 'bg-line'}`}
              role="progressbar"
              aria-label={t('Использование ящика')}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={Math.min(100, Math.max(0, percent))}
            >
              <div
                className={`h-full rounded-full ${
                  percent >= 95 ? 'bg-[#EF4444]' : percent >= 80 ? 'bg-[#F59E0B]' : 'bg-accent'
                }`}
                style={{ width: `${Math.min(100, Math.max(0, percent))}%` }}
              />
            </div>
          )}
          <p className="mt-2 text-[11px] leading-relaxed tabular-nums">
            {quota.quotaBytes === 0
              ? t('Использовано {used} · без лимита', { used: formatBytes(locale, quota.usedBytes) })
              : t('Использовано {used} из {total}', {
                  used: formatBytes(locale, quota.usedBytes),
                  total: formatBytes(locale, quota.quotaBytes),
                })}
          </p>
          {unavailable && (
            <p className={`mt-1 text-[10px] leading-4 ${isDark ? 'text-[#FCD34D]' : 'text-amber-700'}`}>
              {t('Не удалось обновить объём; показаны последние полученные данные.')}
            </p>
          )}
        </>
      )}
    </div>
  );
}
