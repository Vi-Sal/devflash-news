export interface Story {
  id: number;
  slug: string;
  title: string;
  summary: string | null;
  originalUrl: string;
  imageAlt?: string | null;
  imageAttribution?: string | null;
  imageStatus?: string;
  imageSource?: string | null;
  imageUrl: string | null;
  source: string;
  author: string | null;
  publishedAt: string;
  minutes: number;
  featured: boolean;
  categories: string[];
  topics: string[];
}
export interface Page {
  data: Story[];
  nextCursor: string | null;
  hasMore: boolean;
}
export interface Term {
  slug: string;
  name: string;
  description: string;
  icon?: string;
  accent_color?: string;
}
export interface Stats {
  date: string;
  total: number;
  minutes: number;
  categories: { slug: string; count: number; releases?: number }[];
}
export interface Trend {
  slug: string;
  name: string;
  stories: number;
  publishers: number;
}
export interface Site {
  siteTitle: string;
  tagline: string;
  youtubeUrl: string;
  timezone: string;
}
export interface Detail {
  story: Story;
  enrichment: Record<string, string> | null;
  security: Record<string, string> | null;
}
export type Filters = {
  category?: string;
  topic?: string;
  q?: string;
  from?: string;
  to?: string;
  sort?: string;
  cursor?: string;
  limit?: string;
};
export const defaultCategories: Term[] = [
  { slug: 'ai', name: 'AI', description: 'Models, research, and new ways to work.' },
  {
    slug: 'security',
    name: 'Security',
    description: 'Advisories, disclosures, and practical fixes.',
  },
  {
    slug: 'new-tech',
    name: 'New Tech',
    description: 'Hardware, platforms, and emerging technology.',
  },
  {
    slug: 'developer',
    name: 'Developer',
    description: 'Languages, open source, and engineering tools.',
  },
  {
    slug: 'fintech',
    name: 'Fintech',
    description: 'Payments, banking, and financial infrastructure.',
  },
];
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}
export async function request<T>(
  path: string,
  params: Filters = {},
  signal?: AbortSignal,
): Promise<T> {
  if (__DEMO__) return (await import('./sample')).sampleResponse(path, params) as T;
  const query = new URLSearchParams(Object.entries(params).filter(([, v]) => v));
  const response = await fetch('/api/v1' + path + (query.size ? '?' + query : ''), {
    signal,
    headers: { Accept: 'application/json' },
  });
  if (!response.ok)
    throw new ApiError(
      response.status,
      response.status === 404 ? 'This page is unavailable.' : 'The newsroom could not be reached.',
    );
  return response.json();
}
export async function data<T>(path: string, signal?: AbortSignal): Promise<T> {
  return (await request<{ data: T }>(path, {}, signal)).data;
}
export function event(slug: string, type: 'view' | 'share') {
  if (!__DEMO__)
    void fetch('/api/v1/articles/' + encodeURIComponent(slug) + '/events', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ type }),
      keepalive: true,
    }).catch(() => {});
}
export function safeUrl(
  value: string | null | undefined,
  context: 'external' | 'publisher' | 'image' = 'external',
) {
  if (!value) return undefined;
  if (context === 'image' && /^\/art\/[a-z-]+\.jpg$/.test(value)) return value;
  try {
    const url = new URL(value);
    const protocolAllowed =
      url.protocol === 'https:' || (context === 'publisher' && url.protocol === 'http:');
    return protocolAllowed && !url.username && !url.password ? url.href : undefined;
  } catch {
    return undefined;
  }
}
export function art(story: Story) {
  const slug = story.categories[0];
  return '/art/' + (defaultCategories.some((c) => c.slug === slug) ? slug : 'new-tech') + '.jpg';
}
