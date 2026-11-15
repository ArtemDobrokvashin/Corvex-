import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowLeft,
  CheckCheck,
  Hash,
  Lock,
  Megaphone,
  MessageSquare,
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
import { EmojiPickerButton, REACTION_EMOJI_OPTIONS, ReactionPickerButton } from '../../components/EmojiPicker';
import { createInitialDemoReactionState, demoReactionKey, toggleDemoReaction } from './demoReactions';
import { Avatar, EmptyState, IconButton } from '../../components/ui';
import ChatSidebar from '../../components/ChatSidebar';
import ChatThreadPanel from '../../components/ChatThreadPanel';
import SidebarSlot from '../../components/SidebarSlot';
import WorkspaceUtilityBar from '../../components/WorkspaceUtilityBar';
import { formatTime, useI18n } from '../../i18n';
import { firstGraphemes, insertTextAtSelection, stripMarkdownPreview } from '../../lib/text';
import { renderChatMarkdown } from '../../lib/markdown';
import { useSession } from '../../store/sessionStore';
import RealChatPage from './RealChatPage';

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
function DemoChatPage() {
  const { locale, t } = useI18n();
  const {
    user,
    demoReadRoomIds,
    demoSentChatMessages,
    markDemoRoomRead,
    addDemoSentChatMessage,
  } = useSession();
  const [rooms, setRooms] = useState<DemoRoom[]>(() => demoRooms(locale).map((room) => (
    demoReadRoomIds.includes(room.id) ? { ...room, unread: 0 } : room
  )));
  const [roomId, setRoomId] = useState('r2');
  const [messages, setMessages] = useState<DemoMsg[]>(() => {
    const seedMessages = demoMessages(locale);
    const seedIds = new Set(seedMessages.map((message) => message.id));
    return [
      ...seedMessages,
      ...demoSentChatMessages.filter((message) => !seedIds.has(message.id)),
    ];
  });
  const [draft, setDraft] = useState('');
  const [newRoomOpen, setNewRoomOpen] = useState(false);
  const [newRoomTitle, setNewRoomTitle] = useState('');
  const [threadRootId, setThreadRootId] = useState<string | null>(null);
  const [threadDraft, setThreadDraft] = useState('');
  const [threadReplies, setThreadReplies] = useState<Record<string, Array<{ id: string; author: string; text: string; time: string }>>>({});
  const [search, setSearch] = useState('');
  const [mobileConversation, setMobileConversation] = useState(false);
  const [reactionState, setReactionState] = useState(createInitialDemoReactionState);
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

  const markRoomRead = (id: string) => {
    setRooms((prev) => prev.map((r) => (r.id === id ? { ...r, unread: 0 } : r)));
    markDemoRoomRead(id);
  };

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
    if (id !== roomId) { setThreadRootId(null); setThreadDraft(''); }
    setRoomId(id);
    setMobileConversation(true);
    markRoomRead(id);
  };

  useEffect(() => {
    if (!room || room.unread === 0) return;
    const narrowLayout = typeof window !== 'undefined'
      && typeof window.matchMedia === 'function'
      && window.matchMedia('(max-width: 767px)').matches;
    // На мобильном начальная комната выбрана для десктопной панели, но разговор ещё не открыт.
    if (narrowLayout && !mobileConversation) return;
    markRoomRead(room.id);
    // markRoomRead намеренно не является зависимостью: эффект должен срабатывать
    // только когда непрочитанная комната фактически показана пользователю.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [room, mobileConversation]);

  const send = () => {
    const text = draft.trim();
    if (!text) return;
    const message = {
      id: `c${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      roomId,
      author: user?.displayName ?? t('Вы'),
      text,
      time: formatTime(locale, new Date()),
      mine: true as const,
    };
    setMessages((prev) => (
      prev.some((candidate) => candidate.id === message.id) ? prev : [...prev, message]
    ));
    addDemoSentChatMessage(message);
    setDraft('');
    requestAnimationFrame(() => inputRef.current?.focus());
  };

  const toggleReaction = (id: string, emoji: string) => {
    // Один чистый updater одного состояния: повторный вызов updater-а React-ом
    // в StrictMode не может второй раз изменить счётчик побочным эффектом.
    setReactionState((prev) => toggleDemoReaction(prev, id, emoji));
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
    <div className="flex h-full min-h-0 min-w-0 flex-1 bg-surface">
      <SidebarSlot>
        <ChatSidebar
          channels={rooms.filter((item) => item.online === undefined).map((item) => ({ id: item.id, title: cleanTitle(item.title), unread: item.unread }))}
          direct={rooms.filter((item) => item.online !== undefined).map((item) => ({ id: item.id, title: cleanTitle(item.title), unread: item.unread, online: item.online }))}
          selectedId={roomId}
          onSelect={selectRoom}
          onNew={() => setNewRoomOpen(true)}
        />
      </SidebarSlot>
      <div className="relative flex h-full min-h-0 min-w-0 flex-1 overflow-hidden bg-surface">
        {/* Список разговоров */}
        <aside className={`${mobileConversation ? 'hidden md:flex' : 'flex'} w-full shrink-0 flex-col border-r border-line bg-surface md:w-[356px]`}>
          <header className="shrink-0 px-4 pb-3 pt-5">
            <div className="flex items-center">
              <h1 className="flex-1 text-[19px] font-semibold tracking-[-0.025em]">{t('Сообщения')}</h1>
              <IconButton icon={PenSquare} label={t('Новый разговор')} onClick={() => setNewRoomOpen(true)} />
            </div>
            <div className="relative mt-3">
              <Search size={16} className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-faint" />
              <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder={t('Поиск людей и каналов')} aria-label={t('Поиск людей и каналов')} className="search-field bg-black/[0.04]" />
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
          <header className="z-10 flex h-14 shrink-0 items-center gap-2 border-b border-line bg-surface px-4">
            <button type="button" onClick={() => setMobileConversation(false)} className="icon-btn md:hidden" aria-label={t('К списку чатов')}><ArrowLeft size={19} /></button>
            <RoomMark room={room} size={34} />
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
            <WorkspaceUtilityBar className="hidden md:flex" />
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
                const visibleReactions = REACTION_EMOJI_OPTIONS
                  .map((option) => ({ ...option, count: reactionState.counts[m.id]?.[option.emoji] ?? 0 }))
                  .filter((reaction) => reaction.count > 0);
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
                      <div className="message-markdown mt-0.5 text-[14.5px] leading-relaxed text-ink-soft [&_p]:m-0" dangerouslySetInnerHTML={renderChatMarkdown(m.text)} />
                      {visibleReactions.length > 0 && (
                        <div className="mt-1.5 flex flex-wrap gap-1.5">
                          {visibleReactions.map((reaction) => {
                            const reacted = reactionState.reactedKeys.has(demoReactionKey(m.id, reaction.emoji));
                            const reactionName = locale === 'en' ? reaction.en : reaction.ru;
                            return (
                              <button
                                key={reaction.emoji}
                                type="button"
                                onClick={() => toggleReaction(m.id, reaction.emoji)}
                                className={`inline-flex items-center gap-1.5 rounded-md border px-2 py-1 text-[11px] font-medium transition ${reacted ? 'border-accent/25 bg-accent-soft text-accent-dark' : 'border-line bg-surface text-muted hover:border-accent/25'}`}
                                title={reactionName}
                                aria-label={`${t('Реакция')}: ${reactionName}: ${reaction.count}`}
                              >
                                <span className="emoji-text text-[13px] leading-none">{reaction.emoji}</span> {reaction.count}
                              </button>
                            );
                          })}
                        </div>
                      )}
                    </div>
                    <div className="absolute -top-3 right-3 flex items-center rounded-lg border border-line bg-surface p-0.5 opacity-100 shadow-soft md:opacity-0 md:group-hover:opacity-100 md:group-focus-within:opacity-100">
                      <ReactionPickerButton onPick={(emoji) => toggleReaction(m.id, emoji)} />
                      <button type="button" onClick={() => replyTo(m)} className="icon-btn-sm" title={t('Ответить')} aria-label={t('Ответить')}><Reply size={15} /></button>
                      <button type="button" onClick={() => { setThreadRootId(m.id); setThreadDraft(''); }} className="icon-btn-sm" title={t('Ответить в ветке')} aria-label={t('Ответить в ветке')}><MessageSquare size={15} /></button>
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
                <div className="rounded-xl border border-line bg-surface transition focus-within:border-accent/40 focus-within:ring-4 focus-within:ring-accent/10">
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
                    aria-label={t('Сообщение для {name}…', { name: cleanTitle(room.title) })}
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
                      className="ml-auto flex h-9 w-9 items-center justify-center rounded-lg bg-accent text-white transition hover:bg-accent-dark disabled:cursor-not-allowed disabled:bg-black/[0.08] disabled:text-faint"
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
        {threadRootId && roomMsgs.find((message) => message.id === threadRootId) && (() => {
          const rootMessage = roomMsgs.find((message) => message.id === threadRootId)!;
          const author = rootMessage.mine ? user?.displayName ?? t('Вы') : rootMessage.author;
          return <ChatThreadPanel
            root={<div className="flex gap-2.5"><Avatar name={author} size={30} /><div className="min-w-0"><div className="text-sm font-semibold text-ink">{author}</div><div className="message-markdown mt-1 text-sm text-ink-soft" dangerouslySetInnerHTML={renderChatMarkdown(rootMessage.text)} /></div></div>}
            draft={threadDraft}
            onDraftChange={setThreadDraft}
            onClose={() => { setThreadRootId(null); setThreadDraft(''); }}
            canWrite={!readOnly}
            onSend={() => {
              const text = threadDraft.trim();
              if (!text || !threadRootId) return;
              const reply = { id: `reply-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`, author: user?.displayName ?? t('Вы'), text, time: formatTime(locale, new Date()) };
              setThreadReplies((previous) => ({ ...previous, [threadRootId]: [...(previous[threadRootId] ?? []), reply] }));
              setThreadDraft('');
            }}
          >
            <p className="text-xs text-muted">{t('Ответы в демо-ветке доступны, пока открыт экран чата.')}</p>
            {(threadReplies[threadRootId] ?? []).length === 0 && <p className="text-xs text-muted">{t('Ответов пока нет')}</p>}
            {(threadReplies[threadRootId] ?? []).map((reply) => <article key={reply.id} className="flex gap-2.5"><Avatar name={reply.author} size={30} /><div className="min-w-0"><div className="flex gap-2 text-sm font-semibold text-ink">{reply.author}<time className="text-[11px] font-normal text-faint">{reply.time}</time></div><div className="message-markdown mt-1 text-sm text-ink-soft" dangerouslySetInnerHTML={renderChatMarkdown(reply.text)} /></div></article>)}
          </ChatThreadPanel>;
        })()}
      </div>
      {newRoomOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 p-4" onClick={() => setNewRoomOpen(false)}>
          <form role="dialog" aria-modal="true" aria-label={t('Новый разговор')} className="w-full max-w-md rounded-xl border border-line bg-surface p-5 shadow-pop" onClick={(event) => event.stopPropagation()} onSubmit={(event) => {
            event.preventDefault();
            const title = newRoomTitle.trim();
            if (!title) return;
            const id = `demo-room-${Date.now()}`;
            setRooms((previous) => [...previous, { id, title, kind: t('Групповой чат'), unread: 0 }]);
            setRoomId(id);
            setMobileConversation(true);
            setNewRoomOpen(false);
            setNewRoomTitle('');
          }}>
            <h2 className="mb-4 text-base font-semibold">{t('Новый разговор')}</h2>
            <label className="field-label" htmlFor="demo-room-title">{t('Название')}</label>
            <input id="demo-room-title" className="field" value={newRoomTitle} onChange={(event) => setNewRoomTitle(event.target.value)} required autoFocus />
            <p className="mt-2 text-xs text-muted">{t('Демо-разговор доступен, пока открыт экран чата.')}</p>
            <div className="mt-4 flex justify-end gap-2"><button type="button" className="btn-secondary" onClick={() => setNewRoomOpen(false)}>{t('Отмена')}</button><button type="submit" className="btn-primary">{t('Создать')}</button></div>
          </form>
        </div>
      )}
    </div>
  );
}

export default function ChatPage() {
  const demoMode = useSession((state) => state.demoMode);
  return demoMode ? <DemoChatPage /> : <RealChatPage />;
}
