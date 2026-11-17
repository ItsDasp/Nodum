const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';

function getAuthHeaders(): HeadersInit {
  const token = localStorage.getItem('nodum-token');
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  return headers;
}

async function request<T>(path: string, method: string, data?: unknown): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method,
    headers: getAuthHeaders(),
    body: data === undefined ? undefined : JSON.stringify(data),
  });

  if (response.status === 401 && path !== '/auth/login') {
    localStorage.removeItem('nodum-token');
    if (window.location.pathname !== '/login') window.location.assign('/login');
  }
  if (!response.ok) {
    const detail = await response.text().catch(() => '');
    let message = detail;
    try {
      const parsed = JSON.parse(detail) as { message?: string; errors?: string[] };
      message = parsed.errors?.join(' ') || parsed.message || detail;
    } catch { /* The server may return a plain text error. */ }
    throw new Error(message || `Solicitud fallida (${response.status})`);
  }
  if (response.status === 204 || response.headers.get('content-length') === '0') return undefined as T;
  const body = await response.text();
  if (!body) return undefined as T;
  try { return JSON.parse(body) as T; } catch { return body as T; }
}

export const get = <T>(path: string) => request<T>(path, 'GET');
export const post = <T>(path: string, data?: unknown) => request<T>(path, 'POST', data);
export const put = <T>(path: string, data?: unknown) => request<T>(path, 'PUT', data);
export const del = <T>(path: string) => request<T>(path, 'DELETE');
