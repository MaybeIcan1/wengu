<script setup lang="ts">
// 复习页：阶段一选分类/数量 → 阶段二卡片流（百词斩式回炉）
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { MdPreview } from 'md-editor-v3'
import 'md-editor-v3/lib/preview.css'
import { startSession, rateCard, getRandom, checkin, checkinStatus, todayRank } from '../api/review'
import { listCategory } from '../api/category'

const router = useRouter()

const phase = ref<'setup' | 'review'>('setup')
const loading = ref(false)
const categories = ref<any[]>([])
const categoryId = ref<string | undefined>(undefined)
const count = ref(10)

const queue = ref<any[]>([])          // 动态队列：回炉的卡会追加到队尾
const idx = ref(0)
const total = ref(0)                  // 本轮唯一题数（不含回炉重复）
const mastered = ref<Record<string, boolean>>({})   // 本轮达到"十拿九稳/完全背诵"的 cardId
const attempts = ref<Record<string, number>>({})    // 每张卡本轮被评次数（防死循环）
const showAnswer = ref(false)
const rating = ref(false)

const status = ref<{ checkedToday: boolean; streak: number; monthCount: number }>({
  checkedToday: false, streak: 0, monthCount: 0,
})
const rank = ref<any[]>([])

const current = computed(() => queue.value[idx.value] ?? null)
const masteredCount = computed(() => Object.keys(mastered.value).length)
const progress = computed(() => total.value ? Math.round((masteredCount.value / total.value) * 100) : 0)

const ratingOptions = [
  { value: 1, label: '忘记', cls: 'again' },
  { value: 2, label: '有点印象', cls: 'hard' },
  { value: 3, label: '十拿九稳', cls: 'good' },
  { value: 4, label: '完全背诵', cls: 'easy' },
]

async function loadMeta() {
  const [cs, st, rk]: any[] = await Promise.all([listCategory(), checkinStatus(), todayRank(10)])
  categories.value = cs ?? []
  status.value = st
  rank.value = rk ?? []
}

onMounted(loadMeta)

async function start() {
  loading.value = true
  try {
    const cards: any = await startSession(categoryId.value, count.value)
    if (!cards?.length) { ElMessage.info('这个分类下还没有题目'); return }
    queue.value = [...cards]
    total.value = cards.length
    mastered.value = {}
    attempts.value = {}
    idx.value = 0
    showAnswer.value = false
    phase.value = 'review'
  } finally { loading.value = false }
}

async function submitRating(value: number) {
  if (!current.value?.cardId || rating.value) return
  rating.value = true
  const card = current.value
  try {
    await rateCard(card.cardId, value)
    const cid = card.cardId as string
    attempts.value[cid] = (attempts.value[cid] ?? 0) + 1

    // ★ 回炉：忘记/有点印象 → 排到队尾本轮再考；3/4 分或已考 3 次 → 出队
    if (value >= 3 || attempts.value[cid] >= 3) {
      mastered.value[cid] = true
    } else {
      queue.value.push({ ...card, requeued: true })
    }

    idx.value++
    showAnswer.value = false
    const [st, rk]: any[] = await Promise.all([checkinStatus(), todayRank(10)])
    status.value = st
    rank.value = rk ?? []
  } finally { rating.value = false }
}

async function doCheckin() {
  status.value = (await checkin()) as any
  ElMessage.success('签到成功')
}

async function randomOne() {
  const c: any = await getRandom()
  if (!c) { ElMessage.info('题库还是空的'); return }
  queue.value = [{ ...c, requeued: false }]
  total.value = 1
  mastered.value = {}
  attempts.value = {}
  idx.value = 0
  showAnswer.value = false
  phase.value = 'review'
}

function backToSetup() {
  phase.value = 'setup'
  queue.value = []
  total.value = 0
  mastered.value = {}
  attempts.value = {}
  idx.value = 0
}
</script>

<template>
  <div class="page-head">
    <div>
      <h1>复习</h1>
      <p class="sub">
        <span :class="{ on: status.checkedToday }">
          {{ status.checkedToday ? `已签到 · 连续 ${status.streak} 天` : '今天还没签到' }}
        </span>
      </p>
    </div>
    <div class="head-ops">
      <el-button v-if="!status.checkedToday" class="primary-btn" @click="doCheckin">签 到</el-button>
      <el-button @click="randomOne">随机问答</el-button>
    </div>
  </div>

  <div class="layout">
    <section class="review-main">
      <!-- 阶段一：准备 -->
      <el-card v-if="phase === 'setup'" class="card setup" shadow="never">
        <h2 class="setup-title">今天想复习哪一类？</h2>
        <p class="hint">开始后会把该分类下的题目纳入复习体系，并抽取 {{ count }} 道组成卡片。</p>

        <div class="cats">
          <button type="button" class="cat-chip" :class="{ on: !categoryId }" @click="categoryId = undefined">全部</button>
          <button v-for="c in categories" :key="c.id" type="button" class="cat-chip"
            :class="{ on: categoryId === String(c.id) }" @click="categoryId = String(c.id)">
            {{ c.name }}<span class="n">{{ c.questionCount }}</span>
          </button>
        </div>

        <div class="count-row">
          <span class="label">数量</span>
          <el-radio-group v-model="count" size="small">
            <el-radio-button :value="5">5</el-radio-button>
            <el-radio-button :value="10">10</el-radio-button>
            <el-radio-button :value="20">20</el-radio-button>
          </el-radio-group>
          <div class="grow" />
          <el-button class="primary-btn" size="large" :loading="loading" @click="start">开始复习</el-button>
        </div>
      </el-card>

      <!-- 阶段二：复习 -->
      <el-card v-else-if="current" class="card" shadow="never">
        <div class="kicker">
          问 答 卡 片 · 已掌握 {{ masteredCount }} / {{ total }}
          <span v-if="current.requeued" class="retry-tag">回炉</span>
        </div>
        <h2 class="title">{{ current.title }}</h2>

        <div v-if="!showAnswer" class="self-check">
          <p class="hint">先在脑子里过一遍答案，再翻面核对。</p>
          <el-button class="primary-btn" size="large" @click="showAnswer = true">显示答案</el-button>
        </div>

        <template v-else>
          <div class="answer">
            <MdPreview :modelValue="current.answerMd || '（空）'" previewTheme="github" code-theme="github" />
          </div>
          <div class="rate">
            <span class="rate-label">这次回忆得怎么样？</span>
            <div class="rate-btns">
              <button v-for="o in ratingOptions" :key="o.value" type="button"
                class="rate-btn" :class="o.cls" :disabled="rating"
                @click="submitRating(o.value)">{{ o.label }}</button>
            </div>
          </div>
        </template>
      </el-card>

      <!-- 阶段二：完成 -->
      <el-empty v-else description="这一轮复习完成">
        <div class="empty-btns">
          <el-button class="primary-btn" @click="start">再来一轮</el-button>
          <el-button @click="backToSetup">换个分类</el-button>
          <el-button @click="router.push('/question/list')">去题库</el-button>
        </div>
      </el-empty>

      <div v-if="phase === 'review' && total" class="progress-line">
        <el-progress :percentage="progress" :stroke-width="8" :show-text="false" />
        <span class="muted">{{ masteredCount }} / {{ total }}</span>
      </div>
    </section>

    <aside class="rank">
      <h3>今日复习榜</h3>
      <ol v-if="rank.length">
        <li v-for="r in rank" :key="r.userId">
          <span class="no">{{ r.rank }}</span>
          <span class="name">{{ r.nickname }}</span>
          <span class="score">{{ r.score }}</span>
        </li>
      </ol>
      <p v-else class="muted small">今天还没有人复习，你可以是第一个。</p>
    </aside>
  </div>
</template>

<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; margin-bottom: var(--sp-4) }
.page-head h1 { margin: 0; font-size: var(--fs-24); font-weight: 700; letter-spacing: .3px }
.page-head .sub { margin: 6px 0 0; font-size: var(--fs-12); color: var(--ink-3) }
.page-head .sub .on { color: var(--brand-deep) }
.head-ops { display: flex; gap: var(--sp-2) }
.primary-btn { background: var(--brand-grad); border: none; color: #fff; font-weight: 600; box-shadow: var(--shadow-brand) }

.layout { display: flex; gap: var(--sp-4); align-items: flex-start }
.review-main { flex: 1; min-width: 0 }
.card { border-radius: var(--r-lg); background: var(--card); padding: var(--sp-6) var(--sp-8) }
.setup-title { margin: 0 0 6px; font-size: var(--fs-20); font-weight: 700 }
.hint { color: var(--ink-3); font-size: var(--fs-13); margin: 0 }
.cats { display: flex; gap: 8px; flex-wrap: wrap; margin: var(--sp-4) 0 }
.cat-chip {
  border: 1px solid var(--line); background: var(--card); color: var(--ink-2);
  border-radius: var(--r-pill); padding: 7px 16px; font-size: var(--fs-13); cursor: pointer;
  font-family: inherit; transition: all .15s;
}
.cat-chip:hover { border-color: var(--brand); color: var(--brand-deep) }
.cat-chip.on { background: var(--brand-soft); border-color: var(--brand); color: var(--brand-deep); font-weight: 600 }
.cat-chip .n { margin-left: 6px; font-size: var(--fs-11); color: var(--ink-3) }
.count-row { display: flex; align-items: center; gap: 12px; border-top: 1px solid var(--line); padding-top: var(--sp-4) }
.count-row .label { font-size: var(--fs-12); color: var(--ink-3) }
.count-row .grow { flex: 1 }

.kicker { font-size: var(--fs-11); letter-spacing: 3px; color: var(--brand-deep) }
.retry-tag {
  margin-left: 8px; font-size: var(--fs-11); letter-spacing: 0;
  background: #fdf0e6; color: #b9770e; border-radius: 999px; padding: 1px 8px;
}
.title { margin: 10px 0 18px; font-size: var(--fs-20); font-weight: 700; line-height: 1.5 }
.self-check { padding: 26px 0 10px; display: flex; flex-direction: column; align-items: center; gap: 16px }
.answer { border-top: 1px solid var(--line); padding-top: var(--sp-4) }
.answer :deep(.md-editor-preview) { font-size: 14.5px; line-height: 2; background: transparent }
.answer :deep(.md-editor-preview pre) { background: var(--code-bg); border: 1px solid var(--line); border-radius: var(--r-sm) }
.rate { margin-top: var(--sp-5); border-top: 1px solid var(--line); padding-top: var(--sp-4) }
.rate-label { font-size: var(--fs-12); color: var(--ink-3) }
.rate-btns { display: flex; gap: var(--sp-2); margin-top: var(--sp-2); flex-wrap: wrap }
.rate-btn {
  border: 1px solid var(--line); background: var(--card); border-radius: var(--r-sm);
  padding: 9px 20px; font-size: var(--fs-13); cursor: pointer; font-family: inherit; transition: all .15s ease;
}
.rate-btn:hover { transform: translateY(-1px); box-shadow: var(--shadow-sm) }
.rate-btn.again { color: #c0392b; border-color: #f0d3ce }
.rate-btn.hard { color: #b9770e; border-color: #f2e2c8 }
.rate-btn.good { color: var(--brand-deep); border-color: #cfe3f5 }
.rate-btn.easy { color: #2e7d5b; border-color: #cfe8dc }
.progress-line { display: flex; align-items: center; gap: 12px; margin-top: var(--sp-4) }
.progress-line :deep(.el-progress) { flex: 1 }
.rank { width: 260px; flex-shrink: 0; background: var(--card); border: 1px solid var(--line); border-radius: var(--r-lg); padding: var(--sp-4); box-shadow: var(--shadow-sm) }
.rank h3 { margin: 0 0 10px; font-size: var(--fs-13); font-weight: 700 }
.rank ol { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px }
.rank li { display: flex; align-items: center; gap: 8px; font-size: var(--fs-12) }
.rank .no { width: 18px; color: var(--ink-3); font-variant-numeric: tabular-nums }
.rank .name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap }
.rank .score { color: var(--brand-deep); font-weight: 600 }
.small { font-size: var(--fs-12) }
.empty-btns { display: flex; gap: 10px; justify-content: center; flex-wrap: wrap }

@media (max-width: 900px) {
  .layout { flex-direction: column }
  .rank { width: 100% }
  .card { padding: var(--sp-4) }
  .count-row { flex-wrap: wrap }
}
</style>
