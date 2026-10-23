<script setup lang="ts">
// 题目详情：编辑式阅读排版（阅读优先）
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
  <StateBox :loading="loading" :empty="!loading && !d"
    empty-text="题目不存在（可能已被删除或属于他人）" :rows="8">
    <div v-if="d" class="reader-wrap">
      <div class="reader">
        <div class="topline">
          <span class="kicker">问答卡片</span>
          <div class="sp" />
          <el-button link type="primary" @click="router.push(`/question/${d.id}/edit`)">编辑</el-button>
          <el-button link type="danger" @click="removeIt">删除</el-button>
        </div>

        <h1 class="title serif">{{ d.title }}</h1>

        <div class="meta">
          <span v-if="d.categoryName">{{ d.categoryName }}</span>
          <span v-for="tn in d.tagNames ?? []" :key="tn" class="tag">#{{ tn }}</span>
          <span>{{ typeName[d.qType ?? 0] }}</span>
          <span v-if="d.difficulty">{{ diffName[d.difficulty] }}</span>
          <span v-if="d.source">来源：{{ d.source }}</span>
          <span class="push">{{ (d.updatedTime ?? '').slice(0, 10) }}</span>
        </div>

        <div class="body">
          <MdPreview :modelValue="d.answerMd" previewTheme="github" code-theme="github" />
        </div>

        <div class="foot">
          <el-button text @click="router.push('/question/list')">← 返回列表</el-button>
        </div>
      </div>
    </div>
  </StateBox>
</template>

<style scoped>
.reader-wrap { display: flex; justify-content: center }
.reader {
  width: 100%; max-width: 900px; background: var(--paper);
  border: 1px solid var(--line); border-radius: var(--r-lg);
  padding: var(--sp-8) 44px var(--sp-6); box-shadow: var(--shadow-sm);
}
.topline { display: flex; align-items: center; gap: 8px }
.kicker { font-size: var(--fs-11); letter-spacing: 3px; color: var(--brand-deep) }
.sp { flex: 1 }
.title {
  margin: 12px 0 10px; font-size: var(--fs-26); font-weight: 600;
  line-height: 1.45; color: var(--ink); letter-spacing: .3px;
}
.meta {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  font-size: var(--fs-11); color: var(--ink-3);
  padding-bottom: var(--sp-4); border-bottom: 1px solid var(--line);
}
.tag { color: var(--brand-deep); opacity: .9 }
.push { margin-left: auto }
.body { margin-top: var(--sp-5) }

/* 阅读排版：把 Markdown 渲染的正文调到适合长读 */
.body :deep(.md-editor-preview) {
  font-size: 14.5px; line-height: 2; color: #3d362f; background: transparent;
}
.body :deep(.md-editor-preview p) { margin: 14px 0 }
.body :deep(.md-editor-preview h1),
.body :deep(.md-editor-preview h2),
.body :deep(.md-editor-preview h3) { color: var(--ink); font-weight: 650; margin: 22px 0 10px }
.body :deep(.md-editor-preview h2) { font-size: 18px }
.body :deep(.md-editor-preview h3) { font-size: 15.5px }
.body :deep(.md-editor-preview blockquote) {
  border-left: 2px solid var(--brand); background: var(--quote-bg);
  margin: 16px 0; padding: 8px 16px; color: var(--ink-2);
}
.body :deep(.md-editor-preview pre) {
  background: var(--code-bg); border: 1px solid var(--line); border-radius: var(--r-sm);
  padding: 14px 16px; line-height: 1.85; font-size: 12.5px;
}
.body :deep(.md-editor-preview code) { font-family: var(--mono); font-size: 12.5px }
.body :deep(.md-editor-preview table) { font-size: 13px }
.body :deep(.md-editor-preview th) { background: var(--code-bg) }
.body :deep(.md-editor-preview img) { border-radius: var(--r-sm); border: 1px solid var(--line) }
.body :deep(.md-editor-preview)::-webkit-scrollbar { display: none }

.foot { margin-top: var(--sp-6); border-top: 1px solid var(--line); padding-top: var(--sp-3) }

@media (max-width: 768px) {
  .reader { padding: var(--sp-5) var(--sp-4); border-radius: var(--r-md) }
  .title { font-size: var(--fs-20) }
}
</style>
