import { ref, onUnmounted } from 'vue'
import { useAuthStore } from '@/stores/auth'

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

    const eventTypes = [
      'STATE_CHANGE',
      'STEP_DONE',
      'TOOL_RESULT',
      'LLM_STREAM',
      'COMPLETED',
      'ERROR',
      'PAUSED',
      'PROGRESS_SNAPSHOT',
      'RETRY',
      'USER_SELECTION_REQUIRED',
      'USER_SELECTION_CONFIRMED'
    ]
    eventTypes.forEach(type => {
      es.addEventListener(type, (e) => handleEvent(type, e))
    })

    es.onerror = () => {
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
        pushEvent({ eventType: 'TOOL_RESULT', ...data, createdAt: new Date().toISOString() })
        break
      case 'COMPLETED':
        currentStatus.value = 'completed'
        pushEvent({ eventType: 'COMPLETED', ...data, createdAt: new Date().toISOString() })
        disconnect()
        break
      case 'ERROR':
        currentStatus.value = 'failed'
        pushEvent({ eventType: 'ERROR', ...data, createdAt: new Date().toISOString() })
        disconnect()
        break
      case 'PAUSED':
        currentStatus.value = 'paused'
        pushEvent({ eventType: 'PAUSED', ...data, createdAt: new Date().toISOString() })
        break
      case 'PROGRESS_SNAPSHOT':
        if (data.events?.length) {
          events.value = data.events
        } else if (data.data?.events?.length) {
          events.value = data.data.events
        }
        currentStatus.value = data.currentStatus || data.data?.currentStatus || data.data?.status || currentStatus.value
        break
      case 'RETRY':
        pushEvent({ eventType: 'RETRY', ...data, createdAt: new Date().toISOString() })
        break
      case 'USER_SELECTION_REQUIRED':
        currentStatus.value = 'awaiting_user_input'
        pushEvent({ eventType: 'USER_SELECTION_REQUIRED', ...data, createdAt: new Date().toISOString() })
        break
      case 'USER_SELECTION_CONFIRMED':
        currentStatus.value = 'resuming'
        pushEvent({ eventType: 'USER_SELECTION_CONFIRMED', ...data, createdAt: new Date().toISOString() })
        break
      default:
        pushEvent({ eventType: type, ...data, createdAt: new Date().toISOString() })
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
