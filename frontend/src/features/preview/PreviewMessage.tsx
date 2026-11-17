import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';

export default function PreviewMessage({ icon: Icon, title, children }: {
  icon: LucideIcon;
  title: string;
  children?: ReactNode;
}) {
  return (
    <div className="flex h-full flex-col items-center justify-center px-6 py-10 text-center">
      <span className="flex h-12 w-12 items-center justify-center rounded-xl border border-line bg-surface text-faint">
        <Icon size={22} strokeWidth={1.7} aria-hidden="true" />
      </span>
      <p className="mt-3 max-w-md text-[15px] font-medium text-ink">{title}</p>
      {children && <div className="mt-1 max-w-md text-[13px] leading-relaxed text-muted">{children}</div>}
    </div>
  );
}
