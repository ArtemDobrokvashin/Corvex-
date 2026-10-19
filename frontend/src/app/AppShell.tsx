import { NavLink, Outlet } from 'react-router-dom';
import { useSession } from '../store/sessionStore';

const linkCls = ({ isActive }: { isActive: boolean }) =>
  `flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium ${isActive ? 'bg-brand text-white' : 'text-slate-200 hover:bg-white/10'}`;

/** Единая оболочка: почта и чат в одном интерфейсе (ТЗ §10.1), боковое меню, единые уведомления. */
export default function AppShell() {
  const { user, demoMode, has } = useSession();
  return (
    <div className="flex h-full flex-col md:flex-row">
      <aside className="flex shrink-0 flex-row items-center gap-2 bg-slate-900 px-3 py-2 md:w-60 md:flex-col md:items-stretch md:py-4">
        <div className="px-2 text-lg font-bold text-white"><span className="text-blue-400">◆</span> Corvex</div>
        <nav className="flex flex-1 flex-row gap-1 md:flex-col">
          <NavLink to="/mail" className={linkCls}>✉️ <span className="hidden sm:inline">Почта</span><span className="ml-auto hidden rounded-full bg-red-500 px-2 py-0.5 text-xs text-white md:inline">4</span></NavLink>
          <NavLink to="/chat" className={linkCls}>💬 <span className="hidden sm:inline">Чат</span><span className="ml-auto hidden rounded-full bg-red-500 px-2 py-0.5 text-xs text-white md:inline">3</span></NavLink>
          {(has('ADMIN_USERS_MANAGE') || has('ADMIN_GROUPS_MANAGE')) && (
            <NavLink to="/admin" className={linkCls}>🛠️ <span className="hidden sm:inline">Админ-панель</span></NavLink>
          )}
        </nav>
        <div className="hidden border-t border-white/10 px-2 pt-3 text-xs text-slate-300 md:block">
          <div className="font-medium text-white">{user?.displayName}</div>
          <div>{user?.email}</div>
          <div className="mt-1 flex items-center gap-1"><span className="h-2 w-2 rounded-full bg-green-400" /> Онлайн · {user?.groups.join(', ')}</div>
        </div>
      </aside>
      <div className="flex min-h-0 flex-1 flex-col">
        {demoMode && (
          <div className="bg-amber-100 px-4 py-1.5 text-center text-xs text-amber-900">
            Демо-режим витрины: бэкенд не запущен, данные учебные. Поднимите <code>docker compose up -d</code> — интерфейс сам перейдёт на реальный API.
          </div>
        )}
        <div className="min-h-0 flex-1"><Outlet /></div>
      </div>
    </div>
  );
}
