import { BookOpen, CalendarDays, ChevronDown, Shield } from 'lucide-react';
import { Link } from 'react-router-dom';
import { localDay, useReader } from '../app/reader';
import type { DailyEdition, Page } from '../services/news';
import { useData } from './common';

export function BriefingPreview() {
  const briefing = useData<DailyEdition>('/daily');
  const edition = briefing.data;
  const size = edition?.articles.length ?? 0;
  const minutes = edition?.minutes ?? edition?.articles.reduce((sum, a) => sum + a.minutes, 0) ?? 0;
  return (
    <section className="glass briefing-preview">
      <CalendarDays size={23} aria-hidden />
      <div>
        <h2>Daily Tech Briefing</h2>
        <p>
          {size
            ? `${size} stories · ${minutes} min`
            : briefing.isPending
              ? 'Loading today’s edition…'
              : briefing.isError
                ? 'Briefing temporarily unavailable'
                : 'Browse past editions while today’s briefing takes shape.'}
        </p>
      </div>
      <Link className="button" to="/daily">
        {size ? 'Start reading' : 'View briefing'}
      </Link>
    </section>
  );
}

export function LearningProgress() {
  const { reading, goal, setGoal } = useReader();
  const now = new Date();
  const today = localDay(now);
  const entries = reading.filter((item) => item.day === today);
  const minutes = entries.reduce((total, item) => total + item.minutes, 0);
  const completed = Math.min(goal, minutes);
  const topics = [...new Set(entries.flatMap((item) => item.topics))];
  const activeDays = new Set(reading.map((item) => item.day));
  let streak = 0;
  if (!activeDays.has(today)) now.setDate(now.getDate() - 1);
  while (activeDays.has(localDay(now))) {
    streak++;
    now.setDate(now.getDate() - 1);
  }
  return (
    <section className="glass widget learning">
      <div className="widget-title">
        <BookOpen />
        <h2>Learning progress</h2>
      </div>
      <div className="learning-summary">
        <p>{entries.length} read today</p>
        <span>
          {streak} {streak === 1 ? 'day' : 'days'} in a row
        </span>
      </div>
      <div className="learning-goal-panel">
        <p>
          <strong>
            {minutes} / {goal} min
          </strong>{' '}
          estimated
        </p>
        <div
          className="learning-track"
          role="progressbar"
          aria-label="Daily reading goal"
          aria-valuemin={0}
          aria-valuemax={goal}
          aria-valuenow={completed}
          aria-valuetext={`${minutes} of ${goal} estimated minutes`}
        >
          <div className="learning-fill" style={{ width: `${(completed / goal) * 100}%` }} />
        </div>
        <label className="learning-goal">
          Daily goal
          <span className="learning-select">
            <select
              aria-label="Daily goal"
              value={goal}
              onChange={(event) => setGoal(Number(event.target.value))}
            >
              {[5, 10, 15, 20, 30].map((amount) => (
                <option key={amount} value={amount}>
                  {amount} min
                </option>
              ))}
            </select>
            <ChevronDown size={15} aria-hidden />
          </span>
        </label>
      </div>
      {!!topics.length && (
        <div className="learning-topics" aria-label="Topics explored today">
          {topics.slice(0, 6).map((topic) => (
            <Link key={topic} to={'/topics/' + topic}>
              {topic.replaceAll('-', ' ')}
            </Link>
          ))}
        </div>
      )}
    </section>
  );
}

export function SecurityHighlights() {
  const alerts = useData<Page['data']>('/security/highlights');
  return (
    <section className="glass widget security-widget">
      <Link to="/security" className="widget-title">
        <Shield />
        <h2>Security Watch</h2>
      </Link>
      <ul>
        {alerts.data?.map((story) => (
          <li key={story.id}>
            <Link to={'/article/' + story.slug}>{story.title}</Link>
          </li>
        ))}
      </ul>
      {!alerts.data?.length && (
        <p>
          {alerts.isPending
            ? 'Loading headlines…'
            : alerts.isError
              ? 'Headlines temporarily unavailable.'
              : 'No security headlines yet.'}
        </p>
      )}
    </section>
  );
}
