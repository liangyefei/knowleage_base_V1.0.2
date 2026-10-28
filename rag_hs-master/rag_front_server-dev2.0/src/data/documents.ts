// EXPORTS: IDocument
import type { DocFormat } from '@/types/document'

export interface IDocument {
  id: string
  name: string
  type: DocFormat
  chipModel: string[]
  version: string
  status: 'draft' | 'reviewing' | 'published' | 'archived' | 'deprecated' | 'DEPRECATED'
  /** 版本状态的显示和操作判断都改为此字段 */
  versionStatus: 'draft' | 'reviewing' | 'published' | 'archived' | 'deprecated' | 'DEPRECATED'
  uploadTime: string
  author: string
  sensitivity: 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL' | 'TOP_SECRET' | 'public' | 'internal' | 'confidential' | 'secret'
  size: string
  chunkCount: number
  // 详情扩展字段
  summary: string
  tags: string[]
  category: string
  lastUpdated: string
  downloadCount: number
  chunkReviewed: number
  chunkPending: number
  vectorStatus: 'pending' | 'processing' | 'vectorized' | 'failed'
  relatedCodeFiles: number
  relatedRegisters: number
  relatedApis: number
  productSeries: string[]
  /** search.metadata：product / chip_model / sensitivity / official / author / tag / remark */
  metadata?: Record<string, string>
  /** 官方属性：OFFICIAL | NON_OFFICIAL */
  official?: string
  /** 实际文件扩展名（来自 API fileType 字段），如 DOCX、XLSX、PDF */
  fileType?: string
  kbCategory?: string
  /** 所属知识库 ID（对应 NavTreeNode.kbId / IKbFolder.kbId） */
  kbId?: string
  /** 所属分类节点 ID（对应 NavTreeNode.nodeId / IKbFolder.nodeId） */
  categoryId?: string
  reviewHistory?: Array<{
    id: string
    reviewer: string
    action: 'approved' | 'rejected' | 'submitted' | 'revised'
    actionLabel: string
    comment: string
    timestamp: string
    version?: string
  }>
}
