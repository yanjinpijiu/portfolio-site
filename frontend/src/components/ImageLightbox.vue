<script setup>
import { computed, onUnmounted, watch } from 'vue'

/**
 * 点击放大的预览层。既放图片也放短片：项目展示页里有演示视频，
 * 按扩展名区分渲染 <img> 还是 <video>，和画廊里用的是同一套判断。
 */
const props = defineProps({
  open: { type: Boolean, default: false },
  src: { type: String, default: '' },
  alt: { type: String, default: '' },
  caption: { type: String, default: '' }
})

const emit = defineEmits(['close'])

const isVideo = computed(() => /\.(mp4|webm)$/i.test(props.src || ''))

function onKeydown(e) {
  if (e.key === 'Escape') emit('close')
}

// 打开时锁住页面滚动，并监听 Esc
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
        v-if="open"
        class="lightbox"
        role="dialog"
        aria-modal="true"
        :aria-label="caption || '预览'"
        @click.self="emit('close')"
      >
        <button class="lightbox__close" type="button" aria-label="关闭（Esc）" @click="emit('close')">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>

        <figure class="lightbox__body">
          <!-- 放大后才给控制条：缩略图上是自动循环静音的，点开才需要能暂停、拖进度 -->
          <video v-if="isVideo" :src="src" controls autoplay loop muted playsinline></video>
          <img v-else :src="src" :alt="alt">
          <figcaption v-if="caption">{{ caption }}</figcaption>
        </figure>
      </div>
    </Transition>
  </Teleport>
</template>
