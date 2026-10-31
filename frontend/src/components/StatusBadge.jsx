export function StatusBadge({ status }) { return <span className={`status-badge ${status.toLowerCase().replace(' ', '-')}`}>{status}</span> }
