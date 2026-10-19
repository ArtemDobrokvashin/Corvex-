/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        canvas: '#F7F7F4',
        chrome: '#FBFBF9',
        surface: '#FFFFFF',
        ink: {
          DEFAULT: '#17181C',
          soft: '#2B2D33',
        },
        muted: '#6F7280',
        faint: '#A5A8B3',
        line: '#E8E7E2',
        accent: {
          DEFAULT: '#3157D5',
          dark: '#2446B6',
          soft: '#EEF2FF',
          muted: '#DCE6FF',
        },
        success: '#16835F',
        warning: '#A16207',
        danger: '#C43228',
        // Обратная совместимость со старыми классами на время миграции экранов.
        brand: { DEFAULT: '#3157D5', dark: '#2446B6' },
      },
      fontFamily: {
        sans: ['"Inter Variable"', 'Inter', '"Segoe UI"', 'system-ui', '-apple-system', 'BlinkMacSystemFont', 'sans-serif'],
        mono: ['"JetBrains Mono"', '"SFMono-Regular"', 'Consolas', '"Liberation Mono"', 'monospace'],
      },
      boxShadow: {
        soft: '0 1px 2px rgba(23, 24, 28, 0.05), 0 1px 3px rgba(23, 24, 28, 0.04)',
        pop: '0 18px 50px -12px rgba(23, 24, 28, 0.28), 0 4px 16px rgba(23, 24, 28, 0.10)',
        composer: '0 16px 44px -16px rgba(23, 24, 28, 0.32), 0 3px 12px rgba(23, 24, 28, 0.10)',
      },
      animation: {
        'fade-in': 'fade-in 140ms ease-out both',
        'rise-in': 'rise-in 180ms cubic-bezier(.21,1.02,.73,1) both',
        'scale-in': 'scale-in 140ms ease-out both',
      },
      keyframes: {
        'fade-in': { from: { opacity: '0' }, to: { opacity: '1' } },
        'rise-in': { from: { opacity: '0', transform: 'translateY(6px)' }, to: { opacity: '1', transform: 'translateY(0)' } },
        'scale-in': { from: { opacity: '0', transform: 'scale(.985) translateY(4px)' }, to: { opacity: '1', transform: 'scale(1) translateY(0)' } },
      },
    },
  },
  plugins: [],
};
