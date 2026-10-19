import { FormEvent, useEffect, useState } from 'react';
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
import { useSession } from '../../store/sessionStore';

/** Безопасность учётной записи: смена пароля, TOTP 2FA, пароли приложений для IMAP/SMTP. */
export default function SecuritySettingsModal({ onClose }: { onClose: () => void }) {
  const { user, demoMode, updateUser } = useSession();
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [setup, setSetup] = useState<TotpSetup | null>(null);
  const [appPasswords, setAppPasswords] = useState<AppPassword[]>([]);
  const [createdPassword, setCreatedPassword] = useState<string | null>(null);

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

  const inputCls = 'mt-1 w-full rounded-lg border px-3 py-2 outline-none focus:border-brand';
  const btnCls = 'rounded-lg bg-brand px-4 py-2 text-sm font-medium text-white hover:bg-brand-dark';

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4" onClick={onClose}>
      <div className="max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-2xl bg-white p-6 shadow-xl" onClick={(e) => e.stopPropagation()}>
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold">🔐 Безопасность</h2>
          <button onClick={onClose} className="rounded-lg px-2 py-1 text-slate-500 hover:bg-slate-100">✕</button>
        </div>
        {error && <p className="mb-3 rounded bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}
        {notice && <p className="mb-3 rounded bg-green-50 px-3 py-2 text-sm text-green-700">{notice}</p>}

        <section className="mb-6">
          <h3 className="mb-2 font-medium">Смена пароля</h3>
          <form onSubmit={onPasswordSubmit} className="space-y-2">
            <input name="current" required type="password" placeholder="Текущий пароль" autoComplete="current-password" className={inputCls} />
            <input name="next" required type="password" placeholder="Новый пароль (≥10, буквы+цифры)" autoComplete="new-password" className={inputCls} />
            <button className={btnCls}>Сменить</button>
          </form>
        </section>

        <section className="mb-6 border-t pt-4">
          <h3 className="mb-1 font-medium">Двухфакторная аутентификация (TOTP)</h3>
          <p className="mb-2 text-sm text-slate-500">
            Статус: {user?.totpEnabled ? <span className="text-green-600">включена</span> : 'выключена'}
          </p>
          {!user?.totpEnabled && !setup && (
            <button onClick={onStartTotp} className={btnCls}>Настроить аутентификатор</button>
          )}
          {!user?.totpEnabled && setup && (
            <form onSubmit={onConfirmTotp} className="space-y-2">
              <p className="text-sm text-slate-600">Введите секрет в приложение-аутентификатор вручную (или отсканируйте QR из otpauth-ссылки), затем подтвердите кодом:</p>
              <p className="break-all rounded bg-slate-50 px-3 py-2 font-mono text-sm">{setup.secret}</p>
              <p className="break-all text-xs text-slate-400">{setup.otpauthUri}</p>
              <input name="code" required inputMode="numeric" pattern="[0-9]{6}" placeholder="Код из приложения" className={inputCls} />
              <button className={btnCls}>Включить 2FA</button>
            </form>
          )}
          {user?.totpEnabled && (
            <form onSubmit={onDisableTotp} className="flex gap-2">
              <input name="code" required inputMode="numeric" pattern="[0-9]{6}" placeholder="Код для отключения" className={`${inputCls} mt-0`} />
              <button className="shrink-0 rounded-lg border border-red-300 px-4 py-2 text-sm text-red-700 hover:bg-red-50">Отключить</button>
            </form>
          )}
        </section>

        <section className="border-t pt-4">
          <h3 className="mb-1 font-medium">Пароли приложений (Outlook/Thunderbird)</h3>
          <p className="mb-2 text-sm text-slate-500">Для почтовых клиентов по IMAP/SMTP — особенно при включённой 2FA, когда основной пароль там не работает.</p>
          {createdPassword && (
            <p className="mb-2 rounded bg-amber-50 px-3 py-2 text-sm text-amber-900">
              Новый пароль (показывается один раз): <span className="font-mono font-semibold">{createdPassword}</span>
            </p>
          )}
          <form onSubmit={onCreateAppPassword} className="mb-3 flex gap-2">
            <input name="label" required placeholder="Название, напр. Thunderbird дома" className={`${inputCls} mt-0`} />
            <button className={`${btnCls} shrink-0`}>Создать</button>
          </form>
          {appPasswords.length === 0 ? (
            <p className="text-sm text-slate-400">Паролей приложений пока нет</p>
          ) : (
            <ul className="divide-y text-sm">
              {appPasswords.map((p) => (
                <li key={p.id} className="flex items-center justify-between py-2">
                  <span>{p.label}<span className="block text-xs text-slate-400">создан {new Date(p.createdAt).toLocaleDateString('ru-RU')}{p.lastUsedAt ? `, использовался ${new Date(p.lastUsedAt).toLocaleDateString('ru-RU')}` : ''}</span></span>
                  <button onClick={() => onDeleteAppPassword(p.id)} className="rounded-lg px-2 py-1 text-red-600 hover:bg-red-50">Отозвать</button>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}
