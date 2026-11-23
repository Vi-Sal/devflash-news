import { expect, test } from '@playwright/test';

test('opens the reference edition with the lead story and sidebar', async ({ page }, info) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Tech news in a flash.' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Today at a Glance' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Trending', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'DevFlash on YouTube' })).toBeVisible();
  const firstStory = page.getByRole('article').first();
  await expect(firstStory.getByRole('heading')).toBeVisible();
  await expect(firstStory.locator('img')).toHaveJSProperty('complete', true);
  await expect(firstStory.locator('img')).not.toHaveJSProperty('naturalWidth', 0);
  await page
    .locator('article img')
    .evaluateAll((images) => Promise.all(images.map((img) => (img as HTMLImageElement).decode())));
  await page.screenshot({ path: info.outputPath('desktop-home.png') });
});

test('opens an article, saves it, and keeps it after a reload', async ({ page }) => {
  await page.goto('/');
  const firstStory = page.getByRole('article').first();
  const title = (await firstStory.getByRole('heading').innerText()).trim();
  await firstStory.getByRole('button', { name: 'Save article', exact: true }).click();
  await firstStory.getByRole('link', { name: title, exact: true }).click();
  await expect(page).toHaveURL(/\/article\/[^/]+$/);
  await expect(page.getByRole('heading', { name: title, exact: true, level: 1 })).toBeVisible();
  await page
    .getByRole('navigation', { name: 'Main navigation' })
    .getByRole('link', { name: /^Saved/ })
    .click();
  await page.reload();
  await expect(page.getByRole('article').getByRole('heading', { name: title })).toBeVisible();
  await page.getByRole('article').getByRole('button', { name: 'Remove bookmark' }).click();
  await expect(page.getByRole('article')).toHaveCount(0);
});

test('selects a category and searches for a story', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('link', { name: 'Security', exact: true }).first().click();
  await expect(page).toHaveURL(/\/category\/security$/);
  await expect(page.getByRole('article').first()).toBeVisible();
  const input = page.getByPlaceholder(/search tech news/i);
  await input.fill('Apple');
  await input.press('Enter');
  await expect(page).toHaveURL(/\/search\?q=Apple/i);
  await expect(page.getByRole('article').first()).toContainText(/Apple/i);
});

test('the 320px reader fits the viewport and reaches saved stories', async ({ page }, info) => {
  await page.setViewportSize({ width: 320, height: 740 });
  await page.goto('/');
  await expect(page.getByRole('article').first()).toBeVisible();
  const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
  expect(scrollWidth).toBeLessThanOrEqual(320);
  await page.screenshot({ path: info.outputPath('mobile-home.png') });
  await page
    .getByRole('navigation', { name: 'Main navigation' })
    .getByRole('link', { name: /^Saved/ })
    .click();
  await expect(page).toHaveURL(/\/saved$/);
});

test('loads additional archive pages and opens an older story', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 480 });
  await page.goto('/');
  const first = page.getByRole('heading', { name: /Apple opens its on-device AI model/ });
  await expect(first).toBeVisible();
  const older = page.getByRole('heading', { name: /TypeScript 5\.8/ });
  await expect(older).toHaveCount(0);
  await page.getByRole('article').last().scrollIntoViewIfNeeded();
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
  await expect(older).toBeVisible();
  await older.getByRole('link').click();
  await expect(page).toHaveURL(/\/article\/typescript-58$/);
  await page.goBack();
  await expect(older).toBeVisible();
});

test('copies the canonical article URL when native sharing is unavailable', async ({
  context,
  page,
}) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.addInitScript(() => Object.defineProperty(navigator, 'share', { value: undefined }));
  await page.goto('/');
  const lead = page.getByRole('article').first();
  const articlePath = await lead.getByRole('heading').getByRole('link').getAttribute('href');
  expect(articlePath).toMatch(/^\/article\//);
  await lead.getByRole('button', { name: 'Share article', exact: true }).click();
  await expect
    .poll(() => page.evaluate(() => navigator.clipboard.readText()))
    .toBe(new URL(articlePath!, page.url()).href);
});

test('remembers Midnight Glacier and follows system appearance when selected', async ({ page }) => {
  await page.goto('/');
  const html = page.locator('html');
  const chooser = page.getByRole('button', { name: 'Choose theme' });
  await expect(html).toHaveAttribute('data-theme', 'light');
  await chooser.click();
  await page.getByRole('dialog').getByRole('button', { name: 'Midnight Glacier' }).click();
  await expect(html).toHaveAttribute('data-theme', 'dark');
  await page.reload();
  await expect(html).toHaveAttribute('data-theme', 'dark');
  await page.emulateMedia({ colorScheme: 'light' });
  await chooser.click();
  await page.getByRole('dialog').getByRole('button', { name: 'System preference' }).click();
  await expect(html).toHaveAttribute('data-theme', 'light');
  await page.emulateMedia({ colorScheme: 'dark' });
  await expect(html).toHaveAttribute('data-theme', 'dark');
});

test('explores a topic, opens the daily briefing, and follows source attribution', async ({
  page,
}) => {
  await page.goto('/explore');
  await page.getByRole('link', { name: 'Apple Intelligence', exact: true }).click();
  await expect(page).toHaveURL(/\/topics\/apple$/);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Apple Intelligence');
  await expect(page.getByRole('article')).toHaveCount(1);
  await page.getByRole('link', { name: 'Today at a Glance' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Daily briefing');
  const source = page
    .getByRole('article')
    .first()
    .getByRole('link', { name: 'Open original source' });
  await expect(source).toHaveAttribute('href', /^https:\/\/machinelearning\.apple\.com\//);
  await expect(source).toHaveAttribute('target', '_blank');
  await page
    .getByRole('navigation', { name: 'Footer navigation' })
    .getByRole('link', { name: 'sources' })
    .click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Source directory');
});
