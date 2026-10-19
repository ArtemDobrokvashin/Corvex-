import { useState } from 'react';
import { DEMO_GROUPS, PERMISSION_LABELS } from '../../api/demoData';
import { useSession } from '../../store/sessionStore';

const SERVICE_HEALTH = [
  { name: 'auth-service', status: 'UP', cpu: '12%', mem: '410 МБ' },
  { name: 'mail-smtp-service', status: 'UP', cpu: '8%', mem: '330 МБ' },
  { name: 'mail-imap-service', status: 'UP', cpu: '15%', mem: '520 МБ' },
  { name: 'mail-storage-service', status: 'UP', cpu: '21%', mem: '640 МБ' },
  { name: 'chat-service', status: 'UP', cpu: '9%', mem: '380 МБ' },
  { name: 'clamav / spamd', status: 'UP', cpu: '6%', mem: '1.1 ГБ' },
  { name: 'elasticsearch', status: 'DEGRADED', cpu: '34%', mem: '1.9 ГБ' },
];

/** Админка: группы и чекбоксы прав (ТЗ §6.2) + сводное здоровье. Все изменения пишутся в аудит. */
export default function AdminPage() {
  const { has } = useSession();
  const [groups, setGroups] = useState(DEMO_GROUPS);
  const [activeCode, setActiveCode] = useState('ACCOUNTANT');
  const [saved, setSaved] = useState(false);
  const active = groups.find((g) => g.code === activeCode)!;
  const canManage = has('ADMIN_GROUPS_MANAGE');

  const toggle = (perm: string) => {
    setSaved(false);
    setGroups((prev) => prev.map((g) => g.code !== activeCode ? g : {
      ...g,
      permissions: g.permissions.includes(perm) ? g.permissions.filter((p) => p !== perm) : [...g.permissions, perm],
    }));
  };

  return (
    <div className="scroll-area h-full bg-slate-50 p-4 md:p-6">
      <div className="mx-auto grid max-w-6xl gap-4 lg:grid-cols-[300px_1fr]">
        <section className="rounded-xl bg-white p-4 shadow-sm">
          <h2 className="mb-3 font-semibold">Группы пользователей</h2>
          {groups.map((g) => (
            <button key={g.code} onClick={() => setActiveCode(g.code)} className={`mb-1 block w-full rounded-lg px-3 py-2 text-left text-sm ${activeCode === g.code ? 'bg-blue-50 font-semibold' : 'hover:bg-slate-50'}`}>
              {g.name}<span className="float-right text-slate-400">{g.members} чел.</span>
              <div className="text-xs font-normal text-slate-400">{g.code}</div>
            </button>
          ))}
          <button className="mt-3 w-full rounded-lg border border-dashed px-3 py-2 text-sm text-slate-500" disabled={!canManage}>＋ Своя группа</button>
          <h3 className="mb-2 mt-6 font-semibold">Здоровье сервисов</h3>
          {SERVICE_HEALTH.map((s) => (
            <div key={s.name} className="flex items-center justify-between border-b py-1.5 text-xs">
              <span>{s.name}</span>
              <span className="text-slate-500">{s.cpu} · {s.mem}</span>
              <span className={`rounded px-1.5 py-0.5 font-semibold ${s.status === 'UP' ? 'bg-green-100 text-green-700' : 'bg-amber-100 text-amber-700'}`}>{s.status}</span>
            </div>
          ))}
          <p className="mt-2 text-xs text-slate-400">Подробные дашборды — Grafana (ссылка в шапке для админов).</p>
        </section>

        <section className="rounded-xl bg-white p-4 shadow-sm">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h2 className="font-semibold">Права группы «{active.name}»</h2>
            {saved && <span className="text-sm text-green-600">✓ Сохранено, записано в аудит</span>}
          </div>
          <p className="mb-4 text-sm text-slate-500">Права пользователя — сумма прав всех его групп. Действующие токены обновятся в течение ≤15 минут.</p>
          <div className="grid gap-1 sm:grid-cols-2">
            {Object.entries(PERMISSION_LABELS).map(([code, label]) => (
              <label key={code} className="flex cursor-pointer items-start gap-2 rounded-lg border px-3 py-2 text-sm hover:bg-slate-50">
                <input type="checkbox" className="mt-0.5 h-4 w-4 accent-blue-600" disabled={!canManage || activeCode === 'SUPER_ADMIN'}
                  checked={active.permissions.includes(code)} onChange={() => toggle(code)} />
                <span>{label}<span className="block text-[11px] text-slate-400">{code}</span></span>
              </label>
            ))}
          </div>
          <button disabled={!canManage || activeCode === 'SUPER_ADMIN'} onClick={() => setSaved(true)}
            className="mt-4 rounded-lg bg-brand px-5 py-2 text-white disabled:opacity-50">
            Сохранить права ({active.permissions.length})
          </button>
          {activeCode === 'SUPER_ADMIN' && <p className="mt-2 text-xs text-slate-400">Права суперадминистратора не редактируются — страховка от самоблокировки системы.</p>}
        </section>
      </div>
    </div>
  );
}
