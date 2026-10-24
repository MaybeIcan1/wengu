// 个人设置与顶栏文案截图
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

// 顶栏局部
await page.screenshot({
  path: `${out}/profile-header.png`,
  clip: { x: 236, y: 0, width: 1204, height: 60 },
})

// 打开个人设置
await page.click('.avatar')
await new Promise(r => setTimeout(r, 800))
await page.screenshot({ path: `${out}/profile-dialog.png` })

const text = await page.evaluate(() => document.body.innerText)
console.log('greetingOk=' + text.includes('祝你「温故」而知新'))
await browser.close()
