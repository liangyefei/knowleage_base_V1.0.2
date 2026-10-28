// 知识图谱类型定义

// ── 代码图谱（CBM SQLite）──

/** 代码图谱节点（API 原始响应） */
export interface CodeGraphNode {
  id: number
  label: string
  name: string
  qualified_name?: string
  file_path?: string
  start_line?: number
  end_line?: number
  matched?: boolean
  deg_local?: number
}

/** 代码图谱边（API 原始响应） */
export interface CodeGraphEdge {
  source: number
  target: number
  type: string
  ref_name?: string
  line?: number
}

/** 代码图谱节点详情（/graph-node 响应） */
export interface CodeNodeDetail {
  id: number
  label: string
  name: string
  file_path?: string
  start_line?: number
  end_line?: number
  in_degree: number
  out_degree: number
  out_types: Record<string, number>
  in_types: Record<string, number>
  source?: string
  file_version?: string
  props?: Record<string, unknown>
}

// ── 文档知识图谱（mempalace KG）──

/** KG 节点 */
export interface KgNode {
  id: string
  name: string
  full_len: number
  type: string
  matched: boolean
}

/** KG 边 */
export interface KgEdge {
  source: string
  target: string
  type: string
  label: string
  confidence: number
  valid_from: string | null
  valid_to: string | null
  current: boolean
  hint: string | null
}

// ── 文档图谱关系片段（POST /kg/graph-fragment）──

/** 片段（关系来源文档中同时包含主体与客体——退化为含其一——的 chunk 文本） */
export interface GraphFragmentItem {
  /** 片段文本（超 2000 字符截断） */
  text: string
  /** 模型侧容器内全路径，仅透传展示 */
  source_file: string
  /** 来源文档名（basename） */
  room: string
  /** 片段在文档中的分块序号（可能为空） */
  chunk_index: string | null
  /** 平台侧追加的 /kg/doc-view 跳转地址；基址缺省时为 null（前端隐藏链接） */
  doc_view_url: string | null
}

/** 关系片段查询结果（count=0 为数据真值非错误；error 存在表示 mempalace 不可达等异常） */
export interface GraphFragmentResult {
  subject: string
  predicate: string | null
  object: string | null
  count: number
  fragments: GraphFragmentItem[] | null
  elapsed_ms: number | null
  error?: string
}