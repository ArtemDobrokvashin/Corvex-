import { useEffect, useMemo, useRef, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import {
  Command,
  Globe,
  LogOut,
  Mail,
  MessageSquare,
  PenSquare,
  Search,
  Settings2,
  ShieldCheck,
} from 'lucide-react';
import { logout as apiLogout, restoreSession } from '../api/authApi';
import { DEMO_MAILS, DEMO_ROOMS, getDemoUser } from '../api/demoData';
import { setAccessToken } from '../api/httpClient';
import { LanguageSwitcher, systemGroupName, useI18n } from '../i18n';
import { Avatar, Clock, LoadingScreen, LogoMark } from '../components/ui';
import { CalendarWidget } from '../components/CalendarWidget';
import ChangePasswordModal from '../features/auth/ChangePasswordModal';
import SecuritySettingsModal from '../features/auth/SecuritySettingsModal';
import { canAccessAdmin } from '../features/admin/adminAccess';
import { useSession } from '../store/sessionStore';

interface QuickAction {
  id: string;
  label: string;
  description: string;
  icon: typeof Mail;
  shortcut?: string;
  run: () => void;
}

/**
 * Единая оболочка в визуальном языке Quiet Work: тонкий светлый rail на десктопе,
 * нижний таб-бар на мобильном, профиль и быстрый переход — без тёмной «стены».
 */
export default function AppShell() {
  const { locale, setLocale, t } = useI18n();
  const { user, demoMode, bootstrapped, demoReadMailIds, demoReadRoomIds, has, setSession, clearSession, setBootstrapped, updateUser } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const restoreStarted = useRef(false);
  const [securityOpen, setSecurityOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [paletteQuery, setPaletteQuery] = useState('');
  const [searchParams] = useSearchParams();
  const [topSearch, setTopSearch] = useState(searchParams.get('mailSearch') ?? '');

  useEffect(() => {
    if (restoreStarted.current || bootstrapped) return;
    restoreStarted.current = true;
    (async () => {
      if (useSession.getState().user) {
        setBootstrapped();
        return;
      }
      if (sessionStorage.getItem('corvex.demo') === '1') {
        setSession(getDemoUser(locale), true);
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
    // Locale is read only for the first demo restore; the ref above prevents a second restore.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bootstrapped, navigate, setSession, setBootstrapped]);

  useEffect(() => {
    if (demoMode) updateUser({ displayName: getDemoUser(locale).displayName });
  }, [demoMode, locale, updateUser]);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen(true);
      }
      if (e.key === 'Escape') {
        setPaletteOpen(false);
        setProfileOpen(false);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  useEffect(() => {
    setProfileOpen(false);
  }, [location.pathname]);

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

  const quickActions = useMemo<QuickAction[]>(() => {
    const actions: QuickAction[] = [
      { id: 'mail', label: t('Открыть почту'), description: t('Входящие и папки'), icon: Mail, run: () => navigate('/mail') },
      { id: 'compose', label: t('Написать письмо'), description: t('Новый черновик'), icon: PenSquare, shortcut: 'C', run: () => navigate('/mail?compose=1') },
      { id: 'chat', label: t('Открыть сообщения'), description: t('Каналы и личные чаты'), icon: MessageSquare, run: () => navigate('/chat') },
    ];
    if (canAccessAdmin(has)) {
      actions.push({ id: 'admin', label: t('Администрирование'), description: t('Пользователи, сервер, аналитика и аудит'), icon: Settings2, run: () => navigate('/admin') });
    }
    actions.push(
      { id: 'security', label: t('Безопасность'), description: t('Пароль, 2FA и пароли приложений'), icon: ShieldCheck, run: () => setSecurityOpen(true) },
      { id: 'logout', label: demoMode ? t('Выйти из демо') : t('Выйти'), description: t('Завершить текущую сессию'), icon: LogOut, run: onLogout },
    );
    return actions;
    // onLogout стабилен по смыслу для палитры; не тянем его в зависимости хука.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [demoMode, has, navigate, t]);

  const visibleActions = quickActions.filter((a) => `${a.label} ${a.description}`.toLowerCase().includes(paletteQuery.trim().toLowerCase()));

  if (!bootstrapped) return <LoadingScreen label={t('Восстанавливаем сессию')} />;
  if (!user) return <LoadingScreen label={t('Переходим ко входу')} />;

  const canAdmin = canAccessAdmin(has);
  const nextLocale = locale === 'ru' ? 'en' : 'ru';
  const demoMailUnreadCount = DEMO_MAILS.filter(
    (mail) => !mail.seen && !demoReadMailIds.includes(mail.id),
  ).length;
  const demoChatUnreadCount = DEMO_ROOMS.reduce(
    (total, room) => total + (demoReadRoomIds.includes(room.id) ? 0 : room.unread),
    0,
  );

  const navItem = (to: string, label: string, Icon: typeof Mail, badge?: number) => (
    <NavLink
      key={to}
      to={to}
      className={({ isActive }) => `group relative flex min-h-[52px] w-full flex-col items-center justify-center gap-1 rounded-xl px-1 text-[10px] font-medium transition duration-150 ${isActive ? 'bg-[#f2f2f7] text-[#0071e3] font-medium' : 'text-ink-soft hover:bg-[#f2f2f7] hover:text-ink'}`}
    >
      <span className="relative">
        <Icon size={20} strokeWidth={1.6} />
        {badge ? <span className="absolute -right-2 -top-1.5 flex h-3 min-w-3 items-center justify-center rounded-full bg-[#0071e3] px-0.5 text-[8px] font-semibold text-white">{badge}</span> : null}
      </span>
      <span>{label}</span>
    </NavLink>
  );

  const mobileNavItem = (to: string, label: string, Icon: typeof Mail, badge?: number) => (
    <NavLink
      key={to}
      to={to}
      className={({ isActive }) => `relative flex min-h-14 flex-1 flex-col items-center justify-center gap-0.5 text-[10px] font-medium ${isActive ? 'text-[#0071e3]' : 'text-ink-soft'}`}
    >
      <span className="relative">
        <Icon size={20} strokeWidth={1.6} />
        {badge ? <span className="absolute -right-2 -top-1.5 flex h-3 min-w-3 items-center justify-center rounded-full bg-[#0071e3] px-0.5 text-[8px] font-semibold text-white">{badge}</span> : null}
      </span>
      {label}
    </NavLink>
  );

  return (
    <div className="flex h-full flex-col bg-canvas">
      {/* Топбар — на всю ширину: логотип, поиск, управление */}
      <header className="flex shrink-0 items-center justify-between border-b border-[#e5e5ea] bg-white px-5 py-2.5 shadow-[0_1px_0_rgba(0,0,0,0.04)]">
        <div className="flex items-center gap-8 shrink-0">
          <LogoMark size={28} />
          <span className="text-[16px] font-semibold tracking-tight text-ink">Corvex</span>
        </div>
        <div className="flex items-center justify-center flex-1 min-w-0 max-w-[720px]">
          <div className="flex items-center gap-2 rounded-full border border-[#e5e5ea] bg-[#f2f2f7] px-3 py-1.5 min-w-[280px] shadow-[0_1px_0_rgba(0,0,0,0.05)]">
            <Search size={17} className="shrink-0 text-ink-soft" />
            <input
              type="text"
              placeholder={t('Поиск по почте…')}
              value={topSearch}
              onChange={(e) => setTopSearch(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  navigate(`/mail?mailSearch=${encodeURIComponent(topSearch)}`);
                }
              }}
              className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none focus:ring-0 placeholder:text-ink-soft"
            />
          </div>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <button
            type="button"
            className="h-9 w-9 rounded-full flex items-center justify-center text-ink-soft hover:text-ink transition hover:bg-[#f2f2f7]"
            aria-label={t('Уведомления')}
          >
            <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>
          </button>
          <button
            type="button"
            onClick={() => setLocale(nextLocale)}
            className="h-9 w-9 rounded-full flex items-center justify-center text-ink-soft hover:text-ink transition hover:bg-[#f2f2f7]"
            aria-label={t('Язык интерфейса')}
            title={t('Язык интерфейса')}
          >
            <Globe size={17} strokeWidth={1.8} />
          </button>
          <Clock className="text-[13px] font-medium text-ink shrink-0 hidden sm:flex" />
          <button type="button" onClick={() => setProfileOpen(!profileOpen)} className="flex items-center gap-1.5 rounded-full border border-[#e5e5ea] bg-white px-2 py-1 text-sm font-medium text-ink hover:bg-[#f2f2f7] transition shadow-[0_1px_0_rgba(0,0,0,0.04)]" aria-label={t('Профиль')}>
            <Avatar name={user.displayName} size={28} presence />
            <span className="truncate hidden sm:inline">{user.displayName}</span>
          </button>
        </div>
      </header>

      <div className="flex-1 flex min-h-0">
        {/* Десктопный сайдбар слева */}
        <aside className="hidden w-[80px] shrink-0 flex-col items-center border-r border-[#e5e5ea] bg-white px-2 py-4 md:flex shadow-[0_1px_0_rgba(0,0,0,0.04)]">
          <nav className="mt-8 flex w-full flex-col gap-1" aria-label={t('Основная навигация')}>
            {navItem('/mail', t('Почта'), Mail, demoMode ? demoMailUnreadCount : undefined)}
            {navItem('/chat', t('Чат'), MessageSquare, demoMode ? demoChatUnreadCount : undefined)}
            <CalendarWidget className="mt-1" />
            {canAdmin && navItem('/admin', t('Админ'), Settings2)}
          </nav>

          <button
            type="button"
            onClick={() => setPaletteOpen(true)}
            className="mt-6 flex min-h-[52px] w-full flex-col items-center justify-center gap-1 rounded-xl px-1 text-[10px] font-medium text-ink-soft transition hover:bg-[#f2f2f7] hover:text-ink"
          >
            <Search size={19} strokeWidth={1.75} />
            {t('Перейти')}
            <span className="flex items-center gap-0.5 text-[9px] text-ink-soft"><Command size={9} />K</span>
          </button>

          <div className="mt-auto flex flex-col items-center gap-3">
            {demoMode && <span className="rounded-full border border-[#e5e5ea] bg-white px-2 py-1 text-[9px] font-semibold uppercase tracking-[0.08em] text-ink">{t('Демо')}</span>}
          </div>
        </aside>

        {/* Контент */}
        <div className="flex min-h-0 min-w-0 flex-1 flex-col">
          {demoMode && (
            <div className="flex shrink-0 items-center justify-center gap-2 border-b border-[#e5e5ea] bg-[#fef3e7] px-4 py-1.5 text-center text-xs text-[#9a3412]">
              <span className="h-1.5 w-1.5 rounded-full bg-[#fb923c]" />
              <span>{t('Демо-режим: сервер не подключён, данные учебные.')}</span>
              <button onClick={onLogout} className="font-medium text-[#0071e3] underline decoration-[#0071e3]/30 underline-offset-2">{t('Выйти')}</button>
            </div>
          )}
          <div className="min-h-0 min-w-0 flex-1"><Outlet /></div>
        </div>
      </div>

      {/* Мобильная нижняя навигация */}
      <nav className="flex shrink-0 border-t border-[#e5e5ea] bg-white px-3 pb-[env(safe-area-inset-bottom)] md:hidden shadow-[0_-1px_0_rgba(0,0,0,0.04)]" aria-label={t('Основная навигация')}>
        {mobileNavItem('/mail', t('Почта'), Mail, demoMode ? demoMailUnreadCount : undefined)}
        {mobileNavItem('/chat', t('Сообщения'), MessageSquare, demoMode ? demoChatUnreadCount : undefined)}
        <CalendarWidget className="mt-0.5 mb-0.5" />
        {canAdmin && mobileNavItem('/admin', t('Админ'), Settings2)}
      </nav>

      {/* Меню профиля */}
      {profileOpen && (
        <>
          <button type="button" aria-label={t('Закрыть меню')} className="fixed inset-0 z-40 cursor-default" onClick={() => setProfileOpen(false)} />
          <div className="fixed right-3 top-16 z-50 w-[280px] animate-scale-in rounded-2xl border border-[#e5e5ea] bg-white p-2 shadow-[0_10px_40px_rgba(0,0,0,0.10)] md:bottom-4 md:left-[86px] md:right-auto md:top-auto">
            <div className="flex items-center gap-3 px-3 py-3">
              <Avatar name={user.displayName} size={42} presence />
              <div className="min-w-0">
                <div className="truncate text-sm font-semibold text-ink">{user.displayName}</div>
                <div className="truncate text-xs text-ink-soft">{user.email}</div>
                <div className="mt-1 flex items-center gap-1.5 text-[11px] text-ink-soft"><span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />{t('В сети')}{user.groups.length > 0 && ` · ${user.groups.map((code) => systemGroupName(locale, code)).join(', ')}`}</div>
              </div>
            </div>
            <div className="hairline-t py-1.5">
              <div className="flex items-center gap-2.5 px-2.5 py-2 text-sm text-ink-soft">
                <Globe size={17} strokeWidth={1.8} />
                <span className="min-w-0 flex-1">{t('Язык интерфейса')}</span>
                <LanguageSwitcher />
              </div>
              <button type="button" onClick={() => { setProfileOpen(false); setSecurityOpen(true); }} className="nav-row">
                <ShieldCheck size={17} strokeWidth={1.8} /> {t('Безопасность')}
              </button>
              <button type="button" onClick={onLogout} className="nav-row text-[#0071e3] hover:bg-[#f2f2f7] hover:text-[#0071e3]">
                <LogOut size={17} strokeWidth={1.8} /> {demoMode ? t('Выйти из демо') : t('Выйти')}
              </button>
            </div>
          </div>
        </>
      )}

      {/* Command palette */}
      {paletteOpen && (
        <div className="fixed inset-0 z-[60] flex items-start justify-center bg-black/25 px-4 pt-[10vh] backdrop-blur-md" onClick={() => setPaletteOpen(false)}>
          <section className="w-full max-w-xl animate-scale-in overflow-hidden rounded-2xl border border-[#E5E5EA] bg-white shadow-[0_20px_50px_rgba(23,24,28,0.2)]" onClick={(e) => e.stopPropagation()} aria-label={t('Быстрый переход')}>
            <div className="flex items-center gap-3 border-b border-[#E5E5EA] px-4 py-3">
              <Search size={18} className="shrink-0 text-ink-soft" />
              <input
                autoFocus
                value={paletteQuery}
                onChange={(e) => setPaletteQuery(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && visibleActions[0]) {
                    setPaletteOpen(false);
                    visibleActions[0].run();
                  }
                }}
                placeholder={t('Перейти или выполнить действие…')}
                aria-label={t('Быстрый переход')}
                className="h-10 min-w-0 flex-1 border-0 bg-transparent text-[14px] outline-none placeholder:text-ink-soft focus:ring-0"
              />
              <span className="kbd">Esc</span>
            </div>
            <div className="max-h-[52vh] overflow-y-auto p-1">
              {visibleActions.map((action) => (
                <button
                  key={action.id}
                  type="button"
                  onClick={() => { setPaletteOpen(false); action.run(); }}
                  className="flex w-full items-center gap-3 rounded-xl px-3 py-2 text-left transition hover:bg-[#F2F2F7] hover:text-ink"
                >
                  <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-[#F2F2F7] text-ink-soft"><action.icon size={17} strokeWidth={1.7} /></span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium text-ink">{action.label}</span>
                    <span className="block truncate text-xs text-ink-soft">{action.description}</span>
                  </span>
                  {action.shortcut && <span className="kbd">{action.shortcut}</span>}
                </button>
              ))}
              {visibleActions.length === 0 && <p className="px-4 py-10 text-center text-sm text-ink-soft">{t('Ничего не найдено')}</p>}
            </div>
          </section>
        </div>
      )}

      {user.mustChangePassword && !demoMode && (
        <ChangePasswordModal onChanged={() => updateUser({ mustChangePassword: false })} />
      )}
      {securityOpen && <SecuritySettingsModal onClose={() => setSecurityOpen(false)} />}
    </div>
  );
}
