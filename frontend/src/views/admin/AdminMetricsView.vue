<template>
  <div>
    <div class="title-bar">
      <h3 class="page-title">系统指标</h3>
      <el-button size="small" :loading="loading" @click="fetchMetrics">刷新</el-button>
    </div>

    <el-row :gutter="16" v-loading="loading">
      <el-col :span="6" v-for="item in statItems" :key="item.key">
        <el-card class="stat-card" shadow="hover">
          <el-statistic :title="item.label" :value="metrics[item.key] ?? '—'" :suffix="item.suffix" />
        </el-card>
      </el-col>
    </el-row>

    <el-card header="任务统计" class="mt-20" v-if="metrics">
      <el-row :gutter="16">
        <el-col :span="6" v-for="item in taskItems" :key="item.key">
          <el-card shadow="never" class="stat-card-inner">
            <el-statistic :title="item.label" :value="metrics[item.key] ?? 0" />
          </el-card>
        </el-col>
      </el-row>
    </el-card>

    <div v-if="updatedAt" class="update-time">最后更新：{{ updatedAt }}</div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getMetrics } from '@/api/admin'

const metrics = ref({})
const loading = ref(false)
const updatedAt = ref('')

const statItems = [
  { key: 'llmCallsTotal',       label: 'LLM 调用总数',    suffix: '次' },
  { key: 'llmAvgLatencyMs',     label: 'LLM 平均耗时',    suffix: 'ms' },
  { key: 'llmErrorsTotal',      label: 'LLM 错误数',      suffix: '次' },
  { key: 'toolCallsTotal',      label: '工具调用总数',     suffix: '次' }
]

const taskItems = [
  { key: 'tasksStarted',    label: '已启动任务' },
  { key: 'tasksCompleted',  label: '已完成任务' },
  { key: 'tasksFailed',     label: '失败任务' },
  { key: 'tasksPaused',     label: '暂停任务' }
]

let timer = null

onMounted(async () => {
  await fetchMetrics()
  timer = setInterval(fetchMetrics, 30000)
})

onUnmounted(() => clearInterval(timer))

async function fetchMetrics() {
  loading.value = true
  try {
    const res = await getMetrics()
    const data = res.data || {}
    // Compute avg latency
    const calls = data.llmCallsTotal || 0
    const totalMs = data.llmLatencyMsTotal || 0
    data.llmAvgLatencyMs = calls > 0 ? Math.round(totalMs / calls) : 0
    metrics.value = data
    updatedAt.value = new Date().toLocaleString('zh-CN')
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.page-title { margin: 0; font-size: 18px; }
.title-bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.stat-card { margin-bottom: 16px; }
.stat-card-inner { background: #f5f7fa; }
.mt-20 { margin-top: 20px; }
.update-time { margin-top: 12px; font-size: 12px; color: #c0c4cc; text-align: right; }
</style>
