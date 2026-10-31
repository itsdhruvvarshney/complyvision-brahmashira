export const rules = [
  { id: 'rule-6-quantity', reference: 'Rule 6(1)(d)', title: 'Net quantity', description: 'The net quantity must be declared using a standard unit of weight, measure, or number.' },
  { id: 'rule-6-mrp', reference: 'Rule 6(1)(e)', title: 'Maximum retail price', description: 'MRP must be printed as inclusive of all taxes.' },
  { id: 'rule-6-date', reference: 'Rule 6(1)(f)', title: 'Month and year of packing', description: 'The month and year of manufacture, packing, or import must be declared.' },
  { id: 'rule-6-consumer', reference: 'Rule 6(1)(b)', title: 'Consumer care details', description: 'Name, address, telephone number, and email address of the responsible person must be available.' },
]

export const demoInspection = {
  id: 'CV-2026-00128', product: 'Masala Biscuits', brand: 'Annapurna Foods', createdAt: '31 Oct 2026, 15:40', status: 'PASS', confidence: 96,
  image: null,
  summary: 'Masala biscuits, 200 grams, priced forty rupees, packed March 2026. All required details were found.',
  fields: [
    { id: 'quantity', label: 'Net quantity', value: '200 g', confidence: 99, status: 'PASS', ruleId: 'rule-6-quantity', region: { left: 22, top: 70, width: 50, height: 11 } },
    { id: 'mrp', label: 'MRP (incl. taxes)', value: '₹40.00', confidence: 97, status: 'PASS', ruleId: 'rule-6-mrp', region: { left: 24, top: 82, width: 42, height: 10 } },
    { id: 'packing-date', label: 'Packed on', value: '03/2026', confidence: 94, status: 'PASS', ruleId: 'rule-6-date', region: { left: 20, top: 56, width: 53, height: 10 } },
    { id: 'care', label: 'Consumer care', value: 'Detected', confidence: 92, status: 'PASS', ruleId: 'rule-6-consumer', region: { left: 21, top: 39, width: 57, height: 10 } },
  ],
}

export const activity = [
  { id: 'CV-2026-00128', product: 'Masala Biscuits', status: 'PASS', time: 'Just now' },
  { id: 'CV-2026-00127', product: 'Assam Tea', status: 'REVIEW', time: '18 min ago' },
  { id: 'CV-2026-00126', product: 'Cooking Oil', status: 'FAIL', time: '1 hr ago' },
]
