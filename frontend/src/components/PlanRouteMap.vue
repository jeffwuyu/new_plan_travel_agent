<template>
  <el-card class="route-map-card" shadow="never">
    <template #header>
      <div class="route-map-header">
        <div>
          <strong>第 {{ dayNumber }} 天路线图</strong>
          <span class="status-text">{{ statusText }}</span>
        </div>
        <div class="route-map-actions">
          <el-select v-model="selectedStyle" size="small" class="style-select" @change="loadMap">
            <el-option label="动漫风" value="anime_travel_map" />
            <el-option label="手账风" value="journal_travel_map" />
            <el-option label="水彩风" value="watercolor_travel_map" />
          </el-select>
          <el-button size="small" :disabled="isRunning" :loading="generating" @click="generate(false)">生成</el-button>
          <el-button
            size="small"
            type="primary"
            :disabled="isRunning || !canRegenerate"
            :loading="generating"
            @click="generate(true)"
          >
            重新生成
          </el-button>
          <span v-if="manualRegenText" class="retry-text">{{ manualRegenText }}</span>
        </div>
      </div>
    </template>

    <div v-if="isRunning" class="route-map-progress">
      <el-progress :percentage="routeMap.progressPercent || 0" />
      <span>{{ statusText }}</span>
    </div>

    <div v-else-if="displayImage" class="route-map-image-wrap">
      <el-image
        class="route-map-image"
        :src="displayImage"
        fit="contain"
        :preview-src-list="[displayImage]"
        preview-teleported
      />
      <el-alert
        v-if="routeMap.status === 'fallback'"
        class="route-map-alert"
        type="warning"
        :closable="false"
        :title="fallbackTitle"
        :description="failureDescription"
      />
    </div>

    <el-alert
      v-else-if="routeMap.status === 'failed'"
      type="error"
      :closable="false"
      :title="failureTitle"
      :description="failureDescription"
    />

    <el-empty v-else description="路线图尚未生成" />

    <div v-if="routeMap.stops?.length" class="guide-list">
      <div v-for="stop in routeMap.stops" :key="stop.stepId || stop.order" class="guide-item">
        <span class="guide-index">{{ stop.order }}</span>
        <div>
          <strong>{{ stop.name }}</strong>
          <p>{{ stop.introduction || '暂无景点讲解' }}</p>
        </div>
      </div>
    </div>
  </el-card>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { generatePlanRouteMap, getPlanRouteMap } from '@/api/routeMaps'

const props = defineProps({
  planId: { type: [String, Number], required: true },
  dayNumber: { type: Number, required: true },
  initialRouteMap: { type: Object, default: null }
})

const emit = defineEmits(['updated'])

const selectedStyle = ref(props.initialRouteMap?.style || 'anime_travel_map')
const routeMap = ref(props.initialRouteMap || {
  status: 'not_generated',
  progressPercent: 0,
  style: selectedStyle.value,
  stops: [],
  segments: []
})
const generating = ref(false)

watch(() => props.initialRouteMap, value => {
  if (value && value.style === selectedStyle.value) {
    routeMap.value = value
  }
})

const isRunning = computed(() => [
  'pending',
  'generating',
  'geometry_ready',
  'skeleton_ready',
  'ai_submitted',
  'ai_generating',
  'ai_ready',
  'overlaying'
].includes(routeMap.value.status))

const displayImage = computed(() => routeMap.value.imageUrl || routeMap.value.fallbackImageUrl)

const manualRegenRemaining = computed(() => {
  const value = Number(routeMap.value.manualRegenerateRemaining)
  return Number.isFinite(value) ? Math.max(0, value) : null
})

const manualRegenLimit = computed(() => {
  const value = Number(routeMap.value.manualRegenerateLimit)
  return Number.isFinite(value) ? Math.max(0, value) : null
})

const canRegenerate = computed(() => manualRegenRemaining.value === null || manualRegenRemaining.value > 0)

const manualRegenText = computed(() => {
  if (manualRegenRemaining.value === null) {
    return ''
  }
  if (manualRegenLimit.value === null) {
    return `剩余重试 ${manualRegenRemaining.value} 次`
  }
  return `剩余重试 ${manualRegenRemaining.value}/${manualRegenLimit.value}`
})

const statusText = computed(() => {
  const map = {
    not_generated: '尚未生成',
    pending: '等待生成',
    generating: '正在生成',
    geometry_ready: '路线已解析',
    skeleton_ready: '骨架图已生成',
    ai_generating: 'AI 美化中',
    succeeded: '已完成',
    fallback: '基础图可用',
    failed: '生成失败'
  }
  return map[routeMap.value.status] || routeMap.value.status || ''
})

const fallbackTitle = computed(() => {
  if (routeMap.value.errorCode) {
    return `AI 美化暂不可用（${routeMap.value.errorCode}）`
  }
  return 'AI 美化暂不可用，已展示可信基础路线图'
})

const failureTitle = computed(() => {
  if (routeMap.value.errorCode) {
    return `路线图生成失败（${routeMap.value.errorCode}）`
  }
  return routeMap.value.errorMessage || '路线图生成失败'
})

const failureDescription = computed(() => {
  const parts = []
  if (routeMap.value.errorMessage) {
    parts.push(routeMap.value.errorMessage)
  }
  if (manualRegenText.value) {
    parts.push(manualRegenText.value)
  }
  return parts.join('；')
})

async function loadMap() {
  try {
    const res = await getPlanRouteMap(props.planId, props.dayNumber, selectedStyle.value)
    routeMap.value = res.data
    emit('updated', res.data)
  } catch (err) {
    ElMessage.error(err.message)
  }
}

async function generate(force) {
  generating.value = true
  try {
    const res = await generatePlanRouteMap(
      props.planId,
      props.dayNumber,
      { style: selectedStyle.value },
      force
    )
    routeMap.value = res.data
    emit('updated', res.data)
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    generating.value = false
  }
}
</script>

<style scoped>
.route-map-card { margin-bottom: 20px; }
.route-map-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.status-text { margin-left: 10px; color: #6b7280; font-size: 13px; }
.route-map-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.style-select { width: 118px; }
.retry-text { color: #6b7280; font-size: 12px; white-space: nowrap; }
.route-map-progress { display: grid; gap: 8px; color: #606266; }
.route-map-image-wrap { display: grid; gap: 12px; }
.route-map-image {
  width: 100%;
  max-height: 560px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
}
.route-map-alert { margin-top: 4px; }
.guide-list {
  display: grid;
  gap: 10px;
  margin-top: 14px;
}
.guide-item {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 10px;
  padding: 10px 0;
  border-top: 1px solid #eef2f7;
}
.guide-index {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #1d4ed8;
  color: #fff;
  font-weight: 700;
}
.guide-item p {
  margin: 4px 0 0;
  color: #606266;
  line-height: 1.6;
}
</style>
