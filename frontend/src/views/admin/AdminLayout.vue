<template>
  <el-container class="admin-container">
    <el-aside width="200px" class="admin-aside">
      <div class="aside-logo">管理后台</div>
      <el-menu :default-active="$route.path" router>
        <el-menu-item index="/admin/users">
          <el-icon><User /></el-icon> 用户管理
        </el-menu-item>
        <el-menu-item index="/admin/quota">
          <el-icon><Coin /></el-icon> 配额配置
        </el-menu-item>
        <el-menu-item index="/admin/rag">
          <el-icon><Document /></el-icon> RAG 文档
        </el-menu-item>
        <el-menu-item index="/admin/metrics">
          <el-icon><DataLine /></el-icon> 系统指标
        </el-menu-item>
        <el-menu-item index="/tasks">
          <el-icon><Back /></el-icon> 返回前台
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-main class="admin-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.admin-container { min-height: 100vh; }
.admin-aside {
  background: #304156;
  color: #fff;
}
.aside-logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  font-weight: 600;
  color: #fff;
  border-bottom: 1px solid #435168;
}
.admin-main { background: #f5f7fa; padding: 24px; }
:deep(.el-menu) { background: #304156; border: none; }
:deep(.el-menu-item) { color: #bfcbd9; }
:deep(.el-menu-item.is-active) { color: #409eff; background: #263445; }
:deep(.el-menu-item:hover) { background: #263445; color: #fff; }
</style>
