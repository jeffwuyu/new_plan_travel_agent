import http from './http'

export const listPlans = () => http.get('/plans')
export const getPlan = (id) => http.get(`/plans/${id}`)
export const getPlanSteps = (id) => http.get(`/plans/${id}/steps`)
export const getPlanByTask = (uuid) => http.get(`/plans/by-task/${uuid}`)
export const refreshPlanAccommodations = (id) => http.post(`/plans/${id}/accommodations/refresh`)
