import { activity, demoInspection } from '../data/demoData'
import { buildDeclarationFields, calculateFieldConfidence, csvForInspection } from './workspaceModel'

const KEY = 'complyvision-inspections'
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
    return JSON.parse(localStorage.getItem(KEY) || 'null') || [
      demoInspection,
      ...activity.slice(1).map((item) => ({ ...demoInspection, ...item, createdAt: item.time })),
    ]
  } catch {
    return [demoInspection]
  }
}

export const getLatestInspection = () => {
  try {
    const saved = JSON.parse(localStorage.getItem(KEY) || '[]')
    const latest = saved.find((item) => item.evaluated && item.report)
    if (!latest) return emptyInspection
    const timestamp = parseLegacyInspectionDate(latest.createdAt)
    if (latest.report?.extracted_fields) {
      const fields = buildDeclarationFields(latest.report.extracted_fields, latest.report)
      const confidence = calculateFieldConfidence(fields)
      const product = fields.find((field) => field.id === 'product_name' && field.present)?.value || latest.product
      const brand = fields.find((field) => field.id === 'brand_name' && field.present)?.value || latest.brand
      return { ...latest, fields, product, brand, createdAt: timestamp, timestamp, confidence: confidence.value, confidenceCapped: confidence.capped, confidenceDefinition: confidence.explanation }
    }
    return {
      ...latest,
      createdAt: timestamp,
      timestamp,
      fields: (latest.fields || []).map((field) => {
        const present = field.present ?? field.value !== 'Not detected'
        const status = field.id === 'brand_name' && !present && field.status === 'PASS'
          ? 'REVIEW'
          : field.status
        return { ...field, present, status }
      }),
    }
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
    const saved = { ...inspection, report, image: null }
    localStorage.setItem(KEY, JSON.stringify([saved, ...getInspections().filter((item) => item.id !== inspection.id)]))
  } catch {
    // Keep the current evaluation visible if the browser's local storage is full.
  }
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
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
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
  saveInspection(inspection)
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

export function printReport(inspection) {
  const rows = (inspection.fields || []).map((field) => `<tr><td>${escapeHtml(field.label)}</td><td>${escapeHtml(field.value)}</td><td>${escapeHtml(field.status)}</td><td>${field.confidence}%</td></tr>`).join('')
  const popup = window.open('', '_blank', 'noopener,noreferrer,width=900,height=700')
  if (!popup) return false
  popup.document.write(`<title>${escapeHtml(inspection.id)} report</title><h1>${escapeHtml(inspection.product)}</h1><p>${escapeHtml(inspection.id)} · ${escapeHtml(inspection.createdAt)}</p><table><thead><tr><th>Declaration</th><th>Value</th><th>Status</th><th>Confidence</th></tr></thead><tbody>${rows}</tbody></table><style>body{font:14px system-ui;padding:32px;color:#12213c}table{width:100%;border-collapse:collapse}td,th{text-align:left;padding:10px;border:1px solid #d9e0ec}</style>`)
  popup.document.close(); popup.focus(); popup.print()
  return true
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char])
}
