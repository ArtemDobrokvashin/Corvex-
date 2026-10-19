import { useMemo, useState } from 'react';
import { DEMO_FOLDERS, DEMO_MAILS, DemoMail } from '../../api/demoData';

/** Почта: папки → список (виртуализация при >200 писем — этап 3) → чтение. На мобильном — один столбец с кнопкой «назад». */
export default function MailPage() {
  const [folder, setFolder] = useState('INBOX');
  const [search, setSearch] = useState('');
  const [mails, setMails] = useState<DemoMail[]>(DEMO_MAILS);
  const [active, setActive] = useState<DemoMail | null>(null);
  const [composing, setComposing] = useState(false);

  const visible = useMemo(
    () => mails.filter((m) => m.folder === folder && (m.subject + m.from + m.snippet).toLowerCase().includes(search.toLowerCase())),
    [mails, folder, search],
  );

  const open = (m: DemoMail) => {
    setActive(m);
    setComposing(false);
    setMails((prev) => prev.map((x) => (x.id === m.id ? { ...x, seen: true } : x)));
  };

  return (
    <div className="flex h-full min-h-0">
      {/* Папки */}
      <aside className={`w-60 shrink-0 border-r bg-white p-3 ${active || composing ? 'hidden md:block' : ''}`}>
        <button onClick={() => { setComposing(true); setActive(null); }}
          className="mb-3 w-full rounded-lg bg-brand px-4 py-2.5 font-medium text-white hover:bg-brand-dark">
          ✉️ Написать письмо
        </button>
        <nav className="space-y-0.5">
          {DEMO_FOLDERS.map((f) => (
            <button key={f.id} onClick={() => { setFolder(f.id); setActive(null); setComposing(false); }}
              className={`flex w-full items-center justify-between rounded-lg px-3 py-2 text-left text-sm ${folder === f.id ? 'bg-blue-50 font-semibold text-brand-dark' : 'hover:bg-slate-50'}`}>
              <span>{f.name}</span>
              {f.unread > 0 && <span className="rounded-full bg-brand px-2 py-0.5 text-xs text-white">{f.unread}</span>}
            </button>
          ))}
        </nav>
        <div className="mt-6 rounded-lg bg-slate-50 p-3 text-xs text-slate-600">
          <div className="mb-1 flex justify-between"><span>Ящик: 5 ГБ</span><span>62%</span></div>
          <div className="h-2 rounded bg-slate-200"><div className="h-2 w-[62%] rounded bg-brand" /></div>
          <p className="mt-2">Квота задаётся администратором (ADMIN_QUOTAS_MANAGE).</p>
        </div>
      </aside>

      {/* Список писем */}
      <section className={`w-full max-w-md shrink-0 border-r bg-white md:w-96 ${active || composing ? 'hidden lg:block' : ''}`}>
        <div className="border-b p-3">
          <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Поиск: тема, отправитель, текст…"
            className="w-full rounded-lg border px-3 py-2 text-sm outline-none focus:border-brand" />
        </div>
        <div className="scroll-area h-[calc(100%-57px)] divide-y">
          {visible.map((m) => (
            <button key={m.id} onClick={() => open(m)} className={`block w-full p-3 text-left hover:bg-slate-50 ${active?.id === m.id ? 'bg-blue-50' : ''}`}>
              <div className="flex items-baseline justify-between gap-2">
                <span className={`truncate text-sm ${m.seen ? '' : 'font-bold'}`}>{m.from}</span>
                <span className="shrink-0 text-xs text-slate-500">{m.date}</span>
              </div>
              <div className={`truncate text-sm ${m.seen ? 'text-slate-700' : 'font-semibold'}`}>
                {m.flagged && '⭐ '}{m.hasAttachments && '📎 '}{m.subject}
              </div>
              <div className="truncate text-sm text-slate-500">{m.snippet}</div>
            </button>
          ))}
          {visible.length === 0 && <p className="p-6 text-center text-sm text-slate-500">Писем не найдено</p>}
        </div>
      </section>

      {/* Чтение / редактор */}
      <main className="scroll-area min-w-0 flex-1 bg-slate-50 p-4 md:p-6">
        {composing ? (
          <Compose onClose={() => setComposing(false)} onSend={() => setComposing(false)} />
        ) : active ? (
          <article className="mx-auto max-w-3xl rounded-xl bg-white p-5 shadow-sm">
            <button className="mb-3 text-sm text-brand lg:hidden" onClick={() => setActive(null)}>← К списку</button>
            <h1 className="text-xl font-semibold">{active.subject}</h1>
            <p className="mt-1 text-sm text-slate-600">{active.from} · {active.date}</p>
            {active.hasAttachments && (
              <div className="mt-3 rounded-lg border bg-slate-50 px-3 py-2 text-sm">📎 Вложение проверено антивирусом (ClamAV): <b>чисто</b> · <button className="text-brand underline">Скачать</button></div>
            )}
            <pre className="mt-4 whitespace-pre-wrap font-sans text-[15px] leading-relaxed">{active.body}</pre>
            <div className="mt-6 flex flex-wrap gap-2">
              <button className="rounded-lg bg-brand px-4 py-2 text-sm text-white" onClick={() => setComposing(true)}>Ответить</button>
              <button className="rounded-lg border px-4 py-2 text-sm">Переслать</button>
              <button className="rounded-lg border px-4 py-2 text-sm">В архив</button>
              <button className="rounded-lg border border-red-200 px-4 py-2 text-sm text-red-600">Удалить</button>
            </div>
          </article>
        ) : (
          <div className="flex h-full items-center justify-center text-slate-400">Выберите письмо</div>
        )}
      </main>
    </div>
  );
}

function Compose({ onClose, onSend }: { onClose: () => void; onSend: () => void }) {
  const [sent, setSent] = useState(false);
  return (
    <div className="mx-auto max-w-3xl rounded-xl bg-white p-5 shadow-sm">
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-lg font-semibold">Новое письмо</h2>
        <button onClick={onClose} className="text-slate-500">✕</button>
      </div>
      {sent ? (
        <div className="rounded-lg bg-green-50 p-4 text-green-800">✓ Письмо поставлено в очередь отправки (mail-smtp-service), копия — в «Отправленных».</div>
      ) : (
        <form onSubmit={(e) => { e.preventDefault(); setSent(true); setTimeout(onSend, 1600); }} className="space-y-3">
          <input required type="email" placeholder="Кому: user@company.local" className="w-full rounded-lg border px-3 py-2 outline-none focus:border-brand" />
          <input placeholder="Копия" className="w-full rounded-lg border px-3 py-2 outline-none focus:border-brand" />
          <input required placeholder="Тема" className="w-full rounded-lg border px-3 py-2 outline-none focus:border-brand" />
          <textarea required rows={9} placeholder="Текст письма… (подпись подставится автоматически)" className="w-full rounded-lg border px-3 py-2 outline-none focus:border-brand" />
          <label className="block cursor-pointer rounded-lg border border-dashed p-3 text-center text-sm text-slate-500">
            📎 Прикрепить файлы (до 25 МБ, проверяются ClamAV до отправки)
            <input type="file" multiple className="hidden" />
          </label>
          <div className="flex gap-2">
            <button className="rounded-lg bg-brand px-5 py-2 text-white">Отправить</button>
            <button type="button" className="rounded-lg border px-4 py-2" onClick={onClose}>Сохранить черновик</button>
          </div>
        </form>
      )}
    </div>
  );
}
