import { CalendarDays, TrendingUp, Clock3, Play, ChevronRight } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useData } from './common';
import { safeUrl, type Stats, type Site, type Trend } from '../services/news';
const rows = [
  ['ai', 'AI releases'],
  ['security', 'security stories'],
  ['new-tech', 'product launches'],
  ['developer', 'developer updates'],
];
export default function Sidebar() {
  const stats = useData<Stats>('/stats/today'),
    trends = useData<Trend[]>('/trending'),
    site = useData<Site>('/site');
  const channel = safeUrl(site.data?.youtubeUrl);
  return (
    <aside className="sidebar" aria-label="Newsroom highlights">
      <section className="glass widget glance">
        <Link to="/daily" className="widget-title">
          <CalendarDays />
          <h2>Today at a Glance</h2>
        </Link>
        <p>
          {__DEMO__
            ? 'A quick summary of the sample edition.'
            : 'A quick summary of what’s happening in tech.'}
        </p>
        {stats.data?.total ? (
          <>
            <ul className="glance-list">
              {rows.map(([slug, label]) => {
                const item = stats.data.categories.find((c) => c.slug === slug);
                const count = Number(
                  (slug === 'ai' || slug === 'new-tech' ? item?.releases : item?.count) || 0,
                );
                return (
                  <li key={slug}>
                    <i className={'dot ' + slug} />
                    <Link to={'/category/' + slug}>
                      {count}{' '}
                      {count === 1
                        ? label
                            .replace('stories', 'story')
                            .replace('updates', 'update')
                            .replace('releases', 'release')
                            .replace('launches', 'launch')
                        : label}
                    </Link>
                  </li>
                );
              })}
            </ul>
            <Link to="/daily" className="time-chip">
              <Clock3 size={13} /> {stats.data.minutes} min
            </Link>
          </>
        ) : (
          <p>
            {stats.isPending
              ? 'Loading the daily briefing…'
              : stats.isError
                ? 'Daily counts unavailable.'
                : 'No published stories so far today.'}
          </p>
        )}
      </section>
      <section className="glass widget trending">
        <Link to="/trending" className="widget-title">
          <TrendingUp />
          <h2>Trending</h2>
        </Link>
        <ol>
          {trends.data?.slice(0, 5).map((t, i) => (
            <li key={t.slug}>
              <Link to={'/topics/' + t.slug}>
                <span className="rank">{i + 1}</span>
                <strong>{t.name}</strong>
                <span className="metric">
                  {t.stories} {Number(t.stories) === 1 ? 'story' : 'stories'}
                </span>
              </Link>
            </li>
          ))}
        </ol>
        {!trends.data?.length && (
          <p>
            {trends.isPending
              ? 'Loading topics…'
              : trends.isError
                ? 'Topic rankings unavailable.'
                : 'Topic rankings appear as coverage comes in.'}
          </p>
        )}
      </section>
      <section className="glass widget youtube">
        <div className="widget-title">
          <span className="video-icon">
            <Play size={13} fill="currentColor" />
          </span>
          <h2>DevFlash on YouTube</h2>
        </div>
        <p>In-depth explainers, tutorials, and tech breakdowns.</p>
        {channel ? (
          <a className="youtube-action" href={channel} target="_blank" rel="noreferrer">
            <Play size={17} fill="currentColor" />
            Watch on YouTube
            <ChevronRight size={17} />
          </a>
        ) : (
          <span className="youtube-action pending">
            <Play size={16} fill="currentColor" />
            Channel link coming soon
          </span>
        )}
      </section>
    </aside>
  );
}
