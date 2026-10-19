/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: { brand: { DEFAULT: '#2f5fe8', dark: '#1c3c96' } },
      fontFamily: { sans: ['"Segoe UI"', 'system-ui', 'Tahoma', 'sans-serif'] }, // системные шрифты: на Win7 нет веб-шрифтов из CDN офлайн
    },
  },
  plugins: [],
};
