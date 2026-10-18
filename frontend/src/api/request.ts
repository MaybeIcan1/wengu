// api/request.ts —— ★ 全项目唯一 axios 实例（拦截器是必考点）
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'
import router from '../router'

const request = axios.create({ baseURL: '/api', timeout: 15000 })

request.interceptors.request.use(cfg => {
    const token = useUserStore().token
    if (token) cfg.headers.Authorization = `Bearer ${token}`
    return cfg
})
request.interceptors.response.use(resp => {
    const { code, message, data } = resp.data
    if (code === 200) return data
    if (code === 401) { router.push('/login'); return Promise.reject() }
    ElMessage.error(message || '请求失败')
    return Promise.reject(new Error(message))
}, err => { ElMessage.error('网络异常'); return Promise.reject(err) })

// 💣 债登记（第7章缓存阶段一并还）：运行时拦截器已拆包返回data，但TS签名仍是AxiosResponse。
// 全局放行为 any 避免所有调用点二次断言；等引入正式类型定义时收掉。
export default request as any