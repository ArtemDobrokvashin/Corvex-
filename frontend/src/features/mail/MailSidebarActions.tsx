import { PenSquare, Settings2 } from 'lucide-react';
import { useI18n } from '../../i18n';

export default function MailSidebarActions({
  canAdminExternalAccounts,
  externalAccountCount,
  composeOpen = false,
  onCompose,
  onOpenExternal,
}: {
  canAdminExternalAccounts: boolean;
  externalAccountCount: number;
  composeOpen?: boolean;
  onCompose: () => void;
  onOpenExternal: () => void;
}) {
  const { t } = useI18n();

  return (
    <>
      <button
        type="button"
        onClick={onCompose}
        disabled={composeOpen}
        className="flex h-10 w-full shrink-0 items-center gap-2 rounded-lg bg-accent px-3 text-[13.5px] font-medium text-white transition hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50"
      >
        <PenSquare size={16} className="shrink-0" />
        <span className="min-w-0 flex-1 truncate text-center">{t('Написать')}</span>
        <span className="flex w-6 shrink-0 justify-end">
          <span className="kbd border-white/20 bg-white/10 text-white/75 shadow-none">C</span>
        </span>
      </button>
      {canAdminExternalAccounts && (
        <button
          type="button"
          onClick={onOpenExternal}
          className="mt-2 flex h-10 w-full shrink-0 items-center gap-2 rounded-lg border border-[#1E2D4A] bg-[#111B30] px-3 text-[13px] font-medium text-sidebar-text transition hover:bg-sidebar-hover hover:text-white"
        >
          <Settings2 size={16} className="shrink-0 text-sidebar-muted" />
          <span className="min-w-0 flex-1 truncate text-center">{t('Внешние ящики')}</span>
          <span className="flex w-6 shrink-0 justify-end text-xs font-semibold text-sidebar-text tabular-nums">
            {externalAccountCount > 0 ? externalAccountCount : null}
          </span>
        </button>
      )}
    </>
  );
}
