import { Header } from '../components/Header'
import { Hero } from '../components/Hero'
import { ProductDemo } from '../components/ProductDemo'
import { Workflow } from '../components/Workflow'
import { HeroDecorations } from '../components/HeroDecorations'

export function HomePage() {
  return (
    <main className="site-shell">
      <a className="skip-link" href="#demo">Skip to inspection workspace</a>
      <div className="page-grid" aria-hidden="true" />
      <HeroDecorations />
      <div className="site-container">
        <Header />
        <Hero />
        <ul className="audience-list" aria-label="Designed for">
          <li>Low-vision shoppers</li>
          <li>Elderly consumers</li>
          <li>Language barriers</li>
          <li>Legal Metrology inspectors</li>
        </ul>
        <ProductDemo />
        <Workflow />
        <footer className="site-footer">
          <span>© ComplyVision</span>
          <span>Demo data shown for preview only. No real inspection results.</span>
        </footer>
      </div>
    </main>
  )
}
