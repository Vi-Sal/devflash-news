import { useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ExternalLink, Clock3, ChevronLeft } from 'lucide-react';
import { format } from 'date-fns';
import NewsCard, { Actions, StoryImage } from './NewsCard';
import { CategoryIcon, Failure, Skeleton, useCategories, useData } from '../../components/common';
import { ApiError, safeUrl, type Detail, type Story } from '../../services/news';
import { useReader } from '../../app/reader';
import { Missing } from './Home';
export default function ArticlePage() {
  const { slug } = useParams(),
    query = useData<Detail>('/articles/' + slug),
    related = useData<Story[]>('/articles/' + slug + '/related'),
    { mark } = useReader(),
    a = query.data?.story,
    terms = useCategories();
  useEffect(() => {
    if (a) {
      document.title = a.title + ' · DevFlash';
      mark(a);
    }
  }, [a, mark]);
  if (query.isError)
    return query.error instanceof ApiError && query.error.status === 404 ? (
      <Missing />
    ) : (
      <Failure retry={query.refetch} />
    );
  if (!a || !query.data) return <Skeleton />;
  const enrichment = query.data.enrichment,
    security = query.data.security,
    original = safeUrl(a.originalUrl, 'publisher');
  const licensed = original && ['kubernetes.io', 'go.dev'].includes(new URL(original).hostname);
  return (
    <div className="reading">
      <Link to="/" className="back">
        <ChevronLeft size={16} />
        News feed
      </Link>
      <article className="glass detail">
        <div className="tag-row">
          {a.categories.map((s) => (
            <Link key={s} to={'/category/' + s} className="badge">
              <CategoryIcon slug={s} size={15} />
              {terms.find((c) => c.slug === s)?.name || s}
            </Link>
          ))}
        </div>
        <h1>{a.title}</h1>
        <p className="detail-summary">{a.summary}</p>
        <div className="detail-meta">
          <strong>{a.source}</strong>
          <time dateTime={a.publishedAt}>{format(new Date(a.publishedAt), 'MMM d, yyyy')}</time>
          <span>
            <Clock3 size={15} /> {a.minutes} min read
          </span>
          <Actions story={a} />
        </div>
        <StoryImage story={a} eager />
        {enrichment ? (
          <>
            {Object.entries({
              what_happened: 'What happened',
              why_it_matters: 'Why it matters',
              key_takeaway: 'Key takeaway',
              developer_impact: 'Developer impact',
            }).map(
              ([key, label]) =>
                enrichment[key] && (
                  <section key={key}>
                    <h2>{label}</h2>
                    <p>{enrichment[key]}</p>
                  </section>
                ),
            )}
            <p className="muted">AI-assisted summary based on the publisher’s preview.</p>
          </>
        ) : (
          <section>
            <h2>From the publisher</h2>
            <p>
              {__DEMO__
                ? 'This archive entry summarizes the linked announcement.'
                : 'This brief uses the available publisher description.'}{' '}
              Read the original for the full context.
            </p>
          </section>
        )}
        {security && Object.values(security).some(Boolean) && (
          <section>
            <h2>Security advisory</h2>
            <dl>
              {Object.entries(security)
                .filter(([, v]) => v != null)
                .map(([k, v]) => (
                  <div key={k}>
                    <dt>{k.replaceAll('_', ' ')}</dt>
                    <dd>{String(v)}</dd>
                  </div>
                ))}
            </dl>
          </section>
        )}
        {original && (
          <a className="button" href={original} target="_blank" rel="noreferrer">
            Read on {a.source}
            <ExternalLink size={16} />
          </a>
        )}
        {a.author && <p className="muted">By {a.author}</p>}
        {licensed && (
          <p className="muted">
            Publisher preview, abridged.{' '}
            <a href="https://creativecommons.org/licenses/by/4.0/" rel="noreferrer" target="_blank">
              CC BY 4.0
            </a>
          </p>
        )}
        {a.topics.length > 0 && (
          <div className="tag-row topics">
            {a.topics.map((t) => (
              <Link key={t} className="pill" to={'/topics/' + t}>
                #{t}
              </Link>
            ))}
          </div>
        )}
      </article>
      {!!related.data?.length && (
        <section>
          <h2 className="section-title">Related stories</h2>
          {related.data.map((s) => (
            <NewsCard story={s} key={s.id} />
          ))}
        </section>
      )}
    </div>
  );
}
