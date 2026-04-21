import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

const STORAGE_KEY = 'travel_agent_auth'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(null)
  const userId = ref(null)
  const username = ref(null)
  const userLevel = ref(0)

  const isAdmin = computed(() => userLevel.value === 3)
  const isLoggedIn = computed(() => !!token.value)

  function loadFromStorage() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (raw) {
        const data = JSON.parse(raw)
        token.value = data.token
        userId.value = data.userId
        username.value = data.username
        userLevel.value = data.userLevel || 0
      }
    } catch {
      // ignore
    }
  }

  function setAuth(data) {
    token.value = data.token
    userId.value = data.userId
    username.value = data.username
    userLevel.value = data.userLevel || 0
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      token: data.token,
      userId: data.userId,
      username: data.username,
      userLevel: data.userLevel
    }))
  }

  function logout() {
    token.value = null
    userId.value = null
    username.value = null
    userLevel.value = 0
    localStorage.removeItem(STORAGE_KEY)
  }

  return { token, userId, username, userLevel, isAdmin, isLoggedIn, loadFromStorage, setAuth, logout }
})
