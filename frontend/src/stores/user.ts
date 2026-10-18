// stores/user.ts
import { defineStore } from 'pinia'
import { loginApi } from '../api/user'

export const useUserStore = defineStore('user', {
    state: () => ({
        token: localStorage.getItem('wengu_token') || '',
        userId: 0, nickname: '' as string,
    }),
    actions: {
        async login(payload: { username: string; password: string }) {
            const vo: any = await loginApi(payload)          // axios层已拆包，拿到的就是data
            this.token = vo.token; this.userId = vo.userId; this.nickname = vo.nickname
            localStorage.setItem('wengu_token', vo.token)
        },
        logout() {
            this.token = ''; localStorage.removeItem('wengu_token'); location.href = '/login'
        },
    },
})