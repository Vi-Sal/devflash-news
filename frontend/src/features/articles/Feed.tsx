import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { request, type Filters, type Page } from '../../services/news';
import { Empty, Failure, Skeleton } from '../../components/common';
import NewsCard from './NewsCard';
export default function Feed({
  filters = {},
  search = false,
  security = false,
}: {
  filters?: Filters;
  search?: boolean;
  security?: boolean;
}) {
  const client = useQueryClient(),
    path = security ? '/security' : search ? '/search' : '/articles',
    anchor = useRef<HTMLDivElement>(null),
    key = ['feed', path, filters];
  const feed = useInfiniteQuery({
    queryKey: key,
    queryFn: ({ pageParam, signal }) =>
      request<Page>(path, { ...filters, cursor: pageParam || undefined }, signal),
    initialPageParam: '',
    getNextPageParam: (p) => p.nextCursor ?? undefined,
    staleTime: Infinity,
  });
  const head = useQuery({
    queryKey: ['head', path, filters],
    enabled: !__DEMO__ && !search && !security && !!feed.data,
    queryFn: ({ signal }) => request<Page>(path, { ...filters, limit: '1' }, signal),
    refetchInterval: 60000,
    staleTime: 60000,
  });
  const { hasNextPage, isFetchingNextPage, fetchNextPage, isFetchNextPageError } = feed;
  useEffect(() => {
    if (
      !anchor.current ||
      !hasNextPage ||
      isFetchingNextPage ||
      isFetchNextPageError ||
      !('IntersectionObserver' in window)
    )
      return;
    const ob = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) void fetchNextPage({ cancelRefetch: false });
      },
      { rootMargin: '150px' },
    );
    ob.observe(anchor.current);
    return () => ob.disconnect();
  }, [fetchNextPage, hasNextPage, isFetchingNextPage, isFetchNextPageError]);
  if (feed.isPending) return <Skeleton />;
  if (feed.isError && !feed.data) return <Failure retry={feed.refetch} />;
  const stories = [
    ...new Map(feed.data?.pages.flatMap((p) => p.data).map((a) => [a.id, a])).values(),
  ];
  const first = head.data?.data[0],
    fresh =
      first &&
      first.id !== stories[0]?.id &&
      (!stories.length || first.publishedAt >= stories[0].publishedAt);
  const metadata = Object.assign({}, ...(feed.data?.pages.map((page) => page.security) ?? []));
  return (
    <div className="feed">
      {fresh && (
        <button
          className="button"
          onClick={() => void client.resetQueries({ queryKey: key, exact: true })}
        >
          New stories available · Refresh
        </button>
      )}
      {stories.length ? (
        stories.map((a, i) => (
          <NewsCard
            key={a.id}
            story={a}
            eager={i === 0}
            advisory={security ? metadata[a.slug] : undefined}
          />
        ))
      ) : (
        <Empty
          title={
            security
              ? 'No matching security stories'
              : search
                ? 'No matching stories'
                : 'No stories here yet'
          }
        >
          {search
            ? 'Try another phrase or adjust your filters.'
            : 'Published coverage will appear here after collection.'}
        </Empty>
      )}
      <div ref={anchor} className="feed-sentinel" aria-hidden="true" />
      {isFetchingNextPage ? (
        <Skeleton />
      ) : hasNextPage ? (
        <button className="button feed-more" onClick={() => void fetchNextPage()}>
          {isFetchNextPageError ? 'Retry loading stories' : 'Load more stories'}
        </button>
      ) : (
        stories.length > 0 && <p className="feed-end">You’ve reached the end of this feed.</p>
      )}
    </div>
  );
}
