<script setup lang="ts">
// 统一状态盒：loading骨架 / 空态 / 正常插槽 —— 三个业务页全部接入，重复即债
defineProps<{
  loading?: boolean
  empty?: boolean
  emptyText?: string
  rows?: number
}>()
</script>

<template>
  <el-skeleton v-if="loading" :rows="rows ?? 5" animated class="box-skeleton" />
  <el-empty v-else-if="empty" :description="emptyText ?? '暂无内容'" class="box-empty">
    <!-- 空态行动区：由使用方决定放什么按钮（如"自己DIY / 一键导入"） -->
    <slot name="empty" />
  </el-empty>
  <slot v-else />
</template>

<style scoped>
.box-skeleton { padding: 24px }
.box-empty { padding: 60px 0 }
</style>
