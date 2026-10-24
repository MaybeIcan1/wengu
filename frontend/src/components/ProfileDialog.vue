<script setup lang="ts">
// 个人设置：修改昵称 / 邮箱（登录名不可改）
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getMeApi, updateProfileApi } from '../api/user'
import { useUserStore } from '../stores/user'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved', nickname: string): void
}>()

const store = useUserStore()
const form = reactive({ nickname: '', email: '' })
const loading = ref(false)
const saving = ref(false)

watch(() => props.modelValue, async (open) => {
  if (!open) return
  loading.value = true
  try {
    const me: any = await getMeApi()
    form.nickname = me.nickname ?? ''
    form.email = me.email ?? ''
  } finally { loading.value = false }
})

async function save() {
  if (!form.nickname.trim()) { ElMessage.warning('昵称不能为空'); return }
  saving.value = true
  try {
    const vo: any = await updateProfileApi({
      nickname: form.nickname.trim(),
      email: form.email.trim() || undefined,
    })
    store.nickname = vo.nickname ?? form.nickname.trim()
    ElMessage.success('已保存')
    emit('saved', store.nickname)
    emit('update:modelValue', false)
  } finally { saving.value = false }
}
</script>

<template>
  <el-dialog :model-value="props.modelValue" title="个人设置" width="440px" align-center
    @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <el-form label-width="64px" v-loading="loading">
      <el-form-item label="昵称">
        <el-input v-model="form.nickname" maxlength="30" show-word-limit placeholder="导航栏与排行榜展示的名字" />
      </el-form-item>
      <el-form-item label="邮箱">
        <el-input v-model="form.email" maxlength="100" placeholder="选填" />
      </el-form-item>
      <p class="tip">登录名不可修改；昵称会显示在顶部问候和今日复习榜。</p>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取 消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保 存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tip { margin: 4px 0 0 64px; font-size: 12px; color: var(--ink-3); line-height: 1.8 }
</style>
