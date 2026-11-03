import { useCallback, useEffect, useState } from 'react';
import { Activity, DatabaseBackup, HardDrive, RefreshCw, Server } from 'lucide-react';
import { adminApi, type BackupRequestResult, type SystemHealth } from '../../api/adminApi';
import { errorText } from '../../api/authApi';
import { Notice } from '../../components/ui';
import { formatBytes, formatDateTime, formatNumber, useI18n } from '../../i18n';
import { useSession } from '../../store/sessionStore';
import { summarizeHealth } from './adminInsights';

const HEALTH_REFRESH_MS = 60_000;

const DEMO_HEALTH: SystemHealth = {
  services: [
    { name: 'auth-service', status: 'UP', version: null },
    { name: 'admin-service', status: 'UP', version: null },
    { name: 'mail-smtp-service', status: 'UP', version: null },
    { name: 'mail-storage-service', status: 'UP', version: null },
    { name: 'chat-service', status: 'UP', version: null },
    { name: 'web-gateway', status: 'UP', version: null },
    { name: 'elasticsearch (поиск)', status: 'DEGRADED', version: null },
  ],
  diskFreeBytes: 412 * 1024 ** 3,
  mailQueueDepth: 0,
};

function HealthStatus({ status }: { status: string }) {
  const { t } = useI18n();
  const up = status === 'UP';
  const degraded = status === 'DEGRADED';
  const label = up ? t('работает') : degraded ? t('деградация') : t('недоступен');
  const className = up
    ? 'border-emerald-200 bg-emerald-50 text-emerald-800'
    : degraded
      ? 'border-amber-200 bg-amber-50 text-amber-800'
      : 'border-red-200 bg-red-50 text-red-800';
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium ${className}`}>
      <span className={`h-1.5 w-1.5 rounded-full ${up ? 'bg-emerald-500' : degraded ? 'bg-amber-500' : 'bg-red-500'}`} />
      {label}
    </span>
  );
}

export default function ServerTab({ canViewHealth, canBackup }: { canViewHealth: boolean; canBackup: boolean }) {
  const { locale, t } = useI18n();
  const demoMode = useSession((state) => state.demoMode);
  const [health, setHealth] = useState<SystemHealth | null>(demoMode ? DEMO_HEALTH : null);
  const [healthLoading, setHealthLoading] = useState(false);
  const [healthError, setHealthError] = useState('');
  const [updatedAt, setUpdatedAt] = useState<Date | null>(demoMode ? new Date() : null);
  const [backupRunning, setBackupRunning] = useState(false);
  const [backupError, setBackupError] = useState('');
  const [backupResult, setBackupResult] = useState<BackupRequestResult | null>(null);

  const loadHealth = useCallback(async () => {
    if (!canViewHealth) return;
    setHealthLoading(true);
    setHealthError('');
    try {
      const result = demoMode ? DEMO_HEALTH : await adminApi.systemHealth();
      setHealth(result);
      setUpdatedAt(new Date());
    } catch (error) {
      setHealthError(errorText(error, locale));
    } finally {
      setHealthLoading(false);
    }
  }, [canViewHealth, demoMode, locale]);

  useEffect(() => {
    if (!canViewHealth) return undefined;
    void loadHealth();
    const timer = window.setInterval(() => void loadHealth(), HEALTH_REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [canViewHealth, loadHealth]);

  async function requestBackup() {
    setBackupRunning(true);
    setBackupError('');
    setBackupResult(null);
    try {
      const result = demoMode
        ? { status: 'REQUESTED', note: '' }
        : await adminApi.requestBackup();
      setBackupResult(result);
    } catch (error) {
      setBackupError(errorText(error, locale));
    } finally {
      setBackupRunning(false);
    }
  }

  const summary = health ? summarizeHealth(health.services) : null;

  return (
    <div className="space-y-5">
      {canViewHealth && (
        <section className="panel animate-rise-in overflow-hidden" aria-labelledby="server-health-title">
          <header className="flex flex-wrap items-start gap-3 border-b border-line px-5 py-5 sm:px-6">
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-accent-soft text-accent-dark"><Server size={19} strokeWidth={1.8} /></span>
            <div className="min-w-0 flex-1">
              <h2 id="server-health-title" className="text-[20px] font-semibold tracking-[-0.025em]">{t('Состояние сервера')}</h2>
              <p className="mt-1 max-w-2xl text-sm leading-relaxed text-muted">{t('Реальные проверки сервисов, диска и почтовой очереди. Обновление — каждую минуту.')}</p>
            </div>
            <button type="button" className="btn-secondary" onClick={() => void loadHealth()} disabled={healthLoading}>
              <RefreshCw size={15} className={healthLoading ? 'animate-spin' : ''} />
              {healthLoading ? t('Обновляем…') : t('Обновить')}
            </button>
          </header>

          <div className="px-5 py-5 sm:px-6">
            {healthError && <Notice tone="error" className="mb-5">{healthError}</Notice>}
            {!health && !healthLoading && !healthError && (
              <p className="text-sm text-muted">{t('Нет данных. Возможно, у текущей роли нет права на системное состояние.')}</p>
            )}
            {!health && healthLoading && <p className="text-sm text-muted" role="status">{t('Проверяем состояние сервисов…')}</p>}

            {health && summary && (
              <>
                <div className="grid gap-3 sm:grid-cols-3" aria-live="polite">
                  <div className="rounded-xl bg-chrome px-4 py-4">
                    <p className="text-xs font-medium text-muted">{t('Работает сервисов')}</p>
                    <p className="mt-1.5 text-2xl font-semibold tracking-[-0.03em] text-ink">{formatNumber(locale, summary.up)} <span className="text-sm font-normal text-faint">/ {formatNumber(locale, summary.total)}</span></p>
                  </div>
                  <div className="rounded-xl bg-chrome px-4 py-4">
                    <p className="text-xs font-medium text-muted">{t('Требуют внимания')}</p>
                    <p className={`mt-1.5 text-2xl font-semibold tracking-[-0.03em] ${summary.issues ? 'text-amber-700' : 'text-emerald-700'}`}>{formatNumber(locale, summary.issues)}</p>
                  </div>
                  <div className="rounded-xl bg-chrome px-4 py-4">
                    <p className="text-xs font-medium text-muted">{t('Свободно на диске')}</p>
                    <p className="mt-1.5 text-2xl font-semibold tracking-[-0.03em] text-ink">{health.diskFreeBytes < 0 ? '—' : formatBytes(locale, health.diskFreeBytes)}</p>
                  </div>
                </div>

                <div className="mt-5 overflow-hidden rounded-xl border border-line">
                  {health.services.map((service) => (
                    <div key={service.name} className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-line px-4 py-3 last:border-0 sm:flex-nowrap">
                      <span className={`h-2 w-2 shrink-0 rounded-full ${service.status === 'UP' ? 'bg-emerald-500' : service.status === 'DEGRADED' ? 'bg-amber-500' : 'bg-red-500'}`} />
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-medium text-ink-soft">{t(service.name)}</p>
                        {service.version && <p className="mt-0.5 truncate font-mono text-[11px] text-faint">{service.version}</p>}
                      </div>
                      <HealthStatus status={service.status} />
                    </div>
                  ))}
                  {health.services.length === 0 && <p className="px-4 py-5 text-sm text-muted">{t('Сервисы не вернули данные проверки.')}</p>}
                </div>

                <div className="mt-4 flex flex-wrap items-center gap-x-5 gap-y-2 text-xs text-muted">
                  <span className="inline-flex items-center gap-2"><HardDrive size={14} />{t('Очередь почты:')} <b className="font-medium text-ink-soft">{health.mailQueueDepth < 0 ? t('нет данных RabbitMQ') : formatNumber(locale, health.mailQueueDepth)}</b></span>
                  {updatedAt && <span>{t('Обновлено: {time}', { time: formatDateTime(locale, updatedAt) })}</span>}
                </div>
              </>
            )}
          </div>
        </section>
      )}

      {canBackup && (
        <section className="panel animate-rise-in p-5 sm:p-6" aria-labelledby="server-backup-title">
          <div className="flex flex-wrap items-start gap-3">
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-accent-soft text-accent-dark"><DatabaseBackup size={19} strokeWidth={1.8} /></span>
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-2">
                <h2 id="server-backup-title" className="text-[20px] font-semibold tracking-[-0.025em]">{t('Резервное копирование')}</h2>
                <span className="chip">{t('Запрос в аудит')}</span>
              </div>
              <p className="mt-1 max-w-3xl text-sm leading-relaxed text-muted">{t('Зафиксируйте необходимость внепланового резервного копирования. Действие попадёт в журнал аудита.')}</p>
            </div>
          </div>

          <Notice tone="warning" className="mt-5">
            {t('Эта кнопка не создаёт резервную копию и не запускает скрипт. Физическое копирование выполняет scripts/backup.ps1 на Windows-хосте по расписанию Планировщика.')}
          </Notice>

          {backupError && <Notice tone="error" className="mt-4">{backupError}</Notice>}
          {backupResult?.status === 'REQUESTED' && (
            <Notice tone="success" className="mt-4">
              <b className="font-medium">{t('Запрос зарегистрирован в аудите.')}</b>{' '}{t('Резервная копия этим действием не создавалась.')}
            </Notice>
          )}
          {backupResult && backupResult.status !== 'REQUESTED' && (
            <Notice tone="warning" className="mt-4">{t('Сервер вернул неожиданный статус запроса: {status}', { status: backupResult.status })}</Notice>
          )}

          <div className="mt-5 flex flex-wrap items-center gap-3">
            <button type="button" className="btn-primary" onClick={() => void requestBackup()} disabled={backupRunning}>
              <Activity size={15} />{backupRunning ? t('Регистрируем запрос…') : t('Зафиксировать запрос в аудите')}
            </button>
            <span className="text-xs leading-relaxed text-faint">{t('После регистрации событие BACKUP_REQUESTED доступно в журнале аудита.')}</span>
          </div>
        </section>
      )}
    </div>
  );
}
