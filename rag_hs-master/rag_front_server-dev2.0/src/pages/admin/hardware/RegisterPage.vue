<script setup lang="ts">
import { ref, computed } from 'vue'
import { Search, Cpu, ChevronDown, ChevronRight } from 'lucide-vue-next'
import { MOCK_REGISTERS, type IRegister } from '@/data/hardware'

const searchKeyword = ref('')
const chipFilter = ref('CV620')
const expandedRow = ref<string | null>(null)

const CHIPS = ['CV620', 'CV610', 'CV720', 'Hi3516DV500']

const filtered = computed(() => {
  return MOCK_REGISTERS.filter((reg) => {
    if (chipFilter.value && reg.chip !== chipFilter.value) return false
    if (searchKeyword.value) {
      const kw = searchKeyword.value.toLowerCase()
      return (
        reg.name.toLowerCase().includes(kw) ||
        reg.address.toLowerCase().includes(kw) ||
        reg.description.toLowerCase().includes(kw)
      )
    }
    return true
  })
})

const toggleRow = (id: string) => {
  expandedRow.value = expandedRow.value === id ? null : id
}

// 级联关系图数据
const cascadeData = {
  nodes: [
    { id: 'clk-en', name: 'CLK_EN', type: 'control' },
    { id: 'gpio-dir', name: 'GPIO_DIR', type: 'register' },
    { id: 'gpio-data', name: 'GPIO_DATA', type: 'register' },
    { id: 'gpio-inten', name: 'GPIO_INTEN', type: 'register' },
    { id: 'npu-ctrl', name: 'NPU_CTRL', type: 'register' },
  ],
  edges: [
    { source: 'clk-en', target: 'gpio-dir' },
    { source: 'clk-en', target: 'npu-ctrl' },
    { source: 'gpio-dir', target: 'gpio-data' },
    { source: 'gpio-dir', target: 'gpio-inten' },
  ],
}

const nodeColors: Record<string, string> = {
  control: '#F59E0B',
  register: '#3B82F6',
}
</script>

<template>
  <div class="space-y-4">
    <!-- Page header -->
    <div>
      <h1 class="text-2xl font-bold tracking-tight flex items-center gap-2">
        <Cpu class="size-6 text-primary" />
        寄存器查询
      </h1>
      <p class="text-sm text-muted-foreground mt-1">
        查询芯片寄存器详细信息，包括地址、位域、读写属性等
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
            placeholder="按名称、地址、功能关键词搜索..."
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
    <div class="grid grid-cols-1 lg:grid-cols-4 gap-4">
      <!-- Table -->
      <div class="lg:col-span-3 rounded-lg border border-border bg-card overflow-hidden">
        <div class="w-full overflow-x-auto">
          <table class="w-full text-sm">
            <thead class="bg-muted/30 border-b border-border">
              <tr>
                <th class="w-8 px-3 py-3"></th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">寄存器名称</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">地址</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">位域</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">属性</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">复位值</th>
                <th class="px-4 py-3 text-left font-medium">功能描述</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="reg in filtered" :key="reg.id">
                <tr
                  @click="toggleRow(reg.id)"
                  class="border-b border-border last:border-0 hover:bg-accent/30 cursor-pointer transition-colors"
                >
                  <td class="px-3 py-3">
                    <ChevronDown
                      v-if="expandedRow === reg.id"
                      class="size-4 text-muted-foreground"
                    />
                    <ChevronRight
                      v-else
                      class="size-4 text-muted-foreground"
                    />
                  </td>
                  <td class="px-4 py-3 font-mono font-medium text-primary">{{ reg.name }}</td>
                  <td class="px-4 py-3 font-mono text-xs text-muted-foreground">{{ reg.address }}</td>
                  <td class="px-4 py-3 text-muted-foreground">{{ reg.bitField }}</td>
                  <td class="px-4 py-3">
                    <span
                      :class="[
                        'px-2 py-0.5 text-xs rounded font-mono',
                        reg.access === 'RW'
                          ? 'bg-primary/10 text-primary'
                          : reg.access === 'R'
                          ? 'bg-info/10 text-info'
                          : 'bg-warning/10 text-warning',
                      ]"
                    >
                      {{ reg.access }}
                    </span>
                  </td>
                  <td class="px-4 py-3 font-mono text-xs">{{ reg.resetValue }}</td>
                  <td class="px-4 py-3 text-muted-foreground">
                    <span class="block truncate max-w-[200px]">{{ reg.description }}</span>
                  </td>
                </tr>
                <!-- Expanded detail -->
                <tr v-if="expandedRow === reg.id" class="bg-muted/20">
                  <td colspan="7" class="px-8 py-4">
                    <div class="text-xs">
                      <h4 class="font-semibold mb-2">位域详细定义</h4>
                      <table class="w-full border border-border/50 rounded">
                        <thead class="bg-muted/50">
                          <tr>
                            <th class="px-3 py-2 text-left font-medium">位段</th>
                            <th class="px-3 py-2 text-left font-medium">名称</th>
                            <th class="px-3 py-2 text-left font-medium">属性</th>
                            <th class="px-3 py-2 text-left font-medium">复位值</th>
                            <th class="px-3 py-2 text-left font-medium">描述</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr v-for="(bf, idx) in (reg as any).bitFields || []" :key="idx" class="border-t border-border/50">
                            <td class="px-3 py-2 font-mono">{{ bf.range }}</td>
                            <td class="px-3 py-2 font-mono text-primary">{{ bf.name }}</td>
                            <td class="px-3 py-2">{{ bf.access }}</td>
                            <td class="px-3 py-2 font-mono">{{ bf.reset }}</td>
                            <td class="px-3 py-2 text-muted-foreground">{{ bf.description }}</td>
                          </tr>
                          <tr v-if="!(reg as any).bitFields" class="border-t border-border/50">
                            <td colspan="5" class="px-3 py-4 text-center text-muted-foreground">
                              暂无位域详细信息
                            </td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
        <div class="px-4 py-3 border-t border-border text-sm text-muted-foreground">
          共 {{ filtered.length }} 个寄存器
        </div>
      </div>

      <!-- Cascade graph -->
      <div class="rounded-lg border border-border bg-card">
        <div class="p-4 border-b border-border">
          <h3 class="text-sm font-semibold">级联关系图</h3>
          <p class="text-xs text-muted-foreground mt-1">寄存器控制与关联关系</p>
        </div>
        <div class="p-4 space-y-3">
          <div
            v-for="node in cascadeData.nodes"
            :key="node.id"
            class="flex items-center gap-2"
          >
            <span
              class="size-3 rounded-full shrink-0"
              :style="{ backgroundColor: nodeColors[node.type] }"
            ></span>
            <span class="font-mono text-sm">{{ node.name }}</span>
            <span class="text-xs text-muted-foreground ml-auto">{{ node.type }}</span>
          </div>
          <div class="pt-3 border-t border-border">
            <p class="text-xs text-muted-foreground mb-2">连接关系 ({{ cascadeData.edges.length }})</p>
            <div class="space-y-1">
              <div
                v-for="(edge, idx) in cascadeData.edges"
                :key="idx"
                class="text-xs font-mono text-muted-foreground flex items-center gap-1"
              >
                <span class="text-primary">{{ edge.source }}</span>
                <span>→</span>
                <span class="text-primary">{{ edge.target }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
