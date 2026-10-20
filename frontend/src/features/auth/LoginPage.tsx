import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ArrowLeft,
  Eye,
  EyeOff,
  Loader2,
  LockKeyhole,
  Mail,
  MessageSquare,
  Play,
  Search,
  ShieldCheck,
  UserRound,
} from 'lucide-react';
import { errorText, fetchMe, isServerUnreachable, login, verifyMfa } from '../../api/authApi';
import { demoMails, getDemoUser } from '../../api/demoData';
import { Avatar, Logo, Notice, cleanPersonName } from '../../components/ui';
import { LanguageSwitcher, useI18n } from '../../i18n';
import { useSession } from '../../store/sessionStore';

/** Вход: локальная учётка, второй шаг TOTP и явный демо-режим витрины. */
export default function LoginPage() {
  const { locale, t } = useI18n();
  const navigate = useNavigate();
  const setSession = useSession((s) => s.setSession);
  const [error, setError] = useState('');
  const [serverDown, setServerDown] = useState(false);
  const [mfaTicket, setMfaTicket] = useState<string | null>(null);
  const [mfaCode, setMfaCode] = useState('');
  const [loading, setLoading] = useState(false);
  const [showPassword, setShowPassword] = useState(false);

  const previewMails = demoMails(locale).slice(0, 3);
  const previewAuthor = cleanPersonName(previewMails[0]?.from ?? 'Anna Petrova');

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
      setError(errorText(err, locale));
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
      setError(errorText(err, locale));
      setServerDown(isServerUnreachable(err));
    } finally {
      setLoading(false);
    }
  }

  function enterDemo() {
    sessionStorage.setItem('corvex.demo', '1');
    setSession(getDemoUser(locale), true);
    navigate('/mail');
  }

  return (
    <main className="grid min-h-full bg-surface lg:grid-cols-[1.04fr_0.96fr]">
      {/* Продуктовая витрина */}
      <section
        className="relative hidden overflow-hidden px-12 py-10 lg:flex lg:flex-col xl:px-16"
        style={{
          backgroundColor: '#EEF1F7',
          backgroundImage:
            'radial-gradient(circle at 15% 10%, rgba(91,124,255,.20), transparent 32%), radial-gradient(circle at 90% 85%, rgba(49,87,213,.13), transparent 38%), linear-gradient(145deg, #F6F7F2 0%, #EDF1F8 52%, #E8EEF8 100%)',
        }}
      >
        <Logo subtitle={t('Корпоративные коммуникации')} />

        <div className="mx-auto flex w-full max-w-[620px] flex-1 flex-col justify-center py-12">
          <p className="text-xs font-semibold uppercase tracking-[0.14em] text-accent-dark">{t('Рабочее пространство компании')}</p>
          <h1 className="mt-4 text-[42px] font-semibold leading-[1.08] tracking-[-0.035em] text-ink">
            {t('Почта и сообщения.')}<br />{t('Без лишнего шума.')}
          </h1>
          <p className="mt-4 max-w-md text-[16px] leading-relaxed text-muted">
            {t('Один спокойный интерфейс для писем, командных чатов и внутренних сервисов вашей организации.')}
          </p>

          <div className="relative mb-9 mt-10">
            <div className="animate-rise-in overflow-hidden rounded-2xl border border-white/80 bg-white/95 shadow-pop backdrop-blur">
              <div className="flex h-11 items-center gap-2 border-b border-line px-4">
                <span className="h-2.5 w-2.5 rounded-full bg-[#FF5F57]" />
                <span className="h-2.5 w-2.5 rounded-full bg-[#FEBC2E]" />
                <span className="h-2.5 w-2.5 rounded-full bg-[#28C840]" />
                <span className="mx-auto flex h-7 w-64 items-center gap-2 rounded-full bg-black/[0.045] px-3 text-xs text-faint"><Search size={13} />{t('Поиск по почте и людям')}</span>
              </div>
              <div className="grid grid-cols-[128px_1fr]">
                <div className="space-y-1 border-r border-line bg-chrome p-3 text-xs text-muted">
                  <div className="flex items-center gap-2 rounded-lg bg-accent-soft px-2 py-2 font-medium text-accent-dark"><Mail size={14} />{t('Входящие')}<span className="ml-auto text-accent">3</span></div>
                  <div className="flex items-center gap-2 px-2 py-2"><MessageSquare size={14} />{t('Сообщения')}</div>
                  <div className="px-2 py-2">{t('Отправленные')}</div>
                  <div className="px-2 py-2">{t('Архив')}</div>
                </div>
                <div className="divide-y divide-line">
                  {previewMails.map((mail) => (
                    <div key={mail.id} className="flex items-start gap-3 px-4 py-3.5">
                      <Avatar name={mail.from} size={34} />
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2 text-[13px]"><span className={!mail.seen ? 'font-semibold' : 'font-medium'}>{cleanPersonName(mail.from)}</span>{!mail.seen ? <span className="h-1.5 w-1.5 rounded-full bg-accent" /> : null}<span className="ml-auto text-[11px] text-faint">{mail.date}</span></div>
                        <div className="mt-0.5 truncate text-[13px] text-muted">{mail.subject}</div>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
            <div className="absolute -bottom-7 -right-5 flex max-w-[300px] items-start gap-3 rounded-2xl border border-line bg-white p-3.5 shadow-composer">
              <Avatar name={previewAuthor} size={34} presence />
              <div className="text-[13px] leading-snug"><b>{previewAuthor}</b><br /><span className="text-muted">{t('Реестр уже в почте, посмотрите до обеда.')}</span></div>
            </div>
          </div>
        </div>

        <div className="mx-auto flex w-full max-w-[620px] flex-wrap gap-x-6 gap-y-2 text-[13px] text-muted">
          <span className="flex items-center gap-2"><ShieldCheck size={15} className="text-accent" />{t('Данные внутри компании')}</span>
          <span className="flex items-center gap-2"><LockKeyhole size={15} className="text-accent" />{t('2FA и пароли приложений')}</span>
          <span className="flex items-center gap-2"><Search size={15} className="text-accent" />{t('Быстрый переход Ctrl+K')}</span>
        </div>
      </section>

      {/* Форма */}
      <section className="relative flex min-h-full items-center justify-center px-5 py-10 sm:px-10">
        <div className="absolute right-5 top-5 sm:right-8 sm:top-7"><LanguageSwitcher /></div>
        <div className="w-full max-w-[390px] animate-rise-in">
          <div className="mb-10 lg:hidden"><Logo subtitle={t('Корпоративная почта и чат')} /></div>

          {!mfaTicket ? (
            <>
              <h2 className="text-[28px] font-semibold tracking-[-0.025em]">{t('С возвращением')}</h2>
              <p className="mb-8 mt-2 text-sm leading-relaxed text-muted">{t('Войдите, чтобы продолжить работу с почтой и сообщениями компании.')}</p>
              <form onSubmit={onSubmit} className="space-y-4">
                <div>
                  <label htmlFor="username" className="field-label">{t('Логин или e-mail')}</label>
                  <div className="relative">
                    <UserRound size={17} className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-faint" />
                    <input id="username" name="username" required autoComplete="username" autoFocus placeholder="i.ivanov" className="field pl-10" />
                  </div>
                </div>
                <div>
                  <label htmlFor="password" className="field-label">{t('Пароль')}</label>
                  <div className="relative">
                    <LockKeyhole size={17} className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-faint" />
                    <input id="password" name="password" required type={showPassword ? 'text' : 'password'} autoComplete="current-password" placeholder={t('Введите пароль')} className="field pl-10 pr-11" />
                    <button type="button" onClick={() => setShowPassword(!showPassword)} className="icon-btn absolute right-1 top-1/2 -translate-y-1/2" aria-label={showPassword ? t('Скрыть пароль') : t('Показать пароль')}>
                      {showPassword ? <EyeOff size={17} /> : <Eye size={17} />}
                    </button>
                  </div>
                </div>
                {error && <Notice tone="error">{error}</Notice>}
                <button disabled={loading} className="btn-primary h-11 w-full">
                  {loading && <Loader2 size={17} className="animate-spin" />}
                  {loading ? t('Проверяем…') : t('Войти')}
                </button>
                <p className="text-center text-xs leading-relaxed text-faint">{t('Первый вход администратора — с паролем из лога auth-service или переменной окружения.')}</p>
              </form>
            </>
          ) : (
            <>
              <div className="mb-6 flex h-12 w-12 items-center justify-center rounded-2xl bg-accent-soft text-accent-dark"><ShieldCheck size={23} strokeWidth={1.8} /></div>
              <h2 className="text-[28px] font-semibold tracking-[-0.025em]">{t('Подтвердите вход')}</h2>
              <p className="mb-8 mt-2 text-sm leading-relaxed text-muted">{t('Введите шестизначный код из приложения-аутентификатора.')}</p>
              <form onSubmit={onMfaSubmit} className="space-y-4">
                <input
                  value={mfaCode}
                  onChange={(e) => setMfaCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                  required
                  inputMode="numeric"
                  pattern="[0-9]{6}"
                  placeholder="000000"
                  autoFocus
                  aria-label={t('Код двухфакторной аутентификации')}
                  className="h-14 w-full rounded-xl border border-line bg-surface text-center font-mono text-[24px] font-medium tracking-[0.35em] outline-none transition placeholder:text-line focus:border-accent focus:ring-4 focus:ring-accent/10"
                />
                {error && <Notice tone="error">{error}</Notice>}
                <button disabled={loading || mfaCode.length !== 6} className="btn-primary h-11 w-full">
                  {loading && <Loader2 size={17} className="animate-spin" />}
                  {loading ? t('Проверяем…') : t('Подтвердить')}
                </button>
                <button type="button" onClick={() => { setMfaTicket(null); setError(''); setMfaCode(''); }} className="btn-ghost mx-auto flex">
                  <ArrowLeft size={15} /> {t('Назад ко входу')}
                </button>
              </form>
            </>
          )}

          <div className="mt-10 border-t border-line pt-6">
            {serverDown && (
              <Notice tone="warning" className="mb-3">{t('Сервер не отвечает. Демо покажет интерфейс на учебных данных, но это не настоящий вход.')}</Notice>
            )}
            <button type="button" onClick={enterDemo} className="btn-secondary w-full">
              <Play size={16} /> {t('Посмотреть демо без сервера')}
            </button>
            <p className="mt-6 flex items-center justify-center gap-1.5 text-center text-[11px] text-faint"><LockKeyhole size={12} /> {t('Сессия защищена HttpOnly refresh-cookie и короткоживущим JWT')}</p>
          </div>
        </div>
      </section>
    </main>
  );
}
