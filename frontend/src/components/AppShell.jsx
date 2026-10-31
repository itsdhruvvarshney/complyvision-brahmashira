const icons = { dashboard: '▦', workspace: '⌖', history: '◷', insights: '◉', rules: '§', reports: '↗', settings: '⚙' }
const navItems = [['dashboard', 'Dashboard'], ['workspace', 'Inspection workspace'], ['history', 'Inspection history'], ['insights', 'Accessibility insights'], ['rules', 'Rule management'], ['reports', 'Analytics & reports'], ['settings', 'Settings']]

export function AppShell({ page, onNavigate, children }) {
  return <div className="app-shell">
    <aside className="sidebar"><a className="brand" href="#"><i>✓</i>ComplyVision</a><span className="workspace-label">WORKSPACE</span><nav aria-label="Product navigation">{navItems.map(([id, label]) => <button className={page === id ? 'active' : ''} onClick={() => onNavigate(id)} key={id}><b>{icons[id]}</b><span>{label}</span></button>)}</nav></aside>
    <section className="app-content"><header className="app-topbar"><div><span className="eyebrow"><i />Legal Metrology workspace</span></div><div className="top-actions"><button className="text-button" onClick={() => onNavigate('workspace')}>New inspection</button><button className="avatar" aria-label="Open profile">AS</button></div></header>{children}</section>
  </div>
}
