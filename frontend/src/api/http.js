import axios from 'axios'
import { useAuthStore } from '@/stores/auth'
import router from '@/router/index'

const http = axios.create({
  baseURL: '/api',
  timeout: 30000
})

http.interceptors.request.use(config => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers['Authorization'] = `Bearer ${auth.token}`
  }
  return config
})

http.interceptors.response.use(
  res => res.data,
  err => {
    if (err.response?.status === 401) {
      const auth = useAuthStore()
      auth.logout()
      router.push('/login')
    }
    const msg = err.response?.data?.message || err.message || '请求失败'
    return Promise.reject(new Error(msg))
  }
)

export default http
