<template>
  <el-container class="page-container">
    <el-header class="page-header">
      <el-button :icon="ArrowLeft" @click="router.push('/tasks')">返回任务列表</el-button>
      <div v-if="task" class="header-meta">
        <span class="page-title">规划执行详情</span>
        <TaskStatusBadge :status="displayStatus" />
      </div>
    </el-header>

    <el-main v-loading="loading">
      <template v-if="task">
        <section class="summary-panel">
          <div>
            <div class="summary-title">{{ task.region }}</div>
            <div class="summary-sub">起点：{{ task.selectedOrigin?.name || task.startLocationQuery || '未提供' }}</div>
            <div class="summary-sub">终点：{{ task.selectedDestination?.name || task.endLocationQuery || '未提供' }}</div>
            <div class="summary-sub">时间范围：{{ formatTime(task.tripStartTime) }} - {{ formatTime(task.tripEndTime) }}</div>
          </div>
          <div class="summary-right">
            <el-progress :percentage="progressPercent" :status="progressBarStatus" />
            <div class="summary-count">{{ task.currentStepIndex ?? 0 }} / {{ task.totalSteps ?? 0 }}</div>
            <div class="summary-budget">剩余时间预算：{{ task.remainingTimeBudgetMin ?? 0 }} 分钟</div>
          </div>
        </section>

        <section class="window-panel">
          <el-card shadow="never">
            <template #header>时间窗配置</template>
            <div class="window-item">
              <span>完整天默认时间</span>
              <strong>{{ formatFullDayWindow(task) }}</strong>
            </div>
            <div class="window-item">
              <span>终点缓冲</span>
              <strong>至少 30 分钟</strong>
            </div>
            <div class="window-item">
              <span>当前预计回到终点耗时</span>
              <strong>{{ task.projectedReturnToDestinationMin ?? 0 }} 分钟</strong>
            </div>
            <div v-if="task.dailyTimeWindows?.length" class="window-list">
              <div v-for="window in task.dailyTimeWindows" :key="window.dayNumber" class="window-chip">
                第 {{ window.dayNumber }} 天：{{ formatTime(window.startTime) }} - {{ formatTime(window.endTime) }}
              </div>
            </div>
          </el-card>
        </section>

        <section class="chat-shell">
          <div class="messages">
            <article class="message message-user">
              <div class="avatar">我</div>
              <div class="bubble">
                <div class="bubble-title">规划需求</div>
                <p>目的地：{{ task.region }}</p>
                <p>起点：{{ task.startLocationQuery }}</p>
                <p>终点：{{ task.endLocationQuery }}</p>
                <p>开始时间：{{ formatTime(task.tripStartTime) }}</p>
                <p>结束时间：{{ formatTime(task.tripEndTime) }}</p>
                <p>意图：{{ task.userIntent }}</p>
              </div>
            </article>

            <article
              v-for="(item, index) in displayMessages"
              :key="`${item.eventType}-${item.createdAt || index}`"
              class="message"
              :class="item.role === 'user' ? 'message-user' : 'message-system'"
            >
              <div class="avatar">{{ item.role === 'user' ? '我' : '系统' }}</div>
              <div class="bubble">
                <div class="bubble-title">{{ item.title }}</div>
                <p v-if="item.text">{{ item.text }}</p>

                <div v-if="item.eventType === 'USER_SELECTION_REQUIRED'" class="candidate-list">
                  <el-alert
                    :title="selectionAlertTitle(item)"
                    :type="item.pendingInputType === 'attraction_selection' ? 'info' : 'warning'"
                    :closable="false"
                    show-icon
                  />

                  <div v-if="item.pendingInputType === 'attraction_selection' && item.currentContext" class="context-panel">
                    <div class="context-grid">
                      <div class="context-item">
                        <span>当前所在</span>
                        <strong>{{ item.currentContext.currentPositionName || '-' }}</strong>
                      </div>
                      <div class="context-item">
                        <span>第几天</span>
                        <strong>第 {{ Number(item.currentContext.dayNumber || 1) }} 天</strong>
                      </div>
                      <div class="context-item">
                        <span>剩余预算</span>
                        <strong>{{ Number(item.currentContext.remainingTimeBudgetMin || 0) }} 分钟</strong>
                      </div>
                      <div class="context-item">
                        <span>终点约束</span>
                        <strong>{{ item.currentContext.destinationName || task.endLocationQuery || '-' }}</strong>
                      </div>
                    </div>
                  </div>

                  <button
                    v-for="candidate in item.recommendationCandidates"
                    :key="candidate.candidateId"
                    class="candidate-card"
                    :disabled="submittingCandidateId === candidate.candidateId"
                    @click="handleSelectCandidate(item.pendingInputType, candidate)"
                  >
                    <div class="candidate-card-header">
                      <div>
                        <div class="candidate-name">{{ candidate.name }}</div>
                        <div class="candidate-meta">
                          {{ candidate.region || task.region }}{{ candidate.district ? ` / ${candidate.district}` : '' }}
                        </div>
                      </div>
                      <div v-if="candidate.score != null" class="candidate-score">
                        匹配度 {{ formatScore(candidate.score) }}
                      </div>
                    </div>

                    <div class="candidate-meta">{{ candidate.category || '景点候选' }}</div>
                    <div v-if="candidate.address" class="candidate-meta">{{ candidate.address }}</div>

                    <div v-if="candidate.routeSummary" class="candidate-route">
                      推荐路线：{{ candidate.routeSummary }}
                    </div>

                    <div v-if="candidate.visitDurationMin != null" class="candidate-duration">
                      预计游玩时长：约 {{ candidate.visitDurationMin }} 分钟
                    </div>

                    <div v-if="candidate.highlights?.length" class="candidate-section">
                      <div class="candidate-section-title">推荐玩点</div>
                      <div class="candidate-tags">
                        <span v-for="(highlight, highlightIndex) in candidate.highlights" :key="`${candidate.candidateId}-h-${highlightIndex}`" class="candidate-tag">
                          {{ highlight }}
                        </span>
                      </div>
                    </div>

                    <div v-if="candidate.explanations?.length" class="candidate-section">
                      <div class="candidate-section-title">推荐理由</div>
                      <ul class="candidate-reasons">
                        <li v-for="(reason, reasonIndex) in candidate.explanations" :key="`${candidate.candidateId}-r-${reasonIndex}`">
                          {{ reason }}
                        </li>
                      </ul>
                    </div>
                  </button>
                </div>

                <div v-if="item.eventType === 'USER_SELECTION_CONFIRMED'" class="origin-confirmed">
                  {{ selectionConfirmedText(item) }}
                </div>
              </div>
            </article>

            <article v-if="llmTokenBuffer" class="message message-system">
              <div class="avatar">系统</div>
              <div class="bubble streaming">
                <div class="bubble-title">规划生成中</div>
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
                <strong>{{ formatQuotaValue(displayTokens) }}</strong>
              </div>
              <div class="sidebar-item">
                <span>已用时间预算</span>
                <strong>{{ task.usedTimeBudgetMin ?? 0 }} 分钟</strong>
              </div>
              <div class="sidebar-item">
                <span>剩余时间预算</span>
                <strong>{{ task.remainingTimeBudgetMin ?? 0 }} 分钟</strong>
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
                >
                  取消任务
                </el-button>
                <el-button
                  v-if="displayStatus === 'paused'"
                  type="warning"
                  @click="handleResume"
                >
                  恢复任务
                </el-button>
                <el-button
                  v-if="displayStatus === 'completed' && planId"
                  type="primary"
                  @click="router.push(`/plans/${planId}`)"
                >
                  查看结果
                </el-button>
              </div>
            </el-card>

            <el-alert
              v-if="displayStatus === 'paused'"
              class="sidebar-alert"
              type="warning"
              :closable="false"
              :title="pausedAlertTitle"
            />
            <el-alert
              v-if="displayStatus === 'awaiting_user_input'"
              class="sidebar-alert"
              type="info"
              :closable="false"
              :title="awaitingInputTitle"
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
import { useAuthStore } from '@/stores/auth'
import { useTaskStream } from '@/composables/useTaskStream'
import TaskStatusBadge from '@/components/TaskStatusBadge.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const uuid = route.params.uuid

const task = ref(null)
const pollEvents = ref([])
const planId = ref(null)
const loading = ref(true)
const submittingCandidateId = ref('')

const TERMINAL = new Set(['completed', 'failed', 'cancelled'])
const isTerminal = (status) => TERMINAL.has(status)

const {
  events: streamEvents,
  currentStatus: liveStatus,
  llmTokenBuffer,
  currentTokens,
  connect,
  disconnect
} = useTaskStream(uuid)

const displayStatus = computed(() => liveStatus.value || task.value?.status || 'pending')
const displayTokens = computed(() => {
  const streamValue = Number(currentTokens.value ?? 0)
  if (streamValue > 0) return streamValue
  return Number(task.value?.totalTokensUsed ?? 0)
})

const mergedEvents = computed(() => {
  const source = streamEvents.value.length > 0 ? streamEvents.value : pollEvents.value
  return [...source]
    .map(normalizeEvent)
    .sort((a, b) => new Date(a.createdAt || 0) - new Date(b.createdAt || 0))
})

const displayMessages = computed(() => mergedEvents.value.map(toMessage))
const awaitingInputTitle = computed(() => {
  const pendingType = task.value?.pendingInputType
  if (pendingType === 'attraction_selection') {
    return '系统正在等待你确认下一站景点候选。'
  }
  return '系统正在等待你确认起点位置。'
})

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

const pausedAlertTitle = computed(() => {
  if (task.value?.pauseReason === 'amap_rate_limited') {
    return '地图接口限流，任务已暂时暂停，可稍后恢复。'
  }
  return '任务因配额耗尽暂停，恢复后会从当前进度继续。'
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
    auth.refreshQuota().catch(() => null)
    task.value = taskRes.data
    if (progRes.data?.totalTokensUsed != null) {
      task.value.totalTokensUsed = Number(progRes.data.totalTokensUsed || 0)
    }
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
      auth.refreshQuota().catch(() => null)
      task.value = taskRes.data
      if (progRes.data?.totalTokensUsed != null) {
        task.value.totalTokensUsed = Number(progRes.data.totalTokensUsed || 0)
      }
      if (!streamEvents.value.length) {
        pollEvents.value = progRes.data?.events || []
      }
    } catch {
      // ignore single poll failures
    }
  }, 8000)
}

async function handleCancel() {
  await ElMessageBox.confirm('确认取消这个规划任务吗？', '提示', { type: 'warning' })
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

async function handleSelectCandidate(pendingInputType, candidate) {
  submittingCandidateId.value = candidate.candidateId
  try {
    const res = await confirmOriginSelection(uuid, {
      pendingInputType,
      selectedCandidateId: candidate.candidateId,
      selectedCandidateName: candidate.name,
      selectedLat: candidate.latitude,
      selectedLng: candidate.longitude
    })
    task.value = res.data
    ElMessage.success(
      pendingInputType === 'attraction_selection'
        ? `已选择 ${candidate.name} 作为下一站景点`
        : `已选择 ${candidate.name} 作为起点`
    )
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
    planId.value = planRes.data?.plan?.id || planRes.data?.id
  } catch {
    // ignore
  }
}

function normalizeEvent(ev) {
  const details = safeParse(ev.detailsJson)
  const pendingInputType = ev.pendingInputType || details.pendingInputType || ''
  const recommendationCandidates = ev.recommendationCandidates
    || details.recommendationCandidates
    || details.locationCandidates
    || ev.locationCandidates
    || []

  return {
    ...ev,
    ...details,
    createdAt: ev.createdAt || new Date().toISOString(),
    pendingInputType,
    recommendationCandidates,
    locationCandidates: ev.locationCandidates || details.locationCandidates || [],
    currentContext: ev.currentContext || details.currentContext || {},
    selectedOrigin: ev.selectedOrigin || details.selectedOrigin || null,
    selectedCandidate: ev.selectedCandidate || details.selectedCandidate || null,
    text: ev.message || details.message || '',
    status: ev.status || details.status || ''
  }
}

function toMessage(ev) {
  const mapping = {
    STATE_CHANGE: { title: '状态更新', text: stateText(ev.status) || ev.text || '任务状态已更新' },
    STEP_DONE: {
      title: `步骤 ${Number(ev.stepIndex ?? 0) + 1} 已完成`,
      text: ev.attractionName
        ? `已确定景点：${ev.attractionName}${ev.plannedStartTime ? `，预计 ${formatTime(ev.plannedStartTime)} 开始` : ''}`
        : ev.text
    },
    TOOL_RESULT: { title: '工具结果', text: ev.text || '已补充地理位置、天气或路程信息。' },
    RETRY: { title: '自动重试', text: ev.message || ev.text || '地图服务调用过于频繁，系统正在自动重试。' },
    PAUSED: { title: '任务暂停', text: pausedText(ev) },
    USER_SELECTION_REQUIRED: {
      title: ev.pendingInputType === 'attraction_selection' ? '请选择下一站景点' : '请选择起点位置',
      text: ev.pendingInputType === 'attraction_selection'
        ? '系统已为当前步骤生成 top-k 景点候选，请确认后继续规划。'
        : `关键词“${ev.startLocationQuery || task.value?.startLocationQuery || ''}”已生成候选地点。`
    },
    USER_SELECTION_CONFIRMED: {
      title: '选择已确认',
      text: selectionConfirmedText(ev)
    },
    COMPLETED: { title: '规划完成', text: '路线已生成，可以查看最终结果。' },
    ERROR: { title: '任务失败', text: userFacingErrorText(ev) }
  }
  const picked = mapping[ev.eventType] || { title: ev.eventType, text: ev.text }
  return {
    ...ev,
    role: ev.eventType === 'USER_SELECTION_CONFIRMED' ? 'user' : 'system',
    title: picked.title,
    text: picked.text
  }
}

function selectionAlertTitle(item) {
  if (item.pendingInputType === 'attraction_selection') {
    return '请选择下一站景点，确认后系统会继续规划后续路线。'
  }
  return '请选择起点候选，确认后系统会继续按时间预算规划路线。'
}

function selectionConfirmedText(item) {
  const selectedName = item.selectedCandidate?.name || item.selectedOrigin?.name
  if (!selectedName) return '已确认候选，系统继续规划中。'
  if (item.pendingInputType === 'attraction_selection') {
    return `已选择 ${selectedName} 作为下一站景点，系统继续规划中。`
  }
  return `已选择 ${selectedName} 作为起点，系统继续规划中。`
}

function pausedText(ev) {
  if (ev.reason === 'amap_rate_limited' || task.value?.pauseReason === 'amap_rate_limited') {
    return ev.message || ev.text || '地图接口限流，任务已暂时暂停，可稍后恢复。'
  }
  return ev.message || ev.text || '当前任务因配额限制暂停，稍后可继续。'
}

function userFacingErrorText(ev) {
  const raw = ev.message || ev.text || ''
  if (raw.includes('CUQPS_HAS_EXCEEDED_THE_LIMIT') || ev.code === 'TOOL_AMAP_RATE_LIMIT') {
    return '地图服务调用过于频繁，请稍后再试。'
  }
  return raw || '任务执行失败。'
}

function stateText(status) {
  const mapping = {
    pending: '任务已创建，等待开始。',
    planning: '系统正在生成路线。',
    tool_calling: '系统正在补充地图和天气信息。',
    awaiting_user_input: '系统正在等待你的确认。',
    paused: '任务已暂停。',
    resuming: '任务已恢复，继续执行中。',
    completed: '任务已完成。',
    failed: '任务失败。',
    cancelled: '任务已取消。'
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

function formatFullDayWindow(taskValue) {
  if (!taskValue?.fullDayStartTime || !taskValue?.fullDayEndTime) return '07:00-21:00'
  return `${taskValue.fullDayStartTime.slice(0, 5)}-${taskValue.fullDayEndTime.slice(0, 5)}`
}

function formatQuotaValue(value) {
  return new Intl.NumberFormat('zh-CN').format(Number(value ?? 0))
}

function formatScore(value) {
  return Number(value ?? 0).toFixed(2)
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
  color: rgba(255, 255, 255, 0.84);
}

.summary-right {
  min-width: 280px;
}

.summary-count,
.summary-budget {
  margin-top: 10px;
  text-align: right;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.85);
}

.window-panel {
  margin-bottom: 20px;
}

.window-item {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.window-list {
  display: grid;
  gap: 10px;
}

.window-chip {
  padding: 10px 12px;
  border-radius: 12px;
  background: #f4f8fb;
  color: #355070;
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

.context-panel {
  margin-top: 12px;
  padding: 14px 16px;
  border-radius: 14px;
  background: #eef8ff;
}

.context-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.context-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  color: #355070;
}

.candidate-card {
  width: 100%;
  margin-top: 12px;
  padding: 16px;
  border: 1px solid #d5e2ee;
  border-radius: 16px;
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition: transform 0.18s ease, border-color 0.18s ease, box-shadow 0.18s ease;
}

.candidate-card:hover {
  transform: translateY(-1px);
  border-color: #1f6aa5;
  box-shadow: 0 12px 24px rgba(13, 59, 102, 0.08);
}

.candidate-card:disabled {
  cursor: wait;
  opacity: 0.75;
}

.candidate-card-header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.candidate-name {
  font-size: 16px;
  font-weight: 700;
}

.candidate-score {
  white-space: nowrap;
  font-size: 13px;
  color: #1f6aa5;
  font-weight: 600;
}

.candidate-meta,
.candidate-route,
.candidate-duration {
  margin-top: 6px;
  font-size: 13px;
  color: #52667a;
}

.candidate-section {
  margin-top: 10px;
}

.candidate-section-title {
  font-size: 13px;
  font-weight: 700;
  color: #243b53;
  margin-bottom: 6px;
}

.candidate-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.candidate-tag {
  padding: 4px 8px;
  border-radius: 999px;
  background: #eef8ff;
  color: #355070;
  font-size: 12px;
}

.candidate-reasons {
  margin: 0;
  padding-left: 18px;
  color: #355070;
}

.candidate-reasons li + li {
  margin-top: 4px;
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
  gap: 12px;
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

  .context-grid,
  .candidate-card-header {
    grid-template-columns: 1fr;
    display: grid;
  }
}
</style>
