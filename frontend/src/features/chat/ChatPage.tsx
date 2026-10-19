import { useEffect, useMemo, useRef, useState } from 'react';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import {
  ArrowLeft,
  CheckCheck,
  Hash,
  Lock,
  Megaphone,
  Mic,
  MoreHorizontal,
  Paperclip,
  PenSquare,
  Phone,
  Reply,
  Search,
  SendHorizontal,
  Users,
  Video,
} from 'lucide-react';
import { demoMessages, demoRooms, DemoMsg, DemoRoom } from '../../api/demoData';
import { EmojiPickerButton } from '../../components/EmojiPicker';
import { Avatar, EmptyState, IconButton } from '../../components/ui';
import { formatTime, useI18n } from '../../i18n';
import { firstGraphemes, insertTextAtSelection, stripMarkdownPreview } from '../../lib/text';
import { useSession } from '../../store/sessionStore';

const cleanTitle = (title: string) => title.replace(/^[^A-Za-zА-Яа-я0-9]+/, '').trim();
const plainText = stripMarkdownPreview;

function RoomMark({ room, size = 40 }: { room: DemoRoom; size?: number }) {
  if (room.online !== undefined) return <Avatar name={cleanTitle(room.title)} size={size} presence={room.online} />;
  const Icon = room.id === 'r1' ? Megaphone : room.id === 'r3' ? Users : Hash;
  return (
    <span className="flex shrink-0 items-center justify-center rounded-full bg-black/[0.055] text-muted" style={{ width: size, height: size }}>
      <Icon size={Math.round(size * 0.46)} strokeWidth={1.8} />
    </span>
  );
}

/** Сообщения Quiet Work: корпоративные строки Slack вместо потребительских «пузырей». */
export default function ChatPage() {
  const { locale, t } = useI18n();
  const { user } = useSession();
  const [rooms, setRooms] = useState<DemoRoom[]>(() => demoRooms(locale));
  const [roomId, setRoomId] = useState('r2');
  const [messages, setMessages] = useState<DemoMsg[]>(() => demoMessages(locale));
  const [draft, setDraft] = useState('');
  const [search, setSearch] = useState('');
  const [mobileConversation, setMobileConversation] = useState(false);
  const [reactions, setReactions] = useState<Record<string, number>>({ c1: 2 });
  const [reacted, setReacted] = useState<Set<string>>(new Set());
  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  const room = rooms.find((r) => r.id === roomId) ?? rooms[0];
  const roomMsgs = useMemo(() => messages.filter((m) => m.roomId === roomId), [messages, roomId]);
  const readOnly = roomId === 'r1';

  const lastByRoom = useMemo(() => {
    const map = new Map<string, DemoMsg>();
    messages.forEach((m) => map.set(m.roomId, m));
    return map;
  }, [messages]);

  const visibleRooms = rooms.filter((r) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    const last = lastByRoom.get(r.id);
    return `${cleanTitle(r.title)} ${r.kind} ${last?.text ?? ''}`.toLowerCase().includes(q);
  });
  const channels = visibleRooms.filter((r) => r.online === undefined);
  const direct = visibleRooms.filter((r) => r.online !== undefined);

  useEffect(() => {
    const localizedRooms = demoRooms(locale);
    const localizedMessages = demoMessages(locale);
    setRooms((prev) => prev.map((room) => {
      const translated = localizedRooms.find((candidate) => candidate.id === room.id);
      return translated ? { ...translated, unread: room.unread } : room;
    }));
    setMessages((prev) => prev.map((message) => {
      const translated = localizedMessages.find((candidate) => candidate.id === message.id);
      return translated ? { ...translated, mine: message.mine } : message;
    }));
  }, [locale]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [roomMsgs.length, roomId]);

  const selectRoom = (id: string) => {
    setRoomId(id);
    setMobileConversation(true);
    setRooms((prev) => prev.map((r) => (r.id === id ? { ...r, unread: 0 } : r)));
  };

  const send = () => {
    const text = draft.trim();
    if (!text) return;
    setMessages((prev) => [...prev, {
      id: `c${Date.now()}`,
      roomId,
      author: user?.displayName ?? t('Вы'),
      text,
      time: formatTime(locale, new Date()),
      mine: true,
    }]);
    setDraft('');
    requestAnimationFrame(() => inputRef.current?.focus());
  };

  const toggleReaction = (id: string) => {
    setReacted((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
        setReactions((counts) => ({ ...counts, [id]: Math.max(0, (counts[id] ?? 0) - 1) }));
      } else {
        next.add(id);
        setReactions((counts) => ({ ...counts, [id]: (counts[id] ?? 0) + 1 }));
      }
      return next;
    });
  };

  const insertEmoji = (emoji: string) => {
    const element = inputRef.current;
    const next = insertTextAtSelection(draft, emoji, element?.selectionStart ?? null, element?.selectionEnd ?? null);
    setDraft(next.value);
    requestAnimationFrame(() => {
      element?.focus();
      element?.setSelectionRange(next.cursor, next.cursor);
    });
  };

  const replyTo = (m: DemoMsg) => {
    const name = m.mine ? (user?.displayName ?? t('Вы')) : m.author;
    setDraft(`@${firstGraphemes(name.split(' ')[0].toLowerCase(), 24)} `);
    inputRef.current?.focus();
  };

  const renderMarkdown = (md: string) => ({
    // Внешние <img> в чате запрещены политикой приватности: tracking pixel не должен
    // сообщать третьим лицам факт/время прочтения и IP сотрудника. Файловые вложения
    // этапа 4 будут рендериться отдельным доверенным компонентом, не Markdown-HTML.
    __html: DOMPurify.sanitize(marked.parse(md) as string, {
      FORBID_TAGS: ['img', 'iframe', 'video', 'audio', 'source', 'style'],
      FORBID_ATTR: ['style'],
    }),
  });

  const renderRoom = (r: DemoRoom) => {
    const last = lastByRoom.get(r.id);
    const selected = roomId === r.id;
    return (
      <button key={r.id} type="button" onClick={() => selectRoom(r.id)} className={`flex w-full items-start gap-3 rounded-xl px-3 py-3 text-left transition ${selected ? 'bg-accent-soft/80' : 'hover:bg-black/[0.035]'}`}>
        <RoomMark room={r} />
        <span className="min-w-0 flex-1">
          <span className="flex items-center gap-2">
            <span className={`min-w-0 flex-1 truncate text-sm ${r.unread > 0 ? 'font-semibold text-ink' : 'font-medium text-ink-soft'}`}>{cleanTitle(r.title)}</span>
            {last && <span className="shrink-0 text-[11px] text-faint">{last.time}</span>}
          </span>
          <span className="mt-0.5 block truncate text-[13px] text-muted">{last ? plainText(last.text) : r.kind}</span>
          <span className="mt-0.5 block truncate text-[11px] text-faint">{r.online === undefined ? r.kind : r.online ? t('В сети') : t('Не в сети')}</span>
        </span>
        {r.unread > 0 && <span className="mt-1 flex h-5 min-w-5 shrink-0 items-center justify-center rounded-full bg-accent px-1.5 text-[11px] font-semibold text-white">{r.unread}</span>}
      </button>
    );
  };

  if (!room) return <EmptyState icon={Hash} title={t('Чатов пока нет')} />;

  return (
    <div className="h-full lg:p-3 xl:p-4">
      <div className="flex h-full min-h-0 overflow-hidden bg-surface lg:rounded-[18px] lg:border lg:border-line lg:shadow-soft">
        {/* Список разговоров */}
        <aside className={`${mobileConversation ? 'hidden md:flex' : 'flex'} w-full shrink-0 flex-col border-r border-line bg-chrome md:w-[320px] xl:w-[344px]`}>
          <header className="shrink-0 px-4 pb-3 pt-5">
            <div className="flex items-center">
              <h1 className="flex-1 text-[19px] font-semibold tracking-[-0.025em]">{t('Сообщения')}</h1>
              <IconButton icon={PenSquare} label={t('Новый разговор')} />
            </div>
            <div className="relative mt-3">
              <Search size={16} className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-faint" />
              <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder={t('Поиск людей и каналов')} className="search-field bg-black/[0.04]" />
            </div>
          </header>

          <div className="scroll-area min-h-0 flex-1 px-2 pb-4">
            {channels.length > 0 && (
              <>
                <p className="section-label mb-1 mt-2">{t('Каналы и группы')}</p>
                <div className="space-y-0.5">{channels.map(renderRoom)}</div>
              </>
            )}
            {direct.length > 0 && (
              <>
                <p className="section-label mb-1 mt-5">{t('Личные сообщения')}</p>
                <div className="space-y-0.5">{direct.map(renderRoom)}</div>
              </>
            )}
            {visibleRooms.length === 0 && <EmptyState icon={Search} title={t('Ничего не найдено')} description={t('Попробуйте другое имя или название канала.')} />}
          </div>
        </aside>

        {/* Разговор */}
        <section className={`${mobileConversation ? 'flex' : 'hidden md:flex'} min-w-0 flex-1 flex-col bg-surface`}>
          <header className="flex min-h-[68px] shrink-0 items-center gap-3 border-b border-line px-4 py-3">
            <button type="button" onClick={() => setMobileConversation(false)} className="icon-btn md:hidden" aria-label={t('К списку чатов')}><ArrowLeft size={19} /></button>
            <RoomMark room={room} size={42} />
            <div className="min-w-0 flex-1">
              <h2 className="truncate text-[16px] font-semibold tracking-[-0.015em]">{cleanTitle(room.title)}</h2>
              <p className="truncate text-xs text-muted">{room.online ? t('В сети') : room.kind}</p>
            </div>
            <div className="flex items-center gap-0.5">
              <span className="hidden sm:block"><IconButton icon={Search} label={t('Поиск в разговоре')} /></span>
              <span className="hidden sm:block"><IconButton icon={Phone} label={t('Аудиозвонок')} /></span>
              <span className="hidden md:block"><IconButton icon={Video} label={t('Видеозвонок')} /></span>
              <IconButton icon={MoreHorizontal} label={t('Ещё действия')} />
            </div>
          </header>

          <div className="scroll-area min-h-0 flex-1 px-3 py-5 sm:px-5">
            <div className="mx-auto mb-5 flex max-w-[820px] items-center gap-3 text-[11px] font-medium text-faint">
              <span className="h-px flex-1 bg-line" />{t('Сегодня')}<span className="h-px flex-1 bg-line" />
            </div>

            <div className="mx-auto max-w-[820px]">
              {roomMsgs.map((m, index) => {
                const previous = roomMsgs[index - 1];
                const sameAuthor = previous && previous.author === m.author && previous.mine === m.mine;
                const author = m.mine ? (user?.displayName ?? t('Вы')) : m.author;
                const count = reactions[m.id] ?? 0;
                return (
                  <div key={m.id} className={`group relative flex gap-3 rounded-xl px-2 py-1.5 transition hover:bg-chrome ${sameAuthor ? '' : 'mt-4 first:mt-0'}`}>
                    <div className="w-10 shrink-0">
                      {!sameAuthor && <Avatar name={author} size={38} />}
                    </div>
                    <div className="min-w-0 max-w-[720px] flex-1">
                      {!sameAuthor && (
                        <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
                          <span className="text-sm font-semibold text-ink">{author}</span>
                          {m.mine && <span className="text-[11px] font-medium text-faint">{t('Вы')}</span>}
                          <time className="text-[11px] text-faint">{m.time}</time>
                          {m.mine && <CheckCheck size={13} className="translate-y-0.5 text-accent" aria-label={t('Прочитано')} />}
                        </div>
                      )}
                      <div className="message-markdown mt-0.5 text-[14.5px] leading-relaxed text-ink-soft [&_p]:m-0" dangerouslySetInnerHTML={renderMarkdown(m.text)} />
                      {count > 0 && (
                        <button type="button" onClick={() => toggleReaction(m.id)} className={`mt-1.5 inline-flex items-center gap-1.5 rounded-full border px-2 py-1 text-[11px] font-medium transition ${reacted.has(m.id) ? 'border-accent/25 bg-accent-soft text-accent-dark' : 'border-line bg-surface text-muted hover:border-accent/25'}`}>
                          <span className="emoji-text text-[13px] leading-none" role="img" aria-label={t('Реакция: большой палец вверх')}>👍</span> {count}
                        </button>
                      )}
                    </div>
                    <div className="absolute -top-3 right-3 hidden items-center rounded-lg border border-line bg-surface p-0.5 shadow-soft group-hover:flex">
                      <button type="button" onClick={() => toggleReaction(m.id)} className="icon-btn-sm emoji-text text-[15px]" title={t('Нравится')} aria-label={t('Нравится')}>👍</button>
                      <button type="button" onClick={() => replyTo(m)} className="icon-btn-sm" title={t('Ответить')} aria-label={t('Ответить')}><Reply size={15} /></button>
                      <button type="button" className="icon-btn-sm" title={t('Ещё')} aria-label={t('Ещё действия с сообщением')}><MoreHorizontal size={15} /></button>
                    </div>
                  </div>
                );
              })}
              {roomMsgs.length === 0 && <EmptyState icon={Hash} title={t('Пока нет сообщений')} description={t('Начните разговор первым сообщением ниже.')} />}
              <div ref={bottomRef} />
            </div>
          </div>

          <div className="shrink-0 px-3 pb-3 sm:px-5 sm:pb-5">
            <div className="mx-auto max-w-[820px]">
              {readOnly ? (
                <div className="flex items-center justify-center gap-2 rounded-2xl border border-line bg-chrome px-4 py-3.5 text-center text-sm text-muted">
                  <Lock size={16} /> {t('Это канал объявлений. Публиковать могут только администраторы.')}
                </div>
              ) : (
                <div className="rounded-[18px] border border-line bg-surface shadow-soft transition focus-within:border-accent/40 focus-within:ring-4 focus-within:ring-accent/10">
                  <textarea
                    ref={inputRef}
                    value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                    rows={2}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && !e.shiftKey) {
                        e.preventDefault();
                        send();
                      }
                    }}
                    placeholder={t('Сообщение для {name}…', { name: cleanTitle(room.title) })}
                    className="max-h-36 min-h-[58px] w-full resize-none border-0 bg-transparent px-4 pb-1 pt-3.5 text-[14.5px] leading-relaxed outline-none placeholder:text-faint focus:ring-0"
                  />
                  <div className="flex items-center gap-0.5 px-2 pb-2">
                    <IconButton icon={Paperclip} label={t('Прикрепить файл')} className="h-8 w-8" size={16} />
                    <EmojiPickerButton onPick={insertEmoji} className="h-8 w-8" />
                    <IconButton icon={Mic} label={t('Голосовое сообщение')} className="h-8 w-8" size={16} />
                    <span className="ml-2 hidden text-[11px] text-faint lg:inline">{t('Enter — отправить · Shift+Enter — новая строка')}</span>
                    <button
                      type="button"
                      onClick={send}
                      disabled={!draft.trim()}
                      className="ml-auto flex h-9 w-9 items-center justify-center rounded-full bg-ink text-white transition hover:bg-ink-soft disabled:cursor-not-allowed disabled:bg-black/[0.08] disabled:text-faint"
                      aria-label={t('Отправить сообщение')}
                    >
                      <SendHorizontal size={16} />
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>
        </section>
      </div>
    </div>
  );
}
