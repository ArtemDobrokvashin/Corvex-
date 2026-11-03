// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';
import { useSession, type SessionUser } from '../../store/sessionStore';

const apiMocks = vi.hoisted(() => ({
  analyticsActivity: vi.fn(),
  requestBackup: vi.fn(),
  systemHealth: vi.fn(),
}));

vi.mock('../../api/adminApi', () => ({
  adminApi: {
    analyticsActivity: apiMocks.analyticsActivity,
    requestBackup: apiMocks.requestBackup,
    systemHealth: apiMocks.systemHealth,
  },
}));

import AnalyticsTab from './AnalyticsTab';
import ServerTab from './ServerTab';

const user = (permissions: string[]): SessionUser => ({
  id: 'user-1',
  displayName: 'Test User',
  email: 'user@company.local',
  department: 'Sales',
  groups: [],
  permissions,
});

const analytics = {
  scope: 'OWN_UNIT',
  department: 'Sales',
  usersTotal: 10,
  usersActive: 8,
  usersBlocked: 1,
  usersDisabled: 1,
  loginsLast24h: 4,
  loginsLast7d: 12,
  generatedAt: '2026-11-03T12:00:00Z',
};

describe('admin server and analytics tabs', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    apiMocks.analyticsActivity.mockReset().mockResolvedValue(analytics);
    apiMocks.requestBackup.mockReset().mockResolvedValue({ status: 'REQUESTED', note: 'recorded' });
    apiMocks.systemHealth.mockReset().mockResolvedValue({ services: [], diskFreeBytes: 0, mailQueueDepth: 0 });
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    useLocaleStore.setState({ locale: 'ru' });
  });

  it('keeps own-unit analytics scoped by the backend and hides the department filter', async () => {
    useSession.getState().setSession(user(['ANALYTICS_VIEW_OWN_UNIT']), false);

    await act(async () => {
      root.render(<AnalyticsTab />);
      await Promise.resolve();
    });

    expect(apiMocks.analyticsActivity).toHaveBeenCalledWith(undefined);
    expect(container.querySelector('#analytics-department')).toBeNull();
    expect(container.textContent).toContain('Own department');
    expect(container.textContent).toContain('Sales');
    expect(container.textContent).toContain('12');
  });

  it('offers the department filter only for company analytics', async () => {
    useSession.getState().setSession(user(['ANALYTICS_VIEW_COMPANY']), false);
    apiMocks.analyticsActivity.mockResolvedValue({ ...analytics, scope: 'COMPANY', department: null });

    await act(async () => {
      root.render(<AnalyticsTab />);
      await Promise.resolve();
    });

    expect(apiMocks.analyticsActivity).toHaveBeenCalledWith('');
    expect(container.querySelector('#analytics-department')).not.toBeNull();
    expect(container.textContent).toContain('Entire company');
  });

  it('does not request health for a backup-only role and reports only an audit request', async () => {
    useSession.getState().setSession(user(['ADMIN_BACKUP']), false);

    await act(async () => root.render(<ServerTab canViewHealth={false} canBackup />));
    expect(apiMocks.systemHealth).not.toHaveBeenCalled();

    const button = Array.from(container.querySelectorAll('button'))
      .find((candidate) => candidate.textContent === 'Record request in audit');
    expect(button).toBeDefined();

    await act(async () => {
      button!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      await Promise.resolve();
    });

    expect(apiMocks.requestBackup).toHaveBeenCalledTimes(1);
    expect(container.textContent).toContain('The request was recorded in the audit log.');
    expect(container.textContent).toContain('This action did not create a backup.');
  });
});
