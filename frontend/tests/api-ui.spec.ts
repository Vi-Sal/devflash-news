import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import type { Route } from '@playwright/test';

const json = (route: Route, body: unknown, status = 200) => route.fulfill({ json: body, status });
function fixtureStory(id: number) {
  return {
    id,
    slug: 'fixture-' + id,
    title: 'Fixture story ' + id,
    summary: 'This article exists only in a browser test.',
    originalUrl: 'https://example.org/test/' + id,
    source: 'Fixture feed',
    author: null,
    imageUrl: null,
    publishedAt: '2025-02-05T09:00:00Z',
    minutes: 1,
    featured: false,
    categories: ['developer'],
    topics: [],
  };
}

function sidebarFixture(route: Route) {
  const path = new URL(route.request().url()).pathname;
  if (path.endsWith('/stats/today'))
    return json(route, { data: { date: '2025-02-05', categories: [], minutes: 0, total: 0 } });
  if (path.endsWith('/site'))
    return json(route, {
      data: {
        siteTitle: 'DevFlash',
        tagline: 'Tech news in a flash.',
        timezone: 'Asia/Phnom_Penh',
        youtubeUrl: '',
      },
    });
  return json(route, { data: [] });
}

test('preserves the first page while appending the cursor page when the reader scrolls', async ({
  page,
}) => {
  const firstPage = Array.from({ length: 10 }, (_, i) => fixtureStory(i + 1));
  let nextPageRequests = 0;
  let releaseFeed!: () => void;
  const ready = new Promise<void>((resolve) => {
    releaseFeed = resolve;
  });
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === '/api/v1/articles') {
      await ready;
      if (url.searchParams.has('cursor')) {
        expect(url.searchParams.get('cursor')).toBe('test-page-two');
        nextPageRequests++;
        return json(route, {
          data: [fixtureStory(10), fixtureStory(11)],
          nextCursor: null,
          hasMore: false,
        });
      }
      return json(route, { data: firstPage, nextCursor: 'test-page-two', hasMore: true });
    }
    return sidebarFixture(route);
  });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Tech news in a flash.' })).toBeVisible();
  await expect(page.getByRole('status', { name: 'Loading articles' })).toBeVisible();
  await expect(page.getByRole('article')).toHaveCount(0);
  releaseFeed();
  await expect(page.getByRole('article')).toHaveCount(10);
  await page.getByRole('article').last().scrollIntoViewIfNeeded();
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
  await expect(page.getByRole('article')).toHaveCount(11);
  expect(nextPageRequests).toBe(1);
  await expect(page.getByRole('article').first()).toContainText('Fixture story 1');
  await expect(page.getByRole('article').last()).toContainText('Fixture story 11');
});

test('admin sign-in returns to the protected page and sign-out revokes access', async ({
  page,
}) => {
  let signedIn = false;
  const csrfToken = randomUUID();
  const password = randomUUID();
  await page.route('**/api/v1/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/auth/me'))
      return signedIn
        ? json(route, { data: { username: 'editor' } })
        : json(route, { error: { message: 'Sign in required' } }, 401);
    if (path.endsWith('/auth/csrf'))
      return json(route, { token: csrfToken, headerName: 'X-CSRF-TOKEN' });
    if (path.endsWith('/auth/login') || path.endsWith('/auth/logout')) {
      expect(route.request().method()).toBe('POST');
      expect(route.request().headers()['x-csrf-token']).toBe(csrfToken);
      if (path.endsWith('/auth/login')) {
        const credentials = new URLSearchParams(route.request().postData() ?? '');
        expect(credentials.get('username')).toBe('editor');
        expect(credentials.get('password')).toBe(password);
        signedIn = true;
      } else signedIn = false;
      return json(route, { data: { success: true } });
    }
    if (path.includes('/admin/') && !signedIn)
      return json(route, { error: { message: 'Sign in required' } }, 401);
    return json(route, { data: [] });
  });
  await page.goto('/admin/audit-logs');
  await expect(page).toHaveURL(/\/admin\/login$/);
  await page.getByLabel('Username').fill('editor');
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page).toHaveURL(/\/admin\/audit-logs$/);
  await expect(page.getByRole('heading', { name: 'Newsroom', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/admin\/login$/);
  await expect(page.getByRole('heading', { name: 'Newsroom sign in' })).toBeVisible();
});

test('recovers after a failed feed and keeps new stories behind an explicit refresh', async ({
  page,
}) => {
  let online = false;
  let hasUpdate = false;
  await page.route('**/api/v1/**', (route) => {
    const url = new URL(route.request().url());
    if (url.pathname !== '/api/v1/articles') return sidebarFixture(route);
    if (!online) return json(route, { error: { message: 'Temporary test outage' } }, 503);
    const headRequest = url.searchParams.get('limit') === '1';
    const rows = headRequest || hasUpdate ? [fixtureStory(12), fixtureStory(1)] : [fixtureStory(1)];
    if (headRequest) hasUpdate = true;
    return json(route, {
      data: headRequest ? rows.slice(0, 1) : rows,
      nextCursor: null,
      hasMore: false,
    });
  });
  await page.goto('/');
  await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible({ timeout: 15_000 });
  online = true;
  await page.getByRole('button', { name: 'Try again' }).click();
  const refresh = page.getByRole('button', { name: /New stories available/ });
  await expect(refresh).toBeVisible();
  await expect(page.getByRole('article')).toHaveCount(1);
  await expect(page.getByRole('article')).toContainText('Fixture story 1');
  await refresh.click();
  await expect(page.getByRole('article')).toHaveCount(2);
  await expect(page.getByRole('article').first()).toContainText('Fixture story 12');
  await expect(refresh).toHaveCount(0);
});

test('renders each article image and replaces only a broken image', async ({ page }) => {
  const rows = [1, 2, 3].map(id => ({
    ...fixtureStory(id), imageUrl:'https://images.example.org/'+id+'.jpg', imageAlt:'Diagram '+id
  }));
  await page.route('https://images.example.org/**', route =>
    route.request().url().endsWith('3.jpg') ? route.abort() :
      route.fulfill({path:'public/art/developer.jpg',contentType:'image/jpeg'}));
  await page.route('**/api/v1/**', route =>
    new URL(route.request().url()).pathname.endsWith('/articles')
      ? json(route,{data:rows,nextCursor:null,hasMore:false}) : sidebarFixture(route));
  await page.goto('/');
  for(const id of [1,2]) await expect(page.getByAltText('Diagram '+id)).toHaveAttribute('src',rows[id-1].imageUrl);
  const third=page.locator('article img').nth(2);
  await third.scrollIntoViewIfNeeded();
  await expect(third).toHaveAttribute('src','/art/developer.jpg');
});
