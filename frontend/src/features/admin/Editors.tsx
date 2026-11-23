import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useAdminAction, useAdminData, timestamp } from './client';
import type { ArticleRow, Source, Taxon } from './client';
import { Feedback, Pager, Panel, State } from './shared';

function selected(form: FormData, name: string) {
  return form.getAll(name).map((value) => Number(value));
}
function Picker({
  name,
  label,
  items,
  ids,
}: {
  name: string;
  label: string;
  items: Taxon[];
  ids: string;
}) {
  const active = new Set(ids.split(',').map(Number));
  return (
    <fieldset>
      <legend>{label}</legend>
      <div className="adm-checks">
        {items.length ? (
          items.map((item) => (
            <label key={item.id}>
              <input
                type="checkbox"
                name={name}
                value={item.id}
                defaultChecked={active.has(item.id)}
              />
              {item.name}
            </label>
          ))
        ) : (
          <p>None available.</p>
        )}
      </div>
    </fieldset>
  );
}
function ArticleEditor({ row, close }: { row: ArticleRow; close: () => void }) {
  const categories = useAdminData<Taxon[]>('/categories');
  const topics = useAdminData<Taxon[]>('/topics');
  const action = useAdminAction();
  const save = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    action.mutate(
      {
        method: 'PATCH',
        path: '/articles/' + row.id,
        body: {
          title: f.get('title'),
          summary: f.get('summary'),
          publicationStatus: f.get('status'),
          featured: f.has('featured'),
          categoryIds: selected(f, 'categories'),
          topicIds: selected(f, 'topics'),
        },
      },
      { onSuccess: close },
    );
  };
  return (
    <Panel
      title="Edit article"
      extra={
        <button type="button" onClick={close}>
          Close
        </button>
      }
    >
      <div className="adm-origin">
        <strong>Original from {row.source_name}</strong>
        <p>{row.original_title}</p>
        {row.source_description && <p>{row.source_description}</p>}
        <a href={row.original_url} target="_blank" rel="noopener noreferrer">
          Read source article
        </a>
      </div>
      <State
        loading={categories.isPending || topics.isPending}
        error={categories.error || topics.error}
      />
      {categories.data && topics.data && (
        <form className="adm-form" onSubmit={save}>
          <label>
            Headline
            <input name="title" required maxLength={500} defaultValue={row.title} />
          </label>
          <label>
            Summary
            <textarea name="summary" rows={5} maxLength={8000} defaultValue={row.summary || ''} />
          </label>
          <label>
            Visibility
            <select name="status" defaultValue={row.publication_status}>
              <option value="DRAFT">Draft</option>
              <option value="PUBLISHED">Published</option>
            </select>
          </label>
          <label className="adm-check">
            <input type="checkbox" name="featured" defaultChecked={row.featured} />
            Featured article
          </label>
          <Picker
            name="categories"
            label="Categories"
            items={categories.data.data}
            ids={row.category_ids}
          />
          <Picker name="topics" label="Topics" items={topics.data.data} ids={row.topic_ids} />
          <div className="adm-actions">
            <button className="adm-primary" disabled={action.isPending}>
              Save article
            </button>
            <button
              type="button"
              disabled={action.isPending}
              onClick={() =>
                action.mutate({ method: 'POST', path: '/articles/' + row.id + '/retry' })
              }
            >
              Retry processing
            </button>
            <button
              className="adm-danger"
              type="button"
              disabled={action.isPending}
              onClick={() => {
                if (
                  window.confirm(
                    'Remove this duplicate from publication and suppress future imports of its URL?',
                  )
                )
                  action.mutate(
                    { method: 'DELETE', path: '/articles/' + row.id },
                    { onSuccess: close },
                  );
              }}
            >
              Remove duplicate
            </button>
          </div>
        </form>
      )}
      <Feedback action={action} success="Processing request completed." />
    </Panel>
  );
}
export function Articles() {
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const [edit, setEdit] = useState<ArticleRow | null>(null);
  const rows = useAdminData<ArticleRow[]>(
    '/articles?' + new URLSearchParams({ page: String(page), q: search, status }),
  );
  if (edit) return <ArticleEditor key={edit.id} row={edit} close={() => setEdit(null)} />;
  return (
    <Panel title="Articles">
      <form
        className="adm-filters"
        onSubmit={(e) => {
          e.preventDefault();
          setPage(0);
          setSearch(String(new FormData(e.currentTarget).get('query') || ''));
        }}
      >
        <label>
          Search headlines
          <input name="query" type="search" maxLength={180} defaultValue={search} />
        </label>
        <label>
          Visibility
          <select
            value={status}
            onChange={(e) => {
              setPage(0);
              setStatus(e.target.value);
            }}
          >
            <option value="">All</option>
            <option value="PUBLISHED">Published</option>
            <option value="DRAFT">Draft</option>
          </select>
        </label>
        <button>Search</button>
      </form>
      <State loading={rows.isPending} error={rows.error} />
      {rows.data?.data.length === 0 && <p>No articles match this selection.</p>}
      <ul className="adm-list">
        {rows.data?.data.map((row) => (
          <li key={row.id}>
            <div>
              <strong>{row.title}</strong>
              <small>
                {row.source_name} · {row.publication_status.toLowerCase()} ·{' '}
                {row.processing_status.toLowerCase()}
              </small>
            </div>
            <div className="adm-actions">
              {row.publication_status === 'PUBLISHED' && (
                <Link to={'/article/' + row.slug}>View</Link>
              )}
              <button onClick={() => setEdit(row)}>Edit</button>
            </div>
          </li>
        ))}
      </ul>
      <Pager page={page} setPage={setPage} hasMore={rows.data?.hasMore} />
    </Panel>
  );
}

function SourceForm({ row, close }: { row: Source | null; close: () => void }) {
  const action = useAdminAction();
  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    action.mutate(
      {
        method: row ? 'PATCH' : 'POST',
        path: '/sources' + (row ? '/' + row.id : ''),
        body: {
          name: f.get('name'),
          websiteUrl: f.get('website'),
          feedUrl: f.get('feed'),
          sourceType: f.get('type'),
          enabled: f.has('enabled'),
        },
      },
      { onSuccess: close },
    );
  }
  return (
    <Panel
      title={row ? 'Edit source' : 'Add source'}
      extra={<button onClick={close}>Cancel</button>}
    >
      <form className="adm-form" onSubmit={save}>
        <label>
          Publisher name
          <input name="name" required maxLength={160} defaultValue={row?.name} />
        </label>
        <label>
          Publisher website
          <input
            name="website"
            type="url"
            required
            maxLength={2048}
            defaultValue={row?.website_url}
          />
        </label>
        <label>
          Permitted feed URL
          <input name="feed" type="url" required maxLength={2048} defaultValue={row?.feed_url} />
        </label>
        <label>
          Format
          <select name="type" defaultValue={row?.source_type || 'RSS'}>
            <option>RSS</option>
            <option>ATOM</option>
            <option>JSON_FEED</option>
            <option>CISA_KEV</option>
          </select>
        </label>
        <label className="adm-check">
          <input type="checkbox" name="enabled" defaultChecked={row?.enabled} />
          Enable collection
        </label>
        <p>Use a publisher-permitted feed on a host configured in the server allowlist.</p>
        <button className="adm-primary" disabled={action.isPending}>
          Save source
        </button>
      </form>
      <Feedback action={action} />
    </Panel>
  );
}
export function Sources() {
  const query = useAdminData<Source[]>('/sources');
  const [edit, setEdit] = useState<Source | null | undefined>(undefined);
  const action = useAdminAction();
  if (edit !== undefined) return <SourceForm row={edit} close={() => setEdit(undefined)} />;
  return (
    <Panel title="Sources" extra={<button onClick={() => setEdit(null)}>Add source</button>}>
      <State loading={query.isPending} error={query.error} />
      {query.data?.data.length === 0 && <p>No sources configured.</p>}
      <ul className="adm-list">
        {query.data?.data.map((row) => (
          <li key={row.id}>
            <div>
              <strong>{row.name}</strong>
              <small>
                {row.source_type} · {row.enabled ? 'Enabled' : 'Disabled'}
              </small>
              <small>Last success {timestamp(row.last_success_at)}</small>
              {row.last_error && <small className="adm-error">{row.last_error}</small>}
            </div>
            <div className="adm-actions">
              <button onClick={() => setEdit(row)}>Edit</button>
              <button
                disabled={action.isPending}
                onClick={() =>
                  action.mutate({ method: 'POST', path: '/sources/' + row.id + '/test' })
                }
              >
                Test feed
              </button>
            </div>
          </li>
        ))}
      </ul>
      <Feedback action={action} success="Feed test completed." />
      {action.isSuccess && <ResultDetails value={action.data?.data} />}
    </Panel>
  );
}

export function ResultDetails({ value }: { value: unknown }) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const fields = Object.entries(value).filter(([, v]) =>
    ['boolean', 'number', 'string'].includes(typeof v),
  );
  return (
    <dl className="adm-result">
      {fields.map(([k, v]) => (
        <div key={k}>
          <dt>{k.replace(/([A-Z])/g, ' $1')}</dt>
          <dd>{String(v)}</dd>
        </div>
      ))}
    </dl>
  );
}

type TaxonomyKind = 'categories' | 'topics';
function TaxonForm({
  kind,
  row,
  all,
  close,
}: {
  kind: TaxonomyKind;
  row: Taxon | null;
  all: Taxon[];
  close: () => void;
}) {
  const action = useAdminAction();
  const noun = kind === 'categories' ? 'category' : 'topic';
  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const body = {
      name: f.get('name'),
      slug: f.get('slug'),
      description: f.get('description'),
      ...(kind === 'categories'
        ? { icon: f.get('icon'), accentColor: f.get('accent'), enabled: f.has('enabled') }
        : {}),
    };
    action.mutate(
      { method: row ? 'PATCH' : 'POST', path: '/' + kind + (row ? '/' + row.id : ''), body },
      { onSuccess: close },
    );
  }
  return (
    <Panel
      title={(row ? 'Edit ' : 'Create ') + noun}
      extra={<button onClick={close}>Cancel</button>}
    >
      <form className="adm-form" onSubmit={save}>
        <label>
          Name
          <input name="name" required maxLength={120} defaultValue={row?.name} />
        </label>
        <label>
          URL slug
          <input
            name="slug"
            required
            maxLength={100}
            pattern="[a-z0-9]+(-[a-z0-9]+)*"
            defaultValue={row?.slug}
          />
        </label>
        <label>
          Description
          <textarea name="description" maxLength={1200} defaultValue={row?.description} />
        </label>
        {kind === 'categories' && (
          <>
            <label>
              Category icon
              <select name="icon" defaultValue={row?.icon?.toLowerCase() || 'layers'}>
                {Array.from(
                  new Set([
                    row?.icon?.toLowerCase(),
                    'layers',
                    'sparkles',
                    'shield',
                    'code',
                    'wallet',
                  ]),
                )
                  .filter((name): name is string => Boolean(name))
                  .map((name) => (
                    <option value={name} key={name}>
                      {name}
                    </option>
                  ))}
              </select>
            </label>
            <label>
              Accent color
              <input type="color" name="accent" defaultValue={row?.accent_color || '#2583ff'} />
            </label>
            <label className="adm-check">
              <input type="checkbox" name="enabled" defaultChecked={row?.enabled ?? true} />
              Show category
            </label>
          </>
        )}
        <button className="adm-primary" disabled={action.isPending}>
          Save {noun}
        </button>
      </form>
      {kind === 'topics' && row && all.length > 1 && (
        <form
          className="adm-merge"
          onSubmit={(e) => {
            e.preventDefault();
            const id = Number(new FormData(e.currentTarget).get('destination'));
            if (
              window.confirm(
                'Move all associated articles to the selected topic and remove this topic?',
              )
            )
              action.mutate(
                {
                  method: 'POST',
                  path: '/topics/' + row.id + '/merge',
                  body: { destinationId: id },
                },
                { onSuccess: close },
              );
          }}
        >
          <label>
            Merge into topic
            <select name="destination" required defaultValue="">
              <option value="" disabled>
                Select a topic
              </option>
              {all
                .filter((t) => t.id !== row.id)
                .map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.name}
                  </option>
                ))}
            </select>
          </label>
          <button disabled={action.isPending}>Merge topics</button>
        </form>
      )}
      <Feedback action={action} />
    </Panel>
  );
}
export function Taxonomy({ kind }: { kind: TaxonomyKind }) {
  const query = useAdminData<Taxon[]>('/' + kind);
  const [edit, setEdit] = useState<Taxon | null | undefined>(undefined);
  const noun = kind === 'categories' ? 'category' : 'topic';
  if (edit !== undefined)
    return (
      <TaxonForm
        kind={kind}
        row={edit}
        all={query.data?.data || []}
        close={() => setEdit(undefined)}
      />
    );
  return (
    <Panel
      title={kind === 'categories' ? 'Categories' : 'Topics'}
      extra={<button onClick={() => setEdit(null)}>Create {noun}</button>}
    >
      <State loading={query.isPending} error={query.error} />
      {query.data?.data.length === 0 && <p>No {kind} yet.</p>}
      <ul className="adm-list">
        {query.data?.data.map((row) => (
          <li key={row.id}>
            <div>
              <strong>{row.name}</strong>
              <small>
                /{row.slug} · {row.articles} linked articles
                {row.enabled === false ? ' · Disabled' : ''}
              </small>
              {row.description && <p>{row.description}</p>}
            </div>
            <button onClick={() => setEdit(row)}>Edit</button>
          </li>
        ))}
      </ul>
      <Link to="/admin/articles">Manage article assignments</Link>
    </Panel>
  );
}
