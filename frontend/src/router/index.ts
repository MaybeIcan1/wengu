// router/index.ts
import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const router = createRouter({
    history: createWebHistory(),
    routes: [
        { path: '/login',    component: () => import('../views/LoginView.vue') },
        { path: '/register', component: () => import('../views/RegisterView.vue') },
        {
            path: '/',
            component: () => import('../views/HomeView.vue'),       // 布局壳
            children: [
                { path: '', redirect: '/question/list' },
                { path: 'question/list',  component: () => import('../views/QuestionListView.vue') },
                { path: 'question/new',   component: () => import('../views/QuestionEditView.vue') },
                { path: 'question/:id',    component: () => import('../views/QuestionDetailView.vue') },
                { path: 'question/:id/edit', component: () => import('../views/QuestionEditView.vue') },
            ],
        },
    ],
})

router.beforeEach(to => {
    const store = useUserStore()
    if (to.path !== '/login' && to.path !== '/register' && !store.token) return '/login'
})

export default router
