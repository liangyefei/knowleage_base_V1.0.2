<script lang="ts">
interface ModuleNode {
  id: string
  name: string
  type: 'core' | 'peripheral' | 'interface' | 'memory'
  x: number
  y: number
  width: number
  height: number
  children?: ModuleNode[]
}

interface Connection {
  from: string
  to: string
  label: string
  width: number
}
</script>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { CircuitBoard, ZoomIn, ZoomOut, Maximize2 } from 'lucide-vue-next'

const svgRef = ref<SVGSVGElement | null>(null)
const scale = ref(1)
const offset = ref({ x: 0, y: 0 })
const isDragging = ref(false)
const dragStart = ref({ x: 0, y: 0 })
const hoveredConn = ref<string | null>(null)
const selectedModule = ref<string | null>(null)

const modules: ModuleNode[] = [
  { id: 'cpu', name: 'CPU Core\nCortex-A55 ×4', type: 'core', x: 300, y: 100, width: 160, height: 80 },
  { id: 'npu', name: 'NPU\n4TOPS INT8', type: 'core', x: 300, y: 220, width: 160, height: 80 },
  { id: 'isp', name: 'ISP\n图像处理', type: 'peripheral', x: 300, y: 340, width: 160, height: 60 },
  { id: 'vpu', name: 'VPU\n视频编解码', type: 'peripheral', x: 300, y: 430, width: 160, height: 60 },
  { id: 'ddr', name: 'DDR Controller\nLPDDR4X', type: 'memory', x: 50, y: 120, width: 140, height: 60 },
  { id: 'flash', name: 'Flash\nSPI NOR/NAND', type: 'memory', x: 50, y: 220, width: 140, height: 60 },
  { id: 'sram', name: 'SRAM\n256KB', type: 'memory', x: 50, y: 320, width: 140, height: 50 },
  { id: 'uart', name: 'UART ×3', type: 'interface', x: 540, y: 80, width: 120, height: 40 },
  { id: 'spi', name: 'SPI ×2', type: 'interface', x: 540, y: 140, width: 120, height: 40 },
  { id: 'i2c', name: 'I2C ×4', type: 'interface', x: 540, y: 200, width: 120, height: 40 },
  { id: 'usb', name: 'USB 2.0 OTG', type: 'interface', x: 540, y: 260, width: 120, height: 40 },
  { id: 'sdio', name: 'SDIO 3.0', type: 'interface', x: 540, y: 320, width: 120, height: 40 },
  { id: 'gmii', name: 'GMAC\n千兆以太网', type: 'interface', x: 540, y: 380, width: 120, height: 50 },
  { id: 'mipi', name: 'MIPI CSI/DSI', type: 'interface', x: 540, y: 450, width: 120, height: 50 },
]

const connections: Connection[] = [
  { from: 'cpu', to: 'npu', label: 'AXI 256bit', width: 4 },
  { from: 'cpu', to: 'ddr', label: 'DDR 总线', width: 4 },
  { from: 'npu', to: 'ddr', label: 'DMA', width: 2 },
  { from: 'cpu', to: 'flash', label: 'SPI', width: 1 },
  { from: 'cpu', to: 'sram', label: 'TCM', width: 2 },
  { from: 'cpu', to: 'isp', label: 'AXI', width: 2 },
  { from: 'cpu', to: 'vpu', label: 'AXI', width: 2 },
  { from: 'isp', to: 'mipi', label: 'CSI', width: 2 },
  { from: 'vpu', to: 'mipi', label: 'DSI', width: 2 },
  { from: 'cpu', to: 'uart', label: 'APB', width: 1 },
  { from: 'cpu', to: 'spi', label: 'APB', width: 1 },
  { from: 'cpu', to: 'i2c', label: 'APB', width: 1 },
  { from: 'cpu', to: 'usb', label: 'AHB', width: 2 },
  { from: 'cpu', to: 'sdio', label: 'AHB', width: 2 },
  { from: 'cpu', to: 'gmii', label: 'AHB', width: 2 },
]

const typeColors: Record<string, { fill: string; stroke: string }> = {
  core: { fill: 'rgba(59, 130, 246, 0.15)', stroke: '#3B82F6' },
  peripheral: { fill: 'rgba(16, 185, 129, 0.15)', stroke: '#10B981' },
  interface: { fill: 'rgba(245, 158, 11, 0.15)', stroke: '#F59E0B' },
  memory: { fill: 'rgba(139, 92, 246, 0.15)', stroke: '#8B5CF6' },
}

const getModuleCenter = (id: string) => {
  const m = modules.find((mod) => mod.id === id)
  if (!m) return { x: 0, y: 0 }
  return { x: m.x + m.width / 2, y: m.y + m.height / 2 }
}

const getModuleEdge = (fromId: string, toId: string) => {
  const from = modules.find((m) => m.id === fromId)
  const to = modules.find((m) => m.id === toId)
  if (!from || !to) return { x1: 0, y1: 0, x2: 0, y2: 0 }

  const fromCenter = { x: from.x + from.width / 2, y: from.y + from.height / 2 }
  const toCenter = { x: to.x + to.width / 2, y: to.y + to.height / 2 }

  // 简单的左右边缘连接
  if (fromCenter.x < toCenter.x) {
    // from 在左边，用右边；to 在右边，用左边
    return {
      x1: from.x + from.width,
      y1: fromCenter.y,
      x2: to.x,
      y2: toCenter.y,
    }
  } else {
    return {
      x1: from.x,
      y1: fromCenter.y,
      x2: to.x + to.width,
      y2: toCenter.y,
    }
  }
}

const zoomIn = () => {
  scale.value = Math.min(scale.value * 1.2, 3)
}

const zoomOut = () => {
  scale.value = Math.max(scale.value / 1.2, 0.3)
}

const resetView = () => {
  scale.value = 1
  offset.value = { x: 0, y: 0 }
}

const handleWheel = (e: WheelEvent) => {
  e.preventDefault()
  const delta = e.deltaY > 0 ? 0.9 : 1.1
  scale.value = Math.max(0.3, Math.min(3, scale.value * delta))
}

const handleMouseDown = (e: MouseEvent) => {
  isDragging.value = true
  dragStart.value = { x: e.clientX - offset.value.x, y: e.clientY - offset.value.y }
}

const handleMouseMove = (e: MouseEvent) => {
  if (!isDragging.value) return
  offset.value = {
    x: e.clientX - dragStart.value.x,
    y: e.clientY - dragStart.value.y,
  }
}

const handleMouseUp = () => {
  isDragging.value = false
}

onMounted(() => {
  window.addEventListener('mouseup', handleMouseUp)
  window.addEventListener('mousemove', handleMouseMove)
})

onUnmounted(() => {
  window.removeEventListener('mouseup', handleMouseUp)
  window.removeEventListener('mousemove', handleMouseMove)
})
</script>

<template>
  <div class="space-y-4">
    <!-- Page header -->
    <div class="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
      <div>
        <h1 class="text-2xl font-bold tracking-tight flex items-center gap-2">
          <CircuitBoard class="size-6 text-primary" />
          原理图展示
        </h1>
        <p class="text-sm text-muted-foreground mt-1">
          CV620 芯片功能框图与模块连接关系，支持缩放平移
        </p>
      </div>
      <div class="flex items-center gap-2">
        <button
          @click="zoomOut"
          class="p-2 rounded-md border border-border hover:bg-accent transition-colors"
          title="缩小"
        >
          <ZoomOut class="size-4" />
        </button>
        <span class="text-sm font-mono w-16 text-center">{{ Math.round(scale * 100) }}%</span>
        <button
          @click="zoomIn"
          class="p-2 rounded-md border border-border hover:bg-accent transition-colors"
          title="放大"
        >
          <ZoomIn class="size-4" />
        </button>
        <button
          @click="resetView"
          class="p-2 rounded-md border border-border hover:bg-accent transition-colors"
          title="重置视图"
        >
          <Maximize2 class="size-4" />
        </button>
      </div>
    </div>

    <!-- Legend -->
    <div class="rounded-lg border border-border bg-card p-3">
      <div class="flex flex-wrap gap-4 text-xs">
        <div class="flex items-center gap-2">
          <span class="size-3 rounded" :style="{ backgroundColor: typeColors.core.stroke }"></span>
          <span>核心处理</span>
        </div>
        <div class="flex items-center gap-2">
          <span class="size-3 rounded" :style="{ backgroundColor: typeColors.peripheral.stroke }"></span>
          <span>外设模块</span>
        </div>
        <div class="flex items-center gap-2">
          <span class="size-3 rounded" :style="{ backgroundColor: typeColors.interface.stroke }"></span>
          <span>接口</span>
        </div>
        <div class="flex items-center gap-2">
          <span class="size-3 rounded" :style="{ backgroundColor: typeColors.memory.stroke }"></span>
          <span>存储</span>
        </div>
      </div>
    </div>

    <!-- Schematic canvas -->
    <div
      class="rounded-lg border border-border bg-card overflow-hidden cursor-grab active:cursor-grabbing"
      style="height: 600px;"
      @wheel="handleWheel"
      @mousedown="handleMouseDown"
    >
      <svg
        ref="svgRef"
        width="100%"
        height="100%"
        viewBox="0 0 720 560"
        :style="{
          transform: `translate(${offset.x}px, ${offset.y}px) scale(${scale})`,
          transformOrigin: 'center center',
        }"
      >
        <!-- Grid background -->
        <defs>
          <pattern id="grid" width="20" height="20" patternUnits="userSpaceOnUse">
            <path d="M 20 0 L 0 0 0 20" fill="none" stroke="hsl(var(--border))" stroke-width="0.5" opacity="0.3" />
          </pattern>
        </defs>
        <rect width="720" height="560" fill="url(#grid)" />

        <!-- Connections -->
        <g>
          <template v-for="conn in connections" :key="`${conn.from}-${conn.to}`">
            <line
              :x1="getModuleEdge(conn.from, conn.to).x1"
              :y1="getModuleEdge(conn.from, conn.to).y1"
              :x2="getModuleEdge(conn.from, conn.to).x2"
              :y2="getModuleEdge(conn.from, conn.to).y2"
              :stroke="hoveredConn === `${conn.from}-${conn.to}` ? '#3B82F6' : 'hsl(var(--muted-foreground))'"
              :stroke-width="conn.width"
              :opacity="hoveredConn && hoveredConn !== `${conn.from}-${conn.to}` ? 0.2 : 0.6"
              class="transition-all cursor-pointer"
              @mouseenter="hoveredConn = `${conn.from}-${conn.to}`"
              @mouseleave="hoveredConn = null"
            />
            <!-- Connection label -->
            <text
              v-if="hoveredConn === `${conn.from}-${conn.to}`"
              :x="(getModuleEdge(conn.from, conn.to).x1 + getModuleEdge(conn.from, conn.to).x2) / 2"
              :y="(getModuleEdge(conn.from, conn.to).y1 + getModuleEdge(conn.from, conn.to).y2) / 2 - 4"
              text-anchor="middle"
              class="text-[10px] fill-primary font-mono"
            >
              {{ conn.label }}
            </text>
          </template>
        </g>

        <!-- Module boxes -->
        <g>
          <g
            v-for="mod in modules"
            :key="mod.id"
            @click="selectedModule = mod.id"
            class="cursor-pointer"
          >
            <rect
              :x="mod.x"
              :y="mod.y"
              :width="mod.width"
              :height="mod.height"
              :fill="typeColors[mod.type].fill"
              :stroke="selectedModule === mod.id ? '#3B82F6' : typeColors[mod.type].stroke"
              :stroke-width="selectedModule === mod.id ? 2 : 1"
              rx="4"
              class="transition-all"
            />
            <text
              :x="mod.x + mod.width / 2"
              :y="mod.y + mod.height / 2 - 4"
              text-anchor="middle"
              class="text-[11px] fill-foreground font-medium"
            >
              {{ mod.name.split('\n')[0] }}
            </text>
            <text
              :x="mod.x + mod.width / 2"
              :y="mod.y + mod.height / 2 + 10"
              text-anchor="middle"
              class="text-[10px] fill-muted-foreground font-mono"
            >
              {{ mod.name.split('\n')[1] || '' }}
            </text>
          </g>
        </g>
      </svg>
    </div>

    <!-- Selected module detail -->
    <div v-if="selectedModule" class="rounded-lg border border-border bg-card p-4">
      <h3 class="text-sm font-semibold mb-2">
        {{ modules.find(m => m.id === selectedModule)?.name.split('\n')[0] }}
      </h3>
      <p class="text-sm text-muted-foreground">
        类型：{{ modules.find(m => m.id === selectedModule)?.type }} |
        点击模块查看详细规格说明。悬停连线查看总线位宽与类型。
      </p>
    </div>
  </div>
</template>
