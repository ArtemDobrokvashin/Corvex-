import { FormEvent, useEffect, useState } from 'react';
import { ChevronLeft, ChevronRight, ScrollText, Search } from 'lucide-react';
import { adminApi, type AuditEntry, type AuditQuery } from '../../api/adminApi';
import { errorText } from '../../api/authApi';
import { DEMO_AUDIT } from '../../api/demoData';
import { Avatar, EmptyState, Notice } from '../../components/ui';
import { countLabel, formatDateTime, useI18n } from '../../i18n';

type AuditFilters = {
  actorId: string;
  action: string;
  from: string;
  to: string;
};

const EMPTY_FILTERS: AuditFilters = { actorId: '', action: '', from: '', to: '' };
const PAGE_SIZE = 50;

function toQuery(filters: AuditFilters, page: number): AuditQuery {
  return {
    actorId: filters.actorId.trim() || undefined,
    action: filters.action.trim() || undefined,
    from: filters.from ? new Date(filters.from).toISOString() : undefined,
    to: filters.to ? new Date(filters.to).toISOString() : undefined,
    page,
    size: PAGE_SIZE,
  };
}

export default function AuditTab({ demoMode }: { demoMode: boolean }) {
  const { locale, t } = useI18n();
  const [entries, setEntries] = useState<AuditEntry[]>(demoMode ? DEMO_AUDIT : []);
  const [filters, setFilters] = useState<AuditFilters>(EMPTY_FILTERS);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(demoMode ? 1 : 0);
  const [total, setTotal] = useState(demoMode ? DEMO_AUDIT.length : 0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  async function load(requestedPage: number, requestedFilters: AuditFilters = filters) {
    setLoading(true);
    setError('');
    try {
      if (demoMode) {
        const from = requestedFilters.from ? new Date(requestedFilters.from).getTime() : null;
        const to = requestedFilters.to ? new Date(requestedFilters.to).getTime() : null;
        const filtered = DEMO_AUDIT.filter((entry) => {
          const created = new Date(entry.createdAt).getTime();
          return (!requestedFilters.actorId.trim() || entry.actorId === requestedFilters.actorId.trim())
            && (!requestedFilters.action.trim() || entry.action === requestedFilters.action.trim())
            && (from === null || created >= from)
            && (to === null || created <= to);
        });
        setEntries(filtered);
        setTotal(filtered.length);
        setTotalPages(filtered.length > 0 ? 1 : 0);
        setPage(0);
      } else {
        const result = await adminApi.listAudit(toQuery(requestedFilters, requestedPage));
        setEntries(result.content);
        setTotal(result.totalElements);
        setTotalPages(result.totalPages);
        setPage(result.page);
      }
    } catch (loadError) {
      setError(errorText(loadError, locale));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load(0, EMPTY_FILTERS);
    // Фильтры применяются явной отправкой формы; смена demoMode перезагружает журнал.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [demoMode]);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void load(0);
  }

  function reset() {
    setFilters(EMPTY_FILTERS);
    void load(0, EMPTY_FILTERS);
  }

  return (
    <section className="panel animate-rise-in overflow-hidden" aria-labelledby="audit-log-title">
      <header className="border-b border-line px-5 py-5 sm:px-6">
        <div className="flex flex-wrap items-center gap-2">
          <h2 id="audit-log-title" className="mr-auto text-[17px] font-semibold tracking-[-0.02em]">{t('Журнал аудита')}</h2>
          <span className="chip">{countLabel(locale, total, ['запись', 'записи', 'записей'], ['entry', 'entries'])}</span>
        </div>
        <p className="mt-1 text-sm text-muted">{t('Административные события: кто, когда и что изменил. Поддерживаются серверные фильтры и постраничный просмотр.')}</p>

        <form onSubmit={submit} className="mt-4 grid items-end gap-3 sm:grid-cols-2 xl:grid-cols-4">
          <label className="block">
            <span className="field-label">{t('UUID инициатора')}</span>
            <input value={filters.actorId} onChange={(event) => setFilters((current) => ({ ...current, actorId: event.target.value }))} className="field h-10 py-0 font-mono text-xs" placeholder="11111111-1111-…" />
          </label>
          <label className="block">
            <span className="field-label">{t('Действие')}</span>
            <input value={filters.action} onChange={(event) => setFilters((current) => ({ ...current, action: event.target.value }))} className="field h-10 py-0 font-mono text-xs" placeholder="USER_UPDATED" />
          </label>
          <label className="block">
            <span className="field-label">{t('С даты и времени')}</span>
            <input type="datetime-local" value={filters.from} onChange={(event) => setFilters((current) => ({ ...current, from: event.target.value }))} className="field h-10 py-0" />
          </label>
          <label className="block">
            <span className="field-label">{t('По дату и время')}</span>
            <input type="datetime-local" value={filters.to} onChange={(event) => setFilters((current) => ({ ...current, to: event.target.value }))} className="field h-10 py-0" />
          </label>
          <div className="flex flex-wrap gap-2 sm:col-span-2 xl:col-span-4">
            <button type="submit" className="btn-secondary h-10" disabled={loading}><Search size={15} />{loading ? t('Загружаем…') : t('Применить фильтры')}</button>
            <button type="button" className="btn-ghost h-10" onClick={reset} disabled={loading}>{t('Сбросить')}</button>
          </div>
        </form>
      </header>

      {error && <div className="px-5 pt-4 sm:px-6"><Notice tone="error">{error}</Notice></div>}
      <div className="overflow-x-auto">
        <table className="w-full min-w-[760px] border-collapse text-sm">
          <thead>
            <tr className="bg-chrome/80 text-left text-[11px] uppercase tracking-[0.07em] text-faint">
              <th className="px-5 py-3 font-semibold">{t('Время')}</th><th className="px-4 py-3 font-semibold">{t('Инициатор')}</th><th className="px-4 py-3 font-semibold">{t('Действие')}</th>
              <th className="px-4 py-3 font-semibold">{t('Объект')}</th><th className="px-4 py-3 font-semibold">{t('Детали')}</th><th className="px-4 py-3 font-semibold">IP</th>
            </tr>
          </thead>
          <tbody>
            {entries.map((entry) => (
              <tr key={entry.id} className="border-t border-line align-top transition hover:bg-chrome/70">
                <td className="whitespace-nowrap px-5 py-4 text-muted">{formatDateTime(locale, entry.createdAt)}</td>
                <td className="px-4 py-4"><div className="flex items-center gap-2.5"><Avatar name={entry.actorName ?? 'system'} size={30} /><span className="font-medium text-ink-soft">{entry.actorName ?? t('Система')}</span></div></td>
                <td className="px-4 py-4"><span className="chip font-mono text-[10px]">{entry.action}</span></td>
                <td className="max-w-[180px] truncate px-4 py-4 text-muted" title={entry.entityId ?? undefined}>{entry.entity ?? '—'}{entry.entityId ? <span className="block truncate text-[11px] text-faint">{entry.entityId}</span> : null}</td>
                <td className="max-w-[260px] truncate px-4 py-4 font-mono text-[11px] text-muted" title={JSON.stringify(entry.details)}>{Object.keys(entry.details ?? {}).length ? JSON.stringify(entry.details) : '—'}</td>
                <td className="px-4 py-4 text-muted">{entry.ipAddress ?? '—'}</td>
              </tr>
            ))}
            {entries.length === 0 && <tr><td colSpan={6}><EmptyState icon={ScrollText} title={t('Записи не найдены')} description={t('Измените фильтры или выполните административное действие.')} /></td></tr>}
          </tbody>
        </table>
      </div>

      {totalPages > 1 && (
        <footer className="flex items-center justify-end gap-2 border-t border-line px-5 py-3 text-sm text-muted sm:px-6">
          <button type="button" disabled={loading || page === 0} onClick={() => void load(page - 1)} className="btn-ghost h-8 px-2"><ChevronLeft size={15} />{t('Назад')}</button>
          <span>{t('{current} из {total}', { current: page + 1, total: totalPages })}</span>
          <button type="button" disabled={loading || page + 1 >= totalPages} onClick={() => void load(page + 1)} className="btn-ghost h-8 px-2">{t('Вперёд')}<ChevronRight size={15} /></button>
        </footer>
      )}
    </section>
  );
}
