import { useState } from 'react';
import type { FormEvent } from 'react';
import { useAdminAction, useAdminData, timestamp } from './client';
import type { Audit, Dashboard, Run, Source } from './client';
import { Feedback, Pager, Panel, State } from './shared';
import { ResultDetails } from './Editors';

function Runs({ runs, retry }: { runs: Run[]; retry?: (id: number) => void }) {
  if (!runs.length) return <p>No collection runs recorded.</p>;
  return (
    <ul className="adm-list">
      {runs.map((run) => (
        <li key={run.id}>
          <div>
            <strong>
              {run.source_name || 'Collection'} · {run.status.toLowerCase()}
            </strong>
            <small>
              Started {timestamp(run.started_at)} · {run.duration_seconds}s
            </small>
            {run.finished_at && <small>Finished {timestamp(run.finished_at)}</small>}
            <p>
              {run.fetched_count} fetched · {run.inserted_count} inserted · {run.duplicate_count}{' '}
              duplicates · {run.error_count} errors
            </p>
            {run.error_message && <p className="adm-error">{run.error_message}</p>}
          </div>
          {retry && run.error_count > 0 && run.source_id && (
            <button onClick={() => retry(run.source_id)}>Retry source</button>
          )}
        </li>
      ))}
    </ul>
  );
}
export function Overview({ analytics = false }: { analytics?: boolean }) {
  const q = useAdminData<Dashboard>('/dashboard');
  const stats = q.data?.data;
  if (!stats) return <State loading={q.isPending} error={q.error} />;
  const tiles = analytics
    ? [
        ['Measured article views', stats.views],
        ['Measured shares', stats.shares],
        ['AI calls today', stats.ai_calls_today],
      ]
    : [
        ['Stored articles', stats.articles],
        ['Collected today', stats.collected_today],
        ['Active sources', stats.active_sources],
        ['Sources with errors', stats.failed_sources],
        ['Awaiting processing', stats.awaiting_processing],
        ['AI calls today', stats.ai_calls_today],
      ];
  return (
    <>
      <Panel title={analytics ? 'Analytics' : 'Overview'}>
        <dl className="adm-tiles">
          {tiles.map(([k, v]) => (
            <div key={k}>
              <dt>{k}</dt>
              <dd>{v.toLocaleString()}</dd>
            </div>
          ))}
        </dl>
        {!analytics && <small>Last successful collection {timestamp(stats.last_success)}</small>}
      </Panel>
      <Panel title="Category distribution">
        <ul className="adm-list">
          {stats.distribution.map((c) => (
            <li key={c.id}>
              <strong>{c.name}</strong>
              <span>{c.articles} articles</span>
            </li>
          ))}
        </ul>
      </Panel>
      {!analytics && (
        <Panel title="Recent ingestion">
          <Runs runs={stats.recent_runs} />
        </Panel>
      )}
    </>
  );
}

export function Ingestion() {
  const [page, setPage] = useState(0);
  const sources = useAdminData<Source[]>('/sources');
  const q = useAdminData<Run[]>('/ingestion/runs?page=' + page);
  const action = useAdminAction();
  const run = (sourceId: number | null) =>
    action.mutate({ method: 'POST', path: '/ingestion/run', body: { sourceId } });
  return (
    <>
      <Panel title="Run ingestion">
        <form
          className="adm-filters"
          onSubmit={(e) => {
            e.preventDefault();
            run(Number(new FormData(e.currentTarget).get('source')) || null);
          }}
        >
          <label>
            Source
            <select name="source">
              <option value="">All due sources</option>
              {sources.data?.data
                .filter((s) => s.enabled)
                .map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
            </select>
          </label>
          <button className="adm-primary" disabled={action.isPending}>
            {action.isPending ? 'Request in progress…' : 'Run ingestion'}
          </button>
        </form>
        <Feedback action={action} success="Ingestion request completed." />
        {action.isSuccess && <ResultDetails value={action.data?.data} />}
      </Panel>
      <Panel
        title="Ingestion history"
        extra={<button onClick={() => void q.refetch()}>Refresh</button>}
      >
        <State loading={q.isPending} error={q.error} />
        {q.data && <Runs runs={q.data.data} retry={action.isPending ? undefined : run} />}
        <Pager page={page} hasMore={q.data?.hasMore} setPage={setPage} />
      </Panel>
    </>
  );
}

export function AuditLog() {
  const [page, setPage] = useState(0);
  const q = useAdminData<Audit[]>('/audit-logs?page=' + page);
  return (
    <Panel title="Audit log">
      <State loading={q.isPending} error={q.error} />
      {q.data?.data.length === 0 && <p>No administrative actions recorded.</p>}
      <ul className="adm-list">
        {q.data?.data.map((row) => (
          <li key={row.id}>
            <div>
              <strong>
                {row.action.replaceAll('_', ' ')} · {row.entity_type}
                {row.entity_id ? ' #' + row.entity_id : ''}
              </strong>
              <small>
                {row.username || 'Administrator'} · {timestamp(row.created_at)}
              </small>
            </div>
          </li>
        ))}
      </ul>
      <Pager page={page} hasMore={q.data?.hasMore} setPage={setPage} />
    </Panel>
  );
}

const numbers = [
  ['ingestionBatchLimit', 'Items per source', 1, 100],
  ['defaultPageSize', 'Feed page size', 1, 30],
  ['aiDailyBudget', 'Maximum daily AI calls', 0, 1000],
  ['trendingWindowDays', 'Trending window (days)', 1, 90],
] as const;
function SettingsForm({ values }: { values: Record<string, string> }) {
  const action = useAdminAction();
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const body = { ...Object.fromEntries(f.entries()), aiEnabled: String(f.has('aiEnabled')) };
    action.mutate({ method: 'PATCH', path: '/settings', body });
  }
  return (
    <form className="adm-form" onSubmit={submit}>
      <label>
        Site title
        <input name="siteTitle" maxLength={100} required defaultValue={values.siteTitle} />
      </label>
      <label>
        Tagline
        <input name="tagline" maxLength={220} required defaultValue={values.tagline} />
      </label>
      <label>
        YouTube channel URL
        <input
          type="url"
          name="youtubeUrl"
          maxLength={2048}
          placeholder="https://www.youtube.com/@channel"
          defaultValue={values.youtubeUrl}
        />
      </label>
      {numbers.map(([key, label, min, max]) => (
        <label key={key}>
          {label}
          <input
            type="number"
            name={key}
            min={min}
            max={max}
            step="1"
            required
            defaultValue={values[key]}
          />
        </label>
      ))}
      <label className="adm-check">
        <input type="checkbox" name="aiEnabled" defaultChecked={values.aiEnabled === 'true'} />
        Enable AI summaries
      </label>
      <button className="adm-primary" disabled={action.isPending}>
        Save settings
      </button>
      <Feedback action={action} />
    </form>
  );
}
export function Settings() {
  const q = useAdminData<Record<string, string>>('/settings');
  return (
    <Panel title="Site settings">
      <State loading={q.isPending} error={q.error} />
      {q.data && <SettingsForm values={q.data.data} />}
    </Panel>
  );
}
