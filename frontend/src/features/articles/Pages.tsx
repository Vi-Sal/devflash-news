import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  Search as SearchIcon,
  Bookmark,
  Share2,
  ExternalLink,
  CalendarDays,
  TrendingUp,
  Newspaper,
  X,
} from 'lucide-react';
import { format } from 'date-fns';
import Feed from './Feed';
import NewsCard from './NewsCard';
import Sidebar from '../../components/Sidebar';
import {
  CategoryIcon,
  Empty,
  Failure,
  Intro,
  Skeleton,
  useCategories,
  useData,
} from '../../components/common';
import { safeUrl, type Stats, type Story, type Trend, type Term } from '../../services/news';
import { read, useReader } from '../../app/reader';
export function Explore() {
  const cats = useCategories(),
    topics = useData<Term[]>('/topics');
  return (
    <div className="columns">
      <div>
        <Intro title="Explore" sub="Discover the stories behind the technologies you follow." />
        <div className="tile-grid">
          {cats.map((c) => (
            <Link className="glass topic-tile" to={'/category/' + c.slug} key={c.slug}>
              <CategoryIcon slug={c.icon || c.slug} size={24} />
              <h2>{c.name}</h2>
              <p>{c.description}</p>
            </Link>
          ))}
        </div>
        <h2 className="section-title">Browse topics</h2>
        <div className="tag-row">
          {topics.data?.map((t) => (
            <Link className="pill" to={'/topics/' + t.slug} key={t.slug}>
              {t.name}
            </Link>
          ))}
        </div>
        <div className="quick-links">
          <Link to="/daily">
            <CalendarDays />
            Daily briefing
          </Link>
          <Link to="/trending">
            <TrendingUp />
            Trending topics
          </Link>
          <Link to="/sources">
            <Newspaper />
            Source directory
          </Link>
        </div>
      </div>
      <Sidebar />
    </div>
  );
}
export function Saved() {
  const { saved } = useReader();
  return (
    <div className="columns">
      <div>
        <Intro
          title={'Saved articles' + (saved.length ? ' (' + saved.length + ')' : '')}
          sub="Your reading list, saved in this browser."
        />
        {saved.length ? (
          saved.map((a) => <NewsCard key={a.id} story={a} />)
        ) : (
          <Empty title="Your reading list starts here">
            <Bookmark size={16} /> Save a story from the feed to read it later.{' '}
            <Link to="/">Browse stories</Link>
          </Empty>
        )}
      </div>
      <Sidebar />
    </div>
  );
}
export function SearchPage() {
  const [params, setParams] = useSearchParams(),
    term = params.get('q') || '',
    cats = useCategories();
  const [input, setInput] = useState(term);
  useEffect(() => {
    setInput(term);
  }, [term]);
  useEffect(() => {
    if (input.trim() === term) return;
    const id = setTimeout(
      () =>
        setParams(
          (p) => {
            const next = new URLSearchParams(p);
            if (input.trim()) next.set('q', input.trim());
            else next.delete('q');
            return next;
          },
          { replace: true },
        ),
      300,
    );
    return () => clearTimeout(id);
  }, [input, term, setParams]);
  const filters = Object.fromEntries(
    ['q', 'category', 'from', 'to', 'sort']
      .map((k) => [k, params.get(k) || undefined])
      .filter(([, v]) => v),
  );
  function change(k: string, v: string) {
    setParams((p) => {
      const n = new URLSearchParams(p);
      if (v) n.set(k, v);
      else n.delete(k);
      return n;
    });
  }
  return (
    <div className="columns">
      <div>
        <Intro title="Search" sub="Find coverage by title, topic, or publisher." />
        <div className="glass search-panel">
          <label className="search-input">
            <SearchIcon size={19} />
            <input
              aria-label="Search articles"
              placeholder="Search articles, topics, publishers…"
              value={input}
              maxLength={160}
              onChange={(e) => setInput(e.target.value)}
            />
            {input && (
              <button aria-label="Clear search" onClick={() => setInput('')}>
                <X size={16} />
              </button>
            )}
          </label>
          <div className="filters">
            <label>
              Category
              <select
                value={filters.category || ''}
                onChange={(e) => change('category', e.target.value)}
              >
                <option value="">All categories</option>
                {cats.map((c) => (
                  <option key={c.slug} value={c.slug}>
                    {c.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              From
              <input
                type="date"
                value={filters.from || ''}
                max={filters.to}
                onChange={(e) => change('from', e.target.value)}
              />
            </label>
            <label>
              Through
              <input
                type="date"
                min={filters.from}
                value={filters.to || ''}
                onChange={(e) => change('to', e.target.value)}
              />
            </label>
            <label>
              Sort
              <select
                value={filters.sort || 'newest'}
                onChange={(e) => change('sort', e.target.value)}
              >
                <option value="newest">Newest</option>
                <option value="relevance">Relevance</option>
              </select>
            </label>
          </div>
          {!term && read<string[]>('recent', []).length > 0 && (
            <div className="tag-row">
              Recent searches{' '}
              {read<string[]>('recent', []).map((t) => (
                <button className="badge" key={t} onClick={() => setInput(t)}>
                  {t}
                </button>
              ))}
            </div>
          )}
        </div>
        <Feed filters={filters} search />
      </div>
      <Sidebar />
    </div>
  );
}
export function Daily() {
  const day = useData<{ date: string; articles: Story[] }>('/daily'),
    stats = useData<Stats>('/stats/today'),
    { share } = useReader();
  if (day.isError) return <Failure retry={day.refetch} />;
  if (!day.data) return <Skeleton />;
  return (
    <div className="columns">
      <div>
        <Intro
          title="Daily briefing"
          sub={
            __DEMO__
              ? 'Highlights from the historical sample edition.'
              : format(new Date(day.data.date + 'T12:00:00'), 'EEEE, MMMM d, yyyy')
          }
        />
        <div className="brief-bar">
          <span>
            {stats.data?.total ?? '…'} stories · {stats.data?.minutes ?? '…'} min briefing
          </span>
          <button
            className="button"
            onClick={() => void share('/daily', 'DevFlash daily briefing')}
          >
            <Share2 size={16} />
            Share briefing
          </button>
        </div>
        {day.data.articles.length ? (
          day.data.articles.map((a) => <NewsCard key={a.id} story={a} />)
        ) : (
          <Empty title="Today’s briefing is clear">
            The first published stories of the day will appear here.
          </Empty>
        )}
      </div>
      <Sidebar />
    </div>
  );
}
export function TrendingPage() {
  const q = useData<Trend[]>('/trending');
  return (
    <div className="reading">
      <Intro
        title="Trending topics"
        sub={
          __DEMO__
            ? 'Topics featured in the sample edition.'
            : 'Recent coverage, distinct publishers, and reader activity.'
        }
      />
      {q.isPending ? (
        <Skeleton />
      ) : q.isError ? (
        <Failure retry={q.refetch} />
      ) : q.data?.length ? (
        <ol className="glass directory">
          {q.data.map((t, i) => (
            <li key={t.slug}>
              <Link to={'/topics/' + t.slug}>
                <span className="rank">{i + 1}</span>
                {t.name}
                <small>
                  {t.stories} stories · {t.publishers}{' '}
                  {Number(t.publishers) === 1 ? 'publisher' : 'publishers'}
                </small>
              </Link>
            </li>
          ))}
        </ol>
      ) : (
        <Empty title="No trending topics yet" />
      )}
    </div>
  );
}
export function Sources() {
  const q = useData<{ name: string; website_url: string }[]>('/sources');
  return (
    <div className="reading">
      <Intro
        title="Source directory"
        sub={
          __DEMO__
            ? 'Publishers represented in the archive.'
            : 'The publications and official feeds collected by DevFlash.'
        }
      />
      {q.isError ? (
        <Failure retry={q.refetch} />
      ) : (
        <ul className="glass directory">
          {q.data?.map((s) => (
            <li key={s.website_url}>
              <a href={safeUrl(s.website_url)} target="_blank" rel="noreferrer">
                {s.name}
                <ExternalLink size={16} />
              </a>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
export function Info({ page }: { page: 'about' | 'terms' | 'privacy' }) {
  const copy = {
    about: [
      'About DevFlash',
      'A quick way to understand technology news.',
      'DevFlash collects permitted feeds, groups related coverage, and links every story to its publisher. Short previews help you decide what to read in full.',
    ],
    privacy: [
      'Privacy',
      'Your reading list stays in this browser.',
      'DevFlash stores bookmarks, reading history, recent searches, and theme choices locally. The server counts article opens and successful shares. Session cookies support administration and limit repeated engagement events. Following an external link takes you to the publisher’s privacy policy.',
    ],
    terms: [
      'Terms of use',
      'Read and share with attribution.',
      'Publishers retain rights to their reporting and images. Each article links to its original source. Short previews and optional AI-assisted explanations may omit context. Consult the original announcement or advisory before acting.',
    ],
  }[page];
  return (
    <div className="reading">
      <Intro title={copy[0]} sub={copy[1]} />
      <div className="glass prose">
        <p>{copy[2]}</p>
        <Link className="button" to="/sources">
          Source directory
        </Link>
      </div>
    </div>
  );
}
