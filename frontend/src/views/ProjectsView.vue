<script setup>
import { computed, onMounted, ref } from 'vue'
import { fetchProjects } from '../store/site'
import { section } from '../store/site'
import ProjectCard from '../components/ProjectCard.vue'

const projects = ref([])
const loading = ref(true)
const error = ref('')

const featured = computed(() => projects.value.filter((p) => p.type !== '其他实践'))
const practices = computed(() => projects.value.filter((p) => p.type === '其他实践'))

const header = computed(() => section('projects.header'))
const practiceSection = computed(() => section('projects.practice'))

onMounted(async () => {
  try {
    projects.value = (await fetchProjects()) || []
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

      <div v-if="loading" class="project-grid">
        <div v-for="i in 4" :key="i" class="skeleton" style="height: 230px"></div>
      </div>

      <div v-else-if="error" class="empty">{{ error }}</div>

      <template v-else>
        <div v-if="featured.length" class="project-grid">
          <ProjectCard v-for="project in featured" :key="project.id" :project="project" />
        </div>

        <template v-if="practices.length">
          <div class="section-head" style="margin-top: 46px; margin-bottom: 16px">
            <h2 class="section-title" style="font-size: 17px">{{ practiceSection.title }}</h2>
            <p v-if="practiceSection.description" class="section-desc">{{ practiceSection.description }}</p>
          </div>
          <div class="practice-list">
            <RouterLink
              v-for="item in practices"
              :key="item.id"
              class="practice-item"
              :to="`/projects/${item.slug}`"
            >
              <span class="practice-item__name">{{ item.name }}</span>
              <span class="practice-item__summary">{{ item.summary }}</span>
              <span class="practice-item__period">{{ item.period }}</span>
            </RouterLink>
          </div>
        </template>
      </template>
    </div>
  </section>
</template>
