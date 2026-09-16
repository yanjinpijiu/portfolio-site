<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

/**
 * ECharts 图表容器。
 *
 * 两点刻意的设计：
 *
 * 1. **按需异步加载**。echarts 只在后台用到，绝不能进公开页面的包里。
 *    这里用 `import()` 动态引入，Rollup 会把它切成单独的 chunk，只有真的打开看板才会下载；
 *    同时只引需要的图表类型和组件，比整包小一半多。
 * 2. **实例跟着容器走**。数据一更新就 setOption（不重建实例），
 *    容器尺寸变化用 ResizeObserver 兜住——看板的栅格在窄屏会变成单列。
 *
 * 地图用的 GeoJSON（582KB）同样只在真正画地图时才去取。
 */
const props = defineProps({
  option: { type: Object, default: null },
  height: { type: Number, default: 260 },
  /** 直接给 echarts 的第一个参数，用来指定注册过的地图等 */
  seriesType: { type: String, default: '' }
})

const el = ref(null)
let chart = null
let echartsLib = null
let ro = null

/** 动态加载 echarts 与地图数据，只做一次 */
async function ensureLib() {
  if (echartsLib) return echartsLib
  const [core, charts, components, renderers] = await Promise.all([
    import('echarts/core'),
    import('echarts/charts'),
    import('echarts/components'),
    import('echarts/renderers')
  ])
  core.use([
    charts.LineChart,
    charts.BarChart,
    charts.PieChart,
    charts.MapChart,
    components.GridComponent,
    components.TooltipComponent,
    components.LegendComponent,
    components.VisualMapComponent,
    components.TitleComponent,
    renderers.CanvasRenderer
  ])
  // 中国地图：前台永远用不到，所以放在这个懒加载块里
  if (props.seriesType === 'map') {
    const geo = await import('../../assets/china-geo.json')
    core.registerMap('china', geo.default || geo)
  }
  echartsLib = core
  return echartsLib
}

async function render() {
  if (!el.value || !props.option) return
  const lib = await ensureLib()
  if (!chart) {
    chart = lib.init(el.value)
  }
  // notMerge = true：换了指标之后旧的 series 不能残留
  chart.setOption(props.option, true)
}

onMounted(async () => {
  await render()
  if (el.value && typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(() => chart?.resize())
    ro.observe(el.value)
  }
})

watch(() => props.option, render, { deep: true })

onBeforeUnmount(() => {
  ro?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div ref="el" class="chart" :style="{ height: `${height}px` }"></div>
</template>

<style scoped>
.chart { width: 100%; }
</style>
