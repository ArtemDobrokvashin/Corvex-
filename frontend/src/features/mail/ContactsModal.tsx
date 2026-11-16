import { useEffect, useRef, useState, type FormEvent } from 'react';
import {
  Download, Loader2, Pencil, Search, Trash2, Upload, UserPlus, UserRound, X,
} from 'lucide-react';
import { ApiError } from '../../api/httpClient';
import {
  contactsApi, saveBlob, type Contact, type ContactExportFormat, type ContactImportResult,
} from '../../api/contactsApi';
import { Avatar, EmptyState, IconButton, Notice } from '../../components/ui';
import { useModalFocus } from '../../components/useModalFocus';
import { useI18n } from '../../i18n';

const EMPTY_FORM = {
  displayName: '',
  email: '',
  phone: '',
  organization: '',
  jobTitle: '',
  note: '',
  groupName: '',
};

type FormState = typeof EMPTY_FORM;

function errorText(error: unknown, fallback: string): string {
  if (error instanceof ApiError) return error.detail || fallback;
  return error instanceof Error ? error.message : fallback;
}

/**
 * Личная адресная книга. Импорт — vCard/CSV из Google, Outlook, Яндекса, Thunderbird и
 * Roundcube; экспорт — те же форматы. Сервер не перезаписывает заполненные поля, поэтому
 * повторный импорт безопасен, а результат показывается до закрытия окна.
 */
export default function ContactsModal({
  demoMode, onClose, onChanged,
}: {
  demoMode: boolean;
  onClose: () => void;
  onChanged?: (contacts: Contact[]) => void;
}) {
  const { t } = useI18n();
  const dialogRef = useRef<HTMLElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const importRef = useRef<HTMLInputElement>(null);
  useModalFocus(dialogRef, headingRef);

  const [contacts, setContacts] = useState<Contact[]>([]);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(!demoMode);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [editing, setEditing] = useState<Contact | null>(null);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [result, setResult] = useState<ContactImportResult | null>(null);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  useEffect(() => {
    if (demoMode) {
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    contactsApi.list(query)
      .then((items) => {
        if (cancelled) return;
        setContacts(items);
        onChanged?.(items);
      })
      .catch((err) => { if (!cancelled) setError(errorText(err, t('Не удалось загрузить контакты'))); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
    // onChanged намеренно не в зависимостях: родитель может передавать новую функцию
    // на каждый рендер, и список перезапрашивался бы бесконечно.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, demoMode, t]);

  const reload = async (nextQuery = query) => {
    if (demoMode) return;
    const items = await contactsApi.list(nextQuery);
    setContacts(items);
    onChanged?.(items);
  };

  const startCreate = () => {
    setEditing(null);
    setCreating(true);
    setForm(EMPTY_FORM);
    setError('');
    setNotice('');
  };

  const startEdit = (contact: Contact) => {
    setCreating(true);
    setEditing(contact);
    setForm({
      displayName: contact.displayName,
      email: contact.email,
      phone: contact.phone ?? '',
      organization: contact.organization ?? '',
      jobTitle: contact.jobTitle ?? '',
      note: contact.note ?? '',
      groupName: contact.groupName ?? '',
    });
    setError('');
    setNotice('');
  };

  const cancelForm = () => {
    setCreating(false);
    setEditing(null);
    setForm(EMPTY_FORM);
  };

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (demoMode) {
      setNotice(t('Демо-режим: изменения не сохраняются.'));
      return;
    }
    setBusy(true);
    setError('');
    try {
      const payload = {
        displayName: form.displayName,
        email: form.email,
        phone: form.phone || null,
        organization: form.organization || null,
        jobTitle: form.jobTitle || null,
        note: form.note || null,
        groupName: form.groupName || null,
      };
      if (editing) await contactsApi.update(editing.id, payload);
      else await contactsApi.create(payload);
      await reload();
      cancelForm();
      setNotice(editing ? t('Контакт обновлён.') : t('Контакт добавлен.'));
    } catch (err) {
      setError(errorText(err, t('Не удалось сохранить контакт')));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (contact: Contact) => {
    if (demoMode) return;
    if (!window.confirm(t('Удалить контакт «{name}»?', { name: contact.displayName }))) return;
    setBusy(true);
    setError('');
    try {
      await contactsApi.remove(contact.id);
      await reload();
      setNotice(t('Контакт удалён.'));
    } catch (err) {
      setError(errorText(err, t('Не удалось удалить контакт')));
    } finally {
      setBusy(false);
    }
  };

  const importFile = async (file: File) => {
    if (demoMode) {
      setNotice(t('Демо-режим: импорт не выполняется.'));
      return;
    }
    setBusy(true);
    setError('');
    setResult(null);
    try {
      const imported = await contactsApi.importFile(file);
      setResult(imported);
      await reload();
    } catch (err) {
      setError(errorText(err, t('Не удалось импортировать файл')));
    } finally {
      setBusy(false);
    }
  };

  const exportFile = async (format: ContactExportFormat) => {
    if (demoMode) {
      setNotice(t('Демо-режим: экспорт не выполняется.'));
      return;
    }
    setBusy(true);
    setError('');
    try {
      saveBlob(await contactsApi.exportFile(format), format === 'vcf' ? 'corvex-contacts.vcf' : 'corvex-contacts.csv');
    } catch (err) {
      setError(errorText(err, t('Не удалось выгрузить контакты')));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 p-3 backdrop-blur-[3px]">
      <section
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="contacts-title"
        className="flex max-h-[92vh] w-full max-w-[880px] animate-scale-in flex-col overflow-hidden rounded-[20px] border border-line bg-surface shadow-pop"
      >
        <header className="flex shrink-0 items-start justify-between gap-4 border-b border-line px-5 py-4 sm:px-6">
          <div className="min-w-0">
            <h2 id="contacts-title" ref={headingRef} tabIndex={-1} className="flex items-center gap-2 text-[19px] font-semibold tracking-[-0.02em] outline-none">
              <UserRound size={18} className="text-accent" /> {t('Адресная книга')}
            </h2>
            <p className="mt-1 text-[13px] leading-relaxed text-muted">
              {t('Личные контакты: подсказки при написании письма, импорт vCard и CSV из другого сервиса.')}
            </p>
          </div>
          <IconButton icon={X} label={t('Закрыть')} onClick={onClose} />
        </header>

        <div className="flex shrink-0 flex-wrap items-center gap-2 border-b border-line px-5 py-3 sm:px-6">
          <div className="relative min-w-[180px] flex-1">
            <Search size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-faint" />
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder={t('Поиск по имени, адресу или организации')}
              aria-label={t('Поиск по имени, адресу или организации')}
              className="field h-9 pl-8 text-[13px]"
            />
          </div>
          <button type="button" onClick={startCreate} className="btn-secondary h-9 px-3 text-[13px]">
            <UserPlus size={14} /> {t('Добавить')}
          </button>
          <input
            ref={importRef}
            type="file"
            accept=".vcf,.vcard,.csv,.tsv,.txt,text/vcard,text/csv"
            className="hidden"
            onChange={(event) => {
              const file = event.target.files?.[0];
              event.target.value = '';
              if (file) void importFile(file);
            }}
          />
          <button type="button" onClick={() => importRef.current?.click()} disabled={busy} className="btn-secondary h-9 px-3 text-[13px]">
            <Upload size={14} /> {t('Импорт')}
          </button>
          <button type="button" onClick={() => void exportFile('vcf')} disabled={busy || contacts.length === 0} className="btn-secondary h-9 px-3 text-[13px]">
            <Download size={14} /> vCard
          </button>
          <button type="button" onClick={() => void exportFile('csv')} disabled={busy || contacts.length === 0} className="btn-secondary h-9 px-3 text-[13px]">
            <Download size={14} /> CSV
          </button>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4 sm:px-6">
          {error && <Notice tone="error" className="mb-3">{error}</Notice>}
          {notice && <Notice tone="success" className="mb-3">{notice}</Notice>}
          {result && (
            <Notice tone={result.issues.length > 0 || result.truncated ? 'warning' : 'success'} className="mb-3">
              <p>
                {t('Импорт завершён: добавлено {created}, дополнено {updated}, пропущено {skipped}.', {
                  created: result.created, updated: result.updated, skipped: result.skipped,
                })}
              </p>
              {result.truncated && <p className="mt-1">{t('Достигнут лимит адресной книги: остаток файла не импортирован.')}</p>}
              {result.issues.length > 0 && (
                <ul className="mt-1.5 max-h-32 space-y-0.5 overflow-y-auto text-[12px]">
                  {result.issues.map((issue, index) => (
                    <li key={`${issue.line}-${index}`}>
                      {t('Строка {line}', { line: issue.line })}
                      {issue.value ? ` · ${issue.value}` : ''} — {issue.reason}
                    </li>
                  ))}
                </ul>
              )}
            </Notice>
          )}

          {creating && (
            <form onSubmit={submit} className="mb-4 rounded-[14px] border border-line bg-canvas p-4">
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="block text-[12px] text-muted">
                  {t('Имя')}
                  <input value={form.displayName} onChange={(event) => setForm({ ...form, displayName: event.target.value })} className="field mt-1 h-9" placeholder={t('Иван Петров')} />
                </label>
                <label className="block text-[12px] text-muted">
                  {t('Адрес электронной почты')}
                  <input required type="email" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} className="field mt-1 h-9" placeholder="ivan@company.ru" />
                </label>
                <label className="block text-[12px] text-muted">
                  {t('Телефон')}
                  <input value={form.phone} onChange={(event) => setForm({ ...form, phone: event.target.value })} className="field mt-1 h-9" placeholder="+7 999 000-00-00" />
                </label>
                <label className="block text-[12px] text-muted">
                  {t('Организация')}
                  <input value={form.organization} onChange={(event) => setForm({ ...form, organization: event.target.value })} className="field mt-1 h-9" />
                </label>
                <label className="block text-[12px] text-muted">
                  {t('Должность')}
                  <input value={form.jobTitle} onChange={(event) => setForm({ ...form, jobTitle: event.target.value })} className="field mt-1 h-9" />
                </label>
                <label className="block text-[12px] text-muted">
                  {t('Группа')}
                  <input value={form.groupName} onChange={(event) => setForm({ ...form, groupName: event.target.value })} className="field mt-1 h-9" placeholder={t('Например, Поставщики')} />
                </label>
                <label className="block text-[12px] text-muted sm:col-span-2">
                  {t('Заметка')}
                  <textarea value={form.note} onChange={(event) => setForm({ ...form, note: event.target.value })} className="field mt-1 min-h-[64px] py-2" />
                </label>
              </div>
              <div className="mt-3 flex items-center gap-2">
                <button type="submit" disabled={busy} className="btn-accent h-9 px-3 text-[13px]">
                  {busy ? <Loader2 size={14} className="animate-spin" /> : null}
                  {editing ? t('Сохранить') : t('Добавить контакт')}
                </button>
                <button type="button" onClick={cancelForm} className="btn-secondary h-9 px-3 text-[13px]">{t('Отмена')}</button>
              </div>
            </form>
          )}

          {loading ? (
            <div className="flex items-center gap-2 py-8 text-sm text-muted">
              <Loader2 size={16} className="animate-spin" /> {t('Загрузка контактов…')}
            </div>
          ) : contacts.length === 0 ? (
            <EmptyState
              icon={UserRound}
              title={query ? t('Ничего не найдено') : t('Адресная книга пока пуста')}
              description={query
                ? t('Измените запрос или добавьте контакт вручную.')
                : t('Добавьте контакт вручную или импортируйте файл vCard/CSV из старого сервиса.')}
            />
          ) : (
            <ul className="divide-y divide-line">
              {contacts.map((contact) => (
                <li key={contact.id} className="flex items-center gap-3 py-2.5">
                  <Avatar name={contact.displayName} size={34} />
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-medium text-ink">{contact.displayName}</p>
                    <p className="truncate text-[12.5px] text-muted">
                      {contact.email}
                      {contact.organization ? ` · ${contact.organization}` : ''}
                      {contact.jobTitle ? ` · ${contact.jobTitle}` : ''}
                    </p>
                    {(contact.phone || contact.groupName) && (
                      <p className="truncate text-[11.5px] text-faint">
                        {contact.phone ?? ''}
                        {contact.phone && contact.groupName ? ' · ' : ''}
                        {contact.groupName ?? ''}
                      </p>
                    )}
                  </div>
                  <div className="flex shrink-0 gap-1">
                    <IconButton icon={Pencil} label={t('Изменить')} onClick={() => startEdit(contact)} />
                    <IconButton icon={Trash2} label={t('Удалить')} onClick={() => void remove(contact)} />
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>

        <footer className="shrink-0 border-t border-line px-5 py-3 text-[12px] leading-relaxed text-faint sm:px-6">
          {t('Импорт не перезаписывает уже заполненные поля: одинаковые адреса дополняются, ручные правки сохраняются. Лимит — 10 000 контактов.')}
        </footer>
      </section>
    </div>
  );
}
