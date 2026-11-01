 
import { useEffect, useState } from 'react'
import { AppShell } from './components/AppShell'
import { getInspectionById, getLatestInspection } from './services/inspectionService'
import { HomePage } from './pages/HomePage'
import { LoginPage } from './pages/LoginPage'
import { DashboardPage } from './pages/DashboardPage'
import { WorkspacePage } from './pages/WorkspacePage'
import { HistoryPage, InsightsPage, ReportsPage, RulesPage, SettingsPage } from './pages/OtherPages'
import { supabase, supabaseConfigured } from './lib/supabaseClient'

function App() {
  const [page, setPage] = useState(() => window.location.hash.slice(2) || 'home')
  const [session, setSession] = useState(null)
  const [authLoading, setAuthLoading] = useState(true)
  const [demoAuthenticated, setDemoAuthenticated] = useState(() => sessionStorage.getItem('cv-demo-authenticated') === 'true')
  const [role, setRole] = useState(() => sessionStorage.getItem('cv-role') || 'inspector')
  const authenticated = Boolean(session || demoAuthenticated)
  const [inspection, setInspection] = useState(getLatestInspection)
  useEffect(() => { const update = () => setPage(window.location.hash.slice(2) || 'home'); addEventListener('hashchange', update); return () => removeEventListener('hashchange', update) }, [])
  useEffect(() => {
    if (!supabaseConfigured) { setAuthLoading(false); return undefined }
    let alive = true
    supabase.auth.getSession().then(({ data }) => {
      if (!alive) return
      setSession(data.session)
      setAuthLoading(false)
      const user = data.session?.user
      if (user) {
        const authRole = user.app_metadata?.role
        const loginId = user.app_metadata?.login_id || user.email?.split('@')[0] || ''
        setRole(['organisation', 'organization', 'business_owner'].includes(authRole) ? 'organisation' : loginId.toUpperCase().startsWith('S') ? 'supreme' : 'inspector')
      }
    }).catch(() => { if (alive) setAuthLoading(false) })
    const { data: { subscription } } = supabase.auth.onAuthStateChange((_event, nextSession) => {
      setSession(nextSession)
      if (!nextSession && sessionStorage.getItem('cv-demo-authenticated') !== 'true') setRole('inspector')
    })
    return () => { alive = false; subscription.unsubscribe() }
  }, [])
  const navigate = (next) => { window.location.hash = `/${next}`; setPage(next) }
  const signOut = async () => {
    if (session) await supabase?.auth.signOut()
    setSession(null)
    setDemoAuthenticated(false)
    sessionStorage.removeItem('cv-demo-authenticated')
    sessionStorage.removeItem('cv-role')
    sessionStorage.removeItem('cv-actor-id')
    setRole('inspector')
    navigate('login')
  }
  const openInspection = (id) => {
    const saved = getInspectionById(id)
    if (saved) setInspection(saved)
    navigate('workspace')
  }
  const pages = { dashboard: <DashboardPage onNavigate={navigate} onOpenInspection={openInspection} />, workspace: <WorkspacePage inspection={inspection} setInspection={setInspection} />, history: <HistoryPage onOpenInspection={openInspection} />, insights: <InsightsPage inspection={inspection} />, rules: <RulesPage />, reports: <ReportsPage inspection={inspection} />, settings: <SettingsPage user={session?.user} role={role} /> }
  const login = <LoginPage onAuthenticated={(nextRole, actorId, demoMode = false) => {
    sessionStorage.setItem('cv-role', nextRole)
    sessionStorage.setItem('cv-actor-id', actorId)
    setRole(nextRole)
    if (demoMode || !supabaseConfigured) { sessionStorage.setItem('cv-demo-authenticated', 'true'); setDemoAuthenticated(true) }
  }} />
  if (page === 'login') return login
  if (page === 'home') return <HomePage />
  if (authLoading) return <main className="login-page" role="status">Checking your sign-in…</main>
  if (!authenticated) { if (page !== 'login') window.location.hash = '/login'; return login }
  if (page === 'dashboard/supreme' && role !== 'supreme') { window.location.hash = role === 'organisation' ? '/dashboard' : '/dashboard/inspector'; return null }
  if (page === 'dashboard/inspector' && role !== 'inspector') { window.location.hash = role === 'organisation' ? '/dashboard' : '/dashboard/supreme'; return null }
  const dashboardRole = page === 'dashboard/supreme' ? 'supreme' : page === 'dashboard/inspector' ? 'inspector' : role
  const appPage = page.startsWith('dashboard/') ? 'dashboard' : page
  const content = appPage === 'dashboard' ? <DashboardPage role={dashboardRole} onNavigate={navigate} onOpenInspection={openInspection} /> : pages[appPage] || pages.dashboard
  return <AppShell page={appPage} role={dashboardRole} user={session?.user} onNavigate={navigate} onSignOut={signOut}>{content}</AppShell>
}

export default App
