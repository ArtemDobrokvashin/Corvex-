import { FormEvent, useEffect, useId, useRef, useState } from 'react';
import { CheckCircle2, Maximize2, Minimize2, Paperclip, Send, Trash2, X } from 'lucide-react';
import type { ExternalMailAccount } from '../../api/externalMailApi';
import { ApiError } from '../../api/httpClient';
import { mailApi, type SavedDraft } from '../../api/mailApi';
import { IconButton } from '../../components/ui';
import { useI18n } from '../../i18n';
import ComposeEditor, { type ComposeEditorHandle } from './ComposeEditor';
import { attachmentToFile, buildDraftPayload, draftSizeProblem, draftStatusKey } from './drafts';

export interface ComposeDraft {
  to: string;
  cc?: string;
  subject: string;
  body: string;
  bodyHtml?: string;
  externalAccountId?: string | null;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export function ComposePanel({ initial, currentEmail, externalAccounts, onClose, onSend, autoSave = true, initialDraft, initialFiles, contactSuggestions = [] }: {
  initial: ComposeDraft;
  currentEmail: string;
  externalAccounts: ExternalMailAccount[];
  /** Адреса из личной адресной книги для подсказок в полях «Кому» и «Копия». */
  contactSuggestions?: string[];
  onClose: () => void;
  /** false — письмо ушло, но копию в «Отправленные» сохранить не удалось. */
  onSend: (draft: ComposeDraft, files: File[]) => Promise<boolean | void>;
  /** Демо-режим не пишет на сервер. */
  autoSave?: boolean;
  initialDraft?: SavedDraft | null;
  /** Вложения групповой пересылки, подготовленные до открытия композера. */
  initialFiles?: File[];
}) {
  const { t } = useI18n();
  const suggestionsId = useId();
  const [draft, setDraft] = useState(initial);
  const [showCc, setShowCc] = useState(Boolean(initial.cc));
  const [files, setFiles] = useState<File[]>(() =>
    initialFiles?.length ? initialFiles
      : (initialDraft?.payload.attachments ?? []).map((attachment) => attachmentToFile(attachment)));
  const [draggingFiles, setDraggingFiles] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [sent, setSent] = useState(false);
  const [sentCopySaved, setSentCopySaved] = useState(true);
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  const [savedId, setSavedId] = useState<string | null>(initialDraft?.id ?? null);
  const savedRevision = useRef<number>(initialDraft?.revision ?? 0);
  const [draftStatus, setDraftStatus] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');
  const [draftNote, setDraftNote] = useState('');
  const savingDraft = useRef(false);
  // Двойной submit (клик + Enter, двойной клик) не должен отправить письмо дважды.
  const sendingRef = useRef(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const bodyRef = useRef<ComposeEditorHandle>(null);
  const sendAsAccounts = externalAccounts.filter((account) => account.enabled && account.sendEnabled && account.smtpEnabled);
  useEffect(() => {
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      if (!sent && (draft.to || draft.cc || draft.subject || draft.body || files.length)) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [draft, files, sent]);


  // Автосохранение: сервер держит owner-scope и ревизию, поэтому вторая
  // вкладка не перезапишет более новый текст молча.
  useEffect(() => {
    if (!autoSave || sent || sending) return;
    const hasContent = Boolean(draft.to || draft.cc || draft.subject || draft.body || files.length);
    if (!hasContent) return;
    const timer = window.setTimeout(() => {
      void (async () => {
        if (savingDraft.current) return;
        const problem = draftSizeProblem(files);
        if (problem) {
          setDraftStatus('error');
          setDraftNote(problem === 'count' ? t('Слишком много вложений для черновика (до 10).')
            : problem === 'file' ? t('Вложение больше 3 МБ не сохраняется в черновике.')
              : t('Вложения черновика больше 10 МБ.'));
          return;
        }
        savingDraft.current = true;
        setDraftStatus('saving');
        try {
          const payload = await buildDraftPayload(draft, files);
          if (savedId) {
            const saved = await mailApi.updateDraft(savedId, savedRevision.current, payload);
            savedRevision.current = saved.revision;
            setSavedId(saved.id);
          } else {
            const saved = await mailApi.createDraft(payload);
            savedRevision.current = saved.revision;
            setSavedId(saved.id);
          }
          setDraftNote('');
          setDraftStatus('saved');
        } catch (error) {
          setDraftStatus('error');
          setDraftNote(errorMessage(error));
        } finally {
          savingDraft.current = false;
        }
      })();
    }, 1500);
    return () => window.clearTimeout(timer);
  }, [autoSave, draft, files, sent, sending, savedId, t]);

  const dropSavedDraft = async () => {
    if (!savedId) return null;
    try {
      await mailApi.deleteDraft(savedId, savedRevision.current);
      return null;
    } catch {
      // Отправка уже состоялась: черновик удалит следующий проход, письмо не теряется.
      return savedId;
    }
  };

  const closeComposer = () => {
    if (sending) return;
    if (!sent && (draft.to || draft.cc || draft.subject || draft.body || files.length) &&
        !window.confirm(t('Закрыть письмо? Несохранённый текст и вложения будут потеряны.'))) return;
    onClose();
  };

  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (sendingRef.current) return;
    if (!draft.body.trim()) { setSendError(t('Введите текст письма.')); return; }
    sendingRef.current = true;
    void (async () => {
      setSending(true);
      setSendError(null);
      try {
        const copySaved = await onSend(draft, files);
        setSentCopySaved(copySaved !== false);
        await dropSavedDraft();
        setSent(true);
        window.setTimeout(onClose, 900);
      } catch (error) {
        // Сервер мог поставить письмо в очередь до ошибки. Повторять отправку
        // вслепую нельзя, поэтому обещаем только то, что знаем наверняка.
        const rejected = error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== 408;
        setSendError(rejected
          ? errorMessage(error)
          : `${t('Не удалось подтвердить отправку. Письмо могло попасть в очередь; перед повтором уточните статус у администратора или получателей.')} ${errorMessage(error)}`);
      } finally {
        sendingRef.current = false;
        setSending(false);
      }
    })();
  };

  return (
    <section className={`fixed z-50 flex animate-rise-in flex-col overflow-hidden border border-line bg-surface shadow-composer ${expanded ? 'inset-0 sm:inset-4 sm:rounded-2xl' : 'inset-x-0 bottom-0 max-h-[94dvh] sm:inset-x-auto sm:bottom-4 sm:right-4 sm:h-[min(640px,92dvh)] sm:w-[min(590px,calc(100vw-2rem))] sm:rounded-2xl'}`} aria-label={t('Новое письмо')}>
      <header className="flex h-11 shrink-0 items-center bg-ink px-4 text-white">
        <h3 className="flex-1 text-sm font-medium">{initial.subject ? t('Ответ / пересылка') : t('Новое письмо')}</h3>
        <button type="button" onClick={() => setExpanded((value) => !value)} aria-pressed={expanded} className="flex h-8 w-8 items-center justify-center rounded-lg text-white/70 transition hover:bg-white/10 hover:text-white" aria-label={expanded ? t('Уменьшить окно письма') : t('Развернуть окно письма')}>{expanded ? <Minimize2 size={16} /> : <Maximize2 size={16} />}</button>
        <button type="button" onClick={closeComposer} disabled={sending} className="flex h-8 w-8 items-center justify-center rounded-lg text-white/70 transition hover:bg-white/10 hover:text-white disabled:opacity-50" aria-label={t('Закрыть черновик')}><X size={16} /></button>
      </header>

      {sent ? (
        <div className="flex flex-1 flex-col items-center justify-center px-8 text-center">
          <span className="flex h-14 w-14 items-center justify-center rounded-full bg-emerald-50 text-emerald-600"><CheckCircle2 size={27} /></span>
          <h4 className="mt-4 text-lg font-semibold">{t('Письмо отправлено')}</h4>
          <p className={`mt-1 text-sm ${sentCopySaved ? 'text-muted' : 'text-danger'}`} role={sentCopySaved ? undefined : 'alert'}>
            {sentCopySaved
              ? t('Копия сохранена в папке «Отправленные».')
              : t('Копию не удалось сохранить в папке «Отправленные». Повторно отправлять письмо не нужно.')}
          </p>
        </div>
      ) : (
        <form onSubmit={submit} className={`flex min-h-0 flex-1 flex-col ${draggingFiles ? 'ring-2 ring-inset ring-accent' : ''}`}
          onDragOver={(event) => { if (Array.from(event.dataTransfer.types).includes('Files')) { event.preventDefault(); setDraggingFiles(true); } }}
          onDragLeave={(event) => { if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDraggingFiles(false); }}
          onDrop={(event) => { event.preventDefault(); setDraggingFiles(false); const dropped = Array.from(event.dataTransfer.files); if (dropped.length) setFiles((previous) => [...previous, ...dropped]); }}>
          {draggingFiles && <div className="bg-accent-soft px-4 py-2 text-sm text-accent-dark">{t('Отпустите файлы, чтобы прикрепить')}</div>}
          {autoSave && draftStatus !== 'idle' && (
            <p className={`px-4 pt-2 text-[11px] ${draftStatus === 'error' ? 'text-danger' : 'text-faint'}`} role="status" aria-live="polite">
              {t(draftStatusKey(draftStatus) ?? '')}
              {draftStatus === 'saved' ? ` ${new Date().toLocaleTimeString()}` : ''}
              {draftNote ? ` · ${draftNote}` : ''}
            </p>
          )}
          {sendAsAccounts.length > 0 && (
            <div className="flex h-12 shrink-0 items-center gap-3 border-b border-line px-4">
              <label htmlFor="compose-from" className="w-12 shrink-0 text-[13px] text-faint">{t('От кого')}</label>
              <select
                id="compose-from"
                value={draft.externalAccountId ?? ''}
                onChange={(e) => setDraft({ ...draft, externalAccountId: e.target.value || null })}
                className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none focus:ring-0"
              >
                <option value="">{currentEmail} · Corvex</option>
                {sendAsAccounts.map((account) => (
                  <option key={account.id} value={account.id}>{account.emailAddress} · {account.label}</option>
                ))}
              </select>
            </div>
          )}
          <div className="flex h-12 shrink-0 items-center gap-3 border-b border-line px-4">
            <label htmlFor="compose-to" className="w-12 shrink-0 text-[13px] text-faint">{t('Кому')}</label>
            <input id="compose-to" required type="text" inputMode="email" list={suggestionsId} value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} placeholder="user@company.local" className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none placeholder:text-faint focus:ring-0" />
            <datalist id={suggestionsId}>
              {contactSuggestions.map((email) => <option key={email} value={email} />)}
            </datalist>
            {!showCc && <button type="button" onClick={() => setShowCc(true)} className="shrink-0 text-xs font-medium text-muted hover:text-ink">{t('Копия')}</button>}
          </div>
          {showCc && (
            <div className="flex h-12 shrink-0 items-center gap-3 border-b border-line px-4">
              <label htmlFor="compose-cc" className="w-12 shrink-0 text-[13px] text-faint">{t('Копия')}</label>
              <input id="compose-cc" type="text" inputMode="email" list={suggestionsId} value={draft.cc ?? ''} onChange={(e) => setDraft({ ...draft, cc: e.target.value })} placeholder="user@company.local" className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none placeholder:text-faint focus:ring-0" />
            </div>
          )}
          <div className="flex h-12 shrink-0 items-center gap-3 border-b border-line px-4">
            <label htmlFor="compose-subject" className="w-12 shrink-0 text-[13px] text-faint">{t('Тема')}</label>
            <input id="compose-subject" required value={draft.subject} onChange={(e) => setDraft({ ...draft, subject: e.target.value })} placeholder={t('О чём письмо?')} className="min-w-0 flex-1 border-0 bg-transparent text-sm font-medium outline-none placeholder:font-normal placeholder:text-faint focus:ring-0" />
          </div>
          <ComposeEditor ref={bodyRef} initialText={initial.body} initialHtml={initial.bodyHtml} onChange={(body, bodyHtml) => setDraft((current) => ({ ...current, body, bodyHtml }))} />
          {files.length > 0 && (
            <div className="flex shrink-0 flex-wrap gap-2 px-4 pb-3">
              {files.map((file, index) => <span key={`${file.name}-${index}`} className="chip"><Paperclip size={12} />{file.name}<button type="button" className="ml-1 rounded p-0.5 hover:text-danger" aria-label={`${t('Удалить вложение')}: ${file.name}`} onClick={() => setFiles((current) => current.filter((_, i) => i !== index))}><X size={13} /></button></span>)}
            </div>
          )}
          {sendError && (
            <div className="mx-4 mb-3 shrink-0 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800" role="alert">{sendError}</div>
          )}
          <footer className="flex shrink-0 items-center gap-2 border-t border-line px-3 py-3">
            <button className="btn-accent" disabled={sending}><Send size={15} /> {t('Отправить')}</button>
            <input ref={fileRef} type="file" multiple className="hidden" onChange={(e) => { const picked = Array.from(e.target.files ?? []); setFiles((previous) => [...previous, ...picked]); e.target.value = ''; }} />
            <IconButton icon={Paperclip} label={t('Прикрепить файлы')} onClick={() => fileRef.current?.click()} />
            <span className="ml-1 hidden text-[11px] text-faint sm:inline">{t('Подпись добавится автоматически')}</span>
            <IconButton icon={Trash2} label={t('Удалить черновик')} className="ml-auto" onClick={closeComposer} />
          </footer>
        </form>
      )}
    </section>
  );
}
