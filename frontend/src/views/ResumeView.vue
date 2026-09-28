<script setup>
import { computed, onMounted, ref } from 'vue'
import { formatDate, formatSize } from '../api'
import { fetchResumes, section, siteState } from '../store/site'
import ResumeDownloadButton from '../components/ResumeDownloadButton.vue'

const site = siteState()

const resumes = ref([])
const loading = ref(true)
const error = ref('')

const header = computed(() => section('resume.header'))
const email = computed(() => site.profile.email || '')

onMounted(async () => {
  try {
    resumes.value = (await fetchResumes()) || []
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <section class="section">
    <div class="container">
      <div class="section-head">
        <p v-if="header.eyebrow" class="section-eyebrow">{{ header.eyebrow }}</p>
        <h2 class="section-title">{{ header.title }}</h2>
        <p v-if="header.description" class="section-desc">{{ header.description }}</p>
      </div>

      <div v-if="loading" class="resume-list">
        <div v-for="i in 2" :key="i" class="skeleton" style="height: 96px"></div>
      </div>

      <div v-else-if="error" class="empty">{{ error }}</div>

      <div v-else-if="!resumes.length" class="empty">
        <p>简历文件还没上传。</p>
        <p v-if="email" style="margin-top: 8px; font-size: 13px">
          可以先发邮件到 <a :href="`mailto:${email}`">{{ email }}</a> 索取。
        </p>
      </div>

      <div v-else class="resume-list">
        <div v-for="item in resumes" :key="item.id" class="resume-item">
          <div class="resume-item__main">
            <div class="resume-item__title">{{ item.title }}</div>
            <div class="resume-item__meta">
              <span v-if="item.direction">方向：{{ item.direction }}</span>
              <span>{{ item.fileName }}</span>
              <span>{{ formatSize(item.fileSize) }}</span>
              <span>更新于 {{ formatDate(item.updatedAt || item.createdAt) }}</span>
            </div>
          </div>
          <ResumeDownloadButton :resume="item" label="下载 PDF" />
        </div>
      </div>

      <p v-if="email" style="margin-top: 26px; font-size: 13px; color: var(--ink-400)">
        如果下载遇到问题，也可以直接邮件联系：<a :href="`mailto:${email}`">{{ email }}</a>
      </p>
    </div>
  </section>
</template>
