// 知识库类目 — 文件夹树

/** 后端 NavTreeNode API 响应结构（GET /kb/navTree） */
export interface NavTreeNode {
  nodeId: string
  nodeType: 'KB' | 'CATEGORY'
  name: string
  kbId: string
  documentCount: number
  categoryCount: number
  hasChildren: boolean
  children: NavTreeNode[]
}

/** 前端文件夹树节点（NavTreeNode 的扁平化视图 + 本地扩展字段） */
export interface IKbFolder {
  id: string
  name: string
  parentId: string | null
  /** 后端节点 ID（nodeId），用于 API 交互 */
  nodeId: string
  /** 节点类型：KB=知识库根节点, CATEGORY=分类节点 */
  nodeType: 'KB' | 'CATEGORY'
  /** 所属知识库 ID */
  kbId: string
  /** 兼容旧版分类标识 */
  kbCategory: string
  /** 文档数量 */
  docCount: number
  /** 子分类数量（仅当 hasChildren 时有意义） */
  categoryCount: number
  /** 是否有子节点（用于懒加载判断） */
  hasChildren: boolean
  /** 子节点是否已从 API 加载 */
  childrenLoaded: boolean
  children?: IKbFolder[]
  createdAt: string
  updatedAt: string
}

/** 将 NavTreeNode 递归转换为 IKbFolder */
export function navTreeToFolders(nodes: NavTreeNode[], parentId: string | null = null): IKbFolder[] {
  return nodes.map(node => {
    const hasExistingChildren = node.children?.length > 0
    return {
      id: node.nodeId,
      name: node.name,
      parentId,
      nodeId: node.nodeId,
      nodeType: node.nodeType,
      kbId: node.kbId,
      kbCategory: node.nodeType === 'KB' ? node.kbId : node.nodeId,
      docCount: node.documentCount,
      categoryCount: node.categoryCount,
      hasChildren: node.hasChildren,
      childrenLoaded: hasExistingChildren,
      children: hasExistingChildren ? navTreeToFolders(node.children, node.nodeId) : [],
      createdAt: '',
      updatedAt: '',
    }
  })
}

export const MAX_FOLDER_DEPTH = 20

/** 递归查找文件夹 */
export function findFolderById(folders: IKbFolder[], id: string): IKbFolder | null {
  for (const f of folders) {
    if (f.id === id) return f
    if (f.children?.length) {
      const found = findFolderById(f.children, id)
      if (found) return found
    }
  }
  return null
}

/** 构建从根到指定文件夹的路径 */
export function buildFolderPath(folders: IKbFolder[], id: string): IKbFolder[] {
  const path: IKbFolder[] = []
  function walk(list: IKbFolder[], target: string): boolean {
    for (const f of list) {
      path.push(f)
      if (f.id === target) return true
      if (f.children?.length && walk(f.children, target)) return true
      path.pop()
    }
    return false
  }
  walk(folders, id)
  return path
}

// ========== 知识库列表（用于角色赋权） ==========
export interface IKnowledgeBase {
  id: string
  name: string
  category: 'faq' | 'chip' | 'solution' | 'hardware' | 'api' | 'tutorial' | 'other'
  status: 'published' | 'draft' | 'archived'
  docCount: number
  description: string
  owner: string
  createdAt: string
  updatedAt: string
  tags: string[]
}

export const MOCK_KNOWLEDGE_BASES: IKnowledgeBase[] = [
  { id: 'kb-diting', name: '谛听', category: 'chip', status: 'published', docCount: 56, description: '谛听智能视觉芯片完整技术文档，包含寄存器定义、引脚说明、SDK开发指南与示例代码', owner: '芯片架构组', createdAt: '2024-03-15 09:30', updatedAt: '2026-07-28 14:20', tags: ['谛听', 'NPU', '视觉处理', 'SoC'] },
  { id: 'kb-ws63', name: 'WS63', category: 'chip', status: 'published', docCount: 42, description: 'WS63 AIoT芯片技术文档，覆盖AI推理、视频编解码、连接与射频、性能调优等模块', owner: '无线芯片组', createdAt: '2025-06-10 09:00', updatedAt: '2026-07-30 17:20', tags: ['WS63', 'AIoT', '射频', '连接'] },
  { id: 'kb-cv610', name: 'CV610', category: 'chip', status: 'published', docCount: 38, description: 'CV610 视频处理芯片技术资料，包含IPC方案、SDK文档与硬件设计参考', owner: '视频芯片组', createdAt: '2024-09-20 14:00', updatedAt: '2026-06-25 10:45', tags: ['CV610', '视频处理', 'IPC', '硬件'] },
  { id: 'kb2', name: '常见问题FAQ', category: 'faq', status: 'published', docCount: 86, description: '开发者高频问题汇总，覆盖环境搭建、编译调试、SDK使用、性能优化等常见问题', owner: '技术支持组', createdAt: '2024-01-10 10:00', updatedAt: '2026-08-02 16:45', tags: ['FAQ', '入门', '问题排查'] },
  { id: 'kb3', name: '智能安防方案说明', category: 'solution', status: 'published', docCount: 34, description: '基于海思芯片的智能安防行业解决方案', owner: '行业方案组', createdAt: '2024-05-20 11:00', updatedAt: '2026-06-18 09:30', tags: ['安防', 'IPC', 'NVR', '方案'] },
  { id: 'kb4', name: '硬件设计资料', category: 'hardware', status: 'published', docCount: 52, description: '芯片硬件设计参考资料', owner: '硬件设计组', createdAt: '2024-02-08 14:00', updatedAt: '2026-07-10 10:15', tags: ['硬件', 'PCB', '原理图', 'EMC'] },
  { id: 'kb5', name: 'SDK API参考手册', category: 'api', status: 'published', docCount: 205, description: '海思SDK完整API参考文档', owner: 'SDK开发组', createdAt: '2023-12-01 08:00', updatedAt: '2026-08-01 11:30', tags: ['API', 'SDK', '接口说明'] },
  { id: 'kb6', name: '开发入门教程', category: 'tutorial', status: 'published', docCount: 24, description: '面向新手开发者的系列教程', owner: '开发者关系组', createdAt: '2024-04-15 13:00', updatedAt: '2026-05-20 15:00', tags: ['教程', '入门', '快速上手'] },
]
