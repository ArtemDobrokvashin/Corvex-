import { useEffect, useMemo, useState } from 'react';
import {
  ArrowRight,
  Calendar as CalendarIcon,
  Command,
  LogOut,
  Mail,
  MessageSquare,
  PanelRightOpen,
  PenSquare,
  Plus,
  Search,
  Shield,
  ShieldCheck,
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { mailApi } from '../api/mailApi';
import { contactsApi } from '../api/contactsApi';
import { useI18n } from '../i18n';
import { useCalendarStore } from '../store/calendarStore';

interface CommandAction {
  id: string;
  label: string;
  hint?: string;
  icon: typeof Mail;
  onSelect: () => void;
}

export default function CommandPalette({
  open,
  onClose,
  canSeeAdmin,
  onToggleCalendarWidget,
  onOpenSecurity,
  onLogout,
  demoMode,
}: {
  open: boolean;
  onClose: () => void;
  canSeeAdmin: boolean;
  onToggleCalendarWidget: () => void;
  onOpenSecurity: () => void;
  onLogout: () => void;
  demoMode: boolean;
}) {
  const { t } = useI18n();
  const navigate = useNavigate();
  const events = useCalendarStore((s) => s.events);
  const setSelectedDate = useCalendarStore((s) => s.setSelectedDate);
  const [query, setQuery] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);
  // Результаты поиска по письмам и адресной книге. Поиск по письмам идёт на
  // сервер: в загруженном списке письма из прошлого года просто нет.
  const [mailHits, setMailHits] = useState<CommandAction[]>([]);
  const [contactHits, setContactHits] = useState<CommandAction[]>([]);
  const [mailSearchNote, setMailSearchNote] = useState<string | null>(null);

  useEffect(() => {
    if (open) {
      setQuery('');
      setActiveIndex(0);
    }
  }, [open]);

  const actions = useMemo<CommandAction[]>(() => {
    const base: CommandAction[] = [
      {
        id: 'go-mail',
        label: t('Открыть почту'),
        hint: '/mail',
        icon: Mail,
        onSelect: () => navigate('/mail'),
      },
      {
        id: 'compose-mail',
        label: t('Написать письмо'),
        hint: 'C',
        icon: PenSquare,
        onSelect: () => navigate('/mail?compose=1'),
      },
      {
        id: 'go-chat',
        label: t('Открыть сообщения'),
        hint: '/chat',
        icon: MessageSquare,
        onSelect: () => navigate('/chat'),
      },
      {
        id: 'toggle-cal-widget',
        label: t('Открыть виджет календаря «Мой день»'),
        hint: t('Боковая панель'),
        icon: PanelRightOpen,
        onSelect: () => onToggleCalendarWidget(),
      },
      {
        id: 'go-calendar',
        label: t('Открыть полный Календарь'),
        hint: '/calendar',
        icon: CalendarIcon,
        onSelect: () => navigate('/calendar'),
      },
      {
        id: 'new-event',
        label: t('Создать новое событие в календаре'),
        hint: '+ Event',
        icon: Plus,
        onSelect: () => navigate('/calendar?createEvent=1'),
      },
      {
        id: 'security',
        label: t('Безопасность и 2FA'),
        icon: ShieldCheck,
        onSelect: () => onOpenSecurity(),
      },
    ];

    if (canSeeAdmin) {
      base.push({
        id: 'go-admin',
        label: t('Администрирование'),
        hint: '/admin',
        icon: Shield,
        onSelect: () => navigate('/admin'),
      });
    }

    base.push({
      id: 'logout',
      label: demoMode ? t('Выйти из демо') : t('Выйти'),
      icon: LogOut,
      onSelect: () => onLogout(),
    });

    for (const ev of events.slice(0, 12)) {
      base.push({
        id: `ev-${ev.id}`,
        label: ev.title,
        hint: `${ev.date} · ${ev.allDay ? t('Весь день') : ev.startTime}`,
        icon: CalendarIcon,
        onSelect: () => {
          setSelectedDate(ev.date);
          navigate(`/calendar?date=${ev.date}`);
        },
      });
    }

    const q = query.trim().toLowerCase();
    if (!q) return base.slice(0, 9);
    const commands = base.filter(
      (item) => item.label.toLowerCase().includes(q) || (item.hint && item.hint.toLowerCase().includes(q)),
    );
    // Команды остаются первыми: человек, набравший «почта», ждёт переход,
    // а не пять писем с этим словом.
    return [...commands, ...mailHits, ...contactHits];
  }, [
    canSeeAdmin,
    demoMode,
    events,
    navigate,
    onLogout,
    onOpenSecurity,
    onToggleCalendarWidget,
    query,
    setSelectedDate,
    t,
    mailHits,
    contactHits,
  ]);

  // Почта и контакты ищутся той же строкой, что и команды: `⌘K` в Gmail ведёт
  // себя так же. Запросы разнесены по времени, чтобы не дёргать сервер на
  // каждый символ.
  useEffect(() => {
    if (!open) return undefined;
    const q = query.trim();
    if (q.length < 2 || demoMode) {
      setMailHits([]);
      setContactHits([]);
      setMailSearchNote(null);
      return undefined;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      mailApi.search(q, 5, controller.signal)
        .then((hits) => {
          if (controller.signal.aborted) return;
          setMailHits(hits.map((hit) => ({
            id: `mail-${hit.messageId}`,
            label: hit.subject || t('(без темы)'),
            hint: hit.sender,
            icon: Mail,
            onSelect: () => navigate(`/mail?mailId=${encodeURIComponent(hit.messageId)}`),
          })));
          setMailSearchNote(null);
        })
        .catch((error: unknown) => {
          if (controller.signal.aborted) return;
          setMailHits([]);
          // 503 — индекс недоступен; пустой список без пояснения читался бы
          // как «писем нет».
          setMailSearchNote(
            error && typeof error === 'object' && 'status' in error && (error as { status?: number }).status === 503
              ? t('Поиск по письмам временно недоступен.')
              : t('Не удалось выполнить поиск по письмам.'),
          );
        });
      contactsApi.list(q, 5)
        .then((items) => {
          if (controller.signal.aborted) return;
          setContactHits(items.map((item) => ({
            id: `contact-${item.id}`,
            label: item.displayName || item.email,
            hint: item.email,
            icon: Mail,
            onSelect: () => navigate(`/mail?compose=1&to=${encodeURIComponent(item.email)}`),
          })));
        })
        .catch(() => { if (!controller.signal.aborted) setContactHits([]); });
    }, 300);
    return () => { controller.abort(); window.clearTimeout(timer); };
  }, [demoMode, navigate, open, query, t]);

  useEffect(() => {
    if (!open) return undefined;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onClose();
      } else if (e.key === 'ArrowDown') {
        e.preventDefault();
        setActiveIndex((idx) => (actions.length > 0 ? (idx + 1) % actions.length : 0));
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setActiveIndex((idx) => (actions.length > 0 ? (idx - 1 + actions.length) % actions.length : 0));
      } else if (e.key === 'Enter' && actions[activeIndex]) {
        e.preventDefault();
        const chosen = actions[activeIndex];
        onClose();
        chosen.onSelect();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [actions, activeIndex, onClose, open]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center bg-ink/40 px-4 pt-[13vh] backdrop-blur-[1px]"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={t('Быстрый переход')}
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-[540px] animate-scale-in overflow-hidden rounded-xl border border-line-control bg-surface shadow-pop"
      >
        <div className="flex items-center gap-2.5 border-b border-line px-4 py-3">
          <Search size={17} className="shrink-0 text-faint" />
          <input
            autoFocus
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setActiveIndex(0);
            }}
            placeholder={t('Поиск разделов, команд и событий календаря…')}
            aria-label={t('Быстрый переход')}
            className="min-w-0 flex-1 bg-transparent text-[14px] text-ink outline-none placeholder:text-faint"
          />
          <kbd className="rounded border border-line-control bg-canvas px-1.5 py-0.5 font-mono text-[11px] text-muted">
            ESC
          </kbd>
        </div>

        {mailSearchNote && (
          <p className="border-b border-line-subtle bg-canvas px-4 py-2 text-[11.5px] text-muted" role="status">
            {mailSearchNote}
          </p>
        )}
        <div className="max-h-[340px] overflow-y-auto p-2">
          {actions.length === 0 ? (
            <div className="py-8 text-center text-xs text-muted">{t('Ничего не найдено')}</div>
          ) : (
            actions.map((item, idx) => {
              const Icon = item.icon;
              const active = idx === activeIndex;
              return (
                <button
                  key={item.id}
                  type="button"
                  onMouseEnter={() => setActiveIndex(idx)}
                  onClick={() => {
                    onClose();
                    item.onSelect();
                  }}
                  className={`flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left text-[13.5px] transition ${
                    active ? 'bg-[#EEF4FF] text-accent-dark' : 'text-ink-soft hover:bg-canvas'
                  }`}
                >
                  <Icon size={16} className={active ? 'text-accent' : 'text-faint'} />
                  <span className="min-w-0 flex-1 truncate font-medium">{item.label}</span>
                  {item.hint && (
                    <span className="shrink-0 text-xs text-faint tabular-nums">{item.hint}</span>
                  )}
                  <ArrowRight size={13} className={active ? 'text-accent' : 'text-transparent'} />
                </button>
              );
            })
          )}
        </div>

        <footer className="flex items-center justify-between border-t border-line bg-canvas px-4 py-2 text-[11px] text-muted">
          <span className="inline-flex items-center gap-1">
            <Command size={12} /> K · {t('Командная палитра Corvex')}
          </span>
          <span>↑↓ · Enter</span>
        </footer>
      </div>
    </div>
  );
}
