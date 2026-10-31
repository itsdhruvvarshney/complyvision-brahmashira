import { useEffect, useMemo, useState } from 'react'
import { StatusBadge } from '../components/StatusBadge'
import { getInspections } from '../services/inspectionService'
import { formatInspectionDate } from '../services/workspaceModel'

const STATUS = (inspection) => inspection.report?.summary?.overall_status || inspection.status || 'REVIEW'

export function DashboardPage({ onNavigate, onOpenInspection }) {
  const [inspections, setInspections] = useState(getInspections)
  useEffect(() => {
    const refresh = () => setInspections(getInspections())
    window.addEventListener('complyvision:history-updated', refresh)
    window.addEventListener('storage', refresh)
    return () => { window.removeEventListener('complyvision:history-updated', refresh); window.removeEventListener('storage', refresh) }
  }, [])

  const completed = inspections.filter((item) => item.evaluated && item.report)
  const metrics = useMemo(() => {
    const pass = completed.filter((item) => STATUS(item) === 'PASS').length
    const failed = completed.filter((item) => STATUS(item) === 'FAIL').length
    const review = completed.filter((item) => STATUS(item) === 'REVIEW').length
    const rules = completed.flatMap((item) => item.report?.rule_results || [])
    const issues = new Map()
    for (const rule of rules) {
      if (!['FAIL', 'REVIEW'].includes(rule.status)) continue
      const label = rule.description || rule.field_name || rule.rule_id || 'Other review'
      const entry = issues.get(label) || { label, count: 0, failed: 0 }
      entry.count += 1
      if (rule.status === 'FAIL') entry.failed += 1
      issues.set(label, entry)
    }
    const recentMonths = Array.from({ length: 6 }, (_, index) => {
      const date = new Date()
      date.setDate(1)
      date.setMonth(date.getMonth() - (5 - index))
      return { date, label: new Intl.DateTimeFormat(undefined, { month: 'short' }).format(date), count: 0, pass: 0 }
    })
    for (const item of completed) {
      const date = new Date(item.timestamp || item.createdAt)
      if (Number.isNaN(date.getTime())) continue
      const month = recentMonths.find((candidate) => candidate.date.getFullYear() === date.getFullYear() && candidate.date.getMonth() === date.getMonth())
      if (month) { month.count += 1; if (STATUS(item) === 'PASS') month.pass += 1 }
    }
    const max = Math.max(1, ...recentMonths.map((month) => month.count))
    return { pass, failed, review, issues: [...issues.values()].sort((a, b) => b.count - a.count).slice(0, 5), months: recentMonths.map((month) => ({ ...month, height: month.count ? Math.max(12, month.count / max * 100) : 4 })) }
  }, [completed])

  return <div className="page-content">
    <div className="page-heading"><div><p className="kicker">Overview</p><h1>Inspection intelligence, at a glance.</h1><p>Metrics and issues are calculated from locally saved evaluations.</p></div><button className="primary-button" onClick={() => onNavigate('workspace')}>Start an inspection →</button></div>
    <section className="stat-grid">
      {[[completed.length, 'Total inspections', 'Saved in this browser'], [metrics.pass, 'Passed reports', 'All applicable checks passed'], [metrics.review, 'Need review', 'Human confirmation needed'], [metrics.failed, 'Failed reports', 'At least one failed rule']].map(([value, label, detail], index) => <article className={`stat-card stat-${index}`} key={label}><span>{label}</span><strong>{value}</strong><small>{detail}</small></article>)}
    </section>
    <section className="dashboard-grid">
      <article className="panel trend-panel"><div className="panel-heading"><div><h2>Inspection volume</h2><p>Saved reports by month · last 6 months</p></div></div>{completed.length ? <div className="chart" aria-label="Saved inspection volume by month">{metrics.months.map((month) => <i title={`${month.count} inspection(s)`} style={{ height: `${month.height}%` }} key={`${month.date.getFullYear()}-${month.date.getMonth()}`}><span>{month.label}</span></i>)}</div> : <div className="empty-state"><b>No saved evaluations yet</b><p>Run an image evaluation to build your local analytics.</p></div>}</article>
      <article className="panel"><div className="panel-heading"><div><h2>Common review findings</h2><p>Rule outcomes across saved reports</p></div></div>{metrics.issues.length ? <ul className="issues-list">{metrics.issues.map((issue) => <li key={issue.label}><span>{issue.label}</span><b title={`${issue.failed} failed`}>{issue.count}</b></li>)}</ul> : <div className="empty-state"><b>No review findings</b><p>Findings will appear after reports are saved.</p></div>}</article>
      <article className="panel activity-panel"><div className="panel-heading"><div><h2>Recent inspections</h2><p>Latest saved reports</p></div><button className="link-button" onClick={() => onNavigate('history')}>View all</button></div>{completed.length ? completed.slice(0, 5).map((item) => <button className="activity-row activity-button" key={item.id} onClick={() => onOpenInspection(item.id)}><span className="document-icon">▤</span><span><strong>{item.product || 'Package inspection'}</strong><small>{item.id} · {formatInspectionDate(item.timestamp || item.createdAt)}</small></span><StatusBadge status={STATUS(item)} /></button>) : <div className="empty-state"><b>No inspection history</b><p>Your completed reports will appear here.</p></div>}</article>
    </section>
  </div>
}
