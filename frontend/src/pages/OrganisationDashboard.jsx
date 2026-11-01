import { useEffect, useMemo, useState } from 'react'
import { StatusBadge } from '../components/StatusBadge'
import { formatInspectionDate } from '../services/workspaceModel'

const portalRequest = async (path, { method = 'GET', body } = {}) => {
  const response = await fetch(`/api/portal${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', 'x-cv-role': 'organisation', 'x-cv-actor-id': sessionStorage.getItem('cv-actor-id') || 'preview@complyvision.local' },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  const result = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(result.error || `Request failed (${response.status}).`)
  return result
}

function downloadReport(item) {
  const blob = new Blob([JSON.stringify(item, null, 2)], { type: 'application/json' })
  const href = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = href; anchor.download = `${item.id}-preview-report.json`; anchor.click()
  URL.revokeObjectURL(href)
}

function printReport(item) {
  const safe = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]))
  const fields = (item.fields || []).map((field) => `<tr><td>${safe(field.label)}</td><td>${safe(field.value)}</td><td>${safe(field.status)}</td><td>${safe(field.confidence)}%</td><td>${safe(field.explanation)}</td></tr>`).join('')
  const html = `<!doctype html><meta charset="utf-8"><title>${safe(item.id)} report</title><style>body{font:14px Arial,sans-serif;color:#16264c;max-width:900px;margin:40px auto}h1{font-size:24px}p{color:#53627e}table{width:100%;border-collapse:collapse;margin-top:24px}th,td{text-align:left;padding:10px;border-bottom:1px solid #dce3ef;font-size:12px}th{background:#f3f6fb}button{padding:9px 14px;border:0;border-radius:7px;background:#1749d2;color:white;font-weight:700}@media print{button{display:none}}</style><h1>${safe(item.brand)} ${safe(item.product)} · ${safe(item.id)}</h1><p>${safe(item.category)} · Overall ${safe(item.status)} · Confidence ${safe(item.confidence)}%</p><p>Preview report only. Sample output; no FSSAI analyzer was run.</p><button onclick="window.print()">Print / Save PDF</button><table><thead><tr><th>Declaration</th><th>Value</th><th>Status</th><th>Confidence</th><th>Explanation</th></tr></thead><tbody>${fields}</tbody></table>`
  const url = URL.createObjectURL(new Blob([html], { type: 'text/html' }))
  const popup = window.open(url, '_blank')
  if (!popup) { URL.revokeObjectURL(url); return false }
  window.setTimeout(() => URL.revokeObjectURL(url), 60000)
  return true
}

export function OrganisationDashboard() {
  const [section, setSection] = useState('overview')
  const [data, setData] = useState({ counts: {}, items: [], activity: [], trend: [] })
  const [officialCases, setOfficialCases] = useState([])
  const [selectedId, setSelectedId] = useState('')
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('all')
  const [language, setLanguage] = useState('en')
  const [concise, setConcise] = useState(false)
  const [product, setProduct] = useState('')
  const [brand, setBrand] = useState('')
  const [category, setCategory] = useState('')
  const [frontFile, setFrontFile] = useState(null)
  const [backFile, setBackFile] = useState(null)
  const [days, setDays] = useState(30)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  async function refresh() {
    setError('')
    try {
      const [pre, cases] = await Promise.all([
        portalRequest(`/preverifications?days=${days}`),
        portalRequest(`/organizations/${encodeURIComponent(sessionStorage.getItem('cv-actor-id') || 'preview@complyvision.local')}/cases`),
      ])
      setData(pre)
      setOfficialCases(cases.cases || [])
      setSelectedId((current) => current || pre.items?.[0]?.id || '')
    } catch (loadError) { setError(loadError.message || 'Could not load the pre-verification dashboard.') }
  }
  useEffect(() => { refresh() }, [days])

  const selected = data.items.find((item) => item.id === selectedId) || null
  const filtered = useMemo(() => data.items.filter((item) => {
    const searchable = `${item.id} ${item.product} ${item.brand} ${item.category}`.toLowerCase()
    return searchable.includes(query.toLowerCase()) && (status === 'all' || item.status === status)
  }), [data.items, query, status])

  function chooseImage(side, file) {
    setError(''); setNotice('')
    if (!file) return
    if (!['image/jpeg', 'image/png'].includes(file.type)) { setError('Choose a JPEG or PNG label image.'); return }
    if (file.size > 10 * 1024 * 1024) { setError('Each image must be 10 MB or smaller.'); return }
    if (side === 'front') setFrontFile(file); else setBackFile(file)
  }

  async function createPreverification(event) {
    event.preventDefault(); setError(''); setNotice('')
    if (!product.trim() || !brand.trim() || !category || !frontFile || !backFile) { setError('Add the product name, brand, category, and both label images.'); return }
    setBusy(true)
    try {
      const result = await portalRequest('/preverifications', { method: 'POST', body: { product, brand, category, front_image_name: frontFile.name, back_image_name: backFile.name, organization: sessionStorage.getItem('cv-organization') || 'Preview organisation' } })
      await refresh(); setSelectedId(result.item.id); setSection('history'); setNotice('Sample pre-verification created. No image data was uploaded and no FSSAI analyzer was run.')
      setProduct(''); setBrand(''); setCategory(''); setFrontFile(null); setBackFile(null)
    } catch (submitError) { setError(submitError.message || 'Could not prepare the pre-verification.') }
    finally { setBusy(false) }
  }

  async function rerun(item) {
    setBusy(true); setError(''); setNotice('')
    try { const result = await portalRequest(`/preverifications/${item.id}/rerun`, { method: 'POST' }); await refresh(); setSelectedId(result.item.id); setNotice('Sample evaluation refreshed.') }
    catch (runError) { setError(runError.message || 'Could not rerun this evaluation.') }
    finally { setBusy(false) }
  }

  async function savePreferences() {
    setBusy(true); setError(''); setNotice('')
    try { const result = await portalRequest('/preferences', { method: 'PUT', body: { summary_language: language, concise_summaries: concise } }); setLanguage(result.preferences.summary_language); setConcise(result.preferences.concise_summaries); setNotice('Preview preferences saved for this API session.') }
    catch (saveError) { setError(saveError.message || 'Could not save preferences.') }
    finally { setBusy(false) }
  }

  async function requestOfficialVerification(item) {
    setBusy(true); setError(''); setNotice('')
    try {
      const result = await portalRequest('/cases', { method: 'POST', body: { organization: sessionStorage.getItem('cv-organization') || 'Preview organisation', product: `${item.brand} ${item.product}`.trim(), inspection_id: item.id } })
      await refresh(); setSection('cases'); setNotice(`Sample official case ${result.case.id} was submitted.`)
    } catch (requestError) { setError(requestError.message || 'Could not submit this case.') }
    finally { setBusy(false) }
  }

  useEffect(() => { portalRequest('/preferences').then(({ preferences }) => { setLanguage(preferences.summary_language); setConcise(preferences.concise_summaries) }).catch(() => {}) }, [])

  const tabs = [['overview','Overview'],['new','New pre-verification'],['history','History'],['cases','Official case status'],['settings','Settings']]
  return <div className="page-content preverification-dashboard">
    <div className="page-heading"><div><p className="kicker">Organisation portal</p><h1>Pre-verification workspace</h1><p>Review sample label checks and follow official verification cases for your organisation.</p></div><span className="dashboard-mode-chip">{data.mode === 'demo' ? 'Preview data' : 'Live data'}</span></div>
    <nav className="preverify-tabs" aria-label="Pre-verification sections">{tabs.map(([id, label]) => <button className={section === id ? 'active' : ''} key={id} onClick={() => { setSection(id); setError(''); setNotice('') }}>{label}</button>)}</nav>
    {error && <div className="notice error" role="alert">{error}<button className="link-button" onClick={refresh}>Retry</button></div>}
    {notice && <div className="notice success" role="status">{notice}</div>}

    {section === 'overview' && <>
      <section className="stat-grid preverify-stat-grid" aria-label="Pre-verification status summary">{[[data.counts.total || 0,'Pre-verifications','View all label checks','all'],[data.counts.pass || 0,'Passed','View passed checks','PASS'],[data.counts.review || 0,'Need review','View checks needing review','REVIEW'],[data.counts.fail || 0,'Failed','View failed checks','FAIL']].map(([value,label,caption,filter],index) => <button type="button" aria-pressed={section === 'history' && status === filter} className={`stat-card stat-${index} preverify-stat-button`} key={label} onClick={() => { const match = filter === 'all' ? data.items[0] : data.items.find((item) => item.status === filter); setStatus(filter); setSelectedId(match?.id || ''); setSection('history') }}><span>{label}</span><strong>{value}</strong><small>{caption} →</small></button>)}</section>
      <section className="dashboard-grid preverify-overview-grid"><article className="panel trend-panel"><div className="panel-heading"><div><h2>Pre-verification activity</h2><p>Sample checks over the selected period</p></div><select aria-label="Activity period" value={days} onChange={(event) => setDays(Number(event.target.value))}><option value={30}>Last 30 days</option><option value={90}>Last 90 days</option></select></div><div className="chart" aria-label={`Pre-verifications over ${days} days`}>{(data.trend || []).map((point) => <i key={point.label} style={{ height: `${Math.max(8, point.count * 24)}%` }} title={`${point.count} checks`}><span>{point.label}</span></i>)}</div></article><article className="panel"><div className="panel-heading"><div><h2>Recent activity</h2><p>Latest preview evaluations</p></div><button className="link-button" onClick={() => setSection('history')}>History →</button></div>{data.activity?.slice(0,4).map((item) => <button className="activity-row activity-button" key={item.id} onClick={() => { setSelectedId(item.id); setSection('history') }}><span className="document-icon">▤</span><span><strong>{item.product}</strong><small>{item.id} · {formatInspectionDate(item.created_at)}</small></span><StatusBadge status={item.status} /></button>)}</article></section>
      <section className="panel org-case-panel"><div className="panel-heading"><div><h2>Official verification status</h2><p>Cases submitted for Legal Metrology review</p></div><button className="link-button" onClick={() => setSection('cases')}>Track cases →</button></div>{officialCases.length ? officialCases.slice(0,2).map((item) => <CaseTimeline key={item.id} item={item} compact />) : <div className="empty-state"><b>No official cases yet</b><p>Submit a pre-verification for official follow-up when the service is connected.</p></div>}</section>
    </>}

    {section === 'new' && <form className="panel org-new-form" onSubmit={createPreverification}><p className="kicker">New pre-verification</p><h2>Check a product label</h2><p className="portal-muted">Add the product details and front/back label images. This preview creates sample results only.</p><div className="org-form-grid"><label>Product name<input value={product} onChange={(event) => setProduct(event.target.value)} placeholder="Product name" /></label><label>Brand<input value={brand} onChange={(event) => setBrand(event.target.value)} placeholder="Brand name" /></label><label>Category<select value={category} onChange={(event) => setCategory(event.target.value)}><option value="">Select category</option>{['Food','Beverage','Dairy','Snacks','Personal care','Other'].map((value) => <option key={value}>{value}</option>)}</select></label></div><div className="org-form-grid image-upload-grid"><label className="org-upload">Front label image<input type="file" accept="image/jpeg,image/png" onChange={(event) => chooseImage('front', event.target.files?.[0])} /><small>{frontFile ? frontFile.name : 'JPEG / PNG · Up to 10 MB'}</small></label><label className="org-upload">Back label image<input type="file" accept="image/jpeg,image/png" onChange={(event) => chooseImage('back', event.target.files?.[0])} /><small>{backFile ? backFile.name : 'JPEG / PNG · Up to 10 MB'}</small></label></div><div className="preview-callout">Preview mode: selected files are not uploaded or retained. Analyzer output below is sample data, not an FSSAI compliance decision.</div><button className="primary-button" type="submit" disabled={busy}>{busy ? 'Preparing…' : 'Run sample pre-verification'}</button></form>}

    {section === 'history' && <section className="panel"><div className="panel-heading"><div><p className="kicker">Searchable records</p><h2>Pre-verification history</h2></div><button className="secondary-button" onClick={refresh}>Refresh</button></div><div className="preverify-filters"><input className="search-input" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search product, brand, or ID" /><select value={status} onChange={(event) => setStatus(event.target.value)}><option value="all">All statuses</option>{['PASS','FAIL','REVIEW'].map((value) => <option key={value}>{value}</option>)}</select></div><div className="responsive-table"><table><thead><tr><th>Pre-verification</th><th>Product</th><th>Date</th><th>Confidence</th><th>Status</th><th>Actions</th></tr></thead><tbody>{filtered.map((item) => <tr key={item.id}><td><button className="link-button" onClick={() => setSelectedId(item.id)}>{item.id}</button></td><td><strong>{item.brand} {item.product}</strong><small className="table-subtitle">{item.category}</small></td><td>{formatInspectionDate(item.createdAt)}</td><td>{item.confidence}%</td><td><StatusBadge status={item.status} /></td><td><div className="table-actions"><button className="link-button" onClick={() => setSelectedId(item.id)}>View</button><button className="link-button" disabled={busy} onClick={() => rerun(item)}>Re-run</button><button className="link-button" onClick={() => downloadReport(item)}>JSON</button></div></td></tr>)}</tbody></table></div>{!filtered.length && <div className="empty-state"><b>No matching pre-verifications</b></div>}
      {selected && <div className="org-report-detail"><div className="panel-heading"><div><p className="kicker">Sample analysis result</p><h2>{selected.brand} {selected.product}</h2><p>{selected.id} · {selected.category} · {formatInspectionDate(selected.createdAt)}</p></div><StatusBadge status={selected.status} /></div><p className="preview-callout">{selected.analysisNote || 'Preview output only. The FSSAI analyzer and private image storage are not connected yet.'}</p><div className="responsive-table"><table><thead><tr><th>Declaration</th><th>Extracted value</th><th>Result</th><th>Confidence</th><th>Rule explanation</th></tr></thead><tbody>{selected.fields.map((field) => <tr key={field.key}><td><strong>{field.label}</strong></td><td>{field.value}</td><td><StatusBadge status={field.status} /></td><td>{field.confidence}%</td><td>{field.rule}: {field.explanation}</td></tr>)}</tbody></table></div><div className="table-actions"><button className="secondary-button" disabled={busy} onClick={() => rerun(selected)}>Re-run evaluation</button><button className="secondary-button" onClick={() => downloadReport(selected)}>Download JSON report</button><button className="secondary-button" onClick={() => { if (!printReport(selected)) setError('Allow pop-ups to print or save a PDF.') }}>Print / Save PDF</button><button className="secondary-button" disabled={busy} onClick={() => requestOfficialVerification(selected)}>Request official verification</button></div></div>}
    </section>}

    {section === 'cases' && <section className="panel"><div className="panel-heading"><div><p className="kicker">Legal Metrology</p><h2>Official case tracker</h2><p>See current stage and case history.</p></div></div>{officialCases.length ? officialCases.map((item) => <CaseTimeline key={item.id} item={item} />) : <div className="empty-state"><b>No official verification cases</b><p>Official case submissions will appear here.</p></div>}</section>}

    {section === 'settings' && <section className="panel org-settings"><p className="kicker">Profile and preferences</p><h2>Organisation settings</h2><div className="org-form-grid"><label>Summary language<select value={language} onChange={(event) => setLanguage(event.target.value)}><option value="en">English</option><option value="hi">Hindi</option><option value="ta">Tamil</option></select></label><label className="org-checkbox"><input type="checkbox" checked={concise} onChange={(event) => setConcise(event.target.checked)} />Use concise summaries</label></div><button className="primary-button" disabled={busy} onClick={savePreferences}>{busy ? 'Saving…' : 'Save preferences'}</button><p className="preview-callout">Preview preferences are stored in the local API process and reset when it restarts.</p></section>}
  </div>
}

function CaseTimeline({ item, compact = false }) {
  const steps = ['submitted','assigned_to_supreme','assigned_to_inspector','in_progress', ...(item.status === 'passed' || item.status === 'failed' ? [item.status] : [])]
  return <article className="org-official-case"><div className="panel-heading"><div><small>{item.id} · {item.organization || 'Your organisation'}</small><h3>{item.product}</h3></div><StatusBadge status={item.status === 'passed' ? 'PASS' : item.status === 'failed' ? 'FAIL' : 'REVIEW'} /></div><p className="portal-muted">Current stage: {item.status_label || item.status.replaceAll('_',' ')} · Authority: {item.authority} · Inspector: {item.inspector}</p>{!compact && <ol className="org-case-timeline">{(item.timeline || []).map((event, index) => <li key={`${event.event_type}-${index}`}><b>{event.event_type.replaceAll('_',' ')}</b><small>{formatInspectionDate(event.created_at)}{event.note ? ` · ${event.note}` : ''}</small></li>)}</ol>}</article>
}
