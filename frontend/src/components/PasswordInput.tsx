import { useState, type InputHTMLAttributes } from 'react';
import { Eye, EyeOff } from 'lucide-react';
import { useI18n } from '../i18n';

/** Пароль по умолчанию скрыт; переключение доступно с клавиатуры и не отправляет форму. */
export default function PasswordInput({ className = '', ...props }: InputHTMLAttributes<HTMLInputElement>) {
  const [visible, setVisible] = useState(false);
  const { t } = useI18n();
  const label = t(visible ? 'Скрыть пароль' : 'Показать пароль');
  return <div className="relative">
    <input {...props} type={visible ? 'text' : 'password'} className={`${className} pr-12`} />
    <button type="button" aria-label={label} title={label} aria-pressed={visible}
      onClick={() => setVisible(!visible)} className="absolute right-2 top-1/2 -translate-y-1/2 rounded-lg p-2 text-muted hover:text-ink focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent">
      {visible ? <EyeOff size={18} /> : <Eye size={18} />}
    </button>
  </div>;
}
