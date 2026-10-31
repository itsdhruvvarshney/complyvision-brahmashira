import { activity, demoInspection } from '../data/demoData'

const KEY = 'complyvision-inspections'
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms))
export const getInspections = () => JSON.parse(localStorage.getItem(KEY) || 'null') || [demoInspection, ...activity.slice(1).map(item => ({ ...demoInspection, ...item, createdAt: item.time }))]
export const saveInspection = (inspection) => localStorage.setItem(KEY, JSON.stringify([inspection, ...getInspections().filter(item => item.id !== inspection.id)]))

export async function inspectPackage(file) {
  await delay(850)
  const inspection = { ...demoInspection, id: `CV-${Date.now().toString().slice(-8)}`, createdAt: new Date().toLocaleString('en-IN'), image: URL.createObjectURL(file), product: file.name.replace(/\.[^.]+$/, '').replace(/[-_]/g, ' ') || 'Uploaded package' }
  saveInspection(inspection)
  return inspection
}

export function downloadJson(inspection) {
  const blob = new Blob([JSON.stringify(inspection, null, 2)], { type: 'application/json' })
  const link = Object.assign(document.createElement('a'), { href: URL.createObjectURL(blob), download: `${inspection.id}.json` })
  link.click(); URL.revokeObjectURL(link.href)
}
