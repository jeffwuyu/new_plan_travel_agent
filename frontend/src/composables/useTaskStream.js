import { ref, onUnmounted } from 'vue'
import { useAuthStore } from '@/stores/auth'

/**
 * SSE composable for real-time task progress.
 * EventSource does not support custom headers, so JWT is passed as ?token= query param.
 * The backend JwtAuthInterceptor is modified to accept this fallback.
 */
export function useTaskStream(taskUuid) {
  const events = ref([])
  const currentStatus = ref('')
  const llmTokenBuffer = ref('')
  const isStreaming = ref(false)

  let es = null

  function connect() {
    if (es) disconnect()

    const auth = useAuthStore()
    const url = `/api/tasks/${taskUuid}/stream?token=${encodeURIComponent(auth.token || '')}`

    es = new EventSource(url)
    isStreaming.value = true

    es.onmessage = (e) => handleEvent('message', e)

    // Named event listeners for typed SSE events
    const eventTypes = [
      'STATE_CHANGE', 'STEP_DONE', 'TOOL_RESULT', 'LLM_STREAM',
      'COMPLETED', 'ERROR', 'PAUSED', 'PROGRESS_SNAPSHOT', 'RETRY'
    ]
    eventTypes.forEach(type => {
      es.addEventListener(type, (e) => handleEvent(type, e))
    })

    es.onerror = () => {
      // Connection error — stop streaming but don't show error if task is terminal
      isStreaming.value = false
    }
  }

  function handleEvent(type, e) {
    let data = {}
    try {
      data = JSON.parse(e.data)
    } catch {
      data = { message: e.data }
    }

    switch (type) {
      case 'STATE_CHANGE':
        currentStatus.value = data.status || data.data?.status || ''
        pushEvent({ eventType: 'STATE_CHANGE', ...data, createdAt: new Date().toISOString() })
        break

      case 'STEP_DONE':
        pushEvent({ eventType: 'STEP_DONE', ...data, createdAt: new Date().toISOString() })
        break

      case 'LLM_STREAM':
        llmTokenBuffer.value += (data.token || data.content || '')
        break

      case 'TOOL_RESULT':
        pushEvent({ eventType: 'TOOL_RESULT', message: `工具结果: ${data.toolName || ''}`, ...data, createdAt: new Date().toISOString() })
        break

      case 'COMPLETED':
        currentStatus.value = 'completed'
        pushEvent({ eventType: 'COMPLETED', message: '规划完成', ...data, createdAt: new Date().toISOString() })
        disconnect()
        break

      case 'ERROR':
        currentStatus.value = 'failed'
        pushEvent({ eventType: 'ERROR', message: data.message || '任务出错', ...data, createdAt: new Date().toISOString() })
        disconnect()
        break

      case 'PAUSED':
        currentStatus.value = 'paused'
        pushEvent({ eventType: 'PAUSED', message: '任务已暂停（配额耗尽）', ...data, createdAt: new Date().toISOString() })
        break

      case 'PROGRESS_SNAPSHOT':
        // Bulk-load initial state on first connect
        if (data.data?.events?.length) {
          events.value = data.data.events
        }
        if (data.data?.status) currentStatus.value = data.data.status
        break

      case 'RETRY':
        pushEvent({ eventType: 'RETRY', message: `重试中...`, ...data, createdAt: new Date().toISOString() })
        break
    }
  }

  function pushEvent(ev) {
    events.value = [ev, ...events.value].slice(0, 100)
  }

  function disconnect() {
    if (es) {
      es.close()
      es = null
    }
    isStreaming.value = false
  }

  onUnmounted(disconnect)

  return { events, currentStatus, llmTokenBuffer, isStreaming, connect, disconnect }
}
