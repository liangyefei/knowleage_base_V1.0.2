<script setup lang="ts">
import { ref, computed } from 'vue'
import { Search, Plug, Zap } from 'lucide-vue-next'
import { MOCK_PINS, type IPin } from '@/data/hardware'

const searchKeyword = ref('')
const groupFilter = ref('all')
const chipFilter = ref('CV620')

const CHIPS = ['CV620', 'CV610', 'CV720']

const PIN_GROUPS = [
  { value: 'all', label: '全部' },
  { value: 'power', label: '电源' },
  { value: 'clock', label: '时钟' },
  { value: 'gpio', label: 'GPIO' },
  { value: 'uart', label: 'UART' },
  { value: 'spi', label: 'SPI' },
  { value: 'i2c', label: 'I2C' },
  { value: 'usb', label: 'USB' },
  { value: 'sdio', label: 'SDIO' },
]

const filtered = computed(() => {
  return MOCK_PINS.filter((pin) => {
    if (chipFilter.value && pin.chip !== chipFilter.value) return false
    if (groupFilter.value !== 'all' && pin.group !== groupFilter.value) return false
    if (searchKeyword.value) {
      const kw = searchKeyword.value.toLowerCase()
      return (
        pin.pinName.toLowerCase().includes(kw) ||
        pin.function.toLowerCase().includes(kw) ||
        String(pin.pinNumber).includes(kw)
      )
    }
    return true
  })
})

const groupCounts = computed(() => {
  const counts: Record<string, number> = { all: MOCK_PINS.length }
  PIN_GROUPS.forEach((g) => {
    if (g.value !== 'all') {
      counts[g.value] = MOCK_PINS.filter((p) => p.group === g.value).length
    }
  })
  return counts
})
</script>

<template>
  <div class="space-y-4">
    <!-- Page header -->
    <div>
      <h1 class="text-2xl font-bold tracking-tight flex items-center gap-2">
        <Plug class="size-6 text-primary" />
        Pin脚查询
      </h1>
      <p class="text-sm text-muted-foreground mt-1">
        查询芯片引脚定义与功能，支持按功能组筛选
      </p>
    </div>

    <!-- Filter bar -->
    <div class="rounded-lg border border-border bg-card p-4">
      <div class="flex flex-col md:flex-row gap-3 items-start md:items-center">
        <div class="flex-1 relative w-full md:w-auto">
          <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <input
            v-model="searchKeyword"
            type="search"
            placeholder="按引脚号、名称、功能搜索..."
            class="w-full h-9 rounded-md border border-border bg-background pl-9 pr-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          />
        </div>
        <select
          v-model="chipFilter"
          class="h-9 w-[140px] rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
        >
          <option v-for="c in CHIPS" :key="c" :value="c">{{ c }}</option>
        </select>
      </div>
    </div>

    <!-- Main content -->
    <div class="grid grid-cols-1 lg:grid-cols-5 gap-4">
      <!-- Left: Group filter -->
      <div class="lg:col-span-1">
        <div class="rounded-lg border border-border bg-card p-3 sticky top-4">
          <h3 class="text-xs font-semibold text-muted-foreground uppercase px-2 py-1">功能分组</h3>
          <div class="space-y-0.5 mt-1">
            <button
              v-for="group in PIN_GROUPS"
              :key="group.value"
              @click="groupFilter = group.value"
              :class="[
                'w-full flex items-center justify-between px-2 py-1.5 text-sm rounded transition-colors',
                groupFilter === group.value
                  ? 'bg-primary/10 text-primary font-medium'
                  : 'hover:bg-accent text-muted-foreground hover:text-foreground',
              ]"
            >
              <span>{{ group.label }}</span>
              <span class="text-xs">{{ groupCounts[group.value] || 0 }}</span>
            </button>
          </div>
        </div>
      </div>

      <!-- Right: Table -->
      <div class="lg:col-span-4 rounded-lg border border-border bg-card overflow-hidden">
        <div class="w-full overflow-x-auto">
          <table class="w-full text-sm">
            <thead class="bg-muted/30 border-b border-border">
              <tr>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">引脚号</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">引脚名称</th>
                <th class="px-4 py-3 text-left font-medium">功能描述</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">复用功能</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">电气特性</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="pin in filtered"
                :key="pin.id"
                class="border-b border-border last:border-0 hover:bg-accent/30 transition-colors"
              >
                <td class="px-4 py-3 font-mono font-semibold text-primary">{{ pin.pinNumber }}</td>
                <td class="px-4 py-3 font-mono text-xs">{{ pin.pinName }}</td>
                <td class="px-4 py-3">
                  <span class="block max-w-[200px] truncate">{{ pin.function }}</span>
                </td>
                <td class="px-4 py-3">
                  <span class="text-xs text-muted-foreground">{{ pin.muxFunction?.[0] || '-' }}</span>
                </td>
                <td class="px-4 py-3">
                  <div class="flex items-center gap-1 text-xs text-muted-foreground">
                    <Zap class="size-3 shrink-0" />
                    <span>{{ pin.electrical }}</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="px-4 py-3 border-t border-border text-sm text-muted-foreground">
          共 {{ filtered.length }} 个引脚
        </div>
      </div>
    </div>
  </div>
</template>
