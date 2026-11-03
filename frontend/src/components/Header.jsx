import { useEffect, useState } from 'react'

const CheckMark = () => <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12.5 4.5 4.5L19 7.5" /></svg>

function userInitials(user) {
  const actorId = sessionStorage.getItem('cv-actor-id') || ''
  const name = user?.user_metadata?.full_name || user?.user_metadata?.display_name || user?.user_metadata?.name || actorId || user?.email || ''
  const words = String(name).replace(/[@._-]+/g, ' ').trim().split(/\s+/).filter(Boolean)
  return (words.length > 1 ? `${words[0][0]}${words.at(-1)[0]}` : words[0]?.slice(0, 2) || 'U').toUpperCase()
}

export function Header({ authenticated = false, user }) {
  const [theme, setTheme] = useState(() => localStorage.getItem('cv-theme') || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'))
  const [large, setLarge] = useState(() => localStorage.getItem('cv-large') === 'true')
  const [contrast, setContrast] = useState(() => localStorage.getItem('cv-contrast') === 'true')

  useEffect(() => { document.documentElement.dataset.theme = theme; localStorage.setItem('cv-theme', theme) }, [theme])
  useEffect(() => { document.documentElement.classList.toggle('large-text', large); localStorage.setItem('cv-large', large) }, [large])
  useEffect(() => { document.documentElement.dataset.contrast = contrast ? 'high' : ''; localStorage.setItem('cv-contrast', contrast) }, [contrast])

  return <nav className="site-nav" aria-label="Main navigation">
    <a className="brand" href="#top"><i><CheckMark /></i>ComplyVision</a>
    <div className="nav-actions">
      <div className="nav-links"><a href="#/dashboard">Dashboard</a><a href="#/history">History</a><a href="#/rules">Rules</a><a href="#/reports">Reports</a><a href="#/settings">Settings</a></div>
      <button className="icon-button type-button" type="button" aria-pressed={large} aria-label="Larger text" onClick={() => setLarge(!large)}>A+</button>
      <button className="icon-button type-button" type="button" aria-pressed={contrast} aria-label="High contrast" onClick={() => setContrast(!contrast)}>◐</button>
      <button className="icon-button" type="button" aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`} onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}>{theme === 'dark' ? '☀' : '☾'}</button>
      {authenticated
        ? <a className="sign-in signed-in-user" href="#/settings" aria-label="Open your account settings" title="Open account settings">{userInitials(user)}</a>
        : <a className="sign-in" href="#/login">Sign in</a>}
    </div>
  </nav>
}
