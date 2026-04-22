<template>
  <el-card class="step-card" shadow="never">
    <div class="step-header">
      <el-tag type="info" size="small">第 {{ step.stepOrder + 1 }} 站</el-tag>
      <span class="step-name">{{ step.attractionName }}</span>
      <span class="step-duration">约 {{ step.estimatedDurationMin || 90 }} 分钟</span>
    </div>

    <div v-if="step.plannedStartTime || step.plannedEndTime" class="time-row">
      预计时间：{{ formatTime(step.plannedStartTime) }} - {{ formatTime(step.plannedEndTime) }}
    </div>

    <div v-if="step.trafficTimeFromPrev && step.stepOrder > 0" class="traffic-info">
      距上一站约 {{ step.trafficTimeFromPrev }} 分钟
    </div>

    <div v-if="step.llmDescription" class="step-desc">{{ step.llmDescription }}</div>

    <div v-if="step.weatherNote" class="weather-note">
      天气：{{ step.weatherNote }}
    </div>

    <div v-if="step.travelTimeToDestinationMin" class="destination-note">
      收尾预留：从本景点前往 {{ destinationName || '结束位置' }} 约 {{ step.travelTimeToDestinationMin }} 分钟，另预留 {{ destinationBufferMin }} 分钟缓冲。
    </div>
  </el-card>
</template>

<script setup>
defineProps({
  step: { type: Object, required: true },
  destinationName: { type: String, default: '' },
  destinationBufferMin: { type: Number, default: 30 }
})

function formatTime(ts) {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}
</script>

<style scoped>
.step-card { margin-bottom: 12px; border-left: 3px solid #409eff; }
.step-header { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.step-name { font-weight: 600; font-size: 15px; flex: 1; }
.step-duration { font-size: 12px; color: #909399; white-space: nowrap; }
.time-row,
.traffic-info,
.step-desc,
.weather-note,
.destination-note {
  font-size: 13px;
  line-height: 1.6;
  margin-bottom: 6px;
}
.traffic-info { color: #909399; }
.step-desc { color: #606266; }
.weather-note { color: #e6a23c; }
.destination-note {
  color: #355070;
  background: #eef8ff;
  padding: 8px 10px;
  border-radius: 10px;
}
</style>
