<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { Network, Search, ZoomIn, ZoomOut, Maximize2 } from 'lucide-vue-next'
import { cn } from '@/lib/utils'
import type { GraphNode, GraphEdge } from '@/types/graph'

const props = withDefaults(defineProps<{
  nodes: GraphNode[]
  edges: GraphEdge[]
  height?: number
}>(), {
  height: 280,
})

const emit = defineEmits<{
  (e: 'node-click', node: GraphNode): void
}>()

const canvasWidth = 520
const canvasHeight = 420

const NODE_COLORS: Record<string, string> = {
  chip: '#23395D',
  peripheral: '#2563EB',
  register: '#0891B2',
  api: '#059669',
  doc: '#D97706',
  code: '#7C3AED',
}

const NODE_TYPE_LABELS: Record<string, string> = {
  chip: '芯片',
  peripheral: '外设',
  register: '寄存器',
  api: 'API',
  doc: '文档',
  code: '代码',
}

// State
const scale = ref(1)
const translate = ref({ x: 0, y: 0 })
const isPanning = ref(false)
const dragStart = ref({ x: 0, y: 0 })
const translateStart = ref({ x: 0, y: 0 })
const draggingNode = ref<string | null>(null)
const hoveredNode = ref<string | null>(null)
const nodePositions = ref<Record<string, { x: number; y: number }>>({})

// Layout: radial
const computeLayout = () => {
  const positions: Record<string, { x: number; y: number }> = {}
  const cx = canvasWidth / 2
  const cy = canvasHeight / 2
  const radius = Math.min(canvasWidth, canvasHeight) * 0.28

  props.nodes.forEach((node, i) => {
    if (i === 0) {
      positions[node.id] = { x: cx, y: cy }
    } else {
      const angle = ((i - 1) / (props.nodes.length - 1)) * Math.PI * 2
      positions[node.id] = {
        x: cx + radius * Math.cos(angle),
        y: cy + radius * Math.sin(angle),
      }
    }
  })
  nodePositions.value = positions
}

watch(() => props.nodes, computeLayout, { immediate: true })

// Pan and zoom
const handleWheel = (e: WheelEvent) => {
  e.preventDefault()
  const delta = e.deltaY > 0 ? 0.92 : 1.08
  scale.value = Math.max(0.5, Math.min(2, scale.value * delta))
}

const handleMouseDown = (e: MouseEvent) => {
  if ((e.target as HTMLElement).closest('[data-node]')) return
  isPanning.value = true
  dragStart.value = { x: e.clientX, y: e.clientY }
  translateStart.value = { ...translate.value }
}

const handleMouseMove = (e: MouseEvent) => {
  if (draggingNode.value && nodePositions.value[draggingNode.value]) {
    const svg = (e.target as HTMLElement).closest('svg')?.getBoundingClientRect()
    if (!svg) return
    const x = (e.clientX - svg.left - translate.value.x) / scale.value
    const y = (e.clientY - svg.top - translate.value.y) / scale.value
    nodePositions.value = {
      ...nodePositions.value,
      [draggingNode.value]: { x, y },
    }
    return
  }
  if (!isPanning.value) return
  const dx = e.clientX - dragStart.value.x
  const dy = e.clientY - dragStart.value.y
  translate.value = {
    x: translateStart.value.x + dx,
    y: translateStart.value.y + dy,
  }
}

const handleMouseUp = () => {
  isPanning.value = false
  draggingNode.value = null
}

// Node operations
const nodeRadius = (nodeId: string) => {
  const node = props.nodes.find((n) => n.id === nodeId)
  if (!node) return 22
  return Math.max(22, Math.min(36, 22 + node.label.length * 2.5))
}

const truncatedLabel = (label: string) => label.length > 6 ? label.slice(0, 6) + '...' : label

const highlightedEdges = computed(() => {
  if (!hoveredNode.value) return new Set<string>()
  const set = new Set<string>()
  props.edges.forEach((e) => {
    if (e.source === hoveredNode.value || e.target === hoveredNode.value) {
      set.add(`${e.source}-${e.target}`)
    }
  })
  return set
})

// Global mouseup
onMounted(() => {
  window.addEventListener('mouseup', handleMouseUp)
  window.addEventListener('mousemove', handleMouseMove)
})
onUnmounted(() => {
  window.removeEventListener('mouseup', handleMouseUp)
  window.removeEventListener('mousemove', handleMouseMove)
})

const resetView = () => { scale.value = 1; translate.value = { x: 0, y: 0 } }
</script>

<template>
  <div class="rounded-xl border border-frontend-border bg-frontend-surface overflow-hidden">
    <!-- Header -->
    <div class="flex items-center justify-between px-4 py-2.5 border-b border-frontend-border bg-frontend-input-bg">
      <div class="flex items-center gap-2">
        <Network class="size-4 text-frontend-fg" />
        <span class="text-sm font-semibold text-frontend-fg">知识关联图谱</span>
      </div>
      <div class="flex items-center gap-1">
        <button @click="scale = Math.max(0.5, scale - 0.1)" class="p-1.5 rounded hover:bg-frontend-accent/10 text-frontend-muted hover:text-frontend-fg transition-colors" title="缩小"><ZoomOut class="size-3.5" /></button>
        <span class="text-xs text-frontend-subtle w-10 text-center tabular-nums">{{ Math.round(scale * 100) }}%</span>
        <button @click="scale = Math.min(2, scale + 0.1)" class="p-1.5 rounded hover:bg-frontend-accent/10 text-frontend-muted hover:text-frontend-fg transition-colors" title="放大"><ZoomIn class="size-3.5" /></button>
        <button @click="resetView" class="p-1.5 rounded hover:bg-frontend-accent/10 text-frontend-muted hover:text-frontend-fg transition-colors" title="重置视图"><Maximize2 class="size-3.5" /></button>
      </div>
    </div>

    <!-- Graph canvas -->
    <div class="relative overflow-hidden bg-frontend-input-bg" :style="{ height: height + 'px' }" @mousedown="handleMouseDown" @wheel="handleWheel" @mouseup="handleMouseUp" @mouseleave="handleMouseUp">
      <!-- Hover tooltip -->
      <div v-if="hoveredNode" class="absolute top-2 left-1/2 -translate-x-1/2 z-10 bg-frontend-surface border border-frontend-border rounded-lg px-3 py-2 shadow-md text-xs pointer-events-none">
        <div class="flex items-center gap-1.5">
          <span class="size-2 rounded-full shrink-0" :style="{ backgroundColor: NODE_COLORS[props.nodes.find(n => n.id === hoveredNode)?.type || 'doc'] }" />
          <span class="font-semibold text-frontend-fg">{{ props.nodes.find(n => n.id === hoveredNode)?.label }}</span>
          <span class="text-frontend-subtle">· {{ NODE_TYPE_LABELS[props.nodes.find(n => n.id === hoveredNode)?.type || 'doc'] }}</span>
        </div>
        <p v-if="props.nodes.find(n => n.id === hoveredNode)?.description" class="text-frontend-muted mt-1 max-w-[240px]">{{ props.nodes.find(n => n.id === hoveredNode)?.description }}</p>
      </div>

      <!-- SVG -->
      <svg :viewBox="`0 0 ${canvasWidth} ${canvasHeight}`" class="w-full h-full">
        <!-- Grid -->
        <defs>
          <pattern id="grid" width="20" height="20" patternUnits="userSpaceOnUse">
            <circle cx="0.5" cy="0.5" r="0.5" fill="#E0DCD0" />
          </pattern>
        </defs>
        <rect width="100%" height="100%" fill="url(#grid)" />

        <g :transform="`translate(${translate.x}, ${translate.y}) scale(${scale})`">
          <!-- Edges -->
          <g v-for="edge in edges" :key="`${edge.source}-${edge.target}`">
            <line
              :x1="nodePositions[edge.source]?.x || 0"
              :y1="nodePositions[edge.source]?.y || 0"
              :x2="nodePositions[edge.target]?.x || 0"
              :y2="nodePositions[edge.target]?.y || 0"
              :stroke="highlightedEdges.has(`${edge.source}-${edge.target}`) ? '#23395D' : '#D0D5DD'"
              :stroke-width="highlightedEdges.has(`${edge.source}-${edge.target}`) ? 2 : 1"
              :opacity="hoveredNode && !highlightedEdges.has(`${edge.source}-${edge.target}`) ? 0.2 : 1"
              class="transition-all duration-150"
            />
            <!-- Edge label -->
            <g v-if="edge.label && nodePositions[edge.source] && nodePositions[edge.target]"
              :transform="`translate(${(nodePositions[edge.source].x + nodePositions[edge.target].x) / 2}, ${(nodePositions[edge.source].y + nodePositions[edge.target].y) / 2})`">
              <rect :x="-14" :y="-8" width="28" height="14" rx="3" fill="white" stroke="#E8E4D9" stroke-width="0.5" />
              <text text-anchor="middle" dy="1" font-size="9" fill="#5A6B85">{{ edge.label }}</text>
            </g>
          </g>

          <!-- Nodes -->
          <g v-for="node in nodes" :key="node.id" data-node="true"
            :transform="`translate(${nodePositions[node.id]?.x || 0}, ${nodePositions[node.id]?.y || 0})`"
            :class="cn('cursor-pointer', draggingNode === node.id ? 'cursor-grabbing' : 'cursor-grab')"
            @mousedown.stop="draggingNode = node.id"
            @mouseenter="hoveredNode = node.id"
            @mouseleave="hoveredNode = null"
            @click.stop="emit('node-click', node)"
            :opacity="hoveredNode && hoveredNode !== node.id ? 0.35 : 1"
          >
            <!-- Glow ring -->
            <circle v-if="hoveredNode === node.id" :r="nodeRadius(node.id) + 4" fill="none" :stroke="NODE_COLORS[node.type]" stroke-width="2" opacity="0.3" class="animate-pulse" />
            <!-- Node circle -->
            <circle :r="nodeRadius(node.id)" :fill="NODE_COLORS[node.type]" />
            <circle :r="nodeRadius(node.id) - 2" fill="white" opacity="0.15" />
            <!-- Label -->
            <text text-anchor="middle" dy="1" font-size="10" fill="white" font-weight="600">{{ truncatedLabel(node.label) }}</text>
            <!-- Type label below -->
            <text text-anchor="middle" :y="nodeRadius(node.id) + 13" font-size="8" :fill="NODE_COLORS[node.type]" font-weight="500">{{ NODE_TYPE_LABELS[node.type] }}</text>
          </g>
        </g>
      </svg>
    </div>

    <!-- Legend -->
    <div class="flex items-center justify-center gap-4 px-4 py-2 border-t border-frontend-border bg-frontend-surface">
      <div v-for="(color, type) in NODE_COLORS" :key="type" class="flex items-center gap-1.5">
        <span class="size-2.5 rounded-full shrink-0" :style="{ backgroundColor: color }" />
        <span class="text-[10px] text-frontend-muted">{{ NODE_TYPE_LABELS[type] }}</span>
      </div>
    </div>

    <!-- Usage hint -->
    <div class="text-center py-1.5 text-[10px] text-frontend-subtle bg-frontend-surface border-t border-frontend-border/50">
      拖拽节点调整位置 · 滚轮缩放 · 点击节点查看详情
    </div>
  </div>
</template>
