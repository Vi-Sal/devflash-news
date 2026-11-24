// Explicit demo build only. Historical examples retain original calendar dates and publisher links.
import { defaultCategories, ApiError, type Story, type Filters } from './news';
type Entry = [string, string, string, string, string, string, string[]];
const entries: Entry[] = [
  [
    'apple-on-device-model',
    'Apple opens its on-device AI model to developers',
    'A new framework gives apps access to the language model behind Apple Intelligence, right on the device.',
    'Apple',
    '2025-06-09',
    'https://machinelearning.apple.com/research/apple-foundation-models-2025-updates',
    ['new-tech', 'ai'],
  ],
  [
    'github-passkeys',
    'GitHub brings passwordless sign-in to every account',
    'Developers can use a passkey to sign in to GitHub with their device, simplifying two-factor authentication.',
    'GitHub',
    '2023-09-21',
    'https://github.blog/changelog/2023-09-21-passkeys-are-generally-available/',
    ['security'],
  ],
  [
    'codex-cloud',
    'OpenAI introduces a cloud-based coding agent',
    'Codex can work on software tasks in parallel, each in its own environment with access to the project repository.',
    'OpenAI',
    '2025-05-16',
    'https://openai.com/index/introducing-codex/',
    ['ai', 'developer'],
  ],
  [
    'stripe-treasury',
    'Stripe expands stablecoin accounts to 101 countries',
    'Businesses can hold a dollar-denominated balance and move funds across supported banking and blockchain networks.',
    'Stripe',
    '2025-05-20',
    'https://stripe.com/blog/introducing-stablecoins-for-treasury',
    ['fintech'],
  ],
  [
    'go-type-aliases',
    'Go 1.24 brings generic type aliases to the language',
    'The release also changes the runtime map implementation and adds a way to declare tool dependencies.',
    'The Go Blog',
    '2025-02-11',
    'https://go.dev/blog/go1.24',
    ['developer'],
  ],
  [
    'typescript-58',
    'TypeScript 5.8 adds finer checks for return expressions',
    'The compiler checks conditional branches individually, helping catch mistakes that previously slipped through.',
    'Microsoft',
    '2025-02-28',
    'https://devblogs.microsoft.com/typescript/announcing-typescript-5-8/',
    ['developer'],
  ],
  [
    'react-19',
    'React 19 ships with Actions and new form tools',
    'The stable release introduces APIs to manage pending states and optimistic updates in interactive applications.',
    'React',
    '2024-12-05',
    'https://react.dev/blog/2024/12/05/react-19',
    ['developer'],
  ],
];
const names: Record<string, string> = {
  apple: 'Apple Intelligence',
  github: 'Passkeys',
  codex: 'Codex',
  stripe: 'Stablecoins',
  go: 'Go',
  typescript: 'TypeScript',
  react: 'React',
};
const archive: Story[] = entries.map(
  ([slug, title, summary, source, date, originalUrl, categories], i) => ({
    id: i + 1,
    slug,
    title,
    summary,
    source,
    originalUrl,
    publishedAt: date + 'T12:00:00Z',
    categories,
    author: null,
    minutes: 1,
    topics: [slug.split('-')[0]],
    featured: i === 0,
    imageUrl: i === 0 ? '/art/apple.jpg' : null,
  }),
);
export function sampleResponse(path: string, f: Filters) {
  const topics = archive.map((a) => ({
    slug: a.topics[0],
    name: names[a.topics[0]],
    stories: archive.filter((item) => item.topics.includes(a.topics[0])).length,
    description: 'From the DevFlash sample archive.',
  }));
  const stats = {
    date: new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Phnom_Penh' }),
    total: archive.length,
    minutes: 5,
    categories: defaultCategories.map((c) => ({
      slug: c.slug,
      count: archive.filter((a) => a.categories.includes(c.slug)).length,
      releases: archive.filter(
        (a) => a.categories.includes(c.slug) && /opens|introduces/.test(a.title),
      ).length,
    })),
  };
  const item = path.split('/')[2];
  if (path === '/site')
    return {
      data: {
        siteTitle: 'DevFlash',
        tagline: 'Tech news in a flash.',
        youtubeUrl: '',
        timezone: 'Asia/Phnom_Penh',
      },
    };
  if (path === '/featured') return { data: archive.filter((story) => story.featured).slice(0, 1) };
  if (path === '/security/highlights')
    return { data: archive.filter((story) => story.categories.includes('security')).slice(0, 3) };
  if (path === '/articles' || path === '/search' || path === '/security') {
    const matching = archive.filter(
      (a) =>
        (path !== '/security' ||
          (a.categories.includes('security') &&
            (!f.severity || f.severity === 'unknown') &&
            !f.cve &&
            !f.affected &&
            !f.incidentType &&
            !f.patch &&
            (!f.verification || f.verification === 'Reported but unverified'))) &&
        String(a.id) !== f.exclude &&
        (!f.category || a.categories.includes(f.category)) &&
        (!f.topic || a.topics.includes(f.topic)) &&
        (!f.from || a.publishedAt.slice(0, 10) >= f.from) &&
        (!f.to || a.publishedAt.slice(0, 10) <= f.to) &&
        (!f.q ||
          [a.title, a.summary, a.source, ...a.topics, ...a.topics.map((t) => names[t])]
            .join(' ')
            .toLowerCase()
            .includes(f.q.toLowerCase())),
    );
    if (f.following) {
      const followed = new Set(f.following.split(','));
      matching.sort(
        (a, b) =>
          Number(b.topics.some((topic) => followed.has(topic))) -
          Number(a.topics.some((topic) => followed.has(topic))),
      );
    }
    if (f.feed === 'latest' || (path === '/search' && f.sort !== 'relevance'))
      matching.sort((a, b) => b.publishedAt.localeCompare(a.publishedAt));
    const offset = Number(f.cursor || 0),
      size = Number(f.limit || 3),
      more = offset + size < matching.length;
    return {
      data: matching.slice(offset, offset + size),
      security:
        path === '/security'
          ? Object.fromEntries(
              matching.map((a) => [a.slug, { verification: 'Reported but unverified' }]),
            )
          : undefined,
      nextCursor: more ? String(offset + size) : null,
      hasMore: more,
    };
  }
  if (path.startsWith('/articles/')) {
    const a = archive.find((a) => a.slug === item);
    if (!a) throw new ApiError(404, 'Article unavailable');
    if (path.endsWith('/related'))
      return {
        data: archive
          .filter((x) => x.id !== a.id && x.categories.some((c) => a.categories.includes(c)))
          .slice(0, 3),
      };
    return { data: { story: a, enrichment: null, security: null } };
  }
  if (path === '/categories') return { data: defaultCategories };
  if (path === '/topics') return { data: topics };
  if (path.startsWith('/topics/') || path.startsWith('/categories/')) {
    const t = (path.startsWith('/topics/') ? topics : defaultCategories).find(
      (t) => t.slug === item,
    );
    if (!t) throw new ApiError(404, 'Section unavailable');
    return { data: t };
  }
  if (path === '/trending')
    return { data: topics.map((t) => ({ ...t, stories: 1, publishers: 1 })) };
  if (path === '/sources')
    return {
      data: archive.map((a) => ({ name: a.source, website_url: new URL(a.originalUrl).origin })),
    };
  const archiveDays = [...new Set(archive.map((a) => a.publishedAt.slice(0, 10)))].sort().reverse();
  if (path === '/daily/archive') return { data: archiveDays };
  if (path === '/daily') return { data: { date: stats.date, articles: archive.slice(0, 5) } };
  if (path.startsWith('/daily/'))
    return {
      data: { date: item, articles: archive.filter((a) => a.publishedAt.startsWith(item)) },
    };
  if (path === '/stats/today') return { data: stats };
  throw new ApiError(404, 'Page unavailable');
}
