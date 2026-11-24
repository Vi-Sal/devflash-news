import { Link } from 'react-router-dom';
import { Clock3, Bookmark, Share2, ExternalLink, Check, Eye } from 'lucide-react';
import { formatDistanceToNowStrict, format } from 'date-fns';
import { art, safeUrl, type Advisory, type Story } from '../../services/news';
import { useReader } from '../../app/reader';
import { CategoryIcon, useCategories } from '../../components/common';
export function StoryImage({ story, eager = false }: { story: Story; eager?: boolean }) {
  return (
    <img
      className="story-image"
      src={safeUrl(story.imageUrl, 'image') || art(story)}
      alt={
        story.imageUrl
          ? story.imageAlt || story.title
          : (story.categories[0] || 'Technology') + ' illustration'
      }
      title={story.imageAttribution || undefined}
      width="760"
      height="506"
      loading={eager ? 'eager' : 'lazy'}
      referrerPolicy="no-referrer"
      onError={(e) => {
        if (!e.currentTarget.src.endsWith(art(story))) {
          e.currentTarget.src = art(story);
          e.currentTarget.alt = (story.categories[0] || 'Technology') + ' illustration';
          e.currentTarget.removeAttribute('title');
        }
      }}
    />
  );
}
export function ViewCount({ count }: { count?: number }) {
  if (count === undefined) return null;
  return (
    <span className="article-views">
      <Eye size={15} aria-hidden /> {count.toLocaleString()} {count === 1 ? 'view' : 'views'}
    </span>
  );
}
export function Actions({ story }: { story: Story }) {
  const { saved, toggle, share } = useReader(),
    marked = saved.some((a) => a.id === story.id),
    url = safeUrl(story.originalUrl, 'publisher');
  return (
    <div className="card-actions">
      <button
        aria-label={marked ? 'Remove bookmark' : 'Save article'}
        aria-pressed={marked}
        title={marked ? 'Remove bookmark' : 'Save article'}
        onClick={() => toggle(story)}
      >
        <Bookmark fill={marked ? 'currentColor' : 'none'} />
      </button>
      <button
        aria-label="Share article"
        title="Share article"
        onClick={() => void share('/article/' + story.slug, story.title, story)}
      >
        <Share2 />
      </button>
      {url && (
        <a
          href={url}
          aria-label="Open original source"
          title="Open original source"
          target="_blank"
          rel="noreferrer"
        >
          <ExternalLink />
        </a>
      )}
    </div>
  );
}
export default function NewsCard({
  story,
  eager = false,
  lead = false,
  advisory,
}: {
  story: Story;
  eager?: boolean;
  lead?: boolean;
  advisory?: Advisory;
}) {
  const { ids } = useReader(),
    categories = useCategories(),
    category = categories.find((c) => c.slug === story.categories[0]),
    path = '/article/' + story.slug;
  const published = new Date(story.publishedAt);
  return (
    <article className={'glass news-card' + (lead ? ' featured-card' : '')}>
      <div className="card-copy">
        <div className="meta-line">
          {category && (
            <Link className="badge" to={'/category/' + category.slug}>
              <span style={{ color: category.accent_color }}>
                <CategoryIcon slug={category.icon || category.slug} size={15} />
              </span>
              {category.name}
            </Link>
          )}
          <span className="separator">·</span>
          <time dateTime={story.publishedAt}>
            {__DEMO__
              ? format(published, 'MMM d, yyyy')
              : formatDistanceToNowStrict(published, { addSuffix: true })}
          </time>
          {ids.includes(story.id) && <Check size={14} aria-label="Already read" />}
        </div>
        <h2>
          <Link to={path}>{story.title}</Link>
        </h2>
        <p>{story.summary || 'Read the publisher’s original report for details.'}</p>
        {advisory && (
          <div className="advisory-meta">
            <span>{advisory.verification ?? 'Reported but unverified'}</span>
            {advisory.severity && (
              <span
                className={advisory.severity.toLowerCase() === 'critical' ? 'critical' : undefined}
              >
                {advisory.severity}
              </span>
            )}
            {[
              advisory.cve_id,
              advisory.incident_type,
              advisory.affected_company,
              advisory.affected_product,
              advisory.patch_status,
              advisory.cvss_score == null ? null : 'CVSS ' + advisory.cvss_score,
            ]
              .filter(Boolean)
              .map((value, index) => (
                <span key={index}>{value}</span>
              ))}
          </div>
        )}
        <div className="card-bottom">
          <div className="byline">
            <Clock3 size={16} />
            <span>{story.minutes} min read</span>
            <span>·</span>
            <span>{story.source}</span>
            <ViewCount count={story.viewCount} />
          </div>
          <Actions story={story} />
        </div>
      </div>
      <Link to={path} className="image-link" aria-label={'Read ' + story.title} tabIndex={-1}>
        <StoryImage story={story} eager={eager} />
      </Link>
    </article>
  );
}
