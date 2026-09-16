<script setup>
import AppIcon from '../components/AppIcon.vue'

defineProps({
  title: { type: String, default: '页面不存在' },
  desc: { type: String, default: '' }
})
</script>

<template>
  <div class="container" style="padding: 90px 20px; text-align: center">
    <h1 style="font-size: 22px">{{ title }}</h1>
    <p style="margin-top: 10px; color: var(--ink-500)">{{ desc }}</p>
    <RouterLink class="btn btn--primary" to="/" style="margin-top: 24px">
      <AppIcon name="arrowLeft" :size="15" />
      返回首页
    </RouterLink>
  </div>
</template>
