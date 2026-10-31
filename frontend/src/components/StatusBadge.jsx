const badge = {
  PASS: ['✓', 'Passed'], REVIEW: ['◷', 'Review'], FAIL: ['×', 'Failed'], READY: ['○', 'Ready'],
  NOT_APPLICABLE: ['—', 'N/A'], 'N/A': ['—', 'N/A'],
}

export function StatusBadge({ status = 'REVIEW' }) {
  const [icon, label] = badge[status] || ['◷', status]
  const tone = status === 'NOT_APPLICABLE' || status === 'N/A' ? 'not-applicable' : status.toLowerCase().replace(' ', '-')
  return <span className={`status-badge ${tone}`}><span aria-hidden="true">{icon}</span> {label}</span>
}
