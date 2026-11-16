import React, { lazy, Suspense } from 'react';
import ReactDOM from 'react-dom/client';
import { createBrowserRouter, Navigate, RouterProvider } from 'react-router-dom';
import AppShell from './app/AppShell';
import RouteError from './app/RouteError';
import LoginPage from './features/auth/LoginPage';
import { useT } from './i18n';
import { ThemeProvider } from './components/ThemeProvider';
import { tryReloadForStaleChunk } from './lib/chunkReload';
import '@fontsource-variable/inter';
import './index.css';

// Ленивая загрузка модулей (ТЗ §10.4): админка не тянется в бандл обычного пользователя
const MailPage = lazy(() => import('./features/mail/MailPage'));
const ChatPage = lazy(() => import('./features/chat/ChatPage'));
const CalendarPage = lazy(() => import('./features/calendar/CalendarPage'));
const FilesPage = lazy(() => import('./features/files/FilesPage'));
const AdminPage = lazy(() => import('./features/admin/AdminPage'));

function RouteLoading({ source }: { source: string }) {
  const t = useT();
  return <div className="flex h-full items-center justify-center text-sm text-muted">{t(source)}</div>;
}

// Передеплой удаляет старые чанки. Vite сообщает об этом отдельным событием,
// но перезагружаемся не более одного раза за кулдаун (см. chunkReload).
window.addEventListener('vite:preloadError', (event) => {
  if (tryReloadForStaleChunk() === 'reloaded') event.preventDefault();
});

const router = createBrowserRouter([
  { path: '/login', element: <LoginPage />, errorElement: <RouteError /> },
  {
    path: '/',
    element: <AppShell />,
    errorElement: <RouteError />,
    children: [
      { index: true, element: <Navigate to="/mail" replace /> },
      { path: 'mail', element: <Suspense fallback={<RouteLoading source="Загружаем почту…" />}><MailPage /></Suspense> },
      { path: 'chat', element: <Suspense fallback={<RouteLoading source="Загружаем сообщения…" />}><ChatPage /></Suspense> },
      { path: 'calendar', element: <Suspense fallback={<RouteLoading source="Загружаем календарь…" />}><CalendarPage /></Suspense> },
      { path: 'files', element: <Suspense fallback={<RouteLoading source="Загружаем файлы…" />}><FilesPage /></Suspense> },
      { path: 'admin/:section?', element: <Suspense fallback={<RouteLoading source="Загружаем администрирование…" />}><AdminPage /></Suspense> },
    ],
  },
  { path: '*', element: <Navigate to="/login" replace />, errorElement: <RouteError /> },
]);

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ThemeProvider />
    <RouterProvider router={router} />
  </React.StrictMode>,
);
