import { useEffect, useRef, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { logout as apiLogout, restoreSession } from '../api/authApi';
import { DEMO_USER } from '../api/demoData';
import { setAccessToken } from '../api/httpClient';
import ChangePasswordModal from '../features/auth/ChangePasswordModal';
import SecuritySettingsModal from '../features/auth/SecuritySettingsModal';
import { useSession } from '../store/sessionStore';

const linkCls = ({ isActive }: { isActive: boolean }) =>
  `flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium ${isActive ? 'bg-brand text-white' : 'text-slate-200 hover:bg-white/10'}`;

/**
 * Единая оболочка: почта и чат в одном интерфейсе (ТЗ §10.1). При загрузке
 * восстанавливает сессию по refresh-cookie (или демо-сессию витрины),
 * без сессии уводит на /login. Блокирует работу, пока временный пароль не сменён.
 */
export default function AppShell() {
  const { user, demoMode, bootstrapped, has, setSession, clearSession, setBootstrapped, updateUser } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const restoreStarted = useRef(false);
  const [securityOpen, setSecurityOpen] = useState(false);

  useEffect(() => {
    if (restoreStarted.current || bootstrapped) return;
    restoreStarted.current = true;
    (async () => {
      if (useSession.getState().user) {
        setBootstrapped();
        return;
      }
      if (sessionStorage.getItem('corvex.demo') === '1') {
        setSession(DEMO_USER, true);
        return;
      }
      const restored = await restoreSession();
      if (restored) {
        setSession(restored, false);
      } else {
        setBootstrapped();
        navigate('/login', { replace: true });
      }
    })();
  }, [bootstrapped, navigate, setSession, setBootstrapped]);

  async function onLogout() {
    if (!demoMode) {
      try {
        await apiLogout();
      } catch {
        // сервер уже недоступен — локальный выход всё равно выполняем
      }
    }
    setAccessToken(null);
    sessionStorage.removeItem('corvex.demo');
    clearSession();
    navigate('/login', { replace: true });
  }

  if (!bootstrapped) {
    return <div className="flex h-full items-center justify-center text-slate-500">Восстановление сессии…</div>;
  }
  if (!user) {
    return <div className="flex h-full items-center justify-center text-slate-500">Переход ко входу…</div>;
  }

  const moduleNotice = !demoMode && location.pathname.startsWith('/mail')
    ? 'Почтовый модуль появится на этапе 2 плана — пока показаны демонстрационные письма. Вход и админка уже работают с настоящим сервером.'
    : !demoMode && location.pathname.startsWith('/chat')
      ? 'Чат реального времени появится на этапе 4 плана — пока показана демонстрация интерфейса.'
      : null;

  return (
    <div className="flex h-full flex-col md:flex-row">
      <aside className="flex shrink-0 flex-row items-center gap-2 bg-slate-900 px-3 py-2 md:w-60 md:flex-col md:items-stretch md:py-4">
        <div className="px-2 text-lg font-bold text-white"><span className="text-blue-400">◆</span> Corvex</div>
        <nav className="flex flex-1 flex-row gap-1 md:flex-col">
          <NavLink to="/mail" className={linkCls}>✉️ <span className="hidden sm:inline">Почта</span>{demoMode && <span className="ml-auto hidden rounded-full bg-red-500 px-2 py-0.5 text-xs text-white md:inline">4</span>}</NavLink>
          <NavLink to="/chat" className={linkCls}>💬 <span className="hidden sm:inline">Чат</span>{demoMode && <span className="ml-auto hidden rounded-full bg-red-500 px-2 py-0.5 text-xs text-white md:inline">3</span>}</NavLink>
          {(has('ADMIN_USERS_MANAGE') || has('ADMIN_GROUPS_MANAGE')) && (
            <NavLink to="/admin" className={linkCls}>🛠️ <span className="hidden sm:inline">Админ-панель</span></NavLink>
          )}
        </nav>
        <div className="hidden border-t border-white/10 px-2 pt-3 text-xs text-slate-300 md:block">
          <div className="font-medium text-white">{user.displayName}</div>
          <div>{user.email}</div>
          <div className="mt-1 flex items-center gap-1"><span className="h-2 w-2 rounded-full bg-green-400" /> Онлайн · {user.groups.join(', ')}</div>
          <div className="mt-3 flex gap-2">
            <button onClick={() => setSecurityOpen(true)} className="rounded-lg bg-white/10 px-3 py-1.5 text-slate-100 hover:bg-white/20">🔐 Безопасность</button>
            <button onClick={onLogout} className="rounded-lg bg-white/10 px-3 py-1.5 text-slate-100 hover:bg-white/20">Выйти</button>
          </div>
        </div>
        <button onClick={onLogout} className="rounded-lg bg-white/10 px-3 py-2 text-sm text-slate-100 md:hidden">Выйти</button>
      </aside>

      <div className="flex min-h-0 flex-1 flex-col">
        {demoMode && (
          <div className="bg-amber-100 px-4 py-1.5 text-center text-xs text-amber-900">
            Демо-режим витрины: сервер не подключён, данные учебные.{' '}
            <button onClick={onLogout} className="font-medium underline">Выйти из демо</button>
          </div>
        )}
        {moduleNotice && (
          <div className="bg-blue-50 px-4 py-1.5 text-center text-xs text-blue-900">{moduleNotice}</div>
        )}
        <div className="min-h-0 flex-1"><Outlet /></div>
      </div>

      {user.mustChangePassword && !demoMode && (
        <ChangePasswordModal onChanged={() => updateUser({ mustChangePassword: false })} />
      )}
      {securityOpen && <SecuritySettingsModal onClose={() => setSecurityOpen(false)} />}
    </div>
  );
}
