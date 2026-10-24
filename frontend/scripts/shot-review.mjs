// 复习流程截图：准备页 → 选分类 → 卡片 → 翻面 → 回炉验证
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

await page.goto('http://localhost:5173/review', { waitUntil: 'networkidle2' })
await new Promise(r => setTimeout(r, 1000))
await page.screenshot({ path: `${out}/review-1-setup.png` })

// 选「复习测试」分类
const chips = await page.$$('.cat-chip')
for (const ch of chips) {
  const t = await page.evaluate(el => (el.textContent || '').trim(), ch)
  if (t.includes('复习测试')) { await ch.click(); break }
}
await new Promise(r => setTimeout(r, 300))

await page.click('.count-row .primary-btn')     // 开始复习
await new Promise(r => setTimeout(r, 1200))
await page.screenshot({ path: `${out}/review-2-card.png` })

await page.click('.self-check .primary-btn')    // 显示答案
await new Promise(r => setTimeout(r, 600))
await page.screenshot({ path: `${out}/review-3-answer.png` })

await page.click('.rate-btn.again')             // 第1张：忘记 → 回炉
await new Promise(r => setTimeout(r, 900))
for (let i = 0; i < 2; i++) {                   // 第2、3张：十拿九稳
  await page.click('.self-check .primary-btn')
  await new Promise(r => setTimeout(r, 300))
  await page.click('.rate-btn.good')
  await new Promise(r => setTimeout(r, 900))
}
// 回炉卡应重新出现（队尾）
await page.screenshot({ path: `${out}/review-4-requeue.png` })

const bodyText = await page.evaluate(() => document.body.innerText)
console.log('hasRequeueTag=' + bodyText.includes('回炉'))
console.log('progressText=' + (bodyText.match(/已掌握\s*\d+\s*\/\s*\d+/) || [''])[0])
await browser.close()
