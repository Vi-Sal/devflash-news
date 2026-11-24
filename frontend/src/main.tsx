import { lazy, Suspense, useLayoutEffect } from 'react';
import { createRoot } from 'react-dom/client';
import {
  BrowserRouter,
  Routes,
  Route,
  Link,
  useLocation,
  useNavigationType,
} from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReaderProvider } from './app/reader';
import { Header } from './components/Header';
import { Skeleton } from './components/common';
import { Home, SectionFeed, Missing } from './features/articles/Home';
const ArticlePage = lazy(() => import('./features/articles/ArticlePage'));
const loadPages = () => import('./features/articles/Pages');
const Explore = lazy(() => loadPages().then((p) => ({ default: p.Explore })));
const Saved = lazy(() => loadPages().then((p) => ({ default: p.Saved })));
const SearchPage = lazy(() => loadPages().then((p) => ({ default: p.SearchPage })));
const Daily = lazy(() => loadPages().then((p) => ({ default: p.Daily })));
const TrendingPage = lazy(() => loadPages().then((p) => ({ default: p.TrendingPage })));
const Sources = lazy(() => loadPages().then((p) => ({ default: p.Sources })));
const Info = lazy(() => loadPages().then((p) => ({ default: p.Info })));
import './styles/site.css';
const Topics = lazy(() =>
  import('./features/articles/Discovery').then((m) => ({ default: m.Topics })),
);
const SecurityWatch = lazy(() =>
  import('./features/articles/Discovery').then((m) => ({ default: m.SecurityWatch })),
);
const Admin = lazy(() => import('./features/admin/Admin'));
const client = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false, gcTime: 1000 * 60 * 20 } },
});
const positions = new Map<string, number>();
function Navigation() {
  const location = useLocation(),
    type = useNavigationType();
  useLayoutEffect(() => {
    document.title =
      (location.pathname === '/'
        ? 'Tech news in a flash.'
        : location.pathname.split('/').filter(Boolean).join(' · ')) + ' · DevFlash';
    history.scrollRestoration = 'manual';
    const previous = type === 'POP' ? positions.get(location.key) || 0 : 0;
    window.scrollTo(0, previous);
    const frame = requestAnimationFrame(() => window.scrollTo(0, previous));
    return () => {
      cancelAnimationFrame(frame);
      positions.set(location.key, window.scrollY);
    };
  }, [location.key, location.pathname, type]);
  return null;
}
function App() {
  return (
    <>
      <Navigation />
      <Header />
      <main id="content" className="page">
        <Suspense fallback={<Skeleton />}>
          <Routes>
            <Route path="/" element={<Home />} />
            <Route path="/explore" element={<Explore />} />
            <Route path="/saved" element={<Saved />} />
            <Route path="/search" element={<SearchPage />} />
            <Route path="/category/:slug" element={<SectionFeed type="categories" />} />
            <Route path="/topics" element={<Topics />} />
            <Route path="/security" element={<SecurityWatch />} />
            <Route path="/topics/:slug" element={<SectionFeed type="topics" />} />
            <Route path="/article/:slug" element={<ArticlePage />} />
            <Route path="/daily" element={<Daily />} />
            <Route path="/daily/:date" element={<Daily />} />
            <Route path="/trending" element={<TrendingPage />} />
            <Route path="/sources" element={<Sources />} />
            {(['about', 'terms', 'privacy'] as const).map((p) => (
              <Route key={p} path={'/' + p} element={<Info page={p} />} />
            ))}
            <Route path="/admin/*" element={<Admin />} />
            <Route path="*" element={<Missing />} />
          </Routes>
        </Suspense>
      </main>
      <footer>
        <Link to="/" className="footer-brand">
          DevFlash
        </Link>
        <nav aria-label="Footer navigation">
          {['about', 'sources', 'privacy', 'terms'].map((p) => (
            <Link to={'/' + p} key={p}>
              {p}
            </Link>
          ))}
        </nav>
      </footer>
    </>
  );
}
createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={client}>
    <BrowserRouter>
      <ReaderProvider>
        <App />
      </ReaderProvider>
    </BrowserRouter>
  </QueryClientProvider>,
);
