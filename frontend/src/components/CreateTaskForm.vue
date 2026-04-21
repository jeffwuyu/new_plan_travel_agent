<template>
  <el-dialog v-model="visible" title="创建旅行规划" width="520px" :close-on-click-modal="false">
    <el-form :model="form" :rules="rules" ref="formRef" label-position="top">
      <el-form-item label="目的地城市" prop="region">
        <el-input v-model="form.region" placeholder="例如：西安市" />
      </el-form-item>
      <el-form-item label="旅行意图" prop="userIntent">
        <el-input v-model="form.userIntent" type="textarea" :rows="2" placeholder="例如：3天历史文化深度游" maxlength="500" show-word-limit />
      </el-form-item>
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="旅行天数" prop="totalDays">
            <el-input-number v-model="form.totalDays" :min="1" :max="14" style="width:100%" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="每天景点数" prop="attractionsPerDay">
            <el-input-number v-model="form.attractionsPerDay" :min="1" :max="6" style="width:100%" />
          </el-form-item>
        </el-col>
      </el-row>
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

const form = ref({
  region: '',
  userIntent: '',
  totalDays: 3,
  attractionsPerDay: 3,
  preferenceKeywords: '',
  travelMode: 'driving'
})

const rules = {
  region: [{ required: true, message: '请输入目的地城市', trigger: 'blur' }],
  userIntent: [{ required: true, message: '请输入旅行意图', trigger: 'blur' }]
}

function open() { visible.value = true }

async function handleSubmit() {
  await formRef.value.validate()
  loading.value = true
  try {
    const payload = { ...form.value }
    if (!payload.preferenceKeywords?.trim()) delete payload.preferenceKeywords
    const res = await createTask(payload)
    ElMessage.success('任务创建成功，正在规划中...')
    visible.value = false
    emit('created', res.data?.taskUuid)
    formRef.value.resetFields()
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}

defineExpose({ open })
</script>
