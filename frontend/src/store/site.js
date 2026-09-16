import { reactive } from 'vue'
import { api, toLines } from '../api'

/**
 * 站点内容。
 *
 * 有两个来源，由服务端在 index.html 里注入的 `window.__SITE_MODE__` 决定：
 *
 * - **dynamic（默认）**：每次访问调 `/api/site` 实时查库，后台改完刷新就生效；
 * - **static**：取站点目录下预生成的 `/snapshot.json` 静态文件，不查库、少一次往返，
 *   但内容是被冻结的——后台改完要在「数据看板」里点一下「重新生成快照」。
 *
 * 两套产物的前端代码是同一份（不是构建两次），静态模式只是多一个快照文件和另一个入口页。
 * 项目列表、详情、简历在静态模式下也从同一份快照里取，所以详情页同样不需要请求接口。
 *
 * 其余约定：
 * - **只拉一次**。内容是全局共用、一天也变不了几次的东西，每次路由切换都重拉纯属浪费。
 * - **失败要能重试**。加载失败不标记 ready，下次路由切换自动再试。
 * - **页面拿到内容之后才渲染**（路由守卫里 await），避免先闪一屏空标题。
 */
const STATIC_MODE = typeof window !== 'undefined' && window.__SITE_MODE__ === 'static'

/** 静态模式下打的是静态文件，动态模式下打接口 */
const SOURCE = STATIC_MODE ? '/snapshot.json' : '/api/site'

const state = reactive({
  /** 内容是否已到手。失败时保持 false，好让下次导航重试 */
  ready: false,
  loading: false,
  error: '',
  mode: STATIC_MODE ? 'static' : 'dynamic',
  /** 静态模式：快照生成时间，用来在页面上提示内容可能不是最新 */
  snapshotAt: '',
  profile: {},
  contacts: [],
  skillGroups: [],
  sections: {},
  settings: {},
  /** 静态模式：快照里的项目与简历（动态模式下保持空，走接口） */
  projects: [],
  resumes: []
})

let inflight = null

export function siteState() {
  return state
}

export function siteMode() {
  return state.mode
}

/** 拿到内容：静态模式是纯静态文件，动态模式是查库接口 */
async function fetchContent() {
  const res = await fetch(SOURCE, {
    // 快照文件会变（重新生成），所以每次都跟服务端校验一下；命中 304 很便宜。
    // 动态模式本来就是接口，用它自己的缓存规则
    cache: STATIC_MODE ? 'no-cache' : 'default'
  })
  if (!res.ok) {
    throw new Error(`内容加载失败（${res.status}）`)
  }
  const data = await res.json()
  // 动态模式外面套了一层 ApiResponse{code,message,data}，静态文件就是内容本身
  return STATIC_MODE ? data : data.data
}

export function loadSite() {
  if (state.ready) {
    return Promise.resolve(state)
  }
  if (inflight) {
    return inflight
  }
  state.loading = true
  inflight = fetchContent()
    .then((payload) => {
      if (payload) {
        // 动态模式：{ profile, contacts, skillGroups, sections, settings }
        // 静态模式：{ generatedAt, site: {...}, projects: [...], resumes: [...] }
        const site = STATIC_MODE ? payload.site || {} : payload
        state.profile = site.profile || {}
        state.contacts = (site.contacts || []).filter((c) => c.visible !== false)
        state.skillGroups = site.skillGroups || []
        state.sections = site.sections || {}
        state.settings = site.settings || {}
        if (STATIC_MODE) {
          state.snapshotAt = payload.generatedAt || ''
          state.projects = payload.projects || []
          state.resumes = payload.resumes || []
        }
      }
      state.error = ''
      state.ready = true
    })
    .catch((e) => {
      state.error = e?.message || '内容加载失败'
    })
    .finally(() => {
      state.loading = false
      inflight = null
    })
  return inflight
}

/* ---------------- 项目与简历：静态模式走快照，动态模式走接口 ---------------- */

export async function fetchProjects() {
  if (STATIC_MODE) {
    await loadSite()
    return state.projects
  }
  return (await api.listProjects()) || []
}

export async function fetchProject(slug) {
  if (STATIC_MODE) {
    await loadSite()
    const found = state.projects.find((p) => p.slug === slug)
    if (!found) {
      // 抛出和接口一致的错误，页面那边不用为两种模式写两套判断
      throw new Error('项目不存在')
    }
    return found
  }
  return api.getProject(slug)
}

export async function fetchResumes() {
  if (STATIC_MODE) {
    await loadSite()
    return state.resumes
  }
  return (await api.listResumes()) || []
}

/* ---------------- 取文案的小工具 ---------------- */

/** 页面大标题 / 副标题，取不到就返回空对象 */
export function section(key) {
  return state.sections[key] || {}
}

/** 站点设置，取不到用兜底值 */
export function setting(key, fallback = '') {
  const value = state.settings[key]
  return value === undefined || value === null || value === '' ? fallback : value
}

/** 个人简介是多行文本，页面按行渲染 */
export function introLines() {
  return toLines(state.profile.intro)
}

/** 按 icon 找联系方式，比如 GitHub / Gitee 的地址 */
export function contactByIcon(icon) {
  return state.contacts.find((c) => c.icon === icon) || null
}
