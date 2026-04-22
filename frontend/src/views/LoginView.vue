<template>
  <div class="auth-container">
    <el-card class="auth-card">
      <template #header>
        <h2 class="auth-title">旅行规划助手</h2>
        <p class="auth-sub">登录您的账户</p>
      </template>
      <el-form :model="form" :rules="rules" ref="formRef" label-position="top" @submit.prevent="handleLogin">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" prefix-icon="User" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            prefix-icon="Lock"
            placeholder="请输入密码"
            show-password
          />
        </el-form-item>
        <el-button type="primary" native-type="submit" class="auth-btn" :loading="loading">登录</el-button>
      </el-form>
      <div class="auth-footer">
        没有账号？<router-link to="/register">立即注册</router-link>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const formRef = ref(null)
const loading = ref(false)

const form = ref({ username: '', password: '' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function handleLogin() {
  await formRef.value.validate()
  loading.value = true
  try {
    const res = await login(form.value)
    auth.setAuth(res.data)
    await auth.bootstrapAuthData()
    const redirect = route.query.redirect || '/tasks'
    router.push(redirect)
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-container {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}
.auth-card {
  width: 400px;
}
.auth-title {
  margin: 0;
  font-size: 22px;
  color: #303133;
}
.auth-sub {
  margin: 4px 0 0;
  color: #909399;
  font-size: 14px;
}
.auth-btn {
  width: 100%;
  margin-top: 8px;
}
.auth-footer {
  margin-top: 16px;
  text-align: center;
  font-size: 14px;
  color: #606266;
}
</style>
