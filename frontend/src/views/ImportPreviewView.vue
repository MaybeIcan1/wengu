<script setup lang="ts">
// 导入第二步：人工关卡——改标题/分类、勾选、纠正标题语义、确认转正/放弃
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MdPreview } from 'md-editor-v3'
import 'md-editor-v3/lib/preview.css'
import { getDrafts, getTask, reparse, updateDraft, confirmImport, discardImport } from '../api/import'
import { listCategory } from '../api/category'

const route = useRoute()
const router = useRouter()
const taskId = route.params.taskId as string

const loading = ref(true)
const drafts = ref<any[]>([])
const categories = ref<any[]>([])
const submitting = ref(false)
const reparsing = ref(false)
/** 当前生效的标题语义（后端自动检测，可一键纠正） */
const mode = ref<'CATEGORY' | 'QUESTION'>('CATEGORY')

const ruleName: Record<string, string> = {
  STRONG: '强识别', WEAK: '弱识别', SCATTER: '散记',
  LLM_HIGH: 'AI高置信', LLM_LOW: 'AI待复核',
}
const ruleType: Record<string, string> = {
  STRONG: 'success', WEAK: 'warning', SCATTER: 'info',
  LLM_HIGH: 'success', LLM_LOW: 'warning',
}
const selectedCount = computed(() => drafts.value.filter(d => d.selected).length)

async function loadDrafts() {
  drafts.value = (await getDrafts(taskId)) as any[]
}

onMounted(async () => {
  try {
    const [t, ds, cs]: any[] = await Promise.all([
      getTask(taskId), getDrafts(taskId), listCategory(),
    ])
    mode.value = t?.headingMode === 'QUESTION' ? 'QUESTION' : 'CATEGORY'
    drafts.value = ds
    categories.value = cs
  } finally { loading.value = false }
})

async function saveDraft(d: any) {
  await updateDraft(d.id, {
    title: d.title, categoryName: d.categoryName, source: d.source,
    selected: d.selected, qType: d.qType,
  })
}

/** 纠正标题语义 → 后端重新分段 → 刷新草稿（会覆盖已编辑内容，二次确认） */
async function switchMode(m: 'CATEGORY' | 'QUESTION') {
  if (m === mode.value) return
  try {
    await ElMessageBox.confirm(
      '将按新的标题语义重新拆分，当前已修改的草稿会被重置，继续？', '重新解析', { type: 'warning' })
  } catch {
    mode.value = mode.value === 'QUESTION' ? 'CATEGORY' : 'QUESTION'   // 取消则回滚选中态
    return
  }
  reparsing.value = true
  try {
    const n: any = await reparse(taskId, m)
    mode.value = m
    await loadDrafts()
    ElMessage.success(`已重新拆分，共 ${n} 条`)
  } finally { reparsing.value = false }
}

async function confirmAll() {
  if (!selectedCount.value) { ElMessage.warning('至少勾选一条'); return }
  try {
    await ElMessageBox.confirm(`确认将 ${selectedCount.value} 条草稿导入题库？`, '确认导入', { type: 'info' })
  } catch { return }
  submitting.value = true
  try {
    for (const d of drafts.value) await saveDraft(d)     // 勾选态兜底落库
    const r: any = await confirmImport(taskId)
    ElMessage.success(`导入完成：成功 ${r.success}，失败 ${r.fail}`)
    router.push('/question/list')
  } finally { submitting.value = false }
}

async function discard() {
  try {
    await ElMessageBox.confirm('放弃后草稿将清空（题库不受影响），继续？', '放弃导入', { type: 'warning' })
  } catch { return }
  await discardImport(taskId)
  ElMessage.info('已放弃')
  router.push('/question/list')
}
</script>

<template>
  <div class="head">
    <div>
      <h3 style="margin:0">导入预览确认</h3>
      <p class="soft">
        共 {{ drafts.length }} 条，已勾选 {{ selectedCount }} 条。改动自动保存。
        强识别=规则明确；弱识别=裸标题推断，重点复核；散记=无法归属的原文兜底。
      </p>
    </div>
    <div class="ops">
      <el-button size="small" @click="drafts.forEach(d => d.selected = 1)">全选</el-button>
      <el-button size="small" @click="drafts.forEach(d => d.selected = 0)">全不选</el-button>
      <el-button type="danger" plain size="small" @click="discard">放弃导入</el-button>
      <el-button type="primary" :loading="submitting" @click="confirmAll">
        确认导入（{{ selectedCount }}）
      </el-button>
    </div>
  </div>

  <el-skeleton v-if="loading" :rows="8" animated />

  <template v-else>
    <div class="mode-bar">
      <span class="soft">标题语义（自动判断，可纠正）：</span>
      <el-radio-group v-model="mode" size="small" :disabled="reparsing" @change="switchMode">
        <el-radio-button value="CATEGORY">标题是分类</el-radio-button>
        <el-radio-button value="QUESTION">标题即题目</el-radio-button>
      </el-radio-group>
      <span class="soft">切换会重新拆分并覆盖当前草稿</span>
    </div>

    <el-empty v-if="!drafts.length" description="没有识别出内容——可以放弃导入或切换标题语义重试" />

    <div v-else class="list">
      <el-card v-for="d in drafts" :key="d.id" class="item" shadow="never"
        :class="{ skipped: !d.selected }">
        <div class="row">
          <el-checkbox v-model="d.selected" :true-value="1" :false-value="0" @change="saveDraft(d)" />
          <el-input v-model="d.title" class="title" @blur="saveDraft(d)" />
          <el-tag :type="(ruleType[d.ruleMatched] as any) ?? 'info'" effect="plain" size="small">
            {{ ruleName[d.ruleMatched] ?? d.ruleMatched }}
          </el-tag>
          <el-select v-model="d.categoryName" filterable allow-create clearable
            placeholder="分类" style="width: 150px" @change="saveDraft(d)">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.name" />
          </el-select>
          <el-select v-model="d.qType" style="width: 110px" @change="saveDraft(d)">
            <el-option v-for="(n, i) in ['八股', '手撕', '主观题', '笔试题']" :key="i" :label="n" :value="i" />
          </el-select>
        </div>
        <el-collapse>
          <el-collapse-item title="查看答案内容">
            <MdPreview :modelValue="d.answerMd || '（空）'" previewTheme="github" code-theme="github" />
          </el-collapse-item>
        </el-collapse>
      </el-card>
    </div>
  </template>
</template>

<style scoped>
.head { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; margin-bottom: 14px }
.soft { color: var(--ink-soft); font-size: 12px; line-height: 1.9; margin: 6px 0 0 }
.ops { display: flex; gap: 8px; flex-shrink: 0; flex-wrap: wrap; justify-content: flex-end }
.mode-bar { display: flex; align-items: center; gap: 10px; margin: 0 0 12px; flex-wrap: wrap }
.list { display: flex; flex-direction: column; gap: 10px }
.item { border-radius: 12px; border: 1px solid var(--line) }
.item.skipped { opacity: .5 }
.row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap }
.title { flex: 1; min-width: 240px }
</style>
