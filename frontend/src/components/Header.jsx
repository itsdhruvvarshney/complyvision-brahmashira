import { useEffect, useState } from 'react'

const CheckMark = () => <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12.5 4.5 4.5L19 7.5" /></svg>

export function Header() {
  const [theme, setTheme] = useState(() => localStorage.getItem('cv-theme') || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'))
  const [large, setLarge] = useState(() => localStorage.getItem('cv-large') === 'true')
  const [contrast, setContrast] = useState(() => localStorage.getItem('cv-contrast') === 'true')

  useEffect(() => { document.documentElement.dataset.theme = theme; localStorage.setItem('cv-theme', theme) }, [theme])
  useEffect(() => { document.documentElement.classList.toggle('large-text', large); localStorage.setItem('cv-large', large) }, [large])
  useEffect(() => { document.documentElement.dataset.contrast = contrast ? 'high' : ''; localStorage.setItem('cv-contrast', contrast) }, [contrast])

  return <nav className="site-nav" aria-label="Main navigation">
    <a className="brand" href="#top"><i><CheckMark /></i>ComplyVision</a>
    <div className="nav-actions">
      <div className="nav-links"><a href="#/dashboard">Dashboard</a><a href="#/workspace">Workspace</a><a href="#/history">History</a><a href="#/insights">Accessibility</a><a href="#/rules">Rules</a><a href="#/reports">Reports</a><a href="#/settings">Settings</a></div>
      <button className="icon-button type-button" type="button" aria-pressed={large} aria-label="Larger text" onClick={() => setLarge(!large)}>A+</button>
      <button className="icon-button type-button" type="button" aria-pressed={contrast} aria-label="High contrast" onClick={() => setContrast(!contrast)}>◐</button>
      <button className="icon-button" type="button" aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`} onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}>{theme === 'dark' ? '☀' : '☾'}</button>
      <a className="sign-in" href="#/login">Sign in</a>
    </div>
  </nav>
}
