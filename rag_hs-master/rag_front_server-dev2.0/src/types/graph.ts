/**
 * 知识图谱类型
 * 从 lib/graph-types.ts 迁移
 */

export interface GraphNode {
  id: string
  label: string
  type: 'chip' | 'peripheral' | 'register' | 'api' | 'doc' | 'code'
  refId?: string
  refType?: string
  refTitle?: string
  description?: string
}

export interface GraphEdge {
  source: string
  target: string
  label?: string
}
