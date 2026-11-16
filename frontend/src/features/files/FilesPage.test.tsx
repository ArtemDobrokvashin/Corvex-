// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';
import { driveApi, type DriveNode } from '../../api/driveApi';
import FilesPage from './FilesPage';

vi.mock('../../api/driveApi', async () => {
  const actual = await vi.importActual<typeof import('../../api/driveApi')>('../../api/driveApi');
  return {
    ...actual,
    saveBlob: vi.fn().mockResolvedValue(undefined),
    driveApi: {
      list: vi.fn(),
      usage: vi.fn(),
      createFolder: vi.fn(),
      upload: vi.fn(),
      rename: vi.fn(),
      move: vi.fn(),
      trash: vi.fn(),
      restore: vi.fn(),
      purge: vi.fn(),
      download: vi.fn(),
      shares: vi.fn(),
      createShare: vi.fn(),
      revokeShare: vi.fn(),
    },
  };
});

const folder: DriveNode = {
  id: 'folder-1',
  parentId: null,
  kind: 'FOLDER',
  name: 'Договоры',
  sizeBytes: 0,
  contentType: null,
  createdAt: '2026-10-01T09:00:00Z',
  updatedAt: '2026-10-01T09:00:00Z',
  trashedAt: null,
};

const pdfFile: DriveNode = {
  id: 'file-1',
  parentId: null,
  kind: 'FILE',
  name: 'Счёт.pdf',
  sizeBytes: 2048,
  contentType: 'application/pdf',
  createdAt: '2026-10-02T09:00:00Z',
  updatedAt: '2026-10-02T09:00:00Z',
  trashedAt: null,
};

let container: HTMLDivElement;
let root: Root;

const flush = async () => {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
};

function buttonByText(text: string): HTMLButtonElement | undefined {
  return Array.from(container.querySelectorAll('button')).find(
    (element) => (element.textContent ?? '').trim().startsWith(text),
  ) as HTMLButtonElement | undefined;
}

function buttonByLabel(label: string): HTMLButtonElement | undefined {
  return container.querySelector(`button[aria-label="${label}"]`) as HTMLButtonElement | undefined;
}

async function click(element: HTMLButtonElement | null | undefined) {
  expect(element).toBeTruthy();
  await act(async () => {
    element!.click();
    await Promise.resolve();
  });
  await flush();
}

beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  localStorage.clear();
  useLocaleStore.setState({ locale: 'ru' });
  vi.mocked(driveApi.list).mockResolvedValue([folder, pdfFile]);
  vi.mocked(driveApi.usage).mockResolvedValue({ usedBytes: 2048, quotaBytes: 5_000_000_000 });
  vi.mocked(driveApi.shares).mockResolvedValue([]);
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(async () => {
  await act(async () => root.unmount());
  container.remove();
  localStorage.clear();
  vi.clearAllMocks();
});

async function render() {
  await act(async () => {
    root.render(
      <MemoryRouter>
        <FilesPage />
      </MemoryRouter>,
    );
  });
  await flush();
}

describe('Файлы: личный диск', () => {
  it('показывает папки и файлы и считает занятое место', async () => {
    await render();

    expect(container.textContent).toContain('Договоры');
    expect(container.textContent).toContain('Счёт.pdf');
    expect(container.textContent).toContain('2,0 КБ');
    expect(driveApi.list).toHaveBeenCalledWith(null, 'files', '');
  });

  it('создаёт папку в текущем каталоге и обновляет список', async () => {
    await render();
    vi.mocked(driveApi.createFolder).mockResolvedValue({ ...folder, id: 'folder-2', name: 'Отчёты' });

    await click(buttonByText('Новая папка'));
    const input = container.querySelector('input[aria-label="Новая папка"]') as HTMLInputElement;
    expect(input).toBeTruthy();
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setter?.call(input, 'Отчёты');
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    const form = container.querySelector('form') as HTMLFormElement;
    await act(async () => {
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    });
    await flush();

    expect(driveApi.createFolder).toHaveBeenCalledWith(null, 'Отчёты');
    expect(driveApi.list).toHaveBeenCalledTimes(2);
  });

  it('переключает корзину и восстанавливает файл', async () => {
    vi.mocked(driveApi.list).mockResolvedValue([{ ...pdfFile, trashedAt: '2026-10-05T09:00:00Z' }]);
    vi.mocked(driveApi.restore).mockResolvedValue(undefined);
    await render();

    await click(buttonByText('Корзина'));
    expect(driveApi.list).toHaveBeenLastCalledWith(null, 'trash', '');

    await click(buttonByLabel('Восстановить'));
    expect(driveApi.restore).toHaveBeenCalledWith('file-1');
  });

  it('выдаёт ссылку только по явному действию и показывает адрес один раз', async () => {
    vi.mocked(driveApi.createShare).mockResolvedValue({
      share: {
        id: 'share-1',
        nodeId: 'file-1',
        createdAt: '2026-10-10T09:00:00Z',
        expiresAt: null,
        revokedAt: null,
      },
      token: 'token',
      path: '/api/v1/drive/shared/token',
    });
    await render();

    await click(buttonByLabel('Ссылка'));
    expect(driveApi.createShare).not.toHaveBeenCalled();

    await click(buttonByText('Создать ссылку'));
    expect(driveApi.createShare).toHaveBeenCalledWith('file-1', null);

    const readOnly = container.querySelector('input[readonly]') as HTMLInputElement;
    expect(readOnly.value).toBe(`${window.location.origin}/api/v1/drive/shared/token`);
  });
});
