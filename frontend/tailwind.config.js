/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        canvas: '#F8FAFC',
        chrome: '#FFFFFF',
        surface: '#FFFFFF',
        sidebar: {
          DEFAULT: '#0B1221',
          hover: '#131E35',
          active: '#16233D',
          sub: '#142240',
          line: '#1A253C',
          text: '#CBD5E1',
          muted: '#94A3B8',
        },
        ink: {
          DEFAULT: '#0F172A',
          soft: '#1E293B',
        },
        muted: '#475569',
        faint: '#64748B',
        line: {
          DEFAULT: '#E5E7EB',
          subtle: '#F1F5F9',
          control: '#E2E8F0',
        },
        accent: {
          DEFAULT: '#1D63ED',
          dark: '#154FD1',
          soft: '#EEF4FF',
          muted: '#DBEAFE',
        },
        success: '#15803D',
        warning: '#92400E',
        danger: '#B91C1C',
        badge: {
          'positive-bg': '#DCFCE7',
          'positive-text': '#15803D',
          'warning-bg': '#FEF3C7',
          'warning-text': '#92400E',
          'neutral-bg': '#F1F5F9',
          'neutral-text': '#334155',
          'critical-bg': '#FEE2E2',
          'critical-text': '#B91C1C',
          'info-bg': '#EFF6FF',
          'info-text': '#1D4ED8',
        },
        brand: {
          DEFAULT: '#1D63ED',
          dark: '#154FD1',
        },
      },
      fontFamily: {
        sans: ['"Inter Variable"', 'Inter', '"Segoe UI"', 'system-ui', '-apple-system', 'BlinkMacSystemFont', '"Apple Color Emoji"', '"Segoe UI Emoji"', '"Noto Color Emoji"', 'sans-serif'],
        mono: ['"JetBrains Mono"', '"SFMono-Regular"', 'Consolas', '"Liberation Mono"', 'monospace'],
      },
      boxShadow: {
        soft: '0 1px 2px rgba(15, 23, 42, 0.04)',
        pop: '0 16px 40px -10px rgba(15, 23, 42, 0.20), 0 4px 12px rgba(15, 23, 42, 0.08)',
        composer: '0 16px 44px -12px rgba(15, 23, 42, 0.24), 0 4px 12px rgba(15, 23, 42, 0.08)',
      },
      animation: {
        'fade-in': 'fade-in 140ms ease-out both',
        'rise-in': 'rise-in 180ms cubic-bezier(.21,1.02,.73,1) both',
        'scale-in': 'scale-in 140ms ease-out both',
        'slide-up': 'slide-up 180ms ease-out both',
      },
      keyframes: {
        'fade-in': { from: { opacity: '0' }, to: { opacity: '1' } },
        'rise-in': { from: { opacity: '0', transform: 'translateY(6px)' }, to: { opacity: '1', transform: 'translateY(0)' } },
        'scale-in': { from: { opacity: '0', transform: 'scale(.985) translateY(4px)' }, to: { opacity: '1', transform: 'scale(1) translateY(0)' } },
        'slide-up': { from: { opacity: '0', transform: 'translateY(12px)' }, to: { opacity: '1', transform: 'translateY(0)' } },
      },
    },
  },
  plugins: [],
};
