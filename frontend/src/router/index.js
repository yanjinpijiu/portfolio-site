import { createRouter, createWebHistory } from 'vue-router'
import { tokenStore } from '../api'
import { loadSite, section, setting, siteState } from '../store/site'
import { installVisibilityTracking, trackView } from '../track'

import HomeView from '../views/HomeView.vue'
import ProjectsView from '../views/ProjectsView.vue'
import ProjectDetailView from '../views/ProjectDetailView.vue'
import ResumeView from '../views/ResumeView.vue'
import NotFoundView from '../views/NotFoundView.vue'
// 后台的页面全部改成动态引入：这样公开页面那个包不含任何后台代码，
// 访客打开首页时不会为「我自己偶尔用一次的管理界面」买单。
// 公开页面保持静态引入，免得点导航还要等一次额外的请求
const AdminLoginView = () => import('../views/admin/AdminLoginView.vue')
const AdminLayout = () => import('../views/admin/AdminLayout.vue')
const AdminStatsView = () => import('../views/admin/AdminStatsView.vue')
const AdminResumeView = () => import('../views/admin/AdminResumeView.vue')
const AdminProjectsView = () => import('../views/admin/AdminProjectsView.vue')
const AdminSkillsView = () => import('../views/admin/AdminSkillsView.vue')
const AdminCertsView = () => import('../views/admin/AdminCertsView.vue')
const AdminProfileView = () => import('../views/admin/AdminProfileView.vue')
const AdminPagesView = () => import('../views/admin/AdminPagesView.vue')
const AdminBackupView = () => import('../views/admin/AdminBackupView.vue')

const routes = [
  // 公开页不写死 title：标题里的「项目」「简历」这些字来自后台维护的页面文案，
  // 见下面的 titleFor()。meta.title 只留给后台和 404 这种跟内容无关的页面
  { path: '/', name: 'home', component: HomeView },
  { path: '/projects', name: 'projects', component: ProjectsView },
  {
    path: '/projects/:slug',
    name: 'project-detail',
    component: ProjectDetailView,
    props: true
  },
  { path: '/resume', name: 'resume', component: ResumeView },
  // /admin 是登录页，故意排在布局路由前面：路径完全相同时先定义的先匹配，
  // 而布局那条要靠子路径（/admin/xxx）才会命中
  { path: '/admin', name: 'admin-login', component: AdminLoginView, meta: { title: '后台登录' } },
  {
    path: '/admin',
    component: AdminLayout,
    // requiresAuth 写在父级上，子路由通过 to.meta 合并继承，不用每条都写
    meta: { requiresAuth: true },
    children: [
      { path: 'dashboard', name: 'admin-dashboard', component: AdminStatsView, meta: { title: '数据看板' } },
      { path: 'resumes', name: 'admin-resumes', component: AdminResumeView, meta: { title: '简历管理' } },
      { path: 'projects', name: 'admin-projects', component: AdminProjectsView, meta: { title: '项目与图片' } },
      { path: 'skills', name: 'admin-skills', component: AdminSkillsView, meta: { title: '技能分组' } },
      { path: 'certs', name: 'admin-certs', component: AdminCertsView, meta: { title: '证书' } },
      { path: 'profile', name: 'admin-profile', component: AdminProfileView, meta: { title: '个人资料' } },
      { path: 'pages', name: 'admin-pages', component: AdminPagesView, meta: { title: '页面文案' } },
      { path: 'backup', name: 'admin-backup', component: AdminBackupView, meta: { title: '导出备份' } }
    ]
  },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundView, meta: { title: '页面不存在' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash, behavior: 'smooth' }
    return { top: 0 }
  }
})

router.beforeEach(async (to) => {
  if (to.meta.requiresAuth && !tokenStore.get()) {
    return { name: 'admin-login', query: { redirect: to.fullPath } }
  }
  // 等内容到手再进页面。不等的话会先渲染出一屏空标题、再跳成真实内容，
  // 那一下很难看。代价是首屏多一次 /api/site 请求（本地几毫秒、线上几十毫秒），
  // 而且只有第一次会真的发请求，之后走内存缓存
  await loadSite()
  return true
})

/**
 * 页面标题。
 *
 * 优先用后台维护的文案（页面大标题 + SEO 设置）拼，拼不出来才退回路由里的静态标题。
 * 这样在后台改标题能直接反映到浏览器标签页，不用改代码重新部署。
 * 注意：这是给「已经渲染完的页面」用的；搜索引擎抓到的是 index.html 里的静态
 * description/og，那几个字段改了之后要手动同步（后台设置页里有标注）。
 */
function titleFor(to) {
  const name = siteState().profile.name || '潜力'
  if (to.name === 'home') {
    return setting('seo.title', name)
  }
  if (to.name === 'projects') {
    return `${section('projects.header').title || '项目'} · ${name}`
  }
  if (to.name === 'resume') {
    return `${section('resume.header').title || '简历'} · ${name}`
  }
  // 项目详情页的标题要等项目名加载出来，由页面自己设置
  if (to.name === 'project-detail') {
    return to.meta.title || name
  }
  return to.meta.title ? `${to.meta.title} · ${name}` : setting('seo.title', name)
}

router.afterEach((to) => {
  document.title = titleFor(to)
  trackView(to.path, to.name)
})

installVisibilityTracking(() => {
  const current = router.currentRoute.value
  return current ? { path: current.path, name: current.name } : null
})

export default router
