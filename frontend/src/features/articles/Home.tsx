import { Link, useParams } from 'react-router-dom';
import Feed from './Feed';
import Sidebar from '../../components/Sidebar';
import { Empty, Failure, Intro, Pills, Skeleton, useData } from '../../components/common';
import { ApiError, type Site, type Term } from '../../services/news';
export function Home() {
  const site = useData<Site>('/site'),
    tagline = site.data?.tagline || 'Tech news in a flash.';
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
          <h1>
            {tagline === 'Tech news in a flash.' ? (
              <>
                Tech news in a <em>flash.</em>
              </>
            ) : (
              tagline
            )}
          </h1>
          <p className="subtitle">The latest in technology, explained simply.</p>
        </div>
        <Pills />
        <Feed />
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
        {type === 'categories' && <Pills active={slug} />}
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
