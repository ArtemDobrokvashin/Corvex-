import { afterEach, describe, expect, it, vi } from 'vitest';

const jsonResponse = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: { 'Content-Type': 'application/json' },
});

describe('apiFetch', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  it('uses one refresh for parallel 401 responses so token rotation does not look like theft', async () => {
    let refreshCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const headers = new Headers(init?.headers);
      if (url.endsWith('/auth/refresh')) {
        refreshCalls += 1;
        await new Promise((resolve) => setTimeout(resolve, 5));
        return jsonResponse({ accessToken: 'fresh-token' });
      }
      if (headers.get('Authorization') !== 'Bearer fresh-token') {
        return jsonResponse({ detail: 'expired' }, 401);
      }
      return jsonResponse({ url });
    });
    vi.stubGlobal('fetch', fetchMock);

    const { apiFetch } = await import('./httpClient');
    const [first, second] = await Promise.all([
      apiFetch<{ url: string }>('/api/v1/admin/groups'),
      apiFetch<{ url: string }>('/api/v1/auth/me'),
    ]);

    expect(refreshCalls).toBe(1);
    expect(first.url).toContain('/admin/groups');
    expect(second.url).toContain('/auth/me');
  });

  it('sends Accept-Language and only sets a JSON content type when there is a body', async () => {
    const seen: Headers[] = [];
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      seen.push(new Headers(init?.headers));
      return jsonResponse({ ok: true });
    });
    vi.stubGlobal('fetch', fetchMock);

    const { apiFetch } = await import('./httpClient');
    await apiFetch('/api/v1/auth/me');
    await apiFetch('/api/v1/auth/change-password', {
      method: 'POST',
      body: JSON.stringify({ currentPassword: 'old', newPassword: 'new' }),
    });

    expect(seen[0].get('Accept-Language')).toBe('ru-RU');
    expect(seen[0].has('Content-Type')).toBe(false);
    expect(seen[1].get('Content-Type')).toBe('application/json');
  });

  it('treats an empty successful response as undefined instead of trying to parse JSON', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 204 })));

    const { apiFetch } = await import('./httpClient');
    await expect(apiFetch<void>('/api/v1/auth/logout', { method: 'POST' })).resolves.toBeUndefined();
  });
});

describe('refresh after the access token expired', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  it('sends the refresh request without the stale bearer token', async () => {
    const seen: (string | null)[] = [];
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const headers = new Headers(init?.headers);
      if (url.endsWith('/auth/refresh')) {
        seen.push(headers.get('Authorization'));
        return jsonResponse({ accessToken: 'fresh-token' });
      }
      if (headers.get('Authorization') !== 'Bearer fresh-token') {
        return jsonResponse({ detail: 'expired' }, 401);
      }
      return jsonResponse({ ok: true });
    });
    vi.stubGlobal('fetch', fetchMock);

    const { apiFetch, setAccessToken } = await import('./httpClient');
    // Истёкший access-токен остаётся в памяти: именно он раньше попадал
    // в заголовок refresh и шлюз отвечал 401 на сам запрос обновления.
    setAccessToken('expired-token');
    await apiFetch('/api/v1/calendar/events');

    expect(seen).toEqual([null]);
    expect(seen.filter((value) => value === 'Bearer expired-token')).toHaveLength(0);
  });

  it('reports a dead session instead of failing silently', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => (
      String(input).includes('/auth/refresh')
        ? jsonResponse({ detail: 'invalid' }, 401)
        : jsonResponse({ detail: 'expired' }, 401)
    )));

    const { apiFetch, setSessionExpiredHandler, setAccessToken } = await import('./httpClient');
    const expired = vi.fn();
    setSessionExpiredHandler(expired);
    setAccessToken('expired-token');

    await expect(apiFetch('/api/v1/calendar/events')).rejects.toMatchObject({ status: 401 });
    expect(expired).toHaveBeenCalledTimes(1);
    setSessionExpiredHandler(null);
  });
});
