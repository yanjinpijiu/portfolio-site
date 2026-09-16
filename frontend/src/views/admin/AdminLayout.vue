<script setup>
import { onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { api, setUnauthorizedHandler, tokenStore } from '../../api'

const router = useRouter()

/**
 * 令牌一旦失效就把人送回登录页。
 *
 * 放在布局里注册一次，所有后台页面都不用各写一遍 401 处理；
 * 页面被销毁时撤销，避免离开后台之后还挂着一个钩子。
 */
function toLogin() {
  router.replace({ name: 'admin-login' })
}

onMounted(() => setUnauthorizedHandler(toLogin))
onUnmounted(() => setUnauthorizedHandler(null))

async function logout() {
  try {
    await api.logout()
  } catch {
    /* 令牌可能已经过期，本来就要登出，忽略错误 */
  }
  tokenStore.clear()
  toLogin()
}
</script>

<template>
  <div class="admin-shell">
    <aside class="admin-side">
      <p class="admin-side__group">内容</p>
      <RouterLink :to="{ name: 'admin-profile' }">个人资料</RouterLink>
      <RouterLink :to="{ name: 'admin-projects' }">项目与图片</RouterLink>
      <RouterLink :to="{ name: 'admin-skills' }">技能分组</RouterLink>
      <RouterLink :to="{ name: 'admin-certs' }">证书</RouterLink>
      <RouterLink :to="{ name: 'admin-pages' }">页面文案</RouterLink>

      <p class="admin-side__group">运行</p>
      <RouterLink :to="{ name: 'admin-dashboard' }">数据看板</RouterLink>
      <RouterLink :to="{ name: 'admin-resumes' }">简历管理</RouterLink>
      <RouterLink :to="{ name: 'admin-backup' }">导出备份</RouterLink>

      <p class="admin-side__group">其它</p>
      <RouterLink to="/">返回站点</RouterLink>
      <a href="#" @click.prevent="logout">退出登录</a>
    </aside>

    <div class="admin-main">
      <RouterView />
    </div>
  </div>
</template>
