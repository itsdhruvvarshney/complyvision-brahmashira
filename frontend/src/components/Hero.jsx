import { useEffect, useState } from 'react'

export function Hero() {
  const [audioState, setAudioState] = useState({ speaking: false, unavailable: false })
  useEffect(() => {
    const updateAudioState = (event) => setAudioState({ speaking: Boolean(event.detail?.speaking), unavailable: Boolean(event.detail?.unavailable) })
    window.addEventListener('complyvision:audio-state', updateAudioState)
    return () => window.removeEventListener('complyvision:audio-state', updateAudioState)
  }, [])

  const hearSummary = () => {
    document.querySelector('#demo')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    window.dispatchEvent(new Event('complyvision:hear-audio-summary'))
  }
  return <header className="hero" id="top">
    <span className="eyebrow"><i />Legal Metrology (Packaged Commodities) Rules, 2011</span>
    <h1>Every label, <em>verified.</em><br />Every shopper, informed.</h1>
    <p className="hero-copy">An inspection workspace that reads packaging with OCR, validates declarations with deterministic rules, and turns the result into audio summaries in English and Indian languages.</p>
    <div className="hero-actions">
      <a className="primary-button" href="#demo">Try the interactive demo <span>→</span></a>
      <button className="secondary-button" type="button" onClick={hearSummary} aria-pressed={audioState.speaking}>{audioState.speaking ? 'Stop audio summary' : 'Hear the audio summary'}</button>
    </div>
    <p className="demo-note">Interactive preview below uses demo data, not a real inspection.</p>
    <p className="hero-audio-status" aria-live="polite">{audioState.speaking ? 'Playing the sample summary. Use the demo controls to change the language.' : audioState.unavailable ? 'Audio playback is unavailable in this browser; the summary text remains available in the demo.' : ''}</p>
  </header>
}
