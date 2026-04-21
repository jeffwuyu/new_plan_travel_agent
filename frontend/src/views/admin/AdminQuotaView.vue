<template>
  <div>
    <h3 class="page-title">配额配置</h3>
    <el-row :gutter="16" v-loading="loading">
      <el-col v-for="cfg in configs" :key="cfg.userLevel" :span="8">
        <el-card :header="levelLabel(cfg.userLevel)" shadow="hover">
          <el-form :model="cfg" label-position="top" size="small">
            <el-form-item label="每日 Token 上限">
              <el-input-number v-model="cfg.dailyTokenLimit" :min="1000" :step="1000" style="width:100%" />
            </el-form-item>
            <el-form-item label="每月 Token 上限">
              <el-input-number v-model="cfg.monthlyTokenLimit" :min="10000" :step="10000" style="width:100%" />
            </el-form-item>
            <el-form-item label="最大并发任务数">
              <el-input-number v-model="cfg.maxConcurrentTasks" :min="1" :max="20" style="width:100%" />
            </el-form-item>
            <el-form-item label="最大规划步骤数">
              <el-input-number v-model="cfg.maxPlanSteps" :min="5" :max="100" style="width:100%" />
            </el-form-item>
            <el-button type="primary" @click="saveCfg(cfg)" :loading="saving[cfg.userLevel]" style="width:100%">
              保存
            </el-button>
          </el-form>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listQuotaConfigs, updateQuotaConfig } from '@/api/admin'

const configs = ref([])
const loading = ref(false)
const saving = reactive({})

const LABELS = { 1: '普通用户 (REGULAR)', 2: 'VIP 用户', 3: '管理员 (ADMIN)' }
const levelLabel = (l) => LABELS[l] || `等级 ${l}`

onMounted(async () => {
  loading.value = true
  try {
    const res = await listQuotaConfigs()
    configs.value = res.data || []
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
})

async function saveCfg(cfg) {
  saving[cfg.userLevel] = true
  try {
    await updateQuotaConfig(cfg.userLevel, cfg)
    ElMessage.success('配额配置已保存')
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    saving[cfg.userLevel] = false
  }
}
</script>

<style scoped>
.page-title { margin: 0 0 16px; font-size: 18px; }
</style>
