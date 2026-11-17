// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';
import { ApiError } from '../../api/httpClient';
import { driveApi, type DriveNode } from '../../api/driveApi';
import FilesPage from './FilesPage';

vi.mock('../../api/driveApi', async () => {
  const actual = await vi.importActual<typeof import('../../api/driveApi')>('../../api/driveApi');
  return {
    ...actual,
    saveBlob: vi.fn().mockResolvedValue(undefined),
    driveApi: {
      list: vi.fn(),
      folders: vi.fn(),
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

const childFolder: DriveNode = {
  ...folder,
  id: 'folder-2',
  parentId: 'folder-1',
  name: 'Архив',
};

let container: HTMLDivElement;
let root: Root;

const flush = async () => {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
};

/** Последовательные await'ы в загрузке и мутациях требуют нескольких проходов. */
const settle = async () => {
  for (let i = 0; i < 8; i += 1) await flush();
};

function buttonByText(text: string): HTMLButtonElement | undefined {
  return Array.from(container.querySelectorAll('button')).find(
    (element) => (element.textContent ?? '').trim().startsWith(text),
  ) as HTMLButtonElement | undefined;
}

function buttonByLabel(label: string): HTMLButtonElement | undefined {
  return container.querySelector(`button[aria-label="${label}"]`) as HTMLButtonElement | undefined;
}

function rowButton(nodeName: string, label: string): HTMLButtonElement | undefined {
  const row = Array.from(container.querySelectorAll('li')).find(
    (element) => (element.textContent ?? '').includes(nodeName),
  );
  return row?.querySelector(`button[aria-label="${label}"]`) as HTMLButtonElement | undefined;
}

function dialog(): HTMLElement | null {
  return container.querySelector('[role="dialog"]');
}

function buttonInDialog(text: string): HTMLButtonElement | undefined {
  const scope = dialog();
  if (!scope) return undefined;
  return Array.from(scope.querySelectorAll('button')).find(
    (element) => (element.textContent ?? '').trim().startsWith(text),
  ) as HTMLButtonElement | undefined;
}

async function click(element: HTMLButtonElement | null | undefined) {
  expect(element).toBeTruthy();
  await act(async () => {
    element!.click();
    await Promise.resolve();
  });
  await settle();
}

function moveSelect(): HTMLSelectElement | null {
  return dialog()?.querySelector('select#drive-move-target') as HTMLSelectElement | null;
}

async function chooseOption(select: HTMLSelectElement, value: string) {
  await act(async () => {
    select.value = value;
    select.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await settle();
}

async function typeInto(input: HTMLInputElement, value: string) {
  await act(async () => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
    setter?.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  localStorage.clear();
  useLocaleStore.setState({ locale: 'ru' });
  vi.mocked(driveApi.list).mockResolvedValue([folder, pdfFile]);
  vi.mocked(driveApi.folders).mockResolvedValue([folder]);
  vi.mocked(driveApi.usage).mockResolvedValue({ usedBytes: 2048, quotaBytes: 5_000_000_000 });
  vi.mocked(driveApi.shares).mockResolvedValue([]);
  vi.mocked(driveApi.trash).mockResolvedValue(undefined);
  vi.mocked(driveApi.restore).mockResolvedValue(undefined);
  vi.mocked(driveApi.purge).mockResolvedValue(undefined);
  vi.mocked(driveApi.revokeShare).mockResolvedValue(undefined);
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(async () => {
  await act(async () => root.unmount());
  container.remove();
  localStorage.clear();
  vi.clearAllMocks();
  Reflect.deleteProperty(navigator, 'clipboard');
});

async function render() {
  await act(async () => {
    root.render(
      <MemoryRouter>
        <FilesPage />
      </MemoryRouter>,
    );
  });
  await settle();
}

describe('Файлы: личный диск', () => {
  // Просмотр открывается через контекст PreviewProvider; в этом файле провайдера
  // нет, поэтому проверяем само решение «просмотр или скачивание»: файл, который
  // умеет показать диалог, не должен тянуться на диск по клику.
  it('не скачивает файл, который можно открыть во встроенном просмотре', async () => {
    await render();
    const row = Array.from(container.querySelectorAll('li'))
      .find((element) => (element.textContent ?? '').includes('Счёт.pdf'));
    const opener = row?.querySelector('button');
    expect(opener).toBeTruthy();

    await click(opener as HTMLButtonElement);

    expect(driveApi.download).not.toHaveBeenCalled();
  });

  it('скачивает архив, для которого нет встроенного просмотра', async () => {
    const archive: DriveNode = {
      ...pdfFile,
      id: 'file-zip',
      name: 'backup.zip',
      contentType: 'application/zip',
    };
    vi.mocked(driveApi.list).mockResolvedValue([archive]);
    vi.mocked(driveApi.download).mockResolvedValue(new Blob(['zip']));
    await render();

    const row = Array.from(container.querySelectorAll('li'))
      .find((element) => (element.textContent ?? '').includes('backup.zip'));
    await click(row?.querySelector('button') as HTMLButtonElement);

    expect(driveApi.download).toHaveBeenCalledWith('file-zip');
  });

  it('показывает папки и файлы и считает занятое место', async () => {
    await render();

    expect(container.textContent).toContain('Договоры');
    expect(container.textContent).toContain('Счёт.pdf');
    expect(container.textContent).toContain('2,0 КБ');
    expect(container.textContent).toContain('Занято 2,0 КБ из 5,0 ГБ');
    expect(driveApi.list).toHaveBeenCalledWith(null, 'files', '');
  });

  it('ставит папки перед файлами независимо от порядка ответа API', async () => {
    vi.mocked(driveApi.list).mockResolvedValue([pdfFile, folder]);
    await render();

    const text = container.textContent ?? '';
    expect(text.indexOf('Договоры')).toBeGreaterThanOrEqual(0);
    expect(text.indexOf('Договоры')).toBeLessThan(text.indexOf('Счёт.pdf'));
  });

  it('показывает пустое состояние, когда файлов нет', async () => {
    vi.mocked(driveApi.list).mockResolvedValue([]);
    await render();

    expect(container.textContent).toContain('Здесь пока нет файлов');
    expect(container.textContent).toContain('Перетащите файлы сюда или нажмите «Загрузить»');
  });

  it('показывает список даже если квота не загрузилась', async () => {
    vi.mocked(driveApi.usage).mockRejectedValue(new ApiError(503, 'Сервис квоты недоступен'));
    await render();

    expect(container.textContent).toContain('Счёт.pdf');
    expect(container.textContent).toContain('Не удалось загрузить занятое место');
    expect(container.textContent).toContain('Сервис квоты недоступен');
  });

  it('показывает исходный detail из Problem Details при ошибке списка', async () => {
    vi.mocked(driveApi.list).mockRejectedValue(new ApiError(423, 'Диск заблокирован администратором'));
    await render();

    expect(container.textContent).toContain('Диск заблокирован администратором');
  });

  it('создаёт папку в текущем каталоге и обновляет список', async () => {
    await render();
    vi.mocked(driveApi.createFolder).mockResolvedValue({ ...folder, id: 'folder-3', name: 'Отчёты' });

    await click(buttonByLabel('Новая папка'));
    const input = container.querySelector('#drive-name') as HTMLInputElement;
    expect(input).toBeTruthy();
    expect(dialog()?.textContent).toContain('Название папки');
    await typeInto(input, 'Отчёты');
    await act(async () => {
      (container.querySelector('#drive-name') as HTMLInputElement).closest('form')!
        .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    });
    await settle();

    expect(driveApi.createFolder).toHaveBeenCalledWith(null, 'Отчёты');
    expect(driveApi.list).toHaveBeenCalledTimes(2);
    expect(dialog()).toBeNull();
  });

  it('показывает конфликт 409 внутри диалога и не закрывает его', async () => {
    vi.mocked(driveApi.createFolder).mockRejectedValue(new ApiError(409, 'Папка с таким именем уже существует'));
    await render();

    await click(buttonByLabel('Новая папка'));
    await typeInto(container.querySelector('#drive-name') as HTMLInputElement, 'Договоры');
    await act(async () => {
      (container.querySelector('#drive-name') as HTMLInputElement).closest('form')!
        .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    });
    await settle();

    expect(dialog()).not.toBeNull();
    expect(dialog()?.textContent).toContain('Папка с таким именем уже существует');
    expect(driveApi.list).toHaveBeenCalledTimes(1);
  });

  it('загружает несколько файлов, обновляет список и не скрывает частичный сбой', async () => {
    const good = new File(['x'], 'отчёт.pdf', { type: 'application/pdf' });
    const bad = new File(['y'], 'огромный.zip', { type: 'application/zip' });
    vi.mocked(driveApi.upload)
      .mockResolvedValueOnce(pdfFile)
      .mockRejectedValueOnce(new ApiError(413, 'Файл больше 100 МБ'));
    await render();

    const input = container.querySelector('[data-testid="file-input"]') as HTMLInputElement;
    Object.defineProperty(input, 'files', { value: [good, bad], configurable: true });
    await act(async () => {
      input.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await settle();

    expect(driveApi.upload).toHaveBeenCalledTimes(2);
    expect(driveApi.upload).toHaveBeenNthCalledWith(1, null, good);
    expect(driveApi.list).toHaveBeenCalledTimes(2);
    expect(driveApi.usage).toHaveBeenCalledTimes(2);
    expect(container.textContent).toContain('Файл загружен');
    expect(container.textContent).toContain('огромный.zip');
    expect(container.textContent).toContain('Файл больше 100 МБ');
    expect(container.querySelector('[aria-label="Прогресс загрузки"]')).toBeNull();
  });

  it('переключает корзину и восстанавливает файл', async () => {
    vi.mocked(driveApi.list).mockResolvedValue([{ ...pdfFile, trashedAt: '2026-10-05T09:00:00Z' }]);
    await render();

    await click(buttonByText('Корзина'));
    expect(driveApi.list).toHaveBeenLastCalledWith(null, 'trash', '');

    await click(buttonByLabel('Восстановить'));
    expect(driveApi.restore).toHaveBeenCalledWith('file-1');
    expect(driveApi.list).toHaveBeenCalledTimes(3);
  });

  it('удаляет навсегда только после подтверждения и обновляет квоту', async () => {
    vi.mocked(driveApi.list).mockResolvedValue([{ ...pdfFile, trashedAt: '2026-10-05T09:00:00Z' }]);
    await render();
    await click(buttonByText('Корзина'));

    await click(buttonByLabel('Удалить навсегда'));
    expect(driveApi.purge).not.toHaveBeenCalled();
    expect(dialog()?.textContent).toContain('без возможности восстановления');

    await click(buttonInDialog('Удалить навсегда'));
    expect(driveApi.purge).toHaveBeenCalledWith('file-1');
    expect(driveApi.usage).toHaveBeenCalledTimes(2);
    expect(dialog()).toBeNull();
  });

  it('перемещает файл в выбранную папку через список, а не кликом по имени', async () => {
    vi.mocked(driveApi.folders).mockResolvedValue([folder, childFolder]);
    vi.mocked(driveApi.move).mockResolvedValue({ ...pdfFile, parentId: 'folder-2' });
    await render();

    await click(rowButton('Счёт.pdf', 'Переместить'));

    const select = moveSelect();
    expect(select).toBeTruthy();
    // Пока папка не выбрана, подтверждение недоступно: иначе кнопка «перемещает»
    // файл в корень и рапортует об успехе, ничего не изменив.
    expect(select?.value).toBe('');
    expect(buttonInDialog('Переместить')?.disabled).toBe(true);

    // Подпапка видна полным путём и отсортирована по имени.
    const labels = Array.from(select!.options, (option) => option.textContent);
    expect(labels).toContain('Договоры / Архив');
    expect(labels.filter((label) => label === 'Договоры' || label === 'Договоры / Архив'))
      .toEqual(['Договоры', 'Договоры / Архив']);

    await chooseOption(select!, 'folder-2');
    expect(dialog()?.textContent).toContain('Договоры / Архив');
    expect(buttonInDialog('Переместить')?.disabled).toBe(false);

    await click(buttonInDialog('Переместить'));
    expect(driveApi.move).toHaveBeenCalledWith('file-1', 'folder-2');
    expect(dialog()).toBeNull();
  });

  it('не предлагает перемещение в текущую папку и в собственное поддерево', async () => {
    const parent: DriveNode = { ...folder, id: 'folder-9', name: 'Родитель', parentId: null };
    const moving: DriveNode = { ...folder, id: 'folder-10', name: 'Цель', parentId: 'folder-9' };
    const inside: DriveNode = { ...folder, id: 'folder-11', name: 'Внутри', parentId: 'folder-10' };
    vi.mocked(driveApi.list).mockResolvedValue([parent, moving]);
    vi.mocked(driveApi.folders).mockResolvedValue([parent, moving, inside]);
    await render();

    await click(rowButton('Цель', 'Переместить'));
    const select = moveSelect();
    const values = Array.from(select!.options, (option) => option.value);

    // Сама папка и её потомки недоступны: перенос внутрь себя — гарантированная ошибка.
    expect(values).not.toContain('folder-10');
    expect(values).not.toContain('folder-11');
    expect(values).toContain('folder-9');

    // Текущий родитель — валидная цель по контракту, но бессмысленная операция.
    await chooseOption(select!, 'folder-9');
    expect(dialog()?.textContent).toContain('Файл уже находится в этой папке');
    expect(buttonInDialog('Переместить')?.disabled).toBe(true);
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
      token: 'secret-token-1',
      path: '/api/v1/drive/shared/secret-token-1',
    });
    await render();

    await click(buttonByLabel('Ссылка'));
    expect(driveApi.createShare).not.toHaveBeenCalled();
    expect(dialog()?.textContent).toContain('Ссылок нет');

    await click(buttonByText('Создать ссылку'));
    expect(driveApi.createShare).toHaveBeenCalledWith('file-1', null);

    const readOnly = container.querySelector('input[readonly]') as HTMLInputElement;
    expect(readOnly.value).toBe(`${window.location.origin}/api/v1/drive/shared/secret-token-1`);
    expect(dialog()?.textContent).toContain('позже токен не показывается');

    // Токен живёт только в поле однократного показа: не в href/src/alt/name/aria-label
    // и не в адресе приложения.
    const attributes = ['href', 'src', 'alt', 'name', 'aria-label', 'title', 'placeholder'];
    for (const element of Array.from(container.querySelectorAll('*'))) {
      for (const attribute of attributes) {
        expect(element.getAttribute(attribute) ?? '').not.toContain('secret-token-1');
      }
    }
    expect(window.location.href).not.toContain('secret-token-1');
  });

  it('выдаёт ссылку с выбранным сроком и отзывает её', async () => {
    const share = {
      id: 'share-2',
      nodeId: 'file-1',
      createdAt: '2026-10-10T09:00:00Z',
      expiresAt: new Date(Date.now() + 7 * 86_400_000).toISOString(),
      revokedAt: null,
    };
    let revoked = false;
    let calls = 0;
    vi.mocked(driveApi.createShare).mockResolvedValue({
      share,
      token: 'secret-token-2',
      path: '/api/v1/drive/shared/secret-token-2',
    });
    vi.mocked(driveApi.shares).mockImplementation(async () => {
      calls += 1;
      if (calls === 1) return [];
      return revoked ? [{ ...share, revokedAt: '2026-10-11T09:00:00Z' }] : [share];
    });
    vi.mocked(driveApi.revokeShare).mockImplementation(async () => {
      revoked = true;
    });
    await render();

    await click(buttonByLabel('Ссылка'));
    const select = dialog()!.querySelector('select') as HTMLSelectElement;
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set;
      setter?.call(select, '7');
      select.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await click(buttonByText('Создать ссылку'));
    expect(driveApi.createShare).toHaveBeenCalledWith('file-1', 7);
    expect(dialog()?.textContent).toContain('Активные ссылки');
    expect(buttonInDialog('Отозвать')).toBeTruthy();

    await click(buttonInDialog('Отозвать'));
    expect(driveApi.revokeShare).toHaveBeenCalledWith('share-2');
    expect(dialog()?.textContent).toContain('Ссылок нет');
  });

  it('показывает ошибку 409 при создании ссылки внутри диалога', async () => {
    vi.mocked(driveApi.createShare).mockRejectedValue(new ApiError(409, 'Ссылки для папок недоступны'));
    await render();

    await click(buttonByLabel('Ссылка'));
    await click(buttonByText('Создать ссылку'));

    expect(dialog()?.textContent).toContain('Ссылки для папок недоступны');
    expect(container.querySelector('input[readonly]')).toBeNull();
  });

  it('не глушит ошибку загрузки активных ссылок', async () => {
    vi.mocked(driveApi.shares).mockRejectedValue(new ApiError(503, 'Сервис ссылок недоступен'));
    await render();

    await click(buttonByLabel('Ссылка'));

    expect(dialog()?.textContent).toContain('Сервис ссылок недоступен');
    expect(dialog()?.textContent).not.toContain('Ссылок нет');
  });

  it('предлагает скопировать адрес вручную, если clipboard недоступен', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) },
    });
    vi.mocked(driveApi.createShare).mockResolvedValue({
      share: {
        id: 'share-3',
        nodeId: 'file-1',
        createdAt: '2026-10-10T09:00:00Z',
        expiresAt: null,
        revokedAt: null,
      },
      token: 'secret-token-3',
      path: '/api/v1/drive/shared/secret-token-3',
    });
    await render();

    await click(buttonByLabel('Ссылка'));
    await click(buttonByText('Создать ссылку'));
    await click(buttonInDialog('Копировать'));

    expect(dialog()?.textContent).toContain('Скопируйте адрес вручную');
    expect(dialog()?.textContent).not.toContain('Скопировано');
  });

  it('закрывает диалог по Escape', async () => {
    await render();
    await click(buttonByLabel('Ссылка'));
    expect(dialog()).not.toBeNull();

    await act(async () => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
    await flush();

    expect(dialog()).toBeNull();
  });
});
