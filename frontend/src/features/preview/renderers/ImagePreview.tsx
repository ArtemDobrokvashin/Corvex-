import { useState } from 'react';
import { Maximize2, Minus, Plus, RotateCw } from 'lucide-react';
import { useI18n } from '../../../i18n';

const MIN_ZOOM = 0.25;
const MAX_ZOOM = 4;

export default function ImagePreview({ url, fileName }: { url: string; fileName: string }) {
  const { t } = useI18n();
  const [zoom, setZoom] = useState(1);
  const [rotation, setRotation] = useState(0);

  const changeZoom = (next: number) => setZoom(Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, next)));
  const scaled = zoom !== 1;

  return (
    <div className="flex h-full flex-col">
      <div className="flex shrink-0 items-center justify-center gap-1 border-b border-line bg-chrome px-3 py-1.5">
        <button
          type="button"
          className="icon-btn-sm"
          aria-label={t('Отдалить')}
          disabled={zoom <= MIN_ZOOM}
          onClick={() => changeZoom(zoom - 0.25)}
        >
          <Minus size={15} />
        </button>
        <span aria-live="polite" className="w-12 text-center text-[12px] tabular-nums text-muted">
          {Math.round(zoom * 100)}%
        </span>
        <button
          type="button"
          className="icon-btn-sm"
          aria-label={t('Приблизить')}
          disabled={zoom >= MAX_ZOOM}
          onClick={() => changeZoom(zoom + 0.25)}
        >
          <Plus size={15} />
        </button>
        <button
          type="button"
          className="icon-btn-sm"
          aria-label={t('Повернуть')}
          onClick={() => setRotation((value) => (value + 90) % 360)}
        >
          <RotateCw size={15} />
        </button>
        <button
          type="button"
          className="icon-btn-sm"
          aria-label={t('Вписать')}
          onClick={() => {
            setZoom(1);
            setRotation(0);
          }}
        >
          <Maximize2 size={15} />
        </button>
      </div>
      <div className="min-h-0 flex-1 overflow-auto bg-canvas p-3">
        <div className={scaled ? 'flex min-h-full items-start justify-center' : 'flex min-h-full items-center justify-center'}>
          <img
            src={url}
            alt={fileName}
            className={scaled ? 'h-auto max-w-none object-contain' : 'max-h-full max-w-full object-contain'}
            style={{
              ...(scaled ? { width: `${Math.round(zoom * 100)}%` } : null),
              transform: `rotate(${rotation}deg)`,
            }}
          />
        </div>
      </div>
    </div>
  );
}
