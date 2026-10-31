 
import { useEffect, useState } from 'react'
import { AppShell } from './components/AppShell'
import { demoInspection } from './data/demoData'
import { HomePage } from './pages/HomePage'
import { DashboardPage } from './pages/DashboardPage'
import { WorkspacePage } from './pages/WorkspacePage'
import { HistoryPage, InsightsPage, ReportsPage, RulesPage, SettingsPage } from './pages/OtherPages'

function App() {
  const [page, setPage] = useState(() => window.location.hash.slice(2) || 'home')
  const [inspection, setInspection] = useState(demoInspection)
  useEffect(() => { const update = () => setPage(window.location.hash.slice(2) || 'home'); addEventListener('hashchange', update); return () => removeEventListener('hashchange', update) }, [])
  const navigate = (next) => { window.location.hash = `/${next}`; setPage(next) }
  const pages = { dashboard: <DashboardPage onNavigate={navigate} />, workspace: <WorkspacePage inspection={inspection} setInspection={setInspection} />, history: <HistoryPage />, insights: <InsightsPage inspection={inspection} />, rules: <RulesPage />, reports: <ReportsPage inspection={inspection} />, settings: <SettingsPage /> }
  if (page === 'home') return <HomePage />
  return <AppShell page={page} onNavigate={navigate}>{pages[page] || pages.dashboard}</AppShell>
}

export default App
