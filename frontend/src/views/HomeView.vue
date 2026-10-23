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

// 💣 侧栏计数陈旧坑：SPA壳只在onMounted取数 → 导航级刷新
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
    <el-aside width="236px" class="side">
      <div class="brand" @click="pickCategory()">
        <span class="mark">温</span>
        <span class="name">温故</span>
        <span class="en">wengu</span>
      </div>

      <div class="cat-head">
        <span class="label">我的分类</span>
        <button class="cat-add" type="button" title="新建分类" @click="quickAddCat">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
            stroke-linecap="round"><path d="M12 5v14M5 12h14" /></svg>
        </button>
      </div>

      <div class="cat-scroll">
        <div class="cat-item" :class="{ active: activeCategory === '' }" @click="pickCategory()">
          <span class="name">全部题目</span>
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
        <span>＋ 新建题目</span>
      </button>
    </el-aside>

    <el-container class="right">
      <el-header class="head">
        <span class="hello">{{ store.nickname || '同学' }}，今天也要温故知新</span>
        <span class="avatar">{{ (store.nickname || '同')[0] }}</span>
        <button class="link-op danger logout" type="button" @click="logout">退出</button>
      </el-header>
      <el-main class="main"><router-view /></el-main>
    </el-container>

    <CreateChoiceDialog v-model="showCreate" />
  </el-container>
</template>

<style scoped>
.shell { height: 100vh; background: var(--bg) }
.side {
  background: var(--paper);
  border-right: 1px solid var(--line);
  padding: var(--sp-5) var(--sp-3);
  display: flex; flex-direction: column; gap: 2px;
}
.brand { display: flex; align-items: center; gap: 9px; padding: 0 8px var(--sp-4); cursor: pointer }
.brand .mark {
  width: 26px; height: 26px; border-radius: 9px; background: var(--brand-grad);
  color: #fff; font-size: 13px; display: grid; place-items: center; font-weight: 700;
  box-shadow: 0 6px 14px rgba(59, 130, 196, .28);
}
.brand .name { font-size: var(--fs-17); font-weight: 800; color: var(--ink); letter-spacing: .5px; font-family: var(--sans) }
.brand .en { font-size: var(--fs-11); color: var(--ink-2); margin-left: 2px; letter-spacing: 1.5px; opacity: .85 }

.cat-head { display: flex; align-items: center; justify-content: space-between; padding: 0 8px var(--sp-2) }
.cat-head .label { font-size: var(--fs-11); color: var(--ink-2); letter-spacing: 2px; font-weight: 600 }
.cat-add {
  width: 22px; height: 22px; border: none; background: transparent; cursor: pointer;
  color: var(--ink-3); border-radius: 6px; display: grid; place-items: center; padding: 0;
  transition: all .15s;
}
.cat-add:hover { background: var(--brand-soft); color: var(--brand-deep) }
.cat-add svg { width: 14px; height: 14px }

.cat-scroll { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 2px; min-height: 0 }
.cat-item {
  display: flex; justify-content: space-between; align-items: center; gap: 8px;
  padding: 9px 10px; border-radius: var(--r-sm); cursor: pointer;
  color: var(--ink-2); font-size: var(--fs-13); transition: background .15s;
}
.cat-item:hover { background: var(--hover) }
.cat-item.active { background: var(--brand-soft); color: var(--brand-deep); font-weight: 600 }
.cat-item .name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap }
.cnt { font-size: var(--fs-11); color: var(--ink-3); flex-shrink: 0 }
.cat-hint { padding: 10px; font-size: var(--fs-12); color: var(--ink-3); line-height: 1.8 }

.create-btn {
  margin-top: var(--sp-3); width: 100%; border: none; cursor: pointer;
  background: var(--brand-grad); color: #fff; font-size: var(--fs-14); font-weight: 700;
  border-radius: var(--r-md); height: 42px;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 10px 22px rgba(59, 130, 196, .26);
  transition: transform .15s, box-shadow .15s;
}
.create-btn:hover { transform: translateY(-1px); box-shadow: 0 14px 28px rgba(59, 130, 196, .36) }

.right { min-width: 0 }
.head {
  display: flex; align-items: center; gap: var(--sp-3);
  height: 60px; padding: 0 var(--sp-6);
  border-bottom: 1px solid var(--line); background: var(--paper);
}
.hello { flex: 1; color: var(--ink-2); font-size: var(--fs-13); font-weight: 600; letter-spacing: .2px }
.avatar {
  width: 30px; height: 30px; border-radius: 50%; background: var(--brand-soft);
  color: var(--brand-deep); display: grid; place-items: center;
  font-size: var(--fs-13); font-weight: 700;
}
.logout { font-size: var(--fs-12) }
.main { padding: var(--sp-6); background: var(--bg); overflow-y: auto }

@media (max-width: 768px) {
  .shell { height: auto; min-height: 100vh }
  .side {
    width: 100% !important; flex-direction: row; align-items: center; overflow-x: auto;
    border-right: none; border-bottom: 1px solid var(--line);
    padding: var(--sp-2) var(--sp-3); gap: var(--sp-1);
  }
  .brand, .cat-head, .cat-hint, .hello { display: none }
  .cat-scroll { flex-direction: row; overflow: visible }
  .cat-item { flex: 0 0 auto }
  .create-btn { width: auto; margin: 0 0 0 var(--sp-2); padding: 0 var(--sp-4); height: 34px; flex-shrink: 0; font-size: var(--fs-13) }
  .head { height: 52px; padding: 0 var(--sp-4) }
  .main { padding: var(--sp-4) }
}
</style>
