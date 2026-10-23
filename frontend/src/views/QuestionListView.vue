<script setup lang="ts">
// 题目列表：筛选/防抖/URL状态同步/三态/编辑式行列表
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StateBox from '../components/StateBox.vue'
import CreateChoiceDialog from '../components/CreateChoiceDialog.vue'
import { pageQuestion, deleteQuestion } from '../api/question'
import { listTag } from '../api/tag'
import { useDebounceFn } from '@vueuse/core'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const tags = ref<any[]>([])
const showCreate = ref(false)

const typeName = ['八股', '手撕', '主观题', '笔试题']
const diffName = ['未标', '易', '中', '难']

// ---- 数据流层：条件与URL双向绑定 ----
const keyword = ref((route.query.k as string) ?? '')
const categoryId = computed(() =>
  route.query.category ? (route.query.category as string) : undefined
)
const tagIds = ref<number[]>(
  (route.query.tags as string | undefined)?.split(',').filter(Boolean).map(Number) ?? []
)
const qType = ref<number | undefined>(
  route.query.type !== undefined ? Number(route.query.type) : undefined
)
const page = computed(() => Math.max(1, Number(route.query.page ?? 1)))

const hasFilter = computed(() =>
  !!((keyword.value ?? '').trim() || tagIds.value.length || qType.value !== undefined || categoryId.value)
)
const emptyText = computed(() =>
  hasFilter.value ? '没有符合条件的题目' : '题库还是空的，先记一道吧'
)

const debouncedSearch = useDebounceFn(() => applyQuery(), 300)
watch(keyword, debouncedSearch)

async function load() {
  loading.value = true
  try {
    const pr: any = await pageQuestion({
      pageNum: page.value,
      pageSize: 10,
      keyword: keyword.value || undefined,
      categoryId: categoryId.value,
      tagIds: tagIds.value.length ? tagIds.value : undefined,
      qType: qType.value,
    })
    rows.value = pr.list
    total.value = pr.total
  } finally { loading.value = false }
}

function applyQuery() {
  router.replace({
    query: {
      ...route.query,
      k: keyword.value || undefined,
      tags: tagIds.value.length ? tagIds.value.join(',') : undefined,
      type: qType.value !== undefined ? String(qType.value) : undefined,
      page: page.value > 1 ? String(page.value) : undefined,
    },
  })
  load()
}

watch(categoryId, () => pageNumTo(1))

function pageNumTo(n: number) {
  router.replace({ query: { ...route.query, page: n > 1 ? String(n) : undefined } })
}

function clearFilters() {
  keyword.value = ''
  tagIds.value = []
  qType.value = undefined
  router.replace({ query: {} })
  load()
}

onMounted(async () => {
  load()
  tags.value = (await listTag()) as any[]
})

function goDetail(id: string) { router.push(`/question/${id}`) }
function goEdit(id: string) { router.push(`/question/${id}/edit`) }

function remove(id: string) {
  ElMessageBox.confirm('删除后列表不可见（逻辑删除可恢复），继续？', '删除', { type: 'warning' })
    .then(async () => {
      await deleteQuestion(id)
      ElMessage.success('已删除')
      load()
    }).catch(() => {})
}
</script>

<template>
  <div class="page-head">
    <div>
      <h1>题目列表</h1>
      <p class="sub">{{ total }} 道题<span v-if="hasFilter"> · 已筛选</span></p>
    </div>
    <el-button class="primary-btn" @click="showCreate = true">＋ 新建题目</el-button>
  </div>

  <div class="bar">
    <el-input v-model="keyword" placeholder="搜索标题关键词…" clearable class="search"
      @keyup.enter="applyQuery" @clear="applyQuery" />
    <el-select v-model="tagIds" multiple collapse-tags placeholder="按标签筛" class="sel"
      @change="applyQuery">
      <el-option v-for="t in tags" :key="t.id" :label="t.name" :value="Number(t.id)" />
    </el-select>
    <el-select v-model="qType" clearable placeholder="题型" class="sel-sm" @change="applyQuery">
      <el-option v-for="(n, i) in typeName" :key="i" :label="n" :value="i" />
    </el-select>
  </div>

  <StateBox :loading="loading" :empty="!rows.length" :empty-text="emptyText" :rows="8">
    <template #empty>
      <div v-if="!hasFilter" class="empty-actions">
        <p class="empty-hint">把平时遇到的八股、笔试题收进来，之后就能按分类翻、按标签筛。</p>
        <div class="empty-btns">
          <el-button class="primary-btn" size="large" @click="showCreate = true">自己 DIY</el-button>
          <el-button size="large" @click="router.push('/import')">一键导入 docx</el-button>
        </div>
      </div>
      <div v-else class="empty-actions">
        <el-button size="large" @click="clearFilters">清除筛选条件</el-button>
      </div>
    </template>

    <div class="rows">
      <div v-for="r in rows" :key="r.id" class="row" @click="goDetail(r.id)">
        <div class="main-col">
          <div class="t">{{ r.title }}</div>
          <div class="meta">
            <span class="tag" v-for="tn in r.tagNames ?? []" :key="tn">#{{ tn }}</span>
            <span class="dot" v-if="r.tagNames?.length"></span>
            <span>{{ r.source || '未记来源' }}</span>
            <span class="dot"></span>
            <span>{{ typeName[r.qType ?? 0] }}</span>
            <span class="dot" v-if="r.difficulty"></span>
            <span v-if="r.difficulty">{{ diffName[r.difficulty] }}</span>
          </div>
        </div>
        <div class="right-col">
          <span class="date">{{ (r.updatedTime ?? '').slice(0, 10) }}</span>
          <span class="ops" @click.stop>
            <button class="link-op" type="button" @click="goEdit(r.id)">编辑</button>
            <button class="link-op danger" type="button" @click="remove(r.id)">删除</button>
          </span>
        </div>
      </div>
    </div>
  </StateBox>

  <div class="pager">
    <el-pagination background layout="prev, pager, next, total" :total="total"
      :current-page="page" :page-size="10" @current-change="pageNumTo" />
  </div>

  <CreateChoiceDialog v-model="showCreate" />
</template>

<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; margin-bottom: var(--sp-4) }
.page-head h1 { margin: 0; font-size: var(--fs-24); font-weight: 700; color: var(--ink); letter-spacing: .3px }
.page-head .sub { margin: 6px 0 0; font-size: var(--fs-12); color: var(--ink-3) }
.primary-btn {
  background: var(--brand-grad); border: none; color: #fff; font-weight: 600;
  box-shadow: 0 8px 18px rgba(59, 130, 196, .26); height: 38px; padding: 0 18px;
}
.primary-btn:hover, .primary-btn:focus { background: var(--brand-grad); filter: brightness(1.03) }

.bar { display: flex; gap: var(--sp-2); align-items: center; margin-bottom: var(--sp-4); flex-wrap: wrap }
.search { width: 260px }
.sel { width: 190px }
.sel-sm { width: 120px }

.rows {
  background: var(--card); border: 1px solid var(--line); border-radius: var(--r-lg);
  overflow: hidden; box-shadow: var(--shadow-md);
}
.row {
  display: flex; align-items: center; gap: var(--sp-4);
  padding: 15px 20px; border-bottom: 1px solid var(--line-soft);
  cursor: pointer; transition: background .15s;
}
.row:last-child { border-bottom: none }
.row:hover { background: var(--hover) }
.main-col { flex: 1; min-width: 0 }
.t {
  font-size: var(--fs-15); font-weight: 600; color: var(--ink); line-height: 1.55;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.meta { display: flex; align-items: center; gap: 8px; margin-top: 6px; font-size: var(--fs-11); color: var(--ink-3) }
.tag { color: var(--brand-deep); opacity: .85 }
.dot { width: 3px; height: 3px; border-radius: 50%; background: var(--ink-3); opacity: .5 }
.right-col { display: flex; align-items: center; gap: var(--sp-4); flex-shrink: 0 }
.date { font-size: var(--fs-11); color: var(--ink-3); font-variant-numeric: tabular-nums }
/* 操作区：保持 Element 原生蓝/红，不做悬停变色与字重变化（避免浮夸） */
.ops { display: flex; gap: 2px }
.ops :deep(.el-button) {
  font-size: var(--fs-12); font-weight: 400;
  padding: 2px 4px; height: auto;
}
.pager { display: flex; justify-content: center; margin-top: var(--sp-5) }

.empty-actions { display: flex; flex-direction: column; align-items: center; gap: 4px }
.empty-hint { max-width: 380px; color: var(--ink-3); font-size: var(--fs-12); line-height: 1.9; margin: 0 0 8px }
.empty-btns { display: flex; gap: 12px; flex-wrap: wrap; justify-content: center }

@media (max-width: 768px) {
  .search { width: 100% }
  .sel, .sel-sm { width: calc(50% - 4px) }
  .row { padding: 14px 14px; align-items: flex-start }
  .right-col { flex-direction: column; align-items: flex-end; gap: 4px }
  .t { white-space: normal; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical }
}
</style>
