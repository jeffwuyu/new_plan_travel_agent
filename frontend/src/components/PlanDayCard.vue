<template>
  <el-card class="day-card" shadow="hover">
    <template #header>
      <div class="day-header">
        <el-tag type="primary">第 {{ dayNumber }} 天</el-tag>
        <span class="day-count">{{ steps.length }} 个景点</span>
      </div>
    </template>
    <PlanStepCard v-for="step in steps" :key="step.id" :step="step" />
  </el-card>
</template>

<script setup>
import PlanStepCard from './PlanStepCard.vue'
defineProps({
  dayNumber: { type: Number, required: true },
  steps: { type: Array, required: true }
})
</script>

<style scoped>
.day-card { margin-bottom: 20px; }
.day-header { display: flex; align-items: center; gap: 10px; }
.day-count { font-size: 13px; color: #909399; }
</style>
