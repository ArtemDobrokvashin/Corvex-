import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { errorText, fetchMe, isServerUnreachable, login, verifyMfa } from '../../api/authApi';
import { DEMO_USER } from '../../api/demoData';
import { useSession } from '../../store/sessionStore';

/**
 * Вход: локальная учётка (AD — этап 5), второй шаг TOTP при включённой 2FA.
 * Неверный пароль — честная ошибка (не демо!). Демо-режим остался только как
 * явная кнопка витрины и предлагается при недоступности сервера.
 */
export default function LoginPage() {
  const navigate = useNavigate();
  const setSession = useSession((s) => s.setSession);
  const [error, setError] = useState('');
  const [serverDown, setServerDown] = useState(false);
  const [mfaTicket, setMfaTicket] = useState<string | null>(null);
  const [mfaCode, setMfaCode] = useState('');
  const [loading, setLoading] = useState(false);

  async function finishLogin() {
    const me = await fetchMe();
    sessionStorage.removeItem('corvex.demo');
    setSession(me, false);
    navigate('/mail');
  }

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError('');
    setServerDown(false);
    setLoading(true);
    const data = new FormData(e.currentTarget);
    try {
      const result = await login(String(data.get('username') ?? ''), String(data.get('password') ?? ''));
      if (result.mfaRequired) {
        setMfaTicket(result.mfaTicket);
      } else {
        await finishLogin();
      }
    } catch (err) {
      setError(errorText(err));
      setServerDown(isServerUnreachable(err));
    } finally {
      setLoading(false);
    }
  }

  async function onMfaSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!mfaTicket) return;
    setError('');
    setLoading(true);
    try {
      await verifyMfa(mfaTicket, mfaCode.trim());
      await finishLogin();
    } catch (err) {
      setError(errorText(err));
      setServerDown(isServerUnreachable(err));
    } finally {
      setLoading(false);
    }
  }

  function enterDemo() {
    sessionStorage.setItem('corvex.demo', '1');
    setSession(DEMO_USER, true);
    navigate('/mail');
  }

  return (
    <div className="flex min-h-full items-center justify-center bg-gradient-to-br from-blue-50 to-slate-100 p-4">
      <div className="w-full max-w-sm rounded-2xl bg-white p-6 shadow-lg">
        <div className="mb-1 flex items-center gap-2 text-2xl font-bold"><span className="text-brand">◆</span> Corvex</div>
        <p className="mb-5 text-sm text-slate-500">Корпоративная почта и чат компании</p>

        {!mfaTicket ? (
          <form onSubmit={onSubmit}>
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
            <p className="mt-3 text-center text-xs text-slate-400">Первый вход администратора — пароль из лога auth-service (см. инструкцию установки)</p>
          </form>
        ) : (
          <form onSubmit={onMfaSubmit}>
            <label className="mb-4 block text-sm">Код из приложения-аутентификатора
              <input value={mfaCode} onChange={(e) => setMfaCode(e.target.value)} required inputMode="numeric" pattern="[0-9]{6}" placeholder="123456" autoFocus
                className="mt-1 w-full rounded-lg border px-3 py-2.5 text-center text-xl tracking-[0.4em] outline-none focus:border-brand" />
            </label>
            {error && <p className="mb-3 rounded bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}
            <button disabled={loading} className="w-full rounded-lg bg-brand py-2.5 font-medium text-white hover:bg-brand-dark disabled:opacity-60">
              {loading ? 'Проверка…' : 'Подтвердить'}
            </button>
            <button type="button" onClick={() => { setMfaTicket(null); setError(''); }} className="mt-3 w-full text-center text-xs text-slate-400 underline">← Назад ко входу</button>
          </form>
        )}

        <div className="mt-5 border-t pt-4">
          {serverDown && (
            <p className="mb-2 rounded bg-amber-50 px-3 py-2 text-xs text-amber-800">
              Сервер не отвечает. Можно посмотреть интерфейс на учебных данных — это не настоящий вход.
            </p>
          )}
          <button type="button" onClick={enterDemo} className="w-full rounded-lg border py-2 text-sm text-slate-600 hover:bg-slate-50">
            Посмотреть демо без сервера
          </button>
        </div>
      </div>
    </div>
  );
}
