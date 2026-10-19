/**
 * Единый HTTP-клиент к web-gateway. Access-токен живёт только в памяти,
 * refresh — HttpOnly cookie, поэтому каждый запрос идёт с credentials: 'include'.
 * На 401 выполняется один прозрачный refresh и повтор запроса (кроме самих
 * auth-эндпоинтов входа: там 401 — содержательный ответ, а не истёкший access).
 */
let accessToken: string | null = null;
export const setAccessToken = (t: string | null) => { accessToken = t; };
export const getAccessToken = () => accessToken;

/** Ошибка API с HTTP-статусом и detail из Problem Details (RFC 7807). */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly detail: string,
    public readonly body: unknown = null,
  ) {
    super(detail || `HTTP ${status}`);
    this.name = 'ApiError';
  }
}

const NO_AUTO_REFRESH = ['/auth/login', '/auth/refresh', '/auth/mfa/', '/auth/logout'];

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

async function toApiError(res: Response, path: string): Promise<ApiError> {
  let detail = '';
  let body: unknown = null;
  try {
    body = await res.json();
    const b = body as { detail?: string; message?: string };
    detail = b.detail || b.message || '';
  } catch {
    // тело не JSON — detail останется пустым, статус несёт смысл
  }
  return new ApiError(res.status, detail || `HTTP ${res.status} ${path}`, body);
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  let res = await raw(path, init);
  if (res.status === 401 && !NO_AUTO_REFRESH.some((p) => path.includes(p))) {
    const refresh = await raw('/api/v1/auth/refresh', { method: 'POST' });
    if (refresh.ok) {
      const tokens = (await refresh.json()) as { accessToken: string };
      setAccessToken(tokens.accessToken);
      res = await raw(path, init);
    }
  }
  if (!res.ok) throw await toApiError(res, path);
  return (res.status === 204 ? undefined : await res.json()) as T;
}
