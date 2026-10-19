import { useEffect, useRef, useState } from 'react';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import { DEMO_MESSAGES, DEMO_ROOMS, DemoMsg } from '../../api/demoData';

/** Чат: в проде сообщения идут STOMP-ом (см. api/chatSocket.ts), в демо-режиме — локальное состояние. */
export default function ChatPage() {
  const [roomId, setRoomId] = useState('r2');
  const [messages, setMessages] = useState<DemoMsg[]>(DEMO_MESSAGES);
  const [draft, setDraft] = useState('');
  const [reactions, setReactions] = useState<Record<string, number>>({ c1: 2 });
  const bottomRef = useRef<HTMLDivElement>(null);
  const room = DEMO_ROOMS.find((r) => r.id === roomId)!;
  const roomMsgs = messages.filter((m) => m.roomId === roomId);
  const readOnly = roomId === 'r1';

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [roomMsgs.length, roomId]);

  const send = () => {
    const text = draft.trim();
    if (!text) return;
    setMessages((prev) => [...prev, { id: `c${Date.now()}`, roomId, author: 'Вы', text, time: new Date().toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' }), mine: true }]);
    setDraft('');
  };

  const renderMarkdown = (md: string) => ({ __html: DOMPurify.sanitize(marked.parse(md) as string) });

  return (
    <div className="flex h-full min-h-0">
      <aside className="w-72 shrink-0 border-r bg-white">
        <div className="border-b p-3 font-semibold">Чаты и каналы</div>
        {DEMO_ROOMS.map((r) => (
          <button key={r.id} onClick={() => setRoomId(r.id)} className={`block w-full border-b px-3 py-3 text-left hover:bg-slate-50 ${roomId === r.id ? 'bg-blue-50' : ''}`}>
            <div className="flex items-center justify-between">
              <span className="font-medium">{r.online !== undefined && <span className={`mr-1.5 inline-block h-2 w-2 rounded-full ${r.online ? 'bg-green-500' : 'bg-slate-300'}`} />}{r.title}</span>
              {r.unread > 0 && <span className="rounded-full bg-brand px-2 py-0.5 text-xs text-white">{r.unread}</span>}
            </div>
            <div className="text-xs text-slate-500">{r.kind}</div>
          </button>
        ))}
        <p className="p-3 text-xs text-slate-500">Статусы присутствия и «печатает…» — через Redis + STOMP-топики присутствия.</p>
      </aside>

      <section className="flex min-w-0 flex-1 flex-col bg-slate-50">
        <header className="border-b bg-white px-4 py-3">
          <div className="font-semibold">{room.title}</div>
          <div className="text-xs text-slate-500">{room.kind}{room.online ? ' · онлайн' : ''}</div>
        </header>
        <div className="scroll-area flex-1 space-y-3 p-4">
          {roomMsgs.map((m) => (
            <div key={m.id} className={`flex ${m.mine ? 'justify-end' : 'justify-start'}`}>
              <div className={`max-w-[75%] rounded-2xl px-4 py-2 shadow-sm ${m.mine ? 'bg-brand text-white' : 'bg-white'}`}>
                {!m.mine && <div className="text-xs font-semibold text-brand-dark">{m.author}</div>}
                <div className="prose-sm [&_p]:m-0" dangerouslySetInnerHTML={renderMarkdown(m.text)} />
                <div className={`mt-1 flex items-center gap-2 text-[11px] ${m.mine ? 'text-blue-100' : 'text-slate-400'}`}>
                  <span>{m.time}{m.mine && ' · ✓✓ прочитано'}</span>
                  <button onClick={() => setReactions((p) => ({ ...p, [m.id]: (p[m.id] ?? 0) + 1 }))} className="hover:scale-110">👍 {reactions[m.id] ?? ''}</button>
                  <span className="cursor-pointer underline">ответить в тред</span>
                </div>
              </div>
            </div>
          ))}
          <div ref={bottomRef} />
        </div>
        {readOnly ? (
          <div className="border-t bg-white p-3 text-center text-sm text-slate-500">🔒 Системный канал: писать могут только администраторы</div>
        ) : (
          <div className="flex items-end gap-2 border-t bg-white p-3">
            <button title="Прикрепить файл" className="rounded-lg border px-3 py-2">📎</button>
            <button title="Голосовое сообщение" className="rounded-lg border px-3 py-2">🎤</button>
            <textarea value={draft} onChange={(e) => setDraft(e.target.value)} rows={1}
              onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
              placeholder="Сообщение… @упоминания, **Markdown**, Enter — отправить"
              className="flex-1 resize-none rounded-lg border px-3 py-2 outline-none focus:border-brand" />
            <button onClick={send} className="rounded-lg bg-brand px-4 py-2 text-white">Отправить</button>
          </div>
        )}
      </section>
    </div>
  );
}
