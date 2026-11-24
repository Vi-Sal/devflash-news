import { useSearchParams, Link } from 'react-router-dom';
import { useReader } from '../../app/reader';
import { Empty, Failure, Intro, Skeleton, useData } from '../../components/common';
import Sidebar from '../../components/Sidebar';
import type { Filters, Term } from '../../services/news';
import Feed from './Feed';
import { FollowButton } from './FollowButton';

export function Topics() {
  const { following } = useReader();
  const topics = useData<Term[]>('/topics');
  const sorted = [...(topics.data ?? [])].sort(
    (a, b) =>
      Number(following.includes(b.slug)) - Number(following.includes(a.slug)) ||
      a.name.localeCompare(b.name),
  );
  return (
    <div className="columns">
      <div>
        <Intro title="Topics" sub="Follow technologies you want to see more often in For You." />
        {topics.isPending ? (
          <Skeleton />
        ) : topics.isError ? (
          <Failure retry={topics.refetch} />
        ) : !sorted.length ? (
          <Empty title="No topics yet">Topics appear as articles are collected.</Empty>
        ) : (
          <div className="tile-grid">
            {sorted.map((topic) => (
              <article className="glass topic-tile topic-card" key={topic.slug}>
                <h2>
                  <Link to={'/topics/' + topic.slug}>{topic.name}</Link>
                </h2>
                {topic.description && <p>{topic.description}</p>}
                <p>
                  {topic.stories ?? 0} {topic.stories === 1 ? 'story' : 'stories'}
                </p>
                <FollowButton topic={topic} />
              </article>
            ))}
          </div>
        )}
      </div>
      <Sidebar />
    </div>
  );
}

export function SecurityWatch() {
  const [params, setParams] = useSearchParams();
  const filterKeys = [
    'severity',
    'verification',
    'incidentType',
    'affected',
    'cve',
    'patch',
    'from',
    'to',
  ] as const;
  const filters = Object.fromEntries(
    filterKeys.map((key) => [key, params.get(key) ?? undefined]),
  ) as Filters;
  const key = params.toString();
  return (
    <div className="columns">
      <section>
        <Intro
          title="Security Watch"
          sub="Follow disclosures, advisories, and confirmed exploitation. Severity and patch details appear when a source provides them."
        />
        <form
          className="glass filter-panel"
          key={key}
          onSubmit={(event) => {
            event.preventDefault();
            const form = new FormData(event.currentTarget);
            const next = new URLSearchParams();
            for (const field of filterKeys) {
              const value = String(form.get(field) ?? '').trim();
              if (value) next.set(field, value);
            }
            setParams(next);
          }}
        >
          <div className="filters">
            <label>
              Severity
              <select name="severity" defaultValue={filters.severity ?? ''}>
                <option value="">All severities</option>
                {['Critical', 'High', 'Medium', 'Low', 'Unknown'].map((severity) => (
                  <option key={severity} value={severity.toLowerCase()}>
                    {severity}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Verification
              <select name="verification" defaultValue={filters.verification ?? ''}>
                <option value="">All reports</option>
                {[
                  'Active exploitation confirmed',
                  'Vulnerability disclosed',
                  'Confirmed',
                  'Investigation ongoing',
                  'Reported but unverified',
                ].map((status) => (
                  <option key={status}>{status}</option>
                ))}
              </select>
            </label>
            <label>
              Company or product
              <input name="affected" maxLength={100} defaultValue={filters.affected} />
            </label>
            <label>
              CVE
              <input
                name="cve"
                placeholder="CVE identifier"
                pattern="[Cc][Vv][Ee]-[0-9]{4}-[0-9]{4,19}"
                maxLength={28}
                defaultValue={filters.cve}
              />
            </label>
            <label>
              Incident type
              <input name="incidentType" maxLength={100} defaultValue={filters.incidentType} />
            </label>
            <label>
              Patch status
              <input name="patch" maxLength={100} defaultValue={filters.patch} />
            </label>
            <label>
              From
              <input type="date" name="from" defaultValue={filters.from} />
            </label>
            <label>
              Through
              <input type="date" name="to" defaultValue={filters.to} />
            </label>
          </div>
          <div className="filter-actions">
            <button className="button">Apply filters</button>
            {key && <Link to="/security">Clear filters</Link>}
          </div>
        </form>
        <Feed security filters={filters} />
      </section>
      <Sidebar />
    </div>
  );
}
