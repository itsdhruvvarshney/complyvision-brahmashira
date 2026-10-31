import { buildDeclarationFields, calculateFieldConfidence, csvForInspection } from './workspaceModel'

const KEY = 'complyvision-inspections'
const displayValue = (value) => {
  if (value == null || value === '') return ''
  if (Array.isArray(value)) return value.map(displayValue).filter(Boolean).join(', ')
  if (typeof value === 'object') return Object.entries(value).filter(([, item]) => item != null && item !== '').map(([key, item]) => `${key.replaceAll('_', ' ')}: ${displayValue(item)}`).join(' · ')
  return String(value)
}
export const emptyInspection = {
  id: 'No evaluation yet',
  product: 'No package selected',
  brand: '',
  createdAt: '',
  status: 'READY',
  confidence: 0,
  summary: 'Upload a package image and run evaluation to see real extracted data.',
  image: null,
  fields: [],
  evaluated: false,
  report: null,
  quality: null,
  ocrText: '',
}
const FIELD_LABELS = {
  product: 'Commodity',
  product_name: 'Product name',
  brand_name: 'Brand',
  net_quantity: 'Net quantity',
  manufacture_date: 'Manufacture / packing date',
  mrp: 'Maximum retail price',
  inclusive_of_all_taxes: 'Price includes all taxes',
  manufacturer: 'Manufacturer',
  marketer: 'Marketer',
  packer: 'Packer',
  importer: 'Importer',
  consumer_care: 'Consumer care details',
  consumer_care_phone: 'Consumer care phone',
  consumer_care_email: 'Consumer care email',
  country_of_origin: 'Country of origin',
}
const FIELD_RULE_ALIASES = {
  product: ['commodity_name'],
  product_name: ['commodity_name'],
  brand_name: ['brand_name'],
  net_quantity: ['net_quantity'],
  manufacture_date: ['month_year', 'manufacture_date', 'packing_date'],
  mrp: ['mrp'],
  inclusive_of_all_taxes: ['mrp'],
  manufacturer: ['manufacturer'],
  marketer: ['marketer'],
  packer: ['packer'],
  importer: ['importer'],
  consumer_care: ['consumer_care', 'phone', 'email'],
  consumer_care_phone: ['consumer_care', 'phone'],
  consumer_care_email: ['consumer_care', 'email'],
  country_of_origin: ['country_of_origin'],
}

const formatValue = (value) => {
  if (value === null || value === undefined || value === '') return 'Not detected'
  if (Array.isArray(value)) return value.map(formatValue).join(', ')
  if (typeof value === 'object') {
    const entries = Object.entries(value).filter(([key, item]) => (
      item !== null && item !== undefined && item !== '' && !key.startsWith('extraction_')
    ))
    if (entries.some(([key]) => key === 'value')) {
      const main = entries.find(([key]) => key === 'value')?.[1]
      const unit = entries.find(([key]) => key === 'unit')?.[1]
      const currency = entries.find(([key]) => key === 'currency')?.[1]
      return `${currency === 'INR' ? '₹' : ''}${formatValue(main)}${unit ? ` ${unit}` : ''}`
    }
    return entries.map(([key, item]) => `${key.replaceAll('_', ' ')}: ${formatValue(item)}`).join(' · ') || 'Not detected'
  }
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  return String(value)
}

const asPercent = (value) => {
  const number = Number(value)
  if (!Number.isFinite(number)) return 0
  return Math.round((number <= 1 ? number * 100 : number))
}

const fieldPolygon = (entry, width, height) => {
  const polygon = entry?.source_polygon
  if (!polygon || !width || !height) return { left: 0, top: 0, width: 0, height: 0 }
  const points = Array.isArray(polygon[0])
    ? polygon.flatMap((item) => Array.isArray(item[0]) ? item : [item])
    : [polygon]
  const xs = points.flatMap((point) => point.length >= 4 && !Array.isArray(point[0])
    ? [Number(point[0]), Number(point[2])]
    : [Number(point[0])])
  const ys = points.flatMap((point) => point.length >= 4 && !Array.isArray(point[0])
    ? [Number(point[1]), Number(point[3])]
    : [Number(point[1])])
  const validX = xs.filter(Number.isFinite)
  const validY = ys.filter(Number.isFinite)
  if (!validX.length || !validY.length) return { left: 0, top: 0, width: 0, height: 0 }
  const minX = Math.min(...validX); const maxX = Math.max(...validX)
  const minY = Math.min(...validY); const maxY = Math.max(...validY)
  return {
    left: Math.max(0, minX / width * 100),
    top: Math.max(0, minY / height * 100),
    width: Math.max(0, (maxX - minX) / width * 100),
    height: Math.max(0, (maxY - minY) / height * 100),
  }
}

const rulesForField = (name, report) => {
  const aliases = FIELD_RULE_ALIASES[name] || [name]
  return (report.rule_results || []).filter((rule) => {
    const target = String(rule.field_name || '').toLowerCase()
    const evidenceFields = (rule.evidence || []).map((item) => String(item.field || '').toLowerCase())
    return aliases.some((alias) => target.includes(alias) || evidenceFields.includes(alias))
  })
}

const statusForRules = (rules) => {
  const statuses = rules.map((rule) => rule.status)
  if (statuses.includes('FAIL')) return 'FAIL'
  if (statuses.includes('REVIEW')) return 'REVIEW'
  if (statuses.includes('PASS')) return 'PASS'
  if (statuses.includes('NOT_APPLICABLE')) return 'NOT_APPLICABLE'
  return 'REVIEW'
}

export const getInspections = () => {
  try {
    const items = JSON.parse(localStorage.getItem(KEY) || '[]')
    return Array.isArray(items) ? items.filter((item) => item && item.id && item.report).sort((a, b) => new Date(b.timestamp || b.createdAt || 0) - new Date(a.timestamp || a.createdAt || 0)) : []
  } catch {
    return []
  }
}

const hydrateInspection = (latest) => {
  if (!latest) return null
  const timestamp = parseLegacyInspectionDate(latest.timestamp || latest.createdAt)
  const fields = Array.isArray(latest.fields) && latest.fields.length
    ? latest.fields
    : latest.report?.extracted_fields ? buildDeclarationFields(latest.report.extracted_fields, latest.report) : []
  const readableFields = fields.map((field) => ({
    ...field,
    label: displayValue(field.label),
    value: displayValue(field.value) || 'Not detected',
    rawText: displayValue(field.rawText),
    reason: displayValue(field.reason),
    ruleId: displayValue(field.ruleId),
    ruleReference: displayValue(field.ruleReference),
    ruleDescription: displayValue(field.ruleDescription),
  }))
  const confidence = calculateFieldConfidence(readableFields)
  return {
    ...latest,
    fields: readableFields,
    image: null,
    createdAt: timestamp,
    timestamp,
    product: displayValue(readableFields.find((field) => field.id === 'product_name' && field.present)?.value || latest.product) || 'Package inspection',
    brand: displayValue(readableFields.find((field) => field.id === 'brand_name' && field.present)?.value || latest.brand),
    confidence: confidence.value || latest.confidence || 0,
    confidenceCapped: confidence.capped,
    confidenceDefinition: confidence.explanation,
    evaluated: true,
  }
}

export const getInspectionById = (id) => hydrateInspection(getInspections().find((item) => item.id === id))

export const getLatestInspection = () => {
  try {
    return hydrateInspection(getInspections().find((item) => item.evaluated && item.report)) || emptyInspection
  } catch {
    return emptyInspection
  }
}

function parseLegacyInspectionDate(value) {
  if (!value) return ''
  if (/^\d{4}-\d\d-\d\dT/.test(value)) return value
  const enIn = String(value).match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})(?:,?\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(am|pm))?$/i)
  if (enIn) {
    const [, day, month, year, hour = '0', minute = '0', second = '0', meridiem = 'am'] = enIn
    const hour24 = (Number(hour) % 12) + (meridiem.toLowerCase() === 'pm' ? 12 : 0)
    return new Date(Number(year), Number(month) - 1, Number(day), hour24, Number(minute), Number(second)).toISOString()
  }
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? String(value) : parsed.toISOString()
}

export const saveInspection = (inspection) => {
  try {
    const report = inspection.report
      ? { ...inspection.report, evidence_images: [] }
      : undefined
    const persisted = { ...inspection }
    delete persisted.historySaveError
    const saved = { ...persisted, report, image: null }
    const next = [saved, ...getInspections().filter((item) => item.id !== inspection.id)]
    localStorage.setItem(KEY, JSON.stringify(next))
    window.dispatchEvent(new CustomEvent('complyvision:history-updated'))
    return { ok: true }
  } catch (error) {
    const message = error?.name === 'QuotaExceededError'
      ? 'Browser storage is full. Remove older inspection history or free browser storage, then retry saving.'
      : `Could not save inspection history: ${error?.message || 'browser storage is unavailable'}`
    return { ok: false, error: message }
  }
}

export async function checkAnalysisHealth({ signal } = {}) {
  const response = await fetch('/health', { signal, cache: 'no-store' })
  if (!response.ok) throw new Error(`Analysis service health check failed (${response.status})`)
  return response.json()
}

export async function fetchRuleCatalog({ signal } = {}) {
  const response = await fetch('/rules', { signal, cache: 'no-store' })
  const result = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(result.detail || `Rule catalog request failed (${response.status})`)
  return result.rules || []
}

export function preparePackage(file) {
  return {
    id: `CV-${Date.now().toString().slice(-8)}`,
    product: file.name.replace(/\.[^.]+$/, '').replace(/[-_]/g, ' ') || 'Uploaded package',
    brand: '',
    createdAt: new Date().toISOString(),
    status: 'READY',
    confidence: 0,
    summary: 'Image selected. Run evaluation to extract declarations and check applicable rules.',
    image: URL.createObjectURL(file),
    fields: [],
    evaluated: false,
    report: null,
    quality: null,
    ocrText: '',
  }
}

export async function hashImageFile(file) {
  if (!globalThis.crypto?.subtle) return ''
  try {
    const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
    return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
  } catch {
    return ''
  }
}

export async function inspectPackage(file, previewUrl, { signal } = {}) {
  const form = new FormData()
  form.append('file', file)
  const response = await fetch('/analyze', { method: 'POST', body: form, signal })
  const result = await response.json().catch(() => ({}))
  if (!response.ok) {
    throw new Error(result.detail || result.error || `Image evaluation failed (${response.status})`)
  }

  const extracted = result.extracted_fields || {}
  const fields = buildDeclarationFields(extracted, result)
  const confidence = calculateFieldConfidence(fields)
  const imageHash = await hashImageFile(file)
  const read = (name) => extracted[name]?.normalized_value
  const brand = formatValue(read('brand_name'))
  const product = fields.find((field) => field.id === 'product_name' && field.present)?.value || formatValue(read('product_name'))
  const commodity = formatValue(read('product'))
  const ocrEvidence = result.ocr?.evidence || []
  const ocrText = ocrEvidence.map((item) => item.raw_text || item.normalized_text || item.text).filter(Boolean).join('\n')
  const timestamp = new Date().toISOString()
  const inspection = {
    id: `CV-${Date.now().toString().slice(-8)}`,
    product: product !== 'Not detected' ? product : commodity !== 'Not detected' ? commodity : file.name,
    brand: brand === 'Not detected' ? '' : brand,
    createdAt: timestamp,
    timestamp,
    status: result.summary?.overall_status || 'REVIEW',
    confidence: confidence.value,
    confidenceCapped: confidence.capped,
    confidenceDefinition: confidence.explanation,
    imageHash,
    summary: result.summary?.reason || 'Evaluation completed. Review the extracted fields and rule outcomes.',
    image: previewUrl,
    fields,
    evaluated: true,
    report: result,
    quality: result.quality || null,
    ocrText,
  }
  const saved = saveInspection(inspection)
  if (!saved.ok) inspection.historySaveError = saved.error
  return inspection
}

export function downloadJson(inspection) {
  const payload = {
    ...inspection,
    timestamp: inspection.timestamp || new Date().toISOString(),
    summary: inspection.fields?.reduce((summary, field) => {
      const key = field.status === 'PASS' ? 'passed' : field.status === 'FAIL' ? 'failed' : 'review'
      summary[key] = (summary[key] || 0) + 1
      return summary
    }, { passed: 0, review: 0, failed: 0 }),
    fields: (inspection.fields || []).map(({ id, label, rawText, normalizedValue, value, confidence, status, ruleId, region }) => ({ id, label, raw: rawText, normalized: normalizedValue ?? value, confidence, status, ruleId, bbox: region })),
    corrections: inspection.corrections || [],
    analysis: inspection.report,
  }
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
  const link = Object.assign(document.createElement('a'), { href: URL.createObjectURL(blob), download: `${inspection.id}.json` })
  link.click(); URL.revokeObjectURL(link.href)
}

export function downloadCsv(inspection) {
  const link = Object.assign(document.createElement('a'), { href: URL.createObjectURL(new Blob([csvForInspection(inspection)], { type: 'text/csv' })), download: `${inspection.id}.csv` })
  link.click(); URL.revokeObjectURL(link.href)
}

export function markdownForInspection(inspection) {
  const report = inspection.report || {}
  const summary = report.summary || {}
  const quality = report.quality || {}
  const measurements = report.evidence || {}
  const rules = report.rule_results || []
  const fields = inspection.fields || []
  const lines = [
    `# Compliance inspection — ${inspection.product || inspection.id}`,
    '',
    `- **Inspection:** ${inspection.id}`,
    `- **Date:** ${inspection.timestamp || inspection.createdAt || 'Unknown'}`,
    `- **Overall status:** ${summary.overall_status || inspection.status || 'REVIEW'}`,
    `- **Image:** ${report.image?.filename || 'Not retained'}`,
    '',
    `> ${report.disclaimer || 'Prototype decision-support report. Review all extracted values and rules.'}`,
    '',
    '## Result summary',
    '',
    `| PASS | FAIL | REVIEW | N/A |`,
    `| ---: | ---: | ---: | ---: |`,
    `| ${summary.pass_count ?? '—'} | ${summary.fail_count ?? '—'} | ${summary.review_count ?? '—'} | ${summary.not_applicable_count ?? '—'} |`,
    '',
    '## Extracted declarations',
    '',
    '| Declaration | Value | Status | Confidence | Evidence |',
    '| --- | --- | --- | ---: | --- |',
    ...fields.map((field) => `| ${mdCell(field.label)} | ${mdCell(field.value)} | ${field.status} | ${field.confidence}% | ${mdCell(field.rawText || '—')} |`),
    '',
    '## Rule results',
    '',
    '| Rule | Status | Explanation | Legal source |',
    '| --- | --- | --- | --- |',
    ...rules.map((rule) => `| ${mdCell(rule.rule_id || rule.field_name)} | ${rule.status} | ${mdCell(rule.reason || rule.description || '—')} | ${mdCell(rule.legal_source || '—')} |`),
    '',
    '## Image quality',
    '',
    `- Usable: ${quality.usable == null ? 'Not reported' : quality.usable ? 'Yes' : 'No'}`,
    `- Resolution: ${report.image?.width || '—'} × ${report.image?.height || '—'}`,
    `- Blur score: ${quality.blur_score ?? 'Not reported'}`,
    `- Brightness: ${quality.brightness ?? 'Not reported'}`,
    `- Glare ratio: ${quality.glare_ratio ?? 'Not reported'}`,
    `- Skew angle: ${quality.skew_angle_degrees == null ? 'Not reported' : `${quality.skew_angle_degrees}°`}`,
    `- Text contrast: ${quality.text_contrast ?? 'Not reported'}`,
    `- Issues: ${(quality.issues || []).join(', ') || 'None reported'}`,
    '',
    '## Numeral height and contrast',
    '',
    `- Numeral height: ${measurements.numeral_height?.estimated_numeral_height_mm == null ? 'Not measured' : `${measurements.numeral_height.estimated_numeral_height_mm} mm`}`,
    `- Measurement status: ${measurements.numeral_height?.measurement_status || 'Not reported'}`,
    `- Measurement confidence: ${measurements.numeral_height?.measurement_confidence == null ? 'Not reported' : `${Math.round(measurements.numeral_height.measurement_confidence * 100)}%`}`,
    `- Contrast targets: ${Object.entries(measurements.contrast?.targets || {}).map(([name, value]) => `${name}: ${value.contrast_ratio ?? 'n/a'} ratio (${value.status || 'unrated'})`).join('; ') || 'Not measured'}`,
    '',
    '## OCR evidence',
    '',
    ...(report.ocr?.evidence || []).map((item) => `- ${mdCell(item.raw_text || item.normalized_text || 'Text')} — ${item.confidence == null ? 'confidence unavailable' : `${Math.round(item.confidence * 100)}% confidence`}`),
    '',
    '---',
    'Generated by ComplyVision. Prototype decision support only; not an official compliance certificate.',
  ]
  return lines.join('\n')
}

const mdCell = (value) => String(value ?? '').replaceAll('|', '\\|').replaceAll('\n', '<br>').trim()

export function downloadMarkdown(inspection) {
  const blob = new Blob([markdownForInspection(inspection)], { type: 'text/markdown;charset=utf-8' })
  const link = Object.assign(document.createElement('a'), { href: URL.createObjectURL(blob), download: `${inspection.id}.md` })
  link.click(); URL.revokeObjectURL(link.href)
}

export function printReport(inspection) {
  const report = inspection.report || {}
  const rows = (inspection.fields || []).map((field) => `<tr><td>${escapeHtml(field.label)}</td><td>${escapeHtml(field.value)}</td><td>${escapeHtml(field.status)}</td><td>${field.confidence}%</td><td>${escapeHtml(field.rawText || '—')}</td></tr>`).join('')
  const ruleRows = (report.rule_results || []).map((rule) => `<tr><td>${escapeHtml(rule.rule_id || rule.field_name)}</td><td>${escapeHtml(rule.status)}</td><td>${escapeHtml(rule.reason || rule.description)}</td><td>${escapeHtml(rule.legal_source || '—')}</td></tr>`).join('')
  const quality = report.quality || {}
  const measurement = report.evidence?.numeral_height || {}
  const contrast = Object.entries(report.evidence?.contrast?.targets || {}).map(([name, item]) => `<li>${escapeHtml(name)}: contrast ratio ${escapeHtml(item.contrast_ratio ?? 'not measured')}; ${escapeHtml(item.status || 'unrated')}</li>`).join('')
  const popup = window.open('', '_blank', 'width=900,height=700')
  if (!popup) return false
  popup.document.write(`<title>${escapeHtml(inspection.id)} report</title><h1>${escapeHtml(inspection.product)}</h1><p>${escapeHtml(inspection.id)} · ${escapeHtml(inspection.timestamp || inspection.createdAt)} · ${escapeHtml(report.summary?.overall_status || inspection.status)}</p><p>${escapeHtml(report.disclaimer || 'Prototype decision-support report; not an official compliance certificate.')}</p><h2>Extracted declarations</h2><table><thead><tr><th>Declaration</th><th>Value</th><th>Status</th><th>Confidence</th><th>OCR evidence</th></tr></thead><tbody>${rows}</tbody></table><h2>Rule results</h2><table><thead><tr><th>Rule</th><th>Status</th><th>Explanation</th><th>Legal source</th></tr></thead><tbody>${ruleRows}</tbody></table><h2>Image quality</h2><p>Usable: ${escapeHtml(quality.usable == null ? 'Not reported' : quality.usable ? 'Yes' : 'No')} · Resolution: ${escapeHtml(report.image?.width || '—')} × ${escapeHtml(report.image?.height || '—')} · Blur: ${escapeHtml(quality.blur_score ?? '—')} · Brightness: ${escapeHtml(quality.brightness ?? '—')} · Glare: ${escapeHtml(quality.glare_ratio ?? '—')} · Skew: ${escapeHtml(quality.skew_angle_degrees ?? '—')} · Text contrast: ${escapeHtml(quality.text_contrast ?? '—')}</p><h2>Numeral height and contrast</h2><p>Numeral height: ${escapeHtml(measurement.estimated_numeral_height_mm ?? 'Not measured')} mm · Status: ${escapeHtml(measurement.measurement_status || 'Not reported')} · Confidence: ${escapeHtml(measurement.measurement_confidence ?? '—')}</p><ul>${contrast}</ul><style>body{font:14px/1.5 system-ui;padding:32px;color:#12213c}table{width:100%;border-collapse:collapse;margin:12px 0 28px}td,th{text-align:left;vertical-align:top;padding:8px;border:1px solid #d9e0ec;overflow-wrap:anywhere}h2{margin-top:30px}</style>`)
  popup.document.close(); popup.focus(); popup.print()
  return true
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char])
}
