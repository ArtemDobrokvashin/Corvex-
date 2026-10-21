// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DEMO_READ_STATE_STORAGE_KEY, SessionUser, useSession } from './sessionStore';

const user: SessionUser = {
  id: 'demo-user',
  displayName: 'Demo User',
  email: 'demo@company.local',
  groups: ['USER'],
  permissions: [],
};

describe('demo read state', () => {
  beforeEach(() => {
    sessionStorage.clear();
    useSession.setState({
      user: null,
      demoMode: false,
      bootstrapped: false,
      demoReadMailIds: [],
      demoReadRoomIds: [],
    });
  });

  afterEach(() => {
    sessionStorage.clear();
  });

  it('records each read demo mail only once', () => {
    const { markDemoMailRead } = useSession.getState();

    markDemoMailRead('m1');
    markDemoMailRead('m1');
    markDemoMailRead('m2');

    expect(useSession.getState().demoReadMailIds).toEqual(['m1', 'm2']);
  });

  it('keeps read ids for the same session and resets them for a new one', () => {
    const { markDemoMailRead, setSession, clearSession } = useSession.getState();

    setSession(user, true);
    markDemoMailRead('m1');
    setSession(user, true);
    expect(useSession.getState().demoReadMailIds).toEqual(['m1']);

    setSession({ ...user, id: 'another-user' }, true);
    expect(useSession.getState().demoReadMailIds).toEqual([]);

    markDemoMailRead('m2');
    clearSession();
    expect(useSession.getState().demoReadMailIds).toEqual([]);
  });

  it('records each read demo room only once and resets rooms with the session', () => {
    const { markDemoRoomRead, setSession, clearSession } = useSession.getState();

    setSession(user, true);
    markDemoRoomRead('r2');
    markDemoRoomRead('r2');
    markDemoRoomRead('r1');
    setSession(user, true);
    expect(useSession.getState().demoReadRoomIds).toEqual(['r2', 'r1']);

    setSession({ ...user, id: 'another-user' }, true);
    expect(useSession.getState().demoReadRoomIds).toEqual([]);

    markDemoRoomRead('r1');
    clearSession();
    expect(useSession.getState().demoReadRoomIds).toEqual([]);
  });

  it('persists demo read ids for the browser-tab demo session and clears them on logout', () => {
    const { markDemoMailRead, markDemoRoomRead, setSession, clearSession } = useSession.getState();

    sessionStorage.setItem('corvex.demo', '1');
    setSession(user, true);
    markDemoMailRead('m1');
    markDemoRoomRead('r2');

    expect(JSON.parse(sessionStorage.getItem(DEMO_READ_STATE_STORAGE_KEY) ?? 'null')).toEqual({
      userId: user.id,
      demoReadMailIds: ['m1'],
      demoReadRoomIds: ['r2'],
    });

    clearSession();
    expect(sessionStorage.getItem(DEMO_READ_STATE_STORAGE_KEY)).toBeNull();
  });

  it('restores demo read ids after a full page reload', async () => {
    sessionStorage.setItem('corvex.demo', '1');
    sessionStorage.setItem(DEMO_READ_STATE_STORAGE_KEY, JSON.stringify({
      userId: user.id,
      demoReadMailIds: ['m1'],
      demoReadRoomIds: ['r2'],
    }));

    vi.resetModules();
    const { useSession: reloadedUseSession } = await import('./sessionStore');

    expect(reloadedUseSession.getState().demoReadMailIds).toEqual(['m1']);
    expect(reloadedUseSession.getState().demoReadRoomIds).toEqual(['r2']);

    reloadedUseSession.getState().setSession(user, true);
    expect(reloadedUseSession.getState().demoReadMailIds).toEqual(['m1']);
    expect(reloadedUseSession.getState().demoReadRoomIds).toEqual(['r2']);
  });
});
