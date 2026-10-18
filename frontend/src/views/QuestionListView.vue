<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pageQuestion, deleteQuestion } from '../api/question'
import { listTag } from '../api/tag'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const tagIds = ref<number[]>([])
const qType = ref<number | undefined>(undefined)
const tags = ref<any[]>([])

const typeName = ['八股', '手撕', '主观题', '笔试题']
const diffName = ['未标', '易', '中', '难']

// 💣 id/分类 同理保持字符串，Spring 的参数绑定会自动把查询串转 Long，前端不碰数字
const categoryId = computed(() =>
  route.query.category ? (route.query.category as string) : undefined
)

async function load() {
  loading.value = true
  try {
    const pr: any = await pageQuestion({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      keyword: keyword.value || undefined,
      categoryId: categoryId.value,
      tagIds: tagIds.value.length ? tagIds.value : undefined,
      qType: qType.value,
    })
    rows.value = pr.list
    total.value = pr.total
  } finally {
    loading.value = false
  }
}

function search() { pageNum.value = 1; load() }

watch(categoryId, () => search())

onMounted(async () => {
  load()
  tags.value = (await listTag()) as any[]
})

function goDetail(id: number) { router.push(`/question/${id}`) }
function goEdit(id: number) { router.push(`/question/${id}/edit`) }

function remove(id: number) {
  ElMessageBox.confirm('删除后可在数据库找回（逻辑删除），继续？', '删除', { type: 'warning' })
    .then(async () => {
      await deleteQuestion(id)
      ElMessage.success('已删除')
      load()
    })
}
</script>

<template>
  <div class="bar">
    <el-input v-model="keyword" placeholder="搜标题关键词…" clearable style="width: 220px"
      @keyup.enter="search" @clear="search" />
    <el-select v-model="tagIds" multiple collapse-tags placeholder="按标签筛" style="width: 200px"
      @change="search">
      <el-option v-for="t in tags" :key="t.id" :label="t.name" :value="t.id" />
    </el-select>
    <el-select v-model="qType" clearable placeholder="题型" style="width: 120px" @change="search">
      <el-option v-for="(n, i) in typeName" :key="i" :label="n" :value="i" />
    </el-select>
    <el-button type="primary" @click="search">查 询</el-button>
    <div class="flex-sp" />
    <el-button type="warning" @click="router.push('/question/new')">+ 新建题目</el-button>
  </div>

  <div v-if="loading" class="grid"><el-skeleton v-for="i in 6" :key="i" :rows="3" animated /></div>
  <el-empty v-else-if="!rows.length" description="还没有题目——去把你最近的八股记进来吧" />

  <div v-else class="grid">
    <el-card v-for="r in rows" :key="r.id" shadow="hover" class="card"
      @click="goDetail(r.id)">
      <div class="t">{{ r.title }}</div>
      <div class="meta">
        <el-tag size="small" effect="plain" round>{{ typeName[r.qType ?? 0] }}</el-tag>
        <el-tag v-if="r.difficulty" size="small" :type="r.difficulty === 1 ? 'success' : r.difficulty === 2 ? 'warning' : 'danger'"
          effect="plain" round>{{ diffName[r.difficulty] }}</el-tag>
        <span class="soft" v-for="tn in r.tagNames ?? []" :key="tn">#{{ tn }}</span>
      </div>
      <div class="foot">
        <span class="soft">{{ r.source || '未记来源' }} · {{ (r.updatedTime ?? '').slice(0, 10) }}</span>
        <span>
          <el-button link type="primary" @click.stop="goEdit(r.id)">编辑</el-button>
          <el-button link type="danger" @click.stop="remove(r.id)">删除</el-button>
        </span>
      </div>
    </el-card>
  </div>

  <div class="pager">
    <el-pagination v-model:current-page="pageNum" :page-size="pageSize" :total="total"
      layout="prev, pager, next, total" @current-change="load" />
  </div>
</template>

<style scoped>
.bar { display: flex; gap: 10px; align-items: center; margin-bottom: 16px; flex-wrap: wrap }
.flex-sp { flex: 1 }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 12px }
.card { cursor: pointer; border-radius: 12px }
.t { font-weight: 600; color: #5d4634; line-height: 1.5; margin-bottom: 10px }
.meta { display: flex; gap: 6px; flex-wrap: wrap; align-items: center }
.soft { color: #b09a82; font-size: 12px }
.foot { display: flex; justify-content: space-between; align-items: center; margin-top: 12px }
.pager { display: flex; justify-content: center; margin-top: 18px }
</style>
