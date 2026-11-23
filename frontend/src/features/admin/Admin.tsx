import { useEffect } from 'react';
import { NavLink, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import {
  LayoutDashboard,
  Newspaper,
  Rss,
  Shapes,
  Tags,
  RefreshCw,
  ChartColumn,
  Settings2,
  History,
  LogOut,
  LockKeyhole,
} from 'lucide-react';
import { AdminError, change, useAdminData } from './client';
import { State } from './shared';
import { Articles, Sources, Taxonomy } from './Editors';
import { AuditLog, Overview, Ingestion, Settings } from './Monitoring';
import './admin.css';

const tabs = [
  { path: '', name: 'Overview', icon: LayoutDashboard },
  { path: 'articles', name: 'Articles', icon: Newspaper },
  { path: 'sources', name: 'Sources', icon: Rss },
  { path: 'categories', name: 'Categories', icon: Shapes },
  { path: 'topics', name: 'Topics', icon: Tags },
  { path: 'ingestion', name: 'Ingestion', icon: RefreshCw },
  { path: 'analytics', name: 'Analytics', icon: ChartColumn },
  { path: 'settings', name: 'Settings', icon: Settings2 },
  { path: 'audit-logs', name: 'Audit log', icon: History },
];
const credentials = z.object({
  username: z.string().min(1).max(80),
  password: z.string().min(1).max(256),
});
function Login({ next }: { next: string }) {
  const form = useForm<z.infer<typeof credentials>>();
  const client = useQueryClient();
  const navigate = useNavigate();
  const login = useMutation({
    mutationFn: async (values: z.infer<typeof credentials>) => {
      if (!credentials.safeParse(values).success) throw new Error('Enter a username and password.');
      await change('POST', '/auth/login', new URLSearchParams(values));
    },
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ['newsroom'] });
      navigate(next, { replace: true });
    },
  });
  return (
    <section className="adm-panel adm-login">
      <LockKeyhole size={27} aria-hidden="true" />
      <h1>Newsroom sign in</h1>
      <p>Use your administrator account to manage DevFlash.</p>
      <form className="adm-form" onSubmit={form.handleSubmit((v) => login.mutate(v))}>
        <label>
          Username
          <input autoComplete="username" required maxLength={80} {...form.register('username')} />
        </label>
        <label>
          Password
          <input
            type="password"
            autoComplete="current-password"
            required
            maxLength={256}
            {...form.register('password')}
          />
        </label>
        <button className="adm-primary" disabled={login.isPending}>
          {login.isPending ? 'Signing in…' : 'Sign in'}
        </button>
        {login.error && (
          <p className="adm-error" role="alert">
            {login.error.message}
          </p>
        )}
      </form>
    </section>
  );
}

export default function Admin() {
  const me = useAdminData<{ username: string }>('/auth/me');
  const location = useLocation();
  const navigate = useNavigate();
  const client = useQueryClient();
  const loginPage = location.pathname === '/admin/login';
  const desiredPath = location.state?.from;
  const next =
    typeof desiredPath === 'string' &&
    desiredPath.startsWith('/admin/') &&
    desiredPath !== '/admin/login'
      ? desiredPath
      : '/admin';
  useEffect(() => {
    const expire = () => {
      client.removeQueries({ queryKey: ['newsroom'] });
      navigate('/admin/login', { replace: true, state: { from: location.pathname } });
    };
    window.addEventListener('devflash-session-expired', expire);
    return () => window.removeEventListener('devflash-session-expired', expire);
  }, [client, navigate, location.pathname]);
  const logout = useMutation({
    mutationFn: () => change('POST', '/auth/logout'),
    onSuccess: async () => {
      await client.cancelQueries({ queryKey: ['newsroom'] });
      client.removeQueries({ queryKey: ['newsroom'] });
      navigate('/admin/login', { replace: true });
    },
  });
  if (me.isPending)
    return (
      <div className="adm">
        <State loading error={null} />
      </div>
    );
  if (me.error instanceof AdminError && me.error.status === 401) {
    return loginPage ? (
      <div className="adm">
        <Login next={next} />
      </div>
    ) : (
      <Navigate replace to="/admin/login" state={{ from: location.pathname }} />
    );
  }
  if (me.error)
    return (
      <div className="adm">
        <h1>Newsroom</h1>
        <State loading={false} error={me.error} />
        <button onClick={() => void me.refetch()}>Retry connection</button>
      </div>
    );
  if (loginPage) return <Navigate replace to={next} />;
  return (
    <div className="adm">
      <div className="adm-top">
        <div>
          <h1>Newsroom</h1>
          <p>Signed in as {me.data?.data.username}</p>
        </div>
        <button disabled={logout.isPending} onClick={() => logout.mutate()}>
          <LogOut size={16} />
          Sign out
        </button>
      </div>
      {logout.error && <p role="alert">{logout.error.message}</p>}
      <div className="adm-layout">
        <nav className="adm-nav" aria-label="Newsroom">
          {tabs.map(({ path, name, icon: Icon }) => (
            <NavLink end key={path} to={'/admin' + (path ? '/' + path : '')}>
              <Icon size={17} />
              {name}
            </NavLink>
          ))}
        </nav>
        <div className="adm-content">
          <Routes>
            <Route index element={<Overview />} />
            <Route path="articles" element={<Articles />} />
            <Route path="sources" element={<Sources />} />
            <Route path="topics" element={<Taxonomy key="topics" kind="topics" />} />
            <Route path="categories" element={<Taxonomy key="categories" kind="categories" />} />
            <Route path="ingestion" element={<Ingestion />} />
            <Route path="analytics" element={<Overview analytics />} />
            <Route path="settings" element={<Settings />} />
            <Route path="audit-logs" element={<AuditLog />} />
            <Route path="*" element={<Navigate to="/admin" replace />} />
          </Routes>
        </div>
      </div>
    </div>
  );
}
