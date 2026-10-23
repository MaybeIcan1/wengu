// UI 截图工具：驱动本机 Edge，注入 token 后截真实页面（供设计验收）
// 用法：node scripts/shot.mjs <token> <输出目录>
import puppeteer from 'puppeteer-core'
import fs from 'node:fs'

const token = process.argv[2]
const outDir = process.argv[3] || 'shots'
const base = 'http://localhost:5173'
const edge = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'

if (!fs.existsSync(outDir)) fs.mkdirSync(outDir, { recursive: true })

const browser = await puppeteer.launch({
  executablePath: edge,
  headless: 'new',
  args: ['--no-sandbox', '--disable-gpu'],
})
const page = await browser.newPage()
await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 1 })

// 先落地一次，写 token（同源）
await page.goto(base + '/login', { waitUntil: 'networkidle2' })
await page.evaluate((t) => localStorage.setItem('wengu_token', t), token)

const shots = [
  ['list', '/question/list'],
  ['detail', null],   // 动态取第一条
]

await page.goto(base + shots[0][1], { waitUntil: 'networkidle2' })
await new Promise(r => setTimeout(r, 1200))
await page.screenshot({ path: `${outDir}/real-list.png` })

// 点第一行进详情
const firstRow = await page.$('.row')
if (firstRow) {
  await firstRow.click()
  await new Promise(r => setTimeout(r, 1200))
  await page.screenshot({ path: `${outDir}/real-detail.png` })
}

// 新建选择弹窗
await page.goto(base + '/question/list', { waitUntil: 'networkidle2' })
await new Promise(r => setTimeout(r, 800))
const btns = await page.$$('button')
for (const b of btns) {
  const text = await page.evaluate(el => el.textContent || '', b)
  if (text.includes('新建题目')) { await b.click(); break }
}
await new Promise(r => setTimeout(r, 700))
await page.screenshot({ path: `${outDir}/real-dialog.png` })

await browser.close()
console.log('done ->', outDir)
