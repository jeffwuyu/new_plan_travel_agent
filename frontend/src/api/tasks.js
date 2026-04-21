import http from './http'

export const createTask = (data) => http.post('/tasks', data)
export const listTasks = () => http.get('/tasks')
export const getTask = (uuid) => http.get(`/tasks/${uuid}`)
export const cancelTask = (uuid) => http.delete(`/tasks/${uuid}`)
export const resumeTask = (uuid) => http.post(`/tasks/${uuid}/resume`)
export const getProgress = (uuid, limit = 20) => http.get(`/tasks/${uuid}/progress`, { params: { limit } })
export const confirmOriginSelection = (uuid, data) => http.post(`/tasks/${uuid}/origin-selection`, data)
