import { useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { useToast } from '../../components/ToastProvider';
import { CheckCircle2, Clock, Maximize2, Minimize2, Paperclip, Send, Trash2, X } from 'lucide-react';
import type { ExternalMailAccount } from '../../api/externalMailApi';
import { ApiError } from '../../api/httpClient';
import { mailApi, type SavedDraft } from '../../api/mailApi';
import { IconButton } from '../../components/ui';
import { useModalFocus } from '../../components/useModalFocus';
import { useI18n } from '../../i18n';
import ComposeEditor, { type ComposeEditorHandle } from './ComposeEditor';
import { attachmentToFile, buildDraftPayload, draftSizeProblem, draftStatusKey, remainingAttachmentBytes, sendSizeProblem } from './drafts';

export interface ComposeDraft {
  to: string;
  cc?: string;
  /** Скрытая копия: сервер её принимает и раньше умел, а форма не показывала. */
  bcc?: string;
  subject: string;
  body: string;
  bodyHtml?: string;
  externalAccountId?: string | null;
  /**
   * Письмо, на которое отвечаем. По нему сервер проставляет In-Reply-To и
   * References, чтобы переписка собиралась в цепочку у получателя.
   */
  inReplyToMessageId?: string | null;
  /** «Отправить позже»: время в ISO. Пусто — отправить сразу. */
  sendAt?: string | null;
}

/**
 * Ближайшие удобные моменты для «Отправить позже». Не «через час», а
 * «завтра в 8:00»: человек думает о времени суток, а не о дельте.
 */
export function scheduledSendOptions(now: Date): { id: string; at: Date; labelKey: string }[] {
  const at = (dayOffset: number, hour: number) => {
    const date = new Date(now);
    date.setDate(date.getDate() + dayOffset);
    date.setHours(hour, 0, 0, 0);
    return date;
  };
  // Если сейчас раннее утро, «сегодня в 8:00» ещё впереди; иначе это прошлое.
  const morning = now.getHours() < 8 ? at(0, 8) : at(1, 8);
  const evening = now.getHours() < 18 ? at(0, 18) : at(1, 18);
  return [
    { id: 'morning', at: morning, labelKey: 'Завтра утром' },
    { id: 'evening', at: evening, labelKey: 'Сегодня вечером' },
    { id: 'tomorrow', at: at(1, 9), labelKey: 'Завтра в 9:00' },
    { id: 'monday', at: nextWeekday(now, 1), labelKey: 'В понедельник в 9:00' },
  ];
}

/** Ближайший заданный день недели (1 = понедельник) в 9:00. */
function nextWeekday(now: Date, weekday: number): Date {
  const date = new Date(now);
  const delta = (weekday - date.getDay() + 7) % 7 || 7;
  date.setDate(date.getDate() + delta);
  date.setHours(9, 0, 0, 0);
  return date;
}

/** Локальное время в формате `datetime-local`, без секунд и зоны. */
export function toLocalInputValue(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
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
  const [showBcc, setShowBcc] = useState(Boolean(initial.bcc));
  // Подтверждение «отправить без темы / без вложения» не должно спрашиваться дважды.
  const [confirmPending, setConfirmPending] = useState<'subject' | 'attachment' | null>(null);
  const [files, setFiles] = useState<File[]>(() =>
    initialFiles?.length ? initialFiles
      : (initialDraft?.payload.attachments ?? []).map((attachment) => attachmentToFile(attachment)));
  const [draggingFiles, setDraggingFiles] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [sent, setSent] = useState(false);
  const [sentCopySaved, setSentCopySaved] = useState(true);
  // Время фактически запланированной отправки: экран успеха должен говорить
  // «отправится позже», а не «отправлено», иначе человек не станет отменять.
  const [scheduledAt, setScheduledAt] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  // «Отправить позже»: null — обычная отправка, иначе локальное время.
  const [sendAtLocal, setSendAtLocal] = useState<string | null>(() => {
    // Черновик хранит время в ISO, input — в локальном виде без зоны.
    if (!initial.sendAt) return null;
    const parsed = new Date(initial.sendAt);
    return Number.isNaN(parsed.getTime()) ? null : toLocalInputValue(parsed);
  });
  const [savedId, setSavedId] = useState<string | null>(initialDraft?.id ?? null);
  const savedRevision = useRef<number>(initialDraft?.revision ?? 0);
  const [draftStatus, setDraftStatus] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');
  const [draftNote, setDraftNote] = useState('');
  const savingDraft = useRef(false);
  // Двойной submit (клик + Enter, двойной клик) не должен отправить письмо дважды.
  const sendingRef = useRef(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const bodyRef = useRef<ComposeEditorHandle>(null);
  const dialogRef = useRef<HTMLElement>(null);
  const toRef = useRef<HTMLInputElement>(null);
  const { notify } = useToast();
  // Тот же механизм, что у остальных модальных окон: фокус не уходит за пределы
  // письма, а после закрытия возвращается туда, откуда его открыли.
  useModalFocus(dialogRef, toRef);
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

  const hasContent = Boolean(draft.to || draft.cc || draft.bcc || draft.subject || draft.body || files.length);

  const closeComposer = () => {
    if (sending) return;
    if (!sent && hasContent &&
        !window.confirm(t('Закрыть письмо? Несохранённый текст и вложения будут потеряны.'))) return;
    onClose();
  };

  /**
   * Отправка. Единственная точка входа: и кнопка, и Ctrl/⌘+Enter идут сюда,
   * поэтому двойное нажатие не может отправить письмо дважды.
   *
   * Проверки перед отправкой — только те, которые человек может исправить:
   * пустое тело, забытое вложение, пустая тема. Отсутствие вложения не блокирует,
   * а предупреждает: подтверждаем повторным нажатием.
   */
  const submit = (e?: FormEvent<HTMLFormElement>) => {
    e?.preventDefault();
    if (sendingRef.current) return;
    if (!draft.body.trim()) { setSendError(t('Введите текст письма.')); return; }
    // Размер проверяется до запроса: иначе сервер отвечает 413, а браузер
    // показывает пустое «ошибка сервера» без подсказки, что делать.
    const sizeProblem = sendSizeProblem(files);
    if (sizeProblem) {
      setSendError(sizeProblem === 'count'
        ? t('Слишком много вложений: максимум 20 файлов.')
        : t('Вложения слишком большие: суммарно до 20 МБ (вместе с кодированием).'));
      return;
    }
    const mentionsAttachment = /вложени|прикреп|attach/i.test(`${draft.subject} ${draft.body}`);
    if (mentionsAttachment && files.length === 0 && confirmPending !== 'attachment') {
      setConfirmPending('attachment');
      setSendError(t('В письме упомянуто вложение, но файлов нет. Нажмите «Отправить» ещё раз, чтобы отправить без вложений.'));
      return;
    }
    if (!draft.subject.trim() && confirmPending !== 'subject') {
      setConfirmPending('subject');
      setSendError(t('Тема не заполнена. Нажмите «Отправить» ещё раз, чтобы отправить без темы.'));
      return;
    }
    if (sendAtLocal) {
      const when = new Date(sendAtLocal);
      if (Number.isNaN(when.getTime()) || when.getTime() <= Date.now()) {
        setSendError(t('Время отложенной отправки уже прошло. Выберите другое.'));
        return;
      }
    }
    setConfirmPending(null);
    sendingRef.current = true;
    void (async () => {
      setSending(true);
      setSendError(null);
      try {
        const copySaved = await onSend(
          sendAtLocal ? { ...draft, sendAt: new Date(sendAtLocal).toISOString() } : draft,
          files,
        );
        setSentCopySaved(copySaved !== false);
        const planned = sendAtLocal ? new Date(sendAtLocal).toISOString() : null;
        setScheduledAt(planned);
        await dropSavedDraft();
        setSent(true);
        notify({
          message: planned ? t('Отправка запланирована.') : t('Письмо отправлено.'),
          tone: 'success',
          durationMs: 4000,
        });
        window.setTimeout(onClose, 900);
      } catch (error) {
        // Сервер мог поставить письмо в очередь до ошибки. Повторять отправку
        // вслепую нельзя, поэтому обещаем только то, что знаем наверняка.
        const rejected = error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== 408;
        // 413 — размер письма. Сервер уже сказал предел; повторять тот же
        // запрос бессмысленно, поэтому объясняем, что именно уменьшить.
        if (error instanceof ApiError && error.status === 413) {
          setSendError(t('Письмо слишком большое для сервера. Уберите часть вложений или отправьте их отдельно.'));
          return;
        }
        setSendError(rejected
          ? errorMessage(error)
          : `${t('Не удалось подтвердить отправку. Письмо могло попасть в очередь; перед повтором уточните статус у администратора или получателей.')} ${errorMessage(error)}`);
      } finally {
        sendingRef.current = false;
        setSending(false);
      }
    })();
  };

  // Escape из MailPage приходит событием: композер сам решает, спросить про
  // несохранённый текст или закрыть пустое окно. Прямое обнуление состояния
  // в обход этого пути теряло последние правки.
  useEffect(() => {
    const section = dialogRef.current;
    if (!section) return undefined;
    const onRequestClose = () => closeComposer();
    section.addEventListener('corvex:close-compose', onRequestClose);
    return () => section.removeEventListener('corvex:close-compose', onRequestClose);
  });

  // Ctrl/⌘+Enter отправляет из любого поля, включая редактор тела: раньше
  // такого сочетания не было вовсе, и отправить можно было только мышью.
  const onKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      submit();
    }
  };

  return (
    <section
      ref={dialogRef}
      data-compose-panel
      tabIndex={-1}
      role="dialog"
      // `aria-label` — стабильное имя окна (по нему его находят тесты и
      // пользователь скринридера); видимый заголовок остаётся в `aria-labelledby`.
      aria-label={t('Новое письмо')}
      aria-modal="true"
      aria-labelledby="compose-title"
      onKeyDown={onKeyDown}
      className={`fixed z-50 flex animate-rise-in flex-col overflow-hidden border border-line bg-surface shadow-composer ${expanded ? 'inset-0 sm:inset-4 sm:rounded-2xl' : 'inset-x-0 bottom-0 max-h-[94dvh] sm:inset-x-auto sm:bottom-4 sm:right-4 sm:h-[min(640px,92dvh)] sm:w-[min(590px,calc(100vw-2rem))] sm:rounded-2xl'}`}>
      <header className="flex h-11 shrink-0 items-center bg-ink px-4 text-white">
        <h3 id="compose-title" className="flex-1 text-sm font-medium">{initial.subject ? t('Ответ / пересылка') : t('Новое письмо')}</h3>
        <button type="button" onClick={() => setExpanded((value) => !value)} aria-pressed={expanded} className="flex h-8 w-8 items-center justify-center rounded-lg text-white/70 transition hover:bg-white/10 hover:text-white" aria-label={expanded ? t('Уменьшить окно письма') : t('Развернуть окно письма')}>{expanded ? <Minimize2 size={16} /> : <Maximize2 size={16} />}</button>
        <button type="button" onClick={closeComposer} disabled={sending} className="flex h-8 w-8 items-center justify-center rounded-lg text-white/70 transition hover:bg-white/10 hover:text-white disabled:opacity-50" aria-label={t('Закрыть черновик')}><X size={16} /></button>
      </header>

      {sent ? (
        <div className="flex flex-1 flex-col items-center justify-center px-8 text-center">
          <span className="flex h-14 w-14 items-center justify-center rounded-full bg-emerald-50 text-emerald-600"><CheckCircle2 size={27} /></span>
          <h4 className="mt-4 text-lg font-semibold">{scheduledAt ? t('Письмо отправится позже') : t('Письмо отправлено')}</h4>
          <p className={`mt-1 text-sm ${sentCopySaved ? 'text-muted' : 'text-danger'}`} role={sentCopySaved ? undefined : 'alert'}>
            {scheduledAt
              ? t('Отправка запланирована на {time}. Копия уже лежит в «Отправленных», отправку можно отменить до этого момента.', { time: new Date(scheduledAt).toLocaleString() })
              : sentCopySaved
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
            <input ref={toRef} id="compose-to" required type="text" inputMode="email" list={suggestionsId} value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} placeholder="user@company.local" className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none placeholder:text-faint focus:ring-0" />
            <datalist id={suggestionsId}>
              {contactSuggestions.map((email) => <option key={email} value={email} />)}
            </datalist>
            {!showCc && <button type="button" onClick={() => setShowCc(true)} className="shrink-0 text-xs font-medium text-muted hover:text-ink">{t('Копия')}</button>}
            {!showBcc && <button type="button" onClick={() => setShowBcc(true)} className="shrink-0 text-xs font-medium text-muted hover:text-ink">{t('Скрытая')}</button>}
          </div>
          {showCc && (
            <div className="flex h-12 shrink-0 items-center gap-3 border-b border-line px-4">
              <label htmlFor="compose-cc" className="w-12 shrink-0 text-[13px] text-faint">{t('Копия')}</label>
              <input id="compose-cc" type="text" inputMode="email" list={suggestionsId} value={draft.cc ?? ''} onChange={(e) => setDraft({ ...draft, cc: e.target.value })} placeholder="user@company.local" className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none placeholder:text-faint focus:ring-0" />
            </div>
          )}
          {showBcc && (
            <div className="flex h-12 shrink-0 items-center gap-3 border-b border-line px-4">
              <label htmlFor="compose-bcc" className="w-12 shrink-0 text-[13px] text-faint">{t('Скрытая')}</label>
              <input id="compose-bcc" type="text" inputMode="email" list={suggestionsId} value={draft.bcc ?? ''} onChange={(e) => setDraft({ ...draft, bcc: e.target.value })} placeholder="user@company.local" className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none placeholder:text-faint focus:ring-0" />
            </div>
          )}
          <div className="flex h-12 shrink-0 items-center gap-3 border-b border-line px-4">
            <label htmlFor="compose-subject" className="w-12 shrink-0 text-[13px] text-faint">{t('Тема')}</label>
            {/* Тема больше не `required`: письмо без темы — нормальный выбор,
                но о нём предупреждает подтверждение перед отправкой. */}
            <input id="compose-subject" value={draft.subject} onChange={(e) => setDraft({ ...draft, subject: e.target.value })} placeholder={t('О чём письмо?')} className="min-w-0 flex-1 border-0 bg-transparent text-sm font-medium outline-none placeholder:font-normal placeholder:text-faint focus:ring-0" />
          </div>
          <ComposeEditor ref={bodyRef} initialText={initial.body} initialHtml={initial.bodyHtml} onChange={(body, bodyHtml) => setDraft((current) => ({ ...current, body, bodyHtml }))} />
          {files.length > 0 && (
            <div className="flex shrink-0 flex-wrap gap-2 px-4 pb-3">
              {files.map((file, index) => <span key={`${file.name}-${index}`} className="chip"><Paperclip size={12} />{file.name}<button type="button" className="ml-1 rounded p-0.5 hover:text-danger" aria-label={`${t('Удалить вложение')}: ${file.name}`} onClick={() => setFiles((current) => current.filter((_, i) => i !== index))}><X size={13} /></button></span>)}
              {/* Остаток бюджета виден сразу: человек не узнаёт о лимите из «413». */}
              <span className="self-center text-[11px] text-faint">
                {t('Можно приложить ещё {size}', { size: `${Math.floor(remainingAttachmentBytes(files) / (1024 * 1024))} МБ` })}
              </span>
            </div>
          )}
          {sendError && (
            <div className="mx-4 mb-3 shrink-0 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800" role="alert">{sendError}</div>
          )}
          <footer className="flex shrink-0 items-center gap-2 border-t border-line px-3 py-3">
            <button className="btn-accent" disabled={sending}>
              <Send size={15} /> {sendAtLocal ? t('Запланировать отправку') : t('Отправить')}
            </button>
            {/* Отложенная отправка: время выбирается здесь, а не в отдельном
                окне — иначе письмо пришлось бы собирать заново. */}
            <div className="flex items-center gap-1">
              <label htmlFor="compose-send-at" className="sr-only">{t('Отправить позже')}</label>
              <IconButton icon={Clock} label={t('Отправить позже')} onClick={() => setSendAtLocal(sendAtLocal ? null : toLocalInputValue(scheduledSendOptions(new Date())[0].at))} />
              {sendAtLocal && (
                <>
                  <input
                    id="compose-send-at"
                    type="datetime-local"
                    value={sendAtLocal}
                    min={toLocalInputValue(new Date(Date.now() + 60_000))}
                    onChange={(event) => setSendAtLocal(event.target.value || null)}
                    className="field h-8 w-[190px] text-xs"
                  />
                  <button type="button" className="text-[11px] text-muted underline" onClick={() => setSendAtLocal(null)}>
                    {t('Отменить')}
                  </button>
                </>
              )}
            </div>
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
