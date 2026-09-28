<template>
  <el-dialog
    v-model="visible"
    title="新建旅行规划"
    width="1040px"
    class="travel-task-dialog"
    :close-on-click-modal="false"
  >
    <el-form :model="form" :rules="rules" ref="formRef" label-position="top" class="task-form">
      <section class="form-section">
        <div class="section-head">
          <h3>基础行程</h3>
          <span>用于生成结构化约束和规划任务</span>
        </div>

        <el-row :gutter="14">
          <el-col :xs="24" :sm="12">
            <el-form-item label="出发地点" prop="departurePlace">
              <el-input v-model="form.departurePlace" placeholder="例如：上海、广州天河、杭州东站" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="目的地 / 区域" prop="region">
              <el-input v-model="form.region" placeholder="例如：北京、西安、成都市区" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="目的地地图选择">
          <TravelMapSelector v-model="mapSelection" @selected="applyMapSelection" />
        </el-form-item>

        <el-row :gutter="14">
          <el-col :xs="24" :sm="12">
            <el-form-item label="到达后起点" prop="startLocationQuery">
              <el-input v-model="form.startLocationQuery" placeholder="例如：北京南站、酒店、钟楼" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="返程 / 结束地点" prop="endLocationQuery">
              <el-input v-model="form.endLocationQuery" placeholder="例如：首都机场、火车站、返程酒店" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="14">
          <el-col :xs="24" :sm="12">
            <el-form-item label="开始时间" prop="startTime">
              <el-date-picker
                v-model="form.startTime"
                type="datetime"
                placeholder="选择出行开始时间"
                value-format="YYYY-MM-DDTHH:mm:ss"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="结束时间" prop="endTime">
              <el-date-picker
                v-model="form.endTime"
                type="datetime"
                placeholder="选择返程或结束时间"
                value-format="YYYY-MM-DDTHH:mm:ss"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <div v-if="tripSummary" class="trip-summary">{{ tripSummary }}</div>
      </section>

      <section class="form-section">
        <div class="section-head compact">
          <h3>预算与人数</h3>
        </div>
        <el-row :gutter="14">
          <el-col :xs="24" :sm="8">
            <el-form-item label="总预算（元）" prop="totalBudgetYuan">
              <el-input-number v-model="form.totalBudgetYuan" :min="1" :precision="0" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="8">
            <el-form-item label="出行人数" prop="adultCount">
              <el-input-number v-model="form.adultCount" :min="1" :max="30" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="8">
            <el-form-item label="房间数" prop="roomCount">
              <el-input-number v-model="form.roomCount" :min="1" :max="10" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="14">
          <el-col :xs="24" :sm="12">
            <el-form-item label="每晚住宿预算（元）" prop="lodgingBudgetPerNightYuan">
              <el-input-number v-model="form.lodgingBudgetPerNightYuan" :min="1" :precision="0" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="住宿类型" prop="accommodationTypes">
              <el-checkbox-group v-model="form.accommodationTypes" class="inline-options">
                <el-checkbox value="hotel">酒店</el-checkbox>
                <el-checkbox value="inn">客栈</el-checkbox>
                <el-checkbox value="homestay">民宿</el-checkbox>
              </el-checkbox-group>
            </el-form-item>
          </el-col>
        </el-row>
      </section>

      <section class="form-section">
        <div class="section-head compact">
          <h3>偏好与约束</h3>
        </div>

        <el-form-item label="交通偏好">
          <el-checkbox-group v-model="form.transportPreferences" class="chip-options">
            <el-checkbox-button v-for="item in transportOptions" :key="item" :value="item">
              {{ item }}
            </el-checkbox-button>
          </el-checkbox-group>
        </el-form-item>

        <el-form-item label="景点偏好">
          <el-checkbox-group v-model="form.interests" class="chip-options">
            <el-checkbox-button v-for="item in interestOptions" :key="item" :value="item">
              {{ item }}
            </el-checkbox-button>
          </el-checkbox-group>
        </el-form-item>

        <el-row :gutter="14">
          <el-col :xs="24" :sm="12">
            <el-form-item label="住宿偏好">
              <el-input v-model="form.hotelPreference" placeholder="例如：靠近地铁、安静、适合亲子" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="饮食偏好">
              <el-input v-model="form.foodPreference" placeholder="例如：本地美食、不吃辣、清淡、素食" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="14">
          <el-col :xs="24" :sm="12">
            <el-form-item label="行程节奏">
              <el-radio-group v-model="form.travelPace" class="pace-group">
                <el-radio-button value="轻松">轻松</el-radio-button>
                <el-radio-button value="普通">普通</el-radio-button>
                <el-radio-button value="高强度">高强度</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="特殊人群">
              <el-checkbox-group v-model="form.specialGroups" class="inline-options">
                <el-checkbox value="老人">老人</el-checkbox>
                <el-checkbox value="儿童">儿童</el-checkbox>
                <el-checkbox value="行动不便者">行动不便者</el-checkbox>
              </el-checkbox-group>
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="14">
          <el-col :xs="24" :sm="12">
            <el-form-item label="预约 / 门票关注">
              <el-switch
                v-model="form.bookingRequired"
                active-text="关注预约、门票和限流"
                inactive-text="暂不特别关注"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="希望避开">
              <el-input v-model="form.avoidText" placeholder="例如：早起、长时间步行、拥挤景点" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="补充需求">
          <el-input
            v-model="form.extraIntent"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="例如：想看历史景点，也想留出时间吃当地美食；最后一天不要安排太满。"
          />
        </el-form-item>
      </section>

      <section class="form-section subtle-section">
        <div class="full-day-head">
          <div>
            <strong>自定义完整旅行日时间窗</strong>
            <p>默认按 07:00-21:00 规划中间完整天，开启后可自定义。</p>
          </div>
          <el-switch v-model="form.useCustomFullDayWindow" />
        </div>

        <el-row v-if="form.useCustomFullDayWindow" :gutter="14">
          <el-col :xs="24" :sm="12">
            <el-form-item label="完整天开始时间" prop="fullDayStartTime">
              <el-time-picker
                v-model="form.fullDayStartTime"
                placeholder="选择时间"
                value-format="HH:mm:ss"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="完整天结束时间" prop="fullDayEndTime">
              <el-time-picker
                v-model="form.fullDayEndTime"
                placeholder="选择时间"
                value-format="HH:mm:ss"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>
      </section>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="loading" @click="handleSubmit">开始规划</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createTask } from '@/api/tasks'
import TravelMapSelector from '@/components/TravelMapSelector.vue'

const emit = defineEmits(['created'])
const visible = ref(false)
const loading = ref(false)
const formRef = ref(null)

const transportOptions = ['高铁', '飞机', '自驾', '市内公共交通', '打车']
const interestOptions = ['历史', '人文', '自然', '亲子', '美食', '拍照', '购物', '博物馆', '室内']

const initialForm = () => ({
  departurePlace: '',
  region: '',
  provinceName: '',
  cityName: '',
  districtName: '',
  adcode: '',
  startLocationQuery: '',
  endLocationQuery: '',
  startTime: '',
  endTime: '',
  totalBudgetYuan: 3000,
  lodgingBudgetPerNightYuan: 500,
  accommodationTypes: ['hotel', 'inn', 'homestay'],
  adultCount: 2,
  roomCount: 1,
  transportPreferences: ['高铁', '市内公共交通'],
  interests: ['历史', '美食'],
  hotelPreference: '靠近地铁',
  foodPreference: '本地美食',
  travelPace: '轻松',
  specialGroups: [],
  bookingRequired: true,
  avoidText: '',
  extraIntent: '',
  useCustomFullDayWindow: false,
  fullDayStartTime: '',
  fullDayEndTime: ''
})

const form = ref(initialForm())
const mapSelection = ref({})

const positiveNumberRule = (message) => ({
  validator: (_, value, callback) => {
    if (Number(value) > 0) return callback()
    return callback(new Error(message))
  },
  trigger: 'change'
})

const rules = {
  departurePlace: [{ required: true, message: '请输入出发地点', trigger: 'blur' }],
  region: [{ required: true, message: '请输入目的地/区域', trigger: 'blur' }],
  startLocationQuery: [{ required: true, message: '请输入到达后的起点', trigger: 'blur' }],
  endLocationQuery: [{ required: true, message: '请输入返程或结束地点', trigger: 'blur' }],
  startTime: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
  endTime: [{ required: true, message: '请选择结束时间', trigger: 'change' }],
  totalBudgetYuan: [positiveNumberRule('总预算必须大于 0')],
  lodgingBudgetPerNightYuan: [positiveNumberRule('每晚住宿预算必须大于 0')],
  adultCount: [positiveNumberRule('出行人数必须大于 0')],
  roomCount: [positiveNumberRule('房间数必须大于 0')],
  accommodationTypes: [{
    validator: (_, value, callback) => {
      if (Array.isArray(value) && value.length > 0) return callback()
      return callback(new Error('请至少选择一种住宿类型'))
    },
    trigger: 'change'
  }],
  fullDayStartTime: [{
    validator: (_, value, callback) => {
      if (!form.value.useCustomFullDayWindow) return callback()
      if (!value) return callback(new Error('请选择完整天开始时间'))
      return callback()
    },
    trigger: 'change'
  }],
  fullDayEndTime: [{
    validator: (_, value, callback) => {
      if (!form.value.useCustomFullDayWindow) return callback()
      if (!value) return callback(new Error('请选择完整天结束时间'))
      return callback()
    },
    trigger: 'change'
  }]
}

const tripSummary = computed(() => {
  if (!form.value.startTime || !form.value.endTime) return ''
  const start = new Date(form.value.startTime)
  const end = new Date(form.value.endTime)
  if (!(start < end)) return ''
  const startDay = new Date(start.getFullYear(), start.getMonth(), start.getDate())
  const endDay = new Date(end.getFullYear(), end.getMonth(), end.getDate())
  const days = Math.floor((endDay - startDay) / 86400000) + 1
  return `预计 ${days} 天 ${Math.max(0, days - 1)} 晚`
})

function open() {
  visible.value = true
}

function applyMapSelection(selection) {
  form.value.provinceName = selection.provinceName || ''
  form.value.cityName = selection.cityName || ''
  form.value.districtName = selection.districtName || ''
  form.value.adcode = selection.adcode || ''
  form.value.region = selection.region || selection.cityName || selection.provinceName || form.value.region
}

function validateChronology() {
  const start = new Date(form.value.startTime)
  const end = new Date(form.value.endTime)
  if (!(start < end)) {
    throw new Error('结束时间必须晚于开始时间')
  }
  if (form.value.useCustomFullDayWindow && form.value.fullDayStartTime >= form.value.fullDayEndTime) {
    throw new Error('完整天结束时间必须晚于完整天开始时间')
  }
}

function splitFreeText(text) {
  return (text || '')
    .split(/[,\uFF0C\u3001;；\s]+/)
    .map(item => item.trim())
    .filter(Boolean)
}

function resolveTravelMode() {
  if (form.value.transportPreferences.includes('自驾')) return 'driving'
  if (form.value.transportPreferences.includes('市内公共交通') || form.value.transportPreferences.includes('高铁')) {
    return 'transit'
  }
  return 'driving'
}

function buildPreferenceKeywords() {
  return Array.from(new Set([
    ...form.value.interests,
    ...splitFreeText(form.value.hotelPreference),
    ...splitFreeText(form.value.foodPreference),
    ...splitFreeText(form.value.avoidText)
  ]))
}

function buildUserIntent() {
  const pieces = [
    `我从${form.value.departurePlace}出发，去${form.value.region}旅行。`,
    `出行人数${form.value.adultCount}人，总预算${form.value.totalBudgetYuan}元。`,
    `交通偏好：${form.value.transportPreferences.join('、') || '暂无特别偏好'}。`,
    `住宿偏好：${form.value.hotelPreference || form.value.accommodationTypes.join('、')}，每晚住宿预算${form.value.lodgingBudgetPerNightYuan}元。`,
    `景点偏好：${form.value.interests.join('、') || '暂无特别偏好'}。`,
    `饮食偏好：${form.value.foodPreference || '暂无特别偏好'}。`,
    `行程节奏：${form.value.travelPace}。`
  ]
  if (form.value.specialGroups.length) {
    pieces.push(`特殊人群：${form.value.specialGroups.join('、')}。`)
  }
  if (form.value.avoidText.trim()) {
    pieces.push(`希望避免${form.value.avoidText.trim()}。`)
  }
  if (form.value.bookingRequired) {
    pieces.push('关注景区预约、门票、限流和官方购票入口。')
  }
  if (form.value.extraIntent.trim()) {
    pieces.push(`补充需求：${form.value.extraIntent.trim()}`)
  }
  const intent = pieces.join('')
  return intent.length > 500 ? `${intent.slice(0, 497)}...` : intent
}

async function handleSubmit() {
  await formRef.value.validate()
  validateChronology()
  loading.value = true
  try {
    const payload = {
      region: form.value.region,
      provinceName: form.value.provinceName,
      cityName: form.value.cityName,
      districtName: form.value.districtName,
      adcode: form.value.adcode,
      userIntent: buildUserIntent(),
      startLocationQuery: form.value.startLocationQuery,
      endLocationQuery: form.value.endLocationQuery,
      startTime: form.value.startTime,
      endTime: form.value.endTime,
      totalBudgetYuan: form.value.totalBudgetYuan,
      lodgingBudgetPerNightYuan: form.value.lodgingBudgetPerNightYuan,
      accommodationTypes: form.value.accommodationTypes,
      adultCount: form.value.adultCount,
      roomCount: form.value.roomCount,
      travelMode: resolveTravelMode(),
      preferenceKeywords: buildPreferenceKeywords()
    }
    if (form.value.useCustomFullDayWindow) {
      payload.fullDayStartTime = form.value.fullDayStartTime
      payload.fullDayEndTime = form.value.fullDayEndTime
    }

    const res = await createTask(payload)
    ElMessage.success('任务创建成功，正在生成起点候选')
    visible.value = false
    emit('created', res.data?.taskUuid)
    form.value = initialForm()
    mapSelection.value = {}
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}

defineExpose({ open })
</script>

<style scoped>
:deep(.travel-task-dialog .el-dialog__body) {
  padding-top: 10px;
  background: #f7fbff;
}

.task-form {
  display: grid;
  gap: 14px;
}

.form-section {
  padding: 16px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #fff;
}

.subtle-section {
  background: #f8fbff;
}

.section-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.section-head.compact {
  margin-bottom: 8px;
}

.section-head h3 {
  margin: 0;
  color: #1e3a8a;
  font-size: 15px;
  font-weight: 700;
}

.section-head span,
.full-day-head p {
  margin: 0;
  color: #64748b;
  font-size: 12px;
}

.trip-summary {
  margin-top: -2px;
  padding: 9px 11px;
  border-radius: 6px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 13px;
}

.inline-options,
.chip-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
}

.pace-group {
  width: 100%;
}

.full-day-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  width: 100%;
  color: #1f2937;
}

:deep(.el-dialog) {
  border-radius: 8px;
  overflow: hidden;
}

:deep(.el-dialog__header) {
  margin-right: 0;
  padding: 18px 22px;
  border-bottom: 1px solid #dbeafe;
  background: #ffffff;
}

:deep(.el-dialog__title) {
  color: #1e3a8a;
  font-weight: 700;
}

:deep(.el-dialog__footer) {
  border-top: 1px solid #dbeafe;
  background: #fff;
}

:deep(.el-form-item) {
  margin-bottom: 14px;
}

:deep(.el-form-item__label) {
  color: #334155;
  font-weight: 600;
}

@media (max-width: 720px) {
  :deep(.travel-task-dialog) {
    width: calc(100vw - 24px) !important;
  }

  .section-head,
  .full-day-head {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
