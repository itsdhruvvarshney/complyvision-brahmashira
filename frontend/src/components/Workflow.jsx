const steps = ['Image upload', 'Image-quality analysis', 'PaddleOCR extraction', 'Structured field extraction', 'Deterministic validation', 'Explainable results', 'Accessibility summary', 'Report and history']

export function Workflow() {
  return <section className="workflow-card" id="workflow" aria-label="Inspection workflow">
    <h2>Rules decide. AI explains.</h2>
    <p>Compliance is checked by a deterministic rule engine. AI only interprets text and writes the accessibility summary.</p>
    <ol className="workflow-steps">{steps.map((step, index) => <li className={index === 4 ? 'highlight' : ''} key={step}>{step}</li>)}</ol>
    <p className="workflow-note">Statuses: PASS · FAIL · REVIEW · NOT APPLICABLE. Reports export as PDF and JSON.</p>
  </section>
}
