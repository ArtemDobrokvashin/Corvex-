import { useI18n } from '../../../i18n';

export default function MediaPreview({ url, kind, fileName }: {
  url: string;
  kind: 'audio' | 'video';
  fileName: string;
}) {
  const { t } = useI18n();
  return (
    <div className="flex h-full items-center justify-center overflow-auto bg-canvas p-4">
      {kind === 'video' ? (
        <video src={url} controls preload="metadata" aria-label={`${t('Видео')}: ${fileName}`} className="max-h-full max-w-full rounded-lg bg-black" />
      ) : (
        <audio src={url} controls preload="metadata" aria-label={`${t('Аудио')}: ${fileName}`} className="w-full max-w-lg" />
      )}
    </div>
  );
}
