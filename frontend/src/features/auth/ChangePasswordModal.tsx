import { FormEvent, useState } from 'react';
import { changePassword, errorText } from '../../api/authApi';

/**
 * Обязательная смена временного пароля (mustChangePassword от сервера).
 * Правила зеркалят серверную PasswordPolicy: ≥10 символов, буквы+цифры.
 */
export default function ChangePasswordModal({ onChanged }: { onChanged: () => void }) {
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    const current = String(data.get('current') ?? '');
    const next = String(data.get('next') ?? '');
    const repeat = String(data.get('repeat') ?? '');
    if (next !== repeat) {
      setError('Новые пароли не совпадают');
      return;
    }
    if (next.length < 10 || !/[A-Za-zА-Яа-я]/.test(next) || !/[0-9]/.test(next)) {
      setError('Пароль должен быть не короче 10 символов и содержать буквы и цифры');
      return;
    }
    setError('');
    setLoading(true);
    try {
      await changePassword(current, next);
      onChanged();
    } catch (err) {
      setError(errorText(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <form onSubmit={onSubmit} className="w-full max-w-sm rounded-2xl bg-white p-6 shadow-xl">
        <h2 className="mb-1 text-lg font-semibold">Смените временный пароль</h2>
        <p className="mb-4 text-sm text-slate-500">Администратор выдал вам временный пароль. Задайте постоянный — без этого работа недоступна.</p>
        <label className="mb-3 block text-sm">Временный (текущий) пароль
          <input name="current" required type="password" autoComplete="current-password" className="mt-1 w-full rounded-lg border px-3 py-2.5 outline-none focus:border-brand" />
        </label>
        <label className="mb-3 block text-sm">Новый пароль
          <input name="next" required type="password" autoComplete="new-password" className="mt-1 w-full rounded-lg border px-3 py-2.5 outline-none focus:border-brand" />
        </label>
        <label className="mb-4 block text-sm">Повторите новый пароль
          <input name="repeat" required type="password" autoComplete="new-password" className="mt-1 w-full rounded-lg border px-3 py-2.5 outline-none focus:border-brand" />
        </label>
        {error && <p className="mb-3 rounded bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}
        <button disabled={loading} className="w-full rounded-lg bg-brand py-2.5 font-medium text-white hover:bg-brand-dark disabled:opacity-60">
          {loading ? 'Сохранение…' : 'Сменить пароль'}
        </button>
      </form>
    </div>
  );
}
