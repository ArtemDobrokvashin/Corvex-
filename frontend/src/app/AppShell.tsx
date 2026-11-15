import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Bell,
  Calendar as CalendarIcon,
  ChevronUp,
  Clock as ClockIcon,
  LogOut,
  Mail,
  Maximize2,
  Menu,
  MessageSquare,
  PanelLeftClose,
  PanelLeftOpen,
  Search,
  Settings2,
  Shield,
  ShieldCheck,
  X,
} from 'lucide-react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { logout as apiLogout, restoreSession } from '../api/authApi';
import { DEMO_MAILS, DEMO_ROOMS, getDemoUser } from '../api/demoData';
import { setAccessToken } from '../api/httpClient';
import CalendarSidebarWidget from '../components/CalendarSidebarWidget';
import Clock from '../components/Clock';
import CommandPalette from '../components/CommandPalette';
import { SidebarSlotContext } from '../components/SidebarSlot';
import { Avatar, LoadingScreen, LogoMark, StatusBadge } from '../components/ui';
import { canAccessAdmin } from '../features/admin/adminAccess';
import ChangePasswordModal from '../features/auth/ChangePasswordModal';
import SecuritySettingsModal from '../features/auth/SecuritySettingsModal';
import { LanguageSwitcher, systemGroupName, useI18n } from '../i18n';
import {
  CalendarEvent,
  formatIsoDate,
  getEventsForDate,
  getDueReminder,
  reminderKey,
  useCalendarStore,
} from '../store/calendarStore';
import { demoMailIsSeen, useSession } from '../store/sessionStore';

const SIDEBAR_COLLAPSED_KEY = 'corvex.sidebar.collapsed';

export default function AppShell() {
  const { locale, t } = useI18n();
  const {
    user,
    demoMode,
    bootstrapped,
    demoReadMailIds,
    demoUnreadMailIds,
    demoReadRoomIds,
    has,
    setSession,
    clearSession,
    setBootstrapped,
    updateUser,
  } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const restoreStarted = useRef(false);

  const [sidebarCollapsed, setSidebarCollapsed] = useState<boolean>(() => {
    try {
      return localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1';
    } catch {
      return false;
    }
  });
  const [slotHost, setSlotHost] = useState<HTMLElement | null>(null);
  const [mobileDrawerOpen, setMobileDrawerOpen] = useState(false);
  const [profileMenuOpen, setProfileMenuOpen] = useState(false);
  const [commandOpen, setCommandOpen] = useState(false);
  const [securityOpen, setSecurityOpen] = useState(false);
  const [dueReminderEvent, setDueReminderEvent] = useState<CalendarEvent | null>(null);

  const profileRef = useRef<HTMLDivElement>(null);

  const widgetOpen = useCalendarStore((s) => s.widgetOpen);
  const setWidgetOpen = useCalendarStore((s) => s.setWidgetOpen);
  const toggleWidget = useCalendarStore((s) => s.toggleWidget);
  const events = useCalendarStore((s) => s.events);
  const dismissedReminderKeys = useCalendarStore((s) => s.dismissedReminderKeys);
  const snoozedUntilMs = useCalendarStore((s) => s.snoozedUntilMs);
  const dismissReminder = useCalendarStore((s) => s.dismissReminder);
  const setCalendarOwner = useCalendarStore((s) => s.setOwner);
  const setDemoCalendarLocale = useCalendarStore((s) => s.setDemoLocale);
  const snoozeEventReminder = useCalendarStore((s) => s.snoozeEventReminder);
  const setSelectedDate = useCalendarStore((s) => s.setSelectedDate);

  // Calendar data is kept per signed-in account in this browser, not on a server.
  useEffect(() => {
    setCalendarOwner(user?.id ?? null, demoMode);
  }, [user?.id, demoMode, setCalendarOwner]);

  useEffect(() => {
    if (demoMode && user) setDemoCalendarLocale(locale);
  }, [demoMode, locale, setDemoCalendarLocale, user?.id]);

  // Restore session on initial mount
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bootstrapped, navigate, setSession, setBootstrapped]);

  useEffect(() => {
    if (demoMode) updateUser({ displayName: getDemoUser(locale).displayName });
  }, [demoMode, locale, updateUser]);

  const toggleSidebarCollapsed = () => {
    setSidebarCollapsed((prev) => {
      const next = !prev;
      try {
        localStorage.setItem(SIDEBAR_COLLAPSED_KEY, next ? '1' : '0');
      } catch {
        // ignore storage restrictions
      }
      return next;
    });
  };

  useEffect(() => {
    setMobileDrawerOpen(false);
    setProfileMenuOpen(false);
    if (location.pathname.startsWith('/calendar')) {
      setWidgetOpen(false);
    }
  }, [location.pathname, setWidgetOpen]);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setCommandOpen((prev) => !prev);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  useEffect(() => {
    if (!profileMenuOpen) return undefined;
    const onPointerDown = (e: PointerEvent) => {
      if (!profileRef.current?.contains(e.target as Node)) setProfileMenuOpen(false);
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setProfileMenuOpen(false);
    };
    window.addEventListener('pointerdown', onPointerDown);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('pointerdown', onPointerDown);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [profileMenuOpen]);

  // In-app reminders are checked while the workspace is open.
  useEffect(() => {
    const checkReminders = () => setDueReminderEvent(
      getDueReminder(events, dismissedReminderKeys, snoozedUntilMs, new Date()),
    );
    checkReminders();
    const timer = window.setInterval(checkReminders, 20_000);
    return () => window.clearInterval(timer);
  }, [dismissedReminderKeys, events, snoozedUntilMs]);

  async function onLogout() {
    if (!demoMode) {
      try {
        await apiLogout();
      } catch {
        // local logout proceeds even if server is unreachable
      }
    }
    setAccessToken(null);
    setCalendarOwner(null);
    sessionStorage.removeItem('corvex.demo');
    clearSession();
    navigate('/login', { replace: true });
  }

  const todayEventCount = useMemo(
    () => getEventsForDate(events, formatIsoDate(new Date())).length,
    [events],
  );

  if (!bootstrapped) return <LoadingScreen label={t('Восстанавливаем сессию')} />;
  if (!user) return <LoadingScreen label={t('Переходим ко входу')} />;

  const unreadMail = demoMode
    ? DEMO_MAILS.filter((m) => !demoMailIsSeen(m, demoReadMailIds, demoUnreadMailIds)).length
    : 0;
  const unreadChat = demoMode
    ? DEMO_ROOMS.reduce((acc, r) => acc + (demoReadRoomIds.includes(r.id) ? 0 : r.unread), 0)
    : 0;

  const canSeeAdmin = canAccessAdmin(has);
  const onCalendarRoute = location.pathname.startsWith('/calendar');
  const sidebarWidthPx = sidebarCollapsed ? 64 : 252;

  return (
    <SidebarSlotContext.Provider
      value={{
        inAppShell: true,
        slotEl: slotHost,
        mobileSlotEl: null,
        collapsed: sidebarCollapsed,
        openPalette: () => setCommandOpen(true),
        onOpenCommandPalette: () => setCommandOpen(true),
        onOpenCalendarWidget: () => setWidgetOpen(true),
        onOpenSecurity: () => { setMobileDrawerOpen(false); setSecurityOpen(true); },
        onCloseNavigation: () => setMobileDrawerOpen(false),
        onLogout,
      }}
    >
      <div className="flex h-dvh w-screen flex-col overflow-hidden bg-surface md:flex-row">
        {/* Mobile (< 768px) 52px top bar */}
        <header className="flex h-[52px] shrink-0 items-center justify-between border-b border-sidebar-line bg-sidebar px-3 text-white md:hidden">
          <div className="flex items-center gap-2.5">
            <button
              type="button"
              onClick={() => setMobileDrawerOpen(true)}
              aria-label={t('Открыть навигацию')}
              className="inline-flex h-8 w-8 items-center justify-center rounded-lg text-sidebar-text hover:bg-sidebar-hover hover:text-white"
            >
              <Menu size={18} />
            </button>
            <span className="inline-flex rounded-[9px] border border-white/[0.14]">
              <LogoMark size={28} />
            </span>
            <span className="text-[17px] font-semibold tracking-[-0.02em] text-white">Corvex</span>
          </div>

          <div className="flex items-center gap-1.5">
            <button
              type="button"
              onClick={() => toggleWidget()}
              aria-label={t('Мой день')}
              className={`inline-flex h-8 w-8 items-center justify-center rounded-lg transition ${
                widgetOpen ? 'bg-accent text-white' : 'text-sidebar-text hover:bg-sidebar-hover hover:text-white'
              }`}
            >
              <CalendarIcon size={16} />
            </button>
            <Clock variant="dark" onOpenCalendar={() => navigate('/calendar')} />
          </div>
        </header>

        {/* Mobile drawer backdrop */}
        {mobileDrawerOpen && (
          <div
            onClick={() => setMobileDrawerOpen(false)}
            className="fixed inset-0 z-40 bg-ink/45 md:hidden"
            aria-hidden="true"
          />
        )}

        {/* Left Midnight Navy Sidebar (#0B1221): 272px drawer on <768px, static 252px/64px on >=768px */}
        <aside
          aria-label={t('Боковая навигация Corvex')}
          className={`fixed inset-y-0 left-0 z-50 flex w-[272px] shrink-0 flex-col border-r border-sidebar-line bg-sidebar text-sidebar-text transition-[width,transform] duration-150 md:static md:z-20 md:translate-x-0 ${
            mobileDrawerOpen ? 'translate-x-0' : '-translate-x-full'
          } ${sidebarCollapsed ? 'md:w-[64px]' : 'md:w-[252px]'}`}
        >
          {/* 56px Brand Header */}
          <div
            className={`flex h-14 shrink-0 items-center border-b border-sidebar-line ${
              sidebarCollapsed ? 'justify-between px-3.5 md:justify-center md:px-2' : 'justify-between px-3.5'
            }`}
          >
            <div className={`flex min-w-0 items-center gap-2.5 ${sidebarCollapsed ? 'md:hidden' : ''}`}>
              <span className="inline-flex shrink-0 rounded-[9px] border border-white/[0.14]">
                <LogoMark size={30} />
              </span>
              <span className="truncate text-[18px] font-semibold tracking-[-0.02em] text-white">
                Corvex
              </span>
            </div>

            {sidebarCollapsed && (
              <button
                type="button"
                onClick={toggleSidebarCollapsed}
                title={t('Развернуть боковую панель')}
                aria-label={t('Развернуть боковую панель')}
                className="hidden h-9 w-9 items-center justify-center rounded-[9px] border border-white/[0.14] transition hover:border-white/30 md:inline-flex"
              >
                <LogoMark size={28} />
              </button>
            )}

            <div className="flex items-center gap-1">
              <button
                type="button"
                onClick={() => setCommandOpen(true)}
                title={`${t('Быстрый переход')} (⌘K)`}
                aria-label={t('Быстрый переход')}
                className={`inline-flex h-7 w-7 items-center justify-center rounded-lg text-sidebar-muted transition hover:bg-sidebar-hover hover:text-white ${
                  sidebarCollapsed ? 'md:hidden' : ''
                }`}
              >
                <Search size={15} />
              </button>

              <button
                type="button"
                onClick={toggleSidebarCollapsed}
                title={sidebarCollapsed ? t('Развернуть боковую панель') : t('Свернуть боковую панель')}
                aria-label={sidebarCollapsed ? t('Развернуть боковую панель') : t('Свернуть боковую панель')}
                className={`hidden h-7 w-7 items-center justify-center rounded-lg text-sidebar-muted transition hover:bg-sidebar-hover hover:text-white md:inline-flex ${
                  sidebarCollapsed ? 'md:hidden' : ''
                }`}
              >
                {sidebarCollapsed ? <PanelLeftOpen size={16} /> : <PanelLeftClose size={16} />}
              </button>

              <button
                type="button"
                onClick={() => setMobileDrawerOpen(false)}
                aria-label={t('Закрыть навигацию')}
                className="inline-flex h-7 w-7 items-center justify-center rounded-lg text-sidebar-muted hover:bg-sidebar-hover hover:text-white md:hidden"
              >
                <X size={16} />
              </button>
            </div>
          </div>

          {sidebarCollapsed && (
            <div className="hidden justify-center border-b border-sidebar-line py-1.5 md:flex">
              <button
                type="button"
                onClick={toggleSidebarCollapsed}
                title={t('Развернуть боковую панель')}
                aria-label={t('Развернуть боковую панель')}
                className="inline-flex h-7 w-7 items-center justify-center rounded-lg text-sidebar-muted transition hover:bg-sidebar-hover hover:text-white"
              >
                <PanelLeftOpen size={15} />
              </button>
            </div>
          )}

          {/* Primary Module Navigation */}
          <nav
            aria-label={t('Основная навигация')}
            className={`shrink-0 space-y-0.5 border-b border-sidebar-line py-2.5 ${
              sidebarCollapsed ? 'px-2.5 md:px-2' : 'px-2.5'
            }`}
          >
            <NavLink
              to="/mail"
              title={t('Почта')}
              className={({ isActive }) =>
                `sidebar-item ${isActive ? 'sidebar-item-active' : ''} ${
                  sidebarCollapsed ? 'md:justify-center md:px-0' : ''
                }`
              }
            >
              <Mail size={17} strokeWidth={1.85} className="shrink-0" />
              <span className={`min-w-0 flex-1 truncate ${sidebarCollapsed ? 'md:hidden' : ''}`}>
                {t('Почта')}
              </span>
              {unreadMail > 0 && (
                <span
                  className={`rounded-full bg-accent px-1.5 py-0.5 text-[10.5px] font-semibold leading-none text-white tabular-nums ${
                    sidebarCollapsed ? 'md:hidden' : ''
                  }`}
                >
                  {unreadMail}
                </span>
              )}
            </NavLink>

            <NavLink
              to="/chat"
              title={t('Чаты')}
              className={({ isActive }) =>
                `sidebar-item ${isActive ? 'sidebar-item-active' : ''} ${
                  sidebarCollapsed ? 'md:justify-center md:px-0' : ''
                }`
              }
            >
              <MessageSquare size={17} strokeWidth={1.85} className="shrink-0" />
              <span className={`min-w-0 flex-1 truncate ${sidebarCollapsed ? 'md:hidden' : ''}`}>
                {t('Чаты')}
              </span>
              {unreadChat > 0 && (
                <span
                  className={`rounded-full bg-accent px-1.5 py-0.5 text-[10.5px] font-semibold leading-none text-white tabular-nums ${
                    sidebarCollapsed ? 'md:hidden' : ''
                  }`}
                >
                  {unreadChat}
                </span>
              )}
            </NavLink>

            {/* Календарь: открывает боковой виджет поверх работы + быстрый переход в /calendar */}
            <div
              className={`group flex items-center rounded-lg transition ${
                onCalendarRoute || widgetOpen
                  ? 'bg-sidebar-active text-white'
                  : 'text-sidebar-text hover:bg-sidebar-hover hover:text-white'
              }`}
            >
              <button
                type="button"
                title={onCalendarRoute ? t('Календарь') : t('Открыть виджет календаря')}
                onClick={() => {
                  if (onCalendarRoute) {
                    navigate('/calendar');
                  } else {
                    setMobileDrawerOpen(false);
                    toggleWidget();
                  }
                }}
                onDoubleClick={() => {
                  setWidgetOpen(false);
                  navigate('/calendar');
                }}
                aria-expanded={!onCalendarRoute ? widgetOpen : undefined}
                className={`relative flex h-9 min-w-0 flex-1 items-center gap-2.5 px-2.5 text-left text-[13.5px] font-medium ${
                  sidebarCollapsed ? 'md:justify-center md:px-0' : ''
                }`}
              >
                {(onCalendarRoute || widgetOpen) && (
                  <span
                    className="absolute bottom-1.5 left-0 top-1.5 w-[3px] rounded-r bg-accent"
                    aria-hidden="true"
                  />
                )}
                <CalendarIcon
                  size={17}
                  strokeWidth={1.85}
                  className={`shrink-0 ${onCalendarRoute || widgetOpen ? 'text-[#60A5FA]' : ''}`}
                />
                <span className={`min-w-0 flex-1 truncate ${sidebarCollapsed ? 'md:hidden' : ''}`}>
                  {t('Календарь')}
                </span>
                {todayEventCount > 0 && (
                  <span
                    className={`rounded-full bg-white/[0.12] px-1.5 py-0.5 text-[10.5px] font-semibold leading-none text-sidebar-text tabular-nums ${
                      sidebarCollapsed ? 'md:hidden' : ''
                    }`}
                  >
                    {todayEventCount}
                  </span>
                )}
              </button>

              {!onCalendarRoute && (
                <button
                  type="button"
                  onClick={() => {
                    setMobileDrawerOpen(false);
                    setWidgetOpen(false);
                    navigate('/calendar');
                  }}
                  title={t('Открыть полный календарь')}
                  aria-label={t('Открыть полный календарь')}
                  className={`mr-1 inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-sidebar-muted transition hover:bg-white/[0.1] hover:text-white ${
                    sidebarCollapsed ? 'md:hidden' : ''
                  }`}
                >
                  <Maximize2 size={13} strokeWidth={1.9} />
                </button>
              )}
            </div>

            {canSeeAdmin && (
              <NavLink
                to="/admin"
                title={t('Администрирование')}
                className={({ isActive }) =>
                  `sidebar-item ${isActive ? 'sidebar-item-active' : ''} ${
                    sidebarCollapsed ? 'md:justify-center md:px-0' : ''
                  }`
                }
              >
                <Shield size={17} strokeWidth={1.85} className="shrink-0" />
                <span className={`min-w-0 flex-1 truncate ${sidebarCollapsed ? 'md:hidden' : ''}`}>
                  {t('Админ')}
                </span>
              </NavLink>
            )}
          </nav>

          {/* Контекстный слот активного модуля (#corvex-sidebar-slot) */}
          <div
            id="corvex-sidebar-slot"
            ref={setSlotHost}
            className={`dark-scroll min-h-0 flex-1 overflow-y-auto ${
              sidebarCollapsed ? 'md:hidden' : ''
            }`}
          />
          {sidebarCollapsed && <div className="hidden flex-1 md:block" />}

          {/* Нижний блок профиля 64px */}
          <div
            ref={profileRef}
            className="relative flex h-16 shrink-0 items-center border-t border-sidebar-line px-2.5"
          >
            <button
              type="button"
              onClick={() => setProfileMenuOpen((v) => !v)}
              aria-expanded={profileMenuOpen}
              aria-label={t('Профиль пользователя')}
              className={`flex w-full items-center gap-2.5 rounded-lg p-1.5 text-left transition hover:bg-sidebar-hover ${
                sidebarCollapsed ? 'md:justify-center' : ''
              }`}
            >
              <Avatar name={user.displayName || user.username || 'Corvex'} size={34} presence />
              <div className={`min-w-0 flex-1 ${sidebarCollapsed ? 'md:hidden' : ''}`}>
                <div className="truncate text-[13px] font-semibold text-white">
                  {user.displayName || user.username}
                </div>
                <div className="truncate text-[11.5px] text-sidebar-muted">
                  {user.email}
                </div>
              </div>
              <ChevronUp
                size={15}
                className={`shrink-0 text-sidebar-muted transition ${
                  profileMenuOpen ? 'rotate-180 text-white' : ''
                } ${sidebarCollapsed ? 'md:hidden' : ''}`}
              />
            </button>

            {profileMenuOpen && (
              <div
                role="menu"
                aria-label={t('Профиль пользователя')}
                className="absolute bottom-[68px] left-2.5 z-30 w-[256px] animate-scale-in rounded-xl border border-line-control bg-surface p-1.5 text-ink shadow-pop"
              >
                <div className="border-b border-line-subtle px-2.5 py-2">
                  <div className="truncate text-[13px] font-semibold text-ink">
                    {user.displayName}
                  </div>
                  <div className="truncate text-xs text-muted">{user.email}</div>
                  {user.groups.length > 0 && (
                    <div className="mt-1 truncate text-[11px] text-faint">
                      {user.groups.map((code) => systemGroupName(locale, code)).join(', ')}
                    </div>
                  )}
                </div>

                <div className="py-1">
                  {has('MAIL_READ_OWN') && <button
                    type="button"
                    role="menuitem"
                    onClick={() => {
                      setProfileMenuOpen(false);
                      setMobileDrawerOpen(false);
                      navigate('/mail?settings=1');
                    }}
                    className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[13px] text-ink-soft transition hover:bg-canvas hover:text-ink"
                  >
                    <Settings2 size={15} className="text-muted" />
                    <span>{t('Настройки почты')}</span>
                  </button>}
                  <button
                    type="button"
                    role="menuitem"
                    onClick={() => {
                      setProfileMenuOpen(false);
                      setSecurityOpen(true);
                    }}
                    className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[13px] text-ink-soft transition hover:bg-canvas hover:text-ink"
                  >
                    <ShieldCheck size={15} className="text-muted" />
                    <span>{t('Безопасность и 2FA')}</span>
                  </button>

                  <div className="flex items-center justify-between px-2.5 py-1.5">
                    <span className="text-xs text-muted">{t('Язык интерфейса')}</span>
                    <LanguageSwitcher />
                  </div>
                </div>

                <div className="border-t border-line-subtle pt-1">
                  <button
                    type="button"
                    role="menuitem"
                    onClick={() => {
                      setProfileMenuOpen(false);
                      void onLogout();
                    }}
                    className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[13px] font-medium text-danger transition hover:bg-danger-soft"
                  >
                    <LogOut size={15} />
                    <span>{demoMode ? t('Выйти из демо') : t('Выйти')}</span>
                  </button>
                </div>
              </div>
            )}
          </div>
        </aside>

        {/* Sidebar Calendar Widget */}
        {widgetOpen && !onCalendarRoute && (
          <CalendarSidebarWidget
            sidebarOffsetPx={sidebarWidthPx}
            onClose={() => setWidgetOpen(false)}
          />
        )}

        {/* Crisp White Hairline Workspace */}
        <main className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden bg-surface pb-14 md:pb-0">
          <Outlet />
        </main>

        {/* Mobile (< 768px) 56px bottom navigation bar */}
        <nav
          aria-label={t('Нижняя навигация')}
          className="fixed inset-x-0 bottom-0 z-40 flex h-14 items-center justify-around border-t border-line bg-surface px-2 md:hidden"
        >
          <NavLink
            to="/mail"
            className={({ isActive }) =>
              `flex flex-col items-center gap-0.5 px-3 py-1 text-[11px] font-medium ${
                isActive ? 'text-accent' : 'text-muted'
              }`
            }
          >
            <Mail size={18} />
            <span>{t('Почта')}</span>
          </NavLink>
          <NavLink
            to="/chat"
            className={({ isActive }) =>
              `flex flex-col items-center gap-0.5 px-3 py-1 text-[11px] font-medium ${
                isActive ? 'text-accent' : 'text-muted'
              }`
            }
          >
            <MessageSquare size={18} />
            <span>{t('Чаты')}</span>
          </NavLink>
          <NavLink
            to="/calendar"
            className={({ isActive }) =>
              `flex flex-col items-center gap-0.5 px-3 py-1 text-[11px] font-medium ${
                isActive ? 'text-accent' : 'text-muted'
              }`
            }
          >
            <CalendarIcon size={18} />
            <span>{t('Календарь')}</span>
          </NavLink>
          {canSeeAdmin && (
            <NavLink
              to="/admin"
              className={({ isActive }) =>
                `flex flex-col items-center gap-0.5 px-3 py-1 text-[11px] font-medium ${
                  isActive ? 'text-accent' : 'text-muted'
                }`
              }
            >
              <Shield size={18} />
              <span>{t('Админ')}</span>
            </NavLink>
          )}
        </nav>

        {/* Live In-App Event Reminder Toast */}
        {dueReminderEvent && (
          <div
            role="alert"
            className="fixed bottom-16 right-4 z-50 w-[340px] animate-slide-up rounded-xl border border-line-control bg-surface p-3.5 shadow-pop md:bottom-4"
          >
            <div className="flex items-start gap-2.5">
              <span className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-[#EEF4FF] text-accent">
                <Bell size={16} />
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-[11px] font-semibold uppercase tracking-[0.05em] text-accent">
                    {t('Напоминание календаря')}
                  </span>
                  <StatusBadge
                    tone={
                      dueReminderEvent.priority === 'high'
                        ? 'critical'
                        : dueReminderEvent.priority === 'medium'
                          ? 'warning'
                          : 'positive'
                    }
                  >
                    {dueReminderEvent.priority === 'high'
                      ? t('Высокий')
                      : dueReminderEvent.priority === 'medium'
                        ? t('Средний')
                        : t('Низкий')}
                  </StatusBadge>
                </div>
                <h4 className="mt-1 truncate text-[13.5px] font-semibold text-ink">
                  {dueReminderEvent.title}
                </h4>
                <p className="mt-0.5 flex items-center gap-1.5 text-xs text-muted tabular-nums">
                  <ClockIcon size={12} />
                  <span>
                    {dueReminderEvent.allDay
                      ? t('Весь день')
                      : `${dueReminderEvent.startTime} – ${dueReminderEvent.endTime}`}
                  </span>
                </p>

                <div className="mt-2.5 flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() => {
                      setSelectedDate(dueReminderEvent.date);
                      dismissReminder(reminderKey(dueReminderEvent.id, dueReminderEvent.date));
                      setDueReminderEvent(null);
                      navigate(`/calendar?date=${dueReminderEvent.date}`);
                    }}
                    className="btn-primary h-7 px-2.5 text-xs"
                  >
                    {t('Открыть')}
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      snoozeEventReminder(dueReminderEvent.id, 10, dueReminderEvent.date);
                      setDueReminderEvent(null);
                    }}
                    className="btn-secondary h-7 px-2.5 text-xs"
                  >
                    {t('Отложить 10 мин')}
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      dismissReminder(reminderKey(dueReminderEvent.id, dueReminderEvent.date));
                      setDueReminderEvent(null);
                    }}
                    aria-label={t('Закрыть напоминание')}
                    className="ml-auto rounded p-1 text-faint hover:bg-canvas hover:text-ink"
                  >
                    <X size={14} />
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}

        <CommandPalette
          open={commandOpen}
          onClose={() => setCommandOpen(false)}
          canSeeAdmin={canSeeAdmin}
          onToggleCalendarWidget={() => toggleWidget()}
          onOpenSecurity={() => setSecurityOpen(true)}
          onLogout={() => void onLogout()}
          demoMode={demoMode}
        />

        {user.mustChangePassword && !demoMode && (
          <ChangePasswordModal onChanged={() => updateUser({ mustChangePassword: false })} />
        )}
        {securityOpen && <SecuritySettingsModal onClose={() => setSecurityOpen(false)} />}
      </div>
    </SidebarSlotContext.Provider>
  );
}
