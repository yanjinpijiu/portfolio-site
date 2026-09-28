<script setup>
import { computed } from 'vue'
import { toLines } from '../api'

const props = defineProps({
  project: { type: Object, required: true }
})

const tags = computed(() => toLines(props.project.tags).slice(0, 6))
</script>

<template>
  <article class="project-card">
    <div class="project-card__top">
      <span class="badge">{{ project.type }}</span>
      <span class="project-card__period">{{ project.period }}</span>
    </div>

    <h3 class="project-card__title">
      <RouterLink :to="`/projects/${project.slug}`">{{ project.name }}</RouterLink>
    </h3>

    <p class="project-card__summary">{{ project.summary }}</p>

    <div class="project-card__tags tags">
      <span v-for="tag in tags" :key="tag" class="tag">{{ tag }}</span>
    </div>

    <RouterLink class="project-card__more" :to="`/projects/${project.slug}`">
      查看详情
      <span aria-hidden="true">→</span>
    </RouterLink>
  </article>
</template>
