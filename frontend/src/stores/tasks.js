import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listTasks } from '@/api/tasks'

export const useTasksStore = defineStore('tasks', () => {
  const tasks = ref([])
  const loading = ref(false)

  const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled'])

  function hasActiveTask() {
    return tasks.value.some(t => !TERMINAL_STATUSES.has(t.status))
  }

  async function fetchTasks() {
    loading.value = true
    try {
      const res = await listTasks()
      tasks.value = res.data || []
    } finally {
      loading.value = false
    }
  }

  return { tasks, loading, hasActiveTask, fetchTasks }
})
