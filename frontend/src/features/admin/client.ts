import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

export class AdminError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

export interface ArticleRow {
  id: number;
  title: string;
  slug: string;
  summary: string | null;
  original_title: string;
  original_url: string;
  source_description: string | null;
  published_at: string;
  source_name: string;
  processing_status: string;
  publication_status: 'DRAFT' | 'PUBLISHED';
  featured: boolean;
  category_ids: string;
  topic_ids: string;
}
export interface Source {
  id: number;
  name: string;
  website_url: string;
  feed_url: string;
  source_type: string;
  enabled: boolean;
  last_fetched_at: string | null;
  last_success_at: string | null;
  last_error: string | null;
}
export interface Taxon {
  id: number;
  name: string;
  slug: string;
  description: string;
  articles: number;
  icon?: string;
  accent_color?: string;
  enabled?: boolean;
}
export interface Run {
  id: number;
  source_name: string;
  source_id: number;
  started_at: string;
  finished_at: string | null;
  status: string;
  duration_seconds: number;
  fetched_count: number;
  inserted_count: number;
  duplicate_count: number;
  error_count: number;
  error_message: string | null;
}
export interface Audit {
  id: number;
  created_at: string;
  username: string;
  action: string;
  entity_type: string;
  entity_id?: string;
}
export interface Dashboard {
  articles: number;
  collected_today: number;
  active_sources: number;
  failed_sources: number;
  awaiting_processing: number;
  ai_calls_today: number;
  ai_input_tokens_today?: number;
  ai_output_tokens_today?: number;
  ai_estimated_cost_today?: number | null;
  last_success: string | null;
  views: number;
  shares: number;
  distribution: { id: number; name: string; articles: number }[];
  recent_runs: Run[];
}
export interface ResponseData<T> {
  data: T;
  hasMore?: boolean;
}

const base = '/api/v1/admin';
async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(base + path, {
    credentials: 'same-origin',
    cache: 'no-store',
    ...init,
  });
  const json = response.headers.get('content-type')?.includes('application/json');
  const body = json ? await response.json() : null;
  if (!response.ok || (!json && response.status !== 204)) {
    if (response.status === 401 && !path.startsWith('/auth/'))
      window.dispatchEvent(new Event('devflash-session-expired'));
    throw new AdminError(
      body?.error?.message || 'The newsroom API is unavailable. Check the server connection.',
      response.status,
    );
  }
  return body as T;
}

export function useAdminData<T>(path: string) {
  return useQuery({
    queryKey: ['newsroom', path],
    queryFn: ({ signal }) => request<ResponseData<T>>(path, { signal }),
    retry: false,
    staleTime: 0,
  });
}

export async function change<T>(method: string, path: string, body?: unknown): Promise<T> {
  const { token, headerName } = await request<{ token: string; headerName: string }>('/auth/csrf');
  const form = body instanceof URLSearchParams;
  return request<T>(path, {
    method,
    headers: {
      [headerName]: token,
      'Content-Type': form ? 'application/x-www-form-urlencoded' : 'application/json',
    },
    body: form ? body : body === undefined ? undefined : JSON.stringify(body),
  });
}

export function useAdminAction() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (action: { method: string; path: string; body?: unknown }) =>
      change<ResponseData<unknown>>(action.method, action.path, action.body),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ['newsroom'] });
    },
  });
}

export function timestamp(value: string | null | undefined) {
  return value
    ? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(
        new Date(value),
      )
    : '—';
}
