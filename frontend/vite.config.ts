import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import legacy from '@vitejs/plugin-legacy';

// Совместимость с Chrome 109 (Windows 7) — ТЗ §10.3:
// plugin-legacy прогоняет bundle через Babel (@babel/preset-env по browserslist из package.json)
// и подмешивает core-js полифилы; современный бандл отдаётся новым браузерам, legacy — старым.
export default defineConfig({
  plugins: [
    react(),
    legacy({
      targets: ['chrome >= 109'],
      // Promise.allSettled / structuredClone и т.п. — по мере использования в коде
      modernPolyfills: true,
    }),
  ],
  build: {
    target: 'es2020',
    sourcemap: false,
    rollupOptions: {
      output: {
        // ленивая загрузка модулей почты/чата/админки (ТЗ §10.4) обеспечивается react-router lazy()
        manualChunks: { vendor: ['react', 'react-dom', 'react-router-dom'] },
      },
    },
  },
  server: {
    allowedHosts: true, // dev-сервер может открываться через внешний preview-прокси с другим Host
    // В dev-режиме браузер не ходит на localhost сервисов: все запросы относительные, Vite проксирует в gateway
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws': { target: 'http://localhost:8080', ws: true },
    },
  },
});
