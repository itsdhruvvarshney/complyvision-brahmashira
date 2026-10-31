 
import { useEffect, useState } from 'react'
import { AppShell } from './components/AppShell'
import { getInspectionById, getLatestInspection } from './services/inspectionService'
import { HomePage } from './pages/HomePage'
import { LoginPage } from './pages/LoginPage'
import { DashboardPage } from './pages/DashboardPage'
import { WorkspacePage } from './pages/WorkspacePage'
import { HistoryPage, InsightsPage, ReportsPage, RulesPage, SettingsPage } from './pages/OtherPages'

function App() {
  const [page, setPage] = useState(() => window.location.hash.slice(2) || 'home')
  const [inspection, setInspection] = useState(getLatestInspection)
  useEffect(() => { const update = () => setPage(window.location.hash.slice(2) || 'home'); addEventListener('hashchange', update); return () => removeEventListener('hashchange', update) }, [])
  const navigate = (next) => { window.location.hash = `/${next}`; setPage(next) }
  const openInspection = (id) => {
    const saved = getInspectionById(id)
    if (saved) setInspection(saved)
    navigate('workspace')
  }
  const pages = { dashboard: <DashboardPage onNavigate={navigate} onOpenInspection={openInspection} />, workspace: <WorkspacePage inspection={inspection} setInspection={setInspection} />, history: <HistoryPage onOpenInspection={openInspection} />, insights: <InsightsPage inspection={inspection} />, rules: <RulesPage />, reports: <ReportsPage inspection={inspection} />, settings: <SettingsPage /> }
  if (page === 'login') return <LoginPage />
  if (page === 'home') return <HomePage />
  return <AppShell page={page} onNavigate={navigate}>{pages[page] || pages.dashboard}</AppShell>
}

export default App
