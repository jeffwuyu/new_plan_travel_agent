<template>
  <div>
    <el-progress
      v-if="totalSteps > 0"
      :percentage="progressPercent"
      :status="progressStatus"
      class="mb-16"
    />
    <el-timeline v-if="events.length > 0">
      <el-timeline-item
        v-for="(ev, idx) in displayEvents"
        :key="idx"
        :type="eventType(ev.eventType)"
        :timestamp="formatTime(ev.createdAt)"
        placement="top"
      >
        <div class="event-message">{{ ev.message }}</div>
        <div v-if="ev.stepIndex != null" class="event-meta">步骤 {{ ev.stepIndex + 1 }}</div>
      </el-timeline-item>
    </el-timeline>
    <el-empty v-else description="暂无进度记录" :image-size="60" />
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  events: { type: Array, default: () => [] },
  currentStepIndex: { type: Number, default: 0 },
  totalSteps: { type: Number, default: 0 },
  taskStatus: { type: String, default: '' }
})

const displayEvents = computed(() => [...props.events].reverse())

const progressPercent = computed(() => {
  if (!props.totalSteps) return 0
  return Math.round((props.currentStepIndex / props.totalSteps) * 100)
})

const progressStatus = computed(() => {
  if (props.taskStatus === 'completed') return 'success'
  if (props.taskStatus === 'failed') return 'exception'
  return ''
})

const EVENT_TYPE_MAP = {
  STATE_CHANGE: 'primary',
  TOOL_START: 'warning',
  TOOL_DONE: 'success',
  STEP_DONE: 'success',
  ERROR: 'danger',
  RETRY: 'warning',
  PAUSED: 'info',
  COMPLETED: 'success'
}

function eventType(type) {
  return EVENT_TYPE_MAP[type] || 'primary'
}

function formatTime(ts) {
  if (!ts) return ''
  return new Date(ts).toLocaleTimeString('zh-CN')
}
</script>

<style scoped>
.mb-16 { margin-bottom: 16px; }
.event-message { font-size: 13px; color: #303133; }
.event-meta { font-size: 12px; color: #909399; margin-top: 2px; }
</style>
