import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  { path: '/', redirect: '/tasks' },
  { path: '/login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
  { path: '/register', component: () => import('@/views/RegisterView.vue'), meta: { public: true } },
  { path: '/tasks', component: () => import('@/views/TasksView.vue') },
  { path: '/tasks/:uuid', component: () => import('@/views/TaskDetailView.vue') },
  { path: '/plans', component: () => import('@/views/PlansView.vue') },
  { path: '/plans/:id', component: () => import('@/views/PlanDetailView.vue') },
  {
    path: '/admin',
    component: () => import('@/views/admin/AdminLayout.vue'),
    meta: { adminOnly: true },
    children: [
      { path: '', redirect: '/admin/users' },
      { path: 'users', component: () => import('@/views/admin/AdminUsersView.vue') },
      { path: 'quota', component: () => import('@/views/admin/AdminQuotaView.vue') },
      { path: 'rag', component: () => import('@/views/admin/AdminRagView.vue') },
      { path: 'metrics', component: () => import('@/views/admin/AdminMetricsView.vue') }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/tasks' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(async (to, from, next) => {
  const auth = useAuthStore()
  if (auth.isLoggedIn && !auth.bootstrapped) {
    await auth.bootstrapAuthData()
  }
  if (!to.meta.public && !auth.isLoggedIn) {
    next({ path: '/login', query: { redirect: to.fullPath } })
  } else if (to.meta.adminOnly && !auth.isAdmin) {
    next('/tasks')
  } else {
    next()
  }
})

export default router
