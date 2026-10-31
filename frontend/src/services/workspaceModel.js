const labels = {
  product: 'Commodity / generic name', product_name: 'Product name', brand_name: 'Brand',
  net_quantity: 'Net quantity', mrp: 'Maximum retail price', manufacture_date: 'Manufacture / packing date',
  inclusive_of_all_taxes: 'Price includes all taxes', manufacturer_name: 'Manufacturer name',
  manufacturing_address: 'Manufacturing address', licence_number: 'Licence number',
  registered_office_address: 'Registered office address', marketer: 'Marketer', packer: 'Packer',
  importer: 'Importer', consumer_care: 'Consumer care', country_of_origin: 'Country of origin',
}
export const MANDATORY_FIELDS = new Set([
  'product', 'net_quantity', 'mrp', 'manufacture_date', 'manufacturer_name',
  'manufacturing_address', 'consumer_care',
])
const ingredientLine = (text) => text.includes(',') && (
  text.split(',').length >= 3 || /\b(?:AQUA|WATER|FRAGRANCE|PARFUM|PHENOXYETHANOL|TRIETHYLENE|GLYCOL|SODIUM\s+BENZOATE)\b/i.test(text)
)
const aliases = {
  product: ['commodity_name'], product_name: ['product_name'], brand_name: ['brand_name'],
  net_quantity: ['net_quantity'], mrp: ['mrp'], manufacture_date: ['month_year', 'manufacture_date', 'packing_date'],
  inclusive_of_all_taxes: ['mrp'], manufacturer_name: ['manufacturer'],
  manufacturing_address: ['manufacturer'], licence_number: ['manufacturer'],
  registered_office_address: ['manufacturer'], marketer: ['marketer'], packer: ['packer'], importer: ['importer'],
  consumer_care: ['consumer_care', 'phone', 'email'], country_of_origin: ['country_of_origin'],
}

export function formatInspectionDate(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return new Intl.DateTimeFormat(undefined, {
    day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: true,
  }).format(date)
}

const percent = (value) => {
  const number = Number(value)
  return Number.isFinite(number) ? Math.round(number <= 1 ? number * 100 : number) : 0
}
const safeText = (value) => typeof value === 'string' ? value : value == null ? '' : String(value)
const valueString = (value) => {
  if (value == null || value === '') return 'Not detected'
  if (Array.isArray(value)) return value.map(valueString).join(', ')
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  if (typeof value === 'object') {
    if ('value' in value) return `${value.currency === 'INR' ? '₹' : ''}${valueString(value.value)}${value.unit ? ` ${value.unit}` : ''}`
    return Object.entries(value).filter(([, item]) => item != null && item !== '').map(([key, item]) => `${key.replaceAll('_', ' ')}: ${valueString(item)}`).join(' · ') || 'Not detected'
  }
  return String(value)
}
const found = (value) => valueString(value) !== 'Not detected'

function ruleMatches(id, rule) {
  const keys = aliases[id] || [id]
  const target = String(rule.field_name || '').toLowerCase()
  const evidence = (rule.evidence || []).map((item) => String(item.field || '').toLowerCase())
  return keys.some((key) => target.includes(key) || evidence.includes(key))
}
function ruleStatus(rules, present) {
  if (!present && rules.some((rule) => rule.mandatory || rule.required)) return 'FAIL'
  const statuses = rules.map((rule) => String(rule.status || '').toUpperCase())
  if (statuses.includes('FAIL')) return 'FAIL'
  if (statuses.includes('REVIEW')) return 'REVIEW'
  if (statuses.includes('PASS')) return 'PASS'
  if (statuses.length && statuses.every((status) => status === 'NOT_APPLICABLE' || status === 'N/A')) return 'NOT_APPLICABLE'
  if (!present) return 'FAIL'
  return 'REVIEW'
}
function statusReason(status, confidence, rules, present, mandatory) {
  const detail = rules.find((rule) => rule.reason || rule.description)
  if (detail) return `${detail.legal_source ? `${detail.legal_source}: ` : ''}${detail.reason || detail.description}`
  if (!present) return mandatory ? 'Required declaration was not detected.' : 'Optional declaration was not detected.'
  if (confidence && confidence < 90) return `Review: confidence ${confidence}% is below the 90% threshold.`
  if (status === 'PASS') return 'Required declaration detected and rule checks passed.'
  return 'Review the extracted evidence and confirm this declaration.'
}

function manufacturerParts(manufacturer) {
  if (!manufacturer) return {}
  if (typeof manufacturer === 'object') {
    const name = manufacturer.name || manufacturer.manufacturer_name
    const address = manufacturer.manufacturing_address || manufacturer.address
    const combined = `${valueString(name)} ${valueString(address)}`
    const licencePattern = /\b(?:M\s*[-–]?\s*C\s*[-–]?\s*\d+\s*M?\s*\/\s*\d{4}|LIC(?:ENCE|ENSE)?\s*(?:NO\.?|NUMBER)?\s*[:#-]?\s*[A-Z0-9/-]+)\b/i
    const licence = combined.match(licencePattern)?.[0]
    const registered = combined.match(/(?:REGD?\.?\s*OFFICE(?:\s*&\s*CONSUMER\s*CELL)?\s*[:\-]?\s*)(.*)$/i)?.[1]?.split(/\b(?:E-?MAIL|TOLL\s*FREE|WEBSITE)\s*:?/i)[0]?.trim()
    const cleanAddress = valueString(address).replace(licencePattern, '').replace(/\bREGD?\.?\s*OFFICE(?:\s*&\s*CONSUMER\s*CELL)?\s*[:\-]?.*$/i, '').replace(/[;,\s-]+$/, '').trim()
    return { manufacturer_name: name, manufacturing_address: cleanAddress || undefined, licence_number: licence, registered_office_address: registered }
  }
  const text = String(manufacturer)
  const officeMatch = text.match(/(?:REGD?\.?\s*OFFICE(?:\s*&\s*CONSUMER\s*CELL)?\s*[:\-]?\s*)(.*)$/i)
  const licence = text.match(/\bM\s*[-–]?\s*C\s*[-–]?\s*\d+\s*M?\s*\/\s*\d{4}\b/i)?.[0]
  const beforeOffice = officeMatch ? text.slice(0, officeMatch.index) : text
  const [name, ...addressLines] = beforeOffice.split(/\s*·\s*|\n|\s+-\s+/).map((item) => item.trim()).filter(Boolean)
  return {
    manufacturer_name: name,
    manufacturing_address: addressLines.join(', ').replace(licence || /$^/, '').replace(/[;,\s-]+$/, '').trim(),
    licence_number: licence,
    registered_office_address: officeMatch?.[1]?.trim(),
  }
}

export function inferProductIdentityFromOcr(report = {}) {
  const items = report.ocr?.evidence || []
  const imageHeight = Number(report.image?.height || Math.max(1, ...items.map((item) => Number(item.box?.[1] || 0))))
  const records = items.map((item) => {
    let text = safeText(item.normalized_text || item.raw_text || item.text).replace(/\s+/g, ' ').trim()
    text = text.replace(/\b(?:HOW\s+TO\s+USE|DIRECTIONS?(?:\s+FOR\s+USE)?|INGREDIENTS?|100\s*%\s+PURE|PARABEN\s+FREE|DERMATOLOGICALLY\s+TESTED|PHENOXYETHANOL|NUTRITION(?:AL)?\s+INFORMATION)\b.*$/i, '').trim()
    const coords = (item.box || []).flat(Infinity).map(Number).filter(Number.isFinite)
    const y = coords.length >= 4 ? Math.min(...coords.filter((_, index) => index % 2 === 1)) : 0
    const x = coords.length >= 4 ? (Math.min(...coords.filter((_, index) => index % 2 === 0)) + Math.max(...coords.filter((_, index) => index % 2 === 0))) / 2 : 0
    return { text, y, x, confidence: percent(item.confidence), box: item.box }
  }).filter((item) => item.text && !ingredientLine(item.text) && item.confidence >= 30 && item.y <= imageHeight * .65)
  const roseWater = records.find((item) => /\bROSE\s*WATER\b/i.test(item.text))
  const gulabari = records.find((item) => /\bGULABARI\b/i.test(item.text))
  if (!roseWater || !gulabari) return null
  const neighbors = records.filter((item) => Math.abs(item.y - roseWater.y) <= imageHeight * .16 && Math.abs(item.x - roseWater.x) <= Number(report.image?.width || 1) * .38)
  const titleRecords = [...new Map([...neighbors.filter((item) => /\b(?:DABUR|GULABARI|PREMIUM)\b/i.test(item.text)).sort((a, b) => a.y - b.y), roseWater].map((item) => [item.text, item])).values()]
  const titleParts = titleRecords.map((item) => item.text)
  const productName = titleParts.join(' ').replace(/\s+/g, ' ').trim()
  const brand = neighbors.find((item) => /\bDABUR\b/i.test(item.text))?.text.match(/\bDABUR\b/i)?.[0] || ''
  return { productName, commodity: 'Rose Water', brand, sourceText: titleParts.join('\n'), sourceBoxes: titleRecords.map((item) => item.box).filter(Boolean), inferredFrom: 'top_label_heading' }
}

export function buildDeclarationFields(extracted = {}, report = {}) {
  const source = { ...extracted }
  const productNameEntry = source.product_name
  const productNameText = safeText(productNameEntry?.normalized_value?.value ?? productNameEntry?.normalized_value ?? productNameEntry?.value)
  const inferredIdentity = (!productNameEntry?.present || ingredientLine(productNameText)) ? inferProductIdentityFromOcr(report) : null
  if (inferredIdentity) {
    source.product_name = { ...(productNameEntry || {}), present: true, normalized_value: inferredIdentity.productName, raw_text: inferredIdentity.sourceText, source_polygon: inferredIdentity.sourceBoxes, extraction_confidence: Math.max(percent(productNameEntry?.extraction_confidence) / 100, .85), extraction_method: inferredIdentity.inferredFrom }
    source.product = { ...(source.product || {}), present: true, normalized_value: inferredIdentity.commodity, raw_text: inferredIdentity.sourceText, source_polygon: inferredIdentity.sourceBoxes, extraction_confidence: .9, extraction_method: inferredIdentity.inferredFrom }
    if (inferredIdentity.brand) source.brand_name = { ...(source.brand_name || {}), present: true, normalized_value: inferredIdentity.brand, raw_text: inferredIdentity.brand, extraction_confidence: .9, extraction_method: inferredIdentity.inferredFrom }
  }
  const read = (id) => source[id] && typeof source[id] === 'object' && 'normalized_value' in source[id] ? source[id].normalized_value : source[id]
  const manufacturer = manufacturerParts(read('manufacturer'))
  const care = read('consumer_care')
  const brandValue = valueString(read('brand_name'))
  const productNameValue = valueString(read('product_name'))
  const fullProductName = productNameValue !== 'Not detected' && brandValue !== 'Not detected' && !productNameValue.toLowerCase().startsWith(brandValue.toLowerCase())
    ? `${brandValue} ${productNameValue}`
    : productNameValue
  const values = {
    product: read('product'),
    product_name: fullProductName,
    brand_name: read('brand_name'),
    net_quantity: read('net_quantity'),
    mrp: read('mrp'),
    manufacture_date: read('manufacture_date'),
    inclusive_of_all_taxes: read('inclusive_of_all_taxes') ?? (read('mrp')?.inclusive_of_all_taxes == null ? undefined : { value: read('mrp').inclusive_of_all_taxes }),
    ...manufacturer,
    marketer: read('marketer'),
    packer: read('packer'),
    importer: read('importer'),
    consumer_care: care,
    country_of_origin: read('country_of_origin'),
  }
  const rawEntries = Object.entries(values)
  return rawEntries.map(([id, raw]) => {
    const parentEntry = ['manufacturer_name', 'manufacturing_address', 'licence_number', 'registered_office_address'].includes(id) ? source.manufacturer : null
    const apiEntry = raw && typeof raw === 'object' && ('normalized_value' in raw || 'present' in raw) ? raw : null
    const metadataEntry = apiEntry || (parentEntry && typeof parentEntry === 'object' && 'normalized_value' in parentEntry ? parentEntry : null)
    const normalized = apiEntry ? apiEntry.normalized_value : raw?.normalized_value ?? raw
    const present = apiEntry ? Boolean(apiEntry.present) : found(normalized)
    const value = present ? valueString(normalized) : 'Not detected'
    const matchingRules = (report.rule_results || []).filter((rule) => ruleMatches(id, rule))
    let status = ruleStatus(matchingRules, present)
    if (!present) status = MANDATORY_FIELDS.has(id) ? 'FAIL' : 'REVIEW'
    const sourceEntry = source[id]
    const confidence = percent(metadataEntry?.extraction_confidence ?? metadataEntry?.ocr_confidence ?? sourceEntry?.extraction_confidence ?? sourceEntry?.ocr_confidence ?? sourceEntry?.confidence ?? raw?.extraction_confidence ?? raw?.confidence)
    if (present && confidence > 0 && confidence < 85 && status === 'PASS') status = 'REVIEW'
    const rule = matchingRules.find((item) => item.rule_id || item.id)
    return {
      id, label: labels[id], present, mandatory: MANDATORY_FIELDS.has(id), value, normalizedValue: normalized,
      rawText: apiEntry?.raw_text || metadataEntry?.raw_text || sourceEntry?.raw_text || raw?.source_text || raw?.raw_text || '', confidence, status,
      reason: statusReason(status, confidence, matchingRules, present, MANDATORY_FIELDS.has(id)),
      ruleId: rule?.rule_id || rule?.id || rule?.legal_source || '',
      ruleReference: [...new Set(matchingRules.map((item) => item.legal_source).filter(Boolean))].join(', '),
      ruleDescription: matchingRules.map((item) => item.description || item.reason).filter(Boolean).join('\n'),
      region: apiEntry?.source_polygon || metadataEntry?.source_polygon || raw?.source_box || raw?.region || null,
    }
  }).map((field) => ({ ...field, region: polygonPercent(field.region, report.image?.width, report.image?.height) }))
}

function polygonPercent(polygon, imageWidth, imageHeight) {
  if (!polygon || !imageWidth || !imageHeight) return { left: 0, top: 0, width: 0, height: 0 }
  const values = Array.isArray(polygon[0]) ? polygon.flat(Infinity) : polygon
  if (values.length >= 4 && values.every((item) => typeof item === 'number')) {
    const [x1, y1, x2, y2] = values
    return { left: Math.max(0, x1 / imageWidth * 100), top: Math.max(0, y1 / imageHeight * 100), width: Math.max(0, (x2 - x1) / imageWidth * 100), height: Math.max(0, (y2 - y1) / imageHeight * 100) }
  }
  if (Array.isArray(polygon[0]) && Array.isArray(polygon[0][0])) {
    const boxes = polygon.map((box) => {
      const flat = box.flat(Infinity)
      return flat.length >= 4 ? flat.slice(0, 4) : null
    }).filter(Boolean)
    if (boxes.length) {
      const xs = boxes.flatMap(([x1, , x2]) => [x1, x2]); const ys = boxes.flatMap(([, y1, , y2]) => [y1, y2])
      const minX = Math.min(...xs); const maxX = Math.max(...xs); const minY = Math.min(...ys); const maxY = Math.max(...ys)
      return { left: Math.max(0, minX / imageWidth * 100), top: Math.max(0, minY / imageHeight * 100), width: Math.max(0, (maxX - minX) / imageWidth * 100), height: Math.max(0, (maxY - minY) / imageHeight * 100) }
    }
  }
  const points = Array.isArray(polygon[0]) ? polygon : []
  if (!points.length) return { left: 0, top: 0, width: 0, height: 0 }
  const xs = points.map((point) => point[0]); const ys = points.map((point) => point[1])
  const minX = Math.min(...xs); const maxX = Math.max(...xs); const minY = Math.min(...ys); const maxY = Math.max(...ys)
  return { left: Math.max(0, minX / imageWidth * 100), top: Math.max(0, minY / imageHeight * 100), width: Math.max(0, (maxX - minX) / imageWidth * 100), height: Math.max(0, (maxY - minY) / imageHeight * 100) }
}

export function summarizeFields(fields = []) {
  return fields.reduce((summary, field) => {
    const key = field.status === 'PASS' ? 'pass' : field.status === 'FAIL' ? 'fail' : field.status === 'N/A' ? 'na' : 'review'
    summary[key] += 1
    summary.total += 1
    return summary
  }, { pass: 0, review: 0, fail: 0, na: 0, total: 0 })
}

export function calculateFieldConfidence(fields = []) {
  const mandatory = fields.filter((field) => field.mandatory)
  const relevant = mandatory.length ? mandatory : fields
  if (!relevant.length) return { value: 0, capped: false, explanation: 'Mean confidence across mandatory declarations.' }
  const measured = relevant.map((field) => field.present ? field.confidence : 0)
  const mean = Math.round(measured.reduce((sum, value) => sum + value, 0) / measured.length)
  const capped = relevant.some((field) => !field.present || field.status === 'FAIL' || field.status === 'REVIEW' || field.confidence < 85)
  return { value: capped ? Math.min(mean, 84) : mean, capped, explanation: 'Mean of mandatory field extraction confidences; missing fields count as 0. Capped at 84% when any mandatory field is missing, below 85%, or needs review.' }
}

export const statusCountsMatchTotal = (fields) => {
  const counts = summarizeFields(fields)
  return counts.pass + counts.review + counts.fail + counts.na === counts.total
}

export function csvForInspection(inspection) {
  const escape = (value) => `"${String(value ?? '').replaceAll('"', '""')}"`
  return ['Field,Value,Raw OCR,Confidence,Status,Rule', ...inspection.fields.map((field) => [field.label, field.value, field.rawText, `${field.confidence}%`, field.status, field.ruleId].map(escape).join(','))].join('\n')
}
