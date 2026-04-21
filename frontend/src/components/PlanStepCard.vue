<template>
  <el-card class="step-card" shadow="never">
    <div class="step-header">
      <el-tag type="info" size="small">第{{ step.stepOrder + 1 }}站</el-tag>
      <span class="step-name">{{ step.attractionName }}</span>
      <span class="step-duration">约 {{ step.estimatedDurationMin }} 分钟</span>
    </div>
    <div v-if="step.trafficTimeFromPrevMin && step.stepOrder > 0" class="traffic-info">
      <el-icon><Van /></el-icon> 距上一站约 {{ step.trafficTimeFromPrevMin }} 分钟
    </div>
    <div v-if="step.llmDescription" class="step-desc">{{ step.llmDescription }}</div>
    <div v-if="step.weatherNote" class="weather-note">
      <el-icon><Sunny /></el-icon> {{ step.weatherNote }}
    </div>
  </el-card>
</template>

<script setup>
defineProps({ step: { type: Object, required: true } })
</script>

<style scoped>
.step-card { margin-bottom: 12px; border-left: 3px solid #409eff; }
.step-header { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.step-name { font-weight: 600; font-size: 15px; flex: 1; }
.step-duration { font-size: 12px; color: #909399; white-space: nowrap; }
.traffic-info { font-size: 12px; color: #909399; margin-bottom: 6px; display: flex; align-items: center; gap: 4px; }
.step-desc { font-size: 13px; color: #606266; line-height: 1.6; margin-bottom: 6px; }
.weather-note { font-size: 12px; color: #e6a23c; display: flex; align-items: center; gap: 4px; }
</style>
