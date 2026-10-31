import logo from '../assets/complyvision-logo.png'

const footerGroups = [
  {
    title: 'Explore',
    links: [
      ['Dashboard', '#/dashboard'],
      ['Inspection workspace', '#/workspace'],
      ['Rule management', '#/rules'],
      ['Reports and history', '#/reports'],
    ],
  },
  {
    title: 'Tools and access',
    links: [
      ['Accessibility insights', '#/insights'],
      ['Settings', '#/settings'],
      ['Sign in', '#/login'],
      ['About ComplyVision', '#about-complyvision'],
    ],
  },
]

export function PortalFooter() {
  return <footer className="portal-footer" aria-label="ComplyVision site information">
    <div className="portal-footer-main">
      <div className="portal-footer-brand">
        <a href="#/" className="portal-footer-logo"><img src={logo} alt="" />ComplyVision</a>
        <p>Helping people understand packaged-product labels with accessible, explainable compliance information.</p>
      </div>
      {footerGroups.map((group) => <nav className="portal-footer-group" aria-label={group.title} key={group.title}>
        <h2>{group.title}</h2>
        {group.links.map(([label, href]) => <a href={href} key={label}>{label}</a>)}
      </nav>)}
      <div className="portal-footer-group portal-footer-note">
        <h2>About this portal</h2>
        <p>ComplyVision is an independent information and inspection-support demo. It is not a government agency and does not issue official certification or legal advice.</p>
        <p>Sample data is shown for preview. Do not treat demo results as an official decision.</p>
      </div>
    </div>
    <div className="portal-footer-bottom">
      <span>© {new Date().getFullYear()} ComplyVision</span>
      <a href="#about-complyvision">About the service</a>
      <span>Accessible label information · Explainable rule checks</span>
    </div>
  </footer>
}
