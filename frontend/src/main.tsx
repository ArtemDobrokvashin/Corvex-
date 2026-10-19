import React, { lazy, Suspense } from 'react';
import ReactDOM from 'react-dom/client';
import { createBrowserRouter, Navigate, RouterProvider } from 'react-router-dom';
import AppShell from './app/AppShell';
import LoginPage from './features/auth/LoginPage';
import '@fontsource-variable/inter';
import './index.css';

// Ленивая загрузка модулей (ТЗ §10.4): админка не тянется в бандл обычного пользователя
const MailPage = lazy(() => import('./features/mail/MailPage'));
const ChatPage = lazy(() => import('./features/chat/ChatPage'));
const AdminPage = lazy(() => import('./features/admin/AdminPage'));

const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to="/mail" replace /> },
      { path: 'mail', element: <Suspense fallback={<div className="flex h-full items-center justify-center text-sm text-muted">Загружаем почту…</div>}><MailPage /></Suspense> },
      { path: 'chat', element: <Suspense fallback={<div className="flex h-full items-center justify-center text-sm text-muted">Загружаем сообщения…</div>}><ChatPage /></Suspense> },
      { path: 'admin', element: <Suspense fallback={<div className="flex h-full items-center justify-center text-sm text-muted">Загружаем администрирование…</div>}><AdminPage /></Suspense> },
    ],
  },
  { path: '*', element: <Navigate to="/login" replace /> },
]);

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode><RouterProvider router={router} /></React.StrictMode>,
);
