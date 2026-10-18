<script setup lang="ts">
// 登录/注册共用的品牌外壳：左边产品语境（浮动知识卡），右边表单插槽
</script>

<template>
  <div class="auth-page">
    <div class="brand-pane">
      <div class="brand-logo">wengu · 温故</div>
      <p class="slogan">温故而知新<br />每一道错题，都值得被温柔记得</p>

      <div class="float-card c1">
        <span class="chip">计算机网络</span>
        <p>TCP 为什么握手要三次？</p>
      </div>
      <div class="float-card c2">
        <span class="chip">Java</span>
        <p>HashMap 在并发下为什么不安全？</p>
      </div>
      <div class="float-card c3">
        <span class="chip">今日复习</span>
        <p>距下次复习还有 <b>2</b> 题</p>
      </div>
    </div>

    <div class="form-pane">
      <div class="form-box">
        <slot />
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth-page {
  display: flex;
  min-height: 100vh;
  background: #f7f3ed;                       /* 奶油底 */
}
.brand-pane {
  position: relative;
  width: 42%;
  background: linear-gradient(160deg, #f6b26b 0%, #ec8a6a 55%, #e2735f 100%);
  color: #fff;
  padding: 48px;
  overflow: hidden;
}
.brand-logo {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 1px;
}
.slogan {
  margin-top: 10px;
  font-size: 14px;
  line-height: 1.9;
  opacity: .92;
}
/* 漂浮的知识卡片：既是装饰，也演示产品形态 */
.float-card {
  position: absolute;
  width: 230px;
  padding: 14px 16px;
  border-radius: 14px;
  background: rgba(255, 255, 255, .96);
  color: #8a5b3f;
  box-shadow: 0 12px 30px rgba(126, 67, 36, .18);
}
.float-card p { font-size: 14px; margin: 6px 0 0 }
.float-card .chip {
  font-size: 12px;
  color: #e2735f;
  background: #fdeee4;
  padding: 2px 10px;
  border-radius: 999px;
}
.c1 { top: 24%; left: 14%; transform: rotate(-5deg) }
.c2 { top: 48%; left: 40%; transform: rotate(4deg) }
.c3 { bottom: 16%; left: 12%; transform: rotate(-2deg) }

.form-pane {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
.form-box {
  width: 340px;
  padding: 36px 34px;
  border-radius: 18px;
  background: #fffdf9;
  box-shadow: 0 10px 40px rgba(151, 108, 72, .12);
}

@media (max-width: 768px) {
  .brand-pane { display: none }             /* 手机端只保留表单 */
  .form-pane { padding: 24px }
}
</style>
