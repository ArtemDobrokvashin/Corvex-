import { createContext, ReactNode, useContext } from 'react';
import { createPortal } from 'react-dom';

export const SIDEBAR_SLOT_ID = 'corvex-sidebar-slot';

export interface SidebarRenderProps {
  collapsed: boolean;
}

export interface SidebarSlotContextValue {
  inAppShell: boolean;
  collapsed: boolean;
  slotEl: HTMLElement | null;
  mobileSlotEl: HTMLElement | null;
  openPalette: () => void;
  onLogout?: () => void;
  onOpenCommandPalette?: () => void;
  onOpenCalendarWidget?: () => void;
  onOpenSecurity?: () => void;
  onCloseNavigation?: () => void;
}

const defaultOpenPalette = () => {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true }));
  }
};

export const SidebarSlotContext = createContext<SidebarSlotContextValue>({
  inAppShell: false,
  collapsed: false,
  slotEl: null,
  mobileSlotEl: null,
  openPalette: defaultOpenPalette,
});

export function useSidebarSlotContext(): SidebarSlotContextValue {
  return useContext(SidebarSlotContext);
}

export default function SidebarSlot({
  children,
  ariaLabel,
  fallbackClassName = 'w-[252px] shrink-0 border-r border-sidebar-line bg-sidebar px-3 py-3 text-sidebar-text',
}: {
  children: ReactNode | ((props: SidebarRenderProps) => ReactNode);
  ariaLabel?: string;
  fallbackClassName?: string;
}) {
  const ctx = useContext(SidebarSlotContext);
  const domSlot =
    ctx.slotEl ??
    (typeof document !== 'undefined' ? document.getElementById(SIDEBAR_SLOT_ID) : null);

  const collapsed = ctx.inAppShell
    ? ctx.collapsed
    : domSlot?.getAttribute('data-collapsed') === 'true';

  const renderContent = (isCollapsed: boolean) =>
    typeof children === 'function' ? children({ collapsed: isCollapsed }) : children;

  if (domSlot) {
    return (
      <>
        {createPortal(renderContent(collapsed), domSlot)}
        {ctx.mobileSlotEl ? createPortal(renderContent(false), ctx.mobileSlotEl) : null}
      </>
    );
  }

  if (ctx.inAppShell) {
    return null;
  }

  return (
    <aside aria-label={ariaLabel} className={fallbackClassName}>
      {renderContent(false)}
    </aside>
  );
}
