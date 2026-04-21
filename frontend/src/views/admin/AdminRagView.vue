<template>
  <div>
    <h3 class="page-title">RAG 文档管理</h3>

    <!-- Upload form -->
    <el-card header="上传新文档" class="mb-20">
      <el-form :model="uploadForm" :rules="uploadRules" ref="uploadFormRef" label-position="top" inline>
        <el-form-item label="文档标题" prop="title">
          <el-input v-model="uploadForm.title" placeholder="文档标题" style="width:200px" />
        </el-form-item>
        <el-form-item label="地区（可选）">
          <el-input v-model="uploadForm.region" placeholder="例如：西安市" style="width:160px" />
        </el-form-item>
        <el-form-item label="文档类型" prop="docType">
          <el-select v-model="uploadForm.docType" style="width:120px">
            <el-option value="pdf" label="PDF" />
            <el-option value="markdown" label="Markdown" />
            <el-option value="text" label="纯文本" />
          </el-select>
        </el-form-item>
        <el-form-item label="选择文件" prop="file">
          <el-upload
            ref="uploadRef"
            :auto-upload="false"
            :limit="1"
            :on-change="onFileChange"
            :on-exceed="() => ElMessage.warning('只能选择一个文件')"
          >
            <el-button size="small">选择文件</el-button>
          </el-upload>
        </el-form-item>
        <el-form-item label=" " style="margin-top:0">
          <el-button type="primary" :loading="uploading" @click="handleUpload">上传并入库</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Document list -->
    <el-card header="文档列表">
      <div class="toolbar mb-12">
        <el-button size="small" @click="fetchDocs" :loading="loading">刷新</el-button>
      </div>
      <el-table :data="docs" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="title" label="标题" min-width="160" />
        <el-table-column prop="region" label="地区" width="100" />
        <el-table-column prop="docType" label="类型" width="90" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误信息" min-width="160" show-overflow-tooltip />
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'pending'"
              size="small" type="primary"
              @click="handleIngest(row.id)"
            >触发入库</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { uploadRagDocument, listRagDocuments, ingestDocument } from '@/api/admin'

const docs = ref([])
const loading = ref(false)
const uploading = ref(false)
const uploadFormRef = ref(null)
const uploadRef = ref(null)
const selectedFile = ref(null)

const uploadForm = ref({ title: '', region: '', docType: 'pdf', file: null })
const uploadRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  docType: [{ required: true, message: '请选择类型', trigger: 'change' }]
}

onMounted(fetchDocs)

async function fetchDocs() {
  loading.value = true
  try {
    const res = await listRagDocuments()
    docs.value = res.data || []
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}

function onFileChange(file) {
  selectedFile.value = file.raw
}

async function handleUpload() {
  await uploadFormRef.value.validate()
  if (!selectedFile.value) {
    ElMessage.warning('请先选择文件')
    return
  }
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', selectedFile.value)
    fd.append('title', uploadForm.value.title)
    fd.append('docType', uploadForm.value.docType)
    if (uploadForm.value.region) fd.append('region', uploadForm.value.region)

    await uploadRagDocument(fd)
    ElMessage.success('文档上传成功，正在异步入库')
    uploadFormRef.value.resetFields()
    uploadRef.value.clearFiles()
    selectedFile.value = null
    await fetchDocs()
  } catch (err) {
    ElMessage.error(err.message)
  } finally {
    uploading.value = false
  }
}

async function handleIngest(id) {
  try {
    await ingestDocument(id)
    ElMessage.success('已触发入库，请稍后刷新查看状态')
  } catch (err) {
    ElMessage.error(err.message)
  }
}

const STATUS_MAP = {
  pending: { type: 'warning', label: '待入库' },
  indexed: { type: 'success', label: '已入库' },
  failed:  { type: 'danger',  label: '失败' }
}
const statusType  = (s) => STATUS_MAP[s]?.type  ?? 'info'
const statusLabel = (s) => STATUS_MAP[s]?.label ?? s

function formatTime(ts) {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}
</script>

<style scoped>
.page-title { margin: 0 0 16px; font-size: 18px; }
.mb-20 { margin-bottom: 20px; }
.mb-12 { margin-bottom: 12px; }
.toolbar { display: flex; justify-content: flex-end; }
</style>
