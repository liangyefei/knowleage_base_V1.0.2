import { computed, type Ref, type ComputedRef } from 'vue'
import type { DiffLine } from '@/services/knowledge.service'

export interface DiffMarker {
  /** 距顶部百分比（基于总行数） */
  top: number
  /** 高度百分比（基于总行数） */
  height: number
  type: 'added' | 'removed' | 'modified'
  /** 在此块中第一行的索引（0-based），用于滚动定位 */
  lineIndex: number
}

export type FlatDiffLineType = 'added' | 'removed' | 'modified' | 'normal'

/**
 * 将 DiffLine[] 归类为行类型，严格按接口 op：
 * ADDED → added、REMOVED → removed、MODIFIED → modified、SAME → normal。
 * 不做「删+增块合并为修改」的处理——后端 stats 按 op 统计，
 * 若前端把删/增块染成修改色，会导致顶部统计与内容高亮数量对不上。
 */
function buildFlatLineTypes(lines: DiffLine[]): FlatDiffLineType[] {
  return lines.map((line) => {
    if (line.op === 'MODIFIED') return 'modified'
    if (line.op === 'REMOVED') return 'removed'
    if (line.op === 'ADDED') return 'added'
    return 'normal'
  })
}

/**
 * 为版本对比页面提供固定定位的滚动条颜色标记
 *
 * @param containerRef 滚动容器的 template ref
 * @param linesSource 当前展示的 DiffLine[]（通常为 fullLines）
 */
export function useDiffScrollMarkers(
  containerRef: Ref<HTMLElement | null>,
  linesSource: ComputedRef<DiffLine[]>,
) {
  const lineTypes = computed<FlatDiffLineType[]>(() => buildFlatLineTypes(linesSource.value))

  /**
   * 标记列表：基于 hunks 数据纯计算，不依赖 DOM
   * 相邻同类型的行合并为一个色块
   */
  const markers = computed<DiffMarker[]>(() => {
    const types = lineTypes.value
    if (!types.length) return []

    const total = types.length
    const result: DiffMarker[] = []

    for (let i = 0; i < types.length; i++) {
      const t = types[i]
      if (t === 'normal') continue

      const last = result[result.length - 1]
      if (last && last.type === t) {
        // 合并：延长 height
        last.height = ((i + 1) / total) * 100 - last.top
      } else {
        result.push({
          type: t,
          top: (i / total) * 100,
          height: (1 / total) * 100,
          lineIndex: i,
        })
      }
    }

    // 确保最小可见高度
    for (const m of result) {
      if (m.height < 0.3) m.height = 0.3
    }

    return result
  })

  /**
   * 「修改」行涉及的源文件行号集合（split 模式旧表格 / unified 模式左侧使用）
   */
  const modifiedSourceLineNumbers = computed<Set<number>>(() => {
    const set = new Set<number>()
    const lines = linesSource.value
    const types = lineTypes.value
    for (let i = 0; i < lines.length; i += 1) {
      if (types[i] !== 'modified') continue
      const no = lines[i].sourceLineNo
      if (no != null) set.add(no)
    }
    return set
  })

  /**
   * 「修改」行涉及的目标文件行号集合（split 模式新表格 / unified 模式右侧使用）
   */
  const modifiedTargetLineNumbers = computed<Set<number>>(() => {
    const set = new Set<number>()
    const lines = linesSource.value
    const types = lineTypes.value
    for (let i = 0; i < lines.length; i += 1) {
      if (types[i] !== 'modified') continue
      const no = lines[i].targetLineNo
      if (no != null) set.add(no)
    }
    return set
  })

  /**
   * 纯「新增」块涉及的目标文件行号集合（split 模式新表格 / unified 模式右侧使用）
   */
  const addedTargetLineNumbers = computed<Set<number>>(() => {
    const set = new Set<number>()
    const lines = linesSource.value
    const types = lineTypes.value
    for (let i = 0; i < lines.length; i += 1) {
      if (types[i] !== 'added') continue
      const no = lines[i].targetLineNo
      if (no != null) set.add(no)
    }
    return set
  })

  /**
   * 纯「删除」块涉及的源文件行号集合（split 模式旧表格 / unified 模式左侧使用）
   */
  const removedSourceLineNumbers = computed<Set<number>>(() => {
    const set = new Set<number>()
    const lines = linesSource.value
    const types = lineTypes.value
    for (let i = 0; i < lines.length; i += 1) {
      if (types[i] !== 'removed') continue
      const no = lines[i].sourceLineNo
      if (no != null) set.add(no)
    }
    return set
  })

  /** 点击标记时滚动到对应行 */
  function scrollToMarker(marker: DiffMarker) {
    const container = containerRef.value
    if (!container) return

    // 根据 lineIndex 在 DOM 中找到对应的 tr
    const rows = container.querySelectorAll<HTMLElement>('tr[data-line]')
    if (!rows.length) return

    // 去重：split 模式跳过左半边
    const seenLines = new Set<string>()
    const uniqueRows: HTMLElement[] = []
    for (const row of rows) {
      const lineAttr = row.getAttribute('data-line')
      if (!lineAttr) continue
      const side = row.getAttribute('data-side')
      if (side === 'left' && row.parentElement?.querySelector(`tr[data-line="${lineAttr}"][data-side="right"]`)) {
        continue
      }
      const dedupKey = `${lineAttr}|${side || 'unified'}`
      if (seenLines.has(dedupKey)) continue
      seenLines.add(dedupKey)
      uniqueRows.push(row)
    }

    const targetRow = uniqueRows[marker.lineIndex]
    if (!targetRow) return
    const top = targetRow.offsetTop - 20
    container.scrollTo({ top, behavior: 'smooth' })
  }

  return { markers, scrollToMarker, modifiedSourceLineNumbers, modifiedTargetLineNumbers, addedTargetLineNumbers, removedSourceLineNumbers }
}