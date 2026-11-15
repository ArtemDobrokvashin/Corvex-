import { Hash, Plus } from 'lucide-react';
import { useI18n } from '../i18n';
import { Avatar } from './ui';
import { useSidebarSlotContext } from './SidebarSlot';

export interface ChatSidebarItem {
  id: string;
  title: string;
  unread: number;
  online?: boolean;
}

/** Shortcuts in the shared sidebar; the workspace retains its separate room list. */
export default function ChatSidebar({
  channels,
  direct,
  selectedId,
  onSelect,
  onNew,
}: {
  channels: ChatSidebarItem[];
  direct: ChatSidebarItem[];
  selectedId: string;
  onSelect: (id: string) => void;
  onNew?: () => void;
}) {
  const { t } = useI18n();
  const { onCloseNavigation } = useSidebarSlotContext();
  const section = (label: string, items: ChatSidebarItem[], isDirect: boolean) => (
    <section>
      <div className="mb-1 flex items-center justify-between px-1.5">
        <h2 className="sidebar-section-label">{label}</h2>
        {onNew && (
          <button
            type="button"
            onClick={() => { onCloseNavigation?.(); onNew(); }}
            aria-label={t('Новый разговор')}
            title={t('Новый разговор')}
            className="rounded-md p-1 text-sidebar-muted hover:bg-sidebar-hover hover:text-white"
          ><Plus size={14} /></button>
        )}
      </div>
      <div className="space-y-0.5">
        {items.map((item) => (
          <button
            key={item.id}
            type="button"
            onClick={() => { onSelect(item.id); onCloseNavigation?.(); }}
            className={`sidebar-subitem w-full ${selectedId === item.id ? 'sidebar-subitem-active' : ''}`}
            aria-current={selectedId === item.id ? 'page' : undefined}
            title={item.title}
          >
            {isDirect
              ? <Avatar name={item.title} size={22} presence={item.online ?? false} darkSurface />
              : <Hash size={15} className="shrink-0 text-sidebar-muted" />}
            <span className="min-w-0 flex-1 truncate text-left">{item.title}</span>
            {item.unread > 0 && <span className="rounded-full bg-accent px-1.5 py-0.5 text-[10px] font-semibold leading-none text-white tabular-nums">{item.unread}</span>}
          </button>
        ))}
      </div>
    </section>
  );
  return <div className="space-y-5 px-3 py-4">{section(t('Каналы и группы'), channels, false)}{section(t('Личные сообщения'), direct, true)}</div>;
}
