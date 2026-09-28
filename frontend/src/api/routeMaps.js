import http from './http'

export const getPlanRouteMaps = (id, style) =>
  http.get(`/plans/${id}/route-maps`, { params: { style } })

export const getPlanRouteMap = (id, day, style) =>
  http.get(`/plans/${id}/route-maps/${day}`, { params: { style } })

export const generatePlanRouteMap = (id, day, payload, force = false) =>
  http.post(`/plans/${id}/route-maps/${day}/generate`, payload, { params: { force } })

export const generateAllPlanRouteMaps = (id, payload) =>
  http.post(`/plans/${id}/route-maps/generate-all`, payload)

export const createPlanRouteMapStream = (id, token) =>
  new EventSource(`/api/plans/${id}/route-maps/stream?token=${encodeURIComponent(token || '')}`)
