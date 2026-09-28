<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, formatDate, formatSize, tokenStore } from '../../api'

const router = useRouter()

const resumes = ref([])
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')

/* 上传表单 */
const form = reactive({
  file: null,
  title: '',
  direction: '',
  active: true
})
const fileInput = ref(null)

/* 行内编辑 */
const editingId = ref(null)
const editForm = reactive({ title: '', direction: '', sortOrder: 0 })

function flash(message) {
  notice.value = message
  setTimeout(() => {
    if (notice.value === message) notice.value = ''
  }, 2600)
}

function handleAuthError(e) {
  if (e.code === 401) {
    tokenStore.clear()
    router.replace({ name: 'admin-login' })
    return true
  }
  return false
}

async function load() {
  loading.value = true
  try {
    resumes.value = (await api.adminListResumes()) || []
  } catch (e) {
    if (!handleAuthError(e)) error.value = e.message
  } finally {
    loading.value = false
  }
}

function onFileChange(event) {
  const file = event.target.files?.[0] || null
  form.file = file
  if (file && !form.title) {
    form.title = file.name.replace(/\.[^.]+$/, '')
  }
}

async function upload() {
  if (!form.file) {
    error.value = '请先选择 PDF 文件'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const data = new FormData()
    data.append('file', form.file)
    if (form.title) data.append('title', form.title)
    if (form.direction) data.append('direction', form.direction)
    data.append('active', String(form.active))

    await api.adminUploadResume(data)
    form.file = null
    form.title = ''
    form.direction = ''
    form.active = true
    if (fileInput.value) fileInput.value.value = ''
    flash('上传成功')
    await load()
  } catch (e) {
    if (!handleAuthError(e)) error.value = e.message
  } finally {
    busy.value = false
  }
}

function startEdit(item) {
  editingId.value = item.id
  editForm.title = item.title
  editForm.direction = item.direction || ''
  editForm.sortOrder = item.sortOrder ?? 0
}

function cancelEdit() {
  editingId.value = null
}

async function saveEdit(id) {
  busy.value = true
  error.value = ''
  try {
    await api.adminUpdateResume(id, {
      title: editForm.title,
      direction: editForm.direction,
      sortOrder: editForm.sortOrder
    })
    editingId.value = null
    flash('已保存')
    await load()
  } catch (e) {
    if (!handleAuthError(e)) error.value = e.message
  } finally {
    busy.value = false
  }
}

async function toggleActive(item) {
  busy.value = true
  error.value = ''
  try {
    await api.adminUpdateResume(item.id, { active: !item.active })
    flash(item.active ? '已设为隐藏' : '已设为公开')
    await load()
  } catch (e) {
    if (!handleAuthError(e)) error.value = e.message
  } finally {
    busy.value = false
  }
}

async function preview(item) {
  error.value = ''
  try {
    const url = await api.adminPreview(item.id)
    window.open(url, '_blank', 'noopener')
    setTimeout(() => URL.revokeObjectURL(url), 60_000)
  } catch (e) {
    if (!handleAuthError(e)) error.value = e.message
  }
}

async function remove(item) {
  if (!window.confirm(`确定删除「${item.title}」？文件会一并从存储中移除，无法恢复。`)) return
  busy.value = true
  error.value = ''
  try {
    await api.adminDeleteResume(item.id)
    flash('已删除')
    await load()
  } catch (e) {
    if (!handleAuthError(e)) error.value = e.message
  } finally {
    busy.value = false
  }
}


onMounted(load)
</script>

<template>
  <div>
    <div>
      <div class="admin-bar">
        <div>
          <h1>简历管理</h1>
          <p class="sub">上传、编辑、公开或删除对外展示的简历 PDF。</p>
        </div>
        <div class="admin-actions">
          <RouterLink class="btn btn--ghost btn--sm" to="/resume">查看前台</RouterLink>
        </div>
      </div>

      <div v-if="error" class="alert alert--error">{{ error }}</div>
      <div v-if="notice" class="alert alert--ok">{{ notice }}</div>

      <!-- 上传 -->
      <div class="panel">
        <h2>上传新简历</h2>
        <div class="form-row">
          <div class="field">
            <label for="resume-file">PDF 文件</label>
            <input
              id="resume-file"
              ref="fileInput"
              class="input"
              type="file"
              accept="application/pdf,.pdf"
              @change="onFileChange"
            />
          </div>
          <div class="field">
            <label for="resume-title">名称</label>
            <input id="resume-title" v-model="form.title" class="input" placeholder="如：AI Agent / 后端方向" />
          </div>
          <div class="field">
            <label for="resume-direction">投递方向</label>
            <input id="resume-direction" v-model="form.direction" class="input" placeholder="如：开发" />
          </div>
          <div class="field">
            <label class="checkbox">
              <input v-model="form.active" type="checkbox" />
              上传后立即公开
            </label>
          </div>
          <div class="field">
            <button class="btn btn--primary" type="button" :disabled="busy" @click="upload">
              <AppIcon name="plus" :size="15" />
              上传
            </button>
          </div>
        </div>
      </div>

      <!-- 列表 -->
      <div class="panel">
        <h2>已上传的简历（{{ resumes.length }}）</h2>

        <div v-if="loading" class="skeleton" style="height: 140px"></div>

        <div v-else-if="!resumes.length" class="empty">还没有上传任何简历。</div>

        <table v-else class="admin-table">
          <thead>
            <tr>
              <th>名称 / 方向</th>
              <th>文件</th>
              <th>状态</th>
              <th>下载</th>
              <th style="text-align: right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in resumes" :key="item.id">
              <td>
                <template v-if="editingId === item.id">
                  <input v-model="editForm.title" class="input" placeholder="名称" style="margin-bottom: 6px" />
                  <input v-model="editForm.direction" class="input" placeholder="方向" style="margin-bottom: 6px" />
                  <input v-model.number="editForm.sortOrder" class="input" type="number" placeholder="排序值" />
                </template>
                <template v-else>
                  <div class="col-file">{{ item.title }}</div>
                  <div style="font-size: 12.5px; color: var(--ink-400)">
                    {{ item.direction || '未设方向' }} · 排序 {{ item.sortOrder ?? 0 }}
                  </div>
                </template>
              </td>
              <td>
                <div>{{ item.fileName }}</div>
                <div style="font-size: 12.5px; color: var(--ink-400)">
                  {{ formatSize(item.fileSize) }} · {{ formatDate(item.updatedAt || item.createdAt) }}
                </div>
              </td>
              <td>
                <span class="badge" :class="{ 'badge--muted': !item.active }">
                  {{ item.active ? '公开' : '隐藏' }}
                </span>
              </td>
              <td>{{ item.downloadCount ?? 0 }}</td>
              <td>
                <div class="row-actions">
                  <template v-if="editingId === item.id">
                    <button class="btn btn--primary btn--sm" type="button" :disabled="busy" @click="saveEdit(item.id)">
                      保存
                    </button>
                    <button class="btn btn--ghost btn--sm" type="button" @click="cancelEdit">取消</button>
                  </template>
                  <template v-else>
                    <button class="btn btn--ghost btn--sm" type="button" @click="preview(item)">
                      <AppIcon name="eye" :size="14" />
                      预览
                    </button>
                    <button class="btn btn--ghost btn--sm" type="button" :disabled="busy" @click="toggleActive(item)">
                      {{ item.active ? '隐藏' : '公开' }}
                    </button>
                    <button class="btn btn--ghost btn--sm" type="button" @click="startEdit(item)">编辑</button>
                    <button class="btn btn--danger btn--sm" type="button" :disabled="busy" @click="remove(item)">
                      <AppIcon name="trash" :size="14" />
                      删除
                    </button>
                  </template>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>
