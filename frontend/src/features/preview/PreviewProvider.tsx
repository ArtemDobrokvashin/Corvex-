import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import FilePreviewDialog from './FilePreviewDialog';
import type { PreviewSource } from './types';

interface PreviewContextValue {
  /** Открыть предпросмотр одного файла или списка (стрелки между вложениями). */
  openPreview: (items: PreviewSource[], index?: number) => void;
  closePreview: () => void;
}

/**
 * Значение по умолчанию позволяет рендерить страницы без провайдера
 * (например, в изолированных тестах) — вызов просто ничего не открывает.
 */
const PreviewContext = createContext<PreviewContextValue>({
  openPreview: () => undefined,
  closePreview: () => undefined,
});

export function usePreview(): PreviewContextValue {
  return useContext(PreviewContext);
}

export default function PreviewProvider({ children }: { children: ReactNode }) {
  const [preview, setPreview] = useState<{ items: PreviewSource[]; index: number } | null>(null);

  const openPreview = useCallback((items: PreviewSource[], index = 0) => {
    const available = items.filter(Boolean);
    if (available.length === 0) return;
    const safeIndex = Math.min(Math.max(index, 0), available.length - 1);
    setPreview({ items: available, index: safeIndex });
  }, []);

  const closePreview = useCallback(() => setPreview(null), []);
  const value = useMemo(() => ({ openPreview, closePreview }), [openPreview, closePreview]);

  return (
    <PreviewContext.Provider value={value}>
      {children}
      {preview && (
        <FilePreviewDialog
          items={preview.items}
          index={preview.index}
          onIndexChange={(index) => setPreview((current) => (current ? { ...current, index } : current))}
          onClose={closePreview}
        />
      )}
    </PreviewContext.Provider>
  );
}
