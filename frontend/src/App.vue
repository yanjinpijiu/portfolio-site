<script setup>
import SiteHeader from './components/SiteHeader.vue'
import SiteFooter from './components/SiteFooter.vue'
import { loadSite, siteState } from './store/site'

const site = siteState()
</script>

<template>
  <SiteHeader />
  <main>
    <!-- 整站内容都来自这个接口，它挂了要给句提示并给个重试，静默失败会让人以为站点坏了 -->
    <div v-if="site.error" class="site-notice">
      <span>内容加载失败（{{ site.error }}）</span>
      <button class="link-btn" type="button" @click="loadSite()">重试</button>
    </div>
    <RouterView />
  </main>
  <SiteFooter />
</template>

<style scoped>
.site-notice {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 10px 16px;
  font-size: 13px;
  color: #8A5A2B;
  background: #FDF6EC;
  border-bottom: 1px solid #F3E3CB;
}

.link-btn {
  padding: 0;
  font-size: 13px;
  color: var(--blue-600);
  background: none;
  border: 0;
  cursor: pointer;
  text-decoration: underline;
}
</style>
