import { FormEvent, useEffect, useMemo, useState } from 'react';
import { Edit3, HardDrive, Loader2, Search, Users, X } from 'lucide-react';
import { adminApi, type AdminUser } from '../../api/adminApi';
import { errorText } from '../../api/authApi';
import { demoUsers } from '../../api/demoData';
import { Avatar, EmptyState, Notice } from '../../components/ui';
import { countLabel, formatBytes, useI18n } from '../../i18n';
import { quotaBytesToGigabytes, quotaGigabytesToBytes, quotaUsagePercent } from './quota';

const PAGE_SIZE = 25;

export default function QuotasTab({ demoMode = false }: { demoMode?: boolean }) {
  const { t, locale } = useI18n();
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(!demoMode);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [quotaInput, setQuotaInput] = useState('0');
  const [savingId, setSavingId] = useState<string | null>(null);

  async function load(targetPage = 0) {
    setLoading(true);
    setError('');
    try {
      const result = await adminApi.listUsers({ q: query.trim(), page: targetPage, size: PAGE_SIZE });
      setUsers(result.content);
      setPage(result.page);
      setTotalPages(Math.max(1, result.totalPages));
      setTotal(result.totalElements);
    } catch (err) {
      setError(errorText(err, locale));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    setEditingId(null);
    setNotice('');
    setError('');
    if (demoMode) {
      const demo = demoUsers(locale);
      setUsers(demo);
      setTotal(demo.length);
      setTotalPages(1);
      setPage(0);
      setLoading(false);
    } else {
      void load(0);
    }
    // locale обновляет demo-записи; для API вызывает безопасное повторное чтение.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [demoMode, locale]);

  const displayedUsers = useMemo(() => {
    if (!demoMode || !query.trim()) return users;
    const needle = query.trim().toLocaleLowerCase();
    return users.filter((user) => [user.displayName, user.username, user.email, user.department ?? '']
      .some((value) => value.toLocaleLowerCase().includes(needle)));
  }, [demoMode, query, users]);

  function onSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!demoMode) void load(0);
  }

  function startEditing(user: AdminUser) {
    setEditingId(user.id);
    setQuotaInput(quotaBytesToGigabytes(user.quotaBytes));
    setError('');
    setNotice('');
  }

  async function saveQuota(user: AdminUser) {
    const quotaBytes = quotaGigabytesToBytes(quotaInput);
    if (quotaBytes === null) {
      setError(t('Введите неотрицательное число гигабайт. Для безлимита укажите 0.'));
      return;
    }
    setSavingId(user.id);
    setError('');
    setNotice('');
    try {
      const updated = demoMode ? { ...user, quotaBytes } : await adminApi.updateQuota(user.id, quotaBytes);
      setUsers((current) => current.map((item) => item.id === user.id ? updated : item));
      setEditingId(null);
      setNotice(t('Квота для {name} сохранена: {quota}.', {
        name: user.displayName,
        quota: quotaBytes === 0 ? t('без лимита') : formatBytes(locale, quotaBytes),
      }));
    } catch (err) {
      setError(errorText(err, locale));
    } finally {
      setSavingId(null);
    }
  }

  return (
    <section className="panel overflow-hidden">
      <header className="flex flex-col gap-4 border-b border-line px-5 py-4 lg:flex-row lg:items-center lg:justify-between">
        <div className="flex items-start gap-3">
          <span className="mt-0.5 flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-accent-soft text-accent"><HardDrive size={20} /></span>
          <div>
            <h2 className="text-base font-semibold text-ink">{t('Дисковые квоты')}</h2>
            <p className="mt-1 max-w-2xl text-xs leading-5 text-muted">{t('Лимит применяется к новым письмам и копиям Sent сразу после сохранения. Существующие письма не удаляются. 0 — без лимита.')}</p>
          </div>
        </div>
        <form onSubmit={onSearch} className="flex gap-2">
          <label className="relative min-w-0 flex-1 lg:w-72 lg:flex-none">
            <span className="sr-only">{t('Поиск пользователя')}</span>
            <Search size={15} className="pointer-events-none absolute left-3 top-3 text-faint" />
            <input value={query} onChange={(event) => setQuery(event.target.value)} className="field h-10 py-0 pl-9" placeholder={t('Имя, логин или email')} />
          </label>
          {!demoMode && <button className="btn-secondary h-10" disabled={loading}>{t('Найти')}</button>}
        </form>
      </header>

      {(error || notice) && <div className="space-y-2 px-5 pt-4" aria-live="polite">{error && <Notice tone="error">{error}</Notice>}{notice && <Notice tone="success">{notice}</Notice>}</div>}

      <div className="overflow-x-auto">
        <table className="w-full min-w-[760px] border-collapse text-sm">
          <thead>
            <tr className="bg-chrome/80 text-left text-[11px] uppercase tracking-[0.07em] text-faint">
              <th className="px-5 py-3 font-semibold">{t('Пользователь')}</th>
              <th className="px-4 py-3 font-semibold">{t('Отдел')}</th>
              <th className="px-4 py-3 font-semibold">{t('Занято')}</th>
              <th className="min-w-[210px] px-4 py-3 font-semibold">{t('Лимит и использование')}</th>
              <th className="px-5 py-3 text-right font-semibold">{t('Действия')}</th>
            </tr>
          </thead>
          <tbody>
            {displayedUsers.map((user) => {
              const usage = quotaUsagePercent(user.usedBytes, user.quotaBytes);
              const editing = editingId === user.id;
              const pendingBytes = editing ? quotaGigabytesToBytes(quotaInput) : null;
              const barClass = usage !== null && usage > 100 ? 'bg-danger' : usage !== null && usage >= 85 ? 'bg-amber-500' : 'bg-accent';
              return (
                <tr key={user.id} className="border-t border-line align-top transition hover:bg-chrome/60">
                  <td className="px-5 py-4">
                    <div className="flex items-center gap-3">
                      <Avatar name={user.displayName} size={38} />
                      <div className="min-w-0"><div className="truncate font-medium text-ink">{user.displayName}</div><div className="mt-0.5 max-w-[250px] truncate text-xs text-muted">{user.email}</div><div className="text-[11px] text-faint">{user.username}</div></div>
                    </div>
                  </td>
                  <td className="px-4 py-4 text-muted">{user.department || '—'}</td>
                  <td className="whitespace-nowrap px-4 py-4 text-ink-soft">{formatBytes(locale, user.usedBytes)}</td>
                  <td className="px-4 py-4">
                    {!editing ? <>
                      <div className="font-medium text-ink-soft">{user.quotaBytes === 0 ? t('Без лимита') : formatBytes(locale, user.quotaBytes)}</div>
                      {usage === null ? <div className="mt-1 text-xs text-faint">{t('Объём занятого места не ограничен')}</div> : <>
                        <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-black/[0.07]"><div className={`h-full rounded-full ${barClass}`} style={{ width: `${Math.min(100, usage)}%` }} /></div>
                        <div className={`mt-1 text-xs ${usage > 100 ? 'font-medium text-danger' : 'text-faint'}`}>{t('{used}% занято', { used: usage })}</div>
                      </>}
                    </> : <form onSubmit={(event) => { event.preventDefault(); void saveQuota(user); }} className="max-w-[310px] rounded-xl border border-accent/25 bg-surface p-3 shadow-soft">
                      <label htmlFor={`quota-${user.id}`} className="field-label">{t('Новый лимит, ГБ')}</label>
                      <input id={`quota-${user.id}`} value={quotaInput} onChange={(event) => setQuotaInput(event.target.value)} type="text" inputMode="decimal" autoFocus disabled={savingId === user.id} className="field h-9 py-0" aria-describedby={`quota-help-${user.id}`} />
                      <p id={`quota-help-${user.id}`} className="mt-1.5 text-xs text-faint">{t('0 — без лимита. Можно использовать дробное значение.')}</p>
                      {pendingBytes !== null && pendingBytes > 0 && pendingBytes < user.usedBytes && <p className="mt-2 text-xs leading-5 text-amber-700">{t('Лимит меньше занятого места: новые письма и Sent-копии не будут сохраняться, пока занятый объём превышает лимит.')}</p>}
                      <div className="mt-3 flex gap-2">
                        <button className="btn-primary h-8 px-3 text-xs" disabled={savingId !== null}>{savingId === user.id ? <Loader2 size={14} className="animate-spin" /> : null}{t('Сохранить')}</button>
                        <button type="button" className="btn-ghost h-8 px-3 text-xs" disabled={savingId !== null} onClick={() => setEditingId(null)}>{t('Отмена')}</button>
                      </div>
                    </form>}
                  </td>
                  <td className="px-5 py-4 text-right">
                    <button type="button" className="btn-secondary h-9 px-3 text-xs" disabled={savingId !== null} onClick={() => editing ? setEditingId(null) : startEditing(user)} aria-label={editing ? t('Закрыть редактор квоты для {name}', { name: user.displayName }) : t('Изменить квоту для {name}', { name: user.displayName })}>
                      {editing ? <X size={15} /> : <Edit3 size={15} />}{editing ? t('Закрыть') : t('Изменить')}
                    </button>
                  </td>
                </tr>
              );
            })}
            {!loading && displayedUsers.length === 0 && <tr><td colSpan={5}><EmptyState icon={Users} title={t('Пользователи не найдены')} description={t('Измените поисковый запрос.')} /></td></tr>}
          </tbody>
        </table>
      </div>

      <footer className="flex min-h-14 flex-wrap items-center justify-between gap-3 border-t border-line px-5 py-3 text-xs text-muted">
        <span>{countLabel(locale, demoMode ? displayedUsers.length : total, ['пользователь', 'пользователя', 'пользователей'], ['user', 'users'])}{loading ? ` · ${t('Загрузка…')}` : ''}</span>
        {!demoMode && <div className="flex items-center gap-2"><button type="button" className="btn-ghost h-8" disabled={loading || page <= 0} onClick={() => void load(page - 1)}>{t('Назад')}</button><span>{page + 1} / {totalPages}</span><button type="button" className="btn-ghost h-8" disabled={loading || page + 1 >= totalPages} onClick={() => void load(page + 1)}>{t('Вперёд')}</button></div>}
      </footer>
    </section>
  );
}
