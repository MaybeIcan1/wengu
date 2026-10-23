<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import AuthShell from '../components/AuthShell.vue'
import { useUserStore } from '../stores/user'

const router = useRouter()
const store = useUserStore()
const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function submit() {
  loading.value = true
  try {
    await store.login(form)
    router.push('/')
  } finally { loading.value = false }     // 错误提示在axios拦截器里统一弹了
}
</script>

<template>
  <AuthShell>
    <h2 class="title">欢迎回来</h2>
    <p class="sub">登录后继续你的复习计划</p>
    <el-form>
      <el-form-item><el-input v-model="form.username" placeholder="用户名" size="large" /></el-form-item>
      <el-form-item><el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password @keyup.enter="submit" /></el-form-item>
      <el-button class="go" type="primary" size="large" :loading="loading" @click="submit">登 录</el-button>
      <div class="tail">没有账号？<router-link to="/register" class="link">现在注册</router-link></div>
    </el-form>
  </AuthShell>
</template>

<style scoped>
.title { margin: 0; font-size: 22px; color: var(--ink) }
.sub { margin: 8px 0 22px; font-size: 13px; color: var(--ink-3) }
.go { width: 100%; background: var(--brand-grad); border: none; border-radius: 10px; height: 42px; font-weight: 600 }
.go:hover, .go:focus { background: var(--brand-grad); border: none; filter: brightness(1.04) }
.tail { text-align: center; margin-top: 16px; font-size: 13px; color: var(--ink-3) }
.link { color: var(--brand-deep); text-decoration: none }
</style>
