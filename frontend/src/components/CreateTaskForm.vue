<template>
  <el-dialog v-model="visible" title="创建旅行规划" width="620px" :close-on-click-modal="false">
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
          placeholder="例如：想看历史景点，也想留出时间吃当地美食"
        />
      </el-form-item>

      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="开始位置" prop="startLocationQuery">
            <el-input v-model="form.startLocationQuery" placeholder="例如：酒店、西安北站、钟楼" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="结束位置" prop="endLocationQuery">
            <el-input v-model="form.endLocationQuery" placeholder="例如：机场、高铁站、返程酒店" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="开始时间" prop="startTime">
            <el-date-picker
              v-model="form.startTime"
              type="datetime"
              placeholder="选择开始时间"
              value-format="YYYY-MM-DDTHH:mm:ss"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="结束时间" prop="endTime">
            <el-date-picker
              v-model="form.endTime"
              type="datetime"
              placeholder="选择结束时间"
              value-format="YYYY-MM-DDTHH:mm:ss"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item>
        <div class="full-day-head">
          <span>整天默认时间</span>
          <el-switch v-model="form.useCustomFullDayWindow" />
        </div>
        <div class="full-day-tip">
          不处理时，中间完整天默认按 07:00-21:00；开启后可自定义完整天时间窗。
        </div>
      </el-form-item>

      <el-row v-if="form.useCustomFullDayWindow" :gutter="12">
        <el-col :span="12">
          <el-form-item label="整天开始时间" prop="fullDayStartTime">
            <el-time-picker
              v-model="form.fullDayStartTime"
              placeholder="选择时间"
              value-format="HH:mm:ss"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="整天结束时间" prop="fullDayEndTime">
            <el-time-picker
              v-model="form.fullDayEndTime"
              placeholder="选择时间"
              value-format="HH:mm:ss"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="偏好标签（可选）">
        <el-input v-model="form.preferenceKeywords" placeholder="例如：历史, 美食, 夜景" />
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
  startLocationQuery: '',
  endLocationQuery: '',
  startTime: '',
  endTime: '',
  useCustomFullDayWindow: false,
  fullDayStartTime: '',
  fullDayEndTime: '',
  preferenceKeywords: '',
  travelMode: 'driving'
})

const form = ref(initialForm())

const rules = {
  region: [{ required: true, message: '请输入目的地/区域', trigger: 'blur' }],
  userIntent: [{ required: true, message: '请输入旅行意图', trigger: 'blur' }],
  startLocationQuery: [{ required: true, message: '请输入开始位置', trigger: 'blur' }],
  endLocationQuery: [{ required: true, message: '请输入结束位置', trigger: 'blur' }],
  startTime: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
  endTime: [{ required: true, message: '请选择结束时间', trigger: 'change' }],
  fullDayStartTime: [{
    validator: (_, value, callback) => {
      if (!form.value.useCustomFullDayWindow) return callback()
      if (!value) return callback(new Error('请选择整天开始时间'))
      return callback()
    },
    trigger: 'change'
  }],
  fullDayEndTime: [{
    validator: (_, value, callback) => {
      if (!form.value.useCustomFullDayWindow) return callback()
      if (!value) return callback(new Error('请选择整天结束时间'))
      return callback()
    },
    trigger: 'change'
  }]
}

function open() {
  visible.value = true
}

function validateChronology() {
  const start = new Date(form.value.startTime)
  const end = new Date(form.value.endTime)
  if (!(start < end)) {
    throw new Error('结束时间必须晚于开始时间')
  }
  if (form.value.useCustomFullDayWindow && form.value.fullDayStartTime >= form.value.fullDayEndTime) {
    throw new Error('整天结束时间必须晚于整天开始时间')
  }
}

async function handleSubmit() {
  await formRef.value.validate()
  validateChronology()
  loading.value = true
  try {
    const payload = {
      region: form.value.region,
      userIntent: form.value.userIntent,
      startLocationQuery: form.value.startLocationQuery,
      endLocationQuery: form.value.endLocationQuery,
      startTime: form.value.startTime,
      endTime: form.value.endTime,
      travelMode: form.value.travelMode
    }
    if (form.value.useCustomFullDayWindow) {
      payload.fullDayStartTime = form.value.fullDayStartTime
      payload.fullDayEndTime = form.value.fullDayEndTime
    }
    if (form.value.preferenceKeywords?.trim()) {
      payload.preferenceKeywords = form.value.preferenceKeywords
        .split(',')
        .map(item => item.trim())
        .filter(Boolean)
    }

    const res = await createTask(payload)
    ElMessage.success('任务创建成功，正在生成开始位置候选...')
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

<style scoped>
.full-day-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  font-weight: 600;
}

.full-day-tip {
  margin-top: 6px;
  font-size: 12px;
  color: #7a8ca5;
}
</style>
