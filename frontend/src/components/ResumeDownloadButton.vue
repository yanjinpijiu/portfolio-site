<script setup>
import { nextTick, ref } from 'vue'
import { api } from '../api'
import AppIcon from './AppIcon.vue'

const props = defineProps({
  /** 简历对象，来自 /api/resumes */
  resume: { type: Object, required: true },
  label: { type: String, default: '下载 PDF' },
  variant: { type: String, default: 'primary' }
})

/**
 * 下载流程：
 *
 * 1. 直接请求下载接口。服务端自己判断这个 IP 下载这份简历要不要验证码——
 *    前 3 次（24 小时内）直接给文件，第 4 次起返回业务码 428。
 * 2. 见到 428 就弹验证码框，取一张图给用户算，带上 captchaId 重试同一个接口。
 *
 * 判断逻辑全在服务端，前端改代码绕不过去；正常下载一次请求就完成，不会多弹框。
 */
const busy = ref(false)
const errorText = ref('')
const needCaptcha = ref(false)
const challenge = ref(null)
const answer = ref('')
const captchaInput = ref(null)

async function start() {
  if (busy.value) return
  busy.value = true
  errorText.value = ''
  try {
    const file = await api.downloadResume(props.resume.id)
    save(file)
    needCaptcha.value = false
  } catch (e) {
    if (e.code === 428) {
      // 需要验证码：先取一张图，用户答完再重试
      await fetchChallenge()
      return
    }
    errorText.value = e.message || '下载失败，请稍后重试'
  } finally {
    busy.value = false
  }
}

/** 带上验证码答案再下一次 */
async function submitCaptcha() {
  if (busy.value) return
  if (!answer.value.trim()) {
    errorText.value = '请输入答案'
    return
  }
  busy.value = true
  errorText.value = ''
  try {
    const file = await api.downloadResume(props.resume.id, {
      captchaId: challenge.value?.id,
      captchaAnswer: answer.value.trim()
    })
    save(file)
    needCaptcha.value = false
  } catch (e) {
    if (e.code === 428) {
      // 服务端的验证码是「一次尝试就作废」（先删后验，防止同一个答案被反复试），
      // 所以答错之后必须换一张新图，而不是让用户在旧图上重试
      errorText.value = '答案不对，已换一张，请重新计算'
      answer.value = ''
      await fetchChallenge()
      return
    }
    errorText.value = e.message || '下载失败，请稍后重试'
  } finally {
    busy.value = false
  }
}

async function fetchChallenge() {
  try {
    const data = await api.getCaptcha()
    if (!data) {
      // 服务端画不出验证码图（没装字体）时会返回失败，表示这道闸已经放行，
      // 这时候直接再下一次就能拿到文件
      errorText.value = '验证码服务暂不可用，请直接重试下载'
      return
    }
    challenge.value = data
    needCaptcha.value = true
    answer.value = ''
    await nextTick()
    captchaInput.value?.focus()
  } catch (e) {
    errorText.value = e.message || '验证码加载失败'
  }
}

function closeCaptcha() {
  needCaptcha.value = false
  challenge.value = null
  answer.value = ''
  errorText.value = ''
}

/** 把 blob 存成文件。文件名优先用服务端 Content-Disposition 里的 */
function save({ blob, fileName }) {
  const name = fileName || props.resume.fileName || 'resume.pdf'
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
  // 立刻 revoke 在部分浏览器上会让下载失败，等一会儿再回收
  setTimeout(() => URL.revokeObjectURL(url), 10000)
}
</script>

<template>
  <div class="dl">
    <button
      class="btn"
      :class="`btn--${variant}`"
      type="button"
      :disabled="busy"
      @click="start"
    >
      <AppIcon name="download" :size="15" />
      {{ busy ? '准备中…' : label }}
    </button>

    <p v-if="errorText && !needCaptcha" class="dl__error">{{ errorText }}</p>

    <Teleport to="body">
      <Transition name="lb">
        <div
          v-if="needCaptcha"
          class="captcha-mask"
          role="dialog"
          aria-modal="true"
          aria-label="下载验证"
          @click.self="closeCaptcha"
        >
          <div class="captcha-panel">
            <h3 class="captcha-title">下载前验证一下</h3>
            <p class="captcha-desc">同一份简历连续下载较多，输入图中算式的结果即可继续。</p>

            <button
              v-if="challenge"
              class="captcha-image"
              type="button"
              title="点击换一张"
              @click="fetchChallenge"
            >
              <img :src="challenge.image" alt="验证码，点击可更换">
            </button>

            <div class="captcha-row">
              <input
                ref="captchaInput"
                v-model="answer"
                class="input"
                type="text"
                inputmode="numeric"
                autocomplete="off"
                placeholder="输入计算结果"
                @keyup.enter="submitCaptcha"
              >
              <button class="btn btn--primary" type="button" :disabled="busy" @click="submitCaptcha">
                {{ busy ? '下载中…' : '下载' }}
              </button>
            </div>

            <p v-if="errorText" class="dl__error" style="margin-top: 10px">{{ errorText }}</p>

            <div class="captcha-foot">
              <button class="link-btn" type="button" @click="fetchChallenge">换一张</button>
              <button class="link-btn" type="button" @click="closeCaptcha">取消</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.dl { display: inline-block; }

.dl__error {
  margin: 8px 0 0;
  font-size: 13px;
  color: #B4453A;
}

.captcha-mask {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(23, 33, 43, .5);
  backdrop-filter: blur(2px);
}

.captcha-panel {
  width: 100%;
  max-width: 340px;
  padding: 22px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 12px 40px rgba(23, 33, 43, .18);
}

.captcha-title {
  margin: 0 0 6px;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-900);
}

.captcha-desc {
  margin: 0 0 16px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ink-500);
}

.captcha-image {
  display: block;
  width: 100%;
  padding: 8px;
  margin-bottom: 14px;
  background: var(--bg-soft);
  border: 1px solid var(--line);
  border-radius: 8px;
  cursor: pointer;
}

.captcha-image img {
  display: block;
  width: 132px;
  height: 44px;
  margin: 0 auto;
  image-rendering: auto;
}

.captcha-row {
  display: flex;
  gap: 8px;
}

.captcha-row .input { flex: 1; }

.captcha-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 14px;
}

.link-btn {
  padding: 0;
  font-size: 13px;
  color: var(--blue-600);
  background: none;
  border: 0;
  cursor: pointer;
}

.link-btn:hover { text-decoration: underline; }
</style>
