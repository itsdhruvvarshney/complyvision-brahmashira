const crypto = require('node:crypto')
const express = require('express')

const router = express.Router()
const events = []
const preferences = new Map()
const authorities = [
  { id: 'SA-HR-01', name: 'R. Sharma', region: 'Haryana', status: 'active' },
  { id: 'SA-TN-02', name: 'M. Iyer', region: 'Tamil Nadu', status: 'active' },
]
const inspectors = [
  { id: 'IN-HR-1192', name: 'A. Kumar', region: 'Haryana', status: 'active' },
  { id: 'IN-GJ-2804', name: 'S. Patel', region: 'Gujarat', status: 'active' },
]
const cases = [
  { id: 'CV-2026-1048', organizationId: 'org-northstar', organization: 'Northstar Foods Pvt Ltd', inspectionId: 'PRE-3048', product: 'Oat Drink 1 L', status: 'assigned_to_inspector', authorityId: 'SA-HR-01', inspectorId: 'IN-HR-1192', submittedAt: '2026-10-29T09:00:00.000Z', result: null, remarks: '', timeline: [] },
  { id: 'CV-2026-1046', organizationId: 'org-greenfield', organization: 'Greenfield Essentials', inspectionId: 'PRE-3046', product: 'Dishwash Liquid 500 ml', status: 'assigned_to_supreme', authorityId: 'SA-HR-01', inspectorId: null, submittedAt: '2026-10-28T09:00:00.000Z', result: null, remarks: '', timeline: [] },
  { id: 'CV-2026-1047', organizationId: 'org-greenfield', organization: 'Greenfield Essentials', inspectionId: 'PRE-3047', product: 'Herbal Shampoo 200 ml', status: 'submitted', authorityId: null, inspectorId: null, submittedAt: '2026-10-30T09:00:00.000Z', result: null, remarks: '', timeline: [] },
  { id: 'CV-2026-1041', organizationId: 'org-northstar', organization: 'Northstar Foods Pvt Ltd', inspectionId: 'PRE-3041', product: 'Breakfast Cereal 500 g', status: 'passed', authorityId: 'SA-HR-01', inspectorId: 'IN-GJ-2804', submittedAt: '2026-10-25T09:00:00.000Z', result: 'passed', remarks: 'Declarations reviewed.', timeline: [] },
  { id: 'CV-2026-1038', organizationId: 'org-daily', organization: 'Daily Harvest Co.', inspectionId: 'PRE-3038', product: 'Ground Coffee 250 g', status: 'failed', authorityId: 'SA-TN-02', inspectorId: 'IN-GJ-2804', submittedAt: '2026-10-20T09:00:00.000Z', result: 'failed', remarks: 'Required declaration was not visible.', timeline: [] },
]
const demoFields = [
  { key: 'product_name', label: 'Product name', value: 'Sample product', status: 'PASS', confidence: 94, rule: 'FSSAI label identity', explanation: 'A product identity was detected in the sample result.' },
  { key: 'fssai_licence', label: 'FSSAI licence number', value: '10012022000123', status: 'REVIEW', confidence: 76, rule: 'FSSAI licence display', explanation: 'Confirm the licence number is readable and matches the registered business.' },
  { key: 'net_quantity', label: 'Net quantity', value: '500 g', status: 'PASS', confidence: 91, rule: 'Net quantity declaration', explanation: 'A quantity declaration was detected in the sample result.' },
  { key: 'allergen_declaration', label: 'Allergen declaration', value: 'May contain nuts', status: 'REVIEW', confidence: 68, rule: 'Allergen information', explanation: 'Review the ingredients and allergen statement manually.' },
  { key: 'mrp', label: 'Maximum retail price', value: '₹120', status: 'PASS', confidence: 89, rule: 'Price declaration', explanation: 'A price declaration was detected in the sample result.' },
]
const preVerifications = [
  { id: 'PRE-3048', organizationId: 'org-northstar', organization: 'Northstar Foods Pvt Ltd', product: 'Oat Drink', brand: 'Northstar', category: 'Beverage', status: 'REVIEW', confidence: 82, createdAt: '2026-10-31T10:00:00.000Z', frontImageName: 'oat-drink-front.jpg', backImageName: 'oat-drink-back.jpg', evaluationCount: 1, fields: demoFields.map((field) => ({ ...field, value: field.key === 'product_name' ? 'Oat Drink' : field.value })), sample: true },
  { id: 'PRE-3041', organizationId: 'org-northstar', organization: 'Northstar Foods Pvt Ltd', product: 'Breakfast Cereal', brand: 'Northstar', category: 'Food', status: 'PASS', confidence: 91, createdAt: '2026-10-28T10:00:00.000Z', frontImageName: 'cereal-front.png', backImageName: 'cereal-back.png', evaluationCount: 1, fields: demoFields.map((field) => ({ ...field, status: 'PASS', confidence: Math.max(field.confidence, 86) })), sample: true },
  { id: 'PRE-3036', organizationId: 'org-northstar', organization: 'Northstar Foods Pvt Ltd', product: 'Fruit Bar', brand: 'Northstar', category: 'Food', status: 'FAIL', confidence: 58, createdAt: '2026-10-24T10:00:00.000Z', frontImageName: 'fruit-bar-front.jpg', backImageName: 'fruit-bar-back.jpg', evaluationCount: 2, fields: demoFields.map((field, index) => ({ ...field, status: index === 2 ? 'FAIL' : field.status, confidence: index === 2 ? 42 : field.confidence })), sample: true },
]

for (const item of cases) {
  const steps = [{ type: 'case_submitted', status: 'submitted', actor: 'organisation' }]
  if (['assigned_to_supreme', 'assigned_to_inspector', 'in_progress', 'passed', 'failed'].includes(item.status)) steps.push({ type: 'authority_assigned', status: 'assigned_to_supreme', actor: 'legal_metrology' })
  if (['assigned_to_inspector', 'in_progress', 'passed', 'failed'].includes(item.status)) steps.push({ type: 'inspector_assigned', status: 'assigned_to_inspector', actor: 'supreme' })
  if (['in_progress', 'passed', 'failed'].includes(item.status)) steps.push({ type: 'inspection_started', status: 'in_progress', actor: 'inspector' })
  if (['passed', 'failed'].includes(item.status)) steps.push({ type: 'decision_recorded', status: item.status, actor: 'inspector' })
  item.timeline = steps.map((step, index) => ({ case_id: item.id, event_type: step.type, from_status: index ? steps[index - 1].status : null, to_status: step.status, actor_type: step.actor, actor_id: 'demo-user', note: step.type === 'decision_recorded' ? item.remarks : '', created_at: new Date(new Date(item.submittedAt).getTime() + index * 60 * 60 * 1000).toISOString() }))
  events.push(...item.timeline)
}

const demoEnabled = process.env.NODE_ENV !== 'production' && process.env.PORTAL_DEMO_MODE !== 'false'
const labels = { submitted: 'Pending assignment', assigned_to_supreme: 'Assigned to authority', assigned_to_inspector: 'Assigned to inspector', in_progress: 'In progress', passed: 'Passed', failed: 'Failed' }
const safeAuthority = ({ id, name, region, status, terminated_at, terminated_by, termination_reason }) => ({ id, name, region, status, ...(terminated_at ? { terminated_at, terminated_by, termination_reason } : {}) })
const role = (req) => String(req.header('x-cv-role') || '')
const actorId = (req) => String(req.header('x-cv-actor-id') || 'demo-user')
const requireRole = (...allowed) => (req, res, next) => {
  if (!demoEnabled) return res.status(503).json({ error: 'Portal API is not configured for a database yet.' })
  if (!allowed.includes(role(req))) return res.status(403).json({ error: 'This action is not available to the current portal role.' })
  next()
}
const personName = (list, id) => list.find((person) => person.id === id)?.name || 'Unassigned'
function addEvent(item, req, type, from, to, note = '') {
  const event = { case_id: item.id, event_type: type, from_status: from, to_status: to, actor_type: role(req), actor_id: actorId(req), note, created_at: new Date().toISOString() }
  item.timeline.push(event)
  events.push(event)
}
function present(item) {
  return { ...item, status_label: labels[item.status] || item.status, authority: personName(authorities, item.authorityId), inspector: personName(inspectors, item.inspectorId) }
}
function findCase(req, res) {
  const item = cases.find((entry) => entry.id === req.params.caseId)
  if (!item) { res.status(404).json({ error: 'Case not found.' }); return null }
  return item
}

router.get('/dashboard', requireRole('supreme', 'inspector', 'legal_metrology', 'organisation'), (req, res) => {
  const currentRole = role(req)
  const scoped = currentRole === 'inspector'
    ? (cases.filter((item) => item.inspectorId === actorId(req)).length ? cases.filter((item) => item.inspectorId === actorId(req)) : cases.filter((item) => item.inspectorId && !['passed', 'failed'].includes(item.status)))
    : currentRole === 'supreme'
      ? (cases.filter((item) => item.authorityId === actorId(req)).length ? cases.filter((item) => item.authorityId === actorId(req)) : cases.filter((item) => item.authorityId && !['passed', 'failed'].includes(item.status)))
      : currentRole === 'organisation'
        ? cases.filter((item) => item.organizationId === actorId(req))
        : cases
  const counts = Object.fromEntries(['submitted', 'assigned_to_supreme', 'assigned_to_inspector', 'in_progress', 'passed', 'failed'].map((status) => [status, scoped.filter((item) => item.status === status).length]))
  res.json({ mode: 'demo', role: currentRole, counts, cases: scoped.map(present), authorities: authorities.map(safeAuthority), inspectors, events: events.slice(-30).reverse() })
})

router.get('/cases', requireRole('supreme', 'inspector', 'legal_metrology', 'organisation'), (req, res) => {
  const currentRole = role(req)
  const scoped = currentRole === 'inspector' ? (cases.filter((item) => item.inspectorId === actorId(req)).length ? cases.filter((item) => item.inspectorId === actorId(req)) : cases.filter((item) => item.inspectorId && !['passed', 'failed'].includes(item.status)))
    : currentRole === 'supreme' ? (cases.filter((item) => item.authorityId === actorId(req)).length ? cases.filter((item) => item.authorityId === actorId(req)) : cases.filter((item) => item.authorityId && !['passed', 'failed'].includes(item.status)))
      : currentRole === 'organisation' ? cases.filter((item) => item.organizationId === actorId(req)) : cases
  const status = String(req.query.status || '')
  const q = String(req.query.q || '').toLowerCase()
  res.json({ mode: 'demo', cases: scoped.filter((item) => (!status || item.status === status) && (!q || `${item.id} ${item.organization} ${item.product}`.toLowerCase().includes(q))).map(present) })
})

router.post('/cases', requireRole('organisation'), (req, res) => {
  const organization = String(req.body?.organization || '').trim()
  const product = String(req.body?.product || '').trim()
  const inspectionId = String(req.body?.inspection_id || '').trim()
  if (!organization || !product || !inspectionId) return res.status(400).json({ error: 'Provide an organisation, product, and pre-verification ID.' })
  const organizationId = actorId(req)
  const attempt = cases.filter((item) => item.organizationId === organizationId && item.inspectionId === inspectionId).length + 1
  const item = { id: `CV-DEMO-${Date.now()}`, organizationId, organization, inspectionId, product, attemptNo: attempt, status: 'submitted', authorityId: null, inspectorId: null, submittedAt: new Date().toISOString(), result: null, remarks: '', timeline: [] }
  item.timeline.push({ case_id: item.id, event_type: 'case_submitted', from_status: null, to_status: 'submitted', actor_type: 'organisation', actor_id: actorId(req), note: `Attempt ${attempt}`, created_at: item.submittedAt })
  cases.unshift(item); events.push(...item.timeline)
  res.status(201).json({ mode: 'demo', case: present(item), attempt_no: attempt })
})

router.get('/preverifications', requireRole('organisation'), (req, res) => {
  const own = preVerifications.filter((item) => item.organizationId === actorId(req))
  const items = [...own, ...preVerifications.filter((item) => item.sample && !own.some((mine) => mine.id === item.id))].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
  const counts = { total: items.length, pass: items.filter((item) => item.status === 'PASS').length, fail: items.filter((item) => item.status === 'FAIL').length, review: items.filter((item) => item.status === 'REVIEW').length }
  const activity = items.slice(0, 6).map((item) => ({ id: item.id, product: item.product, status: item.status, created_at: item.createdAt }))
  const trend = Number(req.query.days) === 90
    ? ['Jun','Jul','Aug','Sep','Oct','Today'].map((label, index) => ({ label, count: [4, 6, 3, 8, 5, 7][index] }))
    : ['Oct 1','Oct 7','Oct 13','Oct 19','Oct 25','Today'].map((label, index) => ({ label, count: [1, 1, 1, 1, 2, 1][index] }))
  res.json({ mode: 'demo', counts, items, activity, trend })
})

router.post('/preverifications', requireRole('organisation'), (req, res) => {
  const product = String(req.body?.product || '').trim()
  const brand = String(req.body?.brand || '').trim()
  const category = String(req.body?.category || '').trim()
  const frontImageName = String(req.body?.front_image_name || '').trim()
  const backImageName = String(req.body?.back_image_name || '').trim()
  if (!product || !brand || !category || !frontImageName || !backImageName) return res.status(400).json({ error: 'Product, brand, category, and front/back image selections are required.' })
  const item = { id: `PRE-DEMO-${Date.now()}`, organizationId: actorId(req), organization: String(req.body?.organization || 'Preview organisation'), product, brand, category, status: 'REVIEW', confidence: 78, createdAt: new Date().toISOString(), frontImageName, backImageName, evaluationCount: 1, fields: demoFields.map((field) => ({ ...field, value: field.key === 'product_name' ? product : field.value })), sample: false, analysisNote: 'Preview output only. The FSSAI analyzer and private image storage are not connected yet.' }
  preVerifications.unshift(item)
  res.status(201).json({ mode: 'demo', item })
})

router.get('/preverifications/:inspectionId', requireRole('organisation'), (req, res) => {
  const item = preVerifications.find((entry) => entry.id === req.params.inspectionId && (entry.organizationId === actorId(req) || entry.sample))
  if (!item) return res.status(404).json({ error: 'Pre-verification not found.' })
  res.json({ mode: 'demo', item })
})

router.post('/preverifications/:inspectionId/rerun', requireRole('organisation'), (req, res) => {
  const item = preVerifications.find((entry) => entry.id === req.params.inspectionId && (entry.organizationId === actorId(req) || entry.sample))
  if (!item) return res.status(404).json({ error: 'Pre-verification not found.' })
  item.evaluationCount += 1; item.createdAt = new Date().toISOString(); item.status = 'REVIEW'; item.analysisNote = 'Preview output only. The FSSAI analyzer and private image storage are not connected yet.'
  res.json({ mode: 'demo', item })
})

router.get('/preferences', requireRole('organisation'), (req, res) => res.json({ mode: 'demo', preferences: preferences.get(actorId(req)) || { summary_language: 'en', concise_summaries: false } }))
router.put('/preferences', requireRole('organisation'), (req, res) => {
  const language = String(req.body?.summary_language || 'en')
  if (!['en', 'hi', 'ta'].includes(language)) return res.status(400).json({ error: 'Choose English, Hindi, or Tamil.' })
  const value = { summary_language: language, concise_summaries: Boolean(req.body?.concise_summaries) }
  preferences.set(actorId(req), value)
  res.json({ mode: 'demo', preferences: value })
})

router.post('/cases/:caseId/assign-authority', requireRole('legal_metrology'), (req, res) => {
  const item = findCase(req, res); if (!item) return
  const authority = authorities.find((entry) => entry.id === req.body?.authority_id && entry.status === 'active')
  if (!authority) return res.status(400).json({ error: 'Choose an active supreme authority.' })
  if (['passed', 'failed'].includes(item.status)) return res.status(409).json({ error: 'A completed case cannot be reassigned.' })
  const previous = item.status
  item.authorityId = authority.id; item.inspectorId = null; item.status = 'assigned_to_supreme'; item.result = null
  addEvent(item, req, 'authority_assigned', previous, item.status, `Assigned to ${authority.name}`)
  res.json({ mode: 'demo', case: present(item) })
})

router.post('/cases/:caseId/assign-inspector', requireRole('supreme'), (req, res) => {
  const item = findCase(req, res); if (!item) return
  const inspector = inspectors.find((entry) => entry.id === req.body?.inspector_id && entry.status === 'active')
  if (!inspector) return res.status(400).json({ error: 'Choose an active inspector.' })
  if (item.status !== 'assigned_to_supreme') return res.status(409).json({ error: 'Only cases assigned to this authority can be sent to an inspector.' })
  const previous = item.status
  item.inspectorId = inspector.id; item.status = 'assigned_to_inspector'
  addEvent(item, req, 'inspector_assigned', previous, item.status, `Assigned to ${inspector.name}`)
  res.json({ mode: 'demo', case: present(item) })
})

router.post('/cases/:caseId/start', requireRole('inspector'), (req, res) => {
  const item = findCase(req, res); if (!item) return
  if (item.inspectorId !== actorId(req) && !cases.filter((entry) => entry.inspectorId === actorId(req)).length) return res.status(403).json({ error: 'This case is assigned to another inspector.' })
  if (item.status !== 'assigned_to_inspector') return res.status(409).json({ error: 'This case is not ready to start.' })
  const previous = item.status; item.status = 'in_progress'
  addEvent(item, req, 'inspection_started', previous, item.status)
  res.json({ mode: 'demo', case: present(item) })
})

router.post('/cases/:caseId/decision', requireRole('inspector'), (req, res) => {
  const item = findCase(req, res); if (!item) return
  if (item.inspectorId !== actorId(req) && cases.filter((entry) => entry.inspectorId === actorId(req)).length) return res.status(403).json({ error: 'This case is assigned to another inspector.' })
  const result = String(req.body?.result || '').toLowerCase()
  const remarks = String(req.body?.remarks || '').trim()
  if (!['passed', 'failed'].includes(result)) return res.status(400).json({ error: 'Decision must be passed or failed.' })
  if (!remarks) return res.status(400).json({ error: 'Add remarks before submitting a decision.' })
  if (!['assigned_to_inspector', 'in_progress'].includes(item.status)) return res.status(409).json({ error: 'This case cannot receive a decision in its current stage.' })
  const previous = item.status; item.status = result; item.result = result; item.remarks = remarks
  addEvent(item, req, 'decision_recorded', previous, item.status, remarks)
  res.json({ mode: 'demo', case: present(item) })
})

router.get('/organizations/:organizationId/cases', requireRole('legal_metrology', 'organisation'), (req, res) => {
  if (role(req) === 'organisation' && actorId(req) !== req.params.organizationId) return res.status(403).json({ error: 'You cannot view another organisation.' })
  const ownCases = cases.filter((item) => item.organizationId === req.params.organizationId)
  const scoped = role(req) === 'organisation' && !ownCases.length ? cases.filter((item) => item.organizationId === 'org-northstar') : ownCases
  res.json({ mode: 'demo', cases: scoped.map((item) => ({ ...present(item), timeline: item.timeline })) })
})

router.get('/organizations', requireRole('legal_metrology'), (_req, res) => {
  const organizations = [...new Map(cases.map((item) => [item.organizationId, item.organization])).entries()].map(([id, name]) => {
    const ownCases = cases.filter((item) => item.organizationId === id)
    return { id, name, case_count: ownCases.length, passed_count: ownCases.filter((item) => item.status === 'passed').length, failed_count: ownCases.filter((item) => item.status === 'failed').length, current_cases: ownCases.filter((item) => !['passed', 'failed'].includes(item.status)).map(present) }
  })
  res.json({ mode: 'demo', organizations })
})

router.get('/authorities', requireRole('legal_metrology', 'supreme'), (req, res) => res.json({ mode: 'demo', authorities: authorities.map((item) => ({ ...safeAuthority(item), active_cases: cases.filter((entry) => entry.authorityId === item.id && !['passed', 'failed'].includes(entry.status)).length })) }))
router.get('/authorities/:authorityId', requireRole('legal_metrology', 'supreme'), (req, res) => {
  const authority = authorities.find((item) => item.id === req.params.authorityId)
  if (!authority) return res.status(404).json({ error: 'Authority not found.' })
  const assigned = cases.filter((item) => item.authorityId === authority.id)
  res.json({ mode: 'demo', authority: safeAuthority(authority), cases: assigned.map(present), inspectors: [...new Set(assigned.map((item) => item.inspectorId).filter(Boolean))].map((id) => inspectors.find((item) => item.id === id)), pass_count: assigned.filter((item) => item.status === 'passed').length, fail_count: assigned.filter((item) => item.status === 'failed').length, activity: events.filter((event) => assigned.some((item) => item.id === event.case_id)).slice(-50).reverse() })
})
router.get('/inspectors', requireRole('legal_metrology', 'supreme'), (req, res) => res.json({ mode: 'demo', inspectors: inspectors.map((item) => ({ ...item, active_cases: cases.filter((entry) => entry.inspectorId === item.id && !['passed', 'failed'].includes(entry.status)).length })) }))

router.post('/authorities', requireRole('legal_metrology'), async (req, res) => {
  const id = String(req.body?.id || '').trim()
  const name = String(req.body?.name || '').trim()
  const region = String(req.body?.region || '').trim()
  const initialPassword = String(req.body?.initial_password || '')
  if (!id || !name || initialPassword.length < 12) return res.status(400).json({ error: 'Provide an officer ID, name, and initial password with at least 12 characters.' })
  if (authorities.some((item) => item.id.toLowerCase() === id.toLowerCase())) return res.status(409).json({ error: 'That officer ID already exists.' })
  const salt = crypto.randomBytes(16)
  const passwordHash = await new Promise((resolve, reject) => crypto.scrypt(initialPassword, salt, 64, (error, key) => error ? reject(error) : resolve(key)))
  authorities.push({ id, name, region, status: 'active', password_salt: salt.toString('hex'), password_hash: passwordHash.toString('hex') })
  res.status(201).json({ mode: 'demo', authority: { id, name, region, status: 'active' }, credential_saved_as: 'scrypt hash' })
})

router.post('/authorities/:authorityId/terminate', requireRole('legal_metrology'), (req, res) => {
  const authority = authorities.find((item) => item.id === req.params.authorityId)
  if (!authority) return res.status(404).json({ error: 'Authority not found.' })
  const reason = String(req.body?.reason || '').trim()
  if (!reason) return res.status(400).json({ error: 'Provide a termination reason.' })
  const openCases = cases.filter((item) => item.authorityId === authority.id && !['passed', 'failed'].includes(item.status))
  if (openCases.length) return res.status(409).json({ error: 'Reassign all open cases before deactivating this authority.', open_case_ids: openCases.map((item) => item.id) })
  authority.status = 'terminated'; authority.terminated_at = new Date().toISOString(); authority.terminated_by = actorId(req); authority.termination_reason = reason
  res.json({ mode: 'demo', authority: { id: authority.id, name: authority.name, status: authority.status, terminated_at: authority.terminated_at, termination_reason: reason } })
})

router.post('/authorities/:authorityId/reset-password', requireRole('legal_metrology'), async (req, res) => {
  const authority = authorities.find((item) => item.id === req.params.authorityId && item.status === 'active')
  if (!authority) return res.status(404).json({ error: 'Active authority not found.' })
  const password = String(req.body?.password || '')
  if (password.length < 12) return res.status(400).json({ error: 'New password must be at least 12 characters.' })
  const salt = crypto.randomBytes(16)
  const hash = await new Promise((resolve, reject) => crypto.scrypt(password, salt, 64, (error, key) => error ? reject(error) : resolve(key)))
  authority.password_salt = salt.toString('hex'); authority.password_hash = hash.toString('hex')
  res.json({ mode: 'demo', authority_id: authority.id, password_reset: true })
})

router.get('/events', requireRole('legal_metrology', 'supreme', 'inspector', 'organisation'), (req, res) => res.json({ mode: 'demo', events: events.slice(-100).reverse() }))

module.exports = router
