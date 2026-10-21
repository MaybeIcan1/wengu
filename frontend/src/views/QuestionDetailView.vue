<script setup lang="ts">
// 题目详情：三态包装 + 只读渲染
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MdPreview } from 'md-editor-v3'
import 'md-editor-v3/lib/preview.css'
import StateBox from '../components/StateBox.vue'
import { detailQuestion, deleteQuestion } from '../api/question'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const d = ref<any>(null)

const typeName = ['八股', '手撕', '主观题', '笔试题']
const diffName = ['未标', '易', '中', '难']
const diffType = [undefined, 'success', 'warning', 'danger'] as const

onMounted(async () => {
  try {
    d.value = await detailQuestion(route.params.id as string)
  } finally { loading.value = false }
})

function removeIt() {
  ElMessageBox.confirm('删除后列表不可见（软删可恢复），继续？', '删除', { type: 'warning' })
    .then(async () => {
      await deleteQuestion(d.value.id)
      ElMessage.success('已删除')
      router.push('/question/list')
    }).catch(() => {})
}
</script>

<template>
  <StateBox :loading="loading"
    :empty="!loading && !d" empty-text="题目不存在（可能已被删除或属于他人）" :rows="7">
    <el-card v-if="d" class="wrap" shadow="never">
      <div class="head">
        <h2>{{ d.title }}</h2>
        <div class="ops">
          <el-tag effect="plain" round>{{ typeName[d.qType ?? 0] }}</el-tag>
          <el-tag v-if="d.difficulty" effect="plain" round
            :type="diffType[d.difficulty] ?? 'info'">{{ diffName[d.difficulty] }}</el-tag>
          <el-button link type="primary" @click="router.push(`/question/${d.id}/edit`)">编辑</el-button>
          <el-button link type="danger" @click="removeIt">删除</el-button>
        </div>
      </div>
      <div class="meta">
        <span v-if="d.categoryName" class="soft">📂 {{ d.categoryName }}</span>
        <span v-for="tn in d.tagNames ?? []" :key="tn" class="soft">#{{ tn }}</span>
        <span v-if="d.source" class="soft">来源：{{ d.source }}</span>
      </div>
      <MdPreview :modelValue="d.answerMd" previewTheme="github" code-theme="github" />
      <div class="foot soft">最后更新：{{ (d.updatedTime ?? '').replace('T', ' ').slice(0, 16) }}</div>
    </el-card>
  </StateBox>
</template>

<style scoped>
.wrap { border-radius: 14px; border: 1px solid var(--line) }
.head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px }
.head h2 { margin: 0; color: var(--ink); font-size: 20px; line-height: 1.5 }
.ops { display: flex; gap: 6px; align-items: center; flex-wrap: nowrap }
.meta { display: flex; gap: 12px; flex-wrap: wrap; margin: 8px 0 18px }
.soft { color: var(--ink-soft); font-size: 12px }
.foot { margin-top: 24px }
</style>
