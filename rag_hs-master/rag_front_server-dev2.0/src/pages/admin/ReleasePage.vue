<script setup lang="ts">
import { ref, computed } from 'vue'
import { Rocket, Search, ChevronRight, Clock, CheckCircle, AlertCircle, Play } from 'lucide-vue-next'

interface Release {
  id: string
  version: string
  name: string
  status: 'draft' | 'testing' | 'released' | 'rolled_back'
  createdAt: string
  createdBy: string
  docCount: number
  description: string
}

const searchKeyword = ref('')
const statusFilter = ref('all')

const STATUS_MAP: Record<string, { label: string; class: string; icon: any }> = {
  draft: { label: '草稿', class: 'bg-muted text-muted-foreground', icon: Clock },
  testing: { label: '测试中', class: 'bg-info/20 text-info', icon: Play },
  released: { label: '已发布', class: 'bg-success/20 text-success', icon: CheckCircle },
  rolled_back: { label: '已回滚', class: 'bg-destructive/20 text-destructive', icon: AlertCircle },
}

const releases: Release[] = [
  {
    id: '1',
    version: 'v2.3.0',
    name: '2024年Q1知识更新',
    status: 'released',
    createdAt: '2024-01-15 14:30',
    createdBy: '张三',
    docCount: 156,
    description: '新增CV720芯片文档，更新NPU驱动指南',
  },
  {
    id: '2',
    version: 'v2.2.1',
    name: '寄存器手册更新',
    status: 'released',
    createdAt: '2024-01-10 09:00',
    createdBy: '李四',
    docCount: 89,
    description: '补充CV620完整寄存器说明',
  },
  {
    id: '3',
    version: 'v2.2.0',
    name: '代码片段库上线',
    status: 'released',
    createdAt: '2024-01-05 16:00',
    createdBy: '王五',
    docCount: 234,
    description: '新增驱动代码片段库，含200+示例',
  },
  {
    id: '4',
    version: 'v2.1.0',
    name: '原理图知识库',
    status: 'rolled_back',
    createdAt: '2023-12-28 11:00',
    createdBy: '赵六',
    docCount: 45,
    description: '新增CV620参考设计原理图',
  },
  {
    id: '5',
    version: 'v2.4.0-beta',
    name: 'Q2预览版',
    status: 'testing',
    createdAt: '2024-01-20 10:00',
    createdBy: '张三',
    docCount: 178,
    description: '新增FAQ知识库，优化检索算法',
  },
  {
    id: '6',
    version: 'v2.5.0-draft',
    name: '下版本规划',
    status: 'draft',
    createdAt: '2024-01-22 08:00',
    createdBy: '李四',
    docCount: 0,
    description: '计划新增知识图谱与多模态支持',
  },
]

const filtered = computed(() => {
  return releases.filter((r) => {
    if (searchKeyword.value && !r.name.toLowerCase().includes(searchKeyword.value.toLowerCase()) && !r.version.toLowerCase().includes(searchKeyword.value.toLowerCase())) return false
    if (statusFilter.value !== 'all' && r.status !== statusFilter.value) return false
    return true
  })
})

const stats = computed(() => ({
  total: releases.length,
  released: releases.filter((r) => r.status === 'released').length,
  testing: releases.filter((r) => r.status === 'testing').length,
  draft: releases.filter((r) => r.status === 'draft').length,
}))
</script>

<template>
  <div class="space-y-4">
    <!-- Page header -->
    <div class="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
      <div>
        <h1 class="text-2xl font-bold tracking-tight flex items-center gap-2">
          <Rocket class="size-6 text-primary" />
          发布管理
        </h1>
        <p class="text-sm text-muted-foreground mt-1">
          管理知识库版本发布，支持灰度发布与回滚
        </p>
      </div>
      <button class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-sm transition-colors flex items-center gap-2">
        <Rocket class="size-4" />
        新建发布
      </button>
    </div>

    <!-- Stats -->
    <div class="grid grid-cols-2 md:grid-cols-4 gap-4">
      <div class="rounded-lg border border-border bg-card p-4">
        <p class="text-sm text-muted-foreground">总发布数</p>
        <p class="text-2xl font-bold mt-1 tabular-nums">{{ stats.total }}</p>
      </div>
      <div class="rounded-lg border border-border bg-card p-4">
        <p class="text-sm text-muted-foreground">已发布</p>
        <p class="text-2xl font-bold mt-1 tabular-nums text-emerald-500">{{ stats.released }}</p>
      </div>
      <div class="rounded-lg border border-border bg-card p-4">
        <p class="text-sm text-muted-foreground">测试中</p>
        <p class="text-2xl font-bold mt-1 tabular-nums text-info">{{ stats.testing }}</p>
      </div>
      <div class="rounded-lg border border-border bg-card p-4">
        <p class="text-sm text-muted-foreground">草稿</p>
        <p class="text-2xl font-bold mt-1 tabular-nums text-muted-foreground">{{ stats.draft }}</p>
      </div>
    </div>

    <!-- Filter bar -->
    <div class="rounded-lg border border-border bg-card p-4">
      <div class="flex flex-col md:flex-row gap-3 items-start md:items-center">
        <div class="flex-1 relative w-full md:w-auto">
          <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <input
            v-model="searchKeyword"
            type="search"
            placeholder="按版本号或名称搜索..."
            class="w-full h-9 rounded-md border border-border bg-background pl-9 pr-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          />
        </div>
        <div class="flex gap-2 flex-wrap">
          <select
            v-model="statusFilter"
            class="h-9 w-[130px] rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          >
            <option value="all">全部状态</option>
            <option value="draft">草稿</option>
            <option value="testing">测试中</option>
            <option value="released">已发布</option>
            <option value="rolled_back">已回滚</option>
          </select>
        </div>
      </div>
    </div>

    <!-- Release list -->
    <div class="space-y-3">
      <div
        v-for="release in filtered"
        :key="release.id"
        class="rounded-lg border border-border bg-card p-4 hover:border-primary/30 hover:shadow-sm transition-all cursor-pointer"
      >
        <div class="flex items-start justify-between gap-4">
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-3">
              <span class="font-mono text-sm font-semibold text-primary">{{ release.version }}</span>
              <span
                :class="[
                  'inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium',
                  STATUS_MAP[release.status].class,
                ]"
              >
                <component :is="STATUS_MAP[release.status].icon" class="size-3" />
                {{ STATUS_MAP[release.status].label }}
              </span>
            </div>
            <h3 class="font-medium mt-1.5">{{ release.name }}</h3>
            <p class="text-sm text-muted-foreground mt-1">{{ release.description }}</p>
            <div class="flex items-center gap-4 mt-3 text-xs text-muted-foreground">
              <span>{{ release.docCount }} 篇文档</span>
              <span>{{ release.createdBy }}</span>
              <span>{{ release.createdAt }}</span>
            </div>
          </div>
          <div class="flex items-center gap-2 shrink-0">
            <button
              v-if="release.status === 'draft'"
              class="px-3 py-1.5 text-sm rounded-md border border-border hover:bg-accent transition-colors"
            >
              提交测试
            </button>
            <button
              v-if="release.status === 'testing'"
              class="px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
            >
              正式发布
            </button>
            <button
              v-if="release.status === 'released'"
              class="px-3 py-1.5 text-sm rounded-md border border-destructive/30 text-destructive hover:bg-destructive/10 transition-colors"
            >
              回滚
            </button>
            <ChevronRight class="size-4 text-muted-foreground" />
          </div>
        </div>
      </div>
    </div>

    <div class="text-sm text-muted-foreground text-center py-4">
      共 {{ filtered.length }} 个版本
    </div>
  </div>
</template>
