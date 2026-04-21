<template>
  <div>
    <h3 class="page-title">用户管理</h3>
    <el-table :data="users" v-loading="loading" stripe border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="username" label="用户名" min-width="120" />
      <el-table-column prop="email" label="邮箱" min-width="180" />
      <el-table-column label="用户等级" width="140">
        <template #default="{ row }">
          <el-select v-model="row.userLevel" size="small" @change="(v) => saveLevel(row, v)">
            <el-option :value="1" label="普通用户" />
            <el-option :value="2" label="VIP 用户" />
            <el-option :value="3" label="管理员" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-switch
            :model-value="row.status === 1"
            @change="(v) => saveStatus(row, v)"
            active-color="#13ce66"
            inactive-color="#ff4949"
          />
        </template>
      </el-table-column>
      <el-table-column label="注册时间" width="160">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="total > pageSize"
      class="mt-16"
      layout="prev, pager, next"
      :total="total"
      :page-size="pageSize"
      :current-page="page"
      @current-change="onPageChange"
    />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listUsers, updateUserLevel, updateUserStatus } from '@/api/admin'

const users = ref([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)

onMounted(() => fetchUsers())

async function fetchUsers() {
  loading.value = true
  try {
    const res = await listUsers(page.value, pageSize.value)
    users.value = res.data?.records || res.data || []
    total.value = res.data?.total || users.value.length
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}

async function saveLevel(row, level) {
  try {
    await updateUserLevel(row.id, level)
    ElMessage.success('用户等级已更新')
  } catch (err) {
    ElMessage.error(err.message)
    await fetchUsers() // revert
  }
}

async function saveStatus(row, enabled) {
  const newStatus = enabled ? 1 : 0
  try {
    await updateUserStatus(row.id, newStatus)
    row.status = newStatus
    ElMessage.success(enabled ? '用户已启用' : '用户已禁用')
  } catch (err) {
    ElMessage.error(err.message)
  }
}

function onPageChange(p) {
  page.value = p
  fetchUsers()
}

function formatTime(ts) {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}
</script>

<style scoped>
.page-title { margin: 0 0 16px; font-size: 18px; }
.mt-16 { margin-top: 16px; }
</style>
