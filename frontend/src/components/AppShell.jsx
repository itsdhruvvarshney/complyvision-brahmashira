import { useEffect, useRef, useState } from 'react'

const icons = { dashboard: '▦', history: '◷', rules: '§', reports: '↗', settings: '⚙' }
const navItems = [['dashboard', 'Dashboard'], ['history', 'Inspection history'], ['rules', 'Rule management'], ['reports', 'Analytics & reports'], ['settings', 'Settings']]

const portalNames = { supreme: 'Supreme Authority', inspector: 'Inspector', organisation: 'Organisation', legal_metrology: 'Legal Metrology' }
const accessScopes = {
  supreme: 'Review cases assigned to your authority and assign them to inspectors.',
  inspector: 'Review cases assigned to you and record inspection decisions.',
  organisation: 'Manage this organisation’s pre-verification requests and reports.',
  legal_metrology: 'Manage Legal Metrology cases and portal assignments.',
}

export function AppShell({ page, role, user, onNavigate, onSignOut, children }) {
  const [profileOpen, setProfileOpen] = useState(false)
  const profileRef = useRef(null)
  const portalLabel = role === 'supreme' ? 'Supreme Authority portal' : role === 'organisation' ? 'Organisation portal' : 'Inspector portal'
  const portalName = portalNames[role] || 'Inspector'
  const accountId = sessionStorage.getItem('cv-actor-id') || user?.email || 'Not available'
  const displayName = user?.user_metadata?.full_name || user?.user_metadata?.display_name || user?.user_metadata?.name || sessionStorage.getItem('cv-organization') || accountId
  const sessionKind = sessionStorage.getItem('cv-demo-authenticated') === 'true' ? 'Local preview account' : user ? 'Verified account' : 'Local account'

  useEffect(() => {
    if (!profileOpen) return undefined
    const closeOnOutsideClick = (event) => { if (!profileRef.current?.contains(event.target)) setProfileOpen(false) }
    const closeOnEscape = (event) => { if (event.key === 'Escape') setProfileOpen(false) }
    document.addEventListener('pointerdown', closeOnOutsideClick)
    document.addEventListener('keydown', closeOnEscape)
    return () => { document.removeEventListener('pointerdown', closeOnOutsideClick); document.removeEventListener('keydown', closeOnEscape) }
  }, [profileOpen])

  return <div className="app-shell">
    <aside className="sidebar"><a className="brand" href="#"><i>✓</i>ComplyVision</a><span className="workspace-label">PORTAL</span><nav aria-label="Product navigation">{navItems.map(([id, label]) => <button className={page === id ? 'active' : ''} onClick={() => onNavigate(id)} key={id}><b>{icons[id]}</b><span>{label}</span></button>)}</nav></aside>
    <section className="app-content"><header className="app-topbar"><div><span className="eyebrow"><i />{portalLabel}</span></div><div className="top-actions"><div className="profile-menu-wrap" ref={profileRef}><button className="avatar" type="button" aria-label="Open profile" title="Open profile" aria-haspopup="dialog" aria-expanded={profileOpen} onClick={() => setProfileOpen((open) => !open)}>AS</button>{profileOpen && <section className="profile-popover" role="dialog" aria-label="Profile and account details"><div className="profile-popover-head"><span className="profile-avatar-large">AS</span><div><p className="kicker">My profile</p><h2>{displayName}</h2><small>{portalName}</small></div></div><dl className="profile-details"><div><dt>Account ID</dt><dd>{accountId}</dd></div><div><dt>Portal</dt><dd>{portalName}</dd></div><div><dt>Sign-in</dt><dd>{sessionKind}</dd></div><div><dt>Account status</dt><dd><span className="profile-active"><i />Active</span></dd></div></dl><div className="profile-access"><strong>Access scope</strong><p>{accessScopes[role] || accessScopes.inspector}</p></div><button className="profile-signout" type="button" onClick={onSignOut}><span aria-hidden="true">↪</span>Sign out</button></section>}</div></div></header>{children}</section>
  </div>
}
