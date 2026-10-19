import { FormEvent, useEffect, useState } from 'react';
import { AdminGroup, AdminUser, AuditEntry, SystemHealth, adminApi } from '../../api/adminApi';
import { errorText } from '../../api/authApi';
import { DEMO_AUDIT, DEMO_GROUPS, DEMO_USERS, PERMISSION_LABELS } from '../../api/demoData';
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

const fmtBytes = (n: number) => (n < 0 ? '—' : `${(n / 1024 ** 3).toFixed(1)} ГБ`);
const fmtDate = (s: string | null) => (s ? new Date(s).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : '—');

/** Админ-панель этапа 1: пользователи, группы/права, аудит — всё на реальном API (в демо — локально). */
export default function AdminPage() {
  const { has, demoMode } = useSession();
  const [tab, setTab] = useState<Tab>(has('ADMIN_USERS_MANAGE') ? 'users' : 'groups');
  const [groups, setGroups] = useState<AdminGroup[]>(demoMode ? DEMO_GROUPS : []);
  const [groupsError, setGroupsError] = useState('');

  useEffect(() => {
    if (demoMode) {
      setGroups(DEMO_GROUPS);
      return;
    }
    adminApi.listGroups().then(setGroups).catch((e) => setGroupsError(errorText(e)));
  }, [demoMode]);

  const tabBtn = (t: Tab, label: string) => (
    <button key={t} onClick={() => setTab(t)}
      className={`rounded-lg px-4 py-2 text-sm font-medium ${tab === t ? 'bg-brand text-white' : 'bg-white text-slate-600 shadow-sm hover:bg-slate-100'}`}>
      {label}
    </button>
  );

  return (
    <div className="scroll-area h-full bg-slate-50 p-4 md:p-6">
      <div className="mx-auto max-w-6xl">
        <div className="mb-4 flex flex-wrap gap-2">
          {has('ADMIN_USERS_MANAGE') && tabBtn('users', '👥 Пользователи')}
          {(has('ADMIN_GROUPS_MANAGE') || has('ADMIN_USERS_MANAGE')) && tabBtn('groups', '🛡️ Группы и права')}
          {has('AUDIT_VIEW') && tabBtn('audit', '📜 Аудит')}
        </div>
        {groupsError && <p className="mb-3 rounded bg-red-50 px-3 py-2 text-sm text-red-700">Группы: {groupsError}</p>}
        {tab === 'users' && <UsersTab groups={groups} demoMode={demoMode} />}
        {tab === 'groups' && <GroupsTab groups={groups} setGroups={setGroups} demoMode={demoMode} canManage={has('ADMIN_GROUPS_MANAGE')} />}
        {tab === 'audit' && <AuditTab demoMode={demoMode} />}
      </div>
    </div>
  );
}

// ================= Пользователи =================

function UsersTab({ groups, demoMode }: { groups: AdminGroup[]; demoMode: boolean }) {
  const { has } = useSession();
  const canManageGroups = has('ADMIN_GROUPS_MANAGE') || has('ADMIN_USERS_MANAGE');
  const [users, setUsers] = useState<AdminUser[]>(demoMode ? (DEMO_USERS as AdminUser[]) : []);
  const [total, setTotal] = useState(demoMode ? DEMO_USERS.length : 0);
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
      setError(errorText(e));
    }
  }

  useEffect(() => {
    load(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [demoMode]);

  const patchLocal = (u: AdminUser) => setUsers((prev) => prev.map((x) => (x.id === u.id ? u : x)));

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
          source: 'LOCAL', status: 'ACTIVE', department: body.department ?? null, groups: createGroups,
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
      setNotice('Пользователь создан. Временный пароль он сменит при первом входе.');
    } catch (err) {
      setError(errorText(err));
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
      setError(errorText(err));
    }
  }

  async function disable(u: AdminUser) {
    if (!window.confirm(`Отключить пользователя ${u.displayName} (${u.username})? Сессии будут отозваны, группы сняты.`)) return;
    setError('');
    try {
      if (demoMode) {
        patchLocal({ ...u, status: 'DISABLED', groups: [] });
      } else {
        await adminApi.disableUser(u.id);
        patchLocal({ ...u, status: 'DISABLED', groups: [] });
      }
    } catch (err) {
      setError(errorText(err));
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
      setNotice(`Группы ${u.username} обновлены (токены действуют до своего окончания ≤15 мин)`);
    } catch (err) {
      setError(errorText(err));
    }
  }

  const inputCls = 'rounded-lg border px-3 py-2 text-sm outline-none focus:border-brand';
  const statusBadge = (s: string) =>
    s === 'ACTIVE' ? 'bg-green-100 text-green-700' : s === 'BLOCKED' ? 'bg-red-100 text-red-700' : 'bg-slate-200 text-slate-600';

  return (
    <section className="rounded-xl bg-white p-4 shadow-sm">
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <h2 className="mr-auto font-semibold">Пользователи <span className="text-slate-400">({total})</span></h2>
        <form onSubmit={(e) => { e.preventDefault(); load(0); }} className="flex gap-2">
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Поиск: логин, имя, отдел…" className={`${inputCls} w-56`} />
          <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} className={inputCls}>
            <option value="">Все статусы</option>
            <option value="ACTIVE">Активные</option>
            <option value="BLOCKED">Заблокированные</option>
            <option value="DISABLED">Отключённые</option>
          </select>
          {!demoMode && <button className="rounded-lg border px-3 py-2 text-sm">Найти</button>}
        </form>
        <button onClick={() => setShowCreate(!showCreate)} className="rounded-lg bg-brand px-4 py-2 text-sm text-white">＋ Новый пользователь</button>
      </div>
      {error && <p className="mb-3 rounded bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}
      {notice && <p className="mb-3 rounded bg-green-50 px-3 py-2 text-sm text-green-700">{notice}</p>}

      {showCreate && (
        <form onSubmit={onCreate} className="mb-4 grid gap-2 rounded-lg border bg-slate-50 p-3 sm:grid-cols-2">
          <input name="username" required placeholder="Логин (i.ivanov)" className={inputCls} />
          <input name="email" required type="email" placeholder="E-mail" className={inputCls} />
          <input name="displayName" required placeholder="Имя Фамилия" className={inputCls} />
          <input name="department" placeholder="Подразделение" className={inputCls} />
          <input name="tempPassword" required placeholder="Временный пароль (≥10, буквы+цифры)" className={inputCls} />
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <span className="text-slate-500">Группы:</span>
            {groups.map((g) => (
              <label key={g.code} className="flex items-center gap-1">
                <input type="checkbox" className="h-4 w-4 accent-blue-600"
                  checked={createGroups.includes(g.code)}
                  onChange={() => setCreateGroups((prev) => prev.includes(g.code) ? prev.filter((c) => c !== g.code) : [...prev, g.code])} />
                {g.code}
              </label>
            ))}
          </div>
          <div className="sm:col-span-2">
            <button className="rounded-lg bg-brand px-4 py-2 text-sm text-white">Создать</button>
            <span className="ml-3 text-xs text-slate-400">Пользователь сменит временный пароль при первом входе</span>
          </div>
        </form>
      )}

      <div className="overflow-x-auto">
        <table className="w-full min-w-[760px] text-sm">
          <thead>
            <tr className="border-b text-left text-slate-500">
              <th className="py-2 pr-3">Пользователь</th><th className="pr-3">Отдел</th><th className="pr-3">Группы</th>
              <th className="pr-3">Статус</th><th className="pr-3">Квота</th><th className="pr-3">Вход</th><th>Действия</th>
            </tr>
          </thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id} className="border-b align-top hover:bg-slate-50">
                <td className="py-2 pr-3">
                  <div className="font-medium">{u.displayName}{u.totpEnabled && ' 🔐'}</div>
                  <div className="text-xs text-slate-400">{u.username} · {u.email}</div>
                  {u.mustChangePassword && <div className="text-xs text-amber-600">ожидается смена пароля</div>}
                  {editingGroupsFor === u.id && (
                    <div className="mt-2 flex max-w-md flex-wrap gap-2 rounded-lg border bg-white p-2">
                      {groups.map((g) => (
                        <label key={g.code} className="flex items-center gap-1 text-xs">
                          <input type="checkbox" className="h-4 w-4 accent-blue-600"
                            checked={selectedGroups.includes(g.code)}
                            onChange={() => setSelectedGroups((prev) => prev.includes(g.code) ? prev.filter((c) => c !== g.code) : [...prev, g.code])} />
                          {g.code}
                        </label>
                      ))}
                      <button onClick={() => saveUserGroups(u)} className="rounded bg-brand px-2 py-1 text-xs text-white">Сохранить</button>
                      <button onClick={() => setEditingGroupsFor(null)} className="rounded border px-2 py-1 text-xs">Отмена</button>
                    </div>
                  )}
                </td>
                <td className="py-2 pr-3">{u.department ?? '—'}</td>
                <td className="py-2 pr-3">{u.groups.join(', ') || '—'}</td>
                <td className="py-2 pr-3"><span className={`rounded px-1.5 py-0.5 text-xs font-semibold ${statusBadge(u.status)}`}>{u.status}</span></td>
                <td className="py-2 pr-3">{fmtBytes(u.quotaBytes)}</td>
                <td className="py-2 pr-3">{fmtDate(u.lastLoginAt)}</td>
                <td className="py-2">
                  <div className="flex flex-wrap gap-1">
                    {canManageGroups && (
                      <button onClick={() => { setEditingGroupsFor(u.id); setSelectedGroups(u.groups); }} className="rounded border px-2 py-1 text-xs">Группы</button>
                    )}
                    <button onClick={() => toggleBlock(u)} className="rounded border px-2 py-1 text-xs">
                      {u.status === 'ACTIVE' ? 'Заблокировать' : 'Разблокировать'}
                    </button>
                    {u.status !== 'DISABLED' && (
                      <button onClick={() => disable(u)} className="rounded border border-red-200 px-2 py-1 text-xs text-red-600">Отключить</button>
                    )}
                  </div>
                </td>
              </tr>
            ))}
            {users.length === 0 && <tr><td colSpan={7} className="py-6 text-center text-slate-400">Пользователей не найдено</td></tr>}
          </tbody>
        </table>
      </div>
      {!demoMode && totalPages > 1 && (
        <div className="mt-3 flex items-center gap-2 text-sm">
          <button disabled={page === 0} onClick={() => load(page - 1)} className="rounded border px-3 py-1 disabled:opacity-40">← Назад</button>
          <span>Страница {page + 1} из {totalPages}</span>
          <button disabled={page + 1 >= totalPages} onClick={() => load(page + 1)} className="rounded border px-3 py-1 disabled:opacity-40">Вперёд →</button>
        </div>
      )}
    </section>
  );
}

// ================= Группы и права =================

function GroupsTab({ groups, setGroups, demoMode, canManage }: {
  groups: AdminGroup[];
  setGroups: React.Dispatch<React.SetStateAction<AdminGroup[]>>;
  demoMode: boolean;
  canManage: boolean;
}) {
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
      setError(errorText(e));
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
      setError(errorText(err));
    }
  }

  if (!active) return <section className="rounded-xl bg-white p-4 shadow-sm">Группы загружаются…</section>;

  return (
    <div className="grid gap-4 lg:grid-cols-[300px_1fr]">
      <section className="rounded-xl bg-white p-4 shadow-sm">
        <h2 className="mb-3 font-semibold">Группы пользователей</h2>
        {groups.map((g) => (
          <button key={g.code} onClick={() => setActiveCode(g.code)} className={`mb-1 block w-full rounded-lg px-3 py-2 text-left text-sm ${activeCode === g.code ? 'bg-blue-50 font-semibold' : 'hover:bg-slate-50'}`}>
            {g.name}<span className="float-right text-slate-400">{g.memberCount} чел.</span>
            <div className="text-xs font-normal text-slate-400">{g.code}{g.isSystem ? ' · системная' : ''}</div>
          </button>
        ))}
        <form onSubmit={createGroup} className="mt-3 space-y-2 rounded-lg border border-dashed p-3">
          <div className="text-sm font-medium">Своя группа</div>
          <input name="code" required placeholder="КОД (SALES)" className="w-full rounded-lg border px-3 py-2 text-sm outline-none focus:border-brand" disabled={!canManage} />
          <input name="name" required placeholder="Название (Отдел продаж)" className="w-full rounded-lg border px-3 py-2 text-sm outline-none focus:border-brand" disabled={!canManage} />
          <button className="w-full rounded-lg border px-3 py-2 text-sm disabled:opacity-50" disabled={!canManage}>＋ Создать</button>
        </form>

        <h3 className="mb-2 mt-6 font-semibold">Здоровье сервисов</h3>
        {!health && <p className="text-xs text-slate-400">Нет данных (нужно право ADMIN_SERVER_CONFIG или ADMIN_USERS_MANAGE)</p>}
        {health?.services.map((s) => (
          <div key={s.name} className="flex items-center justify-between border-b py-1.5 text-xs">
            <span>{s.name}</span>
            <span className={`rounded px-1.5 py-0.5 font-semibold ${s.status === 'UP' ? 'bg-green-100 text-green-700' : s.status === 'DEGRADED' ? 'bg-amber-100 text-amber-700' : 'bg-red-100 text-red-700'}`}>{s.status}</span>
          </div>
        ))}
        {health && <p className="mt-2 text-xs text-slate-400">Свободно на диске: {fmtBytes(health.diskFreeBytes)} · очередь почты: {health.mailQueueDepth < 0 ? '—' : health.mailQueueDepth}</p>}
      </section>

      <section className="rounded-xl bg-white p-4 shadow-sm">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="font-semibold">Права группы «{active.name}»</h2>
          {saved && <span className="text-sm text-green-600">✓ Сохранено, записано в аудит</span>}
        </div>
        <p className="mb-4 text-sm text-slate-500">Права пользователя — сумма прав всех его групп. Действующие токены обновятся в течение ≤15 минут.</p>
        {error && <p className="mb-3 rounded bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}
        <div className="grid gap-1 sm:grid-cols-2">
          {Object.entries(PERMISSION_LABELS).map(([code, label]) => (
            <label key={code} className="flex cursor-pointer items-start gap-2 rounded-lg border px-3 py-2 text-sm hover:bg-slate-50">
              <input type="checkbox" className="mt-0.5 h-4 w-4 accent-blue-600" disabled={!canManage || active.code === 'SUPER_ADMIN'}
                checked={active.permissions.includes(code)} onChange={() => toggle(code)} />
              <span>{label}<span className="block text-[11px] text-slate-400">{code}</span></span>
            </label>
          ))}
        </div>
        <button disabled={!canManage || active.code === 'SUPER_ADMIN' || saving} onClick={save}
          className="mt-4 rounded-lg bg-brand px-5 py-2 text-white disabled:opacity-50">
          {saving ? 'Сохранение…' : `Сохранить права (${active.permissions.length})`}
        </button>
        {active.code === 'SUPER_ADMIN' && <p className="mt-2 text-xs text-slate-400">Права суперадминистратора не редактируются — страховка от самоблокировки системы.</p>}
      </section>
    </div>
  );
}

// ================= Аудит =================

function AuditTab({ demoMode }: { demoMode: boolean }) {
  const [entries, setEntries] = useState<AuditEntry[]>(demoMode ? DEMO_AUDIT : []);
  const [error, setError] = useState('');

  useEffect(() => {
    if (demoMode) {
      setEntries(DEMO_AUDIT);
      return;
    }
    adminApi.listAudit(0, 50).then((r) => setEntries(r.content)).catch((e) => setError(errorText(e)));
  }, [demoMode]);

  return (
    <section className="rounded-xl bg-white p-4 shadow-sm">
      <h2 className="mb-3 font-semibold">Журнал аудита <span className="text-sm font-normal text-slate-400">(последние события)</span></h2>
      {error && <p className="mb-3 rounded bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}
      <div className="overflow-x-auto">
        <table className="w-full min-w-[720px] text-sm">
          <thead>
            <tr className="border-b text-left text-slate-500">
              <th className="py-2 pr-3">Время</th><th className="pr-3">Кто</th><th className="pr-3">Действие</th>
              <th className="pr-3">Объект</th><th className="pr-3">Детали</th><th>IP</th>
            </tr>
          </thead>
          <tbody>
            {entries.map((en) => (
              <tr key={en.id} className="border-b align-top hover:bg-slate-50">
                <td className="whitespace-nowrap py-2 pr-3">{fmtDate(en.createdAt)}</td>
                <td className="py-2 pr-3">{en.actorName ?? '—'}</td>
                <td className="py-2 pr-3 font-medium">{en.action}</td>
                <td className="py-2 pr-3">{en.entity ?? ''}{en.entityId ? ` · ${en.entityId.slice(0, 18)}` : ''}</td>
                <td className="max-w-xs truncate py-2 pr-3 text-xs text-slate-500" title={JSON.stringify(en.details)}>{JSON.stringify(en.details)}</td>
                <td className="py-2">{en.ipAddress ?? '—'}</td>
              </tr>
            ))}
            {entries.length === 0 && <tr><td colSpan={6} className="py-6 text-center text-slate-400">Записей пока нет</td></tr>}
          </tbody>
        </table>
      </div>
    </section>
  );
}
