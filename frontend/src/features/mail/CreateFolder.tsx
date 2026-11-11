import { useId, useState, type FormEvent } from 'react';
import { FolderPlus, X } from 'lucide-react';
import { useI18n } from '../../i18n';

export default function CreateFolder({ onCreate }: { onCreate: (name: string) => Promise<void> }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const id = useId();
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!name.trim() || busy) return;
    setBusy(true); setError('');
    try { await onCreate(name.trim()); setName(''); setOpen(false); }
    catch (e) { setError(e instanceof Error ? e.message : t('Не удалось создать папку')); }
    finally { setBusy(false); }
  }
  if (!open) return <button type="button" className="nav-row" onClick={() => setOpen(true)}><FolderPlus size={16} />{t('Создать папку')}</button>;
  return <form onSubmit={submit} className="space-y-2 rounded-lg border border-line bg-surface p-2">
    <div className="flex items-center gap-2"><label htmlFor={id} className="flex-1 text-xs font-medium">{t('Название папки')}</label><button type="button" disabled={busy} className="icon-btn-sm" aria-label={t('Отменить')} onClick={() => { setOpen(false); setError(''); }}><X size={15} /></button></div>
    <input id={id} className="field field-sm w-full" value={name} onChange={(e) => setName(e.target.value)} maxLength={256} required autoFocus />
    {error && <p role="alert" className="text-xs text-danger">{error}</p>}
    <button type="submit" disabled={busy || !name.trim()} className="btn-primary h-8 w-full">{t('Создать')}</button>
  </form>;
}
