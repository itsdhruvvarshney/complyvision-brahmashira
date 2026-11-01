import { useCallback, useEffect, useMemo, useState } from 'react'

const products = [
  { name: 'Masala Biscuits', kind: 'biscuit', verdict: 'pass', badge: 'All declarations found', fields: [['Net quantity', '200 g', 'ok', 'Rule 6(1)'], ['MRP', '₹40.00', 'ok', 'Rule 6(1)'], ['Packed on', '03/2026', 'ok', 'Rule 6(1)']], copy: { en: 'Masala biscuits, 200 grams, priced forty rupees, packed March 2026. All required details were found.', hi: 'मसाला बिस्कुट, 200 ग्राम, कीमत चालीस रुपये, मार्च 2026 में पैक। सभी ज़रूरी जानकारी मौजूद है।', ta: 'மசாலா பிஸ்கட், 200 கிராம், விலை நாற்பது ரூபாய், மார்ச் 2026 இல் பேக் செய்யப்பட்டது. தேவையான அனைத்து தகவலும் உள்ளது.' } },
  { name: 'Assam Tea', kind: 'tea', verdict: 'review', badge: 'Needs a second look', fields: [['Net quantity', '250 g', 'ok', 'Rule 6(1)'], ['MRP', '₹180.00', 'ok', 'Rule 6(1)'], ['Packed on', 'Unclear', 'check', 'Low OCR confidence']], copy: { en: 'Assam tea, 250 grams, priced one hundred eighty rupees. Warning: the packing date is unclear, please check the pack.', hi: 'असम चाय, 250 ग्राम, कीमत एक सौ अस्सी रुपये। चेतावनी: पैकिंग की तारीख़ साफ़ नहीं है, कृपया पैक देखें।', ta: 'அசாம் தேநீர், 250 கிராம், விலை நூற்று எண்பது ரூபாய். எச்சரிக்கை: பேக்கிங் தேதி தெளிவாக இல்லை, தயவுசெய்து சரிபாருங்கள்.' } },
]
const labels = { en: 'EN', hi: 'हिं', ta: 'த' }
const profiles = [{ id: 'auto', title: 'Auto', sub: 'Cycles through demo products and languages' }, { id: 'low', title: 'Low vision', sub: 'Large text and high contrast' }, { id: 'elder', title: 'Elderly shopper', sub: 'Large text, slower reading' }, { id: 'hi', title: 'Hindi speaker', sub: 'Summary read in Hindi' }, { id: 'ta', title: 'Tamil speaker', sub: 'Summary read in Tamil' }]

function PackIcon({ kind }) { return <div className={`product-pack ${kind}`} aria-hidden="true"><span className="pack-mark">{kind === 'tea' ? '✦' : '●'}</span><span>{kind === 'tea' ? 'ASSAM TEA' : 'MASALA\nBISCUITS'}</span><b>{kind === 'tea' ? '250 g' : '200 g'}</b></div> }

export function ProductDemo() {
  const [profile, setProfile] = useState('auto')
  const [productIndex, setProductIndex] = useState(0)
  const [language, setLanguage] = useState('en')
  const [result, setResult] = useState(false)
  const [phoneLarge, setPhoneLarge] = useState(false)
  const [phoneContrast, setPhoneContrast] = useState(false)
  const [details, setDetails] = useState(false)
  const [speaking, setSpeaking] = useState(false)
  const product = products[productIndex]
  const isReduced = useMemo(() => matchMedia('(prefers-reduced-motion: reduce)').matches, [])

  useEffect(() => {
    const timing = [500, 1400, 2300, 3200]
    let timers = timing.map((time, i) => setTimeout(() => { if (i === 3) setResult(true) }, isReduced ? 0 : time))
    const rotate = setInterval(() => { if (profile === 'auto') { setResult(false); setProductIndex(value => (value + 1) % products.length); setLanguage(value => value === 'en' ? 'hi' : value === 'hi' ? 'ta' : 'en') } }, 9500)
    return () => { timers.forEach(clearTimeout); clearInterval(rotate) }
  }, [productIndex, profile, isReduced])
  const selectProfile = (id) => {
    setProfile(id); setPhoneLarge(id === 'low' || id === 'elder'); setPhoneContrast(id === 'low')
    if (id === 'hi' || id === 'ta') setLanguage(id)
  }
  const play = useCallback(() => {
    if (!('speechSynthesis' in window) || !('SpeechSynthesisUtterance' in window)) {
      window.dispatchEvent(new CustomEvent('complyvision:audio-state', { detail: { speaking: false, unavailable: true } }))
      return
    }
    if (speaking) {
      speechSynthesis.cancel()
      setSpeaking(false)
      window.dispatchEvent(new CustomEvent('complyvision:audio-state', { detail: { speaking: false } }))
      return
    }
    const utterance = new SpeechSynthesisUtterance(product.copy[language])
    utterance.lang = language === 'en' ? 'en-IN' : `${language}-IN`
    utterance.rate = profile === 'elder' ? 0.8 : 0.95
    utterance.onend = () => {
      setSpeaking(false)
      window.dispatchEvent(new CustomEvent('complyvision:audio-state', { detail: { speaking: false } }))
    }
    utterance.onerror = () => {
      setSpeaking(false)
      window.dispatchEvent(new CustomEvent('complyvision:audio-state', { detail: { speaking: false, unavailable: true } }))
    }
    speechSynthesis.cancel()
    speechSynthesis.speak(utterance)
    setSpeaking(true)
    window.dispatchEvent(new CustomEvent('complyvision:audio-state', { detail: { speaking: true } }))
  }, [language, product, profile, speaking])

  useEffect(() => {
    const hearSummary = () => { setResult(true); play() }
    window.addEventListener('complyvision:hear-audio-summary', hearSummary)
    return () => window.removeEventListener('complyvision:hear-audio-summary', hearSummary)
  }, [play])

  useEffect(() => () => {
    if ('speechSynthesis' in window) speechSynthesis.cancel()
  }, [])

  return <section className="demo-stage" id="demo" aria-label="Live demo">
    <div className="demo-copy">
      <span className="eyebrow"><i />Live demo</span>
      <h2>Point. Check. Listen.</h2>
      <p>Scan a pack and the phone checks each declaration against fixed rules, then reads the result aloud. Pick who is shopping to see it adapt.</p>
      <div className="profile-list" role="group" aria-label="See it work for">{profiles.map((item, index) => <button className={profile === item.id ? 'active' : ''} onClick={() => selectProfile(item.id)} type="button" key={item.id}><i>{index + 1}</i><span><strong>{item.title}</strong><small>{item.sub}</small></span></button>)}</div>
      <p className="demo-note">Demo products and values, not a real inspection.</p>
    </div>
    <div className="phone-area">
      <div className="phone" role="img" aria-label="Phone app demo: a product is scanned, checked and read aloud">
        <div className={`phone-screen ${phoneLarge ? 'phone-large' : ''} ${phoneContrast ? 'phone-contrast' : ''}`}>
          <div className="phone-bar"><span>ComplyVision</span><div><button onClick={() => setPhoneLarge(!phoneLarge)} aria-pressed={phoneLarge} type="button">A+</button><button onClick={() => setPhoneContrast(!phoneContrast)} aria-pressed={phoneContrast} type="button">◐</button></div></div>
          {!result ? <div className="scanner"><div className="scanner-line" /><p>Point at any packaged product</p><PackIcon kind={product.kind} /><div className="scan-frame" /><div className="scan-status">Checking label declarations</div></div> : <div className="result-view"><h3>{product.name}</h3><span className={`result-badge ${product.verdict}`}>{product.badge}</span><ul>{product.fields.map(([name, value, status, rule]) => <li key={name}><div><b>{name}</b> {value}{details && <small>{rule}</small>}</div><span className={`field-status ${status}`}>{status === 'ok' ? 'OK' : 'CHECK'}</span></li>)}</ul></div>}
          <div className={`audio-sheet ${speaking ? 'speaking' : ''}`}><div className="audio-controls"><button className="play-button" type="button" onClick={play}>{speaking ? '■' : '▶'}</button><div className="sound-wave">{Array.from({ length: 16 }, (_, i) => <i key={i} />)}</div><div className="language-buttons">{Object.entries(labels).map(([key, label]) => <button key={key} className={language === key ? 'active' : ''} onClick={() => setLanguage(key)} type="button">{label}</button>)}</div></div><p className={product.verdict === 'review' ? 'warning' : ''}>{product.copy[language]}</p>{result && <button className="details-button" onClick={() => setDetails(!details)} type="button">{details ? 'Hide rule details' : 'Show rule details'}</button>}</div>
        </div>
      </div>
    </div>
  </section>
}
