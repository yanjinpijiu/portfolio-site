<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api } from '../../api'
import ImageField from '../../components/admin/ImageField.vue'

/**
 * 证书管理。证书可以挂到某个技能分组下（首页点开那个分组就能看到），
 * 也可以不挂（那就只在后台存在，前台不显示）。
 */
const certs = ref([])
const groups = ref([])
const loading = ref(true)
const notice = ref('')
const error = ref('')

/** 正在编辑的证书 id，null = 关闭编辑面板 */
const editingId = ref(null)
const form = reactive({
  title: '',
  org: '',
  certDate: '',
  summary: '',
  imageKey: '',
  ratio: '4/3',
  groupId: null,
  sortOrder: 100,
  visible: true
})

function flash(msg) {
  notice.value = msg
  setTimeout(() => {
    if (notice.value === msg) notice.value = ''
  }, 2400)
}

async function load() {
  loading.value = true
  try {
    const [c, g] = await Promise.all([api.adminListCertificates(), api.adminListSkillGroups()])
    certs.value = c || []
    groups.value = g || []
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function startCreate() {
  editingId.value = 0
  Object.assign(form, {
    title: '',
    org: '',
    certDate: '',
    summary: '',
    imageKey: '',
    ratio: '4/3',
    groupId: groups.value[0]?.id ?? null,
    sortOrder: (certs.value.length + 1) * 10,
    visible: true
  })
}

function startEdit(item) {
  editingId.value = item.id
  Object.assign(form, item)
}

async function submit() {
  if (!form.title.trim()) {
    error.value = '证书名称不能为空'
    return
  }
  try {
    if (editingId.value === 0) {
      const created = await api.adminCreateCertificate({ ...form })
      certs.value.push(created)
      flash('已新增')
    } else {
      const saved = await api.adminUpdateCertificate(editingId.value, { ...form })
      const idx = certs.value.findIndex((c) => c.id === editingId.value)
      if (idx >= 0) certs.value[idx] = saved
      flash('已保存')
    }
    editingId.value = null
  } catch (e) {
    error.value = e.message || '保存失败'
  }
}

async function remove(item) {
  if (!confirm(`删除证书「${item.title}」？图片文件不会被删。`)) return
  try {
    await api.adminDeleteCertificate(item.id)
    certs.value = certs.value.filter((c) => c.id !== item.id)
    if (editingId.value === item.id) editingId.value = null
    flash('已删除')
  } catch (e) {
    error.value = e.message || '删除失败'
  }
}

/** 分组名，用来在列表里显示证书挂在哪 */
const groupName = (id) => groups.value.find((g) => g.id === id)?.category || '—'

onMounted(load)
</script>

<template>
  <div>
    <div class="admin-head">
      <div>
        <h1>证书</h1>
        <p class="sub">挂到技能分组后，首页点开那个分组就能看到证书缩略图，点图放大。</p>
      </div>
      <button class="btn btn--primary btn--sm" type="button" @click="startCreate">新增证书</button>
    </div>

    <div v-if="notice" class="alert alert--ok">{{ notice }}</div>
    <div v-if="error" class="alert alert--error">{{ error }}</div>

    <div v-if="loading" class="skeleton" style="height: 240px"></div>

    <template v-else>
      <div v-if="editingId !== null" class="card">
        <h2>{{ editingId === 0 ? '新增证书' : '编辑证书' }}</h2>
        <ImageField v-model="form.imageKey" label="证书图片" hint="会自动缩到 1600px 再存" />
        <div class="grid-2">
          <div class="field">
            <label>名称</label>
            <input v-model="form.title" class="input" type="text">
          </div>
          <div class="field">
            <label>颁发机构</label>
            <input v-model="form.org" class="input" type="text">
          </div>
          <div class="field">
            <label>时间</label>
            <input v-model="form.certDate" class="input" type="text" placeholder="2024.06">
          </div>
          <div class="field">
            <label>缩略图比例</label>
            <input v-model="form.ratio" class="input" type="text" placeholder="3/4 或 4/3">
          </div>
          <div class="field">
            <label>挂在哪个技能分组</label>
            <select v-model="form.groupId" class="input">
              <option :value="null">不挂（前台不显示）</option>
              <option v-for="g in groups" :key="g.id" :value="g.id">{{ g.category }}</option>
            </select>
          </div>
          <div class="field">
            <label>排序（小的在前）</label>
            <input v-model.number="form.sortOrder" class="input" type="number">
          </div>
        </div>
        <div class="field">
          <label>说明</label>
          <input v-model="form.summary" class="input" type="text">
        </div>
        <div class="row">
          <label class="row" style="gap: 6px">
            <input v-model="form.visible" type="checkbox">
            <span>前台可见</span>
          </label>
          <button class="btn btn--primary btn--sm" type="button" @click="submit">保存</button>
          <button class="btn btn--ghost btn--sm" type="button" @click="editingId = null">取消</button>
        </div>
      </div>

      <div class="card">
        <table class="admin-table">
          <thead>
            <tr>
              <th style="width: 90px">图片</th>
              <th>名称</th>
              <th style="width: 170px">机构</th>
              <th style="width: 110px">时间</th>
              <th style="width: 120px">分组</th>
              <th style="width: 70px">排序</th>
              <th style="width: 60px">显示</th>
              <th style="width: 110px"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in certs" :key="item.id">
              <td>
                <img v-if="item.imageKey" class="thumb" :src="`/files/${item.imageKey}`" :alt="item.title">
                <span v-else class="muted">—</span>
              </td>
              <td>
                <div>{{ item.title }}</div>
                <div class="muted">{{ item.summary }}</div>
              </td>
              <td class="muted">{{ item.org }}</td>
              <td class="muted">{{ item.certDate }}</td>
              <td class="muted">{{ groupName(item.groupId) }}</td>
              <td class="num">{{ item.sortOrder }}</td>
              <td>{{ item.visible ? '是' : '否' }}</td>
              <td class="nowrap">
                <button class="link-btn" type="button" @click="startEdit(item)">编辑</button>
                <button class="link-btn link-btn--danger" type="button" @click="remove(item)">删除</button>
              </td>
            </tr>
            <tr v-if="!certs.length">
              <td colspan="8" class="muted">还没有证书</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>
