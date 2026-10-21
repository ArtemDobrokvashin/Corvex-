import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import legacy from '@vitejs/plugin-legacy';

// Совместимость с Chrome 109 (Windows 7) — ТЗ §10.3:
// plugin-legacy прогоняет bundle через Babel (@babel/preset-env по browserslist из package.json)
// и подмешивает core-js полифилы; современный бандл отдаётся новым браузерам, legacy — старым.
export default defineConfig({
  define: {
    // sockjs-client 1.6.1 рассчитан на Browserify и обращается к Node-имени `global`.
    // В браузерном ESM такого идентификатора нет, поэтому заменяем его стандартным globalThis.
    global: 'globalThis',
  },
  optimizeDeps: {
    // Top-level define не переписывает pre-bundle CJS-зависимостей, поэтому та же замена
    // нужна Rolldown-оптимизатору, который собирает sockjs-client для dev-сервера.
    rolldownOptions: {
      transform: {
        define: {
          global: 'globalThis',
        },
      },
    },
  },
  plugins: [
    react(),
    legacy({
      targets: ['chrome >= 109'],
      // Promise.allSettled / structuredClone и т.п. — по мере использования в коде
      modernPolyfills: true,
    }),
  ],
  build: {
    sourcemap: false,
    rollupOptions: {
      output: {
        // Ленивая загрузка модулей почты/чата/админки (ТЗ §10.4) обеспечивается react-router lazy().
        // Vite 8/Rolldown принимает manualChunks только как функцию.
        manualChunks(id) {
          const normalizedId = id.replaceAll('\\', '/');
          if (/\/node_modules\/(react|react-dom|react-router|react-router-dom|scheduler)\//.test(normalizedId)) {
            return 'vendor';
          }
          return undefined;
        },
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
