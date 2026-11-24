import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
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
import { safeUrl, type DailyEdition, type Story, type Trend, type Term } from '../../services/news';
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
        <div className="stream-heading">
          <h2>Browse topics</h2>
          <Link to="/topics">View all topics</Link>
        </div>
        <div className="tag-row">
          {topics.data?.slice(0, 14).map((t) => (
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
  const { saved, ids, setRead } = useReader();
  const categories = useCategories();
  const [search, setSearch] = useState('');
  const [category, setCategory] = useState('');
  const [order, setOrder] = useState('newest');
  const term = search.trim().toLowerCase();
  const matches = saved.filter(
    (story) =>
      (!category || story.categories.includes(category)) &&
      (!term ||
        [story.title, story.summary, story.source, ...story.topics]
          .join(' ')
          .toLowerCase()
          .includes(term)),
  );
  if (order === 'oldest') matches.reverse();
  return (
    <div className="columns">
      <div>
        <Intro
          title={'Saved articles' + (saved.length ? ' (' + saved.length + ')' : '')}
          sub="Your reading list, saved in this browser."
        />
        {!!saved.length && (
          <div className="glass filter-panel filters">
            <label>
              Search saved articles
              <input type="search" value={search} onChange={(e) => setSearch(e.target.value)} />
            </label>
            <label>
              Category
              <select value={category} onChange={(e) => setCategory(e.target.value)}>
                <option value="">All categories</option>
                {categories.map((c) => (
                  <option key={c.slug} value={c.slug}>
                    {c.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Saved date
              <select value={order} onChange={(e) => setOrder(e.target.value)}>
                <option value="newest">Newest saved first</option>
                <option value="oldest">Oldest saved first</option>
              </select>
            </label>
          </div>
        )}
        {matches.length ? (
          matches.map((story) => (
            <section key={story.id} className="saved-item">
              <NewsCard story={story} />
              <button className="button" onClick={() => setRead(story, !ids.includes(story.id))}>
                {ids.includes(story.id) ? 'Mark unread' : 'Mark as read'}
              </button>
            </section>
          ))
        ) : saved.length ? (
          <Empty title="No saved articles match">Try changing the search or category.</Empty>
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
  const { date } = useParams();
  const day = useData<DailyEdition>(date ? '/daily/' + date : '/daily');
  const archive = useData<string[]>('/daily/archive');
  const terms = useCategories();
  const { share } = useReader();
  if (day.isError) return <Failure retry={day.refetch} />;
  if (!day.data) return <Skeleton />;
  const edition = day.data;
  const count = edition.articles.length;
  const minutes = edition.minutes ?? edition.articles.reduce((sum, a) => sum + a.minutes, 0);
  return (
    <div className="columns">
      <div>
        <section className="glass edition-panel">
          <Intro
            title="Daily briefing"
            sub={format(new Date(edition.date + 'T12:00:00'), 'EEEE, MMMM d, yyyy')}
          />
          <p>
            {count} {count === 1 ? 'story' : 'stories'} · {minutes} min
          </p>
          {!!count && (
            <div className="tag-row edition-categories">
              {Object.entries(edition.categories ?? {}).map(([slug, total]) => (
                <Link to={'/category/' + slug} className="badge" key={slug}>
                  {terms.find((term) => slug === term.slug)?.name ?? slug} · {total}
                </Link>
              ))}
            </div>
          )}
          <div className="brief-bar">
            {!!count && (
              <a className="button" href="#briefing-stories">
                Start reading
              </a>
            )}
            <button
              className="button"
              onClick={() =>
                void share(date ? '/daily/' + date : '/daily', 'DevFlash daily briefing')
              }
            >
              <Share2 size={16} /> Share briefing
            </button>
            {date && <Link to="/daily">Today’s edition</Link>}
          </div>
        </section>
        <section id="briefing-stories" aria-label="Briefing stories">
          {count ? (
            edition.articles.map((story) => {
              const notes = edition.notes?.[story.id];
              return (
                <div key={story.id} className="briefing-story">
                  <NewsCard story={story} />
                  {(notes?.whyItMatters || notes?.keyTakeaway) && (
                    <div className="glass briefing-notes">
                      {notes.whyItMatters && (
                        <p>
                          <strong>Why it matters</strong> {notes.whyItMatters}
                        </p>
                      )}
                      {notes.keyTakeaway && (
                        <p>
                          <strong>Key takeaway</strong> {notes.keyTakeaway.split('\n')[0]}
                        </p>
                      )}
                    </div>
                  )}
                </div>
              );
            })
          ) : (
            <Empty title="No edition for this date">Browse the archive for recent briefings.</Empty>
          )}
        </section>
        <section className="glass edition-panel archive">
          <h2>Past briefings</h2>
          {archive.isError && <p>Archive temporarily unavailable.</p>}
          {!archive.isError && !archive.data?.length && (
            <p>{archive.isPending ? 'Loading editions…' : 'No editions in the archive yet.'}</p>
          )}
          <ul className="tag-row">
            {archive.data
              ?.filter((day) => day !== edition.date)
              .map((day) => (
                <li key={day}>
                  <Link className="pill" to={'/daily/' + day}>
                    {day}
                  </Link>
                </li>
              ))}
          </ul>
        </section>
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
