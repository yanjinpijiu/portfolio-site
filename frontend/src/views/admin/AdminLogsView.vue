<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../../api'

/**
 * 日志检索。
 *
 * 看板上的「最新 50 条」只能看个大概，真排查问题得能按日期、按 IP、按访客 ID 去翻，
 * 这一页就是干这个的：访问日志和接口日志两个数据源，共用一套筛选条和分页。
 *
 * 筛选条件会写回 URL（用 replace 而不是 push，免得翻两页就把浏览器的返回键塞满），
 * 所以在手机上能存一个「只看某天某个省来的访问」的地址，刷新也不丢。
 *
 * 接口日志那张表没有地区字段（只存了 IP），也没有页面类型，所以切到「接口」时
 * 那几个下拉会自动收起来，而不是给一堆选了没用的控件。
 */
const route = useRoute()
const router = useRouter()

/** 本地日期。不能用 toISOString()——那是 UTC，晚上 8 点之后会算成「明天」 */
function localDate(d = new Date()) {
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

function daysAgo(n) {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return localDate(d)
}

const type = ref('visit')
const from = ref(localDate())
const to = ref(localDate())
const page = ref(1)
const size = ref(50)
const q = ref('')
const path = ref('')
const pageType = ref('')
const country = ref('')
const province = ref('')
const city = ref('')
const browser = ref('')
const os = ref('')
const device = ref('')
const onlyFail = ref(false)
const includeInternal = ref(false)

const data = ref(null)
const options = ref(null)
const loading = ref(false)
const exporting = ref(false)
const error = ref('')

const isApi = computed(() => type.value === 'api')
const isDownload = computed(() => type.value === 'download')

/**
 * 一次请求的参数包。空值不传，后端把空串也当没填，两边都不硬塞。
 *
 * 三个数据源的可筛字段不一样：接口日志只有路径，下载日志有地区和简历，
 * 访问日志最全。不适用的一律不传，免得后端收到一个用不上的条件
 */
const params = computed(() => ({
  type: type.value,
  from: from.value,
  to: to.value,
  page: page.value,
  size: size.value,
  q: q.value,
  path: type.value === 'visit' ? path.value : '',
  pageType: type.value === 'visit' ? pageType.value : '',
  country: isApi.value ? '' : country.value,
  province: isApi.value ? '' : province.value,
  city: isApi.value ? '' : city.value,
  browser: type.value === 'visit' ? browser.value : '',
  os: type.value === 'visit' ? os.value : '',
  device: type.value === 'visit' ? device.value : '',
  onlyFail: isApi.value && onlyFail.value,
  includeInternal: isApi.value && includeInternal.value
}))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [rows, opts] = await Promise.all([
      api.adminStatsLogs(params.value),
      api.adminStatsLogOptions({ type: type.value, from: from.value, to: to.value })
    ])
    data.value = rows
    options.value = opts
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

/** 改了筛选条件就回第一页：停在第 3 页看新条件只会看到一张空表 */
function apply() {
  page.value = 1
  syncUrl()
  return load()
}

function goPage(next) {
  const pages = data.value?.pages || 0
  if (next < 1 || (pages && next > pages)) return
  page.value = next
  syncUrl()
  return load()
}

function changeSize(next) {
  size.value = Number(next)
  return apply()
}

function setRange(days) {
  from.value = days === 0 ? localDate() : daysAgo(days - 1)
  to.value = localDate()
  return apply()
}

function reset() {
  q.value = ''
  path.value = ''
  pageType.value = ''
  country.value = ''
  province.value = ''
  city.value = ''
  browser.value = ''
  os.value = ''
  device.value = ''
  onlyFail.value = false
  includeInternal.value = false
  from.value = localDate()
  to.value = localDate()
  return apply()
}

function syncUrl() {
  const query = {}
  Object.entries(params.value).forEach(([k, v]) => {
    if (v !== '' && v !== false && v !== undefined && v !== null) query[k] = String(v)
  })
  router.replace({ query })
}

/* ---------------- 地区三级联动 ---------------- */

const regions = computed(() => options.value?.regions || [])

const countries = computed(() => unique(regions.value.map((r) => r.country)))
const provinces = computed(() =>
  unique(regions.value.filter((r) => !country.value || r.country === country.value).map((r) => r.province)))
const cities = computed(() =>
  unique(regions.value
    .filter((r) => (!country.value || r.country === country.value) && (!province.value || r.province === province.value))
    .map((r) => r.city)))

function unique(list) {
  return [...new Set(list.filter((v) => v && v !== ''))]
}

// 上一级一改，下一级原来的选择几乎必然失效（换个国家还留着「浙江省」），直接清掉
watch(country, () => {
  province.value = ''
  city.value = ''
  apply()
})
watch(province, () => {
  city.value = ''
  apply()
})

watch([type], () => {
  pageType.value = ''
  country.value = ''
  province.value = ''
  city.value = ''
  browser.value = ''
  os.value = ''
  device.value = ''
  onlyFail.value = false
  includeInternal.value = false
  apply()
})

/* ---------------- 导出 ---------------- */

async function exportCsv() {
  exporting.value = true
  error.value = ''
  try {
    const { blob, fileName } = await api.adminExportLogs({ ...params.value, page: 1, size: undefined })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = fileName || 'logs.csv'
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 10000)
  } catch (e) {
    error.value = e.message || '导出失败'
  } finally {
    exporting.value = false
  }
}

/* ---------------- 初始化 ---------------- */

function readUrl() {
  const r = route.query
  // 三种类型都要认，不然从地址栏带着 type=download 进来会静默退回访问日志
  if (r.type === 'api' || r.type === 'download') type.value = r.type
  if (r.from) from.value = String(r.from)
  if (r.to) to.value = String(r.to)
  if (r.page) page.value = Math.max(1, Number(r.page) || 1)
  if (r.size) size.value = [50, 100, 200].includes(Number(r.size)) ? Number(r.size) : 50
  q.value = r.q ? String(r.q) : ''
  path.value = r.path ? String(r.path) : ''
  pageType.value = r.pageType ? String(r.pageType) : ''
  country.value = r.country ? String(r.country) : ''
  province.value = r.province ? String(r.province) : ''
  city.value = r.city ? String(r.city) : ''
  browser.value = r.browser ? String(r.browser) : ''
  os.value = r.os ? String(r.os) : ''
  device.value = r.device ? String(r.device) : ''
  onlyFail.value = r.onlyFail === 'true'
  includeInternal.value = r.includeInternal === 'true'
}

onMounted(() => {
  readUrl()
  load()
})

const rows = computed(() => data.value?.rows || [])
const pages = computed(() => data.value?.pages || 0)
const total = computed(() => data.value?.total || 0)
/** 有访问记录、但 IP 解析不出省份，所以地图上看不到的那部分 */
const unlocated = computed(() => data.value?.unlocated || 0)

function reload() {
  syncUrl()
  return load()
}
</script>

<template>
  <div>
    <div class="admin-head">
      <div>
        <h1>访问日志</h1>
        <p class="sub">
          按日期翻明细。条件会写进地址栏，刷新或在手机上打开都还是这一屏。
        </p>
      </div>
      <div class="row">
        <!-- 这一页只列明细，图表和汇总都在看板那边。不给个入口的话，
             很容易以为「统计没了」 -->
        <RouterLink class="btn btn--ghost btn--sm" :to="{ name: 'admin-dashboard' }">
          看图表统计去数据看板 →
        </RouterLink>
        <button class="btn btn--ghost btn--sm" type="button" @click="reload">刷新</button>
      </div>
    </div>

    <div v-if="error" class="alert alert--error">{{ error }}</div>

    <div class="card">
      <div class="row" style="gap: 6px; margin-bottom: 12px">
        <button
          v-for="t in [
            { key: 'visit', label: '访问日志' },
            { key: 'api', label: '接口日志' },
            { key: 'download', label: '下载日志' }
          ]"
          :key="t.key"
          type="button"
          class="tabs-none"
          style="padding: 5px 14px; font-size: 13px; border: 1px solid var(--line); border-radius: 999px; background: #fff; cursor: pointer"
          :style="type === t.key ? 'background: var(--blue-600); color: #fff; border-color: var(--blue-600)' : ''"
          @click="type = t.key"
        >
          {{ t.label }}
        </button>
      </div>

      <div class="row" style="gap: 8px; margin-bottom: 10px">
        <input v-model="from" class="input" type="date" style="width: 150px" @change="apply" />
        <span class="muted">至</span>
        <input v-model="to" class="input" type="date" style="width: 150px" @change="apply" />
        <button class="btn btn--ghost btn--sm" type="button" @click="setRange(1)">今天</button>
        <button class="btn btn--ghost btn--sm" type="button" @click="setRange(2)">昨天</button>
        <button class="btn btn--ghost btn--sm" type="button" @click="setRange(7)">近 7 天</button>
        <button class="btn btn--ghost btn--sm" type="button" @click="setRange(30)">近 30 天</button>
      </div>

      <div class="row" style="gap: 8px; margin-bottom: 10px">
        <input
          v-model="q"
          class="input"
          style="width: 260px"
          placeholder="搜 IP / 访客 ID / 路径"
          @keyup.enter="apply"
        />
        <button class="btn btn--primary btn--sm" type="button" @click="apply">搜索</button>
        <button class="btn btn--ghost btn--sm" type="button" @click="reset">重置</button>
        <button class="btn btn--ghost btn--sm" type="button" :disabled="exporting" @click="exportCsv">
          {{ exporting ? '导出中…' : '导出 CSV' }}
        </button>
      </div>

      <div class="row" style="gap: 8px">
        <select v-if="!isApi && !isDownload" v-model="path" class="input" style="width: 220px" @change="apply">
          <option value="">全部路径</option>
          <option v-for="p in options?.paths || []" :key="p" :value="p">{{ p }}</option>
        </select>

        <template v-if="!isApi">
          <select v-if="!isDownload" v-model="pageType" class="input" style="width: 130px" @change="apply">
            <option value="">全部页面</option>
            <option v-for="p in options?.pageTypes || []" :key="p" :value="p">{{ p }}</option>
          </select>

          <select v-model="country" class="input" style="width: 120px">
            <option value="">全部国家</option>
            <option v-for="c in countries" :key="c" :value="c">{{ c }}</option>
          </select>
          <select v-model="province" class="input" style="width: 140px">
            <option value="">全部省份</option>
            <option v-for="p in provinces" :key="p" :value="p">{{ p }}</option>
          </select>
          <select v-model="city" class="input" style="width: 140px" @change="apply">
            <option value="">全部城市</option>
            <option v-for="c in cities" :key="c" :value="c">{{ c }}</option>
          </select>

          <template v-if="!isDownload">
            <select v-model="browser" class="input" style="width: 120px" @change="apply">
              <option value="">全部浏览器</option>
              <option v-for="b in options?.browsers || []" :key="b" :value="b">{{ b }}</option>
            </select>
            <select v-model="os" class="input" style="width: 120px" @change="apply">
              <option value="">全部系统</option>
              <option v-for="o in options?.oses || []" :key="o" :value="o">{{ o }}</option>
            </select>
            <select v-model="device" class="input" style="width: 110px" @change="apply">
              <option value="">全部设备</option>
              <option v-for="d in options?.devices || []" :key="d" :value="d">{{ d }}</option>
            </select>
          </template>
        </template>

        <template v-if="isApi">
          <label class="row" style="gap: 4px">
            <input v-model="onlyFail" type="checkbox" @change="apply" />
            <span class="muted">只看失败</span>
          </label>
          <label class="row" style="gap: 4px">
            <input v-model="includeInternal" type="checkbox" @change="apply" />
            <span class="muted">包含后台自己</span>
          </label>
        </template>
      </div>
    </div>

    <div class="card">
      <h2>
        明细
        <span class="muted">共 {{ total }} 条</span>
      </h2>

      <div v-if="loading" class="skeleton" style="height: 200px"></div>

      <!-- 访问日志 -->
      <table v-else-if="!isApi && !isDownload" class="admin-table">
        <thead>
          <tr>
            <th style="width: 150px">时间</th>
            <th>路径</th>
            <th style="width: 90px">页面</th>
            <th style="width: 140px">IP</th>
            <th style="width: 150px">归属地</th>
            <th style="width: 110px">访客 ID</th>
            <th style="width: 170px">终端</th>
            <th style="width: 130px">来源</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id">
            <td class="muted nowrap">{{ (row.at || '').slice(5) }}</td>
            <td>{{ row.path }}</td>
            <td class="muted">{{ row.pageType || '—' }}</td>
            <td class="muted">{{ row.ip }}</td>
            <td class="muted">{{ row.region || '—' }}</td>
            <td class="muted">{{ (row.visitorId || '').slice(0, 8) || '—' }}</td>
            <td class="muted">{{ [row.browser, row.os, row.device].filter(Boolean).join(' / ') }}</td>
            <td class="muted">{{ row.referer ? row.referer.slice(0, 24) : '直接进入' }}</td>
          </tr>
          <tr v-if="!rows.length"><td colspan="8" class="muted">这段时间没有记录</td></tr>
        </tbody>
      </table>

      <!-- 接口日志 -->
      <table v-else-if="isApi" class="admin-table">
        <thead>
          <tr>
            <th style="width: 150px">时间</th>
            <th>路径</th>
            <th style="width: 70px">方法</th>
            <th style="width: 80px">业务码</th>
            <th style="width: 70px">HTTP</th>
            <th style="width: 90px">耗时</th>
            <th style="width: 140px">IP</th>
            <th style="width: 150px">归属地</th>
            <th style="width: 90px">来源</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id">
            <td class="muted nowrap">{{ (row.at || '').slice(5) }}</td>
            <td>{{ row.path }}</td>
            <td class="muted">{{ row.method }}</td>
            <td class="num" :style="row.bizCode ? 'color: #C0634F; font-weight: 600' : ''">{{ row.bizCode }}</td>
            <td class="num">{{ row.httpStatus }}</td>
            <td class="num">{{ row.durationMs }} ms</td>
            <td class="muted">{{ row.ip }}</td>
            <td class="muted">{{ row.region || '—' }}</td>
            <td class="muted">{{ row.internal ? '后台' : '外部' }}</td>
          </tr>
          <tr v-if="!rows.length"><td colspan="9" class="muted">这段时间没有记录</td></tr>
        </tbody>
      </table>

      <!-- 下载日志 -->
      <table v-else class="admin-table">
        <thead>
          <tr>
            <th style="width: 150px">时间</th>
            <th>简历</th>
            <th style="width: 120px">方向</th>
            <th style="width: 140px">IP</th>
            <th style="width: 150px">归属地</th>
            <th style="width: 110px">访客 ID</th>
            <th style="width: 110px">放行方式</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id">
            <td class="muted nowrap">{{ (row.at || '').slice(5) }}</td>
            <td>{{ row.title || `#${row.resumeId}` }}</td>
            <td class="muted">{{ row.direction || '—' }}</td>
            <td class="muted">{{ row.ip }}</td>
            <td class="muted">{{ row.region || '—' }}</td>
            <td class="muted">{{ (row.visitorId || '').slice(0, 8) || '—' }}</td>
            <td><span class="pill">{{ row.freePass ? '免验证额度' : '过验证码' }}</span></td>
          </tr>
          <tr v-if="!rows.length"><td colspan="7" class="muted">这段时间没有记录</td></tr>
        </tbody>
      </table>

      <p v-if="type === 'visit' && unlocated" class="muted" style="margin-top: 10px">
        其中 {{ unlocated }} 条没能解析出省份，不会出现在地区地图和省份排行榜里。
      </p>

      <div class="row row--between" style="margin-top: 14px">
        <div class="row" style="gap: 6px">
          <button class="btn btn--ghost btn--sm" type="button" :disabled="page <= 1 || loading" @click="goPage(page - 1)">
            上一页
          </button>
          <span class="muted">第 {{ page }} / {{ pages || 1 }} 页</span>
          <button
            class="btn btn--ghost btn--sm"
            type="button"
            :disabled="loading || !pages || page >= pages"
            @click="goPage(page + 1)"
          >
            下一页
          </button>
        </div>
        <div class="row" style="gap: 6px">
          <span class="muted">每页</span>
          <select class="input" style="width: 90px" :value="size" @change="changeSize($event.target.value)">
            <option v-for="n in [50, 100, 200]" :key="n" :value="n">{{ n }} 条</option>
          </select>
        </div>
      </div>
    </div>
  </div>
</template>
