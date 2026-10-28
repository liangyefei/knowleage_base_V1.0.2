<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount, shallowRef, nextTick, watch } from 'vue'
import { Network, Loader2, Database, Search, X, FileCode, Download } from 'lucide-vue-next'
import mammoth from 'mammoth'
import * as XLSX from 'xlsx'
import { init as initPptxPreview } from 'pptx-preview'
import { sanitizeRenderedHtml } from '@/lib/sanitize-html'
import { sniffBlobKind } from '@/lib/file-sniff'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'
import type { CodeGraphNode, CodeGraphEdge, CodeNodeDetail, KgNode, KgEdge, GraphFragmentResult } from '@/data/graph'
import {
  searchCodeGraph,
  searchDocGraph,
  graphSuggest,
  getCodeNodeDetail,
  fetchGraphFragment,
  getCodeNodeColor,
  getKgNodeColor,
  labelZh,
  KG_COLORS,
  type GraphSuggestItem,
} from '@/services/graph.service'
import { getKbOptions, getFileContent, type KbOption } from '@/services/knowledge.service'
import { cancelPendingToast } from '@/services/api-client'
import { useI18n } from '@/composables/useI18n'
import { toast } from '@/lib/toast'
import { useDownloadProgress } from '@/composables/useDownloadProgress'
import SelectMenu from '@/components/admin/SelectMenu.vue'

use([CanvasRenderer, GraphChart, TooltipComponent, LegendComponent])

const { t } = useI18n()
const loading = ref(false)
const hasSearched = ref(false)

// 视图模式: code=代码图谱, kg=文档知识图谱
const mode = ref<'code' | 'kg'>('code')

// ── 代码图谱数据 ──
const codeNodes = reactive(new Map<number, CodeGraphNode>())
const codeEdges = reactive(new Map<string, CodeGraphEdge>())
const selectedCodeId = ref<number | null>(null)
const candidates = ref<CodeGraphNode[]>([])
const codeNodeDetail = ref<CodeNodeDetail | null>(null)
const codeDetailLoading = ref(false)
const codeElapsed = ref(0)
const codeCenterId = ref<number | null>(null)

// ── 文档知识图谱数据 ──
const kgNodes = reactive(new Map<string, KgNode>())
const kgEdges = reactive(new Map<string, KgEdge>())
const kgSelectedId = ref<string | null>(null)
const kgCenter = ref<string | null>(null)
const kgCount = ref(0)
const kgDown = ref<string | null>(null)
const kgElapsed = ref(0)
// KG 节点详情：该节点的关系边列表
const kgDetailEdges = ref<KgEdge[]>([])
const kgDetailLoading = ref(false)

// ── KG 关系片段面板（POST /kg/graph-fragment，按边索引记录展开/加载/结果状态）──
interface KgFragmentState {
  expanded: boolean
  loading: boolean
  result: GraphFragmentResult | null
  error: string
}
const kgFragmentStates = ref<Record<number, KgFragmentState>>({})

function resetKgFragments() {
  kgFragmentStates.value = {}
}

/** 展开 → 首次拉取片段（出错后再次展开会重试）；收起 → 仅折叠不清缓存 */
async function toggleKgFragment(i: number) {
  // 注意：必须先写入再从代理读回；赋值表达式返回的是原始对象，直接用会导致后续修改不触发视图更新
  if (!kgFragmentStates.value[i]) {
    kgFragmentStates.value[i] = { expanded: false, loading: false, result: null, error: '' }
  }
  const st = kgFragmentStates.value[i]
  if (st.expanded) {
    st.expanded = false
    return
  }
  if (!selectedKbId.value) {
    toast.info(t('graph.selectKbFirst'))
    return
  }
  const e = kgDetailEdges.value[i]
  if (!e) return
  st.expanded = true
  if (st.result || st.loading) return
  st.loading = true
  st.error = ''
  try {
    const res = await fetchGraphFragment(selectedKbId.value, e.source, e.type, e.target)
    st.result = res
    if (res.error) st.error = res.error
  } catch (err: unknown) {
    cancelPendingToast()
    st.error =
      err instanceof Error && err.name === 'TimeoutError'
        ? t('graph.fragmentTimeout')
        : (err as { message?: string })?.message || t('graph.fragmentLoadFailed')
  } finally {
    st.loading = false
  }
}

// ── 知识库 ──
const kbOptions = ref<KbOption[]>([])
const selectedKbId = ref('')

const keyword = ref('')
const chartRef = shallowRef<InstanceType<typeof VChart> | null>(null)
let searchSeq = 0

// ── 查看原文弹窗（同 /codemap：getFileContent → blob 渲染）──
const sourceFileOpen = ref(false)
const sourceFilePath = ref('')
const sourceFileStartLine = ref(1)
const sourceFileEndLine = ref(1)
const sourceFileContent = ref('')
const sourceFileLines = ref<string[]>([])
const sourceFileHtml = ref(false)
const sourceFileBlobUrl = ref('')
const sourceFileIsPdf = ref(false)
const sourceFileLoading = ref(false)
const sourceFileName = ref('')
const dlProgress = useDownloadProgress()
const sourceFileContentEl = ref<HTMLElement | null>(null)
// pptx 幻灯片渲染（pptx-preview）
const sourceFilePptxBuffer = ref<ArrayBuffer | null>(null)
const sourceFilePptxContainerRef = ref<HTMLElement | null>(null)
// 不支持在线预览的二进制：展示提示卡片 + 手动下载
const sourceFileBinary = ref<Blob | null>(null)

// PDF 无行概念：start_line 视为页码，通过浏览器 PDF 查看器的 #page 参数跳页
const sourceFileSrc = computed(() => {
  if (!sourceFileBlobUrl.value) return ''
  if (sourceFileIsPdf.value && sourceFileStartLine.value > 0) {
    return `${sourceFileBlobUrl.value}#page=${sourceFileStartLine.value}`
  }
  return sourceFileBlobUrl.value
})

function closeSourceFile() {
  if (sourceFileBlobUrl.value) URL.revokeObjectURL(sourceFileBlobUrl.value)
  sourceFileOpen.value = false
  sourceFileBlobUrl.value = ''
  sourceFileContent.value = ''
  sourceFileLines.value = []
  sourceFileHtml.value = false
  sourceFileIsPdf.value = false
  sourceFileEndLine.value = 1
  sourceFilePptxBuffer.value = null
  sourceFileBinary.value = null
}

/** 不支持预览的文件：手动下载（不自动触发） */
function downloadSourceFile() {
  const blob = sourceFileBinary.value
  if (!blob) return
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = sourceFileName.value || 'file'
  a.click()
  URL.revokeObjectURL(url)
}

async function renderSourcePptx() {
  if (sourceFilePptxBuffer.value) {
    await nextTick()
    const el = sourceFilePptxContainerRef.value
    if (el) {
      el.innerHTML = ''
      try {
        const previewer = initPptxPreview(el, { width: el.clientWidth || 800, height: 450, mode: 'list' })
        await previewer.preview(sourceFilePptxBuffer.value)
      } catch { /* 渲染失败保持空面板 */ }
    }
  }
}

watch(sourceFilePptxBuffer, () => { void renderSourcePptx() })

async function openSourceFile(kbId: string, file: string, startLine?: number, endLine?: number) {
  if (!kbId || !file) return
  sourceFileOpen.value = true
  sourceFilePath.value = file
  sourceFileStartLine.value = Math.max(1, startLine || 1)
  sourceFileEndLine.value = Math.max(sourceFileStartLine.value, endLine || startLine || 1)
  sourceFileContent.value = ''
  sourceFileLines.value = []
  sourceFileHtml.value = false
  sourceFileIsPdf.value = false
  sourceFilePptxBuffer.value = null
  sourceFileBinary.value = null
  if (sourceFileBlobUrl.value) URL.revokeObjectURL(sourceFileBlobUrl.value)
  sourceFileBlobUrl.value = ''
  sourceFileLoading.value = true
  sourceFileName.value = file.split('/').pop() || file

  try {
    dlProgress.start()
    const { blob } = await getFileContent(kbId, file, dlProgress.onProgress)
    const ext = sourceFileName.value.split('.').pop()?.toLowerCase() || ''

    if (ext === 'md' || ext === 'markdown') {
      // 与映射 start_line 对齐：按原始行渲染以支持定位高亮
      const text = await blob.text()
      sourceFileContent.value = text
      sourceFileLines.value = text.split('\n')
    } else if (ext === 'docx') {
      const arrayBuffer = await blob.arrayBuffer()
      const result = await mammoth.convertToHtml({ arrayBuffer })
      sourceFileContent.value = sanitizeRenderedHtml(result.value)
      sourceFileHtml.value = true
    } else if (ext === 'xlsx' || ext === 'xls' || ext === 'csv') {
      const arrayBuffer = await blob.arrayBuffer()
      const workbook = XLSX.read(arrayBuffer, { type: 'array' })
      const firstSheet = workbook.SheetNames[0]
      sourceFileContent.value = sanitizeRenderedHtml(XLSX.utils.sheet_to_html(workbook.Sheets[firstSheet], { id: 'xlsx-preview-table' }))
      sourceFileHtml.value = true
    } else if (ext === 'ppt' || ext === 'pptx' || ext === 'ppsx') {
      sourceFilePptxBuffer.value = await blob.arrayBuffer()
    } else if (ext === 'png' || ext === 'jpg' || ext === 'jpeg' || ext === 'gif' || ext === 'svg' || ext === 'webp' || ext === 'bmp') {
      // 校验字节头，内容与扩展名不符时走下载兜底，避免裂图
      const kind = await sniffBlobKind(blob)
      if (ext === 'svg' || kind === 'image') {
        sourceFileBlobUrl.value = URL.createObjectURL(blob)
      } else {
        sourceFileBinary.value = blob
      }
    } else if (ext === 'pdf') {
      // 校验 %PDF 字节头，避免把非 PDF 内容喂给浏览器查看器（报「未能加载 PDF 文档」）
      if ((await sniffBlobKind(blob)) === 'pdf') {
        const typedBlob = new Blob([blob], { type: 'application/pdf' })
        sourceFileBlobUrl.value = URL.createObjectURL(typedBlob)
        sourceFileIsPdf.value = true
      } else {
        sourceFileBinary.value = blob
      }
    } else {
      // 未知扩展名：按字节头分流，二进制走下载兜底，避免乱码
      const kind = await sniffBlobKind(blob)
      if (kind === 'pdf') {
        const typedBlob = new Blob([blob], { type: 'application/pdf' })
        sourceFileBlobUrl.value = URL.createObjectURL(typedBlob)
        sourceFileIsPdf.value = true
      } else if (kind === 'binary') {
        sourceFileBinary.value = blob
      } else {
        const text = await blob.text()
        sourceFileContent.value = text
        // 按行拆分，保留原始内容（包括空行）
        sourceFileLines.value = text.split('\n')
      }
    }
  } catch (err: any) {
    sourceFileContent.value = t('docs.loadFileFailed', { message: err?.message || t('common.error') })
  } finally {
    sourceFileLoading.value = false
    dlProgress.done()
  }

  // 滚动到 start_line 位置（纯文本/代码文件）
  if (sourceFileLines.value.length) {
    await nextTick()
    const el = sourceFileContentEl.value
    if (el) {
      const target = el.querySelector<HTMLElement>(`[data-line="${sourceFileStartLine.value}"]`)
      if (target) {
        // 用 getBoundingClientRect 相对差值计算偏移，避免 offsetTop 受定位祖先影响导致定位偏上
        const delta = target.getBoundingClientRect().top - el.getBoundingClientRect().top
        el.scrollTop += delta - 80
      }
    }
  }
}

// ── 已知标签/类型集合（用于图例）──
const codeLabels = computed(() => {
  const set = new Set<string>()
  codeNodes.forEach((n) => set.add(n.label || ''))
  return [...set].sort()
})

const codeCategories = computed(() =>
  codeLabels.value.map((l) => ({
    name: labelZh(l) || '?',
    itemStyle: { color: getCodeNodeColor(l) },
  })),
)

const kgTypes = computed(() => {
  const set = new Set<string>()
  kgNodes.forEach((n) => set.add(n.type || '实体'))
  const order = Object.keys(KG_COLORS)
  return order.filter((t) => set.has(t))
})

const kgCategories = computed(() =>
  kgTypes.value.map((t) => ({
    name: t,
    itemStyle: { color: getKgNodeColor(t) },
  })),
)

// ── 选中节点相关边 ──
const selectedCodeEdges = computed(() => {
  if (selectedCodeId.value == null) return []
  const edges: CodeGraphEdge[] = []
  codeEdges.forEach((e) => {
    if (e.source === selectedCodeId.value || e.target === selectedCodeId.value) edges.push(e)
  })
  return edges
})

const selectedKgEdges = computed(() => {
  if (kgSelectedId.value == null) return []
  const edges: KgEdge[] = []
  kgEdges.forEach((e) => {
    if (e.source === kgSelectedId.value || e.target === kgSelectedId.value) edges.push(e)
  })
  return edges
})

// 选中节点的邻居 ID 集合
const selectedCodeNeighbors = computed(() => {
  const ids = new Set<number>()
  if (selectedCodeId.value == null) return ids
  ids.add(selectedCodeId.value)
  codeEdges.forEach((e) => {
    if (e.source === selectedCodeId.value) ids.add(e.target)
    if (e.target === selectedCodeId.value) ids.add(e.source)
  })
  return ids
})

const selectedKgNeighbors = computed(() => {
  const ids = new Set<string>()
  if (kgSelectedId.value == null) return ids
  ids.add(kgSelectedId.value)
  kgEdges.forEach((e) => {
    if (e.source === kgSelectedId.value) ids.add(e.target)
    if (e.target === kgSelectedId.value) ids.add(e.source)
  })
  return ids
})

// ── ECharts option ──
const chartOption = computed(() => {
  if (mode.value === 'kg') return buildKgOption()
  return buildCodeOption()
})

function buildCodeOption(): EChartsOption {
  const catIdx: Record<string, number> = {}
  codeCategories.value.forEach((c, i) => (catIdx[c.name] = i))

  const ns: Record<string, unknown>[] = []
  codeNodes.forEach((n) => {
    const deg = n.deg_local || 0
    ns.push({
      id: String(n.id),
      nid: n.id,
      name: (n.name || '').length > 24 ? (n.name || '').slice(0, 24) + '\u2026' : (n.name || '#' + n.id),
      category: catIdx[n.label || '?'] ?? 0,
      symbolSize: n.matched ? 46 : Math.min(14 + deg * 3, 30),
      itemStyle: n.matched
        ? { borderColor: '#fff', borderWidth: 3, opacity: 1 }
        : {
            opacity:
              selectedCodeId.value != null && !selectedCodeNeighbors.value.has(n.id) ? 0.2 : 1,
          },
      label: {
        show: !!n.matched || deg >= 4,
        fontSize: 11,
        color: '#334155',
        position: 'right' as const,
      },
    })
  })

  const es: Record<string, unknown>[] = []
  codeEdges.forEach((e) => {
    es.push({
      source: String(e.source),
      target: String(e.target),
      lineStyle: {
        color: '#94a3b8',
        width: 1.2,
        curveness: 0.12,
        type: e.type === 'DOCUMENTS_CODE' ? 'dashed' : 'solid',
        opacity:
          selectedCodeId.value != null &&
          e.source !== selectedCodeId.value &&
          e.target !== selectedCodeId.value
            ? 0.15
            : 0.6,
      },
      etype: e.type,
      ref_name: e.ref_name,
      ref_line: e.line,
    })
  })

  return {
    backgroundColor: 'transparent',
    tooltip: {
      backgroundColor: '#fff',
      borderColor: '#e2e8f0',
      textStyle: { color: '#1e293b' },
      formatter: (p: unknown) => {
        const params = p as { dataType?: string; data?: Record<string, unknown> }
        if (params.dataType === 'edge') {
          return (
            esc(String(params.data?.etype || '')) +
            (params.data?.ref_name
              ? '<br/>' + t('graph.refName') + ': ' + esc(String(params.data.ref_name)) +
                (params.data?.ref_line ? ' @' + t('graph.line') + String(params.data.ref_line) : '')
              : '')
          )
        }
        const n = codeNodes.get((params.data as { nid?: number })?.nid as number)
        if (!n) return ''
        return (
          '<b>' + esc(n.name) + '</b> [' + esc(labelZh(n.label)) + ']' +
          (n.file_path
            ? '<br/>' + esc(n.file_path) + (n.start_line ? ':' + n.start_line : '')
            : '')
        )
      },
    },
    legend: [
      {
        data: codeCategories.value.map((c) => c.name),
        textStyle: { color: '#64748b', fontSize: 11 },
        top: 8,
        type: 'scroll',
      },
    ],
    series: [
      {
        type: 'graph',
        layout: 'force',
        roam: true,
        draggable: true,
        categories: codeCategories.value,
        data: ns,
        edges: es as never[],
        force: {
          repulsion: 260,
          edgeLength: [60, 140],
          gravity: 0.08,
          layoutAnimation: false,
        },
        edgeSymbol: ['none', 'arrow'],
        edgeSymbolSize: 7,
        emphasis: {
          focus: 'adjacency',
          lineStyle: { width: 2.5, color: '#475569' },
          label: { show: true },
        },
      },
    ],
  } as EChartsOption
}

function buildKgOption(): EChartsOption {
  const deg = new Map<string, number>()
  kgEdges.forEach((e) => {
    deg.set(e.source, (deg.get(e.source) || 0) + 1)
    deg.set(e.target, (deg.get(e.target) || 0) + 1)
  })

  const catIdx: Record<string, number> = {}
  kgCategories.value.forEach((c, i) => (catIdx[c.name] = i))

  const ns: Record<string, unknown>[] = []
  kgNodes.forEach((n) => {
    const d = deg.get(n.id) || 0
    ns.push({
      id: n.id,
      nid: n.id,
      name: (n.name || '').length > 24 ? (n.name || '').slice(0, 24) + '\u2026' : n.name,
      category: catIdx[n.type || '实体'] ?? (catIdx['实体'] ?? 0),
      symbolSize: n.matched ? 46 : Math.min(14 + d * 3, 30),
      itemStyle: n.matched
        ? { borderColor: '#fff', borderWidth: 3, opacity: 1 }
        : {
            opacity:
              kgSelectedId.value != null && !selectedKgNeighbors.value.has(n.id) ? 0.2 : 1,
          },
      label: {
        show: !!n.matched || d >= 3,
        fontSize: 11,
        color: '#334155',
        position: 'right' as const,
      },
    })
  })

  const es: Record<string, unknown>[] = []
  kgEdges.forEach((e) => {
    es.push({
      source: e.source,
      target: e.target,
      lineStyle: {
        color: '#94a3b8',
        width: 1.2,
        curveness: 0.12,
        type: e.type === 'has_content' ? 'dotted' : 'solid',
        opacity:
          kgSelectedId.value != null && e.source !== kgSelectedId.value && e.target !== kgSelectedId.value
            ? 0.15
            : 0.6,
      },
      etype: e.type,
      conf: e.confidence,
      vf: e.valid_from,
      vt: e.valid_to,
    })
  })

  return {
    backgroundColor: 'transparent',
    tooltip: {
      backgroundColor: '#fff',
      borderColor: '#e2e8f0',
      textStyle: { color: '#1e293b' },
      formatter: (p: unknown) => {
        const params = p as { dataType?: string; data?: Record<string, unknown> }
        if (params.dataType === 'edge') {
          return (
            esc(String(params.data?.etype || '')) +
            (params.data?.conf != null ? ' · ' + t('graph.confidence') + ' ' + params.data.conf : '') +
            (params.data?.vf ? '<br/>' + t('graph.validFrom') + ': ' + esc(String(params.data.vf)) : '') +
            (params.data?.vt ? '<br/>' + t('graph.validTo') + ': ' + esc(String(params.data.vt)) : '')
          )
        }
        const n = kgNodes.get((params.data as { nid?: string })?.nid as string)
        if (!n) return ''
        return (
          '<b>' + esc(n.name) + '</b> [' + esc(n.type) + ']' +
          (n.full_len > 80 ? '<br/>' + t('graph.fullNameLen') + ': ' + n.full_len : '')
        )
      },
    },
    legend: [
      {
        data: kgCategories.value.map((c) => c.name),
        textStyle: { color: '#64748b', fontSize: 11 },
        top: 8,
        type: 'scroll',
      },
    ],
    series: [
      {
        type: 'graph',
        layout: 'force',
        roam: true,
        draggable: true,
        categories: kgCategories.value,
        data: ns,
        edges: es as never[],
        force: {
          repulsion: 260,
          edgeLength: [60, 140],
          gravity: 0.08,
          layoutAnimation: false,
        },
        edgeSymbol: ['none', 'arrow'],
        edgeSymbolSize: 7,
        emphasis: {
          focus: 'adjacency',
          lineStyle: { width: 2.5, color: '#475569' },
          label: { show: true },
        },
      },
    ],
  } as EChartsOption
}

// ── 工具函数 ──
function esc(s: string) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

function cut(s: string, max = 60): string {
  return s.length > max ? s.slice(0, max) + '\u2026' : s
}

/** KG 来源追溯：提取入边中的文档名（带文件扩展名） */
function getKgSourceDocs(edges: KgEdge[], targetId: string): string[] {
  const docRe = /\.(md|pdf|docx?|xlsx?|pptx?|txt|html?|csv)$/i
  const docs = new Set<string>()
  edges.forEach((e) => {
    if (e.target === targetId && docRe.test(e.source)) docs.add(e.source)
  })
  return [...docs]
}

/** KG 来源追溯：提取入边中的知识库 ID（kb+16进制） */
function getKgSourceKbs(edges: KgEdge[], targetId: string): string[] {
  const kbRe = /^kb[0-9a-f]{20,}$/i
  const kbs = new Set<string>()
  edges.forEach((e) => {
    if (e.target === targetId && kbRe.test(e.source)) kbs.add(e.source)
  })
  return [...kbs]
}

// ── 图表事件 ──
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function onChartClick(params: any) {
  if (params?.dataType === 'node' && params?.data?.nid != null) {
    const nid = params.data.nid
    if (mode.value === 'kg') {
      kgSelectedId.value = kgSelectedId.value === nid ? null : nid
      if (kgSelectedId.value) showKgDetail(kgSelectedId.value)
    } else {
      selectedCodeId.value = selectedCodeId.value === nid ? null : nid
      if (selectedCodeId.value != null) showCodeDetail(selectedCodeId.value)
    }
  } else if (params?.dataType !== 'edge') {
    selectedCodeId.value = null
    kgSelectedId.value = null
    codeNodeDetail.value = null
    kgDetailEdges.value = []
    resetKgFragments()
  }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
async function onChartDblClick(params: any) {
  if (params?.dataType === 'node' && params?.data?.nid != null) {
    const nid = params.data.nid
    if (mode.value === 'kg') {
      await expandKg(nid)
    } else {
      await expandCode(nid)
    }
  }
}

// ── 搜索联想（POST /kg/graph-suggest，输入防抖 250ms）──
const searchWrapRef = ref<HTMLElement | null>(null)
const searchInputRef = ref<HTMLInputElement | null>(null)
const suggestOpen = ref(false)
const suggestLoading = ref(false)
const suggestItems = ref<GraphSuggestItem[]>([])
const suggestActive = ref(-1)
// 从联想中选中的实体：搜索框保持输入的联想词不回填，选中项单独展示
const pickedSuggest = ref<GraphSuggestItem | null>(null)
let suggestSeq = 0
let suggestTimer: ReturnType<typeof setTimeout> | null = null

/** 仅收起下拉，不清空候选缓存，便于再次聚焦时恢复显示 */
function closeSuggest() {
  if (suggestTimer) {
    clearTimeout(suggestTimer)
    suggestTimer = null
  }
  suggestOpen.value = false
  suggestActive.value = -1
}

watch(keyword, (v) => {
  if (suggestTimer) clearTimeout(suggestTimer)
  // 关键词变化即开始新的联想，清除上一次的选中状态
  pickedSuggest.value = null
  const q = v.trim()
  if (!q || !selectedKbId.value) {
    suggestItems.value = []
    closeSuggest()
    return
  }
  suggestTimer = setTimeout(() => void fetchSuggest(q), 250)
})

/** 聚焦搜索框时，若当前关键词有候选缓存则重新展示，可重新选择 */
function onSearchFocus() {
  if (keyword.value.trim() && selectedKbId.value && suggestItems.value.length) {
    suggestOpen.value = true
  }
}

async function fetchSuggest(q: string, autoOpen = true) {
  const reqId = ++suggestSeq
  suggestLoading.value = true
  try {
    const res = await graphSuggest(selectedKbId.value, q, 10)
    if (reqId !== suggestSeq) return
    suggestItems.value = res.suggestions || []
    suggestActive.value = -1
    // autoOpen：输入联想时打开下拉；false：仅刷新缓存（切换知识库后后台重拉）
    if (autoOpen) suggestOpen.value = suggestItems.value.length > 0
    else suggestOpen.value = false
  } catch {
    if (reqId === suggestSeq) closeSuggest()
  } finally {
    if (reqId === suggestSeq) suggestLoading.value = false
  }
}

async function pickSuggest(item: GraphSuggestItem) {
  // 搜索框值不变，仅记录选中项并按其渲染图谱；再次聚焦搜索框可重新选择
  pickedSuggest.value = item
  closeSuggest()
  // 选中后让搜索框失焦，光标不再停留在输入框
  searchInputRef.value?.blur()
  await doSearch(item.name)
}

function onSuggestKeydown(e: KeyboardEvent) {
  if (!suggestOpen.value || !suggestItems.value.length) return
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    suggestActive.value = (suggestActive.value + 1) % suggestItems.value.length
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    suggestActive.value =
      (suggestActive.value - 1 + suggestItems.value.length) % suggestItems.value.length
  } else if (e.key === 'Enter') {
    if (suggestActive.value >= 0) {
      e.preventDefault()
      void pickSuggest(suggestItems.value[suggestActive.value])
    }
  } else if (e.key === 'Escape') {
    closeSuggest()
  }
}

function onDocClick(e: MouseEvent) {
  if (searchWrapRef.value && !searchWrapRef.value.contains(e.target as Node)) closeSuggest()
}
onMounted(() => document.addEventListener('click', onDocClick))
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
  if (suggestTimer) clearTimeout(suggestTimer)
})

async function doSearch(q: string) {
  // 重置
  codeNodes.clear()
  codeEdges.clear()
  kgNodes.clear()
  kgEdges.clear()
  candidates.value = []
  selectedCodeId.value = null
  kgSelectedId.value = null
  codeNodeDetail.value = null
  kgDetailEdges.value = []
  resetKgFragments()
  codeCenterId.value = null
  kgCenter.value = null
  kgCount.value = 0
  kgDown.value = null

  const reqId = ++searchSeq
  loading.value = true
  hasSearched.value = true

  try {
    const [codeRes, kgRes] = await Promise.all([
      searchCodeGraph(selectedKbId.value, q, 120).catch(() => null),
      searchDocGraph(q, selectedKbId.value).catch(() => null),
    ])
    if (reqId !== searchSeq) return

    // 处理代码图谱结果
    if (codeRes) {
      mergeCode(codeRes)
      codeElapsed.value = codeRes.elapsed_ms
      if (codeRes.hint) toast.info(codeRes.hint)
      if (codeRes.center) codeCenterId.value = codeRes.center.id
      candidates.value = codeRes.candidates || []
    }

    // 处理 KG 结果
    if (kgRes) {
      if (kgRes.error) {
        kgDown.value = kgRes.error
      } else {
        mergeKg(kgRes)
        kgCount.value = kgRes.edges?.length || 0
        kgElapsed.value = kgRes.elapsed_ms
        if (kgCount.value) kgCenter.value = q
      }
    }

    // 文档意图二次匹配：代码图谱中心是 Document 但 KG 未命中 → 用文档全名再查
    const docExt = /\.(md|pdf|docx?|xlsx?|pptx?|txt|html?|csv)$/i
    const docCenter =
      codeRes?.center &&
      (codeRes.center.label === 'Document' || docExt.test(codeRes.center.name))
        ? codeRes.center
        : null
    if (docCenter && kgCount.value === 0 && docCenter.name && docCenter.name !== q) {
      const kg2 = await searchDocGraph(docCenter.name, selectedKbId.value).catch(() => null)
      if (reqId !== searchSeq) return
      if (kg2 && !kg2.error && (kg2.edges || []).length > 0) {
        mergeKg(kg2)
        kgCount.value = kg2.edges?.length || 0
        kgElapsed.value = kg2.elapsed_ms
      }
    }

    // 不自动切换视图：保持当前所在图谱，仅选中当前视图的中心节点
    if (mode.value === 'kg') {
      if (kgCenter.value && kgNodes.has(kgCenter.value)) {
        kgSelectedId.value = kgCenter.value
        showKgDetail(kgCenter.value)
      }
    } else if (codeRes?.center) {
      selectedCodeId.value = codeRes.center.id
      showCodeDetail(codeRes.center.id)
    }
  } catch (err: unknown) {
    if (reqId !== searchSeq) return
    cancelPendingToast()
    const message = err instanceof Error ? err.message : t('graph.searchFailed')
    toast.error(message || t('graph.searchFailed'))
  } finally {
    if (reqId === searchSeq) loading.value = false
  }
}

// ── 候选栏点击 ──
async function onCandidateClick(name: string) {
  codeNodes.clear()
  codeEdges.clear()
  selectedCodeId.value = null
  codeNodeDetail.value = null
  try {
    const d = await searchCodeGraph(selectedKbId.value, name, 120)
    mergeCode(d)
    codeElapsed.value = d.elapsed_ms
    candidates.value = d.candidates || []
    if (d.hint) toast.info(d.hint)
    if (d.center) {
      codeCenterId.value = d.center.id
      selectedCodeId.value = d.center.id
      showCodeDetail(d.center.id)
    }
  } catch (err: unknown) {
    cancelPendingToast()
    toast.error((err as { message?: string })?.message || t('graph.searchFailed'))
  }
}

// ── 合并数据 ──
function mergeCode(d: { nodes?: CodeGraphNode[]; edges?: CodeGraphEdge[] }) {
  ;(d.nodes || []).forEach((n) => {
    codeNodes.set(n.id, n)
  })
  ;(d.edges || []).forEach((e) => {
    const k = `${e.source}|${e.target}|${e.type}`
    if (!codeEdges.has(k)) codeEdges.set(k, e)
  })
}

function mergeKg(d: { nodes?: KgNode[]; edges?: KgEdge[] }) {
  ;(d.nodes || []).forEach((n) => {
    const ex = kgNodes.get(n.id)
    if (!ex) {
      const nodeMap = kgNodes
      nodeMap.set(n.id, n)
    } else if (n.matched) {
      ex.matched = true
    }
  })
  ;(d.edges || []).forEach((e) => {
    const k = `${e.source}|${e.target}|${e.type}`
    if (!kgEdges.has(k)) {
      const edgeMap = kgEdges
      edgeMap.set(k, e)
    }
  })
}

// ── 双击展开 ──
async function expandCode(nid: number) {
  const n = codeNodes.get(nid)
  if (!n) return
  try {
    const d = await searchCodeGraph(selectedKbId.value, n.name, 80, 0)
    mergeCode(d)
  } catch (err: unknown) {
    cancelPendingToast()
    toast.error((err as { message?: string })?.message || t('graph.searchFailed'))
  }
}

async function expandKg(nid: string) {
  const n = kgNodes.get(nid)
  if (!n) return
  if ((n.full_len || n.name.length) > 200) {
    toast.info(t('graph.longTextNoExpand'))
    return
  }
  try {
    const d = await searchDocGraph(nid, selectedKbId.value)
    if (d.error) {
      toast.error(d.error)
      return
    }
    mergeKg(d)
    kgCount.value = kgEdges.size
  } catch (err: unknown) {
    cancelPendingToast()
    toast.error((err as { message?: string })?.message || t('graph.searchFailed'))
  }
}

// ── 节点详情 ──
async function showCodeDetail(nid: number) {
  const base = codeNodes.get(nid)
  if (!base) return
  codeDetailLoading.value = true
  codeNodeDetail.value = null
  try {
    const d = await getCodeNodeDetail(
      selectedKbId.value,
      base.name,
      base.id,
      undefined,
      true,
    )
    codeNodeDetail.value = d.node
  } catch (err: unknown) {
    cancelPendingToast()
    toast.error((err as { message?: string })?.message || t('graph.searchFailed'))
  } finally {
    codeDetailLoading.value = false
  }
}

async function showKgDetail(nid: string) {
  const base = kgNodes.get(nid)
  if (!base) return
  kgDetailLoading.value = true
  kgDetailEdges.value = []
  resetKgFragments()
  if ((base.full_len || base.name.length) > 200) {
    kgDetailLoading.value = false
    return
  }
  try {
    const d = await searchDocGraph(nid, selectedKbId.value)
    if (d.error) {
      toast.error(d.error)
      return
    }
    kgDetailEdges.value = d.edges || []
  } catch (err: unknown) {
    cancelPendingToast()
    toast.error((err as { message?: string })?.message || t('graph.searchFailed'))
  } finally {
    kgDetailLoading.value = false
  }
}

// ── KG 来源跳转 ──
/** 加载文档图谱数据（不切换视图，hasSearched 置位以隐藏空态占位） */
async function loadKgData(name: string) {
  kgNodes.clear()
  kgEdges.clear()
  kgSelectedId.value = null
  kgDetailEdges.value = []
  resetKgFragments()
  kgCount.value = 0
  hasSearched.value = true
  loading.value = true
  try {
    const d = await searchDocGraph(name, selectedKbId.value)
    if (d.error) {
      toast.error(d.error)
      return
    }
    mergeKg(d)
    kgCount.value = d.edges?.length || 0
    kgCenter.value = name
    if (d.center && kgNodes.has(d.center.id)) {
      kgSelectedId.value = d.center.id
      showKgDetail(d.center.id)
    }
  } catch (err: unknown) {
    cancelPendingToast()
    toast.error((err as { message?: string })?.message || t('graph.searchFailed'))
  } finally {
    loading.value = false
  }
}

/** 显式跳转文档图谱（来源跳转按钮）：切换视图 + 加载数据 */
async function kgJump(name: string) {
  mode.value = 'kg'
  await loadKgData(name)
}

// ── 知识库变更 ──
// 仅真正换成其他知识库时触发清空与重载；选中当前同一知识库（值未变）时保留搜索关键词、图谱与节点详情
watch(selectedKbId, (newKb, oldKb) => {
  if (!oldKb || newKb === oldKb) return
  void onKbChange()
})

async function onKbChange() {
  closeSuggest()
  suggestItems.value = []
  pickedSuggest.value = null
  // 切换知识库后清空查询关键词
  keyword.value = ''
  codeNodes.clear()
  codeEdges.clear()
  kgNodes.clear()
  kgEdges.clear()
  candidates.value = []
  selectedCodeId.value = null
  kgSelectedId.value = null
  codeNodeDetail.value = null
  kgDetailEdges.value = []
  resetKgFragments()
  codeCenterId.value = null
  kgCenter.value = null
  kgCount.value = 0
  kgDown.value = null
  codeElapsed.value = 0
  kgElapsed.value = 0
  // 与代码图谱切换知识库行为一致：两种视图均仅清空图谱展示，不调用 /kg/graph-kg 接口
  // （用户需重新搜索联想并选中候选后才加载对应知识库的图谱数据）
}

// ── 生命周期 ──
onMounted(async () => {
  try {
    kbOptions.value = await getKbOptions()
    if (!selectedKbId.value && kbOptions.value.length)
      selectedKbId.value = kbOptions.value[0].kbId
  } catch {
    kbOptions.value = []
  }
})
</script>

<template>
  <div class="space-y-6">
    <div>
      <h1 class="text-2xl font-bold tracking-tight">{{ t('graph.title') }}</h1>
      <p class="text-sm text-muted-foreground mt-1">{{ t('graph.subtitle') }}</p>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-5 gap-6 lg:h-[calc(100vh-16rem)] lg:min-h-[480px]">
      <!-- 左侧栏 -->
      <div class="rounded-lg border border-border bg-card">
        <div class="p-4 pb-3">
          <h3 class="text-sm font-semibold">{{ t('graph.searchDocKeyword') }}</h3>
        </div>
        <div class="px-4 pb-4 space-y-4">
          <!-- 知识库选择 -->
          <div class="space-y-2">
            <p class="text-xs text-muted-foreground">{{ t('qa.filter.kb') }}</p>
            <div class="relative">
              <Database class="pointer-events-none absolute left-2.5 top-1/2 z-10 size-3.5 -translate-y-1/2 text-primary" />
              <SelectMenu
                v-model="selectedKbId"
                :placeholder="t('graph.selectKb')"
                :options="[
                  { value: '', label: t('graph.selectKb') },
                  ...kbOptions.map((kb) => ({ value: kb.kbId, label: kb.kbName || kb.kbId })),
                ]"
                trigger-class="h-8 pl-8 pr-2 text-xs"
              />
            </div>
          </div>

          <div class="border-t border-border"></div>

          <!-- 搜索 -->
          <div ref="searchWrapRef" class="space-y-2 relative">
            <form
              class="relative flex h-8 items-center rounded-md border border-border bg-background overflow-visible focus-within:border-ring focus-within:ring-2 focus-within:ring-ring/20"
              @submit.prevent
            >
              <Search class="ml-2.5 size-3.5 shrink-0 text-muted-foreground" />
              <input
                ref="searchInputRef"
                v-model="keyword"
                type="text"
                :placeholder="t('graph.searchDocKeyword')"
                class="flex-1 min-w-0 h-full bg-transparent px-2 text-xs outline-none placeholder:text-muted-foreground"
                @focus="onSearchFocus"
                @keydown="onSuggestKeydown"
              />
            </form>
            <!-- 当前选中的联想实体（搜索框保持输入的联想词，聚焦可重新选择） -->
            <div v-if="pickedSuggest" class="flex items-center gap-1.5 min-w-0 text-xs">
              <span class="shrink-0 text-muted-foreground">{{ t('graph.suggestPicked') }}</span>
              <span
                :class="[
                  'shrink-0 rounded px-1 py-px text-[10px] leading-4',
                  pickedSuggest.source === 'kg'
                    ? 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400'
                    : 'bg-primary/10 text-primary',
                ]"
              >{{ pickedSuggest.source === 'kg' ? t('graph.suggestDoc') : t('graph.suggestCode') }}</span>
              <span class="truncate font-medium" :title="pickedSuggest.name">{{ pickedSuggest.name }}</span>
            </div>
            <!-- 联想下拉（双源候选）：加载态 / 候选列表 -->
            <div
              v-if="suggestOpen || suggestLoading"
              class="absolute left-0 right-0 top-full mt-1 z-50 rounded-md border border-border bg-card shadow-lg overflow-hidden"
            >
              <div v-if="suggestLoading" class="flex items-center gap-1.5 px-2.5 py-2 text-xs text-muted-foreground">
                <Loader2 class="size-3.5 animate-spin" />
                <span>{{ t('common.loading') }}</span>
              </div>
              <template v-else>
              <div
                v-for="(s, i) in suggestItems"
                :key="s.source + '-' + s.name + '-' + i"
                :class="[
                  'flex w-full items-center gap-1.5 px-2.5 py-1.5 text-left text-xs cursor-pointer transition-colors',
                  i === suggestActive ? 'bg-primary/15 text-primary' : 'hover:bg-muted/50',
                ]"
                @mousedown.prevent
                @click="pickSuggest(s)"
                @mouseenter="suggestActive = i"
              >
                <span
                  :class="[
                    'shrink-0 rounded px-1 py-px text-[10px] leading-4',
                    s.source === 'kg'
                      ? 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400'
                      : 'bg-primary/10 text-primary',
                  ]"
                >{{ s.source === 'kg' ? t('graph.suggestDoc') : t('graph.suggestCode') }}</span>
                <span class="truncate flex-1">{{ s.name }}</span>
                <span v-if="s.file" class="shrink-0 max-w-[40%] truncate text-[10px] text-muted-foreground">{{ s.file }}</span>
              </div>
              </template>
            </div>
          </div>

          <div class="border-t border-border"></div>

          <!-- 统计 -->
          <div class="space-y-2">
            <p class="text-xs text-muted-foreground">{{ t('graph.statInfo') }}</p>
            <div class="text-sm space-y-1">
              <div class="flex justify-between">
                <span class="text-muted-foreground">{{ t('graph.totalNodes') }}</span>
                <span class="font-medium">{{ mode === 'kg' ? kgNodes.size : codeNodes.size }}</span>
              </div>
              <div class="flex justify-between">
                <span class="text-muted-foreground">{{ t('graph.totalEdges') }}</span>
                <span class="font-medium">{{ mode === 'kg' ? kgEdges.size : codeEdges.size }}</span>
              </div>
              <div v-if="codeElapsed || kgElapsed" class="flex justify-between">
                <span class="text-muted-foreground">{{ t('graph.elapsed') }}</span>
                <span class="font-medium">{{ mode === 'kg' ? kgElapsed : codeElapsed }}ms</span>
              </div>
            </div>
          </div>

          <div class="border-t border-border"></div>

          <!-- 操作提示 -->
          <div class="space-y-2">
            <p class="text-xs text-muted-foreground">{{ t('graph.viewControl') }}</p>
            <p class="text-xs text-muted-foreground">
              {{ t('graph.clickHint') }}<br />
              {{ t('graph.dblclickHint') }}
            </p>
          </div>
        </div>
      </div>

      <!-- 中间图谱区域 -->
      <!-- lg 以下（如浏览器 200% 缩放时视口宽度减半）为单列堆叠，需给卡片独立高度，否则 flex-1 内容区高度坍缩为 0 -->
      <div class="lg:col-span-3 rounded-lg border border-border bg-card flex flex-col overflow-hidden h-[70vh] min-h-[420px] lg:h-auto lg:min-h-0">
        <!-- 顶栏：模式切换 + 候选栏 -->
        <div class="px-4 py-2 border-b shrink-0 flex items-center gap-2 flex-wrap">
          <h3 class="text-sm font-semibold flex items-center gap-2 mr-2">
            <Network class="size-4 text-primary" />{{ t('graph.title') }}
          </h3>

          <div class="flex rounded-md border border-border overflow-hidden">
            <button
              :class="[
                'px-3 py-1 text-xs font-medium transition-colors',
                mode === 'code' ? 'bg-primary text-primary-foreground' : 'bg-background text-muted-foreground hover:bg-muted',
              ]"
              @click="mode = 'code'"
            >
              {{ t('graph.codeGraph') }}
            </button>
            <button
              :class="[
                'px-3 py-1 text-xs font-medium transition-colors',
                mode === 'kg' ? 'bg-primary text-primary-foreground' : 'bg-background text-muted-foreground hover:bg-muted',
              ]"
              :title="kgDown || (kgCount ? t('graph.docGraph') + ' · ' + kgCount + ' ' + t('graph.edges') : t('graph.docGraph'))"
              @click="mode = 'kg'"
            >
              {{ kgDown ? t('graph.docGraphError') : (kgCount ? t('graph.docGraph') + ' · ' + kgCount + ' ' + t('graph.edges') : t('graph.docGraph')) }}
            </button>
          </div>
        </div>

        <!-- 候选栏 -->
        <div
          v-if="candidates.length > 0"
          class="px-4 py-2 border-b shrink-0 flex items-center gap-2 flex-wrap bg-muted/30"
        >
          <span class="text-xs text-muted-foreground">{{ t('graph.otherMatches') }}:</span>
          <span
            v-for="c in candidates"
            :key="c.id"
            class="px-2 py-0.5 rounded border border-border bg-background text-xs cursor-pointer hover:bg-muted transition-colors"
            @click="onCandidateClick(c.name)"
          >
            {{ cut(c.name, 30) }}
            <span class="text-muted-foreground ml-1">[{{ labelZh(c.label) }}]</span>
          </span>
        </div>

        <!-- 图谱 -->
        <div class="flex-1 p-0 overflow-hidden bg-muted/20">
          <div
            v-if="loading"
            class="w-full h-full flex items-center justify-center text-sm text-muted-foreground gap-2"
          >
            <Loader2 class="size-4 animate-spin" />{{ t('common.loading') }}
          </div>
          <div
            v-else-if="!hasSearched"
            class="w-full h-full flex items-center justify-center text-sm text-muted-foreground"
          >
            {{ t('graph.searchHint') }}
          </div>
          <div
            v-else-if="(mode === 'kg' ? kgNodes.size : codeNodes.size) === 0"
            class="w-full h-full flex items-center justify-center text-sm text-muted-foreground"
          >
            {{ t('graph.noResult') }}
          </div>
          <div v-else class="w-full h-full">
            <VChart
              ref="chartRef"
              :option="chartOption"
              autoresize
              class="w-full h-full"
              @click="onChartClick"
              @dblclick="onChartDblClick"
            />
          </div>
        </div>
      </div>

      <!-- 右侧详情面板（lg 以下单列堆叠显示，独立限高内部滚动） -->
      <div class="flex flex-col rounded-lg border border-border bg-card overflow-hidden max-h-[70vh] lg:max-h-none">
        <div class="px-4 py-3 border-b shrink-0">
          <h3 class="text-sm font-semibold">{{ t('graph.nodeDetail') }}</h3>
        </div>

        <div class="flex-1 overflow-y-auto p-4">
          <!-- 代码图谱 节点详情 -->
          <template v-if="mode === 'code' && selectedCodeId != null && codeNodes.get(selectedCodeId)">
            <div class="space-y-4">
              <!-- 基本信息 -->
              <div>
                <span
                  class="inline-block px-2 py-0.5 rounded text-xs font-semibold"
                  :style="{ backgroundColor: getCodeNodeColor(codeNodes.get(selectedCodeId)?.label), color: '#0b1626' }"
                >
                  {{ labelZh(codeNodes.get(selectedCodeId)?.label) || '?' }}
                </span>
                <h4 class="text-lg font-semibold mt-2 truncate" :title="codeNodes.get(selectedCodeId)?.name">{{ codeNodes.get(selectedCodeId)?.name }}</h4>
                <p class="text-xs text-muted-foreground mt-1 truncate" :title="codeNodes.get(selectedCodeId)?.qualified_name || ''">{{ codeNodes.get(selectedCodeId)?.qualified_name || '' }}</p>
              </div>

              <div class="border-t border-border"></div>

              <!-- 文件信息 -->
              <div class="space-y-1 text-sm" v-if="codeNodes.get(selectedCodeId)?.file_path">
                <div class="flex justify-between gap-2">
                  <span class="text-muted-foreground shrink-0">{{ t('graph.filePath') }}</span>
                  <span class="text-right truncate font-mono text-xs min-w-0" :title="codeNodes.get(selectedCodeId)?.file_path">{{ codeNodes.get(selectedCodeId)?.file_path }}</span>
                </div>
                <div v-if="codeNodes.get(selectedCodeId)?.start_line" class="flex justify-between gap-2">
                  <span class="text-muted-foreground shrink-0">{{ t('graph.lineRange') }}</span>
                  <span>{{ codeNodes.get(selectedCodeId)?.start_line }} &ndash; {{ codeNodes.get(selectedCodeId)?.end_line || '?' }}</span>
                </div>
                <!-- 查看原文 -->
                <div v-if="codeNodes.get(selectedCodeId)?.file_path">
                  <button
                    type="button"
                    class="inline-flex items-center gap-1 px-2.5 py-1 rounded bg-primary text-primary-foreground text-xs hover:bg-primary/90 transition-colors"
                    @click="openSourceFile(
                      selectedKbId,
                      codeNodes.get(selectedCodeId)!.file_path!,
                      codeNodes.get(selectedCodeId)!.start_line,
                      codeNodes.get(selectedCodeId)!.end_line,
                    )"
                  >
                    {{ t('graph.viewSource') }}
                  </button>
                </div>
              </div>

              <div class="border-t border-border"></div>

              <!-- 节点详情加载区 -->
              <div v-if="codeDetailLoading" class="text-sm text-muted-foreground">{{ t('common.loading') }}...</div>
              <template v-else-if="codeNodeDetail">
                <!-- 度数：入 / 出 / 本地邻接 -->
                <div class="space-y-1 text-sm">
                  <div class="flex justify-between gap-2">
                    <span class="text-muted-foreground shrink-0">{{ t('graph.degree') }}</span>
                    <span>
                      {{ t('graph.inDegreeShort') }} {{ codeNodeDetail.in_degree }}
                      · {{ t('graph.outDegreeShort') }} {{ codeNodeDetail.out_degree }}
                      <template v-if="codeNodes.get(selectedCodeId)?.deg_local != null">
                        · {{ t('graph.localNeighbors') }} {{ codeNodes.get(selectedCodeId)!.deg_local }}
                      </template>
                    </span>
                  </div>
                </div>

                <!-- 邻边类型 -->
                <div v-if="Object.keys(codeNodeDetail.out_types || {}).length || Object.keys(codeNodeDetail.in_types || {}).length">
                  <p class="text-xs text-muted-foreground mb-1">{{ t('graph.edgeTypes') }}</p>
                  <div class="flex flex-wrap gap-1">
                    <span
                      v-for="(count, type) in codeNodeDetail.out_types"
                      :key="'out-' + type"
                      class="px-1.5 py-0.5 rounded border border-border bg-background text-xs"
                    >
                      {{ type }} →{{ count }}
                    </span>
                    <span
                      v-for="(count, type) in codeNodeDetail.in_types"
                      :key="'in-' + type"
                      class="px-1.5 py-0.5 rounded border border-border bg-background text-xs"
                    >
                      ←{{ count }} {{ type }}
                    </span>
                  </div>
                </div>

                <!-- 源码 -->
                <div v-if="codeNodeDetail.source">
                  <p class="text-xs text-muted-foreground mb-1">{{ t('graph.sourceCode') }}</p>
                  <pre class="bg-muted/50 border border-border rounded-md p-2 text-xs overflow-auto max-h-80 whitespace-pre text-muted-foreground font-mono">{{ codeNodeDetail.source }}</pre>
                </div>

                <!-- 版本号（可选） -->
                <div v-if="codeNodeDetail.file_version" class="text-sm">
                  <div class="flex justify-between gap-2">
                    <span class="text-muted-foreground shrink-0">{{ t('graph.fileVersion') }}</span>
                    <span class="truncate min-w-0" :title="codeNodeDetail.file_version">{{ codeNodeDetail.file_version }}</span>
                  </div>
                </div>

                <!-- 节点属性 JSON 折叠面板 -->
                <details v-if="codeNodeDetail.props && Object.keys(codeNodeDetail.props).length" class="mt-2">
                  <summary class="cursor-pointer text-xs text-muted-foreground hover:text-foreground select-none">
                    {{ t('graph.nodePropsJson') }}
                  </summary>
                  <pre class="mt-1 bg-muted/50 border border-border rounded-md p-2 text-xs overflow-auto max-h-48 whitespace-pre text-muted-foreground">{{ JSON.stringify(codeNodeDetail.props, null, 2) }}</pre>
                </details>

                <p class="text-xs text-muted-foreground pt-1">{{ t('graph.dblclickHint') }}</p>
              </template>
            </div>
          </template>

          <!-- KG 节点详情 -->
          <template v-else-if="mode === 'kg' && kgSelectedId != null && kgNodes.get(kgSelectedId)">
            <div class="space-y-4">
              <div>
                <span
                  class="inline-block px-2 py-0.5 rounded text-xs font-semibold"
                  :style="{ backgroundColor: getKgNodeColor(kgNodes.get(kgSelectedId)?.type), color: '#0b1626' }"
                >
                  {{ kgNodes.get(kgSelectedId)?.type || t('graph.entity') }}
                </span>
                <h4 class="text-lg font-semibold mt-2 truncate" :title="kgNodes.get(kgSelectedId)?.name">{{ kgNodes.get(kgSelectedId)?.name }}</h4>
                <p class="text-xs text-muted-foreground mt-1">{{ t('graph.docGraphSystem') }}</p>
              </div>

              <div class="border-t border-border"></div>

              <!-- 全名长度 -->
              <div class="text-sm flex justify-between gap-2">
                <span class="text-muted-foreground">{{ t('graph.fullNameLen') }}</span>
                <span>{{ kgNodes.get(kgSelectedId)?.full_len || kgNodes.get(kgSelectedId)?.name?.length || 0 }} {{ t('graph.chars') }}</span>
              </div>

              <!-- 长文本节点 -->
              <div v-if="(kgNodes.get(kgSelectedId)?.full_len || kgNodes.get(kgSelectedId)?.name?.length || 0) > 200" class="text-sm text-muted-foreground">
                {{ t('graph.longTextNoDetail') }}
              </div>

              <template v-else>
                <div v-if="kgDetailLoading" class="text-sm text-muted-foreground">{{ t('common.loading') }}...</div>

                <template v-else-if="kgDetailEdges.length || kgDetailEdges.length === 0">
                  <!-- 来源追溯 -->
                  <div class="space-y-2">
                    <!-- 所属知识库 -->
                    <div v-if="getKgSourceKbs(kgDetailEdges, kgSelectedId!).length > 0">
                      <p class="text-xs text-muted-foreground mb-1">{{ t('graph.knowledgeBase') }}</p>
                      <div class="flex flex-wrap gap-1">
                        <span
                          v-for="kb in getKgSourceKbs(kgDetailEdges, kgSelectedId!)"
                          :key="kb"
                          class="px-2 py-0.5 rounded border border-border bg-background text-xs cursor-pointer hover:bg-muted transition-colors"
                          @click="kgJump(kb)"
                        >
                          {{ cut(kb, 40) }}
                        </span>
                      </div>
                    </div>

                    <!-- 来源文档 -->
                    <div v-if="getKgSourceDocs(kgDetailEdges, kgSelectedId!).length > 0">
                      <p class="text-xs text-muted-foreground mb-1">{{ t('graph.sourceDoc') }}</p>
                      <div class="flex flex-wrap gap-1">
                        <span
                          v-for="doc in getKgSourceDocs(kgDetailEdges, kgSelectedId!).slice(0, 20)"
                          :key="doc"
                          class="max-w-full truncate px-2 py-0.5 rounded border border-border bg-background text-xs cursor-pointer hover:bg-muted transition-colors"
                          :title="doc"
                          @click="kgJump(doc)"
                        >
                          {{ doc }}
                        </span>
                      </div>
                    </div>
                  </div>

                  <div class="border-t border-border"></div>

                  <!-- 关系三元组 -->
                  <div>
                    <p class="text-xs text-muted-foreground mb-1">{{ t('graph.relations') }} ({{ kgDetailEdges.length }})</p>
                    <div class="space-y-1">
                      <template v-for="(e, i) in kgDetailEdges.slice(0, 50)" :key="i">
                        <div class="text-xs px-2 py-1 rounded border border-border bg-background break-all flex items-start gap-2">
                          <span class="flex-1 min-w-0">
                            {{ cut(e.source, 30) }}
                            <span class="text-primary font-semibold">&mdash;{{ e.type }}&rarr;</span>
                            {{ cut(e.target, 30) }}
                            <span v-if="e.confidence != null" class="text-muted-foreground ml-1">· {{ t('graph.confidence') }} {{ e.confidence }}</span>
                          </span>
                          <button
                            type="button"
                            class="shrink-0 px-2 py-0.5 rounded bg-primary text-primary-foreground text-[11px] font-medium hover:bg-primary/90 transition-colors"
                            @click="toggleKgFragment(i)"
                          >
                            {{ kgFragmentStates[i]?.expanded ? t('graph.fragmentCollapse') : t('graph.fragment') }}
                          </button>
                        </div>
                        <!-- 片段展开区（POST /kg/graph-fragment） -->
                        <div v-if="kgFragmentStates[i]?.expanded" class="rounded-md border border-border bg-muted/30 p-2 space-y-1.5">
                          <div v-if="kgFragmentStates[i]?.loading" class="flex items-center gap-1.5 text-xs text-muted-foreground">
                            <Loader2 class="size-3.5 animate-spin" />{{ t('common.loading') }}
                          </div>
                          <template v-else>
                            <div v-if="kgFragmentStates[i]?.error" class="text-xs text-red-500 break-all">{{ kgFragmentStates[i]?.error }}</div>
                            <div v-else-if="!kgFragmentStates[i]?.result?.count" class="text-xs text-muted-foreground">{{ t('graph.fragmentEmpty') }}</div>
                            <template v-else>
                              <div class="text-[11px] text-muted-foreground">{{ t('graph.fragmentCount', { count: kgFragmentStates[i]?.result?.count ?? 0 }) }}</div>
                              <div
                                v-for="(f, fi) in kgFragmentStates[i]?.result?.fragments || []"
                                :key="fi"
                                class="rounded border border-border bg-background p-2 space-y-1.5"
                              >
                                <pre class="text-xs leading-relaxed whitespace-pre-wrap break-words font-sans max-h-60 overflow-auto">{{ f.text }}</pre>
                                <div class="flex items-center gap-2 flex-wrap text-[11px] text-muted-foreground">
                                  <span class="truncate min-w-0" :title="f.source_file">{{ f.source_file }}</span>
                                  <span v-if="f.chunk_index" class="shrink-0">{{ t('graph.chunkIndex') }} {{ f.chunk_index }}</span>
                                </div>
                              </div>
                            </template>
                          </template>
                        </div>
                      </template>
                      <div v-if="kgDetailEdges.length === 0" class="text-xs text-muted-foreground px-2">{{ t('graph.noRelations') }}</div>
                    </div>
                  </div>

                  <div v-if="kgDetailEdges.length > 0" class="text-xs text-muted-foreground">
                    {{ t('graph.dblclickHint') }} · {{ t('graph.clickSourceDoc') }}
                  </div>
                </template>
              </template>
            </div>
          </template>

          <!-- 空态 -->
          <div v-else class="h-full flex items-center justify-center text-center text-muted-foreground text-sm">
            <div>
              <Network class="size-10 mx-auto mb-2 opacity-30" />
              {{ t('graph.clickHint') }}
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 查看原文弹窗（同 /codemap） -->
    <Transition name="fade">
      <div
        v-if="sourceFileOpen"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
        @click.self="closeSourceFile"
      >
        <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-4xl mx-4 max-h-[85vh] flex flex-col">
          <div class="p-4 border-b border-border flex items-center justify-between shrink-0">
            <div class="flex items-center gap-2 min-w-0">
              <FileCode class="size-5 text-primary shrink-0" />
              <div class="min-w-0">
                <h3 class="font-semibold text-sm truncate">{{ sourceFileName }}</h3>
                <p class="text-[11px] text-muted-foreground truncate">{{ sourceFilePath }}</p>
              </div>
            </div>
            <button class="p-1 rounded hover:bg-accent text-muted-foreground shrink-0" @click="closeSourceFile">
              <X class="size-4" />
            </button>
          </div>
          <div ref="sourceFileContentEl" class="flex-1 min-h-0 overflow-auto p-4">
            <div v-if="sourceFileLoading" class="flex flex-col items-center justify-center py-16 gap-3">
              <Loader2 class="size-6 animate-spin text-muted-foreground" />
              <div v-if="dlProgress.state.isActive" class="w-48 max-w-[80%]">
                <div class="text-xs text-muted-foreground text-center mb-1.5">{{ dlProgress.progressLabel }}</div>
                <div v-if="dlProgress.hasProgress" class="h-1.5 rounded-full bg-muted overflow-hidden">
                  <div class="h-full bg-primary transition-all duration-200" :style="{ width: `${dlProgress.percent}%` }" />
                </div>
              </div>
            </div>
            <!-- PDF 预览（#page=N 跳转页码） -->
            <iframe
              v-else-if="sourceFileIsPdf && sourceFileSrc"
              :src="sourceFileSrc"
              class="w-full h-[70vh] border-0"
            />
            <!-- PPTX 幻灯片：pptx-preview 渲染 -->
            <div v-else-if="sourceFilePptxBuffer" class="py-2">
              <div ref="sourceFilePptxContainerRef" class="pptx-preview-container mx-auto" />
            </div>
            <!-- 不支持预览的类型：手动下载兜底 -->
            <div v-else-if="sourceFileBinary" class="flex flex-col items-center justify-center gap-3 py-16">
              <span class="text-sm text-muted-foreground">{{ t('codemap.previewUnsupported') }}</span>
              <button
                type="button"
                class="inline-flex items-center gap-1.5 h-7 px-3 rounded-md border border-border bg-background text-xs hover:bg-accent transition-colors"
                @click="downloadSourceFile"
              >
                <Download class="size-3.5" /> {{ t('common.download') }}
              </button>
            </div>
            <!-- 图片预览 -->
            <div v-else-if="sourceFileBlobUrl && !sourceFileContent" class="flex items-center justify-center">
              <img :src="sourceFileBlobUrl" :alt="sourceFileName" class="max-w-full max-h-[70vh] object-contain rounded" />
            </div>
            <!-- HTML 渲染（Word / Excel） -->
            <div v-else-if="sourceFileHtml" class="prose prose-sm max-w-none dark:prose-invert" v-html="sourceFileContent" />
            <!-- 纯文本：按行渲染，支持行号定位 -->
            <div
              v-else-if="sourceFileLines.length"
              class="text-xs font-mono leading-relaxed whitespace-pre-wrap break-words"
            >
              <div
                v-for="(line, idx) in sourceFileLines"
                :key="idx"
                :data-line="idx + 1"
                :class="[
                  'px-2 -mx-2 rounded',
                  idx + 1 >= sourceFileStartLine && idx + 1 <= sourceFileEndLine ? 'bg-yellow-200/60 dark:bg-yellow-500/25' : '',
                ]"
              >{{ line || '\u200B' }}</div>
            </div>
            <!-- 回退 -->
            <pre v-else class="text-xs font-mono whitespace-pre-wrap break-words">{{ sourceFileContent }}</pre>
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

/* markdown 渲染基础样式（同 codemap） */
:deep(.prose) :is(h1, h2, h3, h4) {
  margin-top: 1em;
  margin-bottom: 0.5em;
}
:deep(.prose) table {
  border-collapse: collapse;
  width: 100%;
  margin: 1em 0;
}
:deep(.prose) :is(td, th) {
  border: 1px solid hsl(var(--border));
  padding: 4px 8px;
}
:deep(.prose) img {
  max-width: 100%;
}
:deep(.prose) pre {
  background: hsl(var(--muted));
  padding: 8px 12px;
  border-radius: 6px;
  overflow-x: auto;
}
:deep(.prose) code {
  font-size: 0.85em;
}
</style>