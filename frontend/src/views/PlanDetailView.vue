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
            <el-tag type="info">路线结果</el-tag>
          </div>
          <p class="plan-summary">{{ plan.summary }}</p>
        </el-card>

        <PlanDayCard
          v-for="(stepsForDay, day) in groupedSteps"
          :key="day"
          :day-number="Number(day)"
          :steps="stepsForDay"
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
.plan-summary { margin: 0; font-size: 14px; color: #606266; line-height: 1.7; }
</style>
