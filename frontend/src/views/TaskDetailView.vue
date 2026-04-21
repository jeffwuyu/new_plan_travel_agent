<template>
  <el-container class="page-container">
    <el-header class="page-header">
      <el-button :icon="ArrowLeft" @click="router.push('/tasks')">返回任务列表</el-button>
      <span class="page-title">任务详情</span>
    </el-header>

    <el-main v-loading="loading">
      <template v-if="task">
        <el-descriptions :column="3" border class="mb-20">
          <el-descriptions-item label="目的地">{{ task.region }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <TaskStatusBadge :status="liveStatus || task.status" />
          </el-descriptions-item>
          <el-descriptions-item label="Token 消耗">{{ task.totalTokensUsed ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="步骤进度">
            {{ task.currentStepIndex ?? 0 }} / {{ task.totalSteps ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(task.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="完成时间">{{ task.completedAt ? formatTime(task.completedAt) : '—' }}</el-descriptions-item>
        </el-descriptions>

        <div class="action-bar mb-20">
          <el-button
            v-if="!isTerminal(liveStatus || task.status) && (liveStatus || task.status) !== 'paused'"
            type="danger" size="small"
            @click="handleCancel"
          >取消任务</el-button>
          <el-button
            v-if="(liveStatus || task.status) === 'paused'"
            type="warning" size="small"
            @click="handleResume"
          >恢复任务</el-button>
          <el-button
            v-if="(liveStatus || task.status) === 'completed'"
            type="primary" size="small"
            @click="router.push(`/plans/${planId}`)"
            :disabled="!planId"
          >查看规划结果</el-button>
        </div>

        <!-- 配额暂停提示 -->
        <el-alert
          v-if="(liveStatus || task.status) === 'paused'"
          type="warning"
          title="任务已暂停：Token 配额耗尽，请等待次日重置或联系管理员提升配额"
          show-icon
          class="mb-20"
        />

        <!-- LLM 实时流输出 -->
        <LlmStreamOutput v-if="llmTokenBuffer" :tokens="llmTokenBuffer" class="mb-20" />

        <!-- 进度日志 -->
        <el-card header="执行进度">
          <ProgressLog
            :events="mergedEvents"
            :current-step-index="task.currentStepIndex ?? 0"
            :total-steps="task.totalSteps ?? 0"
            :task-status="liveStatus || task.status"
          />
        </el-card>

        <el-alert
          v-if="task.errorMessage"
          type="error"
          :title="task.errorMessage"
          show-icon
          class="mt-20"
        />
      </template>
    </el-main>
  </el-container>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { getTask, getProgress, cancelTask, resumeTask } from '@/api/tasks'
import { getPlanByTask } from '@/api/plans'
import { useTaskStream } from '@/composables/useTaskStream'
import TaskStatusBadge from '@/components/TaskStatusBadge.vue'
import ProgressLog from '@/components/ProgressLog.vue'
import LlmStreamOutput from '@/components/LlmStreamOutput.vue'

const route = useRoute()
const router = useRouter()
const uuid = route.params.uuid

const task = ref(null)
const pollEvents = ref([])   // events from REST polling
const planId = ref(null)
const loading = ref(true)

const TERMINAL = new Set(['completed', 'failed', 'cancelled'])
const isTerminal = (s) => TERMINAL.has(s)

// SSE stream
const { events: streamEvents, currentStatus: liveStatus, llmTokenBuffer, isStreaming, connect, disconnect } = useTaskStream(uuid)

// Merge SSE events (prepend) with polled events (fallback)
const mergedEvents = computed(() => {
  if (streamEvents.value.length > 0) return streamEvents.value
  return pollEvents.value
})

let pollTimer = null

onMounted(async () => {
  await fetchAll()
  // Start SSE stream for non-terminal tasks
  if (task.value && !isTerminal(task.value.status)) {
    connect()
  }
  schedulePoll()
})

onUnmounted(() => {
  clearInterval(pollTimer)
  disconnect()
})

// When SSE reports completed, load plan info
watch(liveStatus, async (status) => {
  if (status === 'completed' && !planId.value) {
    try {
      const planRes = await getPlanByTask(uuid)
      planId.value = planRes.data?.id
    } catch { /* ignore */ }
  }
})

async function fetchAll() {
  try {
    const [taskRes, progRes] = await Promise.all([
      getTask(uuid),
      getProgress(uuid, 50)
    ])
    task.value = taskRes.data
    pollEvents.value = progRes.data?.events || []

    if (task.value?.status === 'completed' && !planId.value) {
      try {
        const planRes = await getPlanByTask(uuid)
        planId.value = planRes.data?.id
      } catch { /* ignore */ }
    }
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}

function schedulePoll() {
  // Light poll — only refresh task meta (not events) when SSE is active
  pollTimer = setInterval(async () => {
    const status = liveStatus.value || task.value?.status
    if (status && !isTerminal(status)) {
      try {
        const res = await getTask(uuid)
        task.value = res.data
      } catch { /* ignore */ }
    }
  }, 10000)
}

async function handleCancel() {
  await ElMessageBox.confirm('确认取消该规划任务？', '提示', { type: 'warning' })
  try {
    await cancelTask(uuid)
    ElMessage.success('任务已取消')
    disconnect()
    await fetchAll()
  } catch (err) {
    ElMessage.error(err.message)
  }
}

async function handleResume() {
  try {
    await resumeTask(uuid)
    ElMessage.success('任务已恢复')
    connect()
    await fetchAll()
  } catch (err) {
    ElMessage.error(err.message)
  }
}

function formatTime(ts) {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
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
.mt-20 { margin-top: 20px; }
.action-bar { display: flex; gap: 8px; }
</style>
