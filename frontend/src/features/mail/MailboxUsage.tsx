import { HardDrive } from 'lucide-react';
import type { MailboxQuota } from '../../api/mailApi';
import { formatBytes, useI18n } from '../../i18n';

export default function MailboxUsage({ quota, unavailable }: { quota: MailboxQuota | null; unavailable: boolean }) {
  const { locale, t } = useI18n();
  const percent = quota && quota.quotaBytes > 0 ? Math.round((quota.usedBytes / quota.quotaBytes) * 100) : null;

  return (
    <div className="mt-auto shrink-0 rounded-xl bg-black/[0.035] p-3">
      <div className="flex items-center gap-2 text-xs font-medium text-ink-soft"><HardDrive size={14} className="text-muted" />{t('Ящик')}</div>
      {!quota ? <p className="mt-2 text-[11px] leading-relaxed text-muted">{unavailable ? t('Не удалось получить фактический объём ящика.') : t('Загрузка объёма ящика…')}</p> : <>
        {percent !== null && <div className="mt-2.5 h-1.5 overflow-hidden rounded-full bg-black/[0.08]" role="progressbar" aria-label={t('Использование ящика')} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.min(100, Math.max(0, percent))}>
          <div className={`h-full rounded-full ${percent > 100 ? 'bg-danger' : 'bg-accent'}`} style={{ width: `${Math.min(100, Math.max(0, percent))}%` }} />
        </div>}
        <p className="mt-2 text-[11px] leading-relaxed text-muted">{quota.quotaBytes === 0
          ? t('Использовано {used} · без лимита', { used: formatBytes(locale, quota.usedBytes) })
          : t('Использовано {used} из {total}', { used: formatBytes(locale, quota.usedBytes), total: formatBytes(locale, quota.quotaBytes) })}</p>
        {unavailable && <p className="mt-1 text-[10px] leading-4 text-amber-700">{t('Не удалось обновить объём; показаны последние полученные данные.')}</p>}
      </>}
    </div>
  );
}
