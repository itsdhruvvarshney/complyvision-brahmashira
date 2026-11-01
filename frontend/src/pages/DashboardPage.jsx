import { Fragment, useEffect, useMemo, useState } from 'react'
import { StatusBadge } from '../components/StatusBadge'
import { getInspections } from '../services/inspectionService'
import { formatInspectionDate } from '../services/workspaceModel'
import { OrganisationDashboard } from './OrganisationDashboard'

const STATUS = (inspection) => inspection.report?.summary?.overall_status || inspection.status || 'REVIEW'

export function DashboardPage({ role = 'inspector', onNavigate, onOpenInspection }) {
  const [inspections, setInspections] = useState(getInspections)
  useEffect(() => {
    const refresh = () => setInspections(getInspections())
    window.addEventListener('complyvision:history-updated', refresh)
    window.addEventListener('storage', refresh)
    return () => { window.removeEventListener('complyvision:history-updated', refresh); window.removeEventListener('storage', refresh) }
  }, [])

  const completed = inspections.filter((item) => item.evaluated && item.report)
  const isSupreme = role === 'supreme'
  const isOrganisation = role === 'organisation'
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

  if (isOrganisation) return <OrganisationDashboard />
  if (isSupreme || role === 'inspector') return <CaseOperationsDashboard role={role} />

  return <div className="page-content">
    <div className="page-heading"><div><p className="kicker">{isSupreme ? 'Supreme Authority' : isOrganisation ? 'Organisation' : 'Inspector'} · Overview</p><h1>{isSupreme ? 'Supreme Authority dashboard.' : isOrganisation ? 'Pre-verification dashboard.' : 'Inspector dashboard.'}</h1><p>{isSupreme ? 'Review inspection activity and saved reports available in this browser.' : isOrganisation ? 'Review label evaluations available to your account.' : 'Review inspection activity and assigned cases.'}</p></div><button className="primary-button" onClick={() => onNavigate('history')}>Review reports →</button></div>
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

function CaseOperationsDashboard({ role }) {
  const isSupreme = role === 'supreme'
  const [data, setData] = useState({ cases: [], counts: {}, inspectors: [] })
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('all')
  const [inspectorChoice, setInspectorChoice] = useState({})
  const [remarks, setRemarks] = useState({})
  const [expandedCase, setExpandedCase] = useState('')
  const [busyCase, setBusyCase] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  async function request(path, method = 'GET', body) {
    const response = await fetch(`/api/portal${path}`, {
      method,
      headers: {
        'Content-Type': 'application/json',
        'x-cv-role': role,
        'x-cv-actor-id': sessionStorage.getItem('cv-actor-id') || 'demo-user',
      },
      ...(body ? { body: JSON.stringify(body) } : {}),
    })
    const result = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(result.error || `Request failed (${response.status}).`)
    return result
  }
  async function load() {
    setError('')
    try { setData(await request('/dashboard')) }
    catch (loadError) { setError(loadError.message || 'Could not load cases.') }
  }
  useEffect(() => {
    load()
    const refreshTimer = window.setInterval(load, 15000)
    return () => window.clearInterval(refreshTimer)
  }, [role])

  const filtered = useMemo(() => data.cases.filter((item) => {
    const searchable = `${item.id} ${item.organization} ${item.product} ${item.authority} ${item.inspector}`.toLowerCase()
    const matchesStatus = status === 'all' || (status === 'decided' ? ['passed', 'failed'].includes(item.status) : item.status === status)
    return searchable.includes(query.toLowerCase()) && matchesStatus
  }), [data.cases, query, status])

  async function act(caseId, path, body) {
    setBusyCase(caseId); setError(''); setNotice('')
    try { await request(`/cases/${caseId}/${path}`, 'POST', body); setNotice(`${caseId} updated.`); await load() }
    catch (actionError) { setError(actionError.message || 'The action could not be completed.') }
    finally { setBusyCase('') }
  }

  const heading = isSupreme ? 'Authority assignment desk' : 'Your inspection assignments'
  const description = isSupreme
    ? 'Review cases assigned to you, choose an inspector, and follow decisions.'
    : 'Work through your assigned cases, record the decision, and add inspection remarks.'
  const decidedCount = (data.counts.passed || 0) + (data.counts.failed || 0)
  const statItems = isSupreme
    ? [[data.counts.assigned_to_supreme || 0, 'Awaiting inspector', 'assigned_to_supreme'], [data.counts.assigned_to_inspector || 0, 'With inspectors', 'assigned_to_inspector'], [data.counts.in_progress || 0, 'In progress', 'in_progress'], [decidedCount, 'Decided', 'decided']]
    : [[data.cases.length, 'Assigned to you', 'all'], [data.counts.assigned_to_inspector || 0, 'Ready to start', 'assigned_to_inspector'], [data.counts.in_progress || 0, 'In progress', 'in_progress'], [decidedCount, 'Decided', 'decided']]
  const stageCounts = [
    ['assigned_to_supreme', 'Awaiting inspector', data.counts.assigned_to_supreme || 0],
    ['assigned_to_inspector', 'With inspectors', data.counts.assigned_to_inspector || 0],
    ['in_progress', 'In progress', data.counts.in_progress || 0],
    ['decided', 'Decided', decidedCount],
  ].filter(([, , count]) => count > 0)
  const caseIds = new Set(data.cases.map((item) => item.id))
  const recentActivity = (data.events || []).filter((event) => caseIds.has(event.case_id)).slice(0, 5)
  const caseById = new Map(data.cases.map((item) => [item.id, item]))
  const activityLabel = { case_submitted: 'Case submitted', authority_assigned: 'Authority assigned', inspector_assigned: 'Inspector assigned', inspection_started: 'Inspection started', decision_recorded: 'Decision recorded' }

  return <div className="page-content role-dashboard">
    <div className="page-heading"><div><p className="kicker">{isSupreme ? 'Supreme Authority' : 'Inspector'} portal</p><h1>{heading}</h1><p>{description}</p></div><div className="dashboard-mode-chip">{data.mode === 'demo' ? 'Local preview data' : 'Live cases'}</div></div>
    {error && <div className="notice error" role="alert">{error}<button className="link-button" onClick={load}>Retry</button></div>}
    {notice && <div className="notice success" role="status">{notice}</div>}
    <section className="role-stat-grid" aria-label="Case status summary">{statItems.map(([value, label, filter], index) => <button type="button" aria-pressed={status === filter} className={`role-stat role-stat-${index}`} key={label} onClick={() => setStatus(filter)}><span>{label}</span><strong>{value}</strong><small>{status === filter ? (filter === 'all' ? 'Showing all cases' : 'Showing this stage') : 'Filter cases by stage'}</small></button>)}</section>
    <section className="role-overview-grid">
      <article className="panel role-overview-panel">
        <div className="panel-heading"><div><h2>Case flow</h2><p>Current status across your assigned cases</p></div><button className="link-button" onClick={() => setStatus('all')}>Clear filter</button></div>
        {stageCounts.length ? <>
          <div className="role-flow-bar" aria-label={`${data.cases.length} total cases`}>{stageCounts.map(([key, label, count]) => <button type="button" key={key} className={`role-flow-segment ${key}`} style={{ flexGrow: count }} title={`${label}: ${count}`} aria-label={`${label}: ${count}`} aria-pressed={status === key} onClick={() => setStatus(key)} />)}</div>
          <div className="role-stage-legend">{stageCounts.map(([key, label, count]) => <button type="button" key={key} aria-pressed={status === key} onClick={() => setStatus(key)}><i className={key} /><span>{label}</span><b>{count}</b></button>)}</div>
        </> : <div className="empty-state role-empty-state"><b>No case activity yet</b><p>New assignments will appear here.</p></div>}
      </article>
      <article className="panel role-overview-panel role-activity-panel">
        <div className="panel-heading"><div><h2>Recent activity</h2><p>Latest updates from your case queue</p></div><button className="link-button" onClick={load}>Refresh</button></div>
        {recentActivity.length ? <div className="role-activity-list">{recentActivity.map((event, index) => { const item = caseById.get(event.case_id); return <button type="button" className="role-activity-item" key={`${event.case_id}-${event.event_type}-${index}`} onClick={() => { setQuery(event.case_id); setStatus('all') }}><i className={`role-activity-dot ${event.to_status || ''}`} /><span><strong>{activityLabel[event.event_type] || event.event_type.replaceAll('_', ' ')}</strong><small>{item?.id} · {item?.organization || ''}</small></span><time>{formatInspectionDate(event.created_at)}</time></button> })}</div> : <div className="empty-state role-empty-state"><b>No recent updates</b><p>Case updates will appear here.</p></div>}
      </article>
    </section>
    <section className="panel role-queue-panel">
      <div className="role-queue-heading"><div><p className="kicker">{isSupreme ? 'Inspector assignment' : 'Field work'}</p><h2>{isSupreme ? 'Case assignment queue' : 'Assigned case queue'}</h2><p>{isSupreme ? 'Choose an inspector for cases awaiting assignment.' : 'Review case details, add remarks, and record your decision.'}</p></div><button className="secondary-button" onClick={load}>Refresh queue</button></div>
      <div className="role-filter-row"><label className="role-search"><span aria-hidden="true">⌕</span><input className="search-input" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search case, organisation, or product" aria-label="Search cases" /></label><select value={status} onChange={(event) => setStatus(event.target.value)} aria-label="Filter cases by status"><option value="all">All stages</option>{['assigned_to_supreme','assigned_to_inspector','in_progress','decided','passed','failed'].map((item) => <option value={item} key={item}>{item === 'decided' ? 'Decided' : item.replaceAll('_',' ')}</option>)}</select><span className="role-result-count">{filtered.length} {filtered.length === 1 ? 'case' : 'cases'}</span></div>
      {filtered.length ? <div className="role-case-table-wrap"><table className="role-case-table"><thead><tr><th>Case / organisation</th><th>Product</th><th>Current stage</th><th>{isSupreme ? 'Inspector' : 'Authority'}</th><th>Submitted</th><th>Action</th></tr></thead><tbody>{filtered.map((item) => <Fragment key={item.id}>
        <tr className={expandedCase === item.id ? 'case-row-open' : ''}>
          <td><strong>{item.id}</strong><small>{item.organization}</small></td><td><strong>{item.product}</strong><small>{item.inspectionId || 'Official verification'}</small></td>
          <td><StatusBadge status={item.status === 'passed' ? 'PASS' : item.status === 'failed' ? 'FAIL' : item.status === 'in_progress' ? 'REVIEW' : 'NOT_APPLICABLE'} /><small className="case-stage-label">{item.status_label || item.status.replaceAll('_',' ')}</small></td>
          <td><strong>{isSupreme ? item.inspector : item.authority}</strong></td><td><time>{new Date(item.submittedAt).toLocaleDateString()}</time></td>
          <td>{isSupreme && item.status === 'assigned_to_supreme' ? <div className="case-action-inline"><select aria-label={`Select inspector for ${item.id}`} value={inspectorChoice[item.id] || ''} onChange={(event) => setInspectorChoice((current) => ({ ...current, [item.id]: event.target.value }))}><option value="">Choose inspector</option>{data.inspectors.filter((person) => person.status === 'active').map((person) => <option key={person.id} value={person.id}>{person.name} · {person.region}</option>)}</select><button className="primary-button compact-action" disabled={busyCase === item.id || !inspectorChoice[item.id]} onClick={() => act(item.id, 'assign-inspector', { inspector_id: inspectorChoice[item.id] })}>{busyCase === item.id ? '…' : 'Assign'}</button></div> : <button type="button" className="case-expand-button" aria-expanded={expandedCase === item.id} onClick={() => setExpandedCase((current) => current === item.id ? '' : item.id)}>{expandedCase === item.id ? 'Close details' : isSupreme ? 'View details' : ['assigned_to_inspector','in_progress'].includes(item.status) ? 'Review case' : 'View details'}</button>}</td>
        </tr>
        {expandedCase === item.id && <tr className="case-detail-row"><td colSpan="6"><div className="case-detail-content">
          <div><p className="case-detail-title">Case history</p>{item.timeline?.length ? <ol className="case-mini-timeline">{item.timeline.slice().reverse().map((event, index) => <li key={`${event.event_type}-${index}`}><i /><span><b>{activityLabel[event.event_type] || event.event_type.replaceAll('_',' ')}</b><small>{formatInspectionDate(event.created_at)}{event.note ? ` · ${event.note}` : ''}</small></span></li>)}</ol> : <p className="case-detail-muted">No case events recorded.</p>}</div>
          {!isSupreme && ['assigned_to_inspector','in_progress'].includes(item.status) ? <div className="case-decision-inline"><label>Inspection remarks<textarea value={remarks[item.id] || ''} onChange={(event) => setRemarks((current) => ({ ...current, [item.id]: event.target.value }))} placeholder="Record findings and evidence reviewed" rows="2" /></label><div className="case-inline-actions">{item.status === 'assigned_to_inspector' && <button className="secondary-button" disabled={busyCase === item.id} onClick={() => act(item.id, 'start')}>{busyCase === item.id ? 'Starting…' : 'Start inspection'}</button>}<button className="primary-button" disabled={busyCase === item.id || !(remarks[item.id] || '').trim()} onClick={() => act(item.id, 'decision', { result: 'passed', remarks: remarks[item.id] })}>Mark passed</button><button className="danger-button" disabled={busyCase === item.id || !(remarks[item.id] || '').trim()} onClick={() => act(item.id, 'decision', { result: 'failed', remarks: remarks[item.id] })}>Mark failed</button></div></div> : <div className="case-detail-summary"><p className="case-detail-title">{item.result ? 'Recorded decision' : 'Assignment'}</p><p>{item.result ? `${item.result}: ${item.remarks}` : `Assigned authority: ${item.authority}. Inspector: ${item.inspector}.`}</p></div>}
        </div></td></tr>}
      </Fragment>)}</tbody></table></div> : <div className="empty-state"><b>No cases match these filters</b><p>Clear the search or select another stage to see more cases.</p><button className="link-button" onClick={() => { setQuery(''); setStatus('all') }}>Clear filters</button></div>}
    </section>
    <div className="role-dashboard-footer"><span>Case changes are held by the local demo API and reset when it restarts.</span></div>
  </div>
}
