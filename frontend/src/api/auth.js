import http from './http'

export const login = (data) => http.post('/auth/login', data)
export const register = (data) => http.post('/auth/register', data)
export const logout = () => http.post('/auth/logout')
export const getProfile = () => http.get('/user/profile')
export const getQuota = () => http.get('/user/quota')
