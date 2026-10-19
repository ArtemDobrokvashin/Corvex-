/**
 * Единый HTTP-клиент к web-gateway. Access-токен живёт только в памяти (Zustand),
 * refresh — HttpOnly cookie, поэтому каждый запрос идёт с credentials: 'include'.
 * На 401 выполняется один прозрачный refresh и повтор запроса.
 */
let accessToken: string | null = null;
export const setAccessToken = (t: string | null) => { accessToken = t; };

async function raw(path: string, init: RequestInit = {}): Promise<Response> {
  return fetch(path, {
    ...init,
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      ...(init.headers ?? {}),
    },
  });
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  let res = await raw(path, init);
  if (res.status === 401 && !path.includes('/auth/refresh')) {
    const refresh = await raw('/api/v1/auth/refresh', { method: 'POST' });
    if (refresh.ok) {
      const tokens = (await refresh.json()) as { accessToken: string };
      setAccessToken(tokens.accessToken);
      res = await raw(path, init);
    }
  }
  if (!res.ok) throw new Error(`HTTP ${res.status} ${path}`);
  return (res.status === 204 ? undefined : await res.json()) as T;
}
