<script setup lang="ts">
// 导入第一步：上传 docx + 订阅 SSE 解析进度；草稿就绪(2)后跳预览确认页
import { onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { UploadFile } from 'element-plus'
import { uploadDocx } from '../api/import'
import { useUserStore } from '../stores/user'

const router = useRouter()
const store = useUserStore()

const file = ref<File | null>(null)
const taskId = ref('')
const status = ref(0)
const total = ref(0)
const errorMsg = ref('')
const uploading = ref(false)
const forceReimport = ref(false)   // 忽略去重：解析规则升级后重新解析同一文件
let es: EventSource | null = null

const statusText = ['排队中…', '解析中…', '待确认（即将跳转预览）', '导入中…', '已完成', '已终止']

function onFileChange(f: UploadFile) {
  file.value = (f.raw ?? null) as File | null
}
function onExceed() {
  ElMessage.warning('一次一个文件')
}

async function start() {
  if (!file.value) { ElMessage.warning('先选一个 .docx 文件'); return }
  uploading.value = true
  try {
    taskId.value = (await uploadDocx(file.value, forceReimport.value)) as unknown as string
    status.value = 0
    subscribe(taskId.value)
  } finally { uploading.value = false }
}

function subscribe(id: string) {
  es?.close()
  es = new EventSource(`/api/import/task/${id}/progress?token=${store.token}`)
  es.addEventListener('progress', (ev: MessageEvent) => {
    const t = JSON.parse(ev.data)
    status.value = t.status
    total.value = t.totalCount ?? 0
    errorMsg.value = t.errorMsg ?? ''
    if (t.status === 2) {                 // 草稿就绪 → 去预览（人工关卡）
      es?.close(); es = null
      router.push(`/import/preview/${id}`)
    } else if (t.status === 4) {          // 幂等命中了已完成任务
      es?.close(); es = null
      ElMessage.info('这份文件之前已导入完成')
    } else if (t.status >= 5) {
      es?.close(); es = null
    }
  })
  es.onerror = () => { es?.close(); es = null }
}

onBeforeUnmount(() => { es?.close() })
</script>

<template>
  <el-card class="wrap" shadow="never">
    <h3 style="margin-top:0;font-weight:700">导入 docx 题库</h3>
    <p class="soft">
      支持 .docx（20MB 内）。解析完成后会先进入<b>预览确认</b>：改标题/分类、取消勾选不需要的条目，
      确认后才写入题库。识别不了的内容会收进「散记」条目，不会丢。
    </p>

    <el-upload drag :auto-upload="false" :limit="1" accept=".docx"
      :on-change="onFileChange" :on-exceed="onExceed">
      <div class="drop">把 docx 拖进来，或点击选择</div>
    </el-upload>

    <div class="ops">
      <el-button type="primary" :disabled="!file || uploading" :loading="uploading" @click="start">
        开始解析
      </el-button>
      <el-checkbox v-model="forceReimport" class="force">忽略去重（同一文件强制重新解析）</el-checkbox>
    </div>

    <div v-if="taskId" class="prog">
      <div class="line">{{ statusText[status] ?? '未知状态' }}
        <span v-if="total" class="soft">（已识别 {{ total }} 条）</span>
      </div>
      <el-progress :percentage="status >= 2 ? 100 : 40" :indeterminate="status <= 1"
        :status="status === 5 ? 'exception' : undefined" :stroke-width="10" />
      <el-alert v-if="status === 5" type="error" :closable="false" :title="errorMsg || '解析失败'" />
      <el-button v-if="status === 5" type="primary" plain @click="start">重试</el-button>
    </div>
  </el-card>
</template>

<style scoped>
.wrap { border-radius: 14px; border: 1px solid var(--line); max-width: 760px }
.soft { color: var(--ink-soft); font-size: 12px; line-height: 1.9 }
.drop { padding: 26px 0; color: var(--ink-soft) }
.ops { margin: 16px 0; display: flex; align-items: center; gap: 14px; flex-wrap: wrap }
.force { font-size: 12px; color: var(--ink-soft) }
.prog { display: flex; flex-direction: column; gap: 10px }
.line { font-weight: 600; color: var(--ink) }
</style>
