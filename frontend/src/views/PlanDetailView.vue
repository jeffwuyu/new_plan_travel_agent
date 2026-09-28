<template>
  <el-container class="page-container">
    <el-header class="page-header">
      <el-button :icon="ArrowLeft" @click="router.push('/plans')">返回规划列表</el-button>
      <span class="page-title">规划详情</span>
    </el-header>

    <el-main v-loading="loading">
      <template v-if="plan">
        <el-card class="plan-overview mb-20" shadow="never">
          <h2 class="plan-title">{{ plan.title }}</h2>
          <div class="plan-meta">
            <el-tag>{{ plan.region }}</el-tag>
            <el-tag type="info">{{ plan.totalDays || 1 }} 天行程</el-tag>
            <el-tag :type="accommodationStatusTagType">
              {{ accommodationStatusText }}
            </el-tag>
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
              <span>完整天时间窗</span>
              <strong>{{ formatFullDayWindow(plan) }}</strong>
            </div>
            <div class="info-item">
              <span>终点缓冲</span>
              <strong>{{ plan.destinationBufferMin || 30 }} 分钟</strong>
            </div>
          </div>
          <p class="plan-summary">{{ plan.summary }}</p>
        </el-card>

        <el-alert
          v-if="plan.accommodationStatus !== 'available'"
          class="mb-20"
          :type="accommodationAlertType"
          :closable="false"
          :title="plan.accommodationFailureReason || '暂无 OTA 实时可订住宿推荐'"
        />

        <el-alert
          v-else-if="hasStaleAccommodationPrices"
          class="mb-20"
          type="warning"
          :closable="false"
          title="住宿价格可能已过期，请刷新后再确认预订"
        />

        <template v-for="(stepsForDay, day) in groupedSteps" :key="day">
          <PlanRouteMap
            :plan-id="planId"
            :day-number="Number(day)"
            :initial-route-map="routeMapsByKey[routeMapKey(Number(day), 'anime_travel_map')]"
            @updated="updateRouteMap"
          />
          <PlanDayCard
            :day-number="Number(day)"
            :steps="stepsForDay"
            :window="dayWindows[day]"
            :destination-name="plan.endLocationQuery"
            :destination-buffer-min="plan.destinationBufferMin || 30"
          />
          <el-card
            v-if="Number(day) < Number(plan.totalDays || 1)"
            class="accommodation-card"
            shadow="never"
          >
            <template #header>
              <div class="accommodation-header">
                <span>第 {{ day }} 晚住宿推荐</span>
                <el-tag size="small" effect="plain">OTA 实时价格</el-tag>
              </div>
            </template>
            <div class="accommodation-refresh-row">
              <el-button
                size="small"
                type="primary"
                plain
                :loading="refreshingAccommodations"
                @click="refreshAccommodations"
              >
                刷新价格
              </el-button>
            </div>
            <div v-if="accommodationsByNight[day]?.length" class="accommodation-list">
              <div v-for="item in accommodationsByNight[day]" :key="item.id || item.providerHotelId" class="accommodation-item">
                <div>
                  <strong>{{ item.name }}</strong>
                  <el-tag v-if="item.priceStale" class="stale-tag" size="small" type="warning" effect="plain">
                    价格可能已过期
                  </el-tag>
                  <div class="muted">{{ item.address || '地址暂缺' }}</div>
                  <div class="reason">{{ item.reason }}</div>
                </div>
                <div class="accommodation-score">
                  <span>￥{{ item.pricePerNightYuan }}</span>
                  <small>{{ item.rating ? `评分 ${item.rating}` : '评分暂缺' }}</small>
                  <small>{{ formatPriceTime(item.priceFetchedAt) }}</small>
                </div>
              </div>
            </div>
            <el-empty v-else description="本晚暂无 OTA 实时可订住宿推荐" />
          </el-card>
        </template>
      </template>
    </el-main>
  </el-container>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { getPlan, getPlanSteps, refreshPlanAccommodations } from '@/api/plans'
import { createPlanRouteMapStream, getPlanRouteMaps } from '@/api/routeMaps'
import { useAuthStore } from '@/stores/auth'
import PlanDayCard from '@/components/PlanDayCard.vue'
import PlanRouteMap from '@/components/PlanRouteMap.vue'

const route = useRoute()
const router = useRouter()
const planId = route.params.id
const auth = useAuthStore()

const plan = ref(null)
const steps = ref([])
const routeMapsByKey = ref({})
const loading = ref(true)
const refreshingAccommodations = ref(false)
let routeMapStream = null

const groupedSteps = computed(() => {
  const groups = {}
  for (const step of steps.value) {
    const day = step.dayNumber || 1
    if (!groups[day]) groups[day] = []
    groups[day].push(step)
  }
  return groups
})

const accommodationsByNight = computed(() => {
  const groups = {}
  for (const item of plan.value?.accommodations || []) {
    const night = item.nightNumber || 1
    if (!groups[night]) groups[night] = []
    groups[night].push(item)
  }
  return groups
})

const accommodationStatusText = computed(() => {
  switch (plan.value?.accommodationStatus) {
    case 'available':
      return '住宿已推荐'
    case 'not_configured':
      return '住宿未配置'
    case 'disabled':
      return '住宿已关闭'
    case 'unavailable':
      return '住宿暂不可用'
    default:
      return '住宿暂无推荐'
  }
})

const accommodationStatusTagType = computed(() => {
  switch (plan.value?.accommodationStatus) {
    case 'available':
      return 'success'
    case 'disabled':
      return 'info'
    case 'not_configured':
      return 'danger'
    default:
      return 'warning'
  }
})

const accommodationAlertType = computed(() => (
  plan.value?.accommodationStatus === 'disabled' ? 'info' : 'warning'
))

const hasStaleAccommodationPrices = computed(() => (
  (plan.value?.accommodations || []).some(item => item.priceStale)
))

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
    const [planRes, stepsRes, routeMapRes] = await Promise.all([
      getPlan(planId),
      getPlanSteps(planId),
      getPlanRouteMaps(planId)
    ])
    plan.value = planRes.data
    steps.value = stepsRes.data || []
    for (const item of routeMapRes.data?.items || []) {
      updateRouteMap(item)
    }
    openRouteMapStream()
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(() => {
  if (routeMapStream) {
    routeMapStream.close()
    routeMapStream = null
  }
})

function routeMapKey(day, style) {
  return `${day}:${style || 'anime_travel_map'}`
}

function updateRouteMap(item) {
  if (!item || !item.dayNumber) return
  routeMapsByKey.value = {
    ...routeMapsByKey.value,
    [routeMapKey(item.dayNumber, item.style)]: item
  }
}

function openRouteMapStream() {
  if (routeMapStream || !auth.token) return
  routeMapStream = createPlanRouteMapStream(planId, auth.token)
  routeMapStream.addEventListener('route_map_progress', event => {
    try {
      const payload = JSON.parse(event.data)
      if (Array.isArray(payload.items)) {
        payload.items.forEach(updateRouteMap)
      } else {
        updateRouteMap(payload)
      }
    } catch (err) {
      console.warn('route map stream parse failed', err)
    }
  })
  routeMapStream.onerror = () => {
    routeMapStream?.close()
    routeMapStream = null
  }
}

async function refreshAccommodations() {
  refreshingAccommodations.value = true
  try {
    const res = await refreshPlanAccommodations(planId)
    plan.value = res.data
    ElMessage.success('住宿价格已刷新')
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    refreshingAccommodations.value = false
  }
}

function formatTime(ts) {
  if (!ts) return '-'
  return ts instanceof Date ? ts.toLocaleString('zh-CN') : new Date(ts).toLocaleString('zh-CN')
}

function formatPriceTime(ts) {
  if (!ts) return '价格时间未知'
  return `价格 ${new Date(ts).toLocaleString('zh-CN')}`
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
.plan-meta { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
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
  border-radius: 8px;
  background: #f5f7fa;
  color: #606266;
}
.plan-summary { margin: 0; font-size: 14px; color: #606266; line-height: 1.7; }
.accommodation-card { margin: -8px 0 20px; }
.accommodation-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.accommodation-refresh-row {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}
.accommodation-list { display: grid; gap: 12px; }
.accommodation-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 140px;
  gap: 12px;
  padding: 12px;
  border: 1px solid #e5edf5;
  border-radius: 8px;
  background: #fbfdff;
}
.stale-tag { margin-left: 8px; }
.muted,
.reason,
.accommodation-score small {
  color: #6b7280;
  font-size: 12px;
}
.reason { margin-top: 6px; line-height: 1.5; }
.accommodation-score {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-end;
}
.accommodation-score span {
  color: #c2410c;
  font-weight: 700;
}
@media (max-width: 900px) {
  .plan-info-grid,
  .accommodation-item {
    grid-template-columns: 1fr;
  }
  .accommodation-score {
    align-items: flex-start;
  }
}
</style>
