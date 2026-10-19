import { Dispatch, FormEvent, SetStateAction, useEffect, useMemo, useState } from 'react';
import {
  Activity,
  Ban,
  Check,
  ChevronLeft,
  ChevronRight,
  HardDrive,
  KeyRound,
  Lock,
  Plus,
  Power,
  Save,
  ScrollText,
  Search,
  ShieldCheck,
  UserPlus,
  Users,
} from 'lucide-react';
import { AdminGroup, AdminUser, AuditEntry, SystemHealth, adminApi } from '../../api/adminApi';
import { errorText } from '../../api/authApi';
import { DEMO_AUDIT, demoGroups, demoUsers, permissionLabels } from '../../api/demoData';
import { Avatar, EmptyState, Notice } from '../../components/ui';
import { countLabel, formatBytes, formatDateTime, systemGroupName, useI18n } from '../../i18n';
import { useSession } from '../../store/sessionStore';

type Tab = 'users' | 'groups' | 'audit';

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


const STATUS_META: Record<string, { label: string; dot: string; className: string }> = {
  ACTIVE: { label: 'Активен', dot: 'bg-emerald-500', className: 'bg-emerald-50 text-emerald-800' },
  BLOCKED: { label: 'Заблокирован', dot: 'bg-red-500', className: 'bg-red-50 text-red-800' },
  DISABLED: { label: 'Отключён', dot: 'bg-zinc-400', className: 'bg-zinc-100 text-zinc-600' },
};

function StatusPill({ status }: { status: string }) {
  const { t } = useI18n();
  const meta = STATUS_META[status] ?? { label: status, dot: 'bg-zinc-400', className: 'bg-zinc-100 text-zinc-600' };
  return <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium ${meta.className}`}><span className={`h-1.5 w-1.5 rounded-full ${meta.dot}`} />{t(meta.label)}</span>;
}

/** Администрирование Quiet Work: пользователи, группы и аудит в паттерне современных настроек. */
export default function AdminPage() {
  const { locale, t } = useI18n();
  const { has, demoMode } = useSession();
  const [tab, setTab] = useState<Tab>(has('ADMIN_USERS_MANAGE') ? 'users' : 'groups');
  const [groups, setGroups] = useState<AdminGroup[]>(demoMode ? demoGroups(locale) : []);
  const [groupsError, setGroupsError] = useState('');

  useEffect(() => {
    if (demoMode) {
      const localized = demoGroups(locale);
      setGroups((prev) => (prev.length ? prev.map((group) => {
        const translated = localized.find((candidate) => candidate.code === group.code);
        return translated ? { ...group, name: translated.name } : group;
      }) : localized));
      return;
    }
    adminApi.listGroups().then(setGroups).catch((e) => setGroupsError(errorText(e, locale)));
  }, [demoMode, locale]);

  const navBtn = (t: Tab, label: string, Icon: typeof Users, description: string) => (
    <button key={t} type="button" onClick={() => setTab(t)} className={`nav-row shrink-0 lg:min-h-[52px] ${tab === t ? 'nav-row-active' : ''}`}>
      <Icon size={17} strokeWidth={1.8} />
      <span className="min-w-0">
        <span className="block truncate">{label}</span>
        <span className="hidden truncate text-[11px] font-normal text-faint lg:block">{description}</span>
      </span>
    </button>
  );

  return (
    <div className="scroll-area h-full bg-canvas">
      <div className="mx-auto w-full max-w-[1260px] px-4 py-6 sm:px-6 lg:px-8 lg:py-8">
        <header className="mb-7 flex flex-wrap items-end justify-between gap-4">
          <div>
            <p className="text-xs font-semibold uppercase tracking-[0.12em] text-faint">{t('Организация')}</p>
            <h1 className="mt-2 text-[29px] font-semibold tracking-[-0.03em] text-ink">{t('Администрирование')}</h1>
            <p className="mt-1 max-w-xl text-sm leading-relaxed text-muted">{t('Люди, доступы и история изменений. Опасные действия всегда подтверждаются и попадают в аудит.')}</p>
          </div>
          <span className="chip bg-surface"><span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />{countLabel(locale, groups.length, ['группа доступа', 'группы доступа', 'групп доступа'], ['access group', 'access groups'])}</span>
        </header>

        <div className="grid items-start gap-6 lg:grid-cols-[226px_minmax(0,1fr)]">
          <aside className="lg:sticky lg:top-8">
            <nav className="flex gap-1 overflow-x-auto pb-1 lg:flex-col lg:overflow-visible lg:pb-0" aria-label={t('Разделы администрирования')}>
              {has('ADMIN_USERS_MANAGE') && navBtn('users', t('Пользователи'), Users, t('Учётные записи и статусы'))}
              {(has('ADMIN_GROUPS_MANAGE') || has('ADMIN_USERS_MANAGE')) && navBtn('groups', t('Группы и права'), ShieldCheck, t('Кто что может делать'))}
              {has('AUDIT_VIEW') && navBtn('audit', t('Аудит'), ScrollText, t('История административных действий'))}
            </nav>
            <div className="mt-6 hidden rounded-xl border border-line bg-chrome p-4 text-[13px] leading-relaxed text-muted lg:block">
              <div className="mb-1.5 flex items-center gap-2 font-medium text-ink-soft"><ShieldCheck size={15} className="text-accent" />{t('Как действуют права')}</div>
              {t('Итоговый доступ человека — объединение прав всех его групп. Новые права попадут в токены при обновлении сессии, не позднее 15 минут.')}
            </div>
          </aside>

          <div className="min-w-0">
            {groupsError && <Notice tone="error" className="mb-4">{t('Не удалось загрузить группы: {error}', { error: groupsError })}</Notice>}
            {tab === 'users' && <UsersTab groups={groups} demoMode={demoMode} />}
            {tab === 'groups' && <GroupsTab groups={groups} setGroups={setGroups} demoMode={demoMode} canManage={has('ADMIN_GROUPS_MANAGE')} />}
            {tab === 'audit' && <AuditTab demoMode={demoMode} />}
          </div>
        </div>
      </div>
    </div>
  );
}

// ================= Пользователи =================

function UsersTab({ groups, demoMode }: { groups: AdminGroup[]; demoMode: boolean }) {
  const { locale, t } = useI18n();
  const { has } = useSession();
  const fmtDate = (value: string | null) => (value ? formatDateTime(locale, value) : '—');
  const groupName = (group: AdminGroup) => systemGroupName(locale, group.code, group.name);
  const canManageGroups = has('ADMIN_GROUPS_MANAGE') || has('ADMIN_USERS_MANAGE');
  const [users, setUsers] = useState<AdminUser[]>(demoMode ? (demoUsers(locale) as AdminUser[]) : []);
  const [total, setTotal] = useState(demoMode ? demoUsers(locale).length : 0);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [q, setQ] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [createGroups, setCreateGroups] = useState<string[]>(['USER']);
  const [editingGroupsFor, setEditingGroupsFor] = useState<string | null>(null);
  const [selectedGroups, setSelectedGroups] = useState<string[]>([]);

  async function load(p = page) {
    if (demoMode) return;
    try {
      const res = await adminApi.listUsers({ q, status: statusFilter || undefined, page: p, size: 20 });
      setUsers(res.content);
      setTotal(res.totalElements);
      setTotalPages(res.totalPages);
      setPage(res.page);
    } catch (e) {
      setError(errorText(e, locale));
    }
  }

  useEffect(() => {
    if (!demoMode) return;
    const localized = demoUsers(locale);
    setUsers((prev) => prev.map((user) => {
      const translated = localized.find((candidate) => candidate.id === user.id);
      return translated ? { ...user, displayName: translated.displayName, department: translated.department } : user;
    }));
  }, [demoMode, locale]);

  useEffect(() => {
    load(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [demoMode]);

  const displayedUsers = useMemo(() => {
    if (!demoMode) return users;
    const query = q.trim().toLowerCase();
    return users.filter((u) => (!statusFilter || u.status === statusFilter) && (!query || `${u.displayName} ${u.username} ${u.email} ${u.department ?? ''}`.toLowerCase().includes(query)));
  }, [demoMode, q, statusFilter, users]);

  const patchLocal = (u: AdminUser) => setUsers((prev) => prev.map((x) => (x.id === u.id ? u : x)));
  const toggleList = (setList: Dispatch<SetStateAction<string[]>>, code: string) => setList((prev) => prev.includes(code) ? prev.filter((c) => c !== code) : [...prev, code]);

  async function onCreate(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const d = new FormData(e.currentTarget);
    setError('');
    setNotice('');
    const body = {
      username: String(d.get('username') ?? ''),
      email: String(d.get('email') ?? ''),
      displayName: String(d.get('displayName') ?? ''),
      tempPassword: String(d.get('tempPassword') ?? ''),
      department: String(d.get('department') ?? '') || undefined,
      groupCodes: createGroups,
    };
    try {
      if (demoMode) {
        const nu: AdminUser = {
          id: `demo-${Date.now()}`, username: body.username, email: body.email, displayName: body.displayName,
          source: 'LOCAL', status: 'ACTIVE', department: body.department ?? null, groups: createGroups.length ? createGroups : ['USER'],
          quotaBytes: 5368709120, usedBytes: 0, mustChangePassword: true, totpEnabled: false,
          lastLoginAt: null, createdAt: new Date().toISOString(),
        };
        setUsers((prev) => [nu, ...prev]);
        setTotal((t) => t + 1);
      } else {
        await adminApi.createUser(body);
        await load(0);
      }
      setShowCreate(false);
      setNotice(t('Пользователь создан. Временный пароль он сменит при первом входе.'));
    } catch (err) {
      setError(errorText(err, locale));
    }
  }

  async function toggleBlock(u: AdminUser) {
    setError('');
    try {
      if (demoMode) {
        patchLocal({ ...u, status: u.status === 'ACTIVE' ? 'BLOCKED' : 'ACTIVE' });
      } else if (u.status === 'ACTIVE') {
        await adminApi.blockUser(u.id);
        patchLocal({ ...u, status: 'BLOCKED' });
      } else {
        patchLocal(await adminApi.patchUser(u.id, { status: 'ACTIVE' }));
      }
    } catch (err) {
      setError(errorText(err, locale));
    }
  }

  async function disable(u: AdminUser) {
    if (!window.confirm(t('Отключить пользователя {name} ({username})? Сессии будут отозваны, группы сняты.', { name: u.displayName, username: u.username }))) return;
    setError('');
    try {
      if (demoMode) {
        patchLocal({ ...u, status: 'DISABLED', groups: [] });
      } else {
        await adminApi.disableUser(u.id);
        patchLocal({ ...u, status: 'DISABLED', groups: [] });
      }
    } catch (err) {
      setError(errorText(err, locale));
    }
  }

  async function saveUserGroups(u: AdminUser) {
    setError('');
    try {
      if (demoMode) {
        patchLocal({ ...u, groups: selectedGroups });
      } else {
        patchLocal(await adminApi.setUserGroups(u.id, selectedGroups));
      }
      setEditingGroupsFor(null);
      setNotice(t('Группы пользователя {username} обновлены.', { username: u.username }));
    } catch (err) {
      setError(errorText(err, locale));
    }
  }

  const groupPicker = (selected: string[], toggle: (code: string) => void) => (
    <div className="flex flex-wrap gap-1.5">
      {groups.map((g) => {
        const active = selected.includes(g.code);
        return (
          <button key={g.code} type="button" aria-pressed={active} onClick={() => toggle(g.code)} className={`chip min-h-8 transition ${active ? 'chip-accent border-accent/30' : 'bg-surface hover:border-accent/25 hover:text-ink'}`}>
            {active && <Check size={12} />}{groupName(g)}
          </button>
        );
      })}
    </div>
  );

  return (
    <section className="panel animate-rise-in overflow-hidden">
      <header className="flex flex-wrap items-center gap-3 border-b border-line px-5 py-4">
        <div className="mr-auto min-w-[180px]">
          <h2 className="text-[17px] font-semibold tracking-[-0.02em]">{t('Пользователи')} <span className="ml-1 text-sm font-normal text-faint">{demoMode ? displayedUsers.length : total}</span></h2>
          <p className="mt-0.5 text-[13px] text-muted">{t('Учётные записи сотрудников и сервисных администраторов')}</p>
        </div>
        <form onSubmit={(e) => { e.preventDefault(); load(0); }} className="flex flex-wrap items-center gap-2">
          <div className="relative">
            <Search size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-faint" />
            <input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('Поиск людей…')} className="search-field h-10 w-[220px] pl-9 pr-3" />
          </div>
          <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} className="field h-10 w-[168px] py-0" aria-label={t('Фильтр по статусу')}>
            <option value="">{t('Все статусы')}</option>
            <option value="ACTIVE">{t('Активные')}</option>
            <option value="BLOCKED">{t('Заблокированные')}</option>
            <option value="DISABLED">{t('Отключённые')}</option>
          </select>
          {!demoMode && <button className="btn-secondary h-10">{t('Найти')}</button>}
        </form>
        <button type="button" onClick={() => setShowCreate(!showCreate)} className="btn-primary h-10"><UserPlus size={16} /><span className="hidden sm:inline">{t('Новый пользователь')}</span></button>
      </header>

      {(error || notice) && <div className="space-y-2 px-5 pt-4">{error && <Notice tone="error">{error}</Notice>}{notice && <Notice tone="success">{notice}</Notice>}</div>}

      {showCreate && (
        <form onSubmit={onCreate} className="animate-rise-in border-b border-line bg-chrome px-5 py-5">
          <div className="mb-4 flex items-center gap-2 font-medium"><UserPlus size={17} className="text-accent" />{t('Новая учётная запись')}</div>
          <div className="grid gap-4 sm:grid-cols-2">
            <div><label className="field-label" htmlFor="new-username">{t('Логин')}</label><input id="new-username" name="username" required placeholder="i.ivanov" className="field" /></div>
            <div><label className="field-label" htmlFor="new-email">E-mail</label><input id="new-email" name="email" required type="email" placeholder="Ivanov@company.local" className="field" /></div>
            <div><label className="field-label" htmlFor="new-name">{t('Имя и фамилия')}</label><input id="new-name" name="displayName" required placeholder={t('Ivanov')} className="field" /></div>
            <div><label className="field-label" htmlFor="new-department">{t('Подразделение')}</label><input id="new-department" name="department" placeholder={t('Бухгалтерия')} className="field" /></div>
            <div className="sm:col-span-2"><label className="field-label" htmlFor="new-password">{t('Временный пароль')}</label><input id="new-password" name="tempPassword" required type="password" autoComplete="off" placeholder={t('Не короче 10 символов, буквы и цифры')} className="field" /><p className="mt-1.5 text-xs text-faint">{t('Пользователь обязательно сменит его при первом входе.')}</p></div>
            <div className="sm:col-span-2">
              <span className="field-label">{t('Группы доступа')}</span>
              {groupPicker(createGroups, (code) => toggleList(setCreateGroups, code))}
              {createGroups.length === 0 && <p className="mt-1.5 text-xs text-faint">{t('Если ничего не выбрать, сервер назначит группу «Обычный пользователь».')}</p>}
            </div>
          </div>
          <div className="mt-5 flex gap-2">
            <button className="btn-primary">{t('Создать пользователя')}</button>
            <button type="button" onClick={() => setShowCreate(false)} className="btn-ghost">{t('Отмена')}</button>
          </div>
        </form>
      )}

      <div className="overflow-x-auto">
        <table className="w-full min-w-[820px] border-collapse text-sm">
          <thead>
            <tr className="bg-chrome/80 text-left text-[11px] uppercase tracking-[0.07em] text-faint">
              <th className="px-5 py-3 font-semibold">{t('Пользователь')}</th><th className="px-4 py-3 font-semibold">{t('Отдел')}</th><th className="px-4 py-3 font-semibold">{t('Группы')}</th>
              <th className="px-4 py-3 font-semibold">{t('Статус')}</th><th className="px-4 py-3 font-semibold">{t('Ящик')}</th><th className="px-4 py-3 font-semibold">{t('Последний вход')}</th><th className="px-4 py-3 text-right font-semibold">{t('Действия')}</th>
            </tr>
          </thead>
          <tbody>
            {displayedUsers.map((u) => {
              const used = u.quotaBytes > 0 ? Math.min(100, Math.round((u.usedBytes / u.quotaBytes) * 100)) : 0;
              return (
                <tr key={u.id} className="border-t border-line align-top transition hover:bg-chrome/70">
                  <td className="px-5 py-4">
                    <div className="flex items-start gap-3">
                      <Avatar name={u.displayName} size={39} />
                      <div className="min-w-0">
                        <div className="flex items-center gap-1.5 font-medium text-ink">{u.displayName}{u.totpEnabled && <ShieldCheck size={14} className="text-accent" aria-label={t('2FA включена')} />}</div>
                        <div className="mt-0.5 max-w-[260px] truncate text-xs text-muted">{u.email}</div>
                        <div className="text-[11px] text-faint">{u.username} · {u.source === 'AD_LDAP' ? 'AD/LDAP' : t('локальный')}</div>
                        {u.mustChangePassword && <div className="mt-1 flex items-center gap-1 text-[11px] font-medium text-amber-700"><KeyRound size={11} />{t('Ожидает смену пароля')}</div>}
                      </div>
                    </div>
                    {editingGroupsFor === u.id && (
                      <div className="mt-3 max-w-[420px] animate-rise-in rounded-xl border border-line bg-surface p-3 shadow-soft">
                        <p className="mb-2 text-xs font-medium text-muted">{t('Группы пользователя')}</p>
                        {groupPicker(selectedGroups, (code) => toggleList(setSelectedGroups, code))}
                        <div className="mt-3 flex gap-1.5">
                          <button type="button" onClick={() => saveUserGroups(u)} className="btn-primary h-8 px-3 text-xs">{t('Сохранить')}</button>
                          <button type="button" onClick={() => setEditingGroupsFor(null)} className="btn-ghost h-8 px-3 text-xs">{t('Отмена')}</button>
                        </div>
                      </div>
                    )}
                  </td>
                  <td className="px-4 py-4 text-muted">{u.department ?? '—'}</td>
                  <td className="max-w-[210px] px-4 py-4"><div className="flex flex-wrap gap-1">{u.groups.length ? u.groups.map((g) => <span key={g} className="chip px-2 py-0.5 text-[11px]">{systemGroupName(locale, g, groups.find((x) => x.code === g)?.name)}</span>) : <span className="text-faint">—</span>}</div></td>
                  <td className="px-4 py-4"><StatusPill status={u.status} /></td>
                  <td className="min-w-[110px] px-4 py-4"><div className="text-[13px] text-ink-soft">{formatBytes(locale, u.quotaBytes)}</div><div className="mt-1.5 h-1 overflow-hidden rounded-full bg-black/[0.07]"><div className="h-full rounded-full bg-accent" style={{ width: `${used}%` }} /></div><div className="mt-1 text-[10px] text-faint">{t('{used}% занято', { used })}</div></td>
                  <td className="whitespace-nowrap px-4 py-4 text-muted">{fmtDate(u.lastLoginAt)}</td>
                  <td className="px-4 py-4">
                    <div className="flex justify-end gap-1">
                      {canManageGroups && <button type="button" onClick={() => { setEditingGroupsFor(u.id); setSelectedGroups(u.groups); }} className="icon-btn" title={t('Назначить группы')} aria-label={t('Назначить группы')}><Users size={17} /></button>}
                      <button type="button" onClick={() => toggleBlock(u)} className="icon-btn" title={u.status === 'ACTIVE' ? t('Заблокировать') : t('Разблокировать')} aria-label={u.status === 'ACTIVE' ? t('Заблокировать') : t('Разблокировать')}>
                        {u.status === 'ACTIVE' ? <Ban size={17} /> : <Check size={17} />}
                      </button>
                      {u.status !== 'DISABLED' && <button type="button" onClick={() => disable(u)} className="icon-btn hover:bg-red-50 hover:text-danger" title={t('Отключить пользователя')} aria-label={t('Отключить пользователя')}><Power size={17} /></button>}
                    </div>
                  </td>
                </tr>
              );
            })}
            {displayedUsers.length === 0 && <tr><td colSpan={7}><EmptyState icon={Users} title={t('Пользователи не найдены')} description={t('Измените запрос или создайте новую учётную запись.')} /></td></tr>}
          </tbody>
        </table>
      </div>

      {!demoMode && totalPages > 1 && (
        <footer className="flex items-center justify-end gap-2 border-t border-line px-5 py-3 text-sm text-muted">
          <button type="button" disabled={page === 0} onClick={() => load(page - 1)} className="btn-ghost h-8 px-2"><ChevronLeft size={15} />{t('Назад')}</button>
          <span>{t('{current} из {total}', { current: page + 1, total: totalPages })}</span>
          <button type="button" disabled={page + 1 >= totalPages} onClick={() => load(page + 1)} className="btn-ghost h-8 px-2">{t('Вперёд')}<ChevronRight size={15} /></button>
        </footer>
      )}
    </section>
  );
}

// ================= Группы и права =================

const PERMISSION_SECTIONS = [
  { title: 'Почта', description: 'Письма, папки и отправка', match: (code: string) => code.startsWith('MAIL_') },
  { title: 'Сообщения', description: 'Чаты, каналы и звонки', match: (code: string) => code.startsWith('CHAT_') },
  { title: 'Администрирование', description: 'Пользователи, политики и сервер', match: (code: string) => code.startsWith('ADMIN_') },
  { title: 'Аналитика и аудит', description: 'Отчёты и контроль изменений', match: (code: string) => code.startsWith('ANALYTICS_') || code.startsWith('AUDIT_') },
];

function GroupsTab({ groups, setGroups, demoMode, canManage }: {
  groups: AdminGroup[];
  setGroups: Dispatch<SetStateAction<AdminGroup[]>>;
  demoMode: boolean;
  canManage: boolean;
}) {
  const { locale, t } = useI18n();
  const labels = permissionLabels(locale);
  const groupName = (group: AdminGroup) => systemGroupName(locale, group.code, group.name);
  const [activeCode, setActiveCode] = useState('ACCOUNTANT');
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [health, setHealth] = useState<SystemHealth | null>(demoMode ? DEMO_HEALTH : null);

  useEffect(() => {
    if (!demoMode) {
      adminApi.systemHealth().then(setHealth).catch(() => setHealth(null));
    }
  }, [demoMode]);

  const active = groups.find((g) => g.code === activeCode) ?? groups[0];

  const toggle = (perm: string) => {
    setSaved(false);
    setGroups((prev) => prev.map((g) => g.code !== activeCode ? g : {
      ...g,
      permissions: g.permissions.includes(perm) ? g.permissions.filter((p) => p !== perm) : [...g.permissions, perm],
    }));
  };

  async function save() {
    if (!active) return;
    setError('');
    setSaving(true);
    try {
      if (demoMode) {
        setSaved(true);
      } else {
        const updated = await adminApi.setGroupPermissions(active.code, active.permissions);
        setGroups((prev) => prev.map((g) => (g.code === updated.code ? updated : g)));
        setSaved(true);
      }
    } catch (e) {
      setError(errorText(e, locale));
    } finally {
      setSaving(false);
    }
  }

  async function createGroup(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const d = new FormData(e.currentTarget);
    const code = String(d.get('code') ?? '').trim().toUpperCase();
    const name = String(d.get('name') ?? '');
    setError('');
    try {
      if (demoMode) {
        setGroups((prev) => [...prev, { code, name, isSystem: false, enabled: true, memberCount: 0, permissions: [] }]);
      } else {
        const created = await adminApi.createGroup({ code, name, permissions: [] });
        setGroups((prev) => [...prev, created]);
      }
      setActiveCode(code);
      e.currentTarget.reset();
    } catch (err) {
      setError(errorText(err, locale));
    }
  }

  if (!active) return <section className="panel"><EmptyState icon={ShieldCheck} title={t('Группы загружаются')} description={t('Если это длится долго, проверьте соединение с сервером.')} /></section>;

  const locked = !canManage || active.code === 'SUPER_ADMIN';

  return (
    <div className="grid items-start gap-5 xl:grid-cols-[304px_minmax(0,1fr)]">
      <div className="space-y-5">
        <section className="panel animate-rise-in p-3">
          <h2 className="px-2 pb-2 pt-1 text-[15px] font-semibold">{t('Группы')}</h2>
          <div className="space-y-1">
            {groups.map((g) => (
              <button key={g.code} type="button" onClick={() => { setActiveCode(g.code); setSaved(false); }} className={`nav-row min-h-[58px] px-3 ${activeCode === g.code ? 'nav-row-active' : ''}`}>
                <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${activeCode === g.code ? 'bg-white text-accent-dark shadow-soft' : 'bg-black/[0.045] text-muted'}`}><ShieldCheck size={17} /></span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-medium text-ink-soft">{groupName(g)}</span>
                  <span className="block truncate text-[11px] font-normal text-faint">{g.code}{g.isSystem ? ` · ${t('системная')}` : ''}</span>
                </span>
                <span className="text-xs text-faint">{g.memberCount}</span>
              </button>
            ))}
          </div>

          <form onSubmit={createGroup} className="mt-4 space-y-3 border-t border-line px-2 pb-2 pt-4">
            <div className="text-sm font-medium text-ink-soft">{t('Своя группа')}</div>
            <div><label className="field-label" htmlFor="group-code">{t('Код')}</label><input id="group-code" name="code" required placeholder="SALES" className="field" disabled={!canManage} /></div>
            <div><label className="field-label" htmlFor="group-name">{t('Название')}</label><input id="group-name" name="name" required placeholder={t('Отдел продаж')} className="field" disabled={!canManage} /></div>
            <button className="btn-secondary w-full" disabled={!canManage}><Plus size={15} />{t('Создать группу')}</button>
            {!canManage && <p className="flex items-center gap-1.5 text-xs text-faint"><Lock size={12} />{t('Нужно право «Группы и права»')}</p>}
          </form>
        </section>

        <section className="panel p-5">
          <div className="mb-3 flex items-center gap-2 font-semibold"><Activity size={17} className="text-accent" />{t('Состояние сервисов')}</div>
          {!health && <p className="text-[13px] leading-relaxed text-muted">{t('Нет данных. Возможно, у текущей роли нет права на системное состояние.')}</p>}
          {health?.services.map((s) => (
            <div key={s.name} className="flex items-center gap-2.5 border-b border-line/70 py-2 text-[13px] last:border-0">
              <span className={`h-2 w-2 shrink-0 rounded-full ${s.status === 'UP' ? 'bg-emerald-500' : s.status === 'DEGRADED' ? 'bg-amber-500' : 'bg-red-500'}`} />
              <span className="min-w-0 flex-1 truncate text-ink-soft">{t(s.name)}</span>
              <span className="text-[11px] text-faint">{s.status === 'UP' ? t('работает') : s.status === 'DEGRADED' ? t('деградация') : t('недоступен')}</span>
            </div>
          ))}
          {health && (
            <div className="mt-4 flex items-start gap-2 rounded-xl bg-chrome p-3 text-xs leading-relaxed text-muted">
              <HardDrive size={15} className="mt-0.5 shrink-0" />
              <span>{t('Свободно на диске:')} <b className="font-medium text-ink-soft">{formatBytes(locale, health.diskFreeBytes)}</b><br />{t('Очередь почты:')} {health.mailQueueDepth < 0 ? t('нет данных RabbitMQ') : health.mailQueueDepth}</span>
            </div>
          )}
        </section>
      </div>

      <section className="panel animate-rise-in overflow-hidden">
        <header className="border-b border-line px-5 py-5 sm:px-6">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="mr-auto text-[20px] font-semibold tracking-[-0.025em]">{groupName(active)}</h2>
            <span className="chip font-mono text-[11px]">{active.code}</span>
            <span className="chip">{countLabel(locale, active.memberCount, ['человек', 'человека', 'человек'], ['person', 'people'])}</span>
            {saved && <span className="inline-flex items-center gap-1 text-sm font-medium text-emerald-700"><Check size={15} />{t('Сохранено')}</span>}
          </div>
          <p className="mt-2 max-w-2xl text-sm leading-relaxed text-muted">{t('Выберите возможности этой группы. Доступ сотрудника складывается из всех групп, в которые он входит.')}</p>
          {active.code === 'SUPER_ADMIN' && <p className="mt-3 flex items-center gap-2 text-[13px] text-muted"><Lock size={14} />{t('Права суперадминистратора защищены от редактирования — это страховка от самоблокировки системы.')}</p>}
        </header>

        <div className="px-5 py-5 sm:px-6">
          {error && <Notice tone="error" className="mb-5">{error}</Notice>}
          <div className="space-y-7">
            {PERMISSION_SECTIONS.map((section) => {
              const permissions = Object.entries(labels).filter(([code]) => section.match(code));
              return (
                <fieldset key={section.title} disabled={locked}>
                  <legend className="sr-only">{t(section.title)}</legend>
                  <div className="mb-3 flex items-baseline justify-between gap-3">
                    <div><h3 className="text-sm font-semibold text-ink">{t(section.title)}</h3><p className="text-xs text-faint">{t(section.description)}</p></div>
                    <span className="text-xs text-faint">{t('{current} из {total}', { current: permissions.filter(([code]) => active.permissions.includes(code)).length, total: permissions.length })}</span>
                  </div>
                  <div className="grid gap-2 md:grid-cols-2">
                    {permissions.map(([code, label]) => {
                      const checked = active.permissions.includes(code);
                      return (
                        <button
                          key={code}
                          type="button"
                          role="checkbox"
                          aria-checked={checked}
                          disabled={locked}
                          onClick={() => toggle(code)}
                          className={`flex min-h-[58px] items-center gap-3 rounded-xl border px-3.5 py-3 text-left transition ${checked ? 'border-accent/25 bg-accent-soft/55' : 'border-line bg-surface hover:border-[#D8D6CF] hover:bg-chrome'} disabled:cursor-not-allowed disabled:opacity-70`}
                        >
                          <span className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-[6px] border transition ${checked ? 'border-accent bg-accent text-white' : 'border-[#CFCFCC] bg-surface text-transparent'}`}><Check size={13} strokeWidth={2.4} /></span>
                          <span className="min-w-0">
                            <span className="block truncate text-sm font-medium text-ink-soft">{label}</span>
                            <span className="block truncate font-mono text-[10px] tracking-wide text-faint">{code}</span>
                          </span>
                        </button>
                      );
                    })}
                  </div>
                </fieldset>
              );
            })}
          </div>
        </div>

        <footer className="sticky bottom-0 flex flex-wrap items-center gap-3 border-t border-line bg-surface/95 px-5 py-4 backdrop-blur sm:px-6">
          <span className="mr-auto text-sm text-muted">{t('Выбрано прав:')} <b className="font-semibold text-ink">{active.permissions.length}</b></span>
          {saved && <span className="text-[13px] text-emerald-700">{t('Изменение записано в аудит')}</span>}
          <button type="button" disabled={locked || saving} onClick={save} className="btn-primary">
            <Save size={15} />{saving ? t('Сохраняем…') : t('Сохранить права')}
          </button>
        </footer>
      </section>
    </div>
  );
}

// ================= Аудит =================

function AuditTab({ demoMode }: { demoMode: boolean }) {
  const { locale, t } = useI18n();
  const fmtDate = (value: string | null) => (value ? formatDateTime(locale, value) : '—');
  const [entries, setEntries] = useState<AuditEntry[]>(demoMode ? DEMO_AUDIT : []);
  const [error, setError] = useState('');

  useEffect(() => {
    if (demoMode) {
      setEntries(DEMO_AUDIT);
      return;
    }
    adminApi.listAudit(0, 50).then((r) => setEntries(r.content)).catch((e) => setError(errorText(e, locale)));
  }, [demoMode]);

  return (
    <section className="panel animate-rise-in overflow-hidden">
      <header className="border-b border-line px-5 py-5">
        <h2 className="text-[17px] font-semibold tracking-[-0.02em]">{t('Журнал аудита')}</h2>
        <p className="mt-1 text-sm text-muted">{t('Последние административные события: кто, когда и что изменил.')}</p>
      </header>
      {error && <div className="px-5 pt-4"><Notice tone="error">{error}</Notice></div>}
      <div className="overflow-x-auto">
        <table className="w-full min-w-[760px] border-collapse text-sm">
          <thead>
            <tr className="bg-chrome/80 text-left text-[11px] uppercase tracking-[0.07em] text-faint">
              <th className="px-5 py-3 font-semibold">{t('Время')}</th><th className="px-4 py-3 font-semibold">{t('Инициатор')}</th><th className="px-4 py-3 font-semibold">{t('Действие')}</th>
              <th className="px-4 py-3 font-semibold">{t('Объект')}</th><th className="px-4 py-3 font-semibold">{t('Детали')}</th><th className="px-4 py-3 font-semibold">IP</th>
            </tr>
          </thead>
          <tbody>
            {entries.map((en) => (
              <tr key={en.id} className="border-t border-line align-top transition hover:bg-chrome/70">
                <td className="whitespace-nowrap px-5 py-4 text-muted">{fmtDate(en.createdAt)}</td>
                <td className="px-4 py-4"><div className="flex items-center gap-2.5"><Avatar name={en.actorName ?? 'system'} size={30} /><span className="font-medium text-ink-soft">{en.actorName ?? t('Система')}</span></div></td>
                <td className="px-4 py-4"><span className="chip font-mono text-[10px]">{en.action}</span></td>
                <td className="max-w-[180px] truncate px-4 py-4 text-muted" title={en.entityId ?? undefined}>{en.entity ?? '—'}{en.entityId ? <span className="block truncate text-[11px] text-faint">{en.entityId}</span> : null}</td>
                <td className="max-w-[260px] truncate px-4 py-4 font-mono text-[11px] text-muted" title={JSON.stringify(en.details)}>{Object.keys(en.details ?? {}).length ? JSON.stringify(en.details) : '—'}</td>
                <td className="px-4 py-4 text-muted">{en.ipAddress ?? '—'}</td>
              </tr>
            ))}
            {entries.length === 0 && <tr><td colSpan={6}><EmptyState icon={ScrollText} title={t('Записей пока нет')} description={t('Здесь появятся создание пользователей, изменения прав и другие события.')} /></td></tr>}
          </tbody>
        </table>
      </div>
    </section>
  );
}
