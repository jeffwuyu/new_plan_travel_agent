<template>
  <el-card header="LLM 实时输出" v-if="tokens">
    <div ref="outputEl" class="stream-output">{{ tokens }}</div>
  </el-card>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'

const props = defineProps({ tokens: { type: String, default: '' } })
const outputEl = ref(null)

watch(() => props.tokens, async () => {
  await nextTick()
  if (outputEl.value) {
    outputEl.value.scrollTop = outputEl.value.scrollHeight
  }
})
</script>

<style scoped>
.stream-output {
  font-family: monospace;
  font-size: 13px;
  line-height: 1.6;
  max-height: 300px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
  color: #303133;
}
</style>
