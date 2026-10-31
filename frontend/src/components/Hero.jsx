export function Hero() {
  const hearSummary = () => document.querySelector('#workspace')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  return <header className="hero" id="top">
    <span className="eyebrow"><i />Legal Metrology (Packaged Commodities) Rules, 2011</span>
    <h1>Every label, <em>verified.</em><br />Every shopper, informed.</h1>
    <p className="hero-copy">An inspection workspace that reads packaging with OCR, validates declarations with deterministic rules, and turns the result into audio summaries in English and Indian languages.</p>
    <div className="hero-actions">
      <a className="primary-button" href="#demo">Open inspection workspace <span>→</span></a>
      <button className="secondary-button" type="button" onClick={hearSummary}>Hear the audio summary</button>
    </div>
    <p className="demo-note">Interactive preview below uses demo data, not a real inspection.</p>
  </header>
}
