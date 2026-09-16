<script setup>
import { computed, onMounted, ref } from 'vue'
import { fetchProjects, fetchResumes, introLines, section, siteState } from '../store/site'
import AppIcon from '../components/AppIcon.vue'
import ProjectCard from '../components/ProjectCard.vue'
import ImageLightbox from '../components/ImageLightbox.vue'
import ResumeDownloadButton from '../components/ResumeDownloadButton.vue'
import SkillDetailModal from '../components/SkillDetailModal.vue'

const site = siteState()

const projects = ref([])
const defaultResume = ref(null)
const loading = ref(true)
const error = ref('')

/** 打开详情弹窗的技能分组，null 表示关闭 */
const activeSkill = ref(null)
/** 弹窗里点开放大的证书，null 表示关闭 */
const activeCert = ref(null)

function closeSkillDetail() {
  activeSkill.value = null
  activeCert.value = null
}

const featured = computed(() => projects.value.filter((p) => p.type !== '其他实践'))
const practices = computed(() => projects.value.filter((p) => p.type === '其他实践'))

/** 页面大标题 / 副标题都从后台来 */
const skillsSection = computed(() => section('home.skills'))
const projectsSection = computed(() => section('home.projects'))
const practiceSection = computed(() => section('home.practice'))
const contactSection = computed(() => section('home.contact'))

/** 顶部的 GitHub 按钮：从联系方式里取，没配就不显示 */
const github = computed(() => site.contacts.find((c) => c.icon === 'github') || null)

onMounted(async () => {
  try {
    // 项目与简历都走 store：静态模式读快照，动态模式调接口，页面不用管
    const [list, resume] = await Promise.all([
      fetchProjects(),
      fetchResumes().then((list) => (list && list.length ? list[0] : null)).catch(() => null)
    ])
    projects.value = list || []
    defaultResume.value = resume
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <!-- Hero -->
  <section class="hero">
    <div class="container">
      <div class="hero-top">
        <div class="hero-identity">
          <h1 class="hero-name">{{ site.profile.name }}</h1>
          <p class="hero-role">{{ site.profile.title }}</p>

          <div class="hero-meta">
            <span v-if="site.profile.school">{{ site.profile.school }}</span>
            <span v-if="site.profile.graduation">{{ site.profile.graduation }}</span>
            <span v-if="site.profile.city">现居 {{ site.profile.city }}</span>
          </div>
        </div>

        <div v-if="site.profile.avatarUrl" class="hero-photo">
          <img
            :src="site.profile.avatarUrl"
            :alt="`${site.profile.name} 的照片`"
            width="88"
            height="110"
            fetchpriority="high"
            decoding="async"
          >
        </div>
      </div>

      <div class="hero-intro">
        <p v-for="(line, i) in introLines()" :key="i">{{ line }}</p>
      </div>

      <div class="hero-actions">
        <ResumeDownloadButton
          v-if="defaultResume"
          :resume="defaultResume"
          label="下载简历 PDF"
        />
        <RouterLink v-else class="btn btn--primary" to="/resume">
          <AppIcon name="file" :size="15" />
          查看简历
        </RouterLink>

        <RouterLink class="btn btn--ghost" to="/projects">浏览项目</RouterLink>
        <a
          v-if="github"
          class="btn btn--ghost"
          :href="github.href"
          target="_blank"
          rel="noopener noreferrer"
        >
          <AppIcon name="github" :size="15" />
          GitHub
        </a>
      </div>

      <p v-if="site.profile.availability" class="hero-availability">{{ site.profile.availability }}</p>
    </div>
  </section>

  <!-- 专业技能 -->
  <section v-if="site.skillGroups.length" class="section">
    <div class="container">
      <div class="section-head">
        <p v-if="skillsSection.eyebrow" class="section-eyebrow">{{ skillsSection.eyebrow }}</p>
        <h2 class="section-title">{{ skillsSection.title }}</h2>
        <p v-if="skillsSection.description" class="section-desc">{{ skillsSection.description }}</p>
      </div>

      <div class="skills-grid">
        <button
          v-for="group in site.skillGroups"
          :key="group.id"
          class="skill-card skill-card--clickable"
          type="button"
          @click="activeSkill = group"
        >
          <h3>{{ group.category }}</h3>
          <ul>
            <li v-for="item in group.items" :key="item">{{ item }}</li>
          </ul>
          <span class="skill-card__hint">
            {{ group.certificates?.length ? '查看证书' : '查看相关项目' }}
            <span aria-hidden="true">→</span>
          </span>
        </button>
      </div>
    </div>
  </section>

  <!-- 项目经历 -->
  <section class="section section--soft">
    <div class="container">
      <div class="section-head">
        <p v-if="projectsSection.eyebrow" class="section-eyebrow">{{ projectsSection.eyebrow }}</p>
        <h2 class="section-title">{{ projectsSection.title }}</h2>
        <p v-if="projectsSection.description" class="section-desc">{{ projectsSection.description }}</p>
      </div>

      <div v-if="loading" class="project-grid">
        <div v-for="i in 3" :key="i" class="skeleton" style="height: 230px"></div>
      </div>

      <div v-else-if="error" class="empty">{{ error }}</div>

      <template v-else>
        <div class="project-grid">
          <ProjectCard v-for="project in featured" :key="project.id" :project="project" />
        </div>

        <!-- 其他实践 -->
        <template v-if="practices.length">
          <div class="section-head" style="margin-top: 40px; margin-bottom: 16px">
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

  <!-- 联系方式 -->
  <section v-if="site.contacts.length" class="section section--soft">
    <div class="container">
      <div class="section-head">
        <p v-if="contactSection.eyebrow" class="section-eyebrow">{{ contactSection.eyebrow }}</p>
        <h2 class="section-title">{{ contactSection.title }}</h2>
        <p v-if="contactSection.description" class="section-desc">{{ contactSection.description }}</p>
      </div>

      <div class="contact-grid">
        <a
          v-for="item in site.contacts"
          :key="item.id"
          class="contact-item"
          :href="item.href || '#'"
          :target="String(item.href || '').startsWith('http') ? '_blank' : undefined"
          :rel="String(item.href || '').startsWith('http') ? 'noopener noreferrer' : undefined"
        >
          <span class="contact-item__label">{{ item.label }}</span>
          <span class="contact-item__value">{{ item.valueText }}</span>
        </a>
      </div>
    </div>
  </section>

  <SkillDetailModal
    :open="!!activeSkill"
    :group="activeSkill"
    :projects="projects"
    :esc-enabled="!activeCert"
    @close="closeSkillDetail"
    @open-cert="activeCert = $event"
  />

  <ImageLightbox
    :open="!!activeCert"
    :src="activeCert?.imageUrl || ''"
    :alt="activeCert?.title || ''"
    :caption="activeCert ? `${activeCert.title} · ${activeCert.org} · ${activeCert.date}` : ''"
    @close="activeCert = null"
  />
</template>
