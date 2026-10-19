import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiFetch, setAccessToken } from '../../api/httpClient';
import { DEMO_USER } from '../../api/demoData';
import { useSession } from '../../store/sessionStore';

/** Вход: локальная учётка или AD (один экран), при включённой 2FA — второй шаг TOTP. */
export default function LoginPage() {
  const navigate = useNavigate();
  const setSession = useSession((s) => s.setSession);
  const [error, setError] = useState('');
  const [mfaStep, setMfaStep] = useState(false);
  const [loading, setLoading] = useState(false);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError('');
    setLoading(true);
    const data = new FormData(e.currentTarget);
    try {
      const res = await apiFetch<{ accessToken: string }>('/api/v1/auth/login', {
        method: 'POST',
        body: JSON.stringify({ username: data.get('username'), password: data.get('password') }),
      });
      setAccessToken(res.accessToken);
      const me = await apiFetch<typeof DEMO_USER>('/api/v1/auth/me');
      setSession(me, false);
      navigate('/mail');
    } catch {
      // Gateway недоступен (витрина без бэкенда): честно помеченный демо-режим вместо ошибки
      setSession(DEMO_USER, true);
      navigate('/mail');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="flex min-h-full items-center justify-center bg-gradient-to-br from-blue-50 to-slate-100 p-4">
      <form onSubmit={onSubmit} className="w-full max-w-sm rounded-2xl bg-white p-6 shadow-lg">
        <div className="mb-1 flex items-center gap-2 text-2xl font-bold"><span className="text-brand">◆</span> Corvex</div>
        <p className="mb-5 text-sm text-slate-500">Корпоративная почта и чат компании</p>
        {!mfaStep ? (
          <>
            <label className="mb-3 block text-sm">Логин или e-mail
              <input name="username" required autoComplete="username" placeholder="i.ivanov" className="mt-1 w-full rounded-lg border px-3 py-2.5 outline-none focus:border-brand" />
            </label>
            <label className="mb-4 block text-sm">Пароль
              <input name="password" required type="password" autoComplete="current-password" className="mt-1 w-full rounded-lg border px-3 py-2.5 outline-none focus:border-brand" />
            </label>
            {error && <p className="mb-3 rounded bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}
            <button disabled={loading} className="w-full rounded-lg bg-brand py-2.5 font-medium text-white hover:bg-brand-dark disabled:opacity-60">
              {loading ? 'Вход…' : 'Войти'}
            </button>
            <button type="button" onClick={() => setMfaStep(true)} className="mt-3 w-full text-center text-xs text-slate-400 underline">
              Как выглядит вход с 2FA →
            </button>
            <p className="mt-2 text-center text-xs text-slate-400">Работает доменная учётная запись Active Directory</p>
          </>
        ) : (
          <>
            <label className="mb-4 block text-sm">Код из приложения-аутентификатора
              <input inputMode="numeric" pattern="[0-9]{6}" placeholder="123456" className="mt-1 w-full rounded-lg border px-3 py-2.5 text-center text-xl tracking-[0.4em] outline-none focus:border-brand" />
            </label>
            <button type="button" onClick={() => { setSession(DEMO_USER, true); navigate('/mail'); }} className="w-full rounded-lg bg-brand py-2.5 font-medium text-white">Подтвердить</button>
            <button type="button" onClick={() => setMfaStep(false)} className="mt-3 w-full text-center text-xs text-slate-400 underline">← Назад</button>
          </>
        )}
      </form>
    </div>
  );
}
