import { FormEvent, useMemo, useState } from 'react';
import { Check, KeyRound, Loader2, X } from 'lucide-react';
import type { AdminUser } from '../../api/adminApi';
import { errorText } from '../../api/authApi';
import { Notice } from '../../components/ui';
import PasswordInput from '../../components/PasswordInput';
import { useI18n } from '../../i18n';

interface ResetUserPasswordModalProps {
  user: Pick<AdminUser, 'username' | 'displayName'>;
  onClose: () => void;
  onReset: (newPassword: string) => Promise<void>;
}

/** Write-only сброс пароля суперадминистратором; секрет не возвращается и не сохраняется в UI. */
export default function ResetUserPasswordModal({ user, onClose, onReset }: ResetUserPasswordModalProps) {
  const { locale, t } = useI18n();
  const [next, setNext] = useState('');
  const [repeat, setRepeat] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  const checks = useMemo(() => [
    { label: t('Не короче 10 символов'), ok: next.length >= 10 },
    { label: t('Не длиннее 128 символов'), ok: next.length <= 128 },
    { label: t('Есть буквы и цифры'), ok: /[A-Za-zА-Яа-я]/.test(next) && /[0-9]/.test(next) },
    { label: t('Повтор совпадает'), ok: next.length > 0 && next === repeat },
  ], [next, repeat, t]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (next !== repeat) {
      setError(t('Новые пароли не совпадают'));
      return;
    }
    if (next.length < 10 || next.length > 128
      || !/[A-Za-zА-Яа-я]/.test(next) || !/[0-9]/.test(next)) {
      setError(t('Пароль должен быть от 10 до 128 символов и содержать буквы и цифры'));
      return;
    }
    setError('');
    setSaving(true);
    try {
      await onReset(next);
      onClose();
    } catch (resetError) {
      setError(errorText(resetError, locale));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="fixed inset-0 z-[70] flex items-center justify-center bg-ink/35 p-4 backdrop-blur-[2px]">
      <button type="button" aria-label={t('Закрыть')} className="absolute inset-0 cursor-default" onClick={onClose} disabled={saving} />
      <form
        onSubmit={submit}
        className="relative z-10 w-full max-w-lg animate-scale-in rounded-2xl border border-line bg-surface p-5 shadow-pop sm:p-6"
        role="dialog"
        aria-modal="true"
        aria-labelledby="password-reset-title"
      >
        <header className="mb-5 flex items-start gap-3">
          <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-accent-soft text-accent-dark"><KeyRound size={20} /></span>
          <div className="min-w-0 flex-1">
            <h2 id="password-reset-title" className="text-[20px] font-semibold tracking-[-0.025em]">{t('Сброс пароля пользователя')}</h2>
            <p className="mt-1 text-sm text-muted">{user.displayName} · {user.username}</p>
          </div>
          <button type="button" className="icon-btn shrink-0" onClick={onClose} disabled={saving} aria-label={t('Закрыть')}><X size={18} /></button>
        </header>

        <p className="mb-5 text-[13px] leading-relaxed text-muted">
          {t('После сброса пользователь должен сменить временный пароль при следующем входе. Refresh-сессии будут отозваны; 2FA, роли и пароли приложений сохранятся.')}
        </p>

        <div className="space-y-4">
          <div className="block">
            <label className="field-label" htmlFor="reset-new-password">{t('Новый временный пароль')}</label>
            <PasswordInput
              id="reset-new-password"
              name="newPassword"
              required
              minLength={10}
              maxLength={128}
              autoComplete="new-password"
              value={next}
              onChange={(event) => setNext(event.target.value)}
              className="field"
              autoFocus
            />
          </div>
          <div className="block">
            <label className="field-label" htmlFor="reset-confirm-password">{t('Повторите временный пароль')}</label>
            <PasswordInput
              id="reset-confirm-password"
              name="confirmPassword"
              required
              minLength={10}
              maxLength={128}
              autoComplete="new-password"
              value={repeat}
              onChange={(event) => setRepeat(event.target.value)}
              className="field"
            />
          </div>
        </div>

        <ul className="mt-4 space-y-1.5" aria-label={t('Требования к паролю')}>
          {checks.map((check) => (
            <li key={check.label} className={`flex items-center gap-2 text-xs ${check.ok ? 'text-emerald-700' : 'text-faint'}`}>
              <span className={`flex h-[18px] w-[18px] items-center justify-center rounded-full ${check.ok ? 'bg-emerald-100' : 'bg-black/[0.05]'}`}><Check size={11} strokeWidth={2.4} /></span>
              {check.label}
            </li>
          ))}
        </ul>

        {error && <Notice tone="error" className="mt-5">{error}</Notice>}
        <footer className="mt-6 flex justify-end gap-2 border-t border-line pt-4">
          <button type="button" className="btn-ghost" onClick={onClose} disabled={saving}>{t('Отмена')}</button>
          <button type="submit" className="btn-primary" disabled={saving}>
            {saving && <Loader2 size={15} className="animate-spin" />}
            {saving ? t('Сохраняем…') : t('Сбросить пароль')}
          </button>
        </footer>
      </form>
    </div>
  );
}
