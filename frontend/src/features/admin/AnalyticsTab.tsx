import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { BarChart3, Building2, RefreshCw, Users } from 'lucide-react';
import { adminApi, type AnalyticsActivity } from '../../api/adminApi';
import { errorText } from '../../api/authApi';
import { DEMO_USERS } from '../../api/demoData';
import { Notice } from '../../components/ui';
import { formatDateTime, formatNumber, useI18n } from '../../i18n';
import { useSession } from '../../store/sessionStore';
import { displayMetric, sharePercent } from './adminInsights';

function demoAnalytics(department: string): AnalyticsActivity {
  const normalized = department.trim().toLocaleLowerCase();
  const users = normalized
    ? DEMO_USERS.filter((user) => user.department?.toLocaleLowerCase() === normalized)
    : DEMO_USERS;
  const active = users.filter((user) => user.status === 'ACTIVE').length;
  return {
    scope: 'COMPANY',
    department: department.trim() || null,
    usersTotal: users.length,
    usersActive: active,
    usersBlocked: users.filter((user) => user.status === 'BLOCKED').length,
    usersDisabled: users.filter((user) => user.status === 'DISABLED').length,
    loginsLast24h: users.length ? Math.max(1, Math.ceil(active / 2)) : 0,
    loginsLast7d: users.length ? Math.max(1, active * 3) : 0,
    generatedAt: new Date().toISOString(),
  };
}

export default function AnalyticsTab() {
  const { locale, t } = useI18n();
  const { user, demoMode, has } = useSession();
  const companyScope = has('ANALYTICS_VIEW_COMPANY');
  const [departmentInput, setDepartmentInput] = useState('');
  const [department, setDepartment] = useState('');
  const [data, setData] = useState<AnalyticsActivity | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const loadAnalytics = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const result = demoMode
        ? demoAnalytics(companyScope ? department : '')
        : await adminApi.analyticsActivity(companyScope ? department : undefined);
      setData(result);
    } catch (requestError) {
      setError(errorText(requestError, locale));
    } finally {
      setLoading(false);
    }
  }, [companyScope, demoMode, department, locale]);

  useEffect(() => {
    void loadAnalytics();
  }, [loadAnalytics]);

  function applyDepartment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const next = departmentInput.trim();
    if (next === department) {
      void loadAnalytics();
    } else {
      setDepartment(next);
    }
  }

  function clearDepartment() {
    setDepartmentInput('');
    if (department) {
      setDepartment('');
    } else {
      void loadAnalytics();
    }
  }

  const logins24h = displayMetric(data?.loginsLast24h ?? null);
  const logins7d = displayMetric(data?.loginsLast7d ?? null);
  const metric = (value: number | null) => value === null ? '—' : formatNumber(locale, value);
  const activePercent = data ? sharePercent(data.usersActive, data.usersTotal) : 0;
  const blockedPercent = data ? sharePercent(data.usersBlocked, data.usersTotal) : 0;
  const disabledPercent = data ? sharePercent(data.usersDisabled, data.usersTotal) : 0;

  return (
    <section className="panel animate-rise-in overflow-hidden" aria-labelledby="analytics-title">
      <header className="border-b border-line px-5 py-5 sm:px-6">
        <div className="flex flex-wrap items-start gap-3">
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-accent-soft text-accent-dark"><BarChart3 size={19} strokeWidth={1.8} /></span>
          <div className="min-w-0 flex-1">
            <h2 id="analytics-title" className="text-[20px] font-semibold tracking-[-0.025em]">{t('Аналитика активности')}</h2>
            <p className="mt-1 max-w-2xl text-sm leading-relaxed text-muted">{t('Сотрудники и события входа. Содержимое писем и сообщений не используется.')}</p>
          </div>
          <button type="button" className="btn-secondary" onClick={() => void loadAnalytics()} disabled={loading}>
            <RefreshCw size={15} className={loading ? 'animate-spin' : ''} />
            {loading ? t('Обновляем…') : t('Обновить')}
          </button>
        </div>

        <div className="mt-5">
          {companyScope ? (
            <form onSubmit={applyDepartment} className="flex flex-col gap-2 sm:flex-row sm:items-end">
              <div className="min-w-0 flex-1 sm:max-w-sm">
                <label className="field-label" htmlFor="analytics-department">{t('Подразделение')}</label>
                <input
                  id="analytics-department"
                  className="field"
                  value={departmentInput}
                  onChange={(event) => setDepartmentInput(event.target.value)}
                  maxLength={120}
                  placeholder={t('Вся компания')}
                  autoComplete="organization"
                />
              </div>
              <button type="submit" className="btn-primary" disabled={loading}>{t('Применить')}</button>
              {(departmentInput || department) && <button type="button" className="btn-ghost" onClick={clearDepartment} disabled={loading}>{t('Сбросить')}</button>}
            </form>
          ) : (
            <div className="flex items-center gap-2 text-sm text-muted"><Building2 size={16} className="text-accent" /><span>{t('Область:')} <b className="font-medium text-ink-soft">{data?.department || user?.department || t('Подразделение не задано')}</b></span></div>
          )}
        </div>
      </header>

      <div className="px-5 py-5 sm:px-6 sm:py-6">
        {error && <Notice tone="error" className="mb-5">{error}</Notice>}
        {!data && loading && <p className="text-sm text-muted" role="status">{t('Собираем аналитику…')}</p>}
        {!data && !loading && !error && <p className="text-sm text-muted">{t('Данные аналитики пока недоступны.')}</p>}

        {data && (
          <>
            <div className="mb-5 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-muted">
              <span className="chip chip-accent">{data.scope === 'COMPANY' ? t('Вся компания') : t('Своё подразделение')}</span>
              {data.department && <span>{t('Подразделение: {department}', { department: data.department })}</span>}
              <span>{t('Сформировано: {time}', { time: formatDateTime(locale, data.generatedAt) })}</span>
            </div>

            <div className="grid gap-3 sm:grid-cols-3" aria-live="polite">
              <div className="rounded-xl bg-chrome px-4 py-4">
                <p className="text-xs font-medium text-muted">{t('Сотрудников всего')}</p>
                <p className="mt-1.5 text-3xl font-semibold tracking-[-0.04em] text-ink">{formatNumber(locale, data.usersTotal)}</p>
              </div>
              <div className="rounded-xl bg-chrome px-4 py-4">
                <p className="text-xs font-medium text-muted">{t('Активные учётные записи')}</p>
                <p className="mt-1.5 text-3xl font-semibold tracking-[-0.04em] text-emerald-700">{formatNumber(locale, data.usersActive)}</p>
                <p className="mt-1 text-xs text-faint">{t('{percent}% от общего числа', { percent: activePercent })}</p>
              </div>
              <div className="rounded-xl bg-chrome px-4 py-4">
                <p className="text-xs font-medium text-muted">{t('Входы за 7 дней')}</p>
                <p className="mt-1.5 text-3xl font-semibold tracking-[-0.04em] text-ink">{metric(logins7d)}</p>
              </div>
            </div>

            {data.usersTotal === 0 && <Notice className="mt-5">{t('В выбранной области нет учётных записей. Проверьте фильтр подразделения.')}</Notice>}

            <div className="mt-7 grid gap-7 border-t border-line pt-6 lg:grid-cols-2">
              <div>
                <div className="mb-4 flex items-center gap-2"><Users size={17} className="text-accent" /><h3 className="text-sm font-semibold text-ink">{t('Учётные записи по статусам')}</h3></div>
                <div className="flex h-2.5 overflow-hidden rounded-full bg-chrome" role="img" aria-label={t('Доли активных, заблокированных и отключённых учётных записей')}>
                  <span className="bg-emerald-500" style={{ width: `${activePercent}%` }} />
                  <span className="bg-amber-500" style={{ width: `${blockedPercent}%` }} />
                  <span className="bg-zinc-400" style={{ width: `${disabledPercent}%` }} />
                </div>
                <dl className="mt-4 space-y-3">
                  <div className="flex items-center gap-3 text-sm"><dt className="flex min-w-0 flex-1 items-center gap-2 text-muted"><span className="h-2 w-2 rounded-full bg-emerald-500" />{t('Активные')}</dt><dd className="font-medium text-ink">{formatNumber(locale, data.usersActive)}</dd></div>
                  <div className="flex items-center gap-3 text-sm"><dt className="flex min-w-0 flex-1 items-center gap-2 text-muted"><span className="h-2 w-2 rounded-full bg-amber-500" />{t('Заблокированные')}</dt><dd className="font-medium text-ink">{formatNumber(locale, data.usersBlocked)}</dd></div>
                  <div className="flex items-center gap-3 text-sm"><dt className="flex min-w-0 flex-1 items-center gap-2 text-muted"><span className="h-2 w-2 rounded-full bg-zinc-400" />{t('Отключённые')}</dt><dd className="font-medium text-ink">{formatNumber(locale, data.usersDisabled)}</dd></div>
                </dl>
              </div>

              <div className="border-t border-line pt-6 lg:border-l lg:border-t-0 lg:pl-7 lg:pt-0">
                <div className="mb-4 flex items-center gap-2"><BarChart3 size={17} className="text-accent" /><h3 className="text-sm font-semibold text-ink">{t('События входа')}</h3></div>
                <dl className="space-y-4">
                  <div className="flex items-end justify-between gap-4 rounded-xl border border-line px-4 py-3"><dt className="text-sm text-muted">{t('За последние 24 часа')}</dt><dd className="text-2xl font-semibold tracking-[-0.03em] text-ink">{metric(logins24h)}</dd></div>
                  <div className="flex items-end justify-between gap-4 rounded-xl border border-line px-4 py-3"><dt className="text-sm text-muted">{t('За последние 7 дней')}</dt><dd className="text-2xl font-semibold tracking-[-0.03em] text-ink">{metric(logins7d)}</dd></div>
                </dl>
                {(logins24h === null || logins7d === null) && <p className="mt-3 text-xs leading-relaxed text-faint">{t('Прочерк означает, что статистика событий входа сейчас недоступна; это не нулевое значение.')}</p>}
              </div>
            </div>
          </>
        )}
      </div>
    </section>
  );
}
