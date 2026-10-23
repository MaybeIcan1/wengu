<script setup lang="ts">
// 新建入口的二选一：自己 DIY / 一键导入 docx
import { useRouter } from 'vue-router'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()
const router = useRouter()

function close() { emit('update:modelValue', false) }
function go(path: string) { close(); router.push(path) }
</script>

<template>
  <el-dialog :model-value="props.modelValue" width="600px" align-center
    :show-close="false" class="choice-dialog"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <div class="head">
      <h3>怎么添加题目？</h3>
      <p>两种方式，最终都汇入同一个题库</p>
    </div>

    <div class="cards">
      <button class="card" type="button" @click="go('/question/new')">
        <svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor"
          stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 20h9" />
          <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" />
        </svg>
        <div class="name">自己 DIY</div>
        <div class="desc">手写标题与答案，Markdown 编辑<br />适合日常零散记录</div>
      </button>

      <button class="card" type="button" @click="go('/import')">
        <svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor"
          stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 3v12" />
          <path d="m7 10 5 5 5-5" />
          <path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" />
        </svg>
        <div class="name">一键导入 docx</div>
        <div class="desc">批量解析已有笔记，自动拆题<br />预览把关后确认入库</div>
      </button>
    </div>

    <div class="tail">
      <el-button text @click="close">取消</el-button>
    </div>
  </el-dialog>
</template>

<style scoped>
.head { text-align: left; margin-bottom: 18px }
.head h3 { margin: 0; font-size: 19px; color: var(--ink) }
.head p { margin: 6px 0 0; font-size: 12.5px; color: var(--ink-soft) }
.cards { display: grid; grid-template-columns: 1fr 1fr; gap: 14px }
.card {
  text-align: left; cursor: pointer; background: var(--card);
  border: 1px solid var(--line); border-radius: 14px;
  padding: 20px 18px; transition: all .18s ease;
  font-family: inherit;
}
.card:hover { border-color: var(--brand); transform: translateY(-2px);
  box-shadow: 0 10px 28px rgba(151, 108, 72, .14) }
.ico { width: 26px; height: 26px; color: var(--brand-deep); margin-bottom: 12px }
.name { font-size: 15px; font-weight: 650; color: var(--ink) }
.desc { margin-top: 8px; font-size: 12px; line-height: 1.8; color: var(--ink-soft) }
.tail { display: flex; justify-content: flex-end; margin-top: 16px }
@media (max-width: 640px) { .cards { grid-template-columns: 1fr } }
</style>
