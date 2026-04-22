<template>
  <el-container class="page-container">
    <el-header class="page-header">
      <div class="header-left">
        <span class="logo">旅行规划助手</span>
        <el-menu mode="horizontal" :default-active="'/tasks'" router class="nav-menu">
          <el-menu-item index="/tasks">我的任务</el-menu-item>
          <el-menu-item index="/plans">规划结果</el-menu-item>
          <el-menu-item v-if="auth.isAdmin" index="/admin">管理后台</el-menu-item>
        </el-menu>
      </div>
      <div class="header-right">
        <span class="username">{{ auth.username }}</span>
        <el-button size="small" @click="handleLogout">退出</el-button>
      </div>
    </el-header>

    <el-main>
      <div class="main-toolbar">
        <h3 class="section-title">我的规划任务</h3>
        <el-button type="primary" @click="createFormRef.open()">
          <el-icon><Plus /></el-icon> 新建规划
        </el-button>
      </div>

      <el-card shadow="never" class="quota-card">
        <div class="quota-header">
          <div>
            <div class="quota-title">免费 Token 额度概览</div>
            <div class="quota-subtitle">{{ auth.userLevelLabel || '当前账户' }}</div>
          </div>
        </div>
        <div class="quota-grid">
          <div class="quota-item">
            <span class="quota-label">今日已用</span>
            <strong>{{ formatQuotaValue(auth.quota.dailyUsed) }}</strong>
          </div>
          <div class="quota-item">
            <span class="quota-label">今日剩余免费 Token</span>
            <strong>{{ formatRemaining(auth.quota.dailyRemaining, auth.quota.dailyLimit) }}</strong>
          </div>
          <div class="quota-item">
            <span class="quota-label">本月已用</span>
            <strong>{{ formatQuotaValue(auth.quota.monthlyUsed) }}</strong>
          </div>
          <div class="quota-item">
            <span class="quota-label">本月剩余免费 Token</span>
            <strong>{{ formatRemaining(auth.quota.monthlyRemaining, auth.quota.monthlyLimit) }}</strong>
          </div>
        </div>
      </el-card>

      <el-table :data="store.tasks" v-loading="store.loading" stripe>
        <el-table-column prop="region" label="目的地" min-width="100" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <TaskStatusBadge :status="row.status" />
          </template>
        </el-table-column>
        <el-table-column prop="totalTokensUsed" label="Token 消耗" width="120" align="right" />
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="router.push(`/tasks/${row.taskUuid}`)">详情</el-button>
            <el-button
              v-if="!isTerminal(row.status) && row.status !== 'paused'"
              size="small"
              type="danger"
              @click="handleCancel(row.taskUuid)"
            >取消</el-button>
            <el-button
              v-if="row.status === 'paused'"
              size="small"
              type="warning"
              @click="handleResume(row.taskUuid)"
            >恢复</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty
        v-if="!store.loading && !store.tasks.length"
        description="暂无规划任务，点击新建开始规划"
      />
    </el-main>

    <CreateTaskForm ref="createFormRef" @created="onTaskCreated" />
  </el-container>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelTask, resumeTask } from '@/api/tasks'
import { logout } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { useTasksStore } from '@/stores/tasks'
import TaskStatusBadge from '@/components/TaskStatusBadge.vue'
import CreateTaskForm from '@/components/CreateTaskForm.vue'

const router = useRouter()
const auth = useAuthStore()
const store = useTasksStore()
const createFormRef = ref(null)

const TERMINAL = new Set(['completed', 'failed', 'cancelled'])
const isTerminal = (status) => TERMINAL.has(status)

let pollTimer = null

onMounted(async () => {
  await Promise.allSettled([store.fetchTasks(), auth.refreshQuota()])
  schedulePoll()
})

onUnmounted(() => clearInterval(pollTimer))

function schedulePoll() {
  pollTimer = setInterval(async () => {
    const jobs = []
    if (store.hasActiveTask()) {
      jobs.push(store.fetchTasks())
    }
    jobs.push(auth.refreshQuota())
    await Promise.allSettled(jobs)
  }, 10000)
}

async function handleLogout() {
  try {
    await logout()
  } catch {
    // ignore logout API failure, local session should still clear
  }
  auth.logout()
  router.push('/login')
}

async function handleCancel(uuid) {
  await ElMessageBox.confirm('确认取消该规划任务？', '提示', { type: 'warning' })
  try {
    await cancelTask(uuid)
    ElMessage.success('任务已取消')
    await Promise.allSettled([store.fetchTasks(), auth.refreshQuota()])
  } catch (err) {
    ElMessage.error(err.message)
  }
}

async function handleResume(uuid) {
  try {
    await resumeTask(uuid)
    ElMessage.success('任务已恢复')
    await Promise.allSettled([store.fetchTasks(), auth.refreshQuota()])
  } catch (err) {
    ElMessage.error(err.message)
  }
}

async function onTaskCreated(taskUuid) {
  await Promise.allSettled([store.fetchTasks(), auth.refreshQuota()])
  if (taskUuid) {
    router.push(`/tasks/${taskUuid}`)
  }
}

function formatTime(ts) {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}

function formatQuotaValue(value) {
  return new Intl.NumberFormat('zh-CN').format(Number(value || 0))
}

function formatRemaining(value, limit) {
  if (!limit) return '--'
  return formatQuotaValue(value)
}
</script>

<style scoped>
.page-container { min-height: 100vh; background: #f5f7fa; }
.page-header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
}
.header-left { display: flex; align-items: center; gap: 24px; }
.logo { font-size: 18px; font-weight: 600; color: #409eff; white-space: nowrap; }
.nav-menu { border-bottom: none; }
.header-right { display: flex; align-items: center; gap: 12px; }
.username { font-size: 14px; color: #606266; }
.main-toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.section-title { margin: 0; font-size: 16px; }
.quota-card { margin-bottom: 16px; }
.quota-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.quota-title { font-size: 16px; font-weight: 600; color: #303133; }
.quota-subtitle { margin-top: 4px; font-size: 13px; color: #909399; }
.quota-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.quota-item { padding: 14px 16px; border-radius: 12px; background: #f5f7fa; display: flex; flex-direction: column; gap: 8px; }
.quota-label { font-size: 13px; color: #606266; }

@media (max-width: 900px) {
  .quota-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
