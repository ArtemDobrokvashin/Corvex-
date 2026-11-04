import { ExternalLink, Mail, ShieldCheck } from 'lucide-react';
import { useI18n } from '../../i18n';

/** Прямой вход в уже существующий рабочий UI External Mail, без дублирования формы. */
export default function MailPolicyTab({ onOpenExternalMail }: { onOpenExternalMail: () => void }) {
  const { t } = useI18n();

  return (
    <section className="panel animate-rise-in p-5 sm:p-6" aria-labelledby="external-mail-admin-title">
      <div className="flex flex-wrap items-start gap-4">
        <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-accent-soft text-accent-dark"><Mail size={21} strokeWidth={1.8} /></span>
        <div className="min-w-0 flex-1">
          <h2 id="external-mail-admin-title" className="text-[20px] font-semibold tracking-[-0.025em]">{t('Внешние ящики сотрудников')}</h2>
          <p className="mt-1 max-w-3xl text-sm leading-relaxed text-muted">{t('Подключение внешней почты, проверка серверов, синхронизация, миграция архивов и отправка от назначенного адреса.')}</p>
        </div>
        <button type="button" className="btn-primary" onClick={onOpenExternalMail}>
          <ExternalLink size={16} />{t('Открыть управление')}
        </button>
      </div>

      <div className="mt-6 grid gap-4 border-t border-line pt-5 text-sm leading-relaxed text-muted sm:grid-cols-2">
        <div>
          <h3 className="font-medium text-ink-soft">{t('Что доступно администратору почты')}</h3>
          <ul className="mt-2 list-disc space-y-1 pl-5">
            <li>{t('Выбор владельца среди сотрудников и сервисных учётных записей.')}</li>
            <li>{t('IMAP/POP3 и SMTP, пресеты провайдеров и проверка подключения.')}</li>
            <li>{t('Ручная синхронизация, история запусков и аудит без секретов.')}</li>
          </ul>
        </div>
        <div className="flex items-start gap-3 rounded-xl bg-chrome p-4">
          <ShieldCheck size={18} className="mt-0.5 shrink-0 text-accent" />
          <p>{t('Пароли внешних ящиков не отображаются в админке. Управление откроется в почтовом разделе с тем же JWT и проверкой ADMIN_MAIL_POLICY на backend.')}</p>
        </div>
      </div>
    </section>
  );
}
