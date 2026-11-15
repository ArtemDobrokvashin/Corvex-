import { ButtonHTMLAttributes, ReactNode } from 'react';
import { AlertCircle, CheckCircle2, Info, LucideIcon } from 'lucide-react';
import { useT } from '../i18n';
import { firstGraphemes } from '../lib/text';

const AVATAR_COLORS: Array<[string, string]> = [
  ['#EFF6FF', '#1D4ED8'],
  ['#DCFCE7', '#15803D'],
  ['#FEF3C7', '#92400E'],
  ['#FEE2E2', '#B91C1C'],
  ['#E0F2FE', '#0369A1'],
  ['#F3E8FF', '#6B21A8'],
  ['#FFEDD5', '#9A3412'],
  ['#CCFBF1', '#0F766E'],
];

export function cleanPersonName(value: string) {
  return value.replace(/\s*<[^>]*>\s*/g, '').trim() || value.trim();
}

export function emailFromHeader(value: string) {
  const match = value.match(/<([^>]+)>/);
  if (match) return match[1].trim();
  const address = value.trim();
  return /^[^\s<>@]+@[^\s<>@]+$/.test(address) ? address : '';
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
  darkSurface = false,
}: {
  name: string;
  size?: number;
  className?: string;
  presence?: boolean | 'online' | 'away' | 'offline';
  darkSurface?: boolean;
}) {
  const clean = cleanPersonName(name);
  let hash = 0;
  for (let i = 0; i < clean.length; i += 1) hash = (hash * 31 + clean.charCodeAt(i)) >>> 0;
  const [background, color] = AVATAR_COLORS[hash % AVATAR_COLORS.length];
  const presenceDotClass =
    presence === true || presence === 'online'
      ? 'bg-[#22C55E]'
      : presence === 'away'
        ? 'bg-[#F59E0B]'
        : 'bg-[#64748B]';

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
        <span
          className={`absolute -bottom-0.5 -right-0.5 h-3 w-3 rounded-full border-2 ${darkSurface ? 'border-sidebar' : 'border-white'} ${presenceDotClass}`}
        />
      )}
    </span>
  );
}

export function LogoMark({
  size = 38,
  inverted = false,
  className = '',
}: {
  size?: number;
  inverted?: boolean;
  className?: string;
}) {
  return (
    <span
      className={`inline-flex shrink-0 items-center justify-center overflow-hidden rounded-[9px] border border-white/[0.14] ${className}`}
      style={{ width: size, height: size }}
    >
      <svg width={size} height={size} viewBox="0 0 48 48" role="img" aria-label="Corvex" className="block shrink-0">
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
    </span>
  );
}

export function Logo({
  compact = false,
  subtitle,
  dark = false,
  markSize = 36,
}: {
  compact?: boolean;
  subtitle?: string;
  dark?: boolean;
  markSize?: number;
}) {
  if (compact) return <LogoMark size={markSize} />;
  return (
    <span className="inline-flex items-center gap-2.5">
      <LogoMark size={markSize} />
      <span className="min-w-0">
        <span className={`block text-[18px] font-semibold leading-tight tracking-[-0.02em] ${dark ? 'text-white' : 'text-ink'}`}>
          Corvex
        </span>
        {subtitle && (
          <span className={`block truncate text-xs leading-tight ${dark ? 'text-sidebar-muted' : 'text-muted'}`}>
            {subtitle}
          </span>
        )}
      </span>
    </span>
  );
}

export type StatusBadgeTone = 'positive' | 'warning' | 'neutral' | 'critical' | 'info';

const STATUS_BADGE_TONES: Record<StatusBadgeTone, { badge: string; dot: string }> = {
  positive: { badge: 'status-badge-positive', dot: 'bg-[#15803D]' },
  warning: { badge: 'status-badge-warning', dot: 'bg-[#92400E]' },
  neutral: { badge: 'status-badge-neutral', dot: 'bg-[#475569]' },
  critical: { badge: 'status-badge-critical', dot: 'bg-[#B91C1C]' },
  info: { badge: 'status-badge-info', dot: 'bg-[#1D4ED8]' },
};

export function StatusBadge({
  tone = 'neutral',
  dot = false,
  children,
  className = '',
}: {
  tone?: StatusBadgeTone;
  dot?: boolean;
  children: ReactNode;
  className?: string;
}) {
  const styles = STATUS_BADGE_TONES[tone];
  return (
    <span className={`status-badge ${styles.badge} ${className}`}>
      {dot && <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${styles.dot}`} />}
      {children}
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
  const toneClass =
    tone === 'error'
      ? 'notice-error'
      : tone === 'success'
        ? 'notice-success'
        : tone === 'warning'
          ? 'notice-warning'
          : 'border-line-control bg-canvas text-muted';
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
      <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-xl border border-line-control bg-canvas text-faint">
        <Icon size={22} strokeWidth={1.7} />
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

export { Clock } from './Clock';

export function LoadingScreen({ label }: { label?: string }) {
  const t = useT();
  const effectiveLabel = label ?? t('Загружаем рабочее пространство');
  return (
    <div className="flex h-full flex-col items-center justify-center bg-surface px-6 text-center">
      <LogoMark size={44} />
      <div className="mt-5 h-1.5 w-36 overflow-hidden rounded-full bg-canvas border border-line-control">
        <div className="h-full w-1/2 animate-pulse rounded-full bg-accent" />
      </div>
      <p className="mt-4 text-sm text-muted">{effectiveLabel}…</p>
    </div>
  );
}
