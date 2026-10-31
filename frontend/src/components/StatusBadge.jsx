const badge = {
  PASS: ['✓', 'Passed'], REVIEW: ['◷', 'Review'], FAIL: ['×', 'Failed'], READY: ['○', 'Ready'],
}

export function StatusBadge({ status = 'REVIEW' }) {
  const [icon, label] = badge[status] || ['◷', status]
  return <span className={`status-badge ${status.toLowerCase().replace(' ', '-')}`}><span aria-hidden="true">{icon}</span> {label}</span>
}
