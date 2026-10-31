import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { checkAnalysisHealth, downloadCsv, downloadJson, downloadMarkdown, hashImageFile, inspectPackage, preparePackage, printReport, saveInspection } from '../services/inspectionService'
import { calculateFieldConfidence, formatInspectionDate, summarizeFields } from '../services/workspaceModel'
import { StatusBadge } from '../components/StatusBadge'

const tools = [
  { id: 'select', icon: '⌖', label: 'Select' }, { id: 'highlight', icon: '▣', label: 'Highlight' },
  { id: 'draw', icon: '╱', label: 'Draw' }, { id: 'issue', icon: '⚑', label: 'Mark issue' },
]
const qualityNames = {
  IMAGE_BLURRY: 'Blur detected', IMAGE_TOO_DARK: 'Image is too dark', IMAGE_TOO_BRIGHT: 'Image is too bright',
  HIGH_GLARE: 'Strong glare detected', MODERATE_GLARE: 'Some glare detected', LOW_RESOLUTION: 'Low resolution',
  SKEWED: 'Label appears tilted', SKEW_ANGLE: 'Label appears tilted', LOW_CONTRAST: 'Low text contrast', LOW_TEXT_CONTRAST: 'Low text contrast',
}
const safeText = (value) => typeof value === 'string' ? value : value == null ? '' : String(value)
const MAX_IMAGE_BYTES = 10 * 1024 * 1024
const pipelineStages = ['Image quality', 'OCR', 'Declaration extraction', 'Rule evaluation', 'Report generation']

export function WorkspacePage({ inspection, setInspection }) {
  const [selected, setSelected] = useState(null)
  const [side, setSide] = useState('front')
  const [tool, setTool] = useState('select')
  const [zoom, setZoom] = useState(1)
  const [dimensions, setDimensions] = useState({ width: 0, height: 0 })
  const [pan, setPan] = useState({ x: 0, y: 0 })
  const [dragging, setDragging] = useState(false)
  const [showAllBoxes, setShowAllBoxes] = useState(false)
  const [boxOpacity, setBoxOpacity] = useState(12)
  const [loading, setLoading] = useState(false)
  const [pipelineStageIndex, setPipelineStageIndex] = useState(0)
  const [healthStatus, setHealthStatus] = useState('checking')
  const [saveError, setSaveError] = useState(inspection.historySaveError || '')
  const [error, setError] = useState('')
  const [selectedFile, setSelectedFile] = useState(null)
  const [filter, setFilter] = useState('all')
  const [search, setSearch] = useState('')
  const [mandatoryOnly, setMandatoryOnly] = useState(false)
  const [inspectorOpen, setInspectorOpen] = useState(false)
  const [editing, setEditing] = useState(false)
  const [editValue, setEditValue] = useState('')
  const [history, setHistory] = useState([])
  const [historyIndex, setHistoryIndex] = useState(-1)
  const [spaceDown, setSpaceDown] = useState(false)
  const input = useRef(null)
  const viewport = useRef(null)
  const stage = useRef(null)
  const pointer = useRef(null)
  const cancelRef = useRef(null)
  const cardRefs = useRef({})
  const fields = inspection.fields || []
  const field = fields.find((item) => item.id === selected) || null
  const counts = useMemo(() => summarizeFields(fields), [fields])
  const confidence = useMemo(() => calculateFieldConfidence(fields), [fields])
  const productName = fields.find((item) => item.id === 'product_name' && item.present)?.value || inspection.product
  const brand = fields.find((item) => item.id === 'brand_name' && item.present)?.value || inspection.brand
  const headingProduct = brand && !String(productName).toLowerCase().startsWith(brand.toLowerCase()) ? `${brand} ${productName}` : productName
  const visibleFields = fields.filter((item) => {
    if (mandatoryOnly && !item.mandatory) return false
    if (search && !`${item.label} ${item.value}`.toLowerCase().includes(search.toLowerCase())) return false
    return filter === 'all' || (filter === 'pass' && item.status === 'PASS') || (filter === 'review' && item.status === 'REVIEW') || (filter === 'fail' && item.status === 'FAIL')
  })
  const detected = visibleFields.filter((item) => item.present)
  const missing = visibleFields.filter((item) => !item.present)
  const mandatoryDetected = detected.filter((item) => item.mandatory)
  const optionalDetected = detected.filter((item) => !item.mandatory)
  const imageMetadata = inspection.report?.image || {}
  const quality = inspection.quality || inspection.report?.quality || {}
  const qualityProblems = [...(quality.issues || []), ...(quality.warnings || [])]
  const ocrCount = inspection.report?.ocr?.evidence?.length || 0
  const fitScale = useCallback(() => {
    if (!viewport.current || !dimensions.width || !dimensions.height) return
    const { width, height } = viewport.current.getBoundingClientRect()
    const nextZoom = Math.min((width - 80) / dimensions.width, (height - 140) / dimensions.height, 1)
    setZoom(Math.max(.05, nextZoom))
    setPan({ x: 0, y: 0 })
  }, [dimensions])

  useEffect(() => {
    if (inspection.fields?.length) setSelected((current) => inspection.fields.some((item) => item.id === current) ? current : inspection.fields.find((item) => item.present)?.id || inspection.fields[0]?.id || null)
  }, [inspection.id, inspection.fields])
  useEffect(() => { fitScale() }, [fitScale])
  useEffect(() => {
    let active = true
    const check = async () => {
      try { await checkAnalysisHealth(); if (active) setHealthStatus('online') }
      catch { if (active) setHealthStatus('offline') }
    }
    check()
    const timer = window.setInterval(check, 30000)
    return () => { active = false; window.clearInterval(timer) }
  }, [])
  useEffect(() => {
    if (!loading) return undefined
    const timer = window.setInterval(() => setPipelineStageIndex((current) => Math.min(current + 1, pipelineStages.length - 1)), 1800)
    return () => window.clearInterval(timer)
  }, [loading])
  useEffect(() => { setSaveError(inspection.historySaveError || '') }, [inspection.id, inspection.historySaveError])
  useEffect(() => {
    const resize = () => fitScale()
    window.addEventListener('resize', resize)
    return () => window.removeEventListener('resize', resize)
  }, [fitScale])
  useEffect(() => {
    const onKeyDown = (event) => {
      const target = event.target
      if (['INPUT', 'TEXTAREA', 'SELECT'].includes(target?.tagName) || target?.isContentEditable) return
      if (event.code === 'Space') {
        if (!target?.closest?.('.canvas-viewport')) return
        event.preventDefault(); setSpaceDown(true); return
      }
      if (event.key === 'Escape') { setSelected(null); setEditing(false); return }
      if (event.key === '+' || event.key === '=') setZoom((value) => Math.min(4, value * 1.15))
      if (event.key === '-') setZoom((value) => Math.max(.05, value / 1.15))
      if (event.key === '0') fitScale()
      if (event.key.toLowerCase() === 'f') setSide((value) => value === 'front' ? 'back' : 'front')
      if (event.key.toLowerCase() === 'b') setShowAllBoxes((value) => !value)
      if (event.key === '?' ) setHelpOpen((value) => !value)
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault()
        const index = visibleFields.findIndex((item) => item.id === selected)
        const next = (index + (event.key === 'ArrowDown' ? 1 : -1) + visibleFields.length) % visibleFields.length
        if (visibleFields[next]) { setSelected(visibleFields[next].id); cardRefs.current[visibleFields[next].id]?.focus() }
      }
      if (event.key === 'Enter' && selected) cardRefs.current[selected]?.click()
    }
    const onKeyUp = (event) => { if (event.code === 'Space') setSpaceDown(false) }
    window.addEventListener('keydown', onKeyDown); window.addEventListener('keyup', onKeyUp)
    return () => { window.removeEventListener('keydown', onKeyDown); window.removeEventListener('keyup', onKeyUp) }
  }, [fitScale, selected, visibleFields])

  const [helpOpen, setHelpOpen] = useState(false)
  const upload = async (file) => {
    if (!file) return
    if (!/^image\/(jpeg|png)$/.test(file.type)) { setSelectedFile(null); setError('Choose a JPEG or PNG package image.'); return }
    if (file.size > MAX_IMAGE_BYTES) { setSelectedFile(null); setError('The image must be 10 MiB or smaller.'); return }
    if (inspection.image?.startsWith('blob:')) URL.revokeObjectURL(inspection.image)
    setError(''); setSelectedFile(file); setSelected(null); setDimensions({ width: 0, height: 0 }); setPan({ x: 0, y: 0 })
    const prepared = preparePackage(file)
    prepared.imageHash = await hashImageFile(file)
    setInspection(prepared)
  }
  const runEvaluation = async () => {
    if (!selectedFile) return
    const controller = new AbortController(); cancelRef.current = controller
    setError(''); setSaveError(''); setPipelineStageIndex(0); setLoading(true)
    try {
      const result = await inspectPackage(selectedFile, inspection.image, { signal: controller.signal })
      setPipelineStageIndex(pipelineStages.length); setInspection(result); setSaveError(result.historySaveError || ''); setSelected(result.fields.find((item) => item.present)?.id || null)
    } catch (evaluationError) {
      if (evaluationError.name !== 'AbortError') setError(evaluationError.message === 'Failed to fetch' ? 'Could not reach the analysis service. Check that it is available, then retry evaluation.' : evaluationError.message || 'Evaluation failed. Try again with a clearer package image.')
    } finally { setLoading(false); cancelRef.current = null }
  }
  const persistCurrentInspection = (next) => {
    setInspection(next)
    const result = saveInspection(next)
    setSaveError(result.ok ? '' : result.error)
  }
  const selectField = (id) => {
    setSelected(id); setInspectorOpen(true)
    requestAnimationFrame(() => cardRefs.current[id]?.scrollIntoView({ block: 'nearest', behavior: 'smooth' }))
  }
  const saveCorrection = () => {
    if (!field) return
    const nextFields = fields.map((item) => item.id === field.id ? { ...item, value: editValue, normalizedValue: editValue, present: Boolean(editValue.trim()), status: editValue.trim() ? 'REVIEW' : item.mandatory ? 'FAIL' : 'REVIEW', reason: 'User correction saved; confirm against the package image.' } : item)
    const entry = { fieldId: field.id, previousValue: field.value, value: editValue, at: new Date().toISOString(), user: 'You' }
    const next = { ...inspection, fields: nextFields, corrections: [...(inspection.corrections || []), entry] }
    const nextHistory = historyIndex < 0 ? [inspection, next] : [...history.slice(0, historyIndex + 1), next]
    setHistory(nextHistory); setHistoryIndex(nextHistory.length - 1); persistCurrentInspection(next); setEditing(false)
  }
  const updateStatus = (id, status, reason) => persistCurrentInspection({ ...inspection, fields: fields.map((item) => item.id === id ? { ...item, status, reason } : item) })
  const pointerStart = (event) => { if (spaceDown || event.button === 1) { event.preventDefault(); pointer.current = { startX: event.clientX, startY: event.clientY, panX: pan.x, panY: pan.y }; setDragging(true); event.currentTarget.setPointerCapture(event.pointerId) } }
  const pointerMove = (event) => { if (dragging && pointer.current) setPan({ x: pointer.current.panX + event.clientX - pointer.current.startX, y: pointer.current.panY + event.clientY - pointer.current.startY }) }
  const undo = () => { if (historyIndex <= 0) return; setInspection(history[historyIndex - 1]); setHistoryIndex((index) => index - 1) }
  const redo = () => { if (historyIndex + 1 >= history.length) return; setInspection(history[historyIndex + 1]); setHistoryIndex((index) => index + 1) }

  return <main className="workspace-page redesigned-workspace" onDragOver={(event) => event.preventDefault()} onDrop={(event) => { event.preventDefault(); upload(event.dataTransfer.files?.[0]) }}>
    <header className="inspection-topbar">
      <div className="inspection-heading">
        <div className="inspection-breadcrumb">Inspections <span>/</span> {inspection.id}</div>
        <div className="inspection-title-line"><h1>{inspection.evaluated ? headingProduct : inspection.product}</h1><StatusBadge status={inspection.status} /></div>
        <div className="inspection-meta"><span>{formatInspectionDate(inspection.createdAt)}</span><span className="live-pill">{inspection.evaluated ? 'Live OCR evaluation' : 'Ready to evaluate'}</span><span title={Intl.DateTimeFormat().resolvedOptions().timeZone}>Time shown in {Intl.DateTimeFormat().resolvedOptions().timeZone}</span></div>
      </div>
      <div className="workspace-actions">
        <button className="secondary-button" onClick={() => input.current?.click()} disabled={loading}>Upload image</button>
        <input ref={input} onChange={(event) => { upload(event.target.files?.[0]); event.target.value = '' }} type="file" accept="image/jpeg,image/png" hidden />
        <button className="primary-button" onClick={runEvaluation} disabled={!selectedFile || loading}>{loading ? 'Evaluating…' : 'Run evaluation'}</button>
        {loading && <button className="secondary-button" onClick={() => cancelRef.current?.abort()}>Cancel</button>}
        <label className="export-menu"><span className="visually-hidden">Export format</span><select aria-label="Export format" disabled={!inspection.evaluated} defaultValue="json" onChange={(event) => { const choice = event.target.value; if (choice === 'json') downloadJson(inspection); if (choice === 'csv') downloadCsv(inspection); if (choice === 'markdown') downloadMarkdown(inspection); if (choice === 'pdf') printReport(inspection); event.target.value = 'json' }}><option value="json">Export JSON</option><option value="csv">Export CSV</option><option value="markdown">Export Markdown</option><option value="pdf">Print PDF report</option></select></label>
        <button className="icon-help" aria-label="Keyboard shortcuts" aria-expanded={helpOpen} onClick={() => setHelpOpen((value) => !value)}>?</button>
      </div>
    </header>
    {helpOpen && <div className="shortcuts-popover" role="dialog" aria-label="Keyboard shortcuts"><b>Keyboard shortcuts</b><span>↑ / ↓ Select declaration</span><span>Enter Open selection</span><span>Esc Clear selection</span><span>+ / − Zoom · 0 Fit</span><span>F Front / back · B Boxes</span><span>Space + drag Pan</span></div>}
    {error && <div className="notice error" role="alert"><span>{error}</span><button onClick={runEvaluation} disabled={!selectedFile || loading}>Retry</button></div>}
    {healthStatus === 'offline' && <div className="notice error" role="status"><span>Analysis service is unavailable. Start or reconnect the service, then retry.</span><button className="secondary-button" onClick={() => checkAnalysisHealth().then(() => setHealthStatus('online')).catch(() => setHealthStatus('offline'))}>Check again</button></div>}
    {loading && <div className="evaluation-progress pipeline-progress" role="status" aria-live="polite"><span className="progress-spinner" /> <div><strong>Evaluation in progress</strong><small className="pipeline-note">Stage indicator is estimated; the analysis API returns its report when processing completes.</small><ol>{pipelineStages.map((stageName, index) => <li key={stageName} className={index < pipelineStageIndex ? 'complete' : index === pipelineStageIndex ? 'active' : ''}>{stageName}</li>)}</ol></div></div>}
    {saveError && <div className="notice error" role="alert"><span>{saveError}</span><button className="secondary-button" onClick={() => { const result = saveInspection(inspection); setSaveError(result.ok ? '' : result.error) }}>Retry save</button></div>}
    {!inspection.evaluated && !inspection.image && !selectedFile && <div className="upload-empty" onClick={() => input.current?.click()}><strong>Drop a package image here or choose a file</strong><span>JPEG or PNG · Up to 10 MiB</span><button className="secondary-button">Choose image</button></div>}
    <div className="inspection-layout redesigned-layout">
      <nav className="tool-rail" aria-label="Annotation tools"><div role="toolbar" aria-label="Image annotation tools">{tools.map((item) => <button key={item.id} className={tool === item.id ? 'active' : ''} aria-label={item.label} aria-pressed={tool === item.id} title={item.label} onClick={() => setTool(item.id)}><span>{item.icon}</span></button>)}</div><div className="history-controls"><button className="undo-control" title="Undo correction" aria-label="Undo correction" onClick={undo} disabled={historyIndex <= 0}>↶</button><button className="undo-control" title="Redo correction" aria-label="Redo correction" onClick={redo} disabled={historyIndex + 1 >= history.length}>↷</button></div></nav>
      <section className="canvas-panel redesigned-canvas" aria-label="Package image viewer">
        <div className="canvas-header redesigned-canvas-header"><div><strong>Package image</strong><span className={`quality-chip ${qualityProblems.length ? 'warning' : ''}`}>{inspection.evaluated ? qualityProblems.length ? `Review · ${qualityProblems.length} quality checks` : 'Quality checks clear' : inspection.image ? 'Checks pending' : 'Awaiting image'}</span></div>
          <details className="quality-popover"><summary aria-label="Image quality details">ⓘ Quality</summary><div><strong>Image quality checks</strong>{inspection.evaluated ? <><p>Resolution: {imageMetadata.width || quality.width || '—'} × {imageMetadata.height || quality.height || '—'}</p><p>Blur variance: {quality.blur_score ?? 'Not reported'}</p><p>Brightness: {quality.brightness ?? 'Not reported'}</p><p>Glare ratio: {quality.glare_ratio == null ? 'Not reported' : `${Math.round(quality.glare_ratio * 100)}%`}</p><p>Skew angle: {quality.skew_angle_degrees == null ? 'Not reported' : `${quality.skew_angle_degrees}°`}</p><p>Text contrast: {quality.text_contrast ?? 'Not reported'}</p>{qualityProblems.length ? qualityProblems.map((code) => <p className="quality-warning" key={code}>⚠ {qualityNames[code] || String(code).replaceAll('_', ' ').toLowerCase()}</p>) : <p>Image quality checks did not report issues.</p>}{inspection.report?.evidence?.calibration?.detected ? <p>Scale reference marker detected (ArUco ID {inspection.report.evidence.calibration.marker_id ?? '—'}).</p> : <p>Scale reference marker not detected; size and perspective measurements may be unavailable.</p>}</> : <p>Run an evaluation to inspect blur, brightness, glare and resolution.</p>}</div></details>
        </div>
        <div ref={viewport} className={`canvas-viewport ${dragging ? 'is-dragging' : ''}`} onPointerDown={pointerStart} onPointerMove={pointerMove} onPointerUp={() => { setDragging(false); pointer.current = null }} onPointerCancel={() => setDragging(false)}>
          {inspection.image ? <div ref={stage} className="image-stage" style={{ width: dimensions.width ? `${dimensions.width}px` : 'auto', height: dimensions.height ? `${dimensions.height}px` : 'auto', transform: `translate(-50%, -50%) translate(${pan.x}px, ${pan.y}px) scale(${zoom})` }}>
            <img src={inspection.image} alt={`${headingProduct || 'Uploaded product'} package label`} onLoad={(event) => setDimensions({ width: event.currentTarget.naturalWidth, height: event.currentTarget.naturalHeight })} />
            {inspection.evaluated && fields.filter((item) => item.present && item.region?.width > 0 && item.region?.height > 0 && (showAllBoxes || selected === item.id)).map((item, index) => <button key={item.id} aria-label={`Select evidence ${index + 1}: ${item.label}, ${item.status}`} aria-pressed={selected === item.id} className={`evidence-box ${item.status.toLowerCase()} ${selected === item.id ? 'selected' : ''}`} onPointerDown={(event) => event.stopPropagation()} onClick={() => selectField(item.id)} style={{ left: `${item.region.left}%`, top: `${item.region.top}%`, width: `${item.region.width}%`, height: `${item.region.height}%`, '--box-opacity': boxOpacity / 100 }}><span>{index + 1}</span></button>)}
          </div> : <div className="canvas-placeholder"><span>▧</span><strong>Package preview</strong><small>Upload a front or back label image to begin.</small></div>}
        </div>
        <div className="box-toolbar"><button onClick={() => setShowAllBoxes((value) => !value)} aria-pressed={showAllBoxes}>{showAllBoxes ? 'Hide boxes' : 'Show all boxes'}</button><label>Box fill <input type="range" min="4" max="30" value={boxOpacity} onChange={(event) => setBoxOpacity(Number(event.target.value))} /></label></div>
        <div className="canvas-toolbar" role="toolbar" aria-label="Image view controls"><div className="segmented"><button aria-pressed={side === 'front'} className={side === 'front' ? 'active' : ''} onClick={() => setSide('front')}>Front</button><button aria-pressed={side === 'back'} className={side === 'back' ? 'active' : ''} onClick={() => setSide('back')}>Back</button></div><span className="toolbar-divider" /><button aria-label="Zoom out" onClick={() => setZoom((value) => Math.max(.05, value / 1.15))}>−</button><output aria-live="polite">{Math.round(zoom * 100)}%</output><button aria-label="Zoom in" onClick={() => setZoom((value) => Math.min(4, value * 1.15))}>+</button><button onClick={fitScale}>Fit</button><button onClick={() => { setZoom(1); setPan({ x: 0, y: 0 }) }}>Reset</button></div>
        <p className="canvas-caption">{inspection.evaluated ? `OCR completed · ${ocrCount} regions · ${qualityProblems.length} image quality warnings` : 'Drag to pan with Space or the middle mouse button.'}</p>
      </section>
      <aside className={`results-panel inspector-panel ${inspectorOpen ? 'inspector-open' : ''}`} aria-label="Validation inspector">
        <div className="inspector-sticky">
          <div className="inspector-heading"><div><p className="eyebrow">Package review</p><h2>Declarations</h2></div><button className="inspector-close" onClick={() => setInspectorOpen(false)} aria-label="Close declarations panel">×</button></div>
          <div className="confidence-card"><div className="confidence-ring" style={{ '--confidence': `${confidence.value}%` }}><span>{confidence.value}<small>%</small></span></div><div><strong>Field confidence</strong><p title={confidence.explanation}>{confidence.capped ? 'Capped · mandatory field needs review' : 'Mean confidence across mandatory fields'}</p></div><button className="info-button" title={confidence.explanation} aria-label={confidence.explanation}>ⓘ</button></div>
          <div className="validation-stats" aria-label={`Validation totals: ${counts.total} declarations`}>
            <button className={filter === 'pass' ? 'selected' : ''} onClick={() => setFilter(filter === 'pass' ? 'all' : 'pass')} aria-pressed={filter === 'pass'}><strong>{counts.pass}</strong><span>✓ Passed</span></button>
            <button className={filter === 'review' ? 'selected' : ''} onClick={() => setFilter(filter === 'review' ? 'all' : 'review')} aria-pressed={filter === 'review'}><strong>{counts.review}</strong><span>◷ Review</span></button>
            <button className={filter === 'fail' ? 'selected' : ''} onClick={() => setFilter(filter === 'fail' ? 'all' : 'fail')} aria-pressed={filter === 'fail'}><strong>{counts.fail}</strong><span>× Failed</span></button>
          </div>
          <div className="inspector-filters"><label className="field-search"><span>⌕</span><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search declarations" /></label><label className="mandatory-toggle"><input type="checkbox" checked={mandatoryOnly} onChange={(event) => setMandatoryOnly(event.target.checked)} /> Mandatory only</label></div>
        </div>
        {!inspection.evaluated ? <div className="inspector-empty">Upload a label and run evaluation to see extracted declarations here.</div> : <div className="declaration-scroll">
          {mandatoryDetected.length > 0 && <section><h3 className="group-title">Mandatory declarations <span>{mandatoryDetected.length}</span></h3><div role="listbox" aria-label="Mandatory declarations" aria-activedescendant={field ? `declaration-${field.id}` : undefined} className="declaration-list">{mandatoryDetected.map((item, index) => <article key={item.id} ref={(node) => { cardRefs.current[item.id] = node }} role="option" id={`declaration-${item.id}`} aria-selected={selected === item.id} tabIndex={selected === item.id ? 0 : -1} className={`declaration-card ${selected === item.id ? 'selected' : ''}`} onClick={() => selectField(item.id)} onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); selectField(item.id) } }}>
            <div className="declaration-top"><span className="declaration-number">{index + 1}</span><strong>{item.label}</strong><StatusBadge status={item.status} /></div><div className="declaration-value">{item.value}</div><div className="declaration-meta"><span className="confidence-bar"><i style={{ width: `${item.confidence}%` }} /></span><span>{item.confidence}%</span></div><p className="declaration-reason">{item.reason}</p><div className="card-actions"><button onClick={(event) => { event.stopPropagation(); updateStatus(item.id, 'PASS', 'Accepted by user.') }}>Accept</button><button onClick={(event) => { event.stopPropagation(); selectField(item.id); setEditValue(item.value); setEditing(true) }}>Edit</button><button onClick={(event) => { event.stopPropagation(); updateStatus(item.id, 'REVIEW', 'Flagged for manual review.') }}>Flag</button></div>
          </article>)}</div></section>}
          {optionalDetected.length > 0 && <section className="optional-group"><h3 className="group-title">Optional declarations <span>{optionalDetected.length}</span></h3><div role="listbox" aria-label="Optional declarations" className="declaration-list">{optionalDetected.map((item, index) => <article key={item.id} ref={(node) => { cardRefs.current[item.id] = node }} role="option" id={`declaration-${item.id}`} aria-selected={selected === item.id} tabIndex={selected === item.id ? 0 : -1} className={`declaration-card ${selected === item.id ? 'selected' : ''}`} onClick={() => selectField(item.id)} onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); selectField(item.id) } }}><div className="declaration-top"><span className="declaration-number">{mandatoryDetected.length + index + 1}</span><strong>{item.label}</strong><StatusBadge status={item.status} /></div><div className="declaration-value">{item.value}</div><div className="declaration-meta"><span className="confidence-bar"><i style={{ width: `${item.confidence}%` }} /></span><span>{item.confidence}%</span></div><p className="declaration-reason">{item.reason}</p><div className="card-actions"><button onClick={(event) => { event.stopPropagation(); updateStatus(item.id, 'PASS', 'Accepted by user.') }}>Accept</button><button onClick={(event) => { event.stopPropagation(); selectField(item.id); setEditValue(item.value); setEditing(true) }}>Edit</button><button onClick={(event) => { event.stopPropagation(); updateStatus(item.id, 'REVIEW', 'Flagged for manual review.') }}>Flag</button></div></article>)}</div></section>}
          {missing.length > 0 && <section className="missing-group"><h3 className="group-title">Not detected <span>{missing.length}</span></h3><div className="missing-chips">{missing.map((item) => <button key={item.id} onClick={() => selectField(item.id)}>{item.label}<StatusBadge status={item.status} /></button>)}</div></section>}
          {inspection.ocrText && <details className="recognized-text"><summary>Recognized text ({ocrCount} regions)</summary><pre>{inspection.ocrText}</pre></details>}
          {!detected.length && !missing.length && <p className="inspector-empty">No declarations match these filters.</p>}
        </div>}
        {field && <section className="evidence-drawer" aria-label={`Evidence for ${field.label}`}><div className="drawer-title"><div><p className="eyebrow">Selected declaration</p><h3>{field.label}</h3></div><StatusBadge status={field.status} /></div>
          <dl><div><dt>Raw OCR text</dt><dd><code>{field.rawText || 'No OCR source text was returned.'}</code></dd></div><div><dt>Normalized value</dt><dd>{editing ? <input aria-label="Corrected declaration value" value={editValue} onChange={(event) => setEditValue(event.target.value)} /> : field.value}</dd></div><div><dt>OCR confidence</dt><dd>{field.confidence}%</dd></div><div><dt>Rule</dt><dd>{field.ruleId || field.ruleReference || 'No direct rule linked'}{field.ruleDescription && <small>{field.ruleDescription}</small>}</dd></div><div><dt>Validation</dt><dd>{field.reason}</dd></div></dl>
          {inspection.image && field.region?.width > 0 && <div className="evidence-crop"><img src={inspection.image} alt={`Image evidence crop for ${field.label}`} style={{ width: `${Math.max(100, 10000 / field.region.width)}%`, left: `${-field.region.left / field.region.width * 100}%`, top: `${-field.region.top / field.region.height * 100}%` }} /></div>}
          {editing ? <div className="drawer-actions"><button className="primary-button" onClick={saveCorrection}>Save correction</button><button className="secondary-button" onClick={() => setEditing(false)}>Cancel</button></div> : <div className="drawer-actions"><button className="secondary-button" onClick={() => { setEditValue(field.value); setEditing(true) }}>Edit value</button><button className="secondary-button" onClick={() => updateStatus(field.id, 'REVIEW', 'Flagged for manual review.')}>Mark as issue</button></div>}
          {inspection.corrections?.filter((entry) => entry.fieldId === field.id).length > 0 && <details className="audit-trail"><summary>Correction history</summary>{inspection.corrections.filter((entry) => entry.fieldId === field.id).map((entry, index) => <p key={`${entry.at}-${index}`}>{entry.user} · {formatInspectionDate(entry.at)}: {entry.previousValue} → {entry.value}</p>)}</details>}
        </section>}
      </aside>
      <button className="inspector-toggle" onClick={() => setInspectorOpen((value) => !value)} aria-expanded={inspectorOpen}>Validation results · {counts.review} to review</button>
    </div>
  </main>
}
