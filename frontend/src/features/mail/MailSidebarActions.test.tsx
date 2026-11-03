// @vitest-environment jsdom
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it } from 'vitest';
import { useLocaleStore } from '../../i18n';
import MailSidebarActions from './MailSidebarActions';

function render(canAdminExternalAccounts: boolean, externalAccountCount: number) {
  document.body.innerHTML = renderToStaticMarkup(
    <MailSidebarActions
      canAdminExternalAccounts={canAdminExternalAccounts}
      externalAccountCount={externalAccountCount}
      onCompose={() => undefined}
      onOpenExternal={() => undefined}
    />,
  );
  return document.body;
}

describe('mail sidebar actions', () => {
  afterEach(() => {
    useLocaleStore.setState({ locale: 'ru' });
    document.body.innerHTML = '';
  });

  it('shows external mailbox administration only to the mail administrator', () => {
    useLocaleStore.setState({ locale: 'en' });

    const employee = render(false, 2);
    expect(employee.querySelectorAll('button')).toHaveLength(1);
    expect(employee.textContent).not.toContain('External mailboxes');

    const admin = render(true, 2);
    expect(admin.querySelectorAll('button')).toHaveLength(2);
    expect(admin.textContent).toContain('External mailboxes');
  });

  it('keeps fixed action classes and reserves the same badge space for zero and two accounts', () => {
    useLocaleStore.setState({ locale: 'en' });
    const zero = render(true, 0).querySelectorAll('button')[1];
    const two = render(true, 2).querySelectorAll('button')[1];

    expect(zero.className).toBe(two.className);
    expect(zero.className).toContain('h-10');
    expect(zero.className).toContain('w-full');
    expect(zero.className).toContain('shrink-0');

    const zeroBadge = zero.lastElementChild;
    const twoBadge = two.lastElementChild;
    expect(zeroBadge?.className).toBe(twoBadge?.className);
    expect(zeroBadge?.className).toContain('w-6');
    expect(zeroBadge?.textContent).toBe('');
    expect(twoBadge?.textContent).toBe('2');
  });
});
