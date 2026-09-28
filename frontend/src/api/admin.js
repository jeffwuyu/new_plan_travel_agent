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
export const getAdminTaskEvents = (taskUuid, limit = 100) =>
  http.get(`/admin/tasks/${taskUuid}/events`, { params: { limit } })
export const getAdminTaskLease = (taskUuid) =>
  http.get(`/admin/tasks/${taskUuid}/lease`)
export const redispatchAdminTask = (taskUuid, reason = 'manual_admin_redispatch') =>
  http.post(`/admin/tasks/${taskUuid}/redispatch`, { reason })

// Metrics
export const getMetrics = () => http.get('/admin/metrics')
export const getCapabilityHealth = () => http.get('/admin/capabilities/health')
export const getRouteMapStatistics = (params = {}) =>
  http.get('/admin/route-maps/statistics', { params })

// RAG documents
export const uploadRagDocument = (formData) =>
  http.post('/rag/documents/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } })
export const listRagDocuments = () => http.get('/rag/documents')
export const getRagDocument = (id) => http.get(`/rag/documents/${id}`)
export const ingestDocument = (id) => http.post(`/rag/documents/${id}/ingest`)
export const retryRagDocument = (id) => http.post(`/rag/documents/${id}/retry`)
export const disableRagDocument = (id) => http.post(`/rag/documents/${id}/disable`)
export const reenableRagDocument = (id) => http.post(`/rag/documents/${id}/reenable`)
export const deleteRagDocument = (id) => http.post(`/rag/documents/${id}/delete`)
