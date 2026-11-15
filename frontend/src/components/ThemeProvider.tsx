import { useEffect } from 'react';
import { useUIStore, accentValue, accentOnDark, accentOnLight, accentButtonText } from '../store/uiStore';

/**
 * Применяет выбранную тему, акцентный цвет и плотность к документу.
 * - 'light' / 'dark' — фиксированная тема через data-theme.
 * - 'system' — следует prefers-color-scheme, с переключением при смене системной настройки.
 * Акцентный цвет записывается в CSS-переменную --corvex-accent (значение цвета, а не ключ).
 * Плотность записывается в data-density.
 */
export function ThemeProvider() {
  const theme = useUIStore((s) => s.theme);
  const accent = useUIStore((s) => s.accent);
  const density = useUIStore((s) => s.density);

  useEffect(() => {
    const html = document.documentElement;

    const apply = (resolved: 'light' | 'dark') => {
      html.setAttribute('data-theme', resolved);
      html.setAttribute('data-density', density);
      html.style.setProperty('--corvex-accent', accentValue(accent));
      html.style.setProperty('--corvex-accent-on-dark', accentOnDark(accent));
      html.style.setProperty('--corvex-accent-on-light', accentOnLight(accent));
      html.style.setProperty('--corvex-accent-button-text', accentButtonText(accent));
    };

    if (theme === 'system') {
      const media = window.matchMedia('(prefers-color-scheme: dark)');
      const resolved = media.matches ? 'dark' : 'light';
      apply(resolved);

      const handler = (event: MediaQueryListEvent) => {
        apply(event.matches ? 'dark' : 'light');
      };
      media.addEventListener('change', handler);
      return () => media.removeEventListener('change', handler);
    }

    apply(theme);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [theme, accent, density]);

  return null;
}
