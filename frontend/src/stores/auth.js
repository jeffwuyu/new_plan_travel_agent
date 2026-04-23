import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getProfile, getQuota } from '@/api/auth'

const STORAGE_KEY = 'travel_agent_auth'

function emptyQuota() {
  return {
    dailyUsed: 0,
    dailyLimit: 0,
    dailyRemaining: 0,
    monthlyUsed: 0,
    monthlyLimit: 0,
    monthlyRemaining: 0,
    maxConcurrentTasks: 0,
    maxPlanSteps: 0
  }
}

function toNumber(value) {
  const num = Number(value)
  return Number.isFinite(num) ? num : 0
}

function normalizeQuota(data = {}) {
  const dailyUsed = toNumber(data.dailyUsed)
  const dailyLimit = toNumber(data.dailyLimit)
  const monthlyUsed = toNumber(data.monthlyUsed)
  const monthlyLimit = toNumber(data.monthlyLimit)
  const dailyRemaining = data.dailyRemaining != null
    ? Math.max(toNumber(data.dailyRemaining), 0)
    : Math.max(dailyLimit - dailyUsed, 0)
  const monthlyRemaining = data.monthlyRemaining != null
    ? Math.max(toNumber(data.monthlyRemaining), 0)
    : Math.max(monthlyLimit - monthlyUsed, 0)

  return {
    dailyUsed,
    dailyLimit,
    dailyRemaining,
    monthlyUsed,
    monthlyLimit,
    monthlyRemaining,
    maxConcurrentTasks: toNumber(data.maxConcurrentTasks),
    maxPlanSteps: toNumber(data.maxPlanSteps)
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(null)
  const userId = ref(null)
  const username = ref(null)
  const userLevel = ref(0)
  const userLevelLabel = ref('')
  const quota = ref(emptyQuota())
  const quotaLoaded = ref(false)
  const hydrated = ref(false)
  const bootstrapped = ref(false)
  let bootstrapPromise = null

  const isAdmin = computed(() => userLevel.value === 3)
  const isLoggedIn = computed(() => !!token.value)

  function persist() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      token: token.value,
      userId: userId.value,
      username: username.value,
      userLevel: userLevel.value,
      userLevelLabel: userLevelLabel.value
    }))
  }

  function loadFromStorage() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (raw) {
        const data = JSON.parse(raw)
        token.value = data.token
        userId.value = data.userId
        username.value = data.username
        userLevel.value = data.userLevel || 0
        userLevelLabel.value = data.userLevelLabel || ''
      }
    } catch {
      // ignore broken storage payloads
    } finally {
      hydrated.value = true
    }
  }

  function setAuth(data) {
    token.value = data.token
    userId.value = data.userId
    username.value = data.username
    userLevel.value = data.userLevel || 0
    userLevelLabel.value = data.userLevelLabel || userLevelLabel.value || ''
    bootstrapped.value = false
    persist()
  }

  async function refreshProfile() {
    if (!token.value) return null
    const res = await getProfile()
    const profile = res.data || {}
    userId.value = profile.id ?? userId.value
    username.value = profile.username ?? username.value
    userLevel.value = profile.userLevel ?? userLevel.value
    userLevelLabel.value = profile.userLevelLabel ?? userLevelLabel.value
    persist()
    return profile
  }

  async function refreshQuota() {
    if (!token.value) {
      quota.value = emptyQuota()
      quotaLoaded.value = false
      return quota.value
    }
    const res = await getQuota()
    const data = res.data || {}
    quota.value = normalizeQuota(data)
    quotaLoaded.value = true
    if (data.userLevel != null) {
      userLevel.value = data.userLevel
    }
    if (data.userLevelLabel) {
      userLevelLabel.value = data.userLevelLabel
    }
    persist()
    return quota.value
  }

  async function bootstrapAuthData() {
    if (!token.value) {
      bootstrapped.value = true
      return null
    }
    if (bootstrapPromise) {
      return bootstrapPromise
    }
    bootstrapPromise = (async () => {
      try {
        await Promise.allSettled([refreshProfile(), refreshQuota()])
      } finally {
        bootstrapped.value = true
        bootstrapPromise = null
      }
    })()
    return bootstrapPromise
  }

  function logout() {
    token.value = null
    userId.value = null
    username.value = null
    userLevel.value = 0
    userLevelLabel.value = ''
    quota.value = emptyQuota()
    quotaLoaded.value = false
    bootstrapped.value = false
    localStorage.removeItem(STORAGE_KEY)
  }

  return {
    token,
    userId,
    username,
    userLevel,
    userLevelLabel,
    quota,
    quotaLoaded,
    hydrated,
    bootstrapped,
    isAdmin,
    isLoggedIn,
    loadFromStorage,
    setAuth,
    refreshProfile,
    refreshQuota,
    bootstrapAuthData,
    logout
  }
})
