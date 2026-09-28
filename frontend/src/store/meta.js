import { reactive } from 'vue'

/**
 * 实例级运行时配置：这个后端是不是「演示模式」（后台只读）、登录页要不要显示演示密钥。
 *
 * 为什么不放进站点内容（`/api/site`）：那是「内容」，静态模式下会被打进 snapshot.json；
 * 而这两个值是运行时配置——同一份前端产物既可能跑在只读演示站上，也可能跑在自己的站上，
 * 跟着快照一起缓存就会出现在线改了配置前台不生效的问题。
 */
export const metaState = reactive({
  demoMode: false,
  demoKeyHint: '',
  loaded: false,
})

let pending = null

/** 取一次就够了；失败按「普通站点」处理（最坏情况是少一条提示条，不影响功能）。 */
export function loadMeta() {
  if (metaState.loaded) {
    return Promise.resolve(metaState)
  }
  if (!pending) {
    pending = fetch('/api/meta')
      .then((res) => res.json())
      .then((body) => {
        const data = body && body.data ? body.data : {}
        metaState.demoMode = !!data.demoMode
        metaState.demoKeyHint = data.demoKeyHint || ''
      })
      .catch(() => {})
      .finally(() => {
        metaState.loaded = true
        pending = null
      })
  }
  return pending
}
