<template>
  <div>
    <div class="title-bar">
      <h3 class="page-title">任务监控</h3>
      <div class="toolbar">
        <el-select v-model="statusFilter" size="small" class="filter-select" @change="fetchTasks">
          <el-option label="全部状态" value="" />
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-button size="small" :loading="loading" @click="fetchTasks">刷新</el-button>
      </div>
    </div>

    <el-table :data="tasks" v-loading="loading" stripe border>
      <el-table-column prop="taskUuid" label="任务 UUID" min-width="240" show-overflow-tooltip />
      <el-table-column prop="userId" label="用户 ID" width="100" />
      <el-table-column label="状态" width="140">
        <template #default="{ row }">
          <el-tag :type="statusMeta(row.status).type" size="small">
            {{ statusMeta(row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="region" label="地区" min-width="120" show-overflow-tooltip />
      <el-table-column prop="totalTokensUsed" label="Token" width="100" />
      <el-table-column prop="errorMessage" label="错误信息" min-width="220" show-overflow-tooltip />
      <el-table-column label="更新时间" width="180">
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEvents(row)">事件</el-button>
          <el-button link type="warning" @click="openLease(row)">Lease</el-button>
          <el-button
            v-if="canRedispatch(row)"
            link
            type="danger"
            :loading="redispatchingTaskUuid === row.taskUuid"
            @click="handleRedispatch(row)"
          >Redispatch</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="total > pageSize"
      class="mt-16"
      layout="prev, pager, next"
      :total="total"
      :page-size="pageSize"
      :current-page="page"
      @current-change="onPageChange"
    />

    <el-drawer v-model="eventsVisible" size="60%" :title="`任务事件：${selectedTask?.taskUuid || ''}`">
      <el-table :data="taskEvents" v-loading="eventsLoading" size="small" empty-text="暂无事件">
        <el-table-column prop="eventType" label="事件" width="180" />
        <el-table-column prop="status" label="状态" width="140" />
        <el-table-column label="步骤" width="100">
          <template #default="{ row }">
            {{ formatStep(row.stepIndex, row.totalSteps) }}
          </template>
        </el-table-column>
        <el-table-column prop="message" label="消息" min-width="240" show-overflow-tooltip />
        <el-table-column prop="detailsJson" label="详情" min-width="260" show-overflow-tooltip />
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-dialog v-model="leaseVisible" width="720px" :title="`Lease 快照：${selectedTask?.taskUuid || ''}`">
      <el-descriptions v-if="leaseInfo" :column="2" border>
        <el-descriptions-item label="任务 UUID">{{ leaseInfo.taskUuid || '-' }}</el-descriptions-item>
        <el-descriptions-item label="Redis 可用">{{ yesNo(leaseInfo.available, true) }}</el-descriptions-item>
        <el-descriptions-item label="已加锁">{{ yesNo(leaseInfo.locked) }}</el-descriptions-item>
        <el-descriptions-item label="Lease 有效">{{ yesNo(leaseInfo.leaseValid) }}</el-descriptions-item>
        <el-descriptions-item label="Lock TTL">{{ formatTtl(leaseInfo.lockTtlSeconds) }}</el-descriptions-item>
        <el-descriptions-item label="Lease TTL">{{ formatTtl(leaseInfo.leaseTtlSeconds) }}</el-descriptions-item>
        <el-descriptions-item label="Lock Key" :span="2">{{ leaseInfo.lockKey || '-' }}</el-descriptions-item>
        <el-descriptions-item label="Lease Key" :span="2">{{ leaseInfo.leaseKey || '-' }}</el-descriptions-item>
        <el-descriptions-item label="Lock Owner" :span="2">{{ leaseInfo.lockOwner || '-' }}</el-descriptions-item>
        <el-descriptions-item label="Lease Owner" :span="2">{{ leaseInfo.leaseOwner || '-' }}</el-descriptions-item>
        <el-descriptions-item v-if="leaseInfo.error" label="错误" :span="2">{{ leaseInfo.error }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAdminTaskEvents, getAdminTaskLease, listAdminTasks, redispatchAdminTask } from '@/api/admin'

const tasks = ref([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const statusFilter = ref('')

const selectedTask = ref(null)
const taskEvents = ref([])
const eventsLoading = ref(false)
const eventsVisible = ref(false)

const leaseInfo = ref(null)
const leaseVisible = ref(false)
const redispatchingTaskUuid = ref('')

const statusOptions = [
  { value: 'pending', label: '等待中' },
  { value: 'planning', label: '规划中' },
  { value: 'tool_calling', label: '工具调用中' },
  { value: 'awaiting_user_input', label: '等待用户输入' },
  { value: 'paused', label: '已暂停' },
  { value: 'resuming', label: '恢复中' },
  { value: 'completed', label: '已完成' },
  { value: 'failed', label: '失败' },
  { value: 'cancelled', label: '已取消' }
]

const statusMapping = {
  pending: { label: '等待中', type: 'warning' },
  planning: { label: '规划中', type: 'primary' },
  tool_calling: { label: '工具调用中', type: 'primary' },
  awaiting_user_input: { label: '等待用户输入', type: 'warning' },
  paused: { label: '已暂停', type: 'info' },
  resuming: { label: '恢复中', type: 'warning' },
  completed: { label: '已完成', type: 'success' },
  failed: { label: '失败', type: 'danger' },
  cancelled: { label: '已取消', type: 'info' }
}

onMounted(fetchTasks)

async function fetchTasks() {
  loading.value = true
  try {
    const res = await listAdminTasks(page.value, pageSize.value, statusFilter.value)
    tasks.value = res.data?.list || []
    total.value = res.data?.total || tasks.value.length
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}

async function openEvents(task) {
  selectedTask.value = task
  eventsVisible.value = true
  eventsLoading.value = true
  taskEvents.value = []
  try {
    const res = await getAdminTaskEvents(task.taskUuid, 200)
    taskEvents.value = res.data || []
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    eventsLoading.value = false
  }
}

async function openLease(task) {
  selectedTask.value = task
  leaseVisible.value = true
  leaseInfo.value = null
  try {
    const res = await getAdminTaskLease(task.taskUuid)
    leaseInfo.value = res.data || {}
  } catch (err) {
    ElMessage.error(err.message)
  }
}

function canRedispatch(task) {
  return task?.status === 'failed' || task?.status === 'paused'
}

async function handleRedispatch(task) {
  try {
    await ElMessageBox.confirm(
      `Redispatch task ${task.taskUuid}?`,
      'Confirm redispatch',
      { type: 'warning' }
    )
    redispatchingTaskUuid.value = task.taskUuid
    await redispatchAdminTask(task.taskUuid)
    ElMessage.success('Task redispatched')
    await fetchTasks()
  } catch (err) {
    if (err !== 'cancel') {
      ElMessage.error(err.message || 'Redispatch failed')
    }
  } finally {
    redispatchingTaskUuid.value = ''
  }
}

function onPageChange(nextPage) {
  page.value = nextPage
  fetchTasks()
}

function statusMeta(status) {
  return statusMapping[status] || { label: status || '-', type: 'info' }
}

function formatTime(ts) {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}

function formatStep(stepIndex, totalStepsValue) {
  if (stepIndex == null && totalStepsValue == null) return '-'
  return `${stepIndex ?? '-'} / ${totalStepsValue ?? '-'}`
}

function formatTtl(ttl) {
  if (ttl == null) return '-'
  if (ttl === -1) return '永不过期'
  if (ttl === -2) return '不存在'
  return `${ttl}s`
}

function yesNo(value, defaultYes = false) {
  if (value == null) return defaultYes ? '是' : '-'
  return value ? '是' : '否'
}
</script>

<style scoped>
.page-title {
  margin: 0;
  font-size: 18px;
}

.title-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
}

.filter-select {
  width: 180px;
}

.mt-16 {
  margin-top: 16px;
}
</style>
