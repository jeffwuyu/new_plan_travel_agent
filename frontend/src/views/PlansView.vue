<template>
  <el-container class="page-container">
    <el-header class="page-header">
      <div class="header-left">
        <span class="logo">✈ 旅游规划助手</span>
        <el-menu mode="horizontal" :default-active="'/plans'" router class="nav-menu">
          <el-menu-item index="/tasks">我的任务</el-menu-item>
          <el-menu-item index="/plans">规划结果</el-menu-item>
          <el-menu-item v-if="auth.isAdmin" index="/admin">管理后台</el-menu-item>
        </el-menu>
      </div>
    </el-header>

    <el-main>
      <h3 class="section-title">我的旅行规划</h3>
      <el-row :gutter="16" v-loading="loading">
        <el-col v-for="plan in plans" :key="plan.id" :xs="24" :sm="12" :lg="8" class="mb-16">
          <el-card shadow="hover" class="plan-card" @click="router.push(`/plans/${plan.id}`)">
            <div class="plan-title">{{ plan.title || `${plan.region}旅行规划` }}</div>
            <div class="plan-meta">
              <el-tag size="small">{{ plan.region }}</el-tag>
              <el-tag size="small" type="success">{{ plan.totalDays }} 天</el-tag>
            </div>
            <p class="plan-summary">{{ plan.summary || '暂无摘要' }}</p>
            <div class="plan-time">{{ formatDate(plan.createdAt) }}</div>
          </el-card>
        </el-col>
      </el-row>
      <el-empty v-if="!loading && !plans.length" description="暂无规划结果" />
    </el-main>
  </el-container>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listPlans } from '@/api/plans'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const plans = ref([])
const loading = ref(true)

onMounted(async () => {
  try {
    const res = await listPlans()
    plans.value = res.data || []
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
})

function formatDate(ts) {
  if (!ts) return ''
  return new Date(ts).toLocaleDateString('zh-CN')
}
</script>

<style scoped>
.page-container { min-height: 100vh; background: #f5f7fa; }
.page-header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  padding: 0 24px;
}
.header-left { display: flex; align-items: center; gap: 24px; }
.logo { font-size: 18px; font-weight: 600; color: #409eff; }
.nav-menu { border-bottom: none; }
.section-title { margin: 0 0 16px; font-size: 16px; }
.mb-16 { margin-bottom: 16px; }
.plan-card { cursor: pointer; }
.plan-title { font-size: 16px; font-weight: 600; margin-bottom: 8px; }
.plan-meta { display: flex; gap: 6px; margin-bottom: 8px; }
.plan-summary { font-size: 13px; color: #606266; line-height: 1.5; margin: 0 0 8px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.plan-time { font-size: 12px; color: #c0c4cc; }
</style>
