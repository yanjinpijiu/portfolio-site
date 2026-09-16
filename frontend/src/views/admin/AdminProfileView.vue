<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api } from '../../api'
import ImageField from '../../components/admin/ImageField.vue'

/**
 * 个人资料 + 联系方式。
 *
 * 资料是单例（后端只有一条），所以直接一个表单整体提交；
 * 联系方式是若干条，用表格逐行改。
 */
const profile = reactive({
  name: '',
  nameEn: '',
  title: '',
  school: '',
  graduation: '',
  city: '',
  email: '',
  phone: '',
  avatarKey: '',
  intro: '',
  availability: ''
})

const contacts = ref([])
const loading = ref(true)
const saving = ref(false)
const notice = ref('')
const error = ref('')

/** 新增联系方式用的草稿 */
const draft = reactive({ label: '', valueText: '', href: '', icon: '', sortOrder: 100, visible: true })

function flash(msg) {
  notice.value = msg
  setTimeout(() => {
    if (notice.value === msg) notice.value = ''
  }, 2600)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [p, cs] = await Promise.all([api.adminProfile(), api.adminListContacts()])
    Object.assign(profile, p || {})
    contacts.value = cs || []
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function saveProfile() {
  saving.value = true
  error.value = ''
  try {
    await api.adminUpdateProfile(profile)
    flash('个人资料已保存，前台刷新即可看到')
  } catch (e) {
    error.value = e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function saveContact(item) {
  try {
    await api.adminUpdateContact(item.id, item)
    flash('已保存')
  } catch (e) {
    error.value = e.message || '保存失败'
  }
}

async function removeContact(item) {
  if (!confirm(`删除联系方式「${item.label}」？`)) return
  try {
    await api.adminDeleteContact(item.id)
    contacts.value = contacts.value.filter((c) => c.id !== item.id)
    flash('已删除')
  } catch (e) {
    error.value = e.message || '删除失败'
  }
}

async function addContact() {
  if (!draft.label.trim()) {
    error.value = '请先填一项名称，例如「邮箱」'
    return
  }
  try {
    const created = await api.adminCreateContact({ ...draft })
    contacts.value.push(created)
    Object.assign(draft, { label: '', valueText: '', href: '', icon: '', sortOrder: 100, visible: true })
    flash('已添加')
  } catch (e) {
    error.value = e.message || '添加失败'
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="admin-head">
      <div>
        <h1>个人资料</h1>
        <p class="sub">首页顶部展示的内容，以及页脚、简历页会用到的联系方式。</p>
      </div>
      <RouterLink class="btn btn--ghost btn--sm" to="/" target="_blank">看前台效果</RouterLink>
    </div>

    <div v-if="notice" class="alert alert--ok">{{ notice }}</div>
    <div v-if="error" class="alert alert--error">{{ error }}</div>

    <div v-if="loading" class="skeleton" style="height: 300px"></div>

    <template v-else>
      <div class="card">
        <h2>基本信息</h2>

        <ImageField v-model="profile.avatarKey" kind="avatar" label="头像" hint="会自动缩到 400px 再存" />

        <div class="grid-2">
          <div class="field">
            <label>姓名</label>
            <input v-model="profile.name" class="input" type="text">
          </div>
          <div class="field">
            <label>英文名</label>
            <input v-model="profile.nameEn" class="input" type="text">
          </div>
          <div class="field">
            <label>一句话头衔</label>
            <input v-model="profile.title" class="input" type="text">
          </div>
          <div class="field">
            <label>学校 / 专业</label>
            <input v-model="profile.school" class="input" type="text">
          </div>
          <div class="field">
            <label>届别</label>
            <input v-model="profile.graduation" class="input" type="text">
          </div>
          <div class="field">
            <label>现居城市</label>
            <input v-model="profile.city" class="input" type="text">
          </div>
          <div class="field">
            <label>邮箱</label>
            <input v-model="profile.email" class="input" type="text">
          </div>
          <div class="field">
            <label>电话</label>
            <input v-model="profile.phone" class="input" type="text">
            <p class="muted" style="margin-top: 5px">留空则前台不显示电话</p>
          </div>
        </div>

        <div class="field">
          <label>自我介绍</label>
          <textarea v-model="profile.intro" class="input" rows="5"></textarea>
          <p class="muted" style="margin-top: 5px">一行一段，前台按行拆开显示</p>
        </div>

        <div class="field">
          <label>实习状态 / 期望地点</label>
          <input v-model="profile.availability" class="input" type="text">
        </div>

        <button class="btn btn--primary" type="button" :disabled="saving" @click="saveProfile">
          {{ saving ? '保存中…' : '保存资料' }}
        </button>
      </div>

      <div class="card">
        <h2>联系方式 <span class="muted">首页「联系方式」区和页脚都用这里</span></h2>

        <table class="admin-table">
          <thead>
            <tr>
              <th style="width: 90px">名称</th>
              <th>显示内容</th>
              <th>跳转链接</th>
              <th style="width: 96px">图标</th>
              <th style="width: 70px">排序</th>
              <th style="width: 60px">显示</th>
              <th style="width: 130px"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in contacts" :key="item.id">
              <td><input v-model="item.label" class="input" type="text"></td>
              <td><input v-model="item.valueText" class="input" type="text"></td>
              <td><input v-model="item.href" class="input" type="text" placeholder="mailto: / https://"></td>
              <td>
                <select v-model="item.icon" class="input">
                  <option value="">无</option>
                  <option value="mail">邮件</option>
                  <option value="phone">电话</option>
                  <option value="github">GitHub</option>
                  <option value="link">链接</option>
                </select>
              </td>
              <td><input v-model.number="item.sortOrder" class="input" type="number"></td>
              <td style="text-align: center"><input v-model="item.visible" type="checkbox"></td>
              <td class="nowrap">
                <button class="link-btn" type="button" @click="saveContact(item)">保存</button>
                <button class="link-btn link-btn--danger" type="button" @click="removeContact(item)">删除</button>
              </td>
            </tr>
            <tr v-if="!contacts.length">
              <td colspan="7" class="muted">还没有联系方式</td>
            </tr>
          </tbody>
        </table>

        <h2 style="margin-top: 18px">新增一条</h2>
        <div class="row">
          <input v-model="draft.label" class="input" style="width: 110px" type="text" placeholder="名称">
          <input v-model="draft.valueText" class="input" style="width: 200px" type="text" placeholder="显示内容">
          <input v-model="draft.href" class="input" style="width: 230px" type="text" placeholder="跳转链接">
          <select v-model="draft.icon" class="input" style="width: 110px">
            <option value="">无图标</option>
            <option value="mail">邮件</option>
            <option value="phone">电话</option>
            <option value="github">GitHub</option>
            <option value="link">链接</option>
          </select>
          <button class="btn btn--ghost btn--sm" type="button" @click="addContact">添加</button>
        </div>
        <p class="muted" style="margin-top: 6px">
          图标只影响首页的样式；标成 GitHub 的那条会出现在页头和首页按钮上。
        </p>
      </div>
    </template>
  </div>
</template>
