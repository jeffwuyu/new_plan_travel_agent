<template>
  <el-container class="page-container">
    <el-header class="page-header">
      <el-button :icon="ArrowLeft" @click="router.push('/plans')">返回规划列表</el-button>
      <span class="page-title">规划详情</span>
    </el-header>

    <el-main v-loading="loading">
      <template v-if="plan">
        <el-card class="plan-overview mb-20">
          <h2 class="plan-title">{{ plan.title }}</h2>
          <div class="plan-meta">
            <el-tag>{{ plan.region }}</el-tag>
            <el-tag type="info">时间填充结果</el-tag>
          </div>
          <div class="plan-info-grid">
            <div class="info-item">
              <span>开始位置</span>
              <strong>{{ plan.startLocationQuery || '-' }}</strong>
            </div>
            <div class="info-item">
              <span>结束位置</span>
              <strong>{{ plan.endLocationQuery || '-' }}</strong>
            </div>
            <div class="info-item">
              <span>开始时间</span>
              <strong>{{ formatTime(plan.tripStartTime) }}</strong>
            </div>
            <div class="info-item">
              <span>结束时间</span>
              <strong>{{ formatTime(plan.tripEndTime) }}</strong>
            </div>
            <div class="info-item">
              <span>完整天默认时间</span>
              <strong>{{ formatFullDayWindow(plan) }}</strong>
            </div>
            <div class="info-item">
              <span>终点缓冲</span>
              <strong>{{ plan.destinationBufferMin || 30 }} 分钟</strong>
            </div>
          </div>
          <p class="plan-summary">{{ plan.summary }}</p>
        </el-card>

        <PlanDayCard
          v-for="(stepsForDay, day) in groupedSteps"
          :key="day"
          :day-number="Number(day)"
          :steps="stepsForDay"
          :window="dayWindows[day]"
          :destination-name="plan.endLocationQuery"
          :destination-buffer-min="plan.destinationBufferMin || 30"
        />
      </template>
    </el-main>
  </el-container>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { getPlan, getPlanSteps } from '@/api/plans'
import PlanDayCard from '@/components/PlanDayCard.vue'

const route = useRoute()
const router = useRouter()
const planId = route.params.id

const plan = ref(null)
const steps = ref([])
const loading = ref(true)

const groupedSteps = computed(() => {
  const groups = {}
  for (const step of steps.value) {
    const day = step.dayNumber || 1
    if (!groups[day]) groups[day] = []
    groups[day].push(step)
  }
  return groups
})

const dayWindows = computed(() => {
  const start = plan.value?.tripStartTime ? new Date(plan.value.tripStartTime) : null
  const end = plan.value?.tripEndTime ? new Date(plan.value.tripEndTime) : null
  if (!start || !end) return {}
  const windows = {}
  const totalDays = Number(plan.value?.totalDays || 1)
  const fullStart = plan.value?.fullDayStartTime?.slice(0, 5) || '07:00'
  const fullEnd = plan.value?.fullDayEndTime?.slice(0, 5) || '21:00'
  for (let i = 0; i < totalDays; i += 1) {
    const day = i + 1
    if (totalDays === 1) {
      windows[day] = `${formatTime(start)} - ${formatTime(end)}`
    } else if (i === 0) {
      windows[day] = `${formatTime(start)} - ${start.toLocaleDateString('zh-CN')} ${fullEnd}`
    } else if (i === totalDays - 1) {
      windows[day] = `${end.toLocaleDateString('zh-CN')} ${fullStart} - ${formatTime(end)}`
    } else {
      const date = new Date(start)
      date.setDate(start.getDate() + i)
      windows[day] = `${date.toLocaleDateString('zh-CN')} ${fullStart} - ${date.toLocaleDateString('zh-CN')} ${fullEnd}`
    }
  }
  return windows
})

onMounted(async () => {
  try {
    const [planRes, stepsRes] = await Promise.all([getPlan(planId), getPlanSteps(planId)])
    plan.value = planRes.data
    steps.value = stepsRes.data || []
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
})

function formatTime(ts) {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}

function formatFullDayWindow(planValue) {
  const start = planValue?.fullDayStartTime?.slice(0, 5) || '07:00'
  const end = planValue?.fullDayEndTime?.slice(0, 5) || '21:00'
  return `${start}-${end}`
}
</script>

<style scoped>
.page-container { min-height: 100vh; background: #f5f7fa; }
.page-header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 24px;
}
.page-title { font-size: 16px; font-weight: 600; }
.mb-20 { margin-bottom: 20px; }
.plan-title { margin: 0 0 12px; font-size: 20px; }
.plan-meta { display: flex; gap: 8px; margin-bottom: 12px; }
.plan-info-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.info-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  border-radius: 12px;
  background: #f5f7fa;
  color: #606266;
}
.plan-summary { margin: 0; font-size: 14px; color: #606266; line-height: 1.7; }

@media (max-width: 900px) {
  .plan-info-grid {
    grid-template-columns: 1fr;
  }
}
</style>
