<template>
  <el-tag :type="tagType" size="small">{{ label }}</el-tag>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ status: { type: String, required: true } })

const CONFIG = {
  pending:      { type: 'warning',  label: '等待中' },
  resuming:     { type: 'warning',  label: '恢复中' },
  planning:     { type: '',         label: '规划中' },
  tool_calling: { type: '',         label: '工具调用' },
  paused:       { type: 'info',     label: '已暂停' },
  completed:    { type: 'success',  label: '已完成' },
  failed:       { type: 'danger',   label: '失败' },
  cancelled:    { type: 'danger',   label: '已取消' }
}

const tagType = computed(() => CONFIG[props.status]?.type ?? 'info')
const label   = computed(() => CONFIG[props.status]?.label ?? props.status)
</script>
