<script setup>
import { computed, onMounted, ref } from 'vue'
import { api, formatDate } from '../../api'
import ChartBox from '../../components/admin/ChartBox.vue'

/**
 * 数据看板。
 *
 * 三块：访客 / 接口 / 简历下载。指标口径照搬短链那套（PV、UV、UIP、24 小时、
 * 星期、地区、终端、新老访客、高频 IP、接口调用量、登录专项、下载排行），
 * 但查询是直接 GROUP BY 明细表——这里是单实例 + 日均几十 PV，用不上预聚合那套。
 *
 * 图表全部由 ChartBox 异步加载的 echarts 渲染，不会进公开页面的包。
 */
const days = ref(30)
const tab = ref('visits')
const includeInternal = ref(false)

const overview = ref(null)
const visits = ref(null)
const apiStats = ref(null)
const resumeStats = ref(null)
const loading = ref(false)
const error = ref('')

async function loadOverview() {
  overview.value = await api.adminStatsOverview(days.value)
}

async function loadTab() {
  loading.value = true
  error.value = ''
  try {
    if (tab.value === 'visits') {
      visits.value = await api.adminStatsVisits(days.value)
    } else if (tab.value === 'api') {
      apiStats.value = await api.adminStatsApi(days.value, includeInternal.value)
    } else {
      resumeStats.value = await api.adminStatsResumes(days.value)
    }
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function switchTab(next) {
  tab.value = next
  await loadTab()
}

async function changeDays(next) {
  days.value = next
  await Promise.all([loadOverview(), loadTab()])
}

async function reload() {
  await Promise.all([loadOverview(), loadTab(), loadMode()])
}

onMounted(reload)

/* ---------------- 图表配置 ---------------- */

const AXIS_STYLE = {
  axisLine: { lineStyle: { color: '#D2DEEA' } },
  axisLabel: { color: '#64748B', fontSize: 11 },
  axisTick: { show: false }
}

const GRID = { left: 44, right: 16, top: 30, bottom: 28 }

const num = (v) => (v === undefined || v === null ? 0 : v)

/** PV / UV / UIP 按天折线 + 新老访客 */
const trendOption = computed(() => {
  const rows = visits.value?.daily || []
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['PV', 'UV', 'UIP', '新访客', '老访客'], right: 0, top: 0, textStyle: { fontSize: 11 } },
    grid: GRID,
    xAxis: { type: 'category', data: rows.map((r) => r.date.slice(5)), ...AXIS_STYLE },
    yAxis: { type: 'value', ...AXIS_STYLE },
    series: [
      { name: 'PV', type: 'line', smooth: true, data: rows.map((r) => r.pv), itemStyle: { color: '#356896' } },
      { name: 'UV', type: 'line', smooth: true, data: rows.map((r) => r.uv), itemStyle: { color: '#4A82B5' } },
      { name: 'UIP', type: 'line', smooth: true, data: rows.map((r) => r.uip), itemStyle: { color: '#A3C6E8' } },
      { name: '新访客', type: 'line', smooth: true, data: rows.map((r) => r.fresh), itemStyle: { color: '#7FA96B' } },
      { name: '老访客', type: 'line', smooth: true, data: rows.map((r) => r.returning), itemStyle: { color: '#C99A5B' } }
    ]
  }
})

/** 24 小时分布 */
const hourOption = computed(() => {
  const rows = visits.value?.hourly || []
  return {
    tooltip: { trigger: 'axis' },
    grid: GRID,
    xAxis: { type: 'category', data: rows.map((r) => r.hour), name: '时', ...AXIS_STYLE },
    yAxis: { type: 'value', ...AXIS_STYLE },
    series: [{ name: 'PV', type: 'bar', data: rows.map((r) => r.pv), itemStyle: { color: '#74A5D4' } }]
  }
})

/** 星期分布 */
const weekdayOption = computed(() => {
  const rows = visits.value?.weekday || []
  const names = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
  return {
    tooltip: { trigger: 'axis' },
    grid: GRID,
    xAxis: { type: 'category', data: rows.map((r) => names[num(r.weekday - 1)] || r.weekday), ...AXIS_STYLE },
    yAxis: { type: 'value', ...AXIS_STYLE },
    series: [{ name: 'PV', type: 'bar', data: rows.map((r) => r.pv), itemStyle: { color: '#A3C6E8' } }]
  }
})

/** 地区：省份地图 + TOP10 条形 */
const provinceOption = computed(() => {
  const rows = visits.value?.province || []
  return {
    tooltip: {
      trigger: 'item',
      formatter: (p) => `${p.name}：${p.value ?? 0} 次`
    },
    visualMap: {
      min: 0,
      max: Math.max(1, ...rows.map((r) => r.pv)),
      left: 0,
      bottom: 0,
      text: ['多', '少'],
      inRange: { color: ['#EAF2FA', '#74A5D4', '#27506F'] },
      textStyle: { fontSize: 11 }
    },
    series: [{
      type: 'map',
      map: 'china',
      roam: false,
      label: { show: false },
      data: rows.map((r) => ({ name: mapName(r.name), value: r.pv }))
    }]
  }
})

/**
 * ip2region 给的是简称（北京、内蒙古、香港），中国地图 GeoJSON 里是全称（北京市、内蒙古自治区）。
 * 对不上名字的省份在地图上不显示，但不影响下面的排行榜，所以这里只做尽力归一。
 */
const REGION_FULL = {
  北京: '北京市',
  上海: '上海市',
  天津: '天津市',
  重庆: '重庆市',
  内蒙古: '内蒙古自治区',
  广西: '广西壮族自治区',
  西藏: '西藏自治区',
  宁夏: '宁夏回族自治区',
  新疆: '新疆维吾尔自治区',
  香港: '香港特别行政区',
  澳门: '澳门特别行政区'
}

function mapName(name) {
  if (!name) return ''
  if (REGION_FULL[name]) return REGION_FULL[name]
  // 已经是全称或带「省」的直接用；剩下的大概率是「江苏」这种简称，补一个「省」
  if (name.endsWith('省') || name.endsWith('市') || name.endsWith('自治区') || name.endsWith('特别行政区')) {
    return name
  }
  return `${name}省`
}

const provinceRankOption = computed(() => {
  const rows = [...(visits.value?.province || [])].slice(0, 10).reverse()
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 60, right: 30, top: 16, bottom: 24 },
    xAxis: { type: 'value', ...AXIS_STYLE },
    yAxis: { type: 'category', data: rows.map((r) => r.name), ...AXIS_STYLE },
    series: [{
      type: 'bar',
      data: rows.map((r) => r.pv),
      itemStyle: { color: '#74A5D4' },
      label: { show: true, position: 'right', fontSize: 11, color: '#64748B' }
    }]
  }
})

/** 终端占比（浏览器 / 系统 / 设备 共用一套配置） */
function pieOption(rows, colors) {
  return {
    tooltip: { trigger: 'item', formatter: '{b}：{c} 次（{d}%）' },
    legend: { orient: 'vertical', right: 0, top: 'center', textStyle: { fontSize: 11 } },
    series: [{
      type: 'pie',
      radius: ['42%', '68%'],
      center: ['38%', '50%'],
      label: { show: false },
      data: (rows || []).map((r, i) => ({
        name: r.name || '未知',
        value: r.pv,
        itemStyle: { color: colors[i % colors.length] }
      }))
    }]
  }
}

const browserOption = computed(() => pieOption(visits.value?.browser, ['#356896', '#4A82B5', '#74A5D4', '#A3C6E8', '#C8DEF3']))
const osOption = computed(() => pieOption(visits.value?.os, ['#27506F', '#4A82B5', '#74A5D4', '#C8DEF3']))
const deviceOption = computed(() => pieOption(visits.value?.device, ['#356896', '#74A5D4', '#C8DEF3']))
const pageOption = computed(() => pieOption(visits.value?.pageType, ['#356896', '#4A82B5', '#74A5D4', '#A3C6E8', '#C8DEF3']))

/* ---------------- 接口面板 ---------------- */

const apiTrendOption = computed(() => {
  const rows = apiStats.value?.daily || []
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['调用量', '失败', '后台自己'], right: 0, top: 0, textStyle: { fontSize: 11 } },
    grid: GRID,
    xAxis: { type: 'category', data: rows.map((r) => r.date.slice(5)), ...AXIS_STYLE },
    yAxis: { type: 'value', ...AXIS_STYLE },
    series: [
      { name: '调用量', type: 'line', smooth: true, data: rows.map((r) => r.count), itemStyle: { color: '#356896' } },
      { name: '失败', type: 'line', smooth: true, data: rows.map((r) => r.fail), itemStyle: { color: '#C0634F' } },
      { name: '后台自己', type: 'line', smooth: true, data: rows.map((r) => r.internal), itemStyle: { color: '#C8DEF3' } }
    ]
  }
})

const loginTrendOption = computed(() => {
  const rows = apiStats.value?.login?.trend || []
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['尝试', '失败'], right: 0, top: 0, textStyle: { fontSize: 11 } },
    grid: GRID,
    xAxis: { type: 'category', data: rows.map((r) => r.date.slice(5)), ...AXIS_STYLE },
    yAxis: { type: 'value', ...AXIS_STYLE },
    series: [
      { name: '尝试', type: 'bar', data: rows.map((r) => r.count), itemStyle: { color: '#A3C6E8' } },
      { name: '失败', type: 'bar', data: rows.map((r) => r.fail), itemStyle: { color: '#C0634F' } }
    ]
  }
})

const loginHourOption = computed(() => {
  const rows = apiStats.value?.login?.byHour || []
  return {
    tooltip: { trigger: 'axis' },
    grid: GRID,
    xAxis: { type: 'category', data: rows.map((r) => r.hour), name: '时', ...AXIS_STYLE },
    yAxis: { type: 'value', ...AXIS_STYLE },
    series: [{ type: 'bar', data: rows.map((r) => r.fail), itemStyle: { color: '#C0634F' } }]
  }
})

/* ---------------- 简历面板 ---------------- */

const resumeTrendOption = computed(() => {
  const rows = resumeStats.value?.daily || []
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['下载次数', '下载人数'], right: 0, top: 0, textStyle: { fontSize: 11 } },
    grid: GRID,
    xAxis: { type: 'category', data: rows.map((r) => r.date.slice(5)), ...AXIS_STYLE },
    yAxis: { type: 'value', ...AXIS_STYLE },
    series: [
      { name: '下载次数', type: 'line', smooth: true, data: rows.map((r) => r.count), itemStyle: { color: '#356896' } },
      { name: '下载人数', type: 'line', smooth: true, data: rows.map((r) => r.uv), itemStyle: { color: '#7FA96B' } }
    ]
  }
})

const resumeRankOption = computed(() => {
  const rows = [...(resumeStats.value?.byResume || [])].reverse()
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 130, right: 40, top: 16, bottom: 24 },
    xAxis: { type: 'value', ...AXIS_STYLE },
    yAxis: {
      type: 'category',
      data: rows.map((r) => r.title || `#${r.resumeId}`),
      ...AXIS_STYLE,
      axisLabel: { color: '#64748B', fontSize: 11, width: 120, overflow: 'truncate' }
    },
    series: [{
      type: 'bar',
      data: rows.map((r) => r.count),
      itemStyle: { color: '#4A82B5' },
      label: { show: true, position: 'right', fontSize: 11, color: '#64748B' }
    }]
  }
})

/* ---------------- 概览数字 ---------------- */

const cards = computed(() => {
  const o = overview.value
  if (!o) return []
  return [
    { label: '今日 PV', value: num(o.today?.pv), hint: `今日 UV ${num(o.today?.uv)}` },
    { label: `近 ${o.days} 天 PV`, value: num(o.range?.pv), hint: `UV ${num(o.range?.uv)} · UIP ${num(o.range?.uip)}` },
    { label: '简历下载', value: num(o.download?.cnt), hint: `独立访客 ${num(o.download?.uv)}` },
    { label: '接口调用', value: num(o.api?.cnt), hint: `失败 ${num(o.api?.fail)} 次` },
    { label: '正在下载', value: `${num(o.activeDownloads)} / ${num(o.maxConcurrentDownloads)}`, hint: '并发闸门占用' },
    {
      label: '埋点队列',
      value: num(o.droppedLogs),
      hint: o.droppedLogs > 0 ? '有埋点被丢弃，可考虑调大队列' : '暂无丢弃',
      warn: o.droppedLogs > 0
    },
    {
      label: '验证码',
      value: o.captchaAvailable ? '正常' : '已降级',
      hint: o.captchaAvailable ? '服务器能画图' : '服务器缺字体，下载不验证码直接放行',
      warn: !o.captchaAvailable
    }
  ]
})

/* ---------------- 前台内容模式 ---------------- */

const siteMode = ref(null)
const modeBusy = ref(false)
const modeNotice = ref('')

async function loadMode() {
  try {
    siteMode.value = await api.adminSiteMode()
  } catch (e) {
    modeNotice.value = e.message || '模式状态读取失败'
  }
}

async function switchMode(next) {
  modeBusy.value = true
  modeNotice.value = ''
  try {
    siteMode.value = await api.adminSetSiteMode(next)
    modeNotice.value = next === 'static'
      ? '已切到静态模式：前台改为读快照，内容改了要点「重新生成快照」'
      : '已切回动态模式：前台实时查库，改完刷新就生效'
  } catch (e) {
    modeNotice.value = e.message || '切换失败'
  } finally {
    modeBusy.value = false
  }
}

async function rebuildSnapshot() {
  modeBusy.value = true
  modeNotice.value = ''
  try {
    siteMode.value = await api.adminRebuildSnapshot()
    modeNotice.value = '快照已按当前内容重新生成'
  } catch (e) {
    modeNotice.value = e.message || '生成失败'
  } finally {
    modeBusy.value = false
  }
}

const snapshotAtText = computed(() =>
  siteMode.value?.snapshotAt ? siteMode.value.snapshotAt.replace('T', ' ').slice(0, 19) : '—')

const recentVisits = computed(() => visits.value?.recent || [])
const topIps = computed(() => (visits.value?.topIps || []).slice(0, 12))
const downloadRows = computed(() => resumeStats.value?.recent || [])
</script>

<template>
  <div>
    <div class="admin-head">
      <div>
        <h1>数据看板</h1>
        <p class="sub">访客、接口、简历下载三块。数字随埋点实时写入，刷新即可。</p>
      </div>
      <div class="row">
        <button
          v-for="d in [7, 30, 90]"
          :key="d"
          class="tabs-none"
          :class="{ 'is-active': days === d }"
          type="button"
          style="padding: 5px 12px; font-size: 13px; border: 1px solid var(--line); border-radius: 999px; background: #fff; cursor: pointer"
          :style="days === d ? 'background: var(--blue-600); color: #fff; border-color: var(--blue-600)' : ''"
          @click="changeDays(d)"
        >
          近 {{ d }} 天
        </button>
        <button class="btn btn--ghost btn--sm" type="button" @click="reload">刷新</button>
      </div>
    </div>

    <div v-if="error" class="alert alert--error">{{ error }}</div>

    <div class="stat-cards">
      <div v-for="c in cards" :key="c.label" class="stat-card" :class="{ 'stat-card--warn': c.warn }">
        <div class="stat-card__label">{{ c.label }}</div>
        <div class="stat-card__value">{{ c.value }}</div>
        <div class="stat-card__hint">{{ c.hint }}</div>
      </div>
    </div>

    <div v-if="siteMode" class="card">
      <h2>
        前台内容模式
        <span class="muted">
          静态 = 读预生成的快照文件，首屏少一次查库往返；动态 = 每次实时查库，改完立刻生效
        </span>
      </h2>
      <div class="row row--between">
        <div class="row">
          <span class="pill" :class="{ 'pill--warn': siteMode.mode === 'static' }">
            当前：{{ siteMode.mode === 'static' ? '静态' : '动态' }}
          </span>
          <span class="muted" v-if="siteMode.mode === 'static'">
            快照生成于 {{ snapshotAtText }}（{{ Math.round((siteMode.snapshotSize || 0) / 1024) }} KB）
          </span>
        </div>
        <div class="row">
          <button
            class="btn btn--ghost btn--sm"
            type="button"
            :disabled="modeBusy || siteMode.mode === 'dynamic'"
            @click="switchMode('dynamic')"
          >
            切到动态
          </button>
          <button
            class="btn btn--primary btn--sm"
            type="button"
            :disabled="modeBusy || siteMode.mode === 'static' || !siteMode.available"
            @click="switchMode('static')"
          >
            切到静态
          </button>
          <button
            v-if="siteMode.mode === 'static'"
            class="btn btn--ghost btn--sm"
            type="button"
            :disabled="modeBusy"
            @click="rebuildSnapshot"
          >
            重新生成快照
          </button>
        </div>
      </div>

      <p v-if="modeNotice" class="muted" style="margin-top: 8px">{{ modeNotice }}</p>
      <p v-if="siteMode.note" class="muted" style="margin-top: 8px; color: #8A5A2B">{{ siteMode.note }}</p>
      <p class="muted" style="margin-top: 8px">
        两种模式共用同一份前端产物，切换只是往入口页里插/删一行标记，不用重新部署。
        静态模式下项目、简历、页面文案都取自快照；下载、访客统计照常走接口。
        后台每次保存内容都会自动重建快照，所以不用担心「改了没反应」。
      </p>
    </div>

    <div class="tabs">
      <button type="button" :class="{ 'is-active': tab === 'visits' }" @click="switchTab('visits')">访客</button>
      <button type="button" :class="{ 'is-active': tab === 'api' }" @click="switchTab('api')">接口</button>
      <button type="button" :class="{ 'is-active': tab === 'resumes' }" @click="switchTab('resumes')">简历下载</button>
    </div>

    <div v-if="loading" class="skeleton" style="height: 260px"></div>

    <!-- 访客 -->
    <template v-else-if="tab === 'visits' && visits">
      <div class="card">
        <h2>访问趋势 <span class="muted">没数据的日子补 0，避免折线跳过空白</span></h2>
        <ChartBox :option="trendOption" :height="280" />
      </div>

      <div class="chart-row">
        <div class="card">
          <h2>24 小时分布</h2>
          <ChartBox :option="hourOption" :height="230" />
        </div>
        <div class="card">
          <h2>星期分布</h2>
          <ChartBox :option="weekdayOption" :height="230" />
        </div>
      </div>

      <div class="chart-row">
        <div class="card">
          <h2>地区分布 <span class="muted">离线 IP 库解析</span></h2>
          <ChartBox :option="provinceOption" :height="320" series-type="map" />
        </div>
        <div class="card">
          <h2>省份 TOP10</h2>
          <ChartBox :option="provinceRankOption" :height="320" />
        </div>
      </div>

      <div class="chart-row">
        <div class="card">
          <h2>浏览器</h2>
          <ChartBox :option="browserOption" :height="220" />
        </div>
        <div class="card">
          <h2>操作系统</h2>
          <ChartBox :option="osOption" :height="220" />
        </div>
      </div>

      <div class="chart-row">
        <div class="card">
          <h2>设备</h2>
          <ChartBox :option="deviceOption" :height="220" />
        </div>
        <div class="card">
          <h2>页面分布</h2>
          <ChartBox :option="pageOption" :height="220" />
        </div>
      </div>

      <div class="card">
        <h2>高频 IP <span class="muted">同一个 IP 反复来通常是爬虫或自己</span></h2>
        <table class="admin-table">
          <thead>
            <tr>
              <th>IP</th>
              <th style="width: 160px">归属地</th>
              <th style="width: 80px">PV</th>
              <th style="width: 80px">UV</th>
              <th style="width: 100px">看过几页</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in topIps" :key="row.ip">
              <td>{{ row.ip }}</td>
              <td class="muted">{{ row.region || '—' }}</td>
              <td class="num">{{ row.pv }}</td>
              <td class="num">{{ row.uv }}</td>
              <td class="num">{{ row.pages }}</td>
            </tr>
            <tr v-if="!topIps.length"><td colspan="5" class="muted">还没有数据</td></tr>
          </tbody>
        </table>
      </div>

      <div class="card">
        <h2>访问明细 <span class="muted">最新 50 条</span></h2>
        <table class="admin-table">
          <thead>
            <tr>
              <th style="width: 150px">时间</th>
              <th>路径</th>
              <th style="width: 90px">页面</th>
              <th style="width: 150px">IP</th>
              <th style="width: 130px">终端</th>
              <th style="width: 140px">来源</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in recentVisits" :key="row.id">
              <td class="muted nowrap">{{ (row.createdAt || '').replace('T', ' ').slice(5, 19) }}</td>
              <td>{{ row.path }}</td>
              <td class="muted">{{ row.pageType }}</td>
              <td class="muted">{{ row.ip }}<span v-if="row.province"> · {{ row.province }}</span></td>
              <td class="muted">{{ row.browser }} / {{ row.os }} / {{ row.device }}</td>
              <td class="muted">{{ row.referer ? row.referer.slice(0, 30) : '直接进入' }}</td>
            </tr>
            <tr v-if="!recentVisits.length"><td colspan="6" class="muted">还没有数据</td></tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- 接口 -->
    <template v-else-if="tab === 'api' && apiStats">
      <div class="card">
        <h2>
          调用量排行
          <span class="muted">默认只看外部请求，不把你自己在后台的操作算进去</span>
        </h2>
        <table class="admin-table">
          <thead>
            <tr>
              <th>接口</th>
              <th style="width: 70px">方法</th>
              <th style="width: 90px">调用次数</th>
              <th style="width: 80px">失败</th>
              <th style="width: 90px">平均耗时</th>
              <th style="width: 90px">最慢</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in apiStats.top" :key="row.path + row.method">
              <td>{{ row.path }}</td>
              <td class="muted">{{ row.method }}</td>
              <td class="num">{{ row.count }}</td>
              <td class="num" :style="row.fail ? 'color: #B4453A' : ''">{{ row.fail }}</td>
              <td class="num">{{ row.avgMs }} ms</td>
              <td class="num muted">{{ row.maxMs }} ms</td>
            </tr>
            <tr v-if="!apiStats.top.length"><td colspan="6" class="muted">还没有数据</td></tr>
          </tbody>
        </table>
      </div>

      <div class="chart-row">
        <div class="card">
          <h2>调用趋势</h2>
          <ChartBox :option="apiTrendOption" :height="240" />
        </div>
        <div class="card">
          <h2>慢接口 <span class="muted">样本少于 5 次的不参与排名</span></h2>
          <table class="admin-table">
            <thead>
              <tr>
                <th>接口</th>
                <th style="width: 90px">平均</th>
                <th style="width: 90px">最慢</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in apiStats.slow" :key="row.path + row.method">
                <td>{{ row.path }}<span class="muted"> · {{ row.method }}</span></td>
                <td class="num">{{ row.avgMs }} ms</td>
                <td class="num muted">{{ row.maxMs }} ms</td>
              </tr>
              <tr v-if="!apiStats.slow.length"><td colspan="3" class="muted">没有够样本量的慢接口</td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="card">
        <h2>登录接口 <span class="muted">失败数持续不为 0 就是有人在试密钥</span></h2>
        <div class="chart-row">
          <ChartBox :option="loginTrendOption" :height="220" />
          <ChartBox :option="loginHourOption" :height="220" />
        </div>
        <table class="admin-table" style="margin-top: 12px">
          <thead>
            <tr>
              <th>失败来源 IP</th>
              <th style="width: 170px">归属地</th>
              <th style="width: 90px">次数</th>
              <th style="width: 170px">最近一次</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in apiStats.login.failByIp" :key="row.ip">
              <td>{{ row.ip }}</td>
              <td class="muted">{{ row.region || '—' }}</td>
              <td class="num">{{ row.count }}</td>
              <td class="muted nowrap">{{ row.lastAt }}</td>
            </tr>
            <tr v-if="!apiStats.login.failByIp.length">
              <td colspan="4" class="muted">没有登录失败记录</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="card">
        <h2>下载接口 <span class="muted">业务码分布</span></h2>
        <table class="admin-table">
          <thead>
            <tr>
              <th>结果</th>
              <th style="width: 90px">业务码</th>
              <th style="width: 90px">次数</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in apiStats.download.byCode" :key="row.code">
              <td>{{ row.label }}</td>
              <td class="muted">{{ row.code }}</td>
              <td class="num">{{ row.count }}</td>
            </tr>
            <tr v-if="!apiStats.download.byCode.length"><td colspan="3" class="muted">还没有下载请求</td></tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- 简历下载 -->
    <template v-else-if="tab === 'resumes' && resumeStats">
      <div class="chart-row">
        <div class="card">
          <h2>下载趋势</h2>
          <ChartBox :option="resumeTrendOption" :height="250" />
        </div>
        <div class="card">
          <h2>每版下载量</h2>
          <ChartBox :option="resumeRankOption" :height="250" />
        </div>
      </div>

      <div class="card">
        <h2>按简历统计</h2>
        <table class="admin-table">
          <thead>
            <tr>
              <th>简历</th>
              <th style="width: 150px">方向</th>
              <th style="width: 90px">下载次数</th>
              <th style="width: 90px">下载人数</th>
              <th style="width: 100px">免验证</th>
              <th style="width: 110px">过验证码</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in resumeStats.byResume" :key="row.resumeId">
              <td>{{ row.title || `#${row.resumeId}` }}</td>
              <td class="muted">{{ row.direction || '—' }}</td>
              <td class="num">{{ row.count }}</td>
              <td class="num">{{ row.uv }}</td>
              <td class="num">{{ row.freeCount }}</td>
              <td class="num">{{ row.captchaCount }}</td>
            </tr>
            <tr v-if="!resumeStats.byResume.length"><td colspan="6" class="muted">还没有下载记录</td></tr>
          </tbody>
        </table>
      </div>

      <div class="card">
        <h2>下载明细 <span class="muted">最新 50 条</span></h2>
        <table class="admin-table">
          <thead>
            <tr>
              <th style="width: 150px">时间</th>
              <th>简历</th>
              <th style="width: 150px">IP</th>
              <th style="width: 160px">归属地</th>
              <th style="width: 110px">放行方式</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in downloadRows" :key="i">
              <td class="muted nowrap">{{ row.at }}</td>
              <td>{{ row.title }}</td>
              <td class="muted">{{ row.ip }}</td>
              <td class="muted">{{ row.region || '—' }}</td>
              <td>
                <span class="pill">{{ row.freePass ? '免验证额度' : '过验证码' }}</span>
              </td>
            </tr>
            <tr v-if="!downloadRows.length"><td colspan="5" class="muted">还没有下载记录</td></tr>
          </tbody>
        </table>
      </div>

      <div class="card">
        <h2>下载来源省份</h2>
        <table class="admin-table">
          <thead>
            <tr><th>省份</th><th style="width: 120px">下载次数</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in resumeStats.province" :key="row.name">
              <td>{{ row.name }}</td>
              <td class="num">{{ row.count }}</td>
            </tr>
            <tr v-if="!resumeStats.province.length"><td colspan="2" class="muted">还没有数据</td></tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>
