import { useEffect, useRef, useState } from 'react';
import { ArrowUpCircle, Download, Loader2, RefreshCw, Upload, Users } from 'lucide-react';
import {
  adminMigrationApi, MIGRATION_CSV_TEMPLATE,
  type AdminMigrationBulkResult, type AdminMigrationSummary,
} from '../../api/adminMigrationApi';
import { errorText } from '../../api/authApi';
import { saveBlob } from '../../api/contactsApi';
import { EmptyState, Notice } from '../../components/ui';
import { formatBytes, useI18n } from '../../i18n';

const JOB_LABELS: Record<string, string> = {
  QUEUED: 'В очереди',
  RUNNING: 'Идёт',
  PAUSED: 'Пауза',
  DONE: 'Завершён',
  PARTIAL: 'С замечаниями',
  FAILED: 'Ошибка',
  CANCELLED: 'Отменён',
};

const INVITATION_LABELS: Record<string, string> = {
  PENDING: 'Приглашён',
  STARTED: 'Начал',
  DONE: 'Готово',
  DISMISSED: 'Отложил',
};

const GIB = 1024 ** 3;

function demoSummary(): AdminMigrationSummary {
  return {
    rows: [
      {
        ownerId: 'demo-1', email: 'ivan@company.ru', displayName: 'Иван Петров',
        invitationStatus: 'PENDING', migrationState: 'PENDING', jobStatus: null, jobId: null,
        migratedMessages: 0, quotaBytes: 5 * GIB, usedBytes: 1.2 * GIB,
        sourceHost: 'imap.old-company.ru', sourceLogin: 'ivan@old-company.ru',
      },
      {
        ownerId: 'demo-2', email: 'anna@company.ru', displayName: 'Анна Смирнова',
        invitationStatus: 'STARTED', migrationState: 'DONE', jobStatus: 'RUNNING', jobId: 'job-2',
        migratedMessages: 4210, quotaBytes: 10 * GIB, usedBytes: 7.4 * GIB,
        sourceHost: 'imap.yandex.ru', sourceLogin: 'anna',
      },
      {
        ownerId: 'demo-3', email: 'petr@company.ru', displayName: 'Пётр Ковалёв',
        invitationStatus: 'PENDING', migrationState: 'DISMISSED', jobStatus: 'PAUSED', jobId: 'job-3',
        migratedMessages: 980, quotaBytes: 5 * GIB, usedBytes: 4.9 * GIB,
        sourceHost: 'mail.old-hosting.ru', sourceLogin: 'petr@old-hosting.ru',
      },
    ],
    totals: {
      employees: 3, invited: 3, notStarted: 1, running: 1, done: 0, failed: 0, migratedMessages: 5190,
    },
    notes: ['Пароли в CSV не принимаются: сотрудник вводит пароль приложения сам.'],
  };
}

/**
 * Админский экран «Перенос»: кто начал, кто нет, массовое приглашение CSV и квота.
 * Квота меняется здесь же — из отчёта видно, кому именно её не хватает.
 */
export default function MigrationTab({ demoMode = false }: { demoMode?: boolean }) {
  const { t, locale } = useI18n();
  const fileRef = useRef<HTMLInputElement>(null);
  const [summary, setSummary] = useState<AdminMigrationSummary | null>(null);
  const [loading, setLoading] = useState(!demoMode);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [bulk, setBulk] = useState<AdminMigrationBulkResult | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [addGb, setAddGb] = useState('5');

  const load = async () => {
    if (demoMode) {
      setSummary(demoSummary());
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      setSummary(await adminMigrationApi.summary());
    } catch (err) {
      setError(errorText(err, locale));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
    // Сводка перечитывается по кнопке; повторный запуск при смене языка не нужен.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [demoMode]);

  const upload = async (file: File) => {
    setBusy(true);
    setError('');
    setNotice('');
    setBulk(null);
    try {
      const result = await adminMigrationApi.bulk(file);
      setBulk(result);
      setNotice(t('CSV обработан: приглашено {invited}, пропущено {skipped}.', {
        invited: result.invited, skipped: result.skipped,
      }));
      await load();
    } catch (err) {
      setError(errorText(err, locale));
    } finally {
      setBusy(false);
    }
  };

  const bump = async (ownerId: string) => {
    const gigabytes = Number(addGb.replace(',', '.'));
    if (!Number.isFinite(gigabytes) || gigabytes <= 0) {
      setError(t('Укажите, сколько гигабайт добавить'));
      return;
    }
    setBusy(true);
    setError('');
    try {
      const result = await adminMigrationApi.quotaBump(ownerId, { addBytes: Math.round(gigabytes * GIB) });
      setNotice(t('Квота увеличена до {size}.', { size: formatBytes(locale, result.quotaBytes) }));
      setEditingId(null);
      await load();
    } catch (err) {
      setError(errorText(err, locale));
    } finally {
      setBusy(false);
    }
  };

  const totals = summary?.totals;

  return (
    <section className="panel overflow-hidden">
      <header className="flex flex-col gap-4 border-b border-line px-5 py-4 lg:flex-row lg:items-center lg:justify-between">
        <div className="flex items-start gap-3">
          <span className="mt-0.5 flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-accent-soft text-accent"><Users size={20} /></span>
          <div>
            <h2 className="text-base font-semibold text-ink">{t('Перенос почты сотрудников')}</h2>
            <p className="mt-1 max-w-2xl text-xs leading-5 text-muted">
              {t('CSV без паролей: email, source_host, source_login. Сотрудник получает приглашение и вводит пароль приложения сам.')}
            </p>
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <button type="button" className="btn-secondary h-9 px-3 text-xs" onClick={() => saveBlob(new Blob([MIGRATION_CSV_TEMPLATE], { type: 'text/csv;charset=utf-8' }), 'migration-invites.csv')}>
            <Download size={14} /> {t('Шаблон CSV')}
          </button>
          {!demoMode && (
            <>
              <input
                ref={fileRef}
                type="file"
                accept=".csv,.tsv,text/csv"
                className="hidden"
                aria-label={t('CSV для массового приглашения')}
                onChange={(event) => {
                  const file = event.target.files?.[0];
                  event.target.value = '';
                  if (file) void upload(file);
                }}
              />
              <button type="button" className="btn-primary h-9 px-3 text-xs" disabled={busy} onClick={() => fileRef.current?.click()}>
                {busy ? <Loader2 size={14} className="animate-spin" /> : <Upload size={14} />} {t('Загрузить CSV')}
              </button>
              <button type="button" className="btn-secondary h-9 px-3 text-xs" disabled={loading} onClick={() => void load()}>
                <RefreshCw size={14} /> {t('Обновить')}
              </button>
            </>
          )}
        </div>
      </header>

      {(error || notice) && (
        <div className="space-y-2 px-5 pt-4" aria-live="polite">
          {error && <Notice tone="error">{error}</Notice>}
          {notice && <Notice tone="success">{notice}</Notice>}
          {bulk && bulk.issues.length > 0 && (
            <Notice tone="warning">
              <ul className="space-y-0.5 text-xs">
                {bulk.issues.map((issue, index) => (
                  <li key={`${issue.line}-${index}`}>{t('Строка {line}', { line: issue.line })}: {issue.value} — {issue.reason}</li>
                ))}
              </ul>
            </Notice>
          )}
          {bulk?.truncated && <Notice tone="warning">{t('Обработаны первые 1000 строк: файл больше лимита.')}</Notice>}
        </div>
      )}

      {totals && (
        <div className="grid grid-cols-2 gap-px border-b border-line bg-line text-center sm:grid-cols-4 lg:grid-cols-7">
          {[
            { label: t('Сотрудников'), value: totals.employees },
            { label: t('Приглашено'), value: totals.invited },
            { label: t('Не начали'), value: totals.notStarted },
            { label: t('Идёт'), value: totals.running },
            { label: t('Завершено'), value: totals.done },
            { label: t('С ошибкой'), value: totals.failed },
            { label: t('Писем перенесено'), value: totals.migratedMessages },
          ].map((item) => (
            <div key={item.label} className="bg-surface px-3 py-3">
              <div className="text-[11px] uppercase tracking-[0.06em] text-faint">{item.label}</div>
              <div className="mt-1 text-base font-semibold tabular-nums text-ink">{item.value}</div>
            </div>
          ))}
        </div>
      )}

      <div className="overflow-x-auto">
        <table className="w-full min-w-[860px] border-collapse text-sm">
          <thead>
            <tr className="bg-chrome/80 text-left text-[11px] uppercase tracking-[0.07em] text-faint">
              <th className="px-5 py-3 font-semibold">{t('Сотрудник')}</th>
              <th className="px-4 py-3 font-semibold">{t('Источник')}</th>
              <th className="px-4 py-3 font-semibold">{t('Состояние')}</th>
              <th className="px-4 py-3 font-semibold">{t('Перенесено')}</th>
              <th className="min-w-[190px] px-4 py-3 font-semibold">{t('Квота')}</th>
              <th className="px-5 py-3 text-right font-semibold">{t('Действия')}</th>
            </tr>
          </thead>
          <tbody>
            {(summary?.rows ?? []).map((row) => {
              const editing = editingId === row.ownerId;
              const usage = row.quotaBytes > 0 ? Math.round((row.usedBytes / row.quotaBytes) * 100) : null;
              return (
                <tr key={row.ownerId} className="border-t border-line align-top transition hover:bg-chrome/60">
                  <td className="px-5 py-4">
                    <div className="font-medium text-ink">{row.displayName || row.email}</div>
                    <div className="mt-0.5 text-xs text-muted">{row.email}</div>
                  </td>
                  <td className="px-4 py-4 text-xs text-muted">
                    {row.sourceHost ?? '—'}
                    {row.sourceLogin && <div className="text-faint">{row.sourceLogin}</div>}
                  </td>
                  <td className="px-4 py-4 text-xs">
                    <div className="text-ink-soft">
                      {row.jobStatus
                        ? t(JOB_LABELS[row.jobStatus] ?? row.jobStatus)
                        : row.invitationStatus
                          ? t(INVITATION_LABELS[row.invitationStatus] ?? row.invitationStatus)
                          : '—'}
                    </div>
                    {row.migrationState === 'DISMISSED' && <div className="mt-0.5 text-faint">{t('Отложил предложение')}</div>}
                    {row.migrationState === 'NOT_NEEDED' && <div className="mt-0.5 text-faint">{t('Сказал, что переносить нечего')}</div>}
                  </td>
                  <td className="whitespace-nowrap px-4 py-4 tabular-nums text-ink-soft">{row.migratedMessages}</td>
                  <td className="px-4 py-4">
                    {!editing ? (
                      <>
                        <div className="text-xs text-muted">
                          {formatBytes(locale, row.usedBytes)} / {row.quotaBytes > 0 ? formatBytes(locale, row.quotaBytes) : t('Без лимита')}
                        </div>
                        {usage !== null && (
                          <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-black/[0.07]">
                            <div className={`h-full rounded-full ${usage > 100 ? 'bg-danger' : usage >= 85 ? 'bg-amber-500' : 'bg-accent'}`} style={{ width: `${Math.min(100, usage)}%` }} />
                          </div>
                        )}
                        {usage !== null && usage >= 85 && <div className="mt-1 text-[11px] text-amber-700">{t('Квоты не хватает на архив')}</div>}
                      </>
                    ) : (
                      <form
                        onSubmit={(event) => { event.preventDefault(); void bump(row.ownerId); }}
                        className="max-w-[240px] rounded-xl border border-accent/25 bg-surface p-3 shadow-soft"
                      >
                        <label htmlFor={`add-${row.ownerId}`} className="field-label">{t('Добавить, ГБ')}</label>
                        <input
                          id={`add-${row.ownerId}`}
                          value={addGb}
                          onChange={(event) => setAddGb(event.target.value)}
                          inputMode="decimal"
                          autoFocus
                          className="field h-9 py-0"
                        />
                        <div className="mt-2 flex gap-2">
                          <button type="submit" className="btn-primary h-8 px-3 text-xs" disabled={busy}>{t('Поднять')}</button>
                          <button type="button" className="btn-ghost h-8 px-3 text-xs" onClick={() => setEditingId(null)}>{t('Отмена')}</button>
                        </div>
                      </form>
                    )}
                  </td>
                  <td className="px-5 py-4 text-right">
                    <button
                      type="button"
                      className="btn-secondary h-8 px-3 text-xs"
                      disabled={busy}
                      onClick={() => { setEditingId(editing ? null : row.ownerId); setAddGb('5'); }}
                      aria-label={t('Поднять квоту для {name}', { name: row.displayName || row.email })}
                    >
                      <ArrowUpCircle size={14} /> {t('Квота')}
                    </button>
                  </td>
                </tr>
              );
            })}
            {!loading && (summary?.rows ?? []).length === 0 && (
              <tr><td colSpan={6}><EmptyState icon={Users} title={t('Приглашений пока нет')} description={t('Загрузите CSV с адресами сотрудников и источниками.')} /></td></tr>
            )}
          </tbody>
        </table>
      </div>

      <footer className="border-t border-line px-5 py-3 text-xs leading-5 text-faint">
        {summary?.notes.map((note) => <p key={note}>{note}</p>)}
      </footer>
    </section>
  );
}
