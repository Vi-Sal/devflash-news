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
export function localDay(date = new Date()) {
  return [date.getFullYear(), date.getMonth() + 1, date.getDate()]
    .map((part) => String(part).padStart(2, '0'))
    .join('-');
}
interface ReadingEntry {
  id: number;
  day: string;
  minutes: number;
  topics: string[];
}
interface Reader {
  following: string[];
  toggleFollow: (slug: string) => void;
  goal: number;
  setGoal: (minutes: number) => void;
  reading: ReadingEntry[];
  setRead: (story: Story, read: boolean) => void;
  saved: Story[];
  ids: number[];
  toggle: (a: Story) => void;
  mark: (story: Story) => void;
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
  const [following, setFollowing] = useState<string[]>(() => read('following', []));
  const [goal, setGoal] = useState<number>(() => read('reading-goal', 10));
  const [reading, setReading] = useState<ReadingEntry[]>(() => read('reading-activity', []));
  useEffect(() => {
    write('following', following);
  }, [following]);
  useEffect(() => {
    write('reading-goal', goal);
  }, [goal]);
  useEffect(() => {
    write('reading-activity', reading);
  }, [reading]);

  function toggleFollow(slug: string) {
    setFollowing((old) =>
      old.includes(slug) ? old.filter((x) => x !== slug) : old.length < 12 ? [...old, slug] : old,
    );
    if (!following.includes(slug) && following.length >= 12)
      notify('You can follow up to 12 topics.');
  }

  const setRead = useCallback((story: Story, isRead: boolean) => {
    setIds((old) => {
      if (!isRead) return old.filter((id) => id !== story.id);
      return old.includes(story.id) ? old : [...old.slice(-999), story.id];
    });
    const day = localDay();
    setReading((old) => {
      if (!isRead) return old.filter((item) => item.id !== story.id || item.day !== day);
      if (old.some((item) => item.id === story.id && item.day === day)) return old;
      const cutoff = new Date();
      cutoff.setDate(cutoff.getDate() - 366);
      const keep = localDay(cutoff);
      return [
        ...old.filter((item) => item.day >= keep).slice(-4999),
        { id: story.id, day, minutes: story.minutes, topics: story.topics },
      ];
    });
  }, []);
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
    (story: Story) => {
      setRead(story, true);
      const { slug } = story;
      void event(slug, 'view').then((recorded) => {
        if (!recorded) return;
        void client.invalidateQueries({ queryKey: ['feed'], refetchType: 'none' });
        void client.invalidateQueries({ queryKey: ['/articles/' + slug], exact: true });
      });
    },
    [client, setRead],
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
    <ReaderContext.Provider
      value={{
        saved,
        ids,
        toggle,
        mark,
        share,
        theme,
        setTheme,
        following,
        toggleFollow,
        goal,
        setGoal,
        reading,
        setRead,
      }}
    >
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
