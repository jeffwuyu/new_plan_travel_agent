<template>
  <el-container class="page-container">
    <el-header class="page-header">
      <div class="header-left">
        <span class="logo">✈ 旅游规划助手</span>
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

      <el-table :data="store.tasks" v-loading="store.loading" stripe>
        <el-table-column prop="region" label="目的地" min-width="100" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <TaskStatusBadge :status="row.status" />
          </template>
        </el-table-column>
        <el-table-column prop="totalTokensUsed" label="Token 消耗" width="110" align="right" />
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="router.push(`/tasks/${row.taskUuid}`)">详情</el-button>
            <el-button
              v-if="!isTerminal(row.status) && row.status !== 'paused'"
              size="small" type="danger"
              @click="handleCancel(row.taskUuid)"
            >取消</el-button>
            <el-button
              v-if="row.status === 'paused'"
              size="small" type="warning"
              @click="handleResume(row.taskUuid)"
            >恢复</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!store.loading && !store.tasks.length" description="暂无规划任务，点击新建开始规划" />
    </el-main>

    <CreateTaskForm ref="createFormRef" @created="onTaskCreated" />
  </el-container>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelTask, resumeTask } from '@/api/tasks'
import { useAuthStore } from '@/stores/auth'
import { useTasksStore } from '@/stores/tasks'
import { logout } from '@/api/auth'
import TaskStatusBadge from '@/components/TaskStatusBadge.vue'
import CreateTaskForm from '@/components/CreateTaskForm.vue'

const router = useRouter()
const auth = useAuthStore()
const store = useTasksStore()
const createFormRef = ref(null)

const TERMINAL = new Set(['completed', 'failed', 'cancelled'])
const isTerminal = (s) => TERMINAL.has(s)

let pollTimer = null

onMounted(async () => {
  await store.fetchTasks()
  schedulePoll()
})

onUnmounted(() => clearInterval(pollTimer))

function schedulePoll() {
  pollTimer = setInterval(async () => {
    if (store.hasActiveTask()) await store.fetchTasks()
  }, 10000)
}

async function handleLogout() {
  try { await logout() } catch { /* ignore */ }
  auth.logout()
  router.push('/login')
}

async function handleCancel(uuid) {
  await ElMessageBox.confirm('确认取消该规划任务？', '提示', { type: 'warning' })
  try {
    await cancelTask(uuid)
    ElMessage.success('任务已取消')
    await store.fetchTasks()
  } catch (err) {
    ElMessage.error(err.message)
  }
}

async function handleResume(uuid) {
  try {
    await resumeTask(uuid)
    ElMessage.success('任务已恢复')
    await store.fetchTasks()
  } catch (err) {
    ElMessage.error(err.message)
  }
}

async function onTaskCreated(taskUuid) {
  await store.fetchTasks()
  if (taskUuid) {
    router.push(`/tasks/${taskUuid}`)
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
</style>
