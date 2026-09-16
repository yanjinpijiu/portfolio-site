<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { toLines } from '../api'
import { fetchProject, siteState } from '../store/site'
import AppIcon from '../components/AppIcon.vue'
import ImageLightbox from '../components/ImageLightbox.vue'

const props = defineProps({
  slug: { type: String, required: true }
})

const site = siteState()

const project = ref(null)
const loading = ref(true)
const error = ref('')

/** 当前放大的成果图，null 表示关闭 */
const activeShot = ref(null)

/** 成果图跟着项目详情一起返回，不再有单独的图库文件 */
const gallery = computed(() => project.value?.images || [])

/** 展示位里既能放图也能放短片，按扩展名区分（上传时后端也是这么判的） */
const isVideo = (url) => /\.(mp4|webm)$/i.test(url || '')

const tags = computed(() => toLines(project.value?.tags))
const highlights = computed(() => toLines(project.value?.highlights))
const paragraphs = computed(() =>
  toLines(project.value?.description).length
    ? project.value.description.split('\n').map((s) => s.trim()).filter(Boolean)
    : []
)

async function load(slug) {
  loading.value = true
  error.value = ''
  project.value = null
  try {
    project.value = await fetchProject(slug)
    document.title = `${project.value.name} · ${site.profile.name || '潜力'}`
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => load(props.slug))
watch(() => props.slug, (next) => load(next))
</script>

<template>
  <section class="section">
    <div class="container">
      <RouterLink class="detail-back" to="/projects">
        <AppIcon name="arrowLeft" :size="15" />
        返回项目列表
      </RouterLink>

      <div v-if="loading">
        <div class="skeleton" style="height: 150px; margin-bottom: 20px"></div>
        <div class="skeleton" style="height: 260px"></div>
      </div>

      <div v-else-if="error" class="empty">
        <p>{{ error }}</p>
        <RouterLink class="btn btn--ghost" to="/projects" style="margin-top: 18px">看看其他项目</RouterLink>
      </div>

      <template v-else-if="project">
        <header class="detail-head">
          <div class="project-card__top" style="margin-bottom: 0">
            <span class="badge">{{ project.type }}</span>
          </div>

          <h1 class="detail-title">{{ project.name }}</h1>

          <div class="detail-meta">
            <span v-if="project.period">{{ project.period }}</span>
            <span v-if="project.role"><strong>角色：</strong>{{ project.role }}</span>
          </div>

          <div class="tags" style="margin-bottom: 20px">
            <span v-for="tag in tags" :key="tag" class="tag">{{ tag }}</span>
          </div>

          <div class="detail-desc">
            <p v-for="(para, i) in paragraphs" :key="i">{{ para }}</p>
          </div>
        </header>

        <section v-if="highlights.length" class="detail-block">
          <h2 class="detail-block-title">个人产出</h2>
          <ul class="highlight-list">
            <li v-for="(item, i) in highlights" :key="i">{{ item }}</li>
          </ul>
        </section>

        <section v-if="gallery.length" class="detail-block">
          <h2 class="detail-block-title">成果展示</h2>
          <p class="gallery-hint">点击图片或短片可放大查看</p>
          <div class="gallery">
            <button
              v-for="(shot, i) in gallery"
              :key="i"
              class="gallery-item"
              :class="{ 'gallery-item--video': isVideo(shot.url) }"
              type="button"
              @click="activeShot = shot"
            >
              <!-- 短片在列表里自动循环静音播放：不占用点击之外的操作，
                   点开灯箱才给控制条（能暂停、能拖进度） -->
              <video
                v-if="isVideo(shot.url)"
                :src="shot.url"
                autoplay
                loop
                muted
                playsinline
                preload="metadata"
              ></video>
              <img v-else :src="shot.url" :alt="shot.caption" loading="lazy" decoding="async">
              <span v-if="isVideo(shot.url)" class="gallery-item__play" aria-hidden="true">▶ 短片</span>
              <span class="gallery-caption">{{ shot.caption }}</span>
            </button>
          </div>
        </section>

        <section v-if="project.repoUrl || project.repoLabel" class="detail-block">
          <h2 class="detail-block-title">代码仓库</h2>
          <div class="detail-repo">
            <template v-if="project.repoUrl">
              <AppIcon name="link" :size="16" />
              <a :href="project.repoUrl" target="_blank" rel="noopener noreferrer">{{ project.repoUrl }}</a>
              <span class="badge badge--muted">{{ project.repoLabel || '开源仓库' }}</span>
            </template>
            <template v-else>
              <AppIcon name="lock" :size="16" />
              <span>{{ project.repoLabel || '代码未开源' }}</span>
            </template>
          </div>
        </section>

        <section class="detail-block">
          <RouterLink class="btn btn--ghost" to="/projects">继续浏览其他项目</RouterLink>
        </section>
      </template>
    </div>
  </section>

  <ImageLightbox
    :open="!!activeShot"
    :src="activeShot?.url || ''"
    :alt="activeShot?.caption || ''"
    :caption="activeShot?.caption || ''"
    @close="activeShot = null"
  />
</template>
