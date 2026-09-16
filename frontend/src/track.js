/**
 * 访客埋点。每次路由切换、以及页面从后台切回前台时，上报一次当前路径。
 *
 * 三条原则：
 *
 * 1. **服务端只信自己**。请求体只带路径和路由名，IP、UA、页面类型、项目 slug
 *    全部由服务端自己推导——前端能改的东西都不算数。
 * 2. **别拖慢页面**。用 `sendBeacon` 发，浏览器在空闲时投递，不占用页面线程，
 *    也不影响导航；不支持时退回 `fetch` + `keepalive`。
 * 3. **不做任何重试**。埋点丢一条无所谓，为了它排队重试反而会干扰正常浏览。
 *    服务端还会对同一访客几秒内的重复上报去重，所以这里不用自己去重。
 *
 * 后台页面不埋点：那是自己在维护内容，不该算进访客数据。
 */
const TRACK_URL = '/api/track'

function shouldTrack(path) {
  return !path.startsWith('/admin')
}

/**
 * 上报一次访问。
 *
 * @param {string} path  当前路径（不含域名），如 /projects/jos
 * @param {string} route 当前路由名，服务端只放行字母数字中划线
 */
export function trackView(path, route) {
  if (!path || !shouldTrack(path)) {
    return
  }
  const payload = JSON.stringify({ path, route: route || '' })

  if (navigator.sendBeacon) {
    // 指定 type 才会发 Content-Type: application/json，否则后端按表单解析会 415
    const blob = new Blob([payload], { type: 'application/json' })
    if (navigator.sendBeacon(TRACK_URL, blob)) {
      return
    }
  }

  fetch(TRACK_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: payload,
    keepalive: true
  }).catch(() => {
    /* 埋点失败就算了，不影响用户 */
  })
}

/**
 * 页面从后台切回前台时补记一次。
 *
 * 手机上很常见：点开链接、切到别的 App、过一会儿回来。
 * 这段时间里路由没变，但确实是新的一次浏览（也可能是很长的一次停留）。
 * 服务端有 5 秒去重，所以频繁切换也不会刷出假数据。
 */
export function installVisibilityTracking(getCurrent) {
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible') {
      return
    }
    const current = getCurrent()
    if (current) {
      trackView(current.path, current.name)
    }
  })
}
