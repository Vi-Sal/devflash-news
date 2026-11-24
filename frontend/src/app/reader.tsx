import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { event, type Story } from '../services/news';
const key = 'devflash:' + (__DEMO__ ? 'sample:' : 'live:');
export function read<T>(name: string, initial: T): T {
  try {
    return JSON.parse(localStorage.getItem(key + name) || 'null') ?? initial;
  } catch {
    return initial;
  }
}
export function write(name: string, value: unknown) {
  try {
    localStorage.setItem(key + name, JSON.stringify(value));
  } catch {
    /* Private browsing can disable storage. */
  }
}
type Theme = 'light' | 'dark' | 'system';
interface Reader {
  saved: Story[];
  ids: number[];
  toggle: (a: Story) => void;
  mark: (id: number, slug: string) => void;
  share: (path: string, title: string, a?: Story) => Promise<void>;
  theme: Theme;
  setTheme: (t: Theme) => void;
}
const ReaderContext = createContext<Reader | null>(null);
export function ReaderProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient();
  const [saved, setSaved] = useState<Story[]>(() => read('saved', [])),
    [ids, setIds] = useState<number[]>(() => read('read', [])),
    [theme, setTheme] = useState<Theme>(() => read('theme', 'light')),
    [message, notify] = useState('');
  useEffect(() => {
    write('saved', saved);
  }, [saved]);
  useEffect(() => {
    write('read', ids);
  }, [ids]);
  useEffect(() => {
    const media = matchMedia('(prefers-color-scheme: dark)');
    const update = () => {
      document.documentElement.dataset.theme =
        theme === 'system' ? (media.matches ? 'dark' : 'light') : theme;
    };
    update();
    write('theme', theme);
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, [theme]);
  useEffect(() => {
    if (message) {
      const timer = setTimeout(() => notify(''), 2200);
      return () => clearTimeout(timer);
    }
  }, [message]);
  const mark = useCallback(
    (id: number, slug: string) => {
      setIds((old) => (old.includes(id) ? old : [...old.slice(-999), id]));
      void event(slug, 'view').then((recorded) => {
        if (!recorded) return;
        void client.invalidateQueries({ queryKey: ['feed'], refetchType: 'none' });
        void client.invalidateQueries({ queryKey: ['/articles/' + slug], exact: true });
      });
    },
    [client],
  );
  function toggle(a: Story) {
    const exists = saved.some((s) => s.id === a.id);
    setSaved((old) => (exists ? old.filter((s) => s.id !== a.id) : [a, ...old]));
    notify(exists ? 'Removed from Saved' : 'Article saved');
  }
  async function share(path: string, title: string, a?: Story) {
    const url = new URL(path, location.origin).href;
    try {
      if (navigator.share) {
        await navigator.share({ title, url });
      } else {
        await navigator.clipboard.writeText(url);
        notify('Link copied');
      }
      if (a) event(a.slug, 'share');
    } catch (err) {
      if (!(err instanceof DOMException && err.name === 'AbortError'))
        notify('Sharing unavailable. Copy the address from your browser.');
    }
  }
  return (
    <ReaderContext.Provider value={{ saved, ids, toggle, mark, share, theme, setTheme }}>
      {children}
      <div className={'toast' + (message ? ' shown' : '')} role="status">
        {message}
      </div>
    </ReaderContext.Provider>
  );
}
export function useReader() {
  return useContext(ReaderContext)!;
}
