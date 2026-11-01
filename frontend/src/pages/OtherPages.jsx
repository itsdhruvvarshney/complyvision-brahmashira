import { useEffect, useMemo, useState } from 'react'
import { StatusBadge } from '../components/StatusBadge'
import { downloadJson, downloadMarkdown, getInspectionById, getInspections, printReport } from '../services/inspectionService'
import { fetchRuleCatalog } from '../services/inspectionService'
import { formatInspectionDate } from '../services/workspaceModel'

const statusOf = (inspection) => inspection?.report?.summary?.overall_status || inspection?.status || 'REVIEW'
const metric = (value, digits = 2) => Number.isFinite(Number(value)) ? Number(value).toFixed(digits) : 'Not reported'
const displayValue = (value) => {
  if (value == null || value === '') return '—'
  if (Array.isArray(value)) return value.map(displayValue).filter((item) => item !== '—').join(', ')
  if (typeof value === 'object') return Object.entries(value).filter(([, item]) => item != null && item !== '').map(([key, item]) => `${key.replaceAll('_', ' ')}: ${displayValue(item)}`).join(' · ') || '—'
  return String(value)
}

export function HistoryPage({ onOpenInspection }) {
  const [query, setQuery] = useState('')
  const [inspections, setInspections] = useState(getInspections)
  useEffect(() => {
    const refresh = () => setInspections(getInspections())
    window.addEventListener('complyvision:history-updated', refresh)
    window.addEventListener('storage', refresh)
    return () => { window.removeEventListener('complyvision:history-updated', refresh); window.removeEventListener('storage', refresh) }
  }, [])
  const data = useMemo(() => inspections.filter((item) => `${displayValue(item.product)} ${item.id} ${statusOf(item)}`.toLowerCase().includes(query.toLowerCase())), [inspections, query])
  return <div className="page-content"><div className="page-heading"><div><p className="kicker">Record keeping</p><h1>Inspection history</h1><p>Saved evaluations are kept in this browser profile.</p></div><span className="stat-inline">{inspections.length} saved reports</span></div><div className="panel table-panel"><input className="search-input" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search product, inspection ID, or status" aria-label="Search inspections" />{data.length ? <div className="responsive-table"><table><thead><tr><th>Inspection</th><th>Product</th><th>Created</th><th>Status</th><th>Actions</th></tr></thead><tbody>{data.map((item) => <tr key={item.id}><td>{item.id}</td><td><strong>{displayValue(item.product) || 'Package inspection'}</strong></td><td>{formatInspectionDate(item.timestamp || item.createdAt)}</td><td><StatusBadge status={statusOf(item)} /></td><td><div className="table-actions"><button className="link-button" onClick={() => onOpenInspection(item.id)}>View report</button><button className="link-button" onClick={() => downloadMarkdown(item)}>Markdown</button><button className="link-button" onClick={() => downloadJson(item)}>JSON</button></div></td></tr>)}</tbody></table></div> : <div className="empty-state"><b>{inspections.length ? 'No matching reports' : 'No saved inspections yet'}</b><p>{inspections.length ? 'Try a different search.' : 'Completed analyses will be saved here for this browser.'}</p></div>}</div></div>
}

export function InsightsPage({ inspection }) {
  const [lang, setLang] = useState('en')
  const actualFields = (inspection.fields || []).filter((field) => field.present)
  const copy = inspection.evaluated
    ? `${inspection.product}. ${actualFields.map((field) => `${field.label}: ${field.value}`).join('. ')}. Overall status: ${statusOf(inspection)}.`
    : 'Run an inspection to create an accessibility summary from extracted package declarations.'
  const speak = () => {
    if (!window.speechSynthesis || !window.SpeechSynthesisUtterance) return
    const utterance = new SpeechSynthesisUtterance(copy)
    utterance.lang = lang === 'en' ? 'en-IN' : `${lang}-IN`
    window.speechSynthesis.cancel(); window.speechSynthesis.speak(utterance)
  }
  return <div className="page-content"><div className="page-heading"><div><p className="kicker">Accessibility insights</p><h1>Product information, independently understood.</h1><p>Listen to a concise summary built from the current inspection values.</p></div></div><div className="insight-grid"><section className="panel audio-panel"><span className="eyebrow"><i />Speech synthesis preview</span><h2>{displayValue(inspection.product)}</h2><div className="language-select">{[['en','English'],['hi','हिंदी'],['ta','தமிழ்']].map(([id,label]) => <button className={lang === id ? 'active' : ''} onClick={() => setLang(id)} key={id}>{label}</button>)}</div><p className="audio-copy">{lang === 'en' ? copy : `The available extracted report is currently summarized in English. Your browser may not have a ${lang === 'hi' ? 'Hindi' : 'Tamil'} speech voice installed.`}</p><button className="primary-button" onClick={speak} disabled={!inspection.evaluated}>▶ Play audio summary</button></section><section className="panel metrics-panel"><h2>Current inspection</h2><div><strong>{inspection.evaluated ? actualFields.length : '—'}</strong><span>declarations detected</span></div><div><strong>{inspection.evaluated ? statusOf(inspection) : '—'}</strong><span>overall status</span></div><div><strong>{inspection.evaluated ? `${inspection.confidence}%` : '—'}</strong><span>mandatory-field confidence</span></div></section></div></div>
}

export function RulesPage() {
  const [rules, setRules] = useState([])
  const [query, setQuery] = useState('')
  const [support, setSupport] = useState('all')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const load = async () => {
    setLoading(true); setError('')
    try { setRules(await fetchRuleCatalog()) }
    catch (loadError) { setError(loadError.message || 'Could not load the rules matrix.') }
    finally { setLoading(false) }
  }
  useEffect(() => { load() }, [])
  const filtered = useMemo(() => rules.filter((rule) => {
    const content = [rule.rule_id, rule.rule_number, rule.sub_rule, rule.requirement_name, rule.requirement_description, rule.legal_source, rule.exemption_condition].join(' ').toLowerCase()
    return content.includes(query.toLowerCase()) && (support === 'all' || (support === 'supported' ? rule.prototype_supported : !rule.prototype_supported))
  }), [rules, query, support])
  return <div className="page-content"><div className="page-heading"><div><p className="kicker">Rule catalogue</p><h1>Legal Metrology rule matrix</h1><p>Rule descriptions, applicability notes, exemptions, support coverage, and cited legal sources from the bundled catalog.</p></div><span className="stat-inline">{rules.length} rules</span></div><div className="rule-controls"><input className="search-input" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search rules, declarations, or sources" aria-label="Search rule catalog" /><select aria-label="Filter rule support" value={support} onChange={(event) => setSupport(event.target.value)}><option value="all">All rules</option><option value="supported">Prototype supported</option><option value="unsupported">Manual / not supported</option></select></div>{loading ? <div className="panel empty-state" role="status">Loading rule catalog…</div> : error ? <div className="panel empty-state"><b>Rule catalog unavailable</b><p>{error}</p><button className="secondary-button" onClick={load}>Retry</button></div> : filtered.length ? <div className="rule-grid">{filtered.map((rule) => <article className="panel rule-card catalog-rule" key={rule.rule_id}><div className="rule-card-meta"><span>{rule.legal_source || `Rule ${rule.rule_number}`}</span><span className={`rule-support ${rule.prototype_supported ? 'supported' : ''}`}>{rule.prototype_supported ? 'Implemented' : 'Manual / not implemented'}</span></div><h2>{rule.requirement_name || rule.field_name || rule.rule_id}</h2><p>{rule.requirement_description}</p><dl><div><dt>Rule ID</dt><dd>{rule.rule_id}</dd></div><div><dt>Declaration</dt><dd>{rule.field_name || 'General'}</dd></div>{rule.applicability_condition && <div><dt>Applies when</dt><dd>{rule.applicability_condition}</dd></div>}{rule.exemption_condition && <div><dt>Exemptions / limits</dt><dd>{rule.exemption_condition}</dd></div>}<div><dt>Implementation</dt><dd>{rule.prototype_supported ? 'Supported by this prototype' : 'Manual review or not implemented'}</dd></div><div><dt>Source document</dt><dd>{rule.source_document || 'Not specified in matrix'}</dd></div></dl>{rule.notes && <details><summary>Notes and scope limits</summary><p>{rule.notes}</p></details>}</article>)}</div> : <div className="panel empty-state"><b>No rules match this filter</b></div>}</div>
}

export function ReportsPage({ inspection }) {
  const [inspections, setInspections] = useState(getInspections)
  const [selectedId, setSelectedId] = useState(inspection.evaluated ? inspection.id : '')
  useEffect(() => {
    const refresh = () => setInspections(getInspections())
    window.addEventListener('complyvision:history-updated', refresh)
    window.addEventListener('storage', refresh)
    return () => { window.removeEventListener('complyvision:history-updated', refresh); window.removeEventListener('storage', refresh) }
  }, [])
  useEffect(() => { if (inspection.evaluated) setSelectedId(inspection.id) }, [inspection.id, inspection.evaluated])
  const reportInspection = useMemo(() => selectedId ? getInspectionById(selectedId) || (inspection.id === selectedId ? inspection : null) : null, [selectedId, inspections, inspection])
  const report = reportInspection?.report || {}
  const summary = report.summary || {}
  const reviewRules = (report.rule_results || []).filter((rule) => ['FAIL', 'REVIEW'].includes(rule.status))
  const quality = report.quality || {}
  const evidence = report.evidence || {}
  const contrastTargets = Object.entries(evidence.contrast?.targets || {})
  const measurement = evidence.numeral_height || {}
  const exportPdf = () => { if (!reportInspection || !printReport(reportInspection)) window.alert('Allow pop-ups to print this report as a PDF.') }
  return <div className="page-content"><div className="page-heading"><div><p className="kicker">Reports and analytics</p><h1>Inspection reports</h1><p>Review saved analysis results, violations, evidence, and measurements.</p></div></div>
    <div className="report-toolbar"><label>Inspection<select value={selectedId} onChange={(event) => setSelectedId(event.target.value)}><option value="">Select a saved report</option>{inspections.map((item) => <option value={item.id} key={item.id}>{item.id} · {item.product || 'Package'} · {statusOf(item)}</option>)}</select></label>{reportInspection && <div className="report-actions"><button className="secondary-button" onClick={() => downloadMarkdown(reportInspection)}>Export Markdown</button><button className="primary-button" onClick={exportPdf}>Export PDF</button><button className="secondary-button" onClick={() => downloadJson(reportInspection)}>Export JSON</button></div>}</div>
    {!reportInspection ? <div className="panel empty-state"><b>No saved report selected</b><p>Complete an image evaluation to see a canonical report here.</p></div> : <>
      <section className="report-summary-grid"><article className="panel report-overall"><span className="kicker">Overall result</span><h2><StatusBadge status={summary.overall_status || reportInspection.status} /></h2><p>{summary.reason || 'Review the rule results and supporting evidence below.'}</p><small>{report.disclaimer || 'Prototype decision support only; not an official compliance certificate.'}</small></article>{[['PASS',summary.pass_count],['FAIL',summary.fail_count],['REVIEW',summary.review_count],['N/A',summary.not_applicable_count]].map(([label,value]) => <article className="panel report-count" key={label}><span>{label}</span><strong>{value ?? 0}</strong></article>)}</section>
      <section className="panel canonical-report"><div className="panel-heading"><div><h2>{displayValue(reportInspection.product)}</h2><p>{displayValue(reportInspection.id)} · {formatInspectionDate(reportInspection.timestamp || reportInspection.createdAt)}</p></div></div><h3>Extracted declarations</h3><div className="responsive-table"><table><thead><tr><th>Declaration</th><th>Extracted value</th><th>Result</th><th>Confidence</th><th>OCR evidence</th></tr></thead><tbody>{(reportInspection.fields || []).map((field) => <tr key={field.id}><td><strong>{displayValue(field.label)}</strong>{field.ruleId && <small className="table-subtitle">{displayValue(field.ruleId)}</small>}</td><td>{displayValue(field.value)}</td><td><StatusBadge status={displayValue(field.status)} /></td><td>{displayValue(field.confidence)}%</td><td>{displayValue(field.rawText) || 'No OCR evidence linked'}</td></tr>)}</tbody></table></div>
        <h3>Rule evaluation and legal sources</h3>{report.rule_results?.length ? <div className="responsive-table"><table><thead><tr><th>Rule</th><th>Declaration</th><th>Result</th><th>Explanation</th><th>Legal source</th></tr></thead><tbody>{report.rule_results.map((rule, index) => <tr key={`${displayValue(rule.rule_id)}-${index}`}><td>{displayValue(rule.rule_id)}</td><td>{displayValue(rule.field_name)}</td><td><StatusBadge status={displayValue(rule.status)} /></td><td>{displayValue(rule.reason || rule.description)}{rule.evidence?.length > 0 && <details className="table-evidence"><summary>{rule.evidence.length} evidence item(s)</summary>{rule.evidence.map((item, evidenceIndex) => <p key={evidenceIndex}>{displayValue(item.ocr_text || item.value || item.evidence_type)} · confidence {item.confidence == null ? 'not reported' : `${Math.round(item.confidence * 100)}%`}</p>)}</details>}</td><td>{displayValue(rule.legal_source) || 'No legal source supplied'}</td></tr>)}</tbody></table></div> : <p>No rule evaluations were returned by the analysis service.</p>}
      <div className="report-grid report-metrics"><section><h3>Image quality</h3><dl className="metric-list"><div><dt>Status</dt><dd>{quality.usable == null ? 'Not reported' : quality.usable ? 'Usable' : 'Review required'}</dd></div><div><dt>Image dimensions</dt><dd>{report.image?.width || '—'} × {report.image?.height || '—'} px</dd></div><div><dt>Blur score</dt><dd>{metric(quality.blur_score)}</dd></div><div><dt>Brightness</dt><dd>{metric(quality.brightness)}</dd></div><div><dt>Glare ratio</dt><dd>{quality.glare_ratio == null ? 'Not reported' : `${metric(quality.glare_ratio * 100)}%`}</dd></div><div><dt>Skew</dt><dd>{quality.skew_angle_degrees == null ? 'Not reported' : `${metric(quality.skew_angle_degrees)}°`}</dd></div><div><dt>Text contrast</dt><dd>{metric(quality.text_contrast)}</dd></div><div><dt>Warnings</dt><dd>{[...(quality.issues || []), ...(quality.warnings || [])].join(', ') || 'None reported'}</dd></div></dl></section><section><h3>Numeral height</h3><dl className="metric-list"><div><dt>Measured height</dt><dd>{measurement.estimated_numeral_height_mm == null ? 'Not measured' : `${metric(measurement.estimated_numeral_height_mm)} mm`}</dd></div><div><dt>Status</dt><dd>{measurement.measurement_status || 'Not reported'}</dd></div><div><dt>Confidence</dt><dd>{measurement.measurement_confidence == null ? 'Not reported' : `${Math.round(measurement.measurement_confidence * 100)}%`}</dd></div><div><dt>Scale calibration</dt><dd>{measurement.calibration_detected ? `Detected · ${metric(measurement.pixels_per_mm)} px/mm` : 'Not detected'}</dd></div><div><dt>Validation</dt><dd>{measurement.validation_status || measurement.unresolved_reason || 'No validation result'}</dd></div></dl><h3>Contrast measurements</h3>{contrastTargets.length ? <dl className="metric-list">{contrastTargets.map(([name,item]) => <div key={name}><dt>{name.replaceAll('_',' ')}</dt><dd>Ratio {metric(item.contrast_ratio)} · ΔE {metric(item.lab_color_difference ?? item.lab_difference)} · {item.status || 'Unrated'} · {item.confidence == null ? 'confidence not reported' : `${Math.round(item.confidence * 100)}% confidence`}</dd></div>)}</dl> : <p>No contrast-target measurements were returned.</p>}</section></div>
        <h3>OCR transcript</h3><details className="recognized-text" open><summary>{report.ocr?.evidence?.length || 0} recognized regions</summary><ol className="ocr-report-list">{(report.ocr?.evidence || []).map((item, index) => <li key={index}><span>{displayValue(item.raw_text || item.normalized_text) || 'Unreadable text'}</span><small>Confidence: {item.confidence == null ? 'Not reported' : `${Math.round(item.confidence * 100)}%`}{item.box ? ` · image box: ${JSON.stringify(item.box)}` : ''}</small></li>)}</ol></details>
        <h3>Violations and items for review</h3>{reviewRules.length ? <ul className="report-violations">{reviewRules.map((rule, index) => <li key={`${displayValue(rule.rule_id)}-${index}`}><StatusBadge status={displayValue(rule.status)} /><div><strong>{displayValue(rule.rule_id)} · {displayValue(rule.field_name)}</strong><p>{displayValue(rule.reason || rule.description)}</p><small>{displayValue(rule.legal_source) || 'No legal source supplied'}</small></div></li>)}</ul> : <p>No failed or review-required rule results were returned.</p>}
      </section>
    </>}
  </div>
}

const accountRoles = {
  supreme: { title: 'Supreme Authority', scope: 'Review authority cases, assign work to inspectors, and monitor case progress.' },
  inspector: { title: 'Inspector', scope: 'Review assigned inspections and record inspection decisions.' },
  organisation: { title: 'Business / pre-verification', scope: 'Submit product pre-verification requests and review their status.' },
}

export function SettingsPage({ user, role = 'inspector' }) {
  const [saved, setSaved] = useState(false)
  const [language, setLanguage] = useState(() => localStorage.getItem('cv-summary-language') || 'en')
  const [concise, setConcise] = useState(() => localStorage.getItem('cv-concise-summaries') !== 'false')
  const accountId = user?.app_metadata?.login_id || sessionStorage.getItem('cv-actor-id') || user?.email || 'Not available'
  const email = user?.email || (String(accountId).includes('@') ? accountId : 'Not provided for this sign-in')
  const fullName = user?.user_metadata?.full_name || user?.user_metadata?.display_name || user?.user_metadata?.name || sessionStorage.getItem('cv-organization') || 'Signed-in user'
  const preview = sessionStorage.getItem('cv-demo-authenticated') === 'true'
  const roleInfo = accountRoles[role] || accountRoles.inspector
  const authStatus = preview ? 'Local preview account' : user ? 'Verified account' : 'Local account'
  const savePreferences = () => {
    localStorage.setItem('cv-summary-language', language)
    localStorage.setItem('cv-concise-summaries', String(concise))
    setSaved(true)
  }

  return <div className="page-content">
    <div className="page-heading"><div><p className="kicker">Account and workspace preferences</p><h1>Settings</h1><p>Review your portal access and manage preferences for this browser.</p></div></div>
    <div className="settings-layout">
      <section className="panel settings-account">
        <div className="settings-section-heading"><div><p className="kicker">Signed-in profile</p><h2>{fullName}</h2><p>Account details associated with this session.</p></div><span className="settings-role-badge">{roleInfo.title}</span></div>
        <dl className="settings-details">
          <div><dt>Portal / role</dt><dd>{roleInfo.title}</dd></div>
          <div><dt>Officer or account ID</dt><dd>{accountId}</dd></div>
          <div><dt>Email</dt><dd>{email}</dd></div>
          <div><dt>Sign-in type</dt><dd>{authStatus}</dd></div>
          <div><dt>Account status</dt><dd><span className="profile-active"><i />Active in this session</span></dd></div>
          {user?.id && <div><dt>Authentication user ID</dt><dd>{user.id}</dd></div>}
        </dl>
        <div className="settings-access"><strong>What this role can do</strong><p>{roleInfo.scope}</p></div>
        <p className="settings-security-note">Passwords and authentication tokens are never displayed here. To change sign-in credentials, use the account recovery flow provided by your administrator.</p>
      </section>
      <section className="panel settings-preferences">
        <div className="settings-section-heading"><div><p className="kicker">Personalize</p><h2>Workspace preferences</h2><p>These choices are saved in this browser.</p></div></div>
        <label>Default summary language<select value={language} onChange={(event) => { setLanguage(event.target.value); setSaved(false) }}><option value="en">English</option><option value="hi">Hindi</option><option value="ta">Tamil</option></select></label>
        <label className="check-row"><input type="checkbox" checked={concise} onChange={(event) => { setConcise(event.target.checked); setSaved(false) }} /> Prefer concise accessibility summaries</label>
        <button className="primary-button" onClick={savePreferences}>Save preferences</button>
        {saved && <span className="save-message" role="status">Preferences saved in this browser.</span>}
      </section>
    </div>
  </div>
}
