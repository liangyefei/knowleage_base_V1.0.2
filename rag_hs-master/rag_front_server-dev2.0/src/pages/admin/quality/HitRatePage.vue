<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart } from 'echarts/charts'
import {
  TitleComponent, TooltipComponent, LegendComponent, GridComponent,
} from 'echarts/components'
import {
  Target, TrendingUp, TrendingDown, BarChart3, PieChart as PieChartIcon,
  Search, XCircle, AlertCircle, Download, X, Loader2, Calendar, MessageSquare, ArrowLeft,
} from 'lucide-vue-next'
import { useI18n } from '@/composables/useI18n'
import { useSearchLenLimit } from '@/composables/useSearchLenLimit'
import { cn } from '@/lib/utils'
import { toast } from '@/lib/toast'
import { mdDate, rangeDays } from '@/lib/trend-aggregate'
import { cancelPendingToast } from '@/services/api-client'
import { getKbOptions, type KbOption } from '@/services/knowledge.service'
import {
  getHitRateSummary, getHitRateTrend, getHitRateByChip,
  getHitRateMisses, exportHitRateMisses,
  type HitRateSummary, type HitRateTrendPoint, type ChipHitRate,
  type MissRecord,
} from '@/services/hitrate.service'
import Pagination from '@/components/ui/Pagination.vue'

use([CanvasRenderer, LineChart, BarChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent])

const route = useRoute()
const { t } = useI18n()

const CHART_COLORS = ['#3B82F6', '#10B981', '#F59E0B', '#8B5CF6', '#EC4899', '#06B6D4']

// ===== 时间范围工具 =====
function todayStr(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
function offsetDate(base: string, days: number): string {
  const d = new Date(base)
  d.setDate(d.getDate() + days)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

// ===== State =====
const timeRange = ref<'day' | 'week' | 'month' | 'custom'>('day')
const customStart = ref('')
const customEnd = ref('')
const dateInvalid = ref(false)
// 日期校验失败原因：true 表示「超过 3 个月」，false 表示「开始 > 结束」，用于内联提示文案区分
const dateRangeTooLong = ref(false)
const chipFilter = ref('all')
const kbOptions = ref<KbOption[]>([])

const timeTabs = [
  { value: 'day' as const, label: t('hitrate.byDay') },
  { value: 'week' as const, label: t('hitrate.byWeek') },
  { value: 'month' as const, label: t('hitrate.byMonth') },
  { value: 'custom' as const, label: t('hitrate.custom') },
]

const validateDateRange = () => {
  dateRangeTooLong.value = false
  if (customStart.value && customEnd.value) {
    dateInvalid.value = customStart.value > customEnd.value
    if (!dateInvalid.value) {
      // 自定义跨度最长 90 天（包含首尾共 90 个自然日）
      const totalDays = Math.floor(
        (new Date(customEnd.value).getTime() - new Date(customStart.value).getTime()) / (1000 * 60 * 60 * 24)
      ) + 1
      if (totalDays > 90) {
        dateInvalid.value = true
        dateRangeTooLong.value = true
        toast.info(t('hitrate.rangeMaxHint'))
      }
    }
  } else {
    dateInvalid.value = false
  }
}

// 日 / 周 / 月 各档位的默认查询区间（闭区间）
// 周：本周最后一天（周日）往前共 4 周（28 天）；月：近 3 个自然月（前 2 个月第一天 ~ 当前月最后一天）
function presetRange(range: 'day' | 'week' | 'month'): { from: string; to: string } {
  const today = todayStr()
  if (range === 'week') {
    const day = new Date().getDay()
    const sunday = offsetDate(today, day === 0 ? 0 : 7 - day)
    return { from: offsetDate(sunday, -27), to: sunday }
  }
  if (range === 'month') {
    const d = new Date()
    const fromDate = new Date(d.getFullYear(), d.getMonth() - 2, 1)
    const from = `${fromDate.getFullYear()}-${String(fromDate.getMonth() + 1).padStart(2, '0')}-01`
    const toDate = new Date(d.getFullYear(), d.getMonth() + 1, 0)
    const to = `${toDate.getFullYear()}-${String(toDate.getMonth() + 1).padStart(2, '0')}-${String(toDate.getDate()).padStart(2, '0')}`
    return { from, to }
  }
  return { from: today, to: today }
}

// computed from/to（接口 to 为闭区间结束日，与未命中列表 missTo 取当天一致）
const queryFrom = computed(() =>
  timeRange.value === 'custom' ? (customStart.value || todayStr()) : presetRange(timeRange.value).from
)
const queryTo = computed(() =>
  timeRange.value === 'custom' ? (customEnd.value || todayStr()) : presetRange(timeRange.value).to
)
const queryKbId = computed(() => chipFilter.value !== 'all' ? chipFilter.value : '')

// ===== 概览四卡 =====
const summary = ref<HitRateSummary | null>(null)
const summaryLoading = ref(false)

async function loadSummary() {
  summaryLoading.value = true
  try {
    summary.value = await getHitRateSummary({
      from: queryFrom.value,
      to: queryTo.value,
      kbId: queryKbId.value || undefined,
    })
  } catch (err) {
    cancelPendingToast()
    toast.error(err instanceof Error ? err.message : t('hitrate.loadFailed'))
    summary.value = null
  } finally {
    summaryLoading.value = false
  }
}

const overallRate = computed(() => summary.value ? (summary.value.overallRate * 100).toFixed(1) : '-')
const totalQueries = computed(() => summary.value?.totalQueries?.toLocaleString() ?? '-')
const totalHits = computed(() => summary.value?.totalHits?.toLocaleString() ?? '-')
const highestChip = computed(() => summary.value?.max ?? null)
const lowestChip = computed(() => summary.value?.min ?? null)
const rateChange = computed(() => {
  if (!summary.value || !summary.value.prevRate) return null
  return summary.value.overallRate - summary.value.prevRate
})

// ===== 时间趋势图 =====
const trendData = ref<HitRateTrendPoint[]>([])
const trendLoading = ref(false)

async function loadTrend() {
  trendLoading.value = true
  try {
    trendData.value = await getHitRateTrend({
      // 自定义：按区间天数自动选粒度（N≤30 日 / N>30 周），聚合在后端完成
      granularity: timeRange.value === 'custom' ? customGranularity.value : timeRange.value,
      from: queryFrom.value,
      to: queryTo.value,
      kbId: queryKbId.value || undefined,
    })
  } catch (err) {
    cancelPendingToast()
    toast.error(err instanceof Error ? err.message : t('hitrate.loadFailed'))
    trendData.value = []
  } finally {
    trendLoading.value = false
  }
}

const trendOption = computed(() => {
  const data = trendData.value
  if (!data.length) return {}
  return {
    tooltip: {
      trigger: 'axis' as const,
      formatter: (params: any) => {
        const list = Array.isArray(params) ? params : [params]
        return list.map((p: any) => {
          const val = p.seriesName === t('sidebar.hitrate')
            ? `${p.value}%`
            : p.value
          return `${p.name}: ${val}`
        }).join('<br/>')
      },
    },
    legend: { bottom: 0 },
    grid: { left: '5%', right: '5%', top: 40, bottom: '15%', containLabel: true },
    xAxis: { type: 'category', data: data.map(d => d.date), boundaryGap: true },
    yAxis: [
      {
        type: 'value', name: t('hitrate.leftAxis'), nameLocation: 'end', nameGap: 8,
        min: 0, max: 100, position: 'left',
      },
      {
        type: 'value', name: t('hitrate.rightAxis'), nameLocation: 'end', nameGap: 8,
        position: 'right',
      },
    ],
    series: [
      {
        name: t('sidebar.hitrate'), type: 'line', yAxisIndex: 0,
        data: data.map(d => Math.round(d.hitRate * 100 * 10) / 10),
        smooth: true, lineStyle: { width: 2, color: CHART_COLORS[0] },
        itemStyle: { color: CHART_COLORS[0] },
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(59, 130, 246, 0.3)' },
              { offset: 1, color: 'rgba(59, 130, 246, 0.02)' },
            ],
          },
        },
      },
      {
        name: t('hitrate.rightAxis'), type: 'bar', yAxisIndex: 1,
        data: data.map(d => d.queryCount),
        barWidth: '40%', barMaxWidth: 40, itemStyle: { color: CHART_COLORS[1], borderRadius: [4, 4, 0, 0] },
      },
    ],
  }
})

// ===== 自定义模式趋势：按区间天数自动聚合（日/自然周）+ 周→日下钻 =====
// 下钻栈：空 = 顶层视图；下钻时锁定该周起止时间
const trendDrillStack = ref<{ from: string; to: string }[]>([])
const drillData = ref<HitRateTrendPoint[]>([])
const drillLoading = ref(false)

const trendDrilled = computed(() => trendDrillStack.value.length > 0)
// 自动粒度：区间包含首尾天数 N ≤ 30 → 日；30 < N ≤ 90 → 自然周
const customGranularity = computed<'day' | 'week'>(() =>
  rangeDays(queryFrom.value, queryTo.value) <= 30 ? 'day' : 'week'
)

// 图表数据源：顶层周视图 X 轴用后端 date 文案（如 2026年41周）；下钻/日视图用 M.DD
const customView = computed<HitRateTrendPoint[]>(() =>
  trendDrilled.value ? drillData.value : trendData.value
)
const customLabels = computed(() =>
  customView.value.map(p =>
    trendDrilled.value || customGranularity.value === 'day'
      ? mdDate(p.bucketStart || p.date)
      : p.date
  )
)

const customTrendSubtitle = computed(() => {
  if (trendDrilled.value) {
    const frame = trendDrillStack.value[trendDrillStack.value.length - 1]
    return `${mdDate(frame.from)} - ${mdDate(frame.to)} · ${t('hitrate.trendAggDrill')}`
  }
  return customGranularity.value === 'week' ? t('hitrate.trendAggWeek') : t('hitrate.trendAggDay')
})

// 与日/周/月档位 trendOption 风格统一：命中率折线（蓝）+ 查询次数柱（绿）双 Y 轴；仅自定义支持柱子下钻
const customTrendOption = computed(() => {
  const data = customView.value
  if (!data.length) return {}
  const rotated = !trendDrilled.value && customGranularity.value === 'week'
  return {
    tooltip: {
      trigger: 'axis' as const,
      formatter: (params: any) => {
        const list = Array.isArray(params) ? params : [params]
        return list.map((p: any) => {
          const val = p.seriesName === t('sidebar.hitrate')
            ? `${p.value}%`
            : p.value
          return `${p.name}: ${val}`
        }).join('<br/>')
      },
    },
    legend: { bottom: 0 },
    grid: { left: '5%', right: '5%', top: 40, bottom: '15%', containLabel: true },
    xAxis: {
      type: 'category', data: customLabels.value, boundaryGap: true,
      axisLabel: { rotate: rotated ? 30 : 0, fontSize: 11, interval: 0 },
    },
    yAxis: [
      {
        type: 'value', name: t('hitrate.leftAxis'), nameLocation: 'end', nameGap: 8,
        min: 0, max: 100, position: 'left',
      },
      {
        type: 'value', name: t('hitrate.rightAxis'), nameLocation: 'end', nameGap: 8,
        position: 'right',
      },
    ],
    series: [
      {
        name: t('sidebar.hitrate'), type: 'line', yAxisIndex: 0,
        data: data.map(d => Math.round(d.hitRate * 100 * 10) / 10),
        smooth: true, lineStyle: { width: 2, color: CHART_COLORS[0] },
        itemStyle: { color: CHART_COLORS[0] },
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(59, 130, 246, 0.3)' },
              { offset: 1, color: 'rgba(59, 130, 246, 0.02)' },
            ],
          },
        },
        universalTransition: { enabled: true, divideShape: 'clone' },
      },
      {
        // 查询次数柱：仅顶层周粒度可点击下钻（见 handleCustomTrendZrClick）
        name: t('hitrate.rightAxis'), type: 'bar', yAxisIndex: 1,
        data: data.map(d => d.queryCount),
        barWidth: '40%', barMaxWidth: 40, itemStyle: { color: CHART_COLORS[1], borderRadius: [4, 4, 0, 0] },
        universalTransition: { enabled: true, divideShape: 'clone' },
      },
    ],
  }
})

// 下钻：bucketStart 即该周起始，锁定 [bucketStart, bucketStart+6] 与查询区间求交，granularity=day 请求每日明细
async function drillToWeek(p: HitRateTrendPoint) {
  const start = p.bucketStart || p.date
  const rawTo = offsetDate(start, 6)
  const from = start < queryFrom.value ? queryFrom.value : start
  const to = rawTo > queryTo.value ? queryTo.value : rawTo
  trendDrillStack.value.push({ from, to })
  drillLoading.value = true
  try {
    drillData.value = await getHitRateTrend({
      granularity: 'day',
      from,
      to,
      kbId: queryKbId.value || undefined,
    })
  } catch (err) {
    cancelPendingToast()
    toast.error(err instanceof Error ? err.message : t('hitrate.loadFailed'))
    drillData.value = []
  } finally {
    drillLoading.value = false
  }
}

// 图表实例引用：用于把点击像素坐标换算成类目索引
const customTrendChart = ref<any>(null)

// 仅顶层周粒度可下钻；点击绘图区任意位置，按列带宽映射到最近的一周（避免矮柱难点中）
function handleCustomTrendZrClick(params: any) {
  if (trendDrilled.value || customGranularity.value !== 'week') return
  const chart = customTrendChart.value
  if (!chart || params?.offsetX == null || params?.offsetY == null) return
  // 限制在绘图区内，点击图例/坐标轴文字不触发
  if (!chart.containPixel('grid', [params.offsetX, params.offsetY])) return
  const point = chart.convertFromPixel({ xAxisIndex: 0, yAxisIndex: 0 }, [params.offsetX, params.offsetY])
  const index = Math.round(point?.[0])
  const p = customView.value[index]
  if (!p) return
  void drillToWeek(p)
}

// 返回：回到上一级周粒度视图（顶层周数据仍在 trendData，无需重复请求）
function goBackTrend() {
  trendDrillStack.value.pop()
  drillData.value = []
}

// 自定义日期 / 维度 / 知识库切换 → 清空下钻，重置为顶层自动聚合视图（loadAll 内重载）
watch([timeRange, customStart, customEnd, chipFilter], () => {
  trendDrillStack.value = []
  drillData.value = []
})

// ===== 芯片维度图 =====
const chipData = ref<ChipHitRate[]>([])
const chipLoading = ref(false)

async function loadChip() {
  chipLoading.value = true
  try {
    chipData.value = await getHitRateByChip({
      from: queryFrom.value,
      to: queryTo.value,
      kbId: queryKbId.value || undefined,
    })
  } catch (err) {
    cancelPendingToast()
    toast.error(err instanceof Error ? err.message : t('hitrate.loadFailed'))
    chipData.value = []
  } finally {
    chipLoading.value = false
  }
}

const chipOption = computed(() => {
  const data = chipData.value
  if (!data.length) return {}
  return {
    tooltip: {
      trigger: 'axis' as const,
      formatter: (params: any) => {
        const list = Array.isArray(params) ? params : [params]
        return list.map((p: any) =>
          `<div class="mb-1"><span class="font-medium">${p.name}</span></div>` +
          `<div>${t('sidebar.hitrate')}: ${p.value}%</div>` +
          `<div>${t('hitrate.hitCount')}: ${p.data?.hit ?? '-'}</div>` +
          `<div>${t('hitrate.totalCount')}: ${p.data?.total ?? '-'}</div>`
        ).join('')
      },
    },
    grid: { left: '3%', right: '8%', bottom: '3%', containLabel: true },
    xAxis: { type: 'value', min: 0, max: 100 },
    yAxis: { type: 'category', data: data.map(d => d.kbName) },
    series: [{
      type: 'bar',
      data: data.map((d, i) => ({
        value: Math.round(d.hitRate * 100 * 10) / 10,
        hit: d.hit,
        total: d.total,
        itemStyle: { color: CHART_COLORS[i % CHART_COLORS.length], borderRadius: [0, 4, 4, 0] },
      })),
      barWidth: '60%',
      label: { show: true, position: 'right', formatter: (p: any) => `${p.value}%` },
    }],
  }
})

// ===== 未命中列表 =====
const missSearch = ref('')
useSearchLenLimit(missSearch, () => t('common.searchMaxLen', { max: 100 }))
const missSourceFilter = ref('')
const missFrom = ref(todayStr())
const missTo = ref(todayStr())
const missPage = ref(1)
const missPageSize = ref(10)
const missRecords = ref<MissRecord[]>([])
const missTotalRow = ref(0)
const missTotalPage = ref(1)
const missLoading = ref(false)
const missDetailId = ref<string | null>(null)

const SOURCE_OPTIONS = [
  { value: '', label: t('hitrate.allSource') },
  { value: 'CHAT_QA', label: t('hitrate.source.qa') },
  { value: 'RECEPTION_BOT', label: t('hitrate.source.frontend') },
  { value: 'AGENT_CALL', label: t('hitrate.source.agent') },
]

async function loadMisses() {
  // date input 手动输入非法值（如 0）时 v-model 会得到空串，此时不发请求，等日期补全后再查
  if (!missFrom.value || !missTo.value) return
  missLoading.value = true
  try {
    const result = await getHitRateMisses({
      from: missFrom.value,
      to: missTo.value,
      keyword: missSearch.value || undefined,
      source: missSourceFilter.value || undefined,
      page: missPage.value,
      pageSize: missPageSize.value,
    })
    missRecords.value = result.records
    missTotalRow.value = result.totalRow
    missTotalPage.value = result.totalPage
  } catch (err) {
    cancelPendingToast()
    toast.error(err instanceof Error ? err.message : t('hitrate.loadFailed'))
    missRecords.value = []
    missTotalRow.value = 0
  } finally {
    missLoading.value = false
  }
}

async function handleMissExport() {
  if (!missFrom.value || !missTo.value) {
    toast.info(t('hitrate.missDateRequired'))
    return
  }
  try {
    const { blob, fileName } = await exportHitRateMisses({
      from: missFrom.value,
      to: missTo.value,
      keyword: missSearch.value || undefined,
      source: missSourceFilter.value || undefined,
    })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = fileName || `未命中列表_${todayStr()}.csv`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
    toast.success(t('hitrate.toast.download', { count: missTotalRow.value }))
  } catch (err) {
    cancelPendingToast()
    toast.error(err instanceof Error ? err.message : t('hitrate.exportFailed'))
  }
}

function handleMissSearch() {
  missPage.value = 1
  loadMisses()
}

watch(missSourceFilter, () => {
  missPage.value = 1
  loadMisses()
})

const sourceText = (source: string) => {
  if (source === 'CHAT_QA') return t('hitrate.source.qa')
  if (source === 'RECEPTION_BOT') return t('hitrate.source.frontend')
  if (source === 'AGENT_CALL') return t('hitrate.source.agent')
  if (source === 'PENDING') return t('hitrate.status.pending')
  if (source === 'PROCESSING') return t('hitrate.status.processing')
  if (source === 'RESOLVED') return t('hitrate.status.resolved')
  return source
}
const sourceVariant = (source: string) => {
  if (source === 'CHAT_QA') return 'bg-primary/10 text-primary'
  if (source === 'RECEPTION_BOT') return 'bg-purple-500/10 text-purple-400'
  if (source === 'AGENT_CALL') return 'bg-cyan-500/10 text-cyan-400'
  if (source === 'PENDING') return 'bg-red-500/10 text-red-500'
  if (source === 'PROCESSING') return 'bg-amber-500/10 text-amber-500'
  if (source === 'RESOLVED') return 'bg-emerald-500/10 text-emerald-500'
  return 'bg-muted text-muted-foreground'
}

const missDetailRecord = computed(() =>
  missRecords.value.find(r => r.messageId === missDetailId.value) ?? null
)

// ===== URL 初始化 + 全量加载 =====
async function loadKbOptions() {
  try {
    kbOptions.value = await getKbOptions()
  } catch { /* ignore */ }
}

function loadAll() {
  // 自定义区间非法（开始 > 结束 / 跨度超 90 天）→ 不请求接口，清空数据展示占位
  if (timeRange.value === 'custom' && dateInvalid.value) {
    summary.value = null
    trendData.value = []
    chipData.value = []
    return
  }
  loadSummary()
  loadTrend()
  loadChip()
}

watch(
  () => route.query,
  (q) => {
    const chip = q.chip
    const range = q.range
    if (chip && typeof chip === 'string') chipFilter.value = chip
    if (range && typeof range === 'string') timeRange.value = range as any
  },
  { immediate: true },
)

// 切到「自定义」时，把当前生效的查询区间回填到日期控件
watch(
  timeRange,
  (range, prevRange) => {
    if (range !== 'custom') return
    const base = prevRange && prevRange !== 'custom' ? prevRange : 'day'
    const { from, to } = presetRange(base)
    customStart.value = from
    customEnd.value = to
  },
  { immediate: true },
)

// 时间维度 / 日期区间 / 芯片切换 → 仅重载上部概览+趋势（不到未命中列表）
// timeRange 必须在依赖里：日 → 自定义 区间相同时 queryFrom/queryTo 不变，仅靠日期 watch 不会触发
watch([timeRange, queryFrom, queryTo, queryKbId], () => {
  loadAll()
})

// miss 列表翻页
watch(missPage, () => { loadMisses() })

// miss 列表独立时间变化 → 重载未命中列表
watch([missFrom, missTo], () => {
  missPage.value = 1
  loadMisses()
})

onMounted(async () => {
  await loadKbOptions()
  loadAll()
  loadMisses()
})
</script>

<template>
  <div class="space-y-6">
    <!-- Page header -->
    <div>
      <h1 class="text-2xl font-bold tracking-tight">{{ t('hitrate.title') }}</h1>
      <p class="text-sm text-muted-foreground mt-1">{{ t('hitrate.subtitle') }}</p>
    </div>

    <!-- Filter bar -->
    <div class="flex items-center gap-2">
      <span class="text-xs font-medium text-muted-foreground shrink-0">{{ t('hitrate.overviewSection') }}</span>
      <div class="h-4 w-px bg-border" />
    </div>
    <div class="flex items-center justify-between">
      <div class="flex items-center gap-2 flex-wrap">
        <div class="inline-flex rounded-md border border-border">
          <button
            v-for="tab in timeTabs"
            :key="tab.value"
            @click="timeRange = tab.value"
            :class="[
              'px-3 h-8 text-sm transition-colors',
              timeRange === tab.value
                ? 'bg-primary text-primary-foreground'
                : 'hover:bg-accent text-muted-foreground',
            ]"
          >
            {{ tab.label }}
          </button>
        </div>
        <template v-if="timeRange === 'custom'">
          <input
            type="date" v-model="customStart" @change="validateDateRange"
            :class="[
              'h-8 rounded-md border bg-background px-3 text-sm outline-none',
              dateInvalid ? 'border-destructive focus:border-destructive' : 'border-border focus:border-ring',
            ]"
          />
          <span class="text-sm text-muted-foreground">—</span>
          <input
            type="date" v-model="customEnd" @change="validateDateRange"
            :class="[
              'h-8 rounded-md border bg-background px-3 text-sm outline-none',
              dateInvalid ? 'border-destructive focus:border-destructive' : 'border-border focus:border-ring',
            ]"
          />
          <span v-if="dateInvalid" class="text-xs text-destructive">{{ t(dateRangeTooLong ? 'hitrate.rangeMaxHint' : 'hitrate.dateInvalid') }}</span>
        </template>
        <select
          v-model="chipFilter"
          class="h-8 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring"
        >
          <option value="all">{{ t('hitrate.allKb') }}</option>
          <option v-for="kb in kbOptions" :key="kb.kbId" :value="kb.kbId">{{ kb.kbName }}</option>
        </select>
      </div>
    </div>

    <!-- KPI cards -->
    <div class="grid grid-cols-2 md:grid-cols-4 gap-4">
      <div class="rounded-lg border border-border bg-card">
        <div class="p-4">
          <div class="flex items-center justify-between">
            <div>
              <p class="text-sm text-muted-foreground">{{ t('hitrate.overall') }}</p>
              <p class="text-2xl font-bold mt-1 tabular-nums">
                <Loader2 v-if="summaryLoading" class="size-5 animate-spin inline" />
                <template v-else>{{ overallRate }}%</template>
              </p>
              <p v-if="rateChange != null" class="text-xs flex items-center gap-1 mt-1"
                :class="rateChange >= 0 ? 'text-emerald-500' : 'text-red-500'">
                <TrendingUp v-if="rateChange >= 0" class="size-3" />
                <TrendingDown v-else class="size-3" />
                {{ rateChange >= 0 ? '+' : '' }}{{ (rateChange * 100).toFixed(1) }}% {{ t('hitrate.mom') }}
              </p>
            </div>
            <div class="size-10 rounded-lg bg-primary/10 flex items-center justify-center">
              <Target class="size-5 text-primary" />
            </div>
          </div>
        </div>
      </div>

      <div class="rounded-lg border border-border bg-card">
        <div class="p-4">
          <div class="flex items-center justify-between">
            <div>
              <p class="text-sm text-muted-foreground">{{ t('hitrate.totalQueries') }}</p>
              <p class="text-2xl font-bold mt-1 tabular-nums">{{ totalQueries }}</p>
              <p class="text-xs text-muted-foreground mt-1">{{ t('hitrate.hitCount') }}: {{ totalHits }}</p>
            </div>
            <div class="size-10 rounded-lg bg-emerald-500/10 flex items-center justify-center">
              <BarChart3 class="size-5 text-emerald-500" />
            </div>
          </div>
        </div>
      </div>

      <div class="rounded-lg border border-border bg-card">
        <div class="p-4">
          <div class="flex items-center justify-between">
            <div>
              <p class="text-sm text-muted-foreground">{{ t('hitrate.highest') }}</p>
              <p class="text-2xl font-bold mt-1 tabular-nums">
                {{ highestChip ? (highestChip.value * 100).toFixed(1) + '%' : '-' }}
              </p>
              <p class="text-xs text-muted-foreground mt-1">{{ highestChip?.label || t('hitrate.noData') }}</p>
            </div>
            <div class="size-10 rounded-lg bg-purple-500/10 flex items-center justify-center">
              <PieChartIcon class="size-5 text-purple-500" />
            </div>
          </div>
        </div>
      </div>

      <div class="rounded-lg border border-border bg-card">
        <div class="p-4">
          <div class="flex items-center justify-between">
            <div>
              <p class="text-sm text-muted-foreground">{{ t('hitrate.lowest') }}</p>
              <p class="text-2xl font-bold mt-1 tabular-nums">
                {{ lowestChip ? (lowestChip.value * 100).toFixed(1) + '%' : '-' }}
              </p>
              <p class="text-xs text-muted-foreground mt-1">{{ lowestChip?.label || t('hitrate.noData') }}</p>
            </div>
            <div class="size-10 rounded-lg bg-red-500/10 flex items-center justify-center">
              <TrendingDown class="size-5 text-red-500" />
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Trend chart -->
    <div class="rounded-lg border border-border bg-card">
      <div class="p-5 pb-3 flex flex-row items-center justify-between">
        <div>
          <h3 class="text-base font-semibold">{{ t('hitrate.trend') }}</h3>
          <p class="text-sm text-muted-foreground mt-1">
            {{ timeRange === 'custom' ? customTrendSubtitle : t('hitrate.trendDesc') }}
          </p>
        </div>
        <!-- 下钻到日粒度后的返回按钮：回到上一级周粒度视图 -->
        <button
          v-if="timeRange === 'custom' && trendDrilled"
          class="h-8 px-3 rounded-md border border-border bg-background hover:bg-accent text-sm flex items-center gap-1 transition-colors shrink-0"
          @click="goBackTrend"
        >
          <ArrowLeft class="size-3.5" />{{ t('common.back') }}
        </button>
      </div>
      <div class="p-5 pt-0" style="height: 320px;">
        <div
          v-if="trendLoading || (timeRange === 'custom' && drillLoading)"
          class="h-full flex items-center justify-center text-muted-foreground text-sm"
        >
          <Loader2 class="size-5 animate-spin mr-2" />{{ t('hitrate.trendLoading') }}
        </div>
        <!-- 自定义模式：接口聚合柱状图（周/日）+ 周→日下钻 -->
        <template v-else-if="timeRange === 'custom'">
          <v-chart
            v-if="trendData.length && (!trendDrilled || drillData.length)"
            ref="customTrendChart"
            :option="customTrendOption" autoresize
            style="height: 100%; width: 100%;"
            @zr:click="handleCustomTrendZrClick"
          />
          <div v-else class="h-full flex items-center justify-center text-muted-foreground text-sm">{{ t('hitrate.noData') }}</div>
        </template>
        <v-chart v-else-if="trendData.length" :option="trendOption" autoresize style="height: 100%; width: 100%;" />
        <div v-else class="h-full flex items-center justify-center text-muted-foreground text-sm">{{ t('hitrate.noData') }}</div>
      </div>
    </div>

    <!-- Chip comparison -->
    <div class="rounded-lg border border-border bg-card">
      <div class="p-5 pb-3 flex flex-row items-center justify-between">
        <div>
          <h3 class="text-base font-semibold">{{ t('hitrate.byChip') }}</h3>
          <p class="text-sm text-muted-foreground mt-1">{{ t('hitrate.totalChip', { count: chipData.length }) }}</p>
        </div>
      </div>
      <div class="p-5 pt-0" style="height: 360px;">
        <div v-if="chipLoading" class="h-full flex items-center justify-center text-muted-foreground text-sm">
          <Loader2 class="size-5 animate-spin mr-2" />{{ t('hitrate.byKbLoading') }}
        </div>
        <v-chart v-else-if="chipData.length" :option="chipOption" autoresize style="height: 100%; width: 100%;" />
        <div v-else class="h-full flex items-center justify-center text-muted-foreground text-sm">{{ t('hitrate.noData') }}</div>
      </div>
    </div>

    <!-- Miss section divider -->
    <div class="flex items-center gap-2 pt-4">
      <span class="text-xs font-medium text-muted-foreground shrink-0">{{ t('hitrate.missSection') }}</span>
      <div class="flex-1 h-px bg-border" />
    </div>

    <!-- Miss list -->
    <div class="rounded-lg border border-border bg-card">
      <div class="p-5 pb-3 flex flex-row items-start justify-between gap-4 flex-wrap">
        <div>
          <h3 class="text-base font-semibold flex items-center gap-2">
            <AlertCircle class="size-5 text-amber-500" />
            {{ t('hitrate.missList') }}
            <span class="text-sm font-normal text-muted-foreground ml-1">
              {{ t('hitrate.missListCount', { count: missTotalRow }) }}
            </span>
          </h3>
          <p class="text-sm text-muted-foreground mt-1">{{ t('hitrate.missListDesc') }}</p>
        </div>
        <div class="flex items-center gap-2 flex-wrap">
          <div class="relative w-[180px]">
            <Search class="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
            <input
              v-model="missSearch" type="text" :placeholder="t('hitrate.searchProblem')"
              class="w-full h-8 rounded-md border border-border bg-background pl-8 pr-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
              @keydown.enter="handleMissSearch"
            />
          </div>
          <div class="flex items-center gap-1">
            <Calendar class="size-3.5 text-muted-foreground shrink-0" />
            <input type="date" v-model="missFrom"
              class="h-8 w-[130px] rounded-md border border-border bg-background px-2 text-xs outline-none focus:border-ring" />
            <span class="text-xs text-muted-foreground">—</span>
            <input type="date" v-model="missTo"
              class="h-8 w-[130px] rounded-md border border-border bg-background px-2 text-xs outline-none focus:border-ring" />
          </div>
          <select v-model="missSourceFilter"
            class="h-8 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring">
            <option v-for="opt in SOURCE_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
          </select>
          <button
            class="h-8 px-3 rounded-md border border-border bg-background hover:bg-accent text-sm flex items-center gap-1 transition-colors"
            @click="handleMissSearch">
            <Search class="size-3.5" />{{ t('common.search') }}
          </button>
          <button
            class="h-8 px-3 rounded-md border border-border bg-background hover:bg-accent text-sm flex items-center gap-1 transition-colors"
            @click="handleMissExport">
            <Download class="size-3.5" />{{ t('hitrate.export') }}
          </button>
        </div>
      </div>
      <div class="w-full overflow-x-auto">
        <table class="w-full text-sm">
          <thead>
            <tr class="border-b border-border">
              <th class="text-left px-4 py-3 font-medium text-muted-foreground whitespace-nowrap min-w-[200px]">{{ t('hitrate.questionContent') }}</th>
              <th class="text-left px-4 py-3 font-medium text-muted-foreground whitespace-nowrap min-w-[240px]">{{ t('hitrate.answerContent') }}</th>
              <th class="text-left px-4 py-3 font-medium text-muted-foreground whitespace-nowrap min-w-[120px]">{{ t('hitrate.selectedKb') }}</th>
              <th class="text-left px-4 py-3 font-medium text-muted-foreground whitespace-nowrap min-w-[120px]">{{ t('hitrate.questioner') }}</th>
              <th class="text-left px-4 py-3 font-medium text-muted-foreground whitespace-nowrap">{{ t('common.source') }}</th>
              <th class="text-left px-4 py-3 font-medium text-muted-foreground whitespace-nowrap">{{ t('hitrate.queryTime') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="missLoading">
              <td colspan="6" class="h-32 text-center text-muted-foreground">
                <Loader2 class="size-6 animate-spin inline" />
              </td>
            </tr>
            <tr v-else-if="missRecords.length === 0">
              <td colspan="6" class="h-32 text-center text-muted-foreground">
                <div class="flex flex-col items-center gap-2">
                  <XCircle class="size-8 opacity-30" />
                  <p class="text-sm">{{ t('hitrate.noMissRecords') }}</p>
                </div>
              </td>
            </tr>
            <tr
              v-for="item in missRecords" :key="item.messageId"
              class="border-b border-border hover:bg-accent/50 transition-colors"
            >
              <td class="px-4 py-3">
                <button
                  type="button"
                  class="block max-w-[320px] truncate text-sm text-left text-primary hover:underline cursor-pointer"
                  :title="item.query"
                  @click="missDetailId = item.messageId"
                >{{ item.query || '-' }}</button>
              </td>
              <td class="px-4 py-3">
                <button
                  type="button"
                  class="block max-w-[360px] truncate text-sm text-left text-primary hover:underline cursor-pointer"
                  :title="item.answerText"
                  @click="missDetailId = item.messageId"
                >{{ item.answerText || '-' }}</button>
              </td>
              <td class="px-4 py-3"><span class="block truncate max-w-[200px] text-sm">{{ item.kbName || '-' }}</span></td>
              <td class="px-4 py-3"><span class="block truncate max-w-[160px] text-sm">{{ item.account || '-' }}</span></td>
              <td class="px-4 py-3">
                <span :class="cn('inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-medium border', sourceVariant(item.source))">
                  {{ sourceText(item.source) }}
                </span>
              </td>
              <td class="px-4 py-3">
                <span class="text-sm text-muted-foreground whitespace-nowrap">{{ item.createdAt }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="missTotalRow > 0">
        <Pagination
          :current-page="missPage"
          :total-pages="missTotalPage"
          :total-items="missTotalRow"
          :page-size="missPageSize"
          @page-change="(p: number) => missPage = p"
        />
      </div>
    </div>

    <!-- 对话全文弹窗 -->
    <Transition name="fade">
      <div
        v-if="missDetailId"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
        @click.self="missDetailId = null"
      >
        <div class="bg-card border border-border rounded-2xl shadow-xl w-full max-w-2xl max-h-[85vh] flex flex-col">
          <!-- 头部 -->
          <div class="flex items-start justify-between px-6 pt-6 pb-2">
            <div class="flex items-center gap-2.5">
              <span class="size-9 rounded-lg bg-primary/10 flex items-center justify-center shrink-0">
                <MessageSquare class="size-5 text-primary" />
              </span>
              <h3 class="text-xl font-bold">{{ t('hitrate.dialogFullText') }}</h3>
            </div>
            <button
              class="size-8 rounded-md hover:bg-accent inline-flex items-center justify-center text-muted-foreground transition-colors shrink-0"
              @click="missDetailId = null">
              <X class="size-5" />
            </button>
          </div>
          <!-- 元信息：知识库 · 时间 -->
          <p class="px-6 pt-1 pb-2 text-sm text-muted-foreground">
            <span v-if="missDetailRecord?.kbName">{{ t('hitrate.dialogKb') }}：{{ missDetailRecord.kbName }}</span>
            <span v-if="missDetailRecord?.kbName && missDetailRecord?.createdAt" class="mx-1.5">·</span>
            <span v-if="missDetailRecord?.createdAt">{{ t('hitrate.dialogTime') }}：{{ missDetailRecord.createdAt }}</span>
          </p>
          <!-- 正文 -->
          <div class="px-6 py-3 space-y-6 overflow-y-auto">
            <template v-if="missDetailRecord">
              <!-- 提问 -->
              <div>
                <div class="flex items-center gap-2 mb-2">
                  <span class="size-6 rounded-full bg-blue-500/10 text-blue-600 text-xs font-bold flex items-center justify-center">Q</span>
                  <span class="text-sm font-semibold text-blue-700 dark:text-blue-400">{{ t('hitrate.dialogQuestion') }}</span>
                </div>
                <p class="pl-8 text-[15px] leading-7 whitespace-pre-wrap break-words">{{ missDetailRecord.query || '-' }}</p>
              </div>
              <!-- 回答 -->
              <div>
                <div class="flex items-center gap-2 mb-2">
                  <span class="size-6 rounded-full bg-emerald-500/10 text-emerald-600 text-xs font-bold flex items-center justify-center">A</span>
                  <span class="text-sm font-semibold text-emerald-700 dark:text-emerald-400">{{ t('hitrate.dialogAnswer') }}</span>
                </div>
                <p class="pl-8 text-[15px] leading-7 whitespace-pre-wrap break-words text-muted-foreground">{{ missDetailRecord.answerText || '-' }}</p>
              </div>
            </template>
          </div>
          <!-- 底部 -->
          <div class="px-6 py-4 flex justify-end">
            <button
              class="h-9 px-6 rounded-lg border border-border bg-background hover:bg-accent text-sm transition-colors"
              @click="missDetailId = null">
              {{ t('common.close') }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>