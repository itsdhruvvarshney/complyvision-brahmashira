import test from 'node:test'
import assert from 'node:assert/strict'
import { buildDeclarationFields, formatInspectionDate, inferProductIdentityFromOcr, statusCountsMatchTotal, summarizeFields } from './workspaceModel.js'

test('manufacturer parser separates company, licence and registered office', () => {
  const fields = buildDeclarationFields({ manufacturer: { normalized_value: 'DABUR INDIA LTD. · Sonitpur - 784105, Assam, M-C-64 M/2022 Regd. Office & Consumer Cell: 8/3, Asaf Ali Road, New Delhi-110002', present: true, extraction_confidence: .96 } })
  const byId = Object.fromEntries(fields.map((field) => [field.id, field]))
  assert.match(byId.manufacturer_name.value, /DABUR INDIA LTD/i)
  assert.match(byId.manufacturing_address.value, /Sonitpur/)
  assert.match(byId.licence_number.value, /M-C-64 M\/2022/i)
  assert.match(byId.registered_office_address.value, /Asaf Ali Road/)
})

test('summary totals include all listed fields, including not detected declarations', () => {
  const fields = buildDeclarationFields({ product: { normalized_value: 'Rose Water', present: true, extraction_confidence: .98 }, net_quantity: { normalized_value: null, present: false } })
  const counts = summarizeFields(fields)
  assert.equal(counts.pass + counts.review + counts.fail + counts.na, counts.total)
  assert.equal(counts.pass + counts.review + counts.fail, counts.total)
  assert.equal(statusCountsMatchTotal(fields), true)
  assert.ok(counts.fail > 0)
})

test('inspection date uses a named month and unambiguous day', () => {
  const formatted = formatInspectionDate('2026-11-01T12:20:00.000Z')
  assert.match(formatted, /Nov/i)
  assert.match(formatted, /01/)
})

test('legacy OCR product identity prefers the top Rose Water heading over ingredients', () => {
  const report = { image: { width: 1000, height: 1200 }, ocr: { evidence: [
    { normalized_text: 'Dabur', confidence: .98, box: [300, 100, 500, 140] },
    { normalized_text: 'Gulabari Premium', confidence: .97, box: [260, 145, 640, 190] },
    { normalized_text: 'Rose Water', confidence: .97, box: [320, 195, 580, 240] },
    { normalized_text: 'Aqua, Fragrance (Rooh Gulab), Rose Oil', confidence: .99, box: [220, 720, 780, 760] },
  ] } }
  const identity = inferProductIdentityFromOcr(report)
  assert.equal(identity.productName, 'Dabur Gulabari Premium Rose Water')
  assert.equal(identity.commodity, 'Rose Water')
  const fields = buildDeclarationFields({ product_name: { present: true, normalized_value: 'Aqua, Fragrance (Rooh Gulab), Rose Oil' }, product: { present: true, normalized_value: 'Oil' } }, report)
  assert.equal(fields.find((field) => field.id === 'product_name').value, 'Dabur Gulabari Premium Rose Water')
  assert.equal(fields.find((field) => field.id === 'product').value, 'Rose Water')
})

test('title recovery strips directions that OCR joined to the product heading', () => {
  const identity = inferProductIdentityFromOcr({ image: { width: 1000, height: 1200 }, ocr: { evidence: [
    { normalized_text: 'Dabur Gulabari PREMIUM Rose Water How to use Rose water: Use with Cotton, face pack or glycerine.', confidence: .98, box: [150, 80, 850, 240] },
  ] } })
  assert.equal(identity.productName, 'Dabur Gulabari PREMIUM Rose Water')
  const fields = buildDeclarationFields({ product_name: { present: false, normalized_value: null }, product: { present: false, normalized_value: null } }, { image: { width: 1000, height: 1200 }, ocr: { evidence: [
    { normalized_text: 'Dabur Gulabari PREMIUM Rose Water How to use Rose water: Use with Cotton, face pack or glycerine.', confidence: .98, box: [150, 80, 850, 240] },
  ] } })
  assert.equal(fields.find((field) => field.id === 'product').value, 'Rose Water')
})
