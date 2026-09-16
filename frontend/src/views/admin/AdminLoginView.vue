<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, tokenStore } from '../../api'
import AppIcon from '../../components/AppIcon.vue'

const router = useRouter()
const route = useRoute()

const key = ref('')
const loading = ref(false)
const error = ref('')

async function submit() {
  if (!key.value.trim()) {
    error.value = '请输入密钥'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const data = await api.login(key.value.trim())
    tokenStore.set(data.token)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/admin/dashboard'
    router.replace(redirect)
  } catch (e) {
    error.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="admin-page">
    <div class="container">
      <div class="login-card">
        <h1>后台登录</h1>
        <p class="hint">仅用于本人管理简历文件，非公开入口。</p>

        <div v-if="error" class="alert alert--error">{{ error }}</div>

        <form @submit.prevent="submit">
          <div class="field">
            <label for="admin-key">访问密钥</label>
            <input
              id="admin-key"
              v-model="key"
              class="input"
              type="password"
              autocomplete="current-password"
              placeholder="请输入密钥"
            />
          </div>

          <button class="btn btn--primary" type="submit" :disabled="loading" style="width: 100%">
            <AppIcon name="lock" :size="15" />
            {{ loading ? '登录中…' : '登录' }}
          </button>
        </form>
      </div>
    </div>
  </div>
</template>
