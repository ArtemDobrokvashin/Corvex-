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
      <button type="button" onClick={onCompose} disabled={composeOpen} className="btn-primary h-10 w-full shrink-0">
        <PenSquare size={16} className="shrink-0" />
        <span className="min-w-0 flex-1 truncate text-center">{t('Написать')}</span>
        <span className="flex w-6 shrink-0 justify-end"><span className="kbd border-white/20 bg-white/10 text-white/70 shadow-none">C</span></span>
      </button>
      {canAdminExternalAccounts && (
        <button type="button" onClick={onOpenExternal} className="btn-secondary mt-2 h-10 w-full shrink-0">
          <Settings2 size={16} className="shrink-0" />
          <span className="min-w-0 flex-1 truncate text-center">{t('Внешние ящики')}</span>
          <span className="flex w-6 shrink-0 justify-end text-xs text-faint">{externalAccountCount > 0 ? externalAccountCount : null}</span>
        </button>
      )}
    </>
  );
}
