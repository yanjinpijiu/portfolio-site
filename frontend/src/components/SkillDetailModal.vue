<script setup>
import { computed, onUnmounted, watch } from 'vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  group: { type: Object, default: null },
  /** 全部项目，用来把技能关联到具体项目 */
  projects: { type: Array, default: () => [] },
  /** 内层灯箱打开时置为 false，避免 Esc 一次关掉两层 */
  escEnabled: { type: Boolean, default: true }
})

const emit = defineEmits(['close', 'open-cert'])

/** 该技能分组关联到的项目 */
const relatedProjects = computed(() => {
  if (!props.group?.related) return []
  const bySlug = new Map(props.projects.map((p) => [p.slug, p]))
  return props.group.related.map((slug) => bySlug.get(slug)).filter(Boolean)
})

const hasCerts = computed(() => (props.group?.certificates || []).length > 0)

function onKeydown(e) {
  if (e.key === 'Escape' && props.escEnabled) emit('close')
}

watch(
  () => props.open,
  (isOpen) => {
    document.body.style.overflow = isOpen ? 'hidden' : ''
    if (isOpen) {
      window.addEventListener('keydown', onKeydown)
    } else {
      window.removeEventListener('keydown', onKeydown)
    }
  }
)

onUnmounted(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Teleport to="body">
    <Transition name="lb">
      <div
        v-if="open && group"
        class="detail-modal"
        role="dialog"
        aria-modal="true"
        :aria-label="group.category"
        @click.self="emit('close')"
      >
        <div class="detail-modal__panel">
          <header class="detail-modal__head">
            <div style="min-width: 0">
              <h2>{{ group.category }}</h2>
              <ul class="detail-modal__items">
                <li v-for="item in group.items" :key="item">{{ item }}</li>
              </ul>
            </div>
            <button class="detail-modal__close" type="button" aria-label="关闭（Esc）" @click="emit('close')">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                   stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
                <path d="M6 6l12 12M18 6L6 18" />
              </svg>
            </button>
          </header>

          <div class="detail-modal__body">
            <!-- 关联项目 -->
            <template v-if="relatedProjects.length">
              <h3 class="detail-modal__sub">用在这些项目里</h3>
              <ul class="related-list">
                <li v-for="p in relatedProjects" :key="p.slug">
                  <RouterLink class="related-item" :to="`/projects/${p.slug}`" @click="emit('close')">
                    <span class="related-item__name">{{ p.name }}</span>
                    <span class="related-item__type">{{ p.type }}</span>
                    <span class="related-item__arrow" aria-hidden="true">→</span>
                  </RouterLink>
                </li>
              </ul>
            </template>

            <!-- 证书 -->
            <template v-if="hasCerts">
              <h3 class="detail-modal__sub">证书</h3>
              <p class="detail-modal__hint">点击可放大查看</p>
              <div class="cert-grid">
                <button
                  v-for="cert in group.certificates"
                  :key="cert.id"
                  class="cert-card"
                  type="button"
                  @click="emit('open-cert', cert)"
                >
                  <div class="cert-thumb" :style="{ aspectRatio: cert.ratio }">
                    <img :src="cert.imageUrl" :alt="cert.title" decoding="async">
                  </div>
                  <div class="cert-info">
                    <div class="cert-title">{{ cert.title }}</div>
                    <div class="cert-org">{{ cert.org }}</div>
                    <div class="cert-date">{{ cert.date }} · {{ cert.summary }}</div>
                  </div>
                </button>
              </div>
            </template>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
