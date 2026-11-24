import { useEffect, useState } from 'react';
import { Link, NavLink, useNavigate, useLocation } from 'react-router-dom';
import { Search, Sun, Moon, Monitor, Check } from 'lucide-react';
import * as Dialog from '@radix-ui/react-dialog';
import { DialogContent } from './DialogContent';
import { useReader, write, read } from '../app/reader';
import { useData } from './common';
import type { Site } from '../services/news';
export function Header() {
  const navigate = useNavigate(),
    location = useLocation(),
    { saved, theme, setTheme } = useReader();
  const [term, setTerm] = useState(''),
    [open, setOpen] = useState(false);
  const site = useData<Site>('/site'),
    ThemeIcon = theme === 'dark' ? Moon : theme === 'system' ? Monitor : Sun;
  useEffect(() => {
    setTerm(new URLSearchParams(location.search).get('q') || '');
  }, [location.search, location.pathname]);
  function search(e: React.FormEvent) {
    e.preventDefault();
    const query = term.trim();
    if (query)
      write(
        'recent',
        [query, ...read<string[]>('recent', []).filter((s) => s !== query)].slice(0, 6),
      );
    navigate('/search' + (query ? '?q=' + encodeURIComponent(query) : ''));
  }
  return (
    <header className="topbar glass">
      <a href="#content" className="skip">
        Skip to content
      </a>
      <Link to="/" className="brand" aria-label="DevFlash home">
        <img src="/art/brand.svg" alt="" width="38" height="38" />
        <span>{site.data?.siteTitle || 'DevFlash'}</span>
      </Link>
      <nav className="main-nav" aria-label="Main navigation">
        {[
          ['/', 'Home'],
          ['/explore', 'Explore'],
          ['/daily', 'Daily Briefing'],
          ['/security', 'Security Watch'],
          ['/saved', 'Saved'],
        ].map(([path, label]) => (
          <NavLink key={path} end to={path}>
            <span className={path === '/daily' || path === '/security' ? 'nav-wide' : undefined}>
              {label}
            </span>
            {path === '/daily' && <span className="nav-short">Briefing</span>}
            {path === '/security' && <span className="nav-short">Security</span>}
            {path === '/saved' && saved.length > 0 && <small>{saved.length}</small>}
          </NavLink>
        ))}
      </nav>
      <div className="header-tools">
        <form role="search" className="header-search" onSubmit={search}>
          <Search size={19} />
          <input
            aria-label="Search tech news"
            placeholder="Search tech news…"
            value={term}
            maxLength={160}
            onChange={(e) => setTerm(e.target.value)}
          />
        </form>
        <Link className="icon-only mobile-search" to="/search" aria-label="Search tech news">
          <Search size={21} />
        </Link>
        <Dialog.Root open={open} onOpenChange={setOpen}>
          <Dialog.Trigger asChild>
            <button
              className="icon-only theme-trigger"
              aria-label="Choose theme"
              title="Choose theme"
            >
              <ThemeIcon size={23} />
            </button>
          </Dialog.Trigger>
          <DialogContent className="theme-dialog">
            <Dialog.Title>Appearance</Dialog.Title>
            <Dialog.Description>Choose your reading theme.</Dialog.Description>
            {(
              [
                ['light', 'Glacier Light', Sun],
                ['dark', 'Midnight Glacier', Moon],
                ['system', 'System preference', Monitor],
              ] as const
            ).map(([value, label, Icon]) => (
              <button
                key={value}
                aria-pressed={value === theme}
                onClick={() => {
                  setTheme(value);
                  setOpen(false);
                }}
              >
                <Icon size={18} />
                {label}
                {value === theme && <Check size={17} />}
              </button>
            ))}
          </DialogContent>
        </Dialog.Root>
      </div>
    </header>
  );
}
