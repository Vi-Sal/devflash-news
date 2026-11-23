import {
  CodeXml,
  Layers3,
  Sparkles,
  Shield,
  WalletCards,
  RefreshCw,
  Newspaper,
} from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { data, defaultCategories, type Term } from '../services/news';
const icons = {
  ai: Sparkles,
  security: Shield,
  'new-tech': Layers3,
  developer: CodeXml,
  fintech: WalletCards,
  code: CodeXml,
  shield: Shield,
  sparkles: Sparkles,
  wallet: WalletCards,
  layers: Layers3,
};
export function CategoryIcon({ slug, size = 17 }: { slug: string; size?: number }) {
  const Icon = icons[slug.toLowerCase() as keyof typeof icons] || Layers3;
  return <Icon size={size} aria-hidden />;
}
export function useData<T>(path: string) {
  return useQuery({
    queryKey: [path],
    queryFn: ({ signal }) => data<T>(path, signal),
    staleTime: 60000,
  });
}
export function useCategories() {
  const query = useData<Term[]>('/categories');
  return query.data ?? defaultCategories;
}
export function Pills({ active }: { active?: string }) {
  const categories = useCategories();
  return (
    <nav className="pills" aria-label="News categories">
      <Link
        to="/"
        className={!active ? 'pill active' : 'pill'}
        aria-current={!active ? 'page' : undefined}
      >
        For You
      </Link>
      {categories.map((c) => (
        <Link
          key={c.slug}
          to={'/category/' + c.slug}
          className={'pill' + (active === c.slug ? ' active' : '')}
          aria-current={active === c.slug ? 'page' : undefined}
        >
          <CategoryIcon slug={c.icon || c.slug} />
          {c.name}
        </Link>
      ))}
    </nav>
  );
}
export function Empty({ title, children }: { title: string; children?: React.ReactNode }) {
  return (
    <section className="glass state">
      <Newspaper size={28} />
      <h2>{title}</h2>
      {children && <p>{children}</p>}
    </section>
  );
}
export function Failure({ retry }: { retry: () => unknown }) {
  return (
    <section className="glass state" role="alert">
      <h2>Couldn’t load this section</h2>
      <button className="button" onClick={() => retry()}>
        <RefreshCw size={16} />
        Try again
      </button>
    </section>
  );
}
export function Skeleton() {
  return (
    <div className="glass skeleton" role="status" aria-label="Loading articles">
      <div />
      <div />
      <div />
    </div>
  );
}

export function Intro({ title, sub }: { title: string; sub?: string }) {
  return (
    <div className="intro">
      <h1>{title}</h1>
      {sub && <p>{sub}</p>}
    </div>
  );
}
