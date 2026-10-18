<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AuthShell from '../components/AuthShell.vue'
import { registerApi } from '../api/user'

const router = useRouter()
const form = reactive({ username: '', password: '', nickname: '' })
const loading = ref(false)

async function submit() {
  loading.value = true
  try {
    await registerApi(form)
    ElMessage.success('注册成功')
    router.push('/login')
  } finally { loading.value = false }
}
</script>

<template>
  <AuthShell>
    <h2 class="title">加入温故</h2>
    <p class="sub">一起把每道八股都变成肌肉记忆</p>
    <el-form>
      <el-form-item><el-input v-model="form.username" placeholder="用户名（3~20位字母数字下划线）" size="large" /></el-form-item>
      <el-form-item><el-input v-model="form.nickname" placeholder="昵称（留空则同用户名）" size="large" /></el-form-item>
      <el-form-item><el-input v-model="form.password" type="password" placeholder="密码（6~32位）" size="large" show-password @keyup.enter="submit" /></el-form-item>
      <el-button class="go" type="primary" size="large" :loading="loading" @click="submit">注 册</el-button>
      <div class="tail">已有账号？<router-link to="/login" class="link">去登录</router-link></div>
    </el-form>
  </AuthShell>
</template>

<style scoped>
.title { margin: 0; font-size: 22px; color: #5d4634 }
.sub { margin: 8px 0 22px; font-size: 13px; color: #a08a76 }
.go { width: 100%; background: #ed7d51; border: none; border-radius: 10px }
.go:hover, .go:focus { background: #df6b40; border: none }
.tail { text-align: center; margin-top: 16px; font-size: 13px; color: #a08a76 }
.link { color: #e2735f; text-decoration: none }
</style>
