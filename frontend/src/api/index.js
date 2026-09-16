const BASE = import.meta.env.VITE_API_BASE || ''

const TOKEN_KEY = 'portfolio_admin_token'

export class ApiError extends Error {
  constructor(code, message) {
    super(message)
    this.code = code
  }
}

export const tokenStore = {
  get() {
    try {
      return localStorage.getItem(TOKEN_KEY) || ''
    } catch {
      return ''
    }
  },
  set(token) {
    try {
      localStorage.setItem(TOKEN_KEY, token)
    } catch {
      /* 隐私模式下忽略 */
    }
  },
  clear() {
    try {
      localStorage.removeItem(TOKEN_KEY)
    } catch {
      /* 忽略 */
    }
  }
}

/**
 * 令牌失效时的统一处理。后台布局挂载时注册一次，任何页面遇到 401 都会被踢回登录页，
 * 不用在每个请求的地方各写一遍判断。
 */
let onUnauthorized = null

export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn
}

async function request(path, { method = 'GET', body, headers = {} } = {}) {
  const finalHeaders = { ...headers }

  const token = tokenStore.get()
  if (token && path.startsWith('/api/admin')) {
    finalHeaders['X-Admin-Token'] = token
  }

  let payloadBody = body
  if (body && !(body instanceof FormData)) {
    finalHeaders['Content-Type'] = 'application/json'
    payloadBody = JSON.stringify(body)
  }

  const res = await fetch(BASE + path, {
    method,
    headers: finalHeaders,
    body: payloadBody
  })

  let data = null
  try {
    data = await res.json()
  } catch {
    /* 非 JSON 响应 */
  }

  if (res.status === 401) {
    tokenStore.clear()
    if (onUnauthorized) {
      onUnauthorized()
    }
    throw new ApiError(401, data?.message || '登录已失效，请重新登录')
  }
  if (!res.ok) {
    throw new ApiError(res.status, data?.message || `请求失败（${res.status}）`)
  }
  if (data && data.code !== 0) {
    throw new ApiError(data.code, data.message || '请求失败')
  }
  return data ? data.data : null
}

function toQuery(params) {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.append(key, String(value))
    }
  })
  return search.toString()
}

export const api = {
  /* 公开接口 */
  getSite: () => request('/api/site'),
  listProjects: () => request('/api/projects'),
  getProject: (slug) => request(`/api/projects/${encodeURIComponent(slug)}`),
  listResumes: () => request('/api/resumes'),
  getCaptcha: () => request('/api/captcha'),

  /**
   * 下载简历。
   *
   * 成功时返回的是文件流而不是 JSON，所以不能复用上面那个 request()——
   * 它总是先试 res.json()，会把文件内容吃掉、返回 null。
   *
   * 需要验证码时服务端返回的不是错误状态码，而是 HTTP 200 + 业务码 428
   * （整个项目的约定就是「出错也返回 200，业务码写在响应体里」），
   * 所以这里按 Content-Type 分流：JSON 就当成业务错误抛出，其余当成文件。
   */
  async downloadResume(id, { captchaId, captchaAnswer } = {}) {
    const query = toQuery({ captchaId, captchaAnswer })
    const res = await fetch(`${BASE}/api/resumes/${id}/download${query ? `?${query}` : ''}`)

    const contentType = res.headers.get('Content-Type') || ''
    if (contentType.includes('application/json')) {
      const data = await res.json().catch(() => null)
      throw new ApiError(data?.code ?? res.status, data?.message || '下载失败')
    }
    if (!res.ok) {
      throw new ApiError(res.status, `下载失败（${res.status}）`)
    }
    const blob = await res.blob()
    return { blob, fileName: fileNameFrom(res.headers.get('Content-Disposition')) }
  },

  /* 后台：简历 */
  login: (key) => request('/api/admin/login', { method: 'POST', body: { key } }),
  logout: () => request('/api/admin/logout', { method: 'POST' }),
  checkSession: () => request('/api/admin/session'),
  adminListResumes: () => request('/api/admin/resumes'),
  adminUploadResume: (formData) => request('/api/admin/resumes', { method: 'POST', body: formData }),
  adminUpdateResume: (id, params) =>
    request(`/api/admin/resumes/${id}?${toQuery(params)}`, { method: 'PUT' }),
  adminDeleteResume: (id) => request(`/api/admin/resumes/${id}`, { method: 'DELETE' }),

  /* 后台：站点内容（个人资料 / 联系方式 / 页面文案 / 站点设置） */
  adminProfile: () => request('/api/admin/site/profile'),
  adminUpdateProfile: (body) => request('/api/admin/site/profile', { method: 'PUT', body }),
  adminSetAvatar: (key) => request('/api/admin/site/profile/avatar', { method: 'PUT', body: { avatarKey: key } }),
  adminListContacts: () => request('/api/admin/site/contacts'),
  adminCreateContact: (body) => request('/api/admin/site/contacts', { method: 'POST', body }),
  adminUpdateContact: (id, body) => request(`/api/admin/site/contacts/${id}`, { method: 'PUT', body }),
  adminDeleteContact: (id) => request(`/api/admin/site/contacts/${id}`, { method: 'DELETE' }),
  adminListSections: () => request('/api/admin/site/sections'),
  adminUpdateSection: (id, body) => request(`/api/admin/site/sections/${id}`, { method: 'PUT', body }),
  adminListSettings: () => request('/api/admin/site/settings'),
  adminSaveSettings: (values) => request('/api/admin/site/settings', { method: 'PUT', body: values }),

  /* 后台：前台内容模式（静态 / 动态） */
  adminSiteMode: () => request('/api/admin/site/mode'),
  adminSetSiteMode: (mode) => request('/api/admin/site/mode', { method: 'POST', body: { mode } }),
  adminRebuildSnapshot: () => request('/api/admin/site/snapshot', { method: 'POST' }),

  /* 后台：技能分组 / 条目 / 关联项目 */
  adminListSkillGroups: () => request('/api/admin/content/skill-groups'),
  adminCreateSkillGroup: (body) => request('/api/admin/content/skill-groups', { method: 'POST', body }),
  adminUpdateSkillGroup: (id, body) =>
    request(`/api/admin/content/skill-groups/${id}`, { method: 'PUT', body }),
  adminDeleteSkillGroup: (id) => request(`/api/admin/content/skill-groups/${id}`, { method: 'DELETE' }),
  adminListSkillItems: (groupId) =>
    request(`/api/admin/content/skill-items?${toQuery({ groupId })}`),
  adminCreateSkillItem: (body) => request('/api/admin/content/skill-items', { method: 'POST', body }),
  adminUpdateSkillItem: (id, body) =>
    request(`/api/admin/content/skill-items/${id}`, { method: 'PUT', body }),
  adminDeleteSkillItem: (id) => request(`/api/admin/content/skill-items/${id}`, { method: 'DELETE' }),
  adminListSkillRelated: (groupId) =>
    request(`/api/admin/content/skill-related?${toQuery({ groupId })}`),
  adminCreateSkillRelated: (body) => request('/api/admin/content/skill-related', { method: 'POST', body }),
  adminDeleteSkillRelated: (id) => request(`/api/admin/content/skill-related/${id}`, { method: 'DELETE' }),

  /* 后台：证书 */
  adminListCertificates: () => request('/api/admin/content/certificates'),
  adminCreateCertificate: (body) => request('/api/admin/content/certificates', { method: 'POST', body }),
  adminUpdateCertificate: (id, body) =>
    request(`/api/admin/content/certificates/${id}`, { method: 'PUT', body }),
  adminDeleteCertificate: (id) => request(`/api/admin/content/certificates/${id}`, { method: 'DELETE' }),

  /* 后台：项目与成果图 */
  adminListProjects: () => request('/api/admin/projects'),
  adminGetProject: (id) => request(`/api/admin/projects/${id}`),
  adminCreateProject: (body) => request('/api/admin/projects', { method: 'POST', body }),
  adminUpdateProject: (id, body) => request(`/api/admin/projects/${id}`, { method: 'PUT', body }),
  adminDeleteProject: (id) => request(`/api/admin/projects/${id}`, { method: 'DELETE' }),
  adminListProjectImages: (slug) => request(`/api/admin/content/project-images?${toQuery({ slug })}`),
  adminCreateProjectImage: (body) => request('/api/admin/content/project-images', { method: 'POST', body }),
  adminUpdateProjectImage: (id, body) =>
    request(`/api/admin/content/project-images/${id}`, { method: 'PUT', body }),
  adminDeleteProjectImage: (id) =>
    request(`/api/admin/content/project-images/${id}`, { method: 'DELETE' }),

  /* 后台：图片上传。返回 { key, url }，key 存进库、url 用来预览 */
  adminUploadImage: (formData) => request('/api/admin/upload/image', { method: 'POST', body: formData }),

  /* 后台：数据看板 */
  adminStatsOverview: (days) => request(`/api/admin/stats/overview?${toQuery({ days })}`),
  adminStatsVisits: (days) => request(`/api/admin/stats/visits?${toQuery({ days })}`),
  adminStatsApi: (days, includeInternal) =>
    request(`/api/admin/stats/api?${toQuery({ days, includeInternal })}`),
  adminStatsResumes: (days, resumeId) =>
    request(`/api/admin/stats/resumes?${toQuery({ days, resumeId })}`),

  /**
   * 导出内容 JSON。响应是文件流，所以和简历预览一样绕开 request() 自己取 blob——
   * 令牌走请求头，普通链接带不上，只能用 fetch。
   */
  async adminExportBackup() {
    const res = await fetch(`${BASE}/api/admin/backup/export`, {
      headers: { 'X-Admin-Token': tokenStore.get() }
    })
    const contentType = res.headers.get('Content-Type') || ''
    if (contentType.includes('application/json') && !contentType.includes('application/octet')) {
      // 出错时后端返回的是 ApiResponse，不是文件
      const data = await res.json().catch(() => null)
      if (res.status === 401 || (data && data.code === 401)) {
        tokenStore.clear()
        if (onUnauthorized) onUnauthorized()
      }
      throw new ApiError(data?.code ?? res.status, data?.message || '导出失败')
    }
    if (!res.ok) {
      throw new ApiError(res.status, `导出失败（${res.status}）`)
    }
    return { blob: await res.blob(), fileName: fileNameFrom(res.headers.get('Content-Disposition')) }
  },

  /** 后台预览：需要带令牌，所以取回 blob 再交给浏览器打开 */
  async adminPreview(id) {
    const res = await fetch(`${BASE}/api/admin/resumes/${id}/file`, {
      headers: { 'X-Admin-Token': tokenStore.get() }
    })
    if (!res.ok) {
      throw new ApiError(res.status, '预览失败')
    }
    const blob = await res.blob()
    return URL.createObjectURL(blob)
  }
}

/**
 * 从 Content-Disposition 里取文件名。
 * 服务端给的是 RFC 5987 的 filename*=UTF-8''，中文文件名要靠它才不会是乱码。
 */
function fileNameFrom(disposition) {
  if (!disposition) return ''
  const star = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (star) {
    try {
      return decodeURIComponent(star[1])
    } catch {
      /* 编码坏了就退回下面的普通 filename */
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(disposition)
  return plain ? plain[1] : ''
}


/** 把换行分隔的字段转成数组 */
export function toLines(value) {
  if (!value) return []
  return String(value)
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
}

/** 人类可读的文件大小 */
export function formatSize(bytes) {
  if (!bytes || bytes < 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** 2026-09-15T16:42:40 -> 2026-09-15 */
export function formatDate(value) {
  if (!value) return '—'
  return String(value).slice(0, 10)
}
