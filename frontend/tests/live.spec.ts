import { expect, test } from '@playwright/test';

// Opt-in smoke checks for a locally running Spring service and its isolated PostgreSQL database.
test('reads the stored catalog and fetches the same feed through the browser', async ({
  page,
  request,
}) => {
  const health = await request.get('/api/v1/health');
  expect(health.ok()).toBe(true);
  expect(await health.json()).toMatchObject({ data: { status: 'UP' } });
  const feed = await request.get('/api/v1/articles');
  expect(feed.ok()).toBe(true);
  const { data } = (await feed.json()) as { data: { title: string; slug: string }[] };
  await page.goto('/');
  if (data.length) {
    const lead = page.getByRole('article').first();
    await expect(lead.getByRole('heading')).toHaveText(data[0].title);
    await lead.getByRole('heading').getByRole('link').click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(data[0].title);
    await expect(page).toHaveURL((url) => url.pathname === '/article/' + data[0].slug);
  } else {
    await expect(page.getByRole('heading', { name: 'No stories here yet' })).toBeVisible();
  }
});

test('signs in to Spring Security, reads the newsroom and signs out', async ({ page }) => {
  const user = process.env.DEVFLASH_TEST_ADMIN_USERNAME;
  const password = process.env.DEVFLASH_TEST_ADMIN_PASSWORD;
  test.skip(
    !user || !password,
    'Supply administrator credentials generated for this isolated test deployment.',
  );
  await page.goto('/admin');
  await expect(page).toHaveURL(/\/admin\/login$/);
  await page.getByLabel('Username').fill(user!);
  await page.getByLabel('Password').fill(password!);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Overview', exact: true })).toBeVisible();
  await page
    .getByRole('navigation', { name: 'Newsroom' })
    .getByRole('link', { name: 'Audit log' })
    .click();
  await expect(page.getByRole('heading', { name: 'Audit log' })).toBeVisible();
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('heading', { name: 'Newsroom sign in' })).toBeVisible();
});
