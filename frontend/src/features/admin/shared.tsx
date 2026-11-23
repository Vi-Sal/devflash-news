import type { ReactNode } from 'react';
import { useAdminAction } from './client';

export function State({ loading, error }: { loading: boolean; error: Error | null }) {
  if (loading) return <p role="status">Loading newsroom data…</p>;
  if (error)
    return (
      <p role="alert" className="adm-error">
        {error.message}
      </p>
    );
  return null;
}
export function Feedback({
  action,
  success = 'Saved.',
}: {
  action: ReturnType<typeof useAdminAction>;
  success?: string;
}) {
  return (
    <div aria-live="polite">
      {action.isError ? (
        <p className="adm-error">{action.error.message}</p>
      ) : action.isSuccess ? (
        <p>{success}</p>
      ) : null}
    </div>
  );
}
export function Panel({
  title,
  children,
  extra,
}: {
  title: string;
  children: ReactNode;
  extra?: ReactNode;
}) {
  return (
    <section className="adm-panel">
      <div className="adm-heading">
        <h2>{title}</h2>
        {extra}
      </div>
      {children}
    </section>
  );
}
export function Pager({
  page,
  hasMore,
  setPage,
}: {
  page: number;
  hasMore?: boolean;
  setPage: (n: number) => void;
}) {
  if (!page && !hasMore) return null;
  return (
    <nav aria-label="Results pages" className="adm-pager">
      <button type="button" disabled={!page} onClick={() => setPage(page - 1)}>
        Previous
      </button>
      <span>Page {page + 1}</span>
      <button type="button" disabled={!hasMore} onClick={() => setPage(page + 1)}>
        Next
      </button>
    </nav>
  );
}
