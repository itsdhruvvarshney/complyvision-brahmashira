const braille = {
  C: [1, 4], O: [1, 3, 5], M: [1, 3, 4], P: [1, 2, 3, 4], L: [1, 2, 3], Y: [1, 3, 4, 5, 6], V: [1, 2, 3, 6], I: [2, 4], S: [2, 3, 4], N: [1, 3, 4, 5],
}
const dots = { 1: [0, 0], 2: [0, 1], 3: [0, 2], 4: [1, 0], 5: [1, 1], 6: [1, 2] }

export function HeroDecorations() {
  const marks = Array.from({ length: 161 }, (_, index) => index * 10)
  return <div className="hero-decorations" aria-hidden="true">
    <svg className="hero-ruler" viewBox="0 0 1600 34">{marks.map(x => <path d={`M${x} 0v${x % 100 === 0 ? 14 : x % 50 === 0 ? 10 : 6}`} key={x} />)}{marks.filter(x => x > 0 && x < 1600 && x % 100 === 0).map(x => <text x={x + 4} y="27" key={`label-${x}`}>{x / 10 * 5}{x === 1500 ? ' g' : ''}</text>)}</svg>
    <svg className="hero-scale" viewBox="0 0 240 260"><path d="M70 246h100M120 246V50M104 246l16-22 16 22" /><circle cx="120" cy="38" r="9" /><path d="M26 62h188M40 62l-30 88M40 62l30 88M200 62l-30 88M200 62l30 88" /><path d="M6 150h68c0 18-16 30-34 30S6 168 6 150zM166 150h68c0 18-16 30-34 30s-34-12-34-30z" /><rect x="26" y="130" width="28" height="20" rx="3" /><path d="M34 140h12" /></svg>
    <svg className="hero-braille" viewBox="0 0 330 200">{['COMPLY', 'VISION'].flatMap((word, row) => word.split('').flatMap((letter, index) => braille[letter].map(number => { const [column, line] = dots[number]; return <circle cx={14 + index * 54 + column * 18} cy={14 + row * 94 + line * 18} r="6.5" key={`${row}-${index}-${number}`} /> })))}</svg>
  </div>
}
