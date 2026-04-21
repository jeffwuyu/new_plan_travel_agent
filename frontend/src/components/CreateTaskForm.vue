<template>
  <el-dialog v-model="visible" title="创建旅行规划" width="560px" :close-on-click-modal="false">
    <el-form :model="form" :rules="rules" ref="formRef" label-position="top">
      <el-form-item label="目的地/区域" prop="region">
        <el-input v-model="form.region" placeholder="例如：西安市" />
      </el-form-item>
      <el-form-item label="旅行意图" prop="userIntent">
        <el-input
          v-model="form.userIntent"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="例如：历史文化和美食为主，希望路线紧凑一些"
        />
      </el-form-item>
      <el-form-item label="当前位置关键词" prop="currentLocationQuery">
        <el-input
          v-model="form.currentLocationQuery"
          placeholder="例如：钟楼、北站、酒店名、商圈名"
        />
      </el-form-item>
      <el-form-item label="偏好标签（可选）">
        <el-input v-model="form.preferenceKeywords" placeholder="例如：历史文化,美食,古迹" />
      </el-form-item>
      <el-form-item label="出行方式">
        <el-radio-group v-model="form.travelMode">
          <el-radio value="driving">自驾</el-radio>
          <el-radio value="transit">公共交通</el-radio>
          <el-radio value="walking">步行</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="loading" @click="handleSubmit">开始规划</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createTask } from '@/api/tasks'

const emit = defineEmits(['created'])
const visible = ref(false)
const loading = ref(false)
const formRef = ref(null)

const initialForm = () => ({
  region: '',
  userIntent: '',
  currentLocationQuery: '',
  preferenceKeywords: '',
  travelMode: 'driving'
})

const form = ref(initialForm())

const rules = {
  region: [{ required: true, message: '请输入目的地/区域', trigger: 'blur' }],
  userIntent: [{ required: true, message: '请输入旅行意图', trigger: 'blur' }],
  currentLocationQuery: [{ required: true, message: '请输入当前位置关键词', trigger: 'blur' }]
}

function open() {
  visible.value = true
}

async function handleSubmit() {
  await formRef.value.validate()
  loading.value = true
  try {
    const payload = { ...form.value, totalDays: 1 }
    if (payload.preferenceKeywords?.trim()) {
      payload.preferenceKeywords = payload.preferenceKeywords
        .split(',')
        .map(item => item.trim())
        .filter(Boolean)
    } else {
      delete payload.preferenceKeywords
    }
    const res = await createTask(payload)
    ElMessage.success('任务创建成功，正在生成起点候选...')
    visible.value = false
    emit('created', res.data?.taskUuid)
    form.value = initialForm()
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}

defineExpose({ open })
</script>
