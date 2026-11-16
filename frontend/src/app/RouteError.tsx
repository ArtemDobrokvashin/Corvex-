import { useEffect, useState } from 'react';
import { AlertTriangle, RefreshCw, WifiOff } from 'lucide-react';
import { isRouteErrorResponse, useRouteError } from 'react-router-dom';
import { useI18n } from '../i18n';
import {
  isChunkLoadError,
  reloadPage,
  resetStaleChunkGuard,
  tryReloadForStaleChunk,
} from '../lib/chunkReload';

type AutoReloadState = 'idle' | 'reloading' | 'blocked' | 'offline';

function technicalDetails(error: unknown): string {
  if (isRouteErrorResponse(error)) {
    return `${error.status} ${error.statusText}`.trim();
  }
  if (error instanceof Error) {
    return error.message.slice(0, 300);
  }
  if (typeof error === 'string') return error.slice(0, 300);
  return '';
}

/**
 * Заменяет технический экран React Router. Отдельно обрабатывает протухшие
 * чанки после передеплоя: пробует одну перезагрузку, а если это не помогло —
 * показывает понятное объяснение и кнопки.
 */
export default function RouteError() {
  const error = useRouteError();
  const { t } = useI18n();
  const staleChunk = isChunkLoadError(error);
  const [autoReload, setAutoReload] = useState<AutoReloadState>('idle');

  useEffect(() => {
    if (!staleChunk) return;
    const result = tryReloadForStaleChunk();
    // В StrictMode эффект может выполниться дважды: не перетираем состояние
    // «перезагружаем» результатом второй, уже заблокированной попытки.
    setAutoReload((current) => {
      if (current === 'reloading') return current;
      if (result === 'reloaded') return 'reloading';
      if (result === 'offline') return 'offline';
      return 'blocked';
    });
  }, [staleChunk]);

  const offline =
    autoReload === 'offline' ||
    (typeof navigator !== 'undefined' && navigator.onLine === false);
  const details = technicalDetails(error);

  const retry = () => {
    resetStaleChunkGuard();
    reloadPage();
  };

  const title = offline
    ? t('Нет подключения к сети')
    : staleChunk
      ? t('Не удалось загрузить часть приложения')
      : t('Что-то пошло не так');
  const description = offline
    ? t('Проверьте интернет и попробуйте снова.')
    : staleChunk
      ? t('Возможно, приложение обновилось. Загрузите страницу ещё раз — так вы получите актуальную версию.')
      : t('Страница не открылась. Обновите её или вернитесь в почту.');

  return (
    <main className="flex min-h-dvh items-center justify-center bg-canvas px-4 py-10">
      <section
        role="alert"
        aria-labelledby="route-error-title"
        className="w-full max-w-[520px] rounded-2xl border border-line bg-surface p-6 shadow-pop sm:p-8"
      >
        <span className="flex h-12 w-12 items-center justify-center rounded-xl bg-accent-soft text-accent">
          {offline
            ? <WifiOff size={22} strokeWidth={1.8} aria-hidden="true" />
            : <AlertTriangle size={22} strokeWidth={1.8} aria-hidden="true" />}
        </span>

        <h1 id="route-error-title" className="mt-4 text-xl font-semibold text-ink">
          {title}
        </h1>
        <p className="mt-2 text-sm leading-relaxed text-muted">{description}</p>

        {autoReload === 'reloading' && (
          <p role="status" className="mt-4 flex items-center gap-2 text-sm font-medium text-accent">
            <RefreshCw size={16} className="animate-spin" aria-hidden="true" />
            {t('Обновляем приложение…')}
          </p>
        )}

        <div className="mt-5 flex flex-wrap gap-2">
          <button
            type="button"
            onClick={retry}
            className="inline-flex h-10 items-center gap-2 rounded-lg bg-accent px-4 text-sm font-medium text-white transition hover:bg-accent-dark"
          >
            <RefreshCw size={16} strokeWidth={1.9} aria-hidden="true" />
            {t('Обновить страницу')}
          </button>
          {/* Полная навигация, а не переход роутера: так браузер заново получит
              index.html и актуальные чанки вместо протухших. */}
          <a
            href="/mail"
            className="inline-flex h-10 items-center rounded-lg border border-line-control bg-surface px-4 text-sm font-medium text-ink transition hover:border-accent hover:text-accent"
          >
            {t('На главную')}
          </a>
        </div>

        {details && (
          <details className="mt-5 text-xs text-faint">
            <summary className="cursor-pointer rounded px-1 py-0.5">{t('Технические подробности')}</summary>
            <pre className="mt-2 max-h-32 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-canvas px-3 py-2 text-[11px] leading-relaxed">
              {details}
            </pre>
          </details>
        )}
      </section>
    </main>
  );
}
