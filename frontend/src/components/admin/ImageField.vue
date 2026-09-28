<script setup>
import { computed, ref } from 'vue'
import { api } from '../../api'

/**
 * 图片字段：预览 + 上传 + 清除。
 *
 * 库里存的是相对 key（如 2026/09/xxx.jpg），这里把 key 拼成 /files/xxx 预览；
 * 上传接口返回的也是 key，所以上传完直接写回 v-model，不存绝对地址。
 * 换域名只要改 nginx 和 SiteContentService.FILE_URL_PREFIX，数据不用动。
 */
const props = defineProps({
  modelValue: { type: String, default: '' },
  /** avatar = 缩到 400px；video = 短片（不压缩）；其它图缩到 1600px */
  kind: { type: String, default: 'image' },
  label: { type: String, default: '图片' },
  hint: { type: String, default: '' }
})

const emit = defineEmits(['update:modelValue'])

const busy = ref(false)
const error = ref('')

const isVideo = computed(() => /\.(mp4|webm)$/i.test(props.modelValue || ''))
const previewUrl = computed(() => (props.modelValue ? `/files/${props.modelValue}` : ''))

/** 默认提示按类型给：短片要提醒先在本地压好（服务端不转码） */
const defaultHint = computed(() => props.kind === 'video'
  ? '只收 mp4 / webm，不超过 16MB。先把片子在本地压好再传：8 秒的屏幕录制压到 720 宽通常只有 200KB 左右，同样内容做成 GIF 要 2.6MB 还糊'
  : '支持 jpg / png / webp，不超过 8MB')

const accept = computed(() => props.kind === 'video'
  ? 'video/mp4,video/webm'
  : 'image/jpeg,image/png,image/webp')

async function pick(event) {
  const file = event.target.files?.[0]
  // 选完就把 input 清空，否则连续选同一个文件不会再触发 change
  event.target.value = ''
  if (!file) return

  busy.value = true
  error.value = ''
  try {
    const form = new FormData()
    form.append('file', file)
    form.append('kind', props.kind)
    const data = await api.adminUploadImage(form)
    emit('update:modelValue', data.objectKey)
  } catch (e) {
    error.value = e.message || '上传失败'
  } finally {
    busy.value = false
  }
}

function clear() {
  emit('update:modelValue', '')
}
</script>

<template>
  <div class="img-field">
    <label>{{ label }}</label>
    <div class="img-field__body">
      <div class="img-field__preview">
        <video v-if="previewUrl && isVideo" :src="previewUrl" muted loop autoplay playsinline></video>
        <img v-else-if="previewUrl" :src="previewUrl" :alt="label">
        <span v-else class="muted">未设置</span>
      </div>
      <div class="img-field__ops">
        <label class="btn btn--ghost btn--sm">
          {{ busy ? '上传中…' : (kind === 'video' ? '选择短片' : '选择图片') }}
          <input type="file" :accept="accept" hidden @change="pick">
        </label>
        <button v-if="previewUrl" class="btn btn--ghost btn--sm" type="button" @click="clear">
          清除
        </button>
        <p class="muted">{{ hint || defaultHint }}</p>
        <p v-if="error" class="img-field__error">{{ error }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.img-field { margin-bottom: 16px; }
.img-field > label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-600);
  margin-bottom: 6px;
}
.img-field__body { display: flex; gap: 12px; align-items: flex-start; }
.img-field__preview {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 132px;
  height: 96px;
  overflow: hidden;
  background: var(--bg-soft);
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius);
}
.img-field__preview img,
.img-field__preview video { max-width: 100%; max-height: 100%; object-fit: contain; }
.img-field__ops { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.img-field__ops .muted { flex-basis: 100%; }
.img-field__error { flex-basis: 100%; font-size: 12.5px; color: #B4453A; }
</style>
