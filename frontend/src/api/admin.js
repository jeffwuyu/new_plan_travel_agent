import http from './http'

// Users
export const listUsers = (page = 1, size = 20) => http.get('/admin/users', { params: { page, size } })
export const updateUserLevel = (userId, level) => http.put(`/admin/users/${userId}/level`, { userLevel: level })
export const updateUserStatus = (userId, status) => http.put(`/admin/users/${userId}/status`, { status })

// Quota configs
export const listQuotaConfigs = () => http.get('/admin/quota-configs')
export const updateQuotaConfig = (level, data) => http.put(`/admin/quota-configs/${level}`, data)

// Tasks
export const listAdminTasks = (page = 1, size = 20, status = '') =>
  http.get('/admin/tasks', { params: { page, size, ...(status ? { status } : {}) } })

// Metrics
export const getMetrics = () => http.get('/admin/metrics')

// RAG documents
export const uploadRagDocument = (formData) =>
  http.post('/rag/documents/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } })
export const listRagDocuments = () => http.get('/rag/documents')
export const getRagDocument = (id) => http.get(`/rag/documents/${id}`)
export const ingestDocument = (id) => http.post(`/rag/documents/${id}/ingest`)
