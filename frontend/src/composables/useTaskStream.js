import { onUnmounted, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { issueTaskStreamTicket } from '@/api/tasks'

export function useTaskStream(taskUuid) {
  const events = ref([])
  const currentStatus = ref('')
  const llmTokenBuffer = ref('')
  const currentTokens = ref(0)
  const isStreaming = ref(false)

  let es = null

  async function connect() {
    if (es) disconnect()

    const auth = useAuthStore()
    let url = ''
    try {
      const res = await issueTaskStreamTicket(taskUuid)
      const ticket = res?.data?.ticket
      if (!ticket) {
        throw new Error('Missing SSE ticket')
      }
      url = `/api/tasks/${taskUuid}/stream?sseTicket=${encodeURIComponent(ticket)}`
    } catch {
      url = `/api/tasks/${taskUuid}/stream?token=${encodeURIComponent(auth.token || '')}`
    }

    es = new EventSource(url)
    isStreaming.value = true

    es.onmessage = (e) => handleEvent('message', e)

    const eventTypes = [
      'STATE_CHANGE',
      'STEP_DONE',
      'TOOL_RESULT',
      'TOOL_DEGRADED',
      'TOOL_RESULT_VALIDATION_WARNING',
      'LLM_STREAM',
      'COMPLETED',
      'ERROR',
      'PAUSED',
      'PROGRESS_SNAPSHOT',
      'RETRY',
      'REWIND',
      'USER_SELECTION_REQUIRED',
      'USER_SELECTION_CONFIRMED',
      'AUTO_SELECTION_APPLIED'
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
        currentTokens.value = toNumber(data.totalTokensUsed ?? data.data?.totalTokensUsed)
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
      case 'TOOL_DEGRADED':
        pushEvent({ eventType: 'TOOL_DEGRADED', ...data, createdAt: new Date().toISOString() })
        break
      case 'TOOL_RESULT_VALIDATION_WARNING':
        pushEvent({ eventType: 'TOOL_RESULT_VALIDATION_WARNING', ...data, createdAt: new Date().toISOString() })
        break
      case 'COMPLETED':
        currentStatus.value = 'completed'
        currentTokens.value = toNumber(data.totalTokensUsed ?? data.data?.totalTokensUsed ?? currentTokens.value)
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
        currentTokens.value = toNumber(data.totalTokensUsed ?? data.data?.totalTokensUsed ?? currentTokens.value)
        break
      case 'RETRY':
        pushEvent({ eventType: 'RETRY', ...data, createdAt: new Date().toISOString() })
        break
      case 'REWIND':
        currentStatus.value = data.status || data.data?.status || 'resuming'
        currentTokens.value = toNumber(data.totalTokensUsed ?? data.data?.totalTokensUsed ?? currentTokens.value)
        pushEvent({ eventType: 'REWIND', ...data, createdAt: new Date().toISOString() })
        break
      case 'USER_SELECTION_REQUIRED':
        currentStatus.value = 'awaiting_user_input'
        currentTokens.value = toNumber(data.totalTokensUsed ?? data.data?.totalTokensUsed ?? currentTokens.value)
        pushEvent({ eventType: 'USER_SELECTION_REQUIRED', ...data, createdAt: new Date().toISOString() })
        break
      case 'USER_SELECTION_CONFIRMED':
        currentStatus.value = 'resuming'
        currentTokens.value = toNumber(data.totalTokensUsed ?? data.data?.totalTokensUsed ?? currentTokens.value)
        pushEvent({ eventType: 'USER_SELECTION_CONFIRMED', ...data, createdAt: new Date().toISOString() })
        break
      case 'AUTO_SELECTION_APPLIED':
        pushEvent({ eventType: 'AUTO_SELECTION_APPLIED', ...data, createdAt: new Date().toISOString() })
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

  function toNumber(value) {
    const num = Number(value)
    return Number.isFinite(num) ? num : 0
  }

  onUnmounted(disconnect)

  return { events, currentStatus, llmTokenBuffer, currentTokens, isStreaming, connect, disconnect }
}
