<script setup lang="ts">
// 登录后的整体布局：左侧分类栏 + 顶部用户区 + 主内容 router-view
import { onMounted, ref, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMeApi } from '../api/user'
import { listCategory, createCategory } from '../api/category'
import { useUserStore } from '../stores/user'
import CreateChoiceDialog from '../components/CreateChoiceDialog.vue'

const router = useRouter()
const route = useRoute()
const store = useUserStore()
const categories = ref<any[]>([])
const showCreate = ref(false)

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
  ElMessageBox.prompt('输入新分类名（建议10字内）', '新建分类', {
    inputPlaceholder: '例如：计算机网络',
    confirmButtonText: '创建',
  })
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
    <el-aside width="228px" class="side">
      <div class="brand" @click="pickCategory()">
        <span class="dot" />wengu · 温故
      </div>

      <div class="cat-head">
        <span>分类</span>
        <button class="cat-add" type="button" title="新建分类" @click="quickAddCat">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
            stroke-linecap="round"><path d="M12 5v14M5 12h14" /></svg>
        </button>
      </div>

      <div class="cat-scroll">
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
          <span class="name">{{ c.name }}</span><span class="cnt">{{ c.questionCount }}</span>
        </div>
        <div v-if="!categories.length" class="cat-hint">还没有分类，点右上角 + 新建</div>
      </div>

      <button class="create-btn" type="button" @click="showCreate = true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
          stroke-linecap="round"><path d="M12 5v14M5 12h14" /></svg>
        新建题目
      </button>
    </el-aside>

    <el-container>
      <el-header class="head">
        <span class="hello">{{ store.nickname || '同学' }}，今天也要温故知新</span>
        <el-tag effect="plain" type="warning" round>{{ store.nickname }}</el-tag>
        <el-button link type="danger" @click="logout">退出</el-button>
      </el-header>
      <el-main><router-view /></el-main>
    </el-container>

    <CreateChoiceDialog v-model="showCreate" />
  </el-container>
</template>

<style scoped>
.shell { height: 100vh }
.side {
  background: var(--card);
  border-right: 1px solid var(--line);
  padding: 16px 12px;
  display: flex; flex-direction: column; gap: 6px;
}
.brand {
  display: flex; align-items: center; gap: 8px;
  font-weight: 700; color: var(--brand-deep); font-size: 17px;
  padding: 4px 8px 10px; cursor: pointer;
}
.dot { width: 8px; height: 8px; border-radius: 50%; background: var(--brand) }
.cat-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 4px 8px; font-size: 12px; color: var(--ink-soft);
  letter-spacing: .5px;
}
.cat-add {
  width: 22px; height: 22px; border: none; background: transparent; cursor: pointer;
  color: var(--ink-soft); border-radius: 6px; display: grid; place-items: center;
  padding: 0; transition: all .15s;
}
.cat-add:hover { background: var(--brand-bg); color: var(--brand-deep) }
.cat-add svg { width: 14px; height: 14px }
.cat-scroll { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 2px; min-height: 0 }
.cat-item {
  padding: 8px 10px; border-radius: 9px; cursor: pointer;
  display: flex; justify-content: space-between; align-items: center; gap: 8px;
  color: #6b584a; font-size: 13.5px; transition: background .15s;
}
.cat-item:hover { background: #f5ecdf }
.cat-item.active { background: var(--brand-bg); color: #c25b34; font-weight: 600 }
.cat-item .name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap }
.cnt { font-size: 11.5px; color: #b09a82; flex-shrink: 0 }
.cat-hint { padding: 10px; font-size: 12px; color: #b8a793; line-height: 1.7 }
.create-btn {
  margin-top: 10px; width: 100%; border: none; cursor: pointer;
  background: var(--brand); color: #fff; font-size: 14px; font-weight: 600;
  border-radius: 10px; padding: 11px 0;
  display: flex; align-items: center; justify-content: center; gap: 6px;
  transition: background .15s;
}
.create-btn:hover { background: var(--brand-deep) }
.create-btn svg { width: 15px; height: 15px }
.head {
  display: flex; align-items: center; gap: 12px;
  border-bottom: 1px solid var(--line); background: var(--card);
}
.hello { flex: 1; color: #8a7663; font-size: 13px }

/* ---- 响应式：手机断点（<768px 纵向堆叠、侧栏变顶部横条） ---- */
@media (max-width: 768px) {
  .shell { height: auto; min-height: 100vh }
  .side { width: 100% !important; flex-direction: row; overflow-x: auto; align-items: center;
          border-right: none; border-bottom: 1px solid var(--line); padding: 10px; gap: 4px }
  .brand { display: none }
  .cat-head { display: none }
  .cat-scroll { flex-direction: row; overflow: visible; }
  .cat-item { flex: 0 0 auto }
  .cat-hint { display: none }
  .create-btn { width: auto; margin: 0 0 0 8px; padding: 8px 14px; flex-shrink: 0; font-size: 13px }
  .hello { display: none }
}
</style>
