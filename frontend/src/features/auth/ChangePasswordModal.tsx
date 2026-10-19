import { FormEvent, useMemo, useState } from 'react';
import { Check, KeyRound, Loader2 } from 'lucide-react';
import { changePassword, errorText } from '../../api/authApi';
import { Notice } from '../../components/ui';

/**
 * Обязательная смена временного пароля (mustChangePassword от сервера).
 * Правила зеркалят серверную PasswordPolicy: ≥10 символов, буквы+цифры.
 */
export default function ChangePasswordModal({ onChanged }: { onChanged: () => void }) {
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [next, setNext] = useState('');
  const [repeat, setRepeat] = useState('');

  const checks = useMemo(() => [
    { label: 'Не короче 10 символов', ok: next.length >= 10 },
    { label: 'Есть буквы и цифры', ok: /[A-Za-zА-Яа-я]/.test(next) && /[0-9]/.test(next) },
    { label: 'Повтор совпадает', ok: next.length > 0 && next === repeat },
  ], [next, repeat]);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    const current = String(data.get('current') ?? '');
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
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 p-4 backdrop-blur-[3px]">
      <form onSubmit={onSubmit} className="w-full max-w-[420px] animate-scale-in rounded-[20px] border border-line bg-surface p-6 shadow-pop sm:p-7">
        <span className="flex h-12 w-12 items-center justify-center rounded-2xl bg-accent-soft text-accent-dark"><KeyRound size={22} /></span>
        <h2 className="mt-5 text-[22px] font-semibold tracking-[-0.025em]">Задайте постоянный пароль</h2>
        <p className="mb-6 mt-2 text-sm leading-relaxed text-muted">Администратор выдал временный пароль. Он перестанет подходить сразу после этой смены.</p>

        <div className="space-y-4">
          <div>
            <label className="field-label" htmlFor="change-current">Временный пароль</label>
            <input id="change-current" name="current" required type="password" autoComplete="current-password" className="field" />
          </div>
          <div>
            <label className="field-label" htmlFor="change-next">Новый пароль</label>
            <input id="change-next" name="next" required type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} className="field" />
          </div>
          <div>
            <label className="field-label" htmlFor="change-repeat">Повторите пароль</label>
            <input id="change-repeat" name="repeat" required type="password" autoComplete="new-password" value={repeat} onChange={(e) => setRepeat(e.target.value)} className="field" />
          </div>
        </div>

        <ul className="mt-4 space-y-1.5" aria-label="Требования к паролю">
          {checks.map((check) => (
            <li key={check.label} className={`flex items-center gap-2 text-xs ${check.ok ? 'text-emerald-700' : 'text-faint'}`}>
              <span className={`flex items-center justify-center rounded-full ${check.ok ? 'bg-emerald-100' : 'bg-black/[0.05]'}`} style={{ width: 18, height: 18 }}><Check size={11} strokeWidth={2.4} /></span>
              {check.label}
            </li>
          ))}
        </ul>

        {error && <Notice tone="error" className="mt-5">{error}</Notice>}
        <button disabled={loading} className="btn-primary mt-6 h-11 w-full">
          {loading && <Loader2 size={16} className="animate-spin" />}
          {loading ? 'Сохраняем…' : 'Сменить пароль и продолжить'}
        </button>
      </form>
    </div>
  );
}
