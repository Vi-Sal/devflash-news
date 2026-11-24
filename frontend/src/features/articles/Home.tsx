import { Link, useParams, useSearchParams } from 'react-router-dom';
import Feed from './Feed';
import NewsCard from './NewsCard';
import { BriefingPreview } from '../../components/ReaderWidgets';
import { useReader } from '../../app/reader';
import { FollowButton } from './FollowButton';
import Sidebar from '../../components/Sidebar';
import { Empty, Failure, Intro, Pills, Skeleton, useData } from '../../components/common';
import { ApiError, type Site, type Story, type Term } from '../../services/news';
export function Home() {
  const [params] = useSearchParams();
  const mode = params.get('feed') === 'latest' ? 'latest' : 'for-you';
  const { following } = useReader();
  const featured = useData<Story[]>('/featured');
  const site = useData<Site>('/site');
  const lead = mode === 'for-you' ? featured.data?.[0] : undefined;
  return (
    <div className="columns">
      <div className="stream">
        <div className="hero">
          {__DEMO__ && (
            <p className="edition">
              <span />
              Sample edition <b>·</b> Historical archive
            </p>
          )}
          <p className="hero-tagline">{site.data?.tagline || 'Tech news in a flash.'}</p>
          <h1>
            Discover <em>what’s next.</em>
          </h1>
          <p className="subtitle">Technology moves fast. Stay informed in minutes.</p>
        </div>
        <BriefingPreview />
        <Pills active={mode} />
        {lead && (
          <section aria-labelledby="lead-story">
            <h2 id="lead-story" className="section-title">
              Featured story
            </h2>
            <NewsCard story={lead} eager lead />
          </section>
        )}
        <div className="stream-heading">
          <h2>{mode === 'latest' ? 'Latest news' : 'For you'}</h2>
          {mode === 'for-you' && (
            <Link to="/topics">
              {following.length ? 'Manage followed topics' : 'Discover topics'}
            </Link>
          )}
        </div>
        {mode === 'for-you' && featured.isPending ? (
          <Skeleton />
        ) : (
          <Feed
            filters={{
              feed: mode,
              following:
                mode === 'for-you' && following.length
                  ? [...following].sort().join(',')
                  : undefined,
              exclude: lead ? String(lead.id) : undefined,
            }}
          />
        )}
      </div>
      <Sidebar />
    </div>
  );
}
export function SectionFeed({ type }: { type: 'topics' | 'categories' }) {
  const { slug } = useParams(),
    term = useData<Term>('/' + type + '/' + slug);
  if (term.isError)
    return term.error instanceof ApiError && term.error.status === 404 ? (
      <Missing />
    ) : (
      <Failure retry={term.refetch} />
    );
  if (!term.data) return <Skeleton />;
  return (
    <div className="columns">
      <div className="stream">
        <Intro title={term.data.name} sub={term.data.description} />
        {type === 'categories' ? (
          <Pills active={slug} />
        ) : (
          <div className="topic-toolbar">
            <FollowButton topic={term.data} />
            <span>{term.data.stories ?? 0} stories</span>
            <Link to="/topics">All topics</Link>
          </div>
        )}
        <Feed key={slug} filters={type === 'topics' ? { topic: slug } : { category: slug }} />
      </div>
      <Sidebar />
    </div>
  );
}
export function Missing() {
  return (
    <Empty title="Page not found">
      This page may have moved or the article may be unpublished.{' '}
      <Link to="/">Return to the feed</Link>
    </Empty>
  );
}
