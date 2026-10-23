<script setup lang="ts">
// 登录后的整体布局：左侧分类栏 + 顶部用户区 + 主内容 router-view
import { onMounted, ref, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMeApi } from '../api/user'
import { listCategory, createCategory } from '../api/category'
import { useUserStore } from '../stores/user'

const router = useRouter()
const route = useRoute()
const store = useUserStore()
const categories = ref<any[]>([])

const activeCategory = computed(() => (route.query.category ? String(route.query.category) : ''))

// 💣 侧栏计数陈旧坑（用户实测踩中：移动题目后侧栏还是0）：SPA壳只在onMounted取数。
// 导航级刷新：任何路由跳转（保存/删除/进入详情）都顺路刷一次分类计数。
watch(() => route.fullPath, () => { refreshCategories() })

onMounted(async () => {
  try {
    const me: any = await getMeApi()
    store.nickname = me.nickname ?? store.nickname
  } catch { /* 401 已被拦截器接管 */ }
  refreshCategories()
})

function refreshCategories() {
  listCategory().then((cs: any) => { categories.value = cs as any[] }).catch(() => {})
}

function quickAddCat() {
  ElMessageBox.prompt('输入新分类名（建议10字内）', '新建分类')
    .then(async ({ value }) => {
      if (!value?.trim()) return
      await createCategory({ name: value.trim() })
      ElMessage.success('已创建')
      refreshCategories()
    })
    .catch(() => {})
}

function pickCategory(id?: string) {
  router.push(id ? `/question/list?category=${id}` : '/question/list')
}

function logout() {
  ElMessageBox.confirm('确定退出登录？', '提示', { type: 'warning' }).then(() => store.logout())
}
</script>

<template>
  <el-container class="shell">
    <el-aside width="220px" class="side">
      <div class="brand" @click="pickCategory()">wengu · 温故</div>
      <div class="cat-title">分类</div>
      <div class="cat-item" :class="{ active: activeCategory === '' }" @click="pickCategory()">
        <span>全部题目</span>
      </div>
      <div
        v-for="c in categories"
        :key="c.id"
        class="cat-item"
        :class="{ active: activeCategory === String(c.id) }"
        @click="pickCategory(String(c.id))"
      >
        <span>{{ c.name }}</span><span class="cnt">{{ c.questionCount }}</span>
      </div>
      <el-button class="add-cat" size="small" text type="primary" @click="quickAddCat">+ 新建分类</el-button>
      <el-button class="add-cat" size="small" text type="warning" @click="router.push('/import')">导入 docx</el-button>
    </el-aside>

    <el-container>
      <el-header class="head">
        <span class="hello">{{ store.nickname || '同学' }}，今天也要温故知新</span>
        <el-tag effect="plain" type="warning" round>{{ store.nickname }}</el-tag>
        <el-button link type="danger" @click="logout">退出</el-button>
      </el-header>
      <el-main><router-view /></el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.shell { height: 100vh }
.side {
  background: var(--card);
  border-right: 1px solid var(--line);
  padding: 18px 10px;
  display: flex; flex-direction: column; gap: 4px;
}
.brand { font-weight: 700; color: var(--brand-deep); font-size: 18px; padding: 6px 10px; cursor: pointer }
.cat-title { font-size: 12px; color: var(--ink-soft); padding: 10px 10px 4px }
.cat-item {
  padding: 8px 10px; border-radius: 8px; cursor: pointer;
  display: flex; justify-content: space-between; align-items: center;
  color: #6b584a; font-size: 14px;
}
.cat-item:hover { background: #f5ecdf }
.cat-item.active { background: var(--brand-bg); color: #c25b34; font-weight: 600 }
.cnt { font-size: 12px; color: #b09a82 }
.add-cat { margin-top: 8px; align-self: flex-start }
.head {
  display: flex; align-items: center; gap: 12px;
  border-bottom: 1px solid var(--line); background: var(--card);
}
.hello { flex: 1; color: #8a7663; font-size: 13px }

/* ---- 响应式：手机断点（<768px 纵向堆叠、侧栏变顶部横条） ---- */
@media (max-width: 768px) {
  .shell { height: auto; min-height: 100vh }
  .side { width: 100% !important; flex-direction: row; overflow-x: auto;
          border-right: none; border-bottom: 1px solid var(--line); padding: 10px }
  .brand { display: none }
  .cat-title { display: none }
  .cat-item { flex: 0 0 auto }
  .add-cat { display: none }
  .hello { display: none }
}
</style>
