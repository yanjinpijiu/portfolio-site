<script setup>
import { computed } from 'vue'
import { contactByIcon, siteState } from '../store/site'

const site = siteState()

const email = computed(() => site.profile.email || '')
const github = computed(() => contactByIcon('github'))
const gitee = computed(() => contactByIcon('gitee'))

/** 版权文案在后台维护，没配就用姓名兜底 */
const copyright = computed(() => {
  const text = site.settings['footer.copyright'] || ''
  return text || `© ${new Date().getFullYear()} ${site.profile.name || ''}`
})

/** 网站性质与日志用途说明（后台可改） */
const notice = computed(() => (site.settings['footer.notice'] || '').trim())

/** ICP 备案号：非经营性备案要求在首页底部标明编号并链接到工信部备案系统 */
const icp = computed(() => (site.settings['footer.icp'] || '').trim())
</script>

<template>
  <footer class="site-footer">
    <div class="container">
      <div class="footer-inner">
        <span>{{ copyright }}</span>
        <div class="footer-links">
          <a v-if="email" :href="`mailto:${email}`">{{ email }}</a>
          <a v-if="github" :href="github.href" target="_blank" rel="noopener noreferrer">GitHub</a>
          <a v-if="gitee" :href="gitee.href" target="_blank" rel="noopener noreferrer">Gitee</a>
          <!-- 后台入口：刻意做得不显眼，只有知道它存在的人会去找 -->
          <RouterLink class="footer-settings" to="/admin" :title="site.settings['nav.settings'] || '设置'">
            {{ site.settings['nav.settings'] || '设置' }}
          </RouterLink>
        </div>
      </div>

      <p v-if="notice || icp" class="footer-note">
        <span v-if="notice">{{ notice }}</span>
        <a
          v-if="icp"
          class="footer-icp"
          href="https://beian.miit.gov.cn/"
          target="_blank"
          rel="noopener noreferrer"
        >{{ icp }}</a>
      </p>
    </div>
  </footer>
</template>
