export type Theme = 'light' | 'dark';

export function getTheme(): Theme {
  const stored = localStorage.getItem('nodum-theme') as Theme;
  if (stored === 'light' || stored === 'dark') return stored;
  return 'dark';
}

export function setTheme(theme: Theme) {
  localStorage.setItem('nodum-theme', theme);
  document.documentElement.classList.remove('light', 'dark');
  document.documentElement.classList.add(theme);
}

export function toggleTheme() {
  const current = getTheme();
  const newTheme = current === 'light' ? 'dark' : 'light';
  setTheme(newTheme);
  return newTheme;
}

export function initTheme() {
  setTheme(getTheme());
}
