<template>
  <el-container class="page-container">
    <el-header class="page-header">
      <el-button :icon="ArrowLeft" @click="router.push('/tasks')">返回任务列表</el-button>
      <div class="header-meta" v-if="task">
        <span class="page-title">对话式规划工作台</span>
        <TaskStatusBadge :status="displayStatus" />
      </div>
    </el-header>

    <el-main v-loading="loading">
      <template v-if="task">
        <section class="summary-panel">
          <div>
            <div class="summary-title">{{ task.region }}</div>
            <div class="summary-sub">
              当前位置：{{ task.selectedOrigin?.name || task.currentLocationQuery || '未提供' }}
            </div>
          </div>
          <div class="summary-right">
            <el-progress :percentage="progressPercent" :status="progressBarStatus" />
            <div class="summary-count">{{ task.currentStepIndex ?? 0 }} / {{ task.totalSteps ?? 0 }}</div>
          </div>
        </section>

        <section class="chat-shell">
          <div class="messages">
            <article class="message message-user">
              <div class="avatar">你</div>
              <div class="bubble">
                <div class="bubble-title">规划需求</div>
                <p>目的地：{{ task.region }}</p>
                <p>意图：{{ task.userIntent }}</p>
                <p>当前位置关键词：{{ task.currentLocationQuery }}</p>
              </div>
            </article>

            <article
              v-for="(item, index) in displayMessages"
              :key="`${item.eventType}-${item.createdAt || index}`"
              class="message"
              :class="item.role === 'user' ? 'message-user' : 'message-system'"
            >
              <div class="avatar">{{ item.role === 'user' ? '你' : '旅' }}</div>
              <div class="bubble">
                <div class="bubble-title">{{ item.title }}</div>
                <p v-if="item.text">{{ item.text }}</p>
                <div v-if="item.eventType === 'USER_SELECTION_REQUIRED'" class="candidate-list">
                  <el-alert
                    type="warning"
                    :closable="false"
                    show-icon
                    title="请选择当前起点，任务会从你选中的地点继续规划。"
                  />
                  <button
                    v-for="candidate in item.locationCandidates"
                    :key="candidate.candidateId"
                    class="candidate-card"
                    :disabled="submittingCandidateId === candidate.candidateId"
                    @click="handleSelectOrigin(candidate)"
                  >
                    <div class="candidate-name">{{ candidate.name }}</div>
                    <div class="candidate-meta">{{ candidate.region || task.region }}{{ candidate.district ? ` · ${candidate.district}` : '' }}</div>
                    <div class="candidate-meta">{{ candidate.address || candidate.category || '候选起点' }}</div>
                  </button>
                </div>
                <div v-if="item.eventType === 'USER_SELECTION_CONFIRMED' && item.selectedOrigin" class="origin-confirmed">
                  已选择起点：{{ item.selectedOrigin.name }}
                </div>
              </div>
            </article>

            <article v-if="llmTokenBuffer" class="message message-system">
              <div class="avatar">旅</div>
              <div class="bubble streaming">
                <div class="bubble-title">生成中的思路</div>
                <p>{{ llmTokenBuffer }}</p>
              </div>
            </article>
          </div>

          <aside class="sidebar">
            <el-card shadow="never">
              <template #header>任务状态</template>
              <div class="sidebar-item">
                <span>当前状态</span>
                <TaskStatusBadge :status="displayStatus" />
              </div>
              <div class="sidebar-item">
                <span>已用 Token</span>
                <strong>{{ task.totalTokensUsed ?? 0 }}</strong>
              </div>
              <div class="sidebar-item">
                <span>创建时间</span>
                <strong>{{ formatTime(task.createdAt) }}</strong>
              </div>
              <div class="sidebar-actions">
                <el-button
                  v-if="!isTerminal(displayStatus) && displayStatus !== 'paused'"
                  type="danger"
                  plain
                  @click="handleCancel"
                >取消任务</el-button>
                <el-button
                  v-if="displayStatus === 'paused'"
                  type="warning"
                  @click="handleResume"
                >恢复任务</el-button>
                <el-button
                  v-if="displayStatus === 'completed' && planId"
                  type="primary"
                  @click="router.push(`/plans/${planId}`)"
                >查看结果</el-button>
              </div>
            </el-card>

            <el-alert
              v-if="displayStatus === 'paused'"
              class="sidebar-alert"
              type="warning"
              :closable="false"
              title="任务因配额耗尽暂停。恢复后会从上次进度继续。"
            />
            <el-alert
              v-if="displayStatus === 'awaiting_user_input'"
              class="sidebar-alert"
              type="info"
              :closable="false"
              title="系统正在等待你确认起点。"
            />
            <el-alert
              v-if="task.errorMessage"
              class="sidebar-alert"
              type="error"
              :closable="false"
              :title="task.errorMessage"
            />
          </aside>
        </section>
      </template>
    </el-main>
  </el-container>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { cancelTask, confirmOriginSelection, getProgress, getTask, resumeTask } from '@/api/tasks'
import { getPlanByTask } from '@/api/plans'
import { useTaskStream } from '@/composables/useTaskStream'
import TaskStatusBadge from '@/components/TaskStatusBadge.vue'

const route = useRoute()
const router = useRouter()
const uuid = route.params.uuid

const task = ref(null)
const pollEvents = ref([])
const planId = ref(null)
const loading = ref(true)
const submittingCandidateId = ref('')

const TERMINAL = new Set(['completed', 'failed', 'cancelled'])
const isTerminal = (s) => TERMINAL.has(s)

const {
  events: streamEvents,
  currentStatus: liveStatus,
  llmTokenBuffer,
  connect,
  disconnect
} = useTaskStream(uuid)

const displayStatus = computed(() => liveStatus.value || task.value?.status || 'pending')

const mergedEvents = computed(() => {
  const source = streamEvents.value.length > 0 ? streamEvents.value : pollEvents.value
  return [...source]
    .map(normalizeEvent)
    .sort((a, b) => new Date(a.createdAt || 0) - new Date(b.createdAt || 0))
})

const displayMessages = computed(() => mergedEvents.value.map(toMessage))

const progressPercent = computed(() => {
  const total = task.value?.totalSteps || 0
  const current = task.value?.currentStepIndex || 0
  if (!total) return 0
  return Math.min(100, Math.round((current / total) * 100))
})

const progressBarStatus = computed(() => {
  if (displayStatus.value === 'completed') return 'success'
  if (displayStatus.value === 'failed') return 'exception'
  return ''
})

let pollTimer = null

onMounted(async () => {
  await fetchAll()
  if (!isTerminal(task.value?.status)) {
    connect()
  }
  schedulePoll()
})

onUnmounted(() => {
  clearInterval(pollTimer)
  disconnect()
})

watch(liveStatus, async (status) => {
  if (status && task.value) {
    task.value.status = status
  }
  if (status === 'completed' && !planId.value) {
    await fetchPlanId()
  }
})

async function fetchAll() {
  try {
    const [taskRes, progRes] = await Promise.all([
      getTask(uuid),
      getProgress(uuid, 100)
    ])
    task.value = taskRes.data
    pollEvents.value = progRes.data?.events || []

    if (task.value?.status === 'completed') {
      await fetchPlanId()
    }
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}

function schedulePoll() {
  pollTimer = setInterval(async () => {
    if (isTerminal(displayStatus.value)) return
    try {
      const [taskRes, progRes] = await Promise.all([
        getTask(uuid),
        getProgress(uuid, 100)
      ])
      task.value = taskRes.data
      if (!streamEvents.value.length) {
        pollEvents.value = progRes.data?.events || []
      }
    } catch {
      // keep the conversation surface stable even if polling hiccups once
    }
  }, 8000)
}

async function handleCancel() {
  await ElMessageBox.confirm('确认取消这个规划任务？', '提示', { type: 'warning' })
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

async function handleSelectOrigin(candidate) {
  submittingCandidateId.value = candidate.candidateId
  try {
    const res = await confirmOriginSelection(uuid, {
      selectedCandidateId: candidate.candidateId,
      selectedCandidateName: candidate.name,
      selectedLat: candidate.latitude,
      selectedLng: candidate.longitude
    })
    task.value = res.data
    ElMessage.success(`已选择 ${candidate.name} 作为起点`)
    connect()
    await fetchAll()
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    submittingCandidateId.value = ''
  }
}

async function fetchPlanId() {
  try {
    const planRes = await getPlanByTask(uuid)
    planId.value = planRes.data?.id
  } catch {
    // ignore
  }
}

function normalizeEvent(ev) {
  const details = safeParse(ev.detailsJson)
  return {
    ...ev,
    ...details,
    createdAt: ev.createdAt || new Date().toISOString(),
    locationCandidates: ev.locationCandidates || details.locationCandidates || [],
    selectedOrigin: ev.selectedOrigin || details.selectedOrigin || null,
    text: ev.message || details.message || '',
    status: ev.status || details.status || ''
  }
}

function toMessage(ev) {
  const mapping = {
    STATE_CHANGE: { title: '状态更新', text: stateText(ev.status) || ev.text || '任务状态已更新' },
    STEP_DONE: { title: `步骤 ${Number(ev.stepIndex ?? 0) + 1} 已完成`, text: ev.attractionName ? `已确定景点：${ev.attractionName}` : ev.text },
    TOOL_RESULT: { title: '工具结果', text: ev.text || '已获取地理位置、天气或路程信息' },
    RETRY: { title: '重试中', text: ev.text || '系统正在重试当前步骤' },
    PAUSED: { title: '任务暂停', text: '当前任务因配额限制暂停，稍后可继续。' },
    USER_SELECTION_REQUIRED: { title: '请选择起点', text: `关键词“${ev.currentLocationQuery || task.value?.currentLocationQuery || ''}”已生成候选地点。` },
    USER_SELECTION_CONFIRMED: { title: '起点已确认', text: ev.selectedOrigin?.name ? `已使用 ${ev.selectedOrigin.name} 作为起点，继续规划中。` : '起点已确认。' },
    COMPLETED: { title: '规划完成', text: '路线已生成，可以查看最终结果。' },
    ERROR: { title: '任务失败', text: ev.message || ev.text || '任务执行失败。' }
  }
  const picked = mapping[ev.eventType] || { title: ev.eventType, text: ev.text }
  return {
    ...ev,
    role: ev.eventType === 'USER_SELECTION_CONFIRMED' ? 'user' : 'system',
    title: picked.title,
    text: picked.text
  }
}

function stateText(status) {
  const mapping = {
    pending: '任务已创建，等待开始',
    planning: '系统正在生成路线',
    tool_calling: '系统正在补充地图和天气信息',
    awaiting_user_input: '系统正在等待你选择起点',
    paused: '任务已暂停',
    resuming: '任务已恢复，继续执行中',
    completed: '任务已完成',
    failed: '任务失败',
    cancelled: '任务已取消'
  }
  return mapping[status] || ''
}

function safeParse(text) {
  if (!text) return {}
  try {
    return JSON.parse(text)
  } catch {
    return {}
  }
}

function formatTime(ts) {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}
</script>

<style scoped>
.page-container {
  min-height: 100vh;
  background:
    radial-gradient(circle at top left, rgba(201, 226, 255, 0.8), transparent 32%),
    linear-gradient(180deg, #f8fbff 0%, #eef3f7 100%);
}

.page-header {
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid #d7e2ec;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 24px;
}

.header-meta {
  display: flex;
  align-items: center;
  gap: 12px;
}

.page-title {
  font-size: 18px;
  font-weight: 700;
}

.summary-panel {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 20px;
  padding: 22px 24px;
  border-radius: 22px;
  background: linear-gradient(135deg, #0d3b66 0%, #1f6aa5 100%);
  color: #fff;
}

.summary-title {
  font-size: 26px;
  font-weight: 700;
}

.summary-sub {
  margin-top: 8px;
  color: rgba(255, 255, 255, 0.8);
}

.summary-right {
  min-width: 260px;
}

.summary-count {
  margin-top: 10px;
  text-align: right;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.85);
}

.chat-shell {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 20px;
}

.messages {
  min-height: 70vh;
  padding: 24px;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.85);
  border: 1px solid #d9e4ee;
}

.message {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  margin-bottom: 18px;
}

.message-user {
  flex-direction: row-reverse;
}

.avatar {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 13px;
  font-weight: 700;
  background: #0d3b66;
  color: #fff;
}

.message-user .avatar {
  background: #f4a261;
}

.bubble {
  max-width: 78%;
  padding: 16px 18px;
  border-radius: 18px;
  background: #f4f8fb;
  color: #243b53;
  box-shadow: inset 0 0 0 1px #e1ebf3;
}

.message-user .bubble {
  background: #fff2df;
  box-shadow: inset 0 0 0 1px #ffd8a8;
}

.bubble-title {
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 700;
}

.bubble p {
  margin: 0;
  line-height: 1.65;
  white-space: pre-wrap;
}

.streaming {
  background: #eef8ff;
}

.candidate-list {
  margin-top: 12px;
}

.candidate-card {
  width: 100%;
  margin-top: 10px;
  padding: 14px 16px;
  border: 1px solid #d5e2ee;
  border-radius: 16px;
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition: transform 0.18s ease, border-color 0.18s ease;
}

.candidate-card:hover {
  transform: translateY(-1px);
  border-color: #1f6aa5;
}

.candidate-card:disabled {
  cursor: wait;
  opacity: 0.75;
}

.candidate-name {
  font-size: 15px;
  font-weight: 700;
}

.candidate-meta {
  margin-top: 4px;
  font-size: 13px;
  color: #52667a;
}

.origin-confirmed {
  margin-top: 8px;
  font-weight: 600;
  color: #0d3b66;
}

.sidebar {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.sidebar-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
  color: #486581;
}

.sidebar-actions {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.sidebar-alert {
  border-radius: 16px;
}

@media (max-width: 960px) {
  .chat-shell {
    grid-template-columns: 1fr;
  }

  .summary-panel {
    flex-direction: column;
  }

  .bubble {
    max-width: 100%;
  }
}
</style>
