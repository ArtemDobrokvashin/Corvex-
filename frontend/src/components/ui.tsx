import { ButtonHTMLAttributes, ReactNode } from 'react';
import { AlertCircle, CheckCircle2, Info, LucideIcon } from 'lucide-react';
import { useT } from '../i18n';
import { firstGraphemes } from '../lib/text';

const AVATAR_COLORS: Array<[string, string]> = [
  ['#E4E9FF', '#2F47B8'],
  ['#DDF3E8', '#17634A'],
  ['#FFF0D6', '#8A5A00'],
  ['#FFE5E9', '#9B2340'],
  ['#E1F2FE', '#0C5B83'],
  ['#F0E7FF', '#63319B'],
  ['#FFEAD9', '#8D4A12'],
  ['#E5F4F1', '#166E66'],
];

export function cleanPersonName(value: string) {
  return value.replace(/\s*<[^>]*>\s*/g, '').trim() || value.trim();
}

export function emailFromHeader(value: string) {
  const match = value.match(/<([^>]+)>/);
  return match?.[1] ?? '';
}

function initials(name: string) {
  const words = cleanPersonName(name).split(/\s+/).filter(Boolean);
  if (words.length === 0) return '?';
  if (words.length === 1) return firstGraphemes(words[0], 2).toUpperCase();
  return `${firstGraphemes(words[0], 1)}${firstGraphemes(words[1], 1)}`.toUpperCase();
}

export function Avatar({
  name,
  size = 36,
  className = '',
  presence,
}: {
  name: string;
  size?: number;
  className?: string;
  presence?: boolean;
}) {
  const clean = cleanPersonName(name);
  let hash = 0;
  for (let i = 0; i < clean.length; i += 1) hash = (hash * 31 + clean.charCodeAt(i)) >>> 0;
  const [background, color] = AVATAR_COLORS[hash % AVATAR_COLORS.length];
  return (
    <span className={`relative inline-flex shrink-0 select-none ${className}`} style={{ width: size, height: size }}>
      <span
        aria-hidden="true"
        className="flex h-full w-full items-center justify-center rounded-full font-semibold"
        style={{ background, color, fontSize: Math.max(10, Math.round(size * 0.34)) }}
      >
        {initials(clean)}
      </span>
      {presence !== undefined && (
        <span className={`absolute -bottom-0.5 -right-0.5 h-3 w-3 rounded-full border-2 border-white ${presence ? 'bg-emerald-500' : 'bg-zinc-300'}`} />
      )}
    </span>
  );
}

export function LogoMark({ size = 38, inverted = false }: { size?: number; inverted?: boolean }) {
  return (
    <svg width={size} height={size} viewBox="0 0 48 48" role="img" aria-label="Corvex" className="shrink-0">
      <rect width="48" height="48" rx="13" fill={inverted ? '#FFFFFF' : '#17181C'} />
      <path
        d="M31.8 17.1c-1.7-2-4-3-6.9-3-5.4 0-9.4 4.2-9.4 9.9s4 9.9 9.4 9.9c2.9 0 5.3-1.1 7-3.1"
        fill="none"
        stroke={inverted ? '#17181C' : '#FFFFFF'}
        strokeWidth="4"
        strokeLinecap="round"
      />
      <circle cx="33.4" cy="24" r="3.4" fill="#5B7CFF" />
    </svg>
  );
}

export function Logo({ compact = false, subtitle }: { compact?: boolean; subtitle?: string }) {
  if (compact) return <LogoMark />;
  return (
    <span className="inline-flex items-center gap-3">
      <LogoMark size={40} />
      <span className="min-w-0">
        <span className="block text-[19px] font-semibold leading-tight tracking-[-0.02em] text-ink">Corvex</span>
        {subtitle && <span className="block truncate text-xs leading-tight text-muted">{subtitle}</span>}
      </span>
    </span>
  );
}

export function Notice({
  tone = 'info',
  children,
  className = '',
}: {
  tone?: 'info' | 'error' | 'success' | 'warning';
  children: ReactNode;
  className?: string;
}) {
  const Icon = tone === 'error' ? AlertCircle : tone === 'success' ? CheckCircle2 : Info;
  const toneClass = tone === 'error' ? 'notice-error' : tone === 'success' ? 'notice-success' : tone === 'warning' ? 'notice-warning' : 'border-line bg-chrome text-muted';
  return (
    <div role={tone === 'error' ? 'alert' : 'status'} className={`notice ${toneClass} ${className}`}>
      <Icon size={17} strokeWidth={1.9} className="mt-0.5 shrink-0" />
      <div className="min-w-0 flex-1">{children}</div>
    </div>
  );
}

export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
}: {
  icon: LucideIcon;
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex h-full flex-col items-center justify-center px-6 py-16 text-center">
      <div className="mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-black/[0.04] text-faint">
        <Icon size={25} strokeWidth={1.6} />
      </div>
      <h3 className="text-[15px] font-semibold text-ink-soft">{title}</h3>
      {description && <p className="mt-1.5 max-w-sm text-sm leading-relaxed text-muted">{description}</p>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}

export function IconButton({
  icon: Icon,
  label,
  className = '',
  size = 18,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { icon: LucideIcon; label: string; size?: number }) {
  return (
    <button type="button" title={label} aria-label={label} className={`icon-btn ${className}`} {...props}>
      <Icon size={size} strokeWidth={1.8} />
    </button>
  );
}

export function LoadingScreen({ label }: { label?: string }) {
  const t = useT();
  const effectiveLabel = label ?? t('Загружаем рабочее пространство');
  return (
    <div className="flex h-full flex-col items-center justify-center bg-canvas px-6 text-center">
      <LogoMark size={48} />
      <div className="mt-5 h-1 w-36 overflow-hidden rounded-full bg-black/[0.07]">
        <div className="h-full w-1/2 animate-pulse rounded-full bg-ink" />
      </div>
      <p className="mt-4 text-sm text-muted">{effectiveLabel}…</p>
    </div>
  );
}
