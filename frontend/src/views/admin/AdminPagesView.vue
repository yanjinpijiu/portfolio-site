<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../../api'

/**
 * 页面文案 + 站点设置。
 *
 * 页面文案（sections）是各页的大标题和副标题；站点设置（settings）是 SEO、
 * 页脚版权、后台入口文字这些散着的键值。
 *
 * SEO 那几项要特别提醒：爬虫读的是 index.html 里的静态 meta，
 * 在这儿改了标题不会自动同步过去，得手工改一次 index.html 再重新部署。
 */
const sections = ref([])
const settings = ref([])
const loading = ref(true)
const notice = ref('')
const error = ref('')

/** 需要人工同步到 index.html 的键 */
const SEO_KEYS = ['seo.title', 'seo.description', 'seo.ogTitle', 'seo.ogDescription']

function flash(msg) {
  notice.value = msg
  setTimeout(() => {
    if (notice.value === msg) notice.value = ''
  }, 2600)
}

async function load() {
  loading.value = true
  try {
    const [s, st] = await Promise.all([api.adminListSections(), api.adminListSettings()])
    sections.value = s || []
    settings.value = st || []
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function saveSection(item) {
  try {
    await api.adminUpdateSection(item.id, item)
    flash('已保存')
  } catch (e) {
    error.value = e.message || '保存失败'
  }
}

async function saveSettings() {
  try {
    const values = {}
    settings.value.forEach((s) => {
      values[s.settingKey] = s.settingValue ?? ''
    })
    await api.adminSaveSettings(values)
    flash('站点设置已保存')
  } catch (e) {
    error.value = e.message || '保存失败'
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="admin-head">
      <div>
        <h1>页面文案</h1>
        <p class="sub">各页的大标题、副标题，以及全站的 SEO / 页脚文字。按钮和提示语这类界面文字不在这里，它们属于页面骨架。</p>
      </div>
    </div>

    <div v-if="notice" class="alert alert--ok">{{ notice }}</div>
    <div v-if="error" class="alert alert--error">{{ error }}</div>

    <div v-if="loading" class="skeleton" style="height: 300px"></div>

    <template v-else>
      <div class="card">
        <h2>页面标题与副标题</h2>
        <table class="admin-table">
          <thead>
            <tr>
              <th style="width: 150px">位置</th>
              <th style="width: 110px">小标签</th>
              <th style="width: 170px">大标题</th>
              <th>副标题</th>
              <th style="width: 70px"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in sections" :key="item.id">
              <td><code class="muted">{{ item.sectionKey }}</code></td>
              <td><input v-model="item.eyebrow" class="input" type="text"></td>
              <td><input v-model="item.title" class="input" type="text"></td>
              <td><input v-model="item.description" class="input" type="text"></td>
              <td><button class="link-btn" type="button" @click="saveSection(item)">保存</button></td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="card">
        <h2>站点设置</h2>
        <div v-for="item in settings" :key="item.id" class="field">
          <label>{{ item.label || item.settingKey }}</label>
          <textarea
            v-if="(item.settingValue || '').length > 60 || item.settingKey.includes('description')"
            v-model="item.settingValue"
            class="input"
            rows="2"
          ></textarea>
          <input v-else v-model="item.settingValue" class="input" type="text">
          <p v-if="item.hint" class="muted" style="margin-top: 5px">{{ item.hint }}</p>
          <p v-if="SEO_KEYS.includes(item.settingKey)" class="muted" style="margin-top: 5px; color: #8A5A2B">
            搜索引擎读的是 index.html 里的静态内容，改完这里还要手动改一次 frontend/index.html 再重新部署才会对爬虫生效（页面里的显示不受影响）。
          </p>
        </div>

        <button class="btn btn--primary" type="button" @click="saveSettings">保存站点设置</button>
      </div>
    </template>
  </div>
</template>
