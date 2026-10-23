<script setup lang="ts">
// 题目列表：筛选/防抖/URL状态同步/三态/卡片网格
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
const showCreate = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const tags = ref<any[]>([])

const typeName = ['八股', '手撕', '主观题', '笔试题']
const diffName = ['未标', '易', '中', '难']
const diffType = [undefined, 'success', 'warning', 'danger'] as const

// ---- 数据流层：条件与URL双向绑定（初始从URL还原；变化时replace回写） ----
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

// 防抖：输入停 300ms 自动搜；Enter 立即搜
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

/** 条件变化 → 静默写回URL（replace不污染历史栈）→ 触发load */
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

watch(categoryId, () => { pageNumTo(1) })

function pageNumTo(n: number) {
  router.replace({ query: { ...route.query, page: n > 1 ? String(n) : undefined } })
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
  <div class="bar">
    <el-input v-model="keyword" placeholder="搜标题关键词…" clearable style="width: 220px"
      @keyup.enter="applyQuery(); " @clear="applyQuery" />
    <el-select v-model="tagIds" multiple collapse-tags placeholder="按标签筛" style="width: 190px"
      @change="applyQuery">
      <el-option v-for="t in tags" :key="t.id" :label="t.name" :value="Number(t.id)" />
    </el-select>
    <el-select v-model="qType" clearable placeholder="题型" style="width: 120px" @change="applyQuery">
      <el-option v-for="(n, i) in typeName" :key="i" :label="n" :value="i" />
    </el-select>
    <div class="flex-sp" />
    <el-button type="warning" class="new-btn" @click="showCreate = true">+ 新建题目</el-button>
  </div>

  <StateBox :loading="loading" :empty="!rows.length"
    empty-text="还没有题目——去把你最近的八股记进来吧" :rows="8">
    <div class="grid">
      <el-card v-for="r in rows" :key="r.id" shadow="never" class="card" @click="goDetail(r.id)">
        <div class="t">{{ r.title }}</div>
        <div class="meta">
          <el-tag size="small" effect="plain" round>{{ typeName[r.qType ?? 0] }}</el-tag>
          <el-tag v-if="r.difficulty" size="small" effect="plain" round
            :type="diffType[r.difficulty] ?? 'info'">{{ diffName[r.difficulty] }}</el-tag>
          <span class="soft" v-for="tn in r.tagNames ?? []" :key="tn">#{{ tn }}</span>
        </div>
        <div class="foot">
          <span class="soft">{{ r.source || '未记来源' }} · {{ (r.updatedTime ?? '').slice(0, 10) }}</span>
          <span class="ops" @click.stop>
            <el-button link type="primary" @click="goEdit(r.id)">编辑</el-button>
            <el-button link type="danger" @click="remove(r.id)">删除</el-button>
          </span>
        </div>
      </el-card>
    </div>
  </StateBox>

  <div class="pager">
    <el-pagination background layout="prev, pager, next, total" :total="total"
      :current-page="page" :page-size="10" @current-change="pageNumTo" />
  </div>

  <CreateChoiceDialog v-model="showCreate" />
</template>

<style scoped>
.bar { display: flex; gap: 10px; align-items: center; margin-bottom: 16px; flex-wrap: wrap }
.flex-sp { flex: 1 }
.new-btn { background: var(--brand); border: none }
.new-btn:hover, .new-btn:focus { background: var(--brand-deep); border: none }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 14px }
.card {
  cursor: pointer; border-radius: 14px; transition: all .18s ease;
  border: 1px solid var(--line);
}
.card:hover { transform: translateY(-3px); box-shadow: var(--shadow-hover) }
.t { font-weight: 600; color: var(--ink); line-height: 1.55; margin-bottom: 10px }
.meta { display: flex; gap: 6px; flex-wrap: wrap; align-items: center }
.soft { color: var(--ink-soft); font-size: 12px }
.foot { display: flex; justify-content: space-between; align-items: center; margin-top: 12px }
.ops :deep(.el-button + .el-button) { margin-left: 2px }
.pager { display: flex; justify-content: center; margin-top: 20px }
</style>
