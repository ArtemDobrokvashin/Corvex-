import { FormEvent, useEffect, useState } from 'react';
import { AppWindow, Check, Copy, KeyRound, ShieldCheck, X } from 'lucide-react';
import {
  AppPassword,
  TotpSetup,
  changePassword,
  createAppPassword,
  deleteAppPassword,
  errorText,
  listAppPasswords,
  totpConfirm,
  totpDisable,
  totpSetup,
} from '../../api/authApi';
import { Notice } from '../../components/ui';
import { useSession } from '../../store/sessionStore';

/** Настройки безопасности: пароль, TOTP и пароли приложений в правой settings-панели. */
export default function SecuritySettingsModal({ onClose }: { onClose: () => void }) {
  const { user, demoMode, updateUser } = useSession();
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [setup, setSetup] = useState<TotpSetup | null>(null);
  const [appPasswords, setAppPasswords] = useState<AppPassword[]>([]);
  const [createdPassword, setCreatedPassword] = useState<string | null>(null);
  const [copied, setCopied] = useState('');

  useEffect(() => {
    if (!demoMode) {
      listAppPasswords().then(setAppPasswords).catch(() => setAppPasswords([]));
    }
  }, [demoMode]);

  const guardDemo = () => {
    if (demoMode) {
      setError('В демо-режиме настройки недоступны — нужен запущенный сервер');
      return true;
    }
    return false;
  };

  async function copyText(value: string, key: string) {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(key);
      window.setTimeout(() => setCopied(''), 1600);
    } catch {
      setError('Не удалось скопировать автоматически — выделите текст вручную');
    }
  }

  async function onPasswordSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (guardDemo()) return;
    const data = new FormData(e.currentTarget);
    setError('');
    setNotice('');
    try {
      await changePassword(String(data.get('current') ?? ''), String(data.get('next') ?? ''));
      setNotice('Пароль изменён. Другие устройства разлогинены.');
      e.currentTarget.reset();
    } catch (err) {
      setError(errorText(err));
    }
  }

  async function onStartTotp() {
    if (guardDemo()) return;
    setError('');
    try {
      setSetup(await totpSetup());
    } catch (err) {
      setError(errorText(err));
    }
  }

  async function onConfirmTotp(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const code = String(new FormData(e.currentTarget).get('code') ?? '');
    setError('');
    try {
      await totpConfirm(code);
      updateUser({ totpEnabled: true });
      setSetup(null);
      setNotice('Двухфакторная аутентификация включена');
    } catch (err) {
      setError(errorText(err));
    }
  }

  async function onDisableTotp(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const code = String(new FormData(e.currentTarget).get('code') ?? '');
    setError('');
    try {
      await totpDisable(code);
      updateUser({ totpEnabled: false });
      setNotice('Двухфакторная аутентификация отключена');
    } catch (err) {
      setError(errorText(err));
    }
  }

  async function onCreateAppPassword(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (guardDemo()) return;
    const label = String(new FormData(e.currentTarget).get('label') ?? '');
    setError('');
    try {
      const created = await createAppPassword(label);
      setCreatedPassword(created.password);
      setAppPasswords(await listAppPasswords());
      e.currentTarget.reset();
    } catch (err) {
      setError(errorText(err));
    }
  }

  async function onDeleteAppPassword(id: string) {
    try {
      await deleteAppPassword(id);
      setAppPasswords((prev) => prev.filter((p) => p.id !== id));
    } catch (err) {
      setError(errorText(err));
    }
  }

  const sectionTitle = (Icon: typeof KeyRound, title: string, description: string) => (
    <div className="mb-4 flex gap-3">
      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-accent-soft text-accent-dark"><Icon size={18} /></span>
      <div><h3 className="text-[15px] font-semibold text-ink">{title}</h3><p className="mt-0.5 text-[13px] leading-relaxed text-muted">{description}</p></div>
    </div>
  );

  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-ink/35 backdrop-blur-[2px]" onClick={onClose}>
      <section className="flex h-full w-full max-w-[570px] animate-rise-in flex-col bg-surface shadow-pop" onClick={(e) => e.stopPropagation()} aria-label="Безопасность">
        <header className="flex shrink-0 items-center gap-3 border-b border-line px-5 py-4 sm:px-6">
          <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-ink text-white"><ShieldCheck size={19} /></span>
          <div className="min-w-0 flex-1">
            <h2 className="text-[17px] font-semibold tracking-[-0.02em]">Безопасность</h2>
            <p className="truncate text-xs text-muted">{user?.email}</p>
          </div>
          <button type="button" onClick={onClose} className="icon-btn" aria-label="Закрыть настройки"><X size={19} /></button>
        </header>

        <div className="scroll-area min-h-0 flex-1 px-5 py-6 sm:px-6">
          {demoMode && <Notice tone="warning" className="mb-5">Это демонстрационная сессия: экран можно изучить, но изменения не сохранятся без сервера.</Notice>}
          {error && <Notice tone="error" className="mb-4">{error}</Notice>}
          {notice && <Notice tone="success" className="mb-4">{notice}</Notice>}

          <section>
            {sectionTitle(KeyRound, 'Пароль', 'После смены пароля все остальные устройства выйдут из Corvex.')}
            <form onSubmit={onPasswordSubmit} className="space-y-3">
              <div><label className="field-label" htmlFor="security-current">Текущий пароль</label><input id="security-current" name="current" required type="password" autoComplete="current-password" className="field" /></div>
              <div><label className="field-label" htmlFor="security-next">Новый пароль</label><input id="security-next" name="next" required type="password" autoComplete="new-password" placeholder="Минимум 10 символов, буквы и цифры" className="field" /></div>
              <button className="btn-primary">Сменить пароль</button>
            </form>
          </section>

          <section className="mt-9 border-t border-line pt-7">
            {sectionTitle(ShieldCheck, 'Двухфакторная аутентификация', 'Одноразовый код из приложения защищает аккаунт даже при утечке пароля.')}
            <div className="mb-4 flex items-center gap-2 rounded-xl bg-chrome px-4 py-3 text-sm">
              <span className={`h-2 w-2 rounded-full ${user?.totpEnabled ? 'bg-emerald-500' : 'bg-zinc-300'}`} />
              <span className="font-medium text-ink-soft">{user?.totpEnabled ? 'Включена' : 'Выключена'}</span>
              <span className="text-muted">· TOTP, шаг 30 секунд</span>
            </div>

            {!user?.totpEnabled && !setup && <button type="button" onClick={onStartTotp} className="btn-primary">Настроить аутентификатор</button>}
            {!user?.totpEnabled && setup && (
              <form onSubmit={onConfirmTotp} className="space-y-3">
                <p className="text-sm leading-relaxed text-muted">Добавьте секрет в приложение-аутентификатор, затем введите первый код для подтверждения.</p>
                <div className="flex items-start gap-2 rounded-xl border border-line bg-chrome px-3.5 py-3">
                  <code className="min-w-0 flex-1 break-all font-mono text-[13px] leading-relaxed">{setup.secret}</code>
                  <button type="button" onClick={() => copyText(setup.secret, 'secret')} className="icon-btn-sm shrink-0" aria-label="Копировать секрет">{copied === 'secret' ? <Check size={15} /> : <Copy size={15} />}</button>
                </div>
                <details className="text-xs text-muted"><summary className="cursor-pointer font-medium">otpauth-ссылка для QR</summary><p className="mt-2 break-all rounded-lg bg-chrome p-3 font-mono text-[11px]">{setup.otpauthUri}</p></details>
                <div><label className="field-label" htmlFor="totp-confirm">Код из приложения</label><input id="totp-confirm" name="code" required inputMode="numeric" pattern="[0-9]{6}" placeholder="123456" className="field text-center font-mono tracking-[0.25em]" /></div>
                <button className="btn-primary">Включить 2FA</button>
              </form>
            )}
            {user?.totpEnabled && (
              <form onSubmit={onDisableTotp} className="space-y-3">
                <div><label className="field-label" htmlFor="totp-disable">Текущий код для отключения</label><input id="totp-disable" name="code" required inputMode="numeric" pattern="[0-9]{6}" placeholder="123456" className="field text-center font-mono tracking-[0.25em]" /></div>
                <button className="btn-danger-ghost border border-red-200">Отключить 2FA</button>
              </form>
            )}
          </section>

          <section className="mt-9 border-t border-line pt-7">
            {sectionTitle(AppWindow, 'Пароли приложений', 'Отдельные пароли для Outlook и Thunderbird. Они особенно нужны при включённой 2FA.')}
            {createdPassword && (
              <div className="mb-4 rounded-xl border border-amber-100 bg-amber-50 px-4 py-3">
                <p className="text-[13px] font-medium text-amber-900">Сохраните пароль сейчас — он показывается один раз.</p>
                <div className="mt-2 flex items-center gap-2">
                  <code className="min-w-0 flex-1 break-all font-mono text-sm font-semibold text-ink">{createdPassword}</code>
                  <button type="button" onClick={() => copyText(createdPassword, 'app-password')} className="btn-secondary h-8 px-3 text-xs">{copied === 'app-password' ? <Check size={13} /> : <Copy size={13} />}Копировать</button>
                </div>
              </div>
            )}
            <form onSubmit={onCreateAppPassword} className="flex gap-2">
              <input name="label" required placeholder="Например: Thunderbird дома" className="field min-w-0 flex-1" aria-label="Название пароля приложения" />
              <button className="btn-primary shrink-0">Создать</button>
            </form>

            {appPasswords.length === 0 ? (
              <p className="mt-4 rounded-xl bg-chrome px-4 py-3 text-sm text-muted">Паролей приложений пока нет.</p>
            ) : (
              <ul className="mt-4 divide-y divide-line rounded-xl border border-line">
                {appPasswords.map((p) => (
                  <li key={p.id} className="flex items-center gap-3 px-4 py-3">
                    <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-black/[0.045] text-muted"><KeyRound size={16} /></span>
                    <span className="min-w-0 flex-1 text-sm font-medium text-ink-soft">
                      {p.label}
                      <span className="block truncate text-xs font-normal text-faint">Создан {new Date(p.createdAt).toLocaleDateString('ru-RU')}{p.lastUsedAt ? ` · использовался ${new Date(p.lastUsedAt).toLocaleDateString('ru-RU')}` : ' · ещё не использовался'}</span>
                    </span>
                    <button type="button" onClick={() => onDeleteAppPassword(p.id)} className="btn-danger-ghost h-8 px-2 text-xs">Отозвать</button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      </section>
    </div>
  );
}
