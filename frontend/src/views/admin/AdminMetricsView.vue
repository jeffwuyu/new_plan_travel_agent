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

    <el-card header="外部能力健康" class="mt-20">
      <el-table :data="capabilities" size="small" empty-text="暂无健康检查数据">
        <el-table-column prop="name" label="能力" min-width="160" />
        <el-table-column label="配置" width="100">
          <template #default="{ row }">
            <el-tag :type="row.configured ? 'success' : 'warning'" size="small">
              {{ row.configured ? '已配置' : '缺失' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="连通" width="100">
          <template #default="{ row }">
            <el-tag :type="reachableTagType(row)" size="small">
              {{ reachableText(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="检查方式" width="120">
          <template #default="{ row }">
            {{ checkTypeText(row.checkType) }}
          </template>
        </el-table-column>
        <el-table-column label="降级" width="100">
          <template #default="{ row }">
            <el-tag :type="row.degraded ? 'danger' : 'success'" size="small">
              {{ row.degraded ? '是' : '否' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorCode" label="错误码" width="150" show-overflow-tooltip />
        <el-table-column prop="lastError" label="最近错误/原因" min-width="240" show-overflow-tooltip />
        <el-table-column prop="checkedAt" label="检查时间" min-width="190" show-overflow-tooltip />
      </el-table>
    </el-card>

    <div v-if="updatedAt" class="update-time">最后更新：{{ updatedAt }}</div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getCapabilityHealth, getMetrics } from '@/api/admin'

const metrics = ref({})
const capabilities = ref([])
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
    const [metricsRes, healthRes] = await Promise.all([getMetrics(), getCapabilityHealth()])
    const res = metricsRes
    const data = res.data || {}
    // Compute avg latency
    const calls = data.llmCallsTotal || 0
    const totalMs = data.llmLatencyMsTotal || 0
    data.llmAvgLatencyMs = calls > 0 ? Math.round(totalMs / calls) : 0
    metrics.value = data
    capabilities.value = healthRes.data || []
    updatedAt.value = new Date().toLocaleString('zh-CN')
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}

function reachableTagType(row) {
  if (row.reachable) return 'success'
  if (row.checkType === 'config_only' || row.checkType === 'disabled') return 'info'
  return row.degraded ? 'danger' : 'warning'
}

function reachableText(row) {
  if (row.reachable) return '正常'
  if (row.checkType === 'config_only') return '未探活'
  if (row.checkType === 'disabled') return '已关闭'
  return '异常'
}

function checkTypeText(type) {
  const mapping = {
    config_only: '配置检查',
    ping: 'Ping 探活',
    postgres_extension: 'PG 扩展',
    disabled: '已关闭'
  }
  return mapping[type] || '未标记'
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
