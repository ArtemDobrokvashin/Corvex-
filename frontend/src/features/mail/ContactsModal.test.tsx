// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';
import type { Contact } from '../../api/contactsApi';

const apiMocks = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  remove: vi.fn(),
  importFile: vi.fn(),
  exportFile: vi.fn(),
}));

vi.mock('../../api/contactsApi', () => ({
  contactsApi: apiMocks,
  saveBlob: vi.fn(),
}));

import ContactsModal from './ContactsModal';

const ivan: Contact = {
  id: '11111111-1111-4111-8111-111111111111',
  displayName: 'Иван Петров',
  email: 'ivan@company.local',
  phone: '+7 999 000-00-00',
  organization: 'Ромашка',
  jobTitle: 'Бухгалтер',
  note: null,
  groupName: 'Бухгалтерия',
  source: 'VCARD',
  createdAt: '2026-11-01T10:00:00Z',
  updatedAt: '2026-11-01T10:00:00Z',
};

function setInput(input: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

async function flush() {
  await Promise.resolve();
  await Promise.resolve();
}

describe('ContactsModal', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    apiMocks.list.mockReset().mockResolvedValue([ivan]);
    apiMocks.importFile.mockReset();
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    useLocaleStore.setState({ locale: 'ru' });
  });

  it('renders the address book and searches by query', async () => {
    await act(async () => {
      root.render(<ContactsModal demoMode={false} onClose={vi.fn()} />);
      await flush();
    });

    expect(container.textContent).toContain('Address book');
    expect(container.textContent).toContain('Иван Петров');
    expect(container.textContent).toContain('ivan@company.local');
    expect(container.textContent).toContain('Ромашка');

    const search = container.querySelector<HTMLInputElement>('input[aria-label="Search by name, address, or organization"]')!;
    await act(async () => setInput(search, 'Петров'));
    await act(async () => flush());

    expect(apiMocks.list).toHaveBeenLastCalledWith('Петров');
  });

  it('shows the import summary with skipped rows', async () => {
    apiMocks.importFile.mockResolvedValue({
      total: 3,
      created: 2,
      updated: 0,
      skipped: 1,
      truncated: false,
      issues: [{ line: 4, value: 'Без адреса', reason: 'в карточке нет адреса электронной почты' }],
    });

    await act(async () => {
      root.render(<ContactsModal demoMode={false} onClose={vi.fn()} />);
      await flush();
    });

    const input = container.querySelector<HTMLInputElement>('input[type="file"]')!;
    const file = new File(['BEGIN:VCARD'], 'contacts.vcf', { type: 'text/vcard' });
    Object.defineProperty(input, 'files', { value: [file], configurable: true });

    await act(async () => {
      input.dispatchEvent(new Event('change', { bubbles: true }));
      await flush();
    });

    expect(apiMocks.importFile).toHaveBeenCalledWith(file);
    expect(container.textContent).toContain('2 added');
    expect(container.textContent).toContain('1 skipped');
    expect(container.textContent).toContain('в карточке нет адреса электронной почты');
  });

  it('does not call the API in demo mode', async () => {
    await act(async () => {
      root.render(<ContactsModal demoMode onClose={vi.fn()} />);
      await flush();
    });

    expect(apiMocks.list).not.toHaveBeenCalled();
    expect(container.textContent).toContain('Address book');
  });
});
