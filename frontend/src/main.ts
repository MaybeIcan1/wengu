// main.ts
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './style.css'            // 💣 全局品牌 tokens+reset 必须在此引入（丢失=全站白板，vp2已修）
import App from './App.vue'
import router from './router'

createApp(App).use(createPinia()).use(router).use(ElementPlus).mount('#app')