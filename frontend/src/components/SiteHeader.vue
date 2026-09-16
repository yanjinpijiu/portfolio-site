<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { contactByIcon, siteState } from '../store/site'
import AppIcon from './AppIcon.vue'

const route = useRoute()
const site = siteState()
const open = ref(false)

/** GitHub 地址从后台的联系方式里来，不再写死 */
const github = computed(() => contactByIcon('github'))

watch(() => route.fullPath, () => {
  open.value = false
})
</script>

<template>
  <header class="site-header">
    <div class="container header-inner">
      <RouterLink to="/" class="brand">
        <span class="brand-name">{{ site.profile.name }}</span>
        <span class="brand-sub">{{ site.profile.nameEn }}</span>
      </RouterLink>

      <nav class="nav-desktop">
        <RouterLink to="/">首页</RouterLink>
        <RouterLink to="/projects">项目</RouterLink>
        <RouterLink to="/resume">简历</RouterLink>
        <a v-if="github" :href="github.href" target="_blank" rel="noopener noreferrer">GitHub</a>
      </nav>

      <button
        class="nav-toggle"
        :class="{ 'is-open': open }"
        type="button"
        :aria-expanded="open"
        aria-label="打开导航菜单"
        @click="open = !open"
      >
        <span></span>
        <span></span>
        <span></span>
      </button>
    </div>

    <nav v-if="open" class="nav-mobile">
      <RouterLink to="/">首页</RouterLink>
      <RouterLink to="/projects">项目</RouterLink>
      <RouterLink to="/resume">简历</RouterLink>
      <a v-if="github" :href="github.href" target="_blank" rel="noopener noreferrer">
        GitHub<AppIcon name="external" :size="13" style="margin-left: 5px; vertical-align: -1px" />
      </a>
    </nav>
  </header>
</template>
