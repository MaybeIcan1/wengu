// 悬停态检查：截图 + 打印按钮 computed style（确认没有高亮框）
import puppeteer from 'puppeteer-core'
const token = process.argv[2]
const out = process.argv[3] || 'shots'
const browser = await puppeteer.launch({
  executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  headless: 'new', args: ['--no-sandbox', '--disable-gpu'],
})
const page = await browser.newPage()
await page.setViewport({ width: 1440, height: 900 })
await page.goto('http://localhost:5173/login', { waitUntil: 'networkidle2' })
await page.evaluate(t => localStorage.setItem('wengu_token', t), token)
await page.goto('http://localhost:5173/question/list', { waitUntil: 'networkidle2' })
await new Promise(r => setTimeout(r, 1000))
const btn = await page.$('.link-op')
if (btn) {
  await btn.hover()
  await new Promise(r => setTimeout(r, 400))
  const style = await page.evaluate(() => {
    const b = document.querySelector('.link-op')
    const s = getComputedStyle(b)
    return {
      background: s.backgroundColor, boxShadow: s.boxShadow,
      outline: s.outlineStyle + ' ' + s.outlineWidth, border: s.border,
      color: s.color, fontWeight: s.fontWeight,
    }
  })
  console.log(JSON.stringify(style, null, 2))
  const box = await btn.boundingBox()
  await page.screenshot({
    path: `${out}/hover-check.png`,
    clip: { x: Math.max(0, box.x - 160), y: Math.max(0, box.y - 40), width: 320, height: 90 },
  })
}
await browser.close()
