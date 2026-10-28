<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart, PieChart } from 'echarts/charts'
import {
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
} from 'echarts/components'
import {
  TrendingUp,
  TrendingDown,
  Bug,
  BookOpen,
  Code2,
  FileCog,
  Cpu,
  HelpCircle,
  ArrowUpRight,
  Search,
  X,
} from 'lucide-vue-next'
import {
  MOCK_DASHBOARD_KPIS,
  MOCK_DASHBOARD_CATEGORIES,
  MOCK_DASHBOARD_BADCASE_STATS,
  MOCK_DASHBOARD_QUICK_ENTRIES,
} from '@/data/dashboard'
import { MOCK_HIT_RATE_TREND } from '@/data/charts'
import { useI18n } from '@/composables/useI18n'

use([CanvasRenderer, LineChart, BarChart, PieChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent])

const router = useRouter()
const { t } = useI18n()
const selectedCategory = ref('all')

const iconMap: Record<string, any> = {
  MessageCircle: BookOpen,
  FolderOpen: BookOpen,
  Network: Cpu,
  Settings: Cpu,
}

const trendOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: { bottom: 0, data: [t('sidebar.hitrate'), t('dashboard.queryCount')] },
  grid: { left: '3%', right: '4%', bottom: '15%', containLabel: true },
  xAxis: {
    type: 'category',
    data: MOCK_HIT_RATE_TREND.map((d) => d.date),
    boundaryGap: false,
  },
  yAxis: [
    { type: 'value', name: t('dashboard.hitRatePercent'), min: 70, max: 100 },
    { type: 'value', name: t('dashboard.queryCount') },
  ],
  series: [
    {
      name: t('sidebar.hitrate'),
      type: 'line',
      smooth: true,
      data: MOCK_HIT_RATE_TREND.map((d) => d.hitRate),
      itemStyle: { color: '#3B82F6' },
      areaStyle: {
        color: {
          type: 'linear',
          x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(59, 130, 246, 0.3)' },
            { offset: 1, color: 'rgba(59, 130, 246, 0.05)' },
          ],
        },
      },
    },
    {
      name: t('dashboard.queryCount'),
      type: 'bar',
      yAxisIndex: 1,
      data: MOCK_HIT_RATE_TREND.map((d) => d.queryCount),
      itemStyle: { color: '#10B981' },
      barWidth: 12,
    },
  ],
}))

const pieOption = computed(() => ({
  tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
  legend: { show: false },
  series: [
    {
      type: 'pie',
      radius: ['45%', '70%'],
      center: ['50%', '45%'],
      avoidLabelOverlap: false,
      label: { show: false },
      emphasis: { label: { show: false } },
      data: MOCK_DASHBOARD_CATEGORIES.map((c) => ({
        name: t(c.name),
        value: c.count,
        itemStyle: {
          color: c.color,
          opacity: selectedCategory.value === 'all' || selectedCategory.value === c.id ? 1 : 0.25,
        },
      })),
    },
  ],
}))

const categoryIcons: Record<string, any> = {
  '1': BookOpen,
  '2': Code2,
  '3': FileCog,
  '4': Cpu,
  '5': HelpCircle,
}

const selectCategory = (cat: any) => {
  selectedCategory.value = selectedCategory.value === cat.id ? 'all' : cat.id
}

const navigateTo = (path: string) => {
  router.push(path)
}
</script>

<template>
  <div class="space-y-6">
    <!-- Page header -->
    <div class="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
      <div>
        <h1 class="text-2xl font-bold tracking-tight">{{ t('sidebar.dashboard') }}</h1>
        <p class="text-sm text-muted-foreground mt-1">
          {{ t('dashboard.subtitle') }}
        </p>
      </div>
    </div>

    <!-- KPI Cards -->
    <div class="grid grid-cols-2 md:grid-cols-4 gap-4">
      <div
        v-for="kpi in MOCK_DASHBOARD_KPIS"
        :key="kpi.id"
        class="rounded-lg border border-border bg-card overflow-hidden"
      >
        <div class="p-5">
          <div class="flex items-start justify-between">
            <div>
              <p class="text-sm text-muted-foreground">{{ t(kpi.label) }}</p>
              <p class="text-2xl font-bold mt-2 tabular-nums">
                {{ kpi.value.toLocaleString() }}
                <span class="text-sm font-normal text-muted-foreground ml-1">{{ t(kpi.unit) }}</span>
              </p>
            </div>
          </div>
          <div class="flex items-center gap-1 mt-3 text-xs">
            <TrendingUp v-if="kpi.trend >= 0" class="size-3.5 text-emerald-500" />
            <TrendingDown v-else class="size-3.5 text-red-500" />
            <span :class="kpi.trend >= 0 ? 'text-emerald-500' : 'text-red-500'">
              {{ kpi.trend >= 0 ? '+' : '' }}{{ kpi.trend }}%
            </span>
            <span class="text-muted-foreground">{{ t(kpi.trendLabel) }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Charts row -->
    <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
      <div class="rounded-lg border border-border bg-card lg:col-span-2">
        <div class="p-5 pb-2">
          <h3 class="text-base font-semibold">{{ t('dashboard.hitRateTrend') }}</h3>
          <p class="text-sm text-muted-foreground mt-1">{{ t('dashboard.hitRateTrendDesc') }}</p>
        </div>
        <div class="p-5 pt-0">
          <v-chart class="h-[320px] w-full" :option="trendOption" autoresize />
        </div>
      </div>

      <div class="rounded-lg border border-border bg-card">
        <div class="p-5 pb-2">
          <h3 class="text-base font-semibold">{{ t('dashboard.knowledgeCategory') }}</h3>
          <p class="text-sm text-muted-foreground mt-1">{{ t('dashboard.knowledgeCategoryDesc') }}</p>
        </div>
        <div class="p-5 pt-0 space-y-4">
          <v-chart class="h-[240px] w-full" :option="pieOption" autoresize />
          <div class="flex flex-wrap gap-2 justify-center">
            <button
              v-for="cat in MOCK_DASHBOARD_CATEGORIES"
              :key="cat.id"
              @click="selectCategory(cat)"
              :class="[
                'flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm transition-colors',
                selectedCategory === cat.id
                  ? 'bg-muted text-black font-medium'
                  : 'text-muted-foreground hover:text-foreground hover:bg-muted/50',
              ]"
            >
              <span
                class="size-2.5 rounded-sm shrink-0"
                :style="{ backgroundColor: cat.color }"
              ></span>
              <span>{{ t(cat.name) }}</span>
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Bottom row -->
    <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
      <!-- Badcase stats -->
      <div class="rounded-lg border border-border bg-card">
        <div class="p-5 pb-2">
          <h3 class="text-base font-semibold flex items-center gap-2">
            <Bug class="size-4 text-red-500" />
            {{ t('dashboard.recentBadcase') }}
          </h3>
          <p class="text-sm text-muted-foreground mt-1">{{ t('dashboard.recentBadcaseDesc') }}</p>
        </div>
        <div class="p-5 pt-0 space-y-3">
          <div
            v-for="item in MOCK_DASHBOARD_BADCASE_STATS"
            :key="item.id"
            class="flex items-center justify-between"
          >
            <div class="flex items-center gap-3">
              <div class="size-2 rounded-full bg-red-500"></div>
              <span class="text-sm">{{ t(item.type) }}</span>
            </div>
            <div class="flex items-center gap-2">
              <span class="font-semibold tabular-nums">{{ item.count }}</span>
              <span
                :class="[
                  'text-xs flex items-center gap-0.5',
                  item.trend === 'up' ? 'text-red-500' : 'text-emerald-500',
                ]"
              >
                <TrendingUp v-if="item.trend === 'up'" class="size-3" />
                <TrendingDown v-else class="size-3" />
                {{ item.changeRate }}%
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- Quick entries -->
      <div class="rounded-lg border border-border bg-card lg:col-span-2">
        <div class="p-5 pb-2">
          <h3 class="text-base font-semibold">{{ t('dashboard.quickEntry') }}</h3>
          <p class="text-sm text-muted-foreground mt-1">{{ t('dashboard.quickEntryDesc') }}</p>
        </div>
        <div class="p-5 pt-0">
          <div class="grid grid-cols-2 md:grid-cols-4 gap-3">
            <button
              v-for="entry in MOCK_DASHBOARD_QUICK_ENTRIES"
              :key="entry.id"
              @click="navigateTo(entry.path)"
              class="flex flex-col items-start gap-2 p-4 rounded-lg border border-border bg-card hover:bg-accent/50 hover:border-primary/30 transition-all text-left group"
            >
              <div class="size-10 rounded-lg bg-primary/10 text-primary flex items-center justify-center group-hover:bg-primary group-hover:text-primary-foreground transition-colors">
                <component :is="iconMap[entry.icon] || Cpu" class="size-5" />
              </div>
              <div>
                <div class="font-medium text-sm flex items-center gap-1">
                  {{ t(entry.title) }}
                  <ArrowUpRight class="size-3 opacity-0 group-hover:opacity-100 transition-opacity" />
                </div>
                <div class="text-xs text-muted-foreground mt-0.5">
                  {{ t(entry.description) }}
                </div>
              </div>
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
