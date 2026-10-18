<script setup lang="ts">
// 题目编辑/新建二合一页：有 :id 即编辑模式
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { MdEditor } from 'md-editor-v3'
import 'md-editor-v3/lib/style.css'
import { detailQuestion, createQuestion, updateQuestion } from '../api/question'
import { listCategory } from '../api/category'
import { listTag } from '../api/tag'
import { useUserStore } from '../stores/user'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

// 💣 id 永远保持字符串（雪花ID 超 JS 安全整数，Number(…) 即精度丢失，编辑页404元凶）
const editId = route.params.id ? (route.params.id as string) : undefined
const categories = ref<any[]>([])
const tags = ref<any[]>([])
const loading = ref(false)

const form = reactive({
  title: '',
  answerMd: '',
  categoryId: undefined as number | undefined,
  tagNames: [] as string[],
  qType: 0,
  source: '',
  difficulty: 0,
})

onMounted(async () => {
  const [cs, ts]: any[] = await Promise.all([listCategory(), listTag()])
  categories.value = cs
  tags.value = ts
  if (editId) {
    const d: any = await detailQuestion(editId)
    form.title = d.title
    form.answerMd = d.answerMd
    form.categoryId = d.categoryId ?? undefined
    form.tagNames = d.tagNames ?? []
    form.qType = d.qType ?? 0
    form.source = d.source ?? ''
    form.difficulty = d.difficulty ?? 0
  }
})

/** md-editor-v3 插图上传：fetch + token（FormData 场景独立走） */
async function onUploadImg(files: any, callback: any) {
  const list: File[] = Array.isArray(files) ? files : [files]
  const results: any[] = []
  for (const file of list) {
    const fd = new FormData()
    fd.append('file', file)
    try {
      const resp = await fetch('/api/media/upload', {
        method: 'POST',
        headers: { Authorization: `Bearer ${store.token}` },
        body: fd,
      })
      const j: any = await resp.json()
      if (j.code === 200) results.push({ url: j.data.url, alt: '', title: '' })
      else ElMessage.error(j.message || '上传失败')
    } catch { ElMessage.error('图片上传网络异常') }
  }
  callback(results)
}

async function submit() {
  if (!form.title.trim()) { ElMessage.warning('标题不能为空'); return }
  if (!form.answerMd.trim()) { ElMessage.warning('答案不能为空'); return }
  loading.value = true
  try {
    if (editId) await updateQuestion({ id: editId, ...form })
    else await createQuestion(form)
    ElMessage.success(editId ? '已保存' : '已创建')
    router.push(editId ? `/question/${editId}` : '/question/list')
  } finally { loading.value = false }
}
</script>

<template>
  <el-card class="wrap" shadow="never">
    <el-form label-width="70px">
      <el-form-item label="标题">
        <el-input v-model="form.title" maxlength="100" show-word-limit placeholder="例如：HashMap 在并发下为什么不安全？" />
      </el-form-item>
      <el-form-item label="归类">
        <el-select v-model="form.categoryId" clearable placeholder="选择分类" style="width: 200px">
          <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-select v-model="form.qType" style="width: 130px">
          <el-option v-for="(n, i) in ['八股', '手撕', '主观题', '笔试题']" :key="i" :label="n" :value="i" />
        </el-select>
        <el-select v-model="form.difficulty" style="width: 130px">
          <el-option v-for="(n, i) in ['未标', '易', '中', '难']" :key="i" :label="n" :value="i" />
        </el-select>
      </el-form-item>
      <el-form-item label="标签">
        <el-select v-model="form.tagNames" multiple filterable allow-create default-first-option
          placeholder="选已有或直接输入新标签" style="width: 100%">
          <el-option v-for="t in tags" :key="t.id" :label="t.name" :value="t.name" />
        </el-select>
      </el-form-item>
      <el-form-item label="来源">
        <el-input v-model="form.source" maxlength="50" placeholder="牛客 / 美的机考 / 实习复盘…" style="width: 260px" />
      </el-form-item>
    </el-form>

    <MdEditor v-model="form.answerMd" class="editor" :style="{ height: '440px' }"
      placeholder="先自己默写答案，再对照补充…" @onUploadImg="onUploadImg" />

    <div class="ops">
      <el-button @click="router.back()">取 消</el-button>
      <el-button type="primary" :loading="loading" @click="submit">保 存</el-button>
    </div>
  </el-card>
</template>

<style scoped>
.wrap { border-radius: 14px }
.editor { margin-top: 10px; border-radius: 10px }
.ops { display: flex; justify-content: flex-end; gap: 10px; margin-top: 12px }
</style>
