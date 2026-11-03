import { Header } from '../components/Header'
import { Hero } from '../components/Hero'
import { ProductDemo } from '../components/ProductDemo'
import { Workflow } from '../components/Workflow'
import { HeroDecorations } from '../components/HeroDecorations'
import { PortalFooter } from '../components/PortalFooter'

export function HomePage({ authenticated = false, user }) {
  return (
    <main className="site-shell">
      <a className="skip-link" href="#demo">Skip to inspection workspace</a>
      <div className="page-grid" aria-hidden="true" />
      <HeroDecorations />
      <div className="site-container">
        <Header authenticated={authenticated} user={user} />
        <Hero />
        <ul className="audience-list" aria-label="Designed for">
          <li>Low-vision shoppers</li>
          <li>Elderly consumers</li>
          <li>Language barriers</li>
          <li>Legal Metrology inspectors</li>
        </ul>
        <ProductDemo />
        <Workflow />
        <PortalFooter />
      </div>
    </main>
  )
}
