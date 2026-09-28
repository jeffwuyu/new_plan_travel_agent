import { test, expect } from '@playwright/test'

function ok(data) {
  return { code: 200, data }
}

test.beforeEach(async ({ page }) => {
  await page.route('**/*', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (!url.pathname.startsWith('/api/')) {
      return route.continue()
    }
    if (url.pathname.endsWith('/auth/login')) {
      return route.fulfill({ json: ok({ token: 'e2e-token', userId: 1, username: 'e2e-user', userLevel: 1, userLevelLabel: '普通用户' }) })
    }
    if (url.pathname.endsWith('/user/profile')) {
      return route.fulfill({ json: ok({ id: 1, username: 'e2e-user', userLevel: 1, userLevelLabel: '普通用户' }) })
    }
    if (url.pathname.endsWith('/user/quota')) {
      return route.fulfill({ json: ok({ dailyUsed: 0, dailyLimit: 100000, dailyRemaining: 100000, monthlyUsed: 0, monthlyLimit: 1000000, monthlyRemaining: 1000000 }) })
    }
    if (url.pathname.endsWith('/tasks') && request.method() === 'GET') {
      return route.fulfill({ json: ok([]) })
    }
    if (url.pathname.endsWith('/auth/logout')) {
      return route.fulfill({ json: ok(null) })
    }
    return route.fulfill({ json: ok({}) })
  })
})

test('user can log in and open task creation dialog', async ({ page }) => {
  await page.goto('/login')
  await page.locator('input').nth(0).fill('e2e-user')
  await page.locator('input[type="password"]').fill('password')
  await page.getByRole('button', { name: '登录' }).click()

  await expect(page.getByText('我的规划任务')).toBeVisible()
  await page.getByRole('button', { name: /新建规划/ }).click()
  await expect(page.getByText('新建旅行规划')).toBeVisible()
  await expect(page.getByLabel('目的地 \/ 区域')).toBeVisible()
})
