import logo from '../assets/complyvision-logo.png'

const highlights = [
  ['For shoppers', 'Understand key label declarations before you buy packaged products.'],
  ['For businesses', 'Review a label and catch missing or inconsistent details before inspection.'],
  ['For inspectors', 'See extracted evidence alongside clear, rule-based results.'],
]

export function Workflow() {
  return <section className="about-card" id="about-complyvision" aria-labelledby="about-title">
    <div className="about-intro">
      <img className="about-logo" src={logo} alt="ComplyVision logo: a blue shield with a check mark and green leaf" />
      <div>
        <p className="about-kicker">Meet ComplyVision</p>
        <h2 id="about-title">Clearer product labels. More confident decisions.</h2>
        <p className="about-copy">ComplyVision helps people understand packaged-goods labels. Add a label image to extract important details and review them against configured compliance rules, with results explained in plain language.</p>
      </div>
    </div>
    <div className="about-highlights">
      {highlights.map(([title, description]) => <article className="about-highlight" key={title}>
        <span aria-hidden="true">✓</span>
        <div><h3>{title}</h3><p>{description}</p></div>
      </article>)}
    </div>
    <div className="about-actions">
      <a className="primary-button" href="#/workspace">Try a demo inspection <span>→</span></a>
      <a className="secondary-button" href="#/dashboard">Explore the dashboard</a>
      <p className="demo-note">Preview mode uses sample results. It does not provide legal advice or official certification.</p>
    </div>
  </section>
}
