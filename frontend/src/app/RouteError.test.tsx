// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../i18n';
import RouteError from './RouteError';

const reloadMocks = vi.hoisted(() => ({
  tryReloadForStaleChunk: vi.fn(),
  resetStaleChunkGuard: vi.fn(),
  reloadPage: vi.fn(),
}));

vi.mock('../lib/chunkReload', async () => {
  const actual = await vi.importActual<typeof import('../lib/chunkReload')>('../lib/chunkReload');
  return { ...actual, ...reloadMocks };
});

const CHUNK_MESSAGE =
  'Failed to fetch dynamically imported module: https://mail.company.local/assets/CalendarPage-CmN8WYl1.js';

let container: HTMLDivElement;
let root: Root;

function Boom({ message }: { message: string }): never {
  throw new Error(message);
}

const flush = async () => {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
};

async function renderError(message: string) {
  const router = createMemoryRouter(
    [{ path: '/', element: <Boom message={message} />, errorElement: <RouteError /> }],
    { initialEntries: ['/'] },
  );
  await act(async () => {
    root.render(<RouterProvider router={router} />);
  });
  await flush();
}

beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  localStorage.clear();
  sessionStorage.clear();
  useLocaleStore.setState({ locale: 'ru' });
  reloadMocks.tryReloadForStaleChunk.mockReset().mockReturnValue('cooldown');
  reloadMocks.resetStaleChunkGuard.mockReset();
  reloadMocks.reloadPage.mockReset();
  // React Router и React печатают перехваченную ошибку в консоль.
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(async () => {
  await act(async () => root.unmount());
  container.remove();
  vi.restoreAllMocks();
});

describe('экран ошибки маршрута', () => {
  it('после передеплоя сам перезагружает страницу один раз', async () => {
    reloadMocks.tryReloadForStaleChunk.mockReturnValue('reloaded');

    await renderError(CHUNK_MESSAGE);

    expect(reloadMocks.tryReloadForStaleChunk).toHaveBeenCalledTimes(1);
    expect(container.textContent).toContain('Обновляем приложение…');
  });

  it('если перезагрузка уже была — объясняет причину и даёт кнопки', async () => {
    await renderError(CHUNK_MESSAGE);

    expect(container.textContent).toContain('Не удалось загрузить часть приложения');
    expect(container.textContent).toContain('Возможно, приложение обновилось');
    expect(container.textContent).toContain('Не удалось загрузить часть приложения');
    expect(container.querySelector('a[href="/mail"]')?.textContent).toContain('На главную');

    const retry = Array.from(container.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Обновить страницу')) as HTMLButtonElement;
    await act(async () => retry.click());

    expect(reloadMocks.resetStaleChunkGuard).toHaveBeenCalledTimes(1);
    expect(reloadMocks.reloadPage).toHaveBeenCalledTimes(1);
  });

  it('показывает технические подробности, но не сырой стек', async () => {
    await renderError(CHUNK_MESSAGE);

    const details = container.querySelector('details');
    expect(details?.textContent).toContain('Технические подробности');
    expect(details?.textContent).toContain('CalendarPage-CmN8WYl1.js');
    expect(container.textContent).not.toContain('Unexpected Application Error');
  });

  it('для обычной ошибки не пытается перезагружать автоматически', async () => {
    await renderError('Cannot read properties of undefined (reading id)');

    expect(reloadMocks.tryReloadForStaleChunk).not.toHaveBeenCalled();
    expect(container.textContent).toContain('Что-то пошло не так');
    expect(container.textContent).toContain('Страница не открылась');
  });

  it('без сети сообщает об этом и не перезагружает страницу', async () => {
    reloadMocks.tryReloadForStaleChunk.mockReturnValue('offline');

    await renderError(CHUNK_MESSAGE);

    expect(container.textContent).toContain('Нет подключения к сети');
    expect(container.textContent).toContain('Проверьте интернет');
  });
});
