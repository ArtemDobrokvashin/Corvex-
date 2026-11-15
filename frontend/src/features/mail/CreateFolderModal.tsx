import { useEffect, useRef, useState, type FormEvent } from 'react';
import { FolderPlus, X } from 'lucide-react';
import { ApiError } from '../../api/httpClient';
import { useModalFocus } from '../../components/useModalFocus';
import { useI18n } from '../../i18n';

export function folderNameProblem(value: string): 'empty' | 'invalid' | 'too-long' | 'reserved' | null {
  const name = value.trim();
  if (!name) return 'empty';
  if (name.length > 256 || name === '.' || /[\\/%*\x00-\x1f]/.test(name) || name.includes('..')) return 'invalid';
  // Maildir replaces each non-ASCII code point with an underscore.
  if (name.replace(/[^A-Za-z0-9@._-]/gu, '_').length > 255) return 'too-long';
  if (name.toUpperCase() === 'INBOX') return 'reserved';
  return null;
}

export default function CreateFolderModal({ demoMode, onClose, onCreate }: {
  demoMode: boolean;
  onClose: () => void;
  onCreate: (name: string) => Promise<void>;
}) {
  const { t } = useI18n();
  const [name, setName] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const dialogRef = useRef<HTMLElement>(null);
  useModalFocus(dialogRef, inputRef);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => { if (event.key === 'Escape' && !saving) onClose(); };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose, saving]);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (saving) return;
    const problem = folderNameProblem(name);
    if (problem) {
      setError(problem === 'empty' ? t('Укажите название папки.') : problem === 'reserved'
        ? t('INBOX — системная папка.') : problem === 'too-long'
          ? t('Название слишком длинное для файлового хранилища.')
          : t('Название не может быть точкой или содержать /, \\, %, *, две точки подряд или управляющие символы.'));
      return;
    }
    setError('');
    setSaving(true);
    try {
      await onCreate(name.trim());
      onClose();
    } catch (error) {
      setError(error instanceof ApiError && error.status === 409
        ? t('Название занято или конфликтует с другой папкой в хранилище.')
        : error instanceof ApiError && error.status === 403
          ? t('Нет права на создание папок.')
          : error instanceof ApiError && error.status === 400
            ? t('Недопустимое название папки.')
            : `${t('Не удалось создать папку.')} ${error instanceof Error ? error.message : ''}`.trim());
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[70] flex items-center justify-center bg-ink/40 p-4 backdrop-blur-[2px]" onClick={() => { if (!saving) onClose(); }}>
      <section ref={dialogRef} tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby="create-folder-title" className="w-full max-w-[440px] rounded-2xl border border-line bg-surface shadow-pop outline-none" onClick={(event) => event.stopPropagation()}>
        <header className="flex items-start gap-3 border-b border-line px-5 py-4">
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-accent-soft text-accent"><FolderPlus size={19} /></span>
          <div className="min-w-0 flex-1"><h2 id="create-folder-title" className="text-base font-semibold text-ink">{t('Создать папку')}</h2><p className="mt-0.5 text-xs text-muted">{t('Новая папка появится в списке почты.')}</p></div>
          <button type="button" className="icon-btn" onClick={onClose} disabled={saving} aria-label={t('Закрыть')}><X size={18} /></button>
        </header>
        <form onSubmit={(event) => void submit(event)}>
          <div className="space-y-3 px-5 py-5">
            <label htmlFor="new-folder-name" className="block text-sm font-medium text-ink">{t('Название папки')}</label>
            <input ref={inputRef} id="new-folder-name" className="field" value={name} maxLength={256} onChange={(event) => { setName(event.target.value); setError(''); }} placeholder={t('Например, Проекты')} aria-invalid={Boolean(error)} aria-describedby={error ? 'folder-name-error' : undefined} />
            <p className="text-xs leading-relaxed text-muted">{demoMode ? t('В демо папки и перемещения доступны, пока открыт экран почты.') : t('Папка создаётся в вашем почтовом ящике. Некоторые разные названия могут конфликтовать в хранилище.')}</p>
            {error && <p id="folder-name-error" role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-xs text-red-800">{error}</p>}
          </div>
          <footer className="flex justify-end gap-2 border-t border-line px-5 py-3.5">
            <button type="button" className="btn-secondary" disabled={saving} onClick={onClose}>{t('Отмена')}</button>
            <button type="submit" className="btn-primary" disabled={saving || !name.trim()}>{saving ? t('Создаём…') : t('Создать папку')}</button>
          </footer>
        </form>
      </section>
    </div>
  );
}
