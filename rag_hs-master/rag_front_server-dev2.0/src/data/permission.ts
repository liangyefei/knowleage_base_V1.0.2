// EXPORTS: IRole, IAbacPolicy, IAbacAttribute, IAccount, IPageTreeNode, IPolicyCondition,
//   MOCK_ROLES, MOCK_ABAC_POLICIES, MOCK_ABAC_ATTRIBUTES, MOCK_ACCOUNTS, MOCK_PAGE_TREE,
//   Permission, DEFAULT_TOKEN_LIMIT, DOC_SECURITY_LEVELS, DocSecurityLevel,
//   AbacAttrType, AbacTargetEntity, AbacTargetResourceType, AbacPolicyEffect

export type Permission = 'read' | 'write' | 'delete' | 'review' | 'admin'
export type PolicyEffect = 'allow' | 'deny'
export type PolicyDimension = '文档类型' | '敏感等级' | '组织' | '地域' | '知识库目录' | '产品' | '芯片型号' | '软件版本' | '用户属性' | '环境属性'
export type PolicyOperator = '==' | '!=' | 'in' | 'not-in' | 'contains' | 'starts-with' | 'ends-with' | '>' | '<' | '>=' | '<='
export type PolicyResourceScope = 'all' | 'document' | 'code' | 'hardware' | 'qa' | 'graph' | 'settings'
export type PolicyAction = 'read' | 'write' | 'delete' | 'review' | 'all'

// ABAC 属性策略相关类型
export type AbacAttrType = 'STRING' | 'NUMBER' | 'ARRAY'
export type AbacTargetEntity = 'USER' | 'KB' | 'CATEGORY' | 'DOCUMENT' | 'NONE'
export type AbacTargetResourceType = 'KB' | 'CATEGORY' | 'DOCUMENT' | 'ALL'
export type AbacPolicyEffect = 'ALLOW' | 'DENY'

// 文档安全等级（用于角色可访问文档密级的赋权）
export type DocSecurityLevel = 'public' | 'internal' | 'confidential' | 'secret'
export const DOC_SECURITY_LEVELS: DocSecurityLevel[] = ['public', 'internal', 'confidential', 'secret']

export interface IPolicyCondition {
  id: string
  attribute: string
  operator: PolicyOperator
  value: string
  logic: 'AND' | 'OR'
  group?: string
}

export interface IPolicyTimeWindow {
  enabled: boolean
  startTime: string
  endTime: string
  days: number[] // 0-6, 0=周日
}

export interface IPageTreeNode {
  id: string
  name: string
  icon?: string
  children?: IPageTreeNode[]
}

export interface IRole {
  id: string
  name: string
  description: string
  permissions: Record<Permission, boolean>
  pagePermissions: Record<string, Record<Permission, boolean>>
  // 核心菜单页面ID列表：核心菜单的访问（可读）权限不可取消
  corePages: string[]
  // 角色可访问的文档安全等级
  securityLevels: DocSecurityLevel[]
  // 角色启用状态
  status: 'active' | 'disabled'
  userCount: number
  builtIn: boolean
  // 超级管理员标记：最高系统管理角色，不允许删除、停用或取消核心功能权限
  isSuperAdmin?: boolean
  // 角色可访问的知识库ID列表
  kbPermissions: string[]
  // 知识库版本级授权：kbId → 已授权版本列表（整库授权走 kbPermissions）
  kbVersions?: Record<string, string[]>
  // 角色可用的访问权限策略（对应字典 access_policy 的条目 value）
  accessPolicies?: string[]
}

export interface IAbacAttribute {
  id: string
  attrKey: string
  attrName: string
  attrType: AbacAttrType
  targetEntityTypes: AbacTargetEntity[]
  description: string
  sortOrder: number
}

export interface IAbacPolicy {
  id: string
  name: string
  kbId: string         // 所属知识库 ID，'all' 表示全部
  kbName: string       // 所属知识库名称，便于展示
  priority: number
  targetResourceType: AbacTargetResourceType
  targetOperation: string  // 如 kb:doc:read 或 *
  conditionExpr: string    // 条件表达式原文
  effect: AbacPolicyEffect
  isEnabled: boolean
  description?: string
  createdAt: string
  updatedAt: string
}

export interface IAccount {
  id: string
  username: string
  name: string
  role: string
  department: string
  departmentId: string
  email: string
  status: 'active' | 'disabled'
  createdAt: string
  // 地域
  region: string
  // 每人每日 Token 限额：page=页面问答、mcp=MCP调用问答
  tokenLimit: { page: number; mcp: number }
}

// 新账号默认每日 Token 限额（页面问答 100万 / MCP调用问答 50万）
export const DEFAULT_TOKEN_LIMIT = { page: 1_000_000, mcp: 500_000 }

// 全部菜单页面ID（与 AdminLayout 侧边栏菜单一一对应，权限控制到页面级别）
const ALL_PAGE_IDS = [
  'qa',
  'knowledge-docs', 'knowledge-approval', 'knowledge-upload', 'knowledge-chunk', 'knowledge-diff', 'codemap', 'graph',
  'quality-badcase', 'quality-hitrate', 'quality-conflict', 'quality-insights', 'quality-evaluation',
  'data-ingestion',
  'hot-questions', 'agent', 'sensitive-word', 'dict',
  'permission', 'system-role', 'system-policy', 'system-department', 'system-region', 'system-audit-log',
]

const PERM_NONE: Record<Permission, boolean> = { read: false, write: false, delete: false, review: false, admin: false }
const PERM_FULL: Record<Permission, boolean> = { read: true, write: true, delete: true, review: true, admin: true }

// 按覆盖配置生成完整页面权限表（未配置的页面默认无权限）
const buildPagePerms = (overrides: Record<string, Partial<Record<Permission, boolean>>>): Record<string, Record<Permission, boolean>> => {
  const result: Record<string, Record<Permission, boolean>> = {}
  ALL_PAGE_IDS.forEach((id) => {
    result[id] = { ...PERM_NONE, ...(overrides[id] || {}) }
  })
  return result
}

// 所有知识库 ID 列表
const ALL_KB_IDS = ['kb-diting', 'kb-ws63', 'kb-cv610', 'kb2', 'kb3', 'kb4', 'kb5', 'kb6', 'kb7', 'kb8', 'kb9', 'kb10', 'kb11', 'kb12']

// 内置角色：超级管理员/系统管理员/知识库管理员/知识库初审管理员/知识库终审管理员/内测人员/开发者
export const MOCK_ROLES: IRole[] = [
  {
    id: 'super-admin',
    name: '超级管理员',
    description: '拥有系统中全部权限',
    permissions: { ...PERM_FULL },
    pagePermissions: buildPagePerms(Object.fromEntries(ALL_PAGE_IDS.map((id) => [id, { ...PERM_FULL }]))),
    corePages: [...ALL_PAGE_IDS],
    securityLevels: ['public', 'internal', 'confidential', 'secret'],
    status: 'active',
    userCount: 2,
    builtIn: true,
    isSuperAdmin: true,
    kbPermissions: [...ALL_KB_IDS],
    accessPolicies: ['allow_qa_search', 'allow_download', 'allow_view_source', 'allow_export', 'intranet_only'],
  },
  {
    id: 'system-admin',
    name: '系统管理员',
    description: '拥有系统配置权限',
    permissions: { read: true, write: true, delete: false, review: false, admin: true },
    pagePermissions: buildPagePerms({
      qa: { read: true },
      permission: { ...PERM_FULL },
      'system-role': { ...PERM_FULL },
      'system-policy': { ...PERM_FULL },
      'system-department': { ...PERM_FULL },
      'system-region': { ...PERM_FULL },
      'system-audit-log': { ...PERM_FULL },
      'hot-questions': { read: true, write: true },
      agent: { read: true, write: true },
      'sensitive-word': { read: true, write: true },
      dict: { read: true, write: true },
    }),
    corePages: ['permission', 'system-role', 'system-policy', 'system-department', 'system-region', 'system-audit-log'],
    securityLevels: ['public', 'internal'],
    status: 'active',
    userCount: 3,
    builtIn: true,
    kbPermissions: [...ALL_KB_IDS],
    accessPolicies: ['allow_qa_search', 'allow_download', 'allow_view_source'],
  },
  {
    id: 'kb-admin',
    name: '知识库管理员',
    description: '拥有上传文件到知识库，对文档以及文档版本进行管理',
    permissions: { read: true, write: true, delete: true, review: false, admin: false },
    pagePermissions: buildPagePerms({
      qa: { read: true, write: true },
      'knowledge-docs': { read: true, write: true, delete: true, admin: true },
      'knowledge-approval': { read: true, write: true },
      'knowledge-upload': { read: true, write: true },
      'knowledge-chunk': { read: true, write: true },
      'knowledge-diff': { read: true },
      codemap: { read: true },
      graph: { read: true },
    }),
    corePages: ['knowledge-docs', 'knowledge-upload', 'knowledge-chunk', 'knowledge-diff'],
    securityLevels: ['public', 'internal', 'confidential', 'secret'],
    status: 'active',
    userCount: 5,
    builtIn: true,
    kbPermissions: [...ALL_KB_IDS],
    accessPolicies: ['allow_qa_search', 'allow_download', 'allow_view_source', 'allow_export'],
  },
  {
    id: 'kb-first-reviewer',
    name: '知识库初审管理员',
    description: '负责文档初审，审核通过后可以提交文档灰度发布',
    permissions: { read: true, write: true, delete: false, review: true, admin: false },
    pagePermissions: buildPagePerms({
      qa: { read: true },
      'knowledge-docs': { read: true },
      'knowledge-approval': { read: true, write: true, review: true },
      'knowledge-chunk': { read: true },
      'knowledge-diff': { read: true },
      'quality-conflict': { read: true },
    }),
    corePages: ['knowledge-approval'],
    securityLevels: ['public', 'internal', 'confidential', 'secret'],
    status: 'active',
    userCount: 3,
    builtIn: true,
    kbPermissions: [...ALL_KB_IDS],
    accessPolicies: ['allow_qa_search', 'allow_view_source'],
  },
  {
    id: 'kb-final-reviewer',
    name: '知识库终审管理员',
    description: '负责文档终审，审核通过后可以提交文档正式发布',
    permissions: { read: true, write: true, delete: false, review: true, admin: false },
    pagePermissions: buildPagePerms({
      qa: { read: true },
      'knowledge-docs': { read: true },
      'knowledge-approval': { read: true, write: true, review: true },
      'knowledge-chunk': { read: true },
      'knowledge-diff': { read: true },
      'quality-conflict': { read: true },
    }),
    corePages: ['knowledge-approval'],
    securityLevels: ['public', 'internal', 'confidential', 'secret'],
    status: 'active',
    userCount: 2,
    builtIn: true,
    kbPermissions: [...ALL_KB_IDS],
    accessPolicies: ['allow_qa_search', 'allow_view_source'],
  },
  {
    id: 'beta-tester',
    name: '内测人员',
    description: '用于灰度发布文件的知识问答内部测试',
    permissions: { read: true, write: true, delete: false, review: false, admin: false },
    pagePermissions: buildPagePerms({
      qa: { read: true, write: true },
      'knowledge-docs': { read: true },
      'knowledge-chunk': { read: true },
    }),
    corePages: ['qa'],
    securityLevels: ['public', 'internal'],
    status: 'active',
    userCount: 24,
    builtIn: true,
    kbPermissions: ['kb2', 'kb3', 'kb4', 'kb5'],
    kbVersions: { 'kb-diting': ['v2.1.0', 'v2.0.0'] },
    accessPolicies: ['allow_qa_search', 'allow_view_source'],
  },
  {
    id: 'developer',
    name: '开发者',
    description: '拥有知识库问答HiSpark社区用户',
    permissions: { read: true, write: false, delete: false, review: false, admin: false },
    pagePermissions: buildPagePerms({
      qa: { read: true, write: true },
    }),
    corePages: ['qa'],
    securityLevels: ['public'],
    status: 'active',
    userCount: 1286,
    builtIn: true,
    kbPermissions: ['kb2', 'kb3'],
    kbVersions: { 'kb-diting': ['v1.3.2'] },
    accessPolicies: ['allow_qa_search'],
  },
]

// 页面权限树与当前管理系统侧边栏菜单一一对应，权限控制到页面级别
export const MOCK_PAGE_TREE: IPageTreeNode[] = [
  { id: 'qa', name: '智能问答' },
  {
    id: 'knowledge',
    name: '知识库管理',
    children: [
      { id: 'knowledge-docs', name: '文档列表' },
      { id: 'knowledge-approval', name: '知识库审批' },
      { id: 'knowledge-upload', name: '文档上传' },
      { id: 'knowledge-chunk', name: '知识切片查看' },
      { id: 'knowledge-diff', name: '版本对比' },
      { id: 'codemap', name: '代码文档映射' },
      { id: 'graph', name: '知识图谱' },
    ],
  },
  {
    id: 'quality',
    name: '质量治理',
    children: [
      { id: 'quality-badcase', name: 'Badcase管理' },
      { id: 'quality-hitrate', name: '命中率' },
      { id: 'quality-conflict', name: '冲突检测' },
      { id: 'quality-insights', name: '问答智能洞察' },
      { id: 'quality-evaluation', name: '评测管理' },
    ],
  },
  {
    id: 'data-sources',
    name: '多源数据管理',
    children: [{ id: 'data-ingestion', name: '采集管理' }],
  },
  {
    id: 'config',
    name: '配置管理',
    children: [
      { id: 'hot-questions', name: '热门问题管理' },
      { id: 'agent', name: 'Agent集成' },
      { id: 'sensitive-word', name: '敏感词' },
      { id: 'dict', name: '字典管理' },
    ],
  },
  {
    id: 'system',
    name: '系统管理',
    children: [
      { id: 'permission', name: '权限管理' },
      { id: 'system-role', name: '角色管理' },
      { id: 'system-policy', name: '策略管理' },
      { id: 'system-department', name: '部门管理' },
      { id: 'system-region', name: '区域管理' },
      { id: 'system-audit-log', name: '审计日志' },
    ],
  },
]

export const MOCK_ABAC_ATTRIBUTES: IAbacAttribute[] = [
  {
    id: 'attr-1',
    attrKey: 'user.dept',
    attrName: '部门',
    attrType: 'STRING',
    targetEntityTypes: ['USER'],
    description: '用户所属部门，如研发部、市场部等',
    sortOrder: 1,
  },
  {
    id: 'attr-2',
    attrKey: 'user.region',
    attrName: '地域',
    attrType: 'STRING',
    targetEntityTypes: ['USER'],
    description: '用户所在地域，如 CN/EU/US',
    sortOrder: 2,
  },
  {
    id: 'attr-3',
    attrKey: 'user.employment_type',
    attrName: '雇佣类型',
    attrType: 'STRING',
    targetEntityTypes: ['USER'],
    description: '用户雇佣类型，如正式员工、外包、实习生',
    sortOrder: 3,
  },
  {
    id: 'attr-4',
    attrKey: 'user.clearance',
    attrName: '安全级别',
    attrType: 'NUMBER',
    targetEntityTypes: ['USER'],
    description: '用户安全密级，1~5 级，数字越高权限越大',
    sortOrder: 4,
  },
  {
    id: 'attr-5',
    attrKey: 'res.doc_type',
    attrName: '文档类型',
    attrType: 'STRING',
    targetEntityTypes: ['DOCUMENT'],
    description: '文档的业务类型，如代码、白皮书、规格书等',
    sortOrder: 5,
  },
  {
    id: 'attr-6',
    attrKey: 'res.sensitivity',
    attrName: '敏感等级',
    attrType: 'NUMBER',
    targetEntityTypes: ['KB', 'CATEGORY', 'DOCUMENT'],
    description: '资源的敏感等级，1~5 级，数字越高越敏感',
    sortOrder: 6,
  },
  {
    id: 'attr-7',
    attrKey: 'res.data_region',
    attrName: '数据地域',
    attrType: 'STRING',
    targetEntityTypes: ['DOCUMENT'],
    description: '数据归属地域，如 CN/EU/US',
    sortOrder: 7,
  },
  {
    id: 'attr-8',
    attrKey: 'res.compliance',
    attrName: '合规标签',
    attrType: 'ARRAY',
    targetEntityTypes: ['DOCUMENT'],
    description: '合规标签数组，如 GDPR/ISO27001/SOC2',
    sortOrder: 8,
  },
  {
    id: 'attr-9',
    attrKey: 'env.network',
    attrName: '网络类型',
    attrType: 'STRING',
    targetEntityTypes: ['NONE'],
    description: '当前访问的网络环境，如内网/公网/VPN',
    sortOrder: 9,
  },
]

// ========== ABAC 属性策略 ==========
export const MOCK_ABAC_POLICIES: IAbacPolicy[] = [
  {
    id: 'policy-1',
    name: '研发文档隔离',
    kbId: 'all',
    kbName: '全部知识库',
    priority: 10,
    targetResourceType: 'DOCUMENT',
    targetOperation: 'kb:doc:read',
    conditionExpr: 'res.doc_type="代码" AND user.dept="研发部"',
    effect: 'ALLOW',
    isEnabled: true,
    description: '仅研发部可以读取代码类文档',
    createdAt: '2024-03-10 10:00:00',
    updatedAt: '2024-06-15 14:30:00',
  },
  {
    id: 'policy-2',
    name: '机密文档控制',
    kbId: 'all',
    kbName: '全部知识库',
    priority: 10,
    targetResourceType: 'KB',
    targetOperation: '*',
    conditionExpr: 'res.sensitivity>=3 AND user.employment_type="正式员工"',
    effect: 'ALLOW',
    isEnabled: true,
    description: '敏感等级 >=3 的资源仅正式员工可访问',
    createdAt: '2024-02-20 09:20:00',
    updatedAt: '2024-07-01 11:00:00',
  },
  {
    id: 'policy-3',
    name: '部门数据隔离',
    kbId: 'kb-rd',
    kbName: '研发知识库',
    priority: 5,
    targetResourceType: 'CATEGORY',
    targetOperation: 'kb:doc:read',
    conditionExpr: 'res.sensitivity>1 AND user.dept=res.org',
    effect: 'ALLOW',
    isEnabled: true,
    description: '敏感等级>1 的分类目录仅同部门用户可读',
    createdAt: '2024-04-05 15:40:00',
    updatedAt: '2024-08-12 16:20:00',
  },
  {
    id: 'policy-4',
    name: '地域限制',
    kbId: 'all',
    kbName: '全部知识库',
    priority: 20,
    targetResourceType: 'DOCUMENT',
    targetOperation: '*',
    conditionExpr: 'res.data_region="EU" AND user.region!="EU"',
    effect: 'DENY',
    isEnabled: true,
    description: 'EU 数据地域的文档禁止非 EU 地域用户访问',
    createdAt: '2024-05-18 08:50:00',
    updatedAt: '2024-09-01 10:15:00',
  },
  {
    id: 'policy-5',
    name: '公网访问拒绝',
    kbId: 'all',
    kbName: '全部知识库',
    priority: 20,
    targetResourceType: 'ALL',
    targetOperation: '*',
    conditionExpr: 'env.network="公网" AND res.sensitivity>=4',
    effect: 'DENY',
    isEnabled: false,
    description: '公网环境下禁止访问敏感等级>=4 的所有资源',
    createdAt: '2024-06-22 13:10:00',
    updatedAt: '2024-09-10 09:45:00',
  },
]

// 账号：角色名同步为 7 个内置角色，补充地域与每日 Token 限额
export const MOCK_ACCOUNTS: IAccount[] = [
  { id: '1', username: 'admin', name: '系统管理员', role: '超级管理员', department: '总公司/研发中心/后端组', departmentId: '1-1-2', email: 'admin@hisilicon.com', status: 'active', createdAt: '2023-06-15', region: '深圳', tokenLimit: { page: 10_000_000, mcp: 5_000_000 } },
  { id: '2', username: 'zhangwei', name: '张伟', role: '知识库初审管理员', department: '总公司/质量部', departmentId: '1-3', email: 'zhangwei@hisilicon.com', status: 'active', createdAt: '2023-07-20', region: '深圳', tokenLimit: { page: 1_000_000, mcp: 500_000 } },
  { id: '3', username: 'liming', name: '李明', role: '知识库管理员', department: '总公司/研发中心/芯片组', departmentId: '1-1-3', email: 'liming@hisilicon.com', status: 'active', createdAt: '2023-08-05', region: '上海', tokenLimit: { page: 2_000_000, mcp: 1_000_000 } },
  { id: '4', username: 'wangfang', name: '王芳', role: '知识库管理员', department: '总公司/研发中心/前端组', departmentId: '1-1-1', email: 'wangfang@hisilicon.com', status: 'active', createdAt: '2023-09-12', region: '北京', tokenLimit: { page: 1_000_000, mcp: 500_000 } },
  { id: '5', username: 'zhaoqiang', name: '赵强', role: '开发者', department: '总公司/市场部', departmentId: '1-2', email: 'zhaoqiang@hisilicon.com', status: 'active', createdAt: '2023-10-08', region: '深圳', tokenLimit: { page: 1_000_000, mcp: 500_000 } },
  { id: '6', username: 'liuyang', name: '刘洋', role: '内测人员', department: '总公司/研发中心/前端组', departmentId: '1-1-1', email: 'liuyang@hisilicon.com', status: 'disabled', createdAt: '2023-11-15', region: '西安', tokenLimit: { page: 500_000, mcp: 200_000 } },
  { id: '7', username: 'chenjing', name: '陈静', role: '知识库初审管理员', department: '总公司/质量部', departmentId: '1-3', email: 'chenjing@hisilicon.com', status: 'active', createdAt: '2023-12-01', region: '深圳', tokenLimit: { page: 1_000_000, mcp: 500_000 } },
  { id: '8', username: 'zhoubo', name: '周博', role: '系统管理员', department: '总公司/研发中心/后端组', departmentId: '1-1-2', email: 'zhoubo@hisilicon.com', status: 'active', createdAt: '2024-01-10', region: '上海', tokenLimit: { page: 2_000_000, mcp: 1_000_000 } },
  { id: '9', username: 'sunli', name: '孙丽', role: '开发者', department: '总公司/研发中心/芯片组', departmentId: '1-1-3', email: 'sunli@hisilicon.com', status: 'disabled', createdAt: '2024-02-18', region: '成都', tokenLimit: { page: 1_000_000, mcp: 500_000 } },
  { id: '10', username: 'wujun', name: '吴军', role: '知识库管理员', department: '总公司/研发中心/芯片组', departmentId: '1-1-3', email: 'wujun@hisilicon.com', status: 'active', createdAt: '2024-03-25', region: '深圳', tokenLimit: { page: 1_000_000, mcp: 500_000 } },
  { id: '11', username: 'huangtao', name: '黄涛', role: '知识库管理员', department: '总公司/研发中心/后端组', departmentId: '1-1-2', email: 'huangtao@hisilicon.com', status: 'active', createdAt: '2024-04-18', region: '北京', tokenLimit: { page: 1_500_000, mcp: 800_000 } },
  { id: '12', username: 'xuyan', name: '徐燕', role: '知识库终审管理员', department: '总公司/质量部', departmentId: '1-3', email: 'xuyan@hisilicon.com', status: 'active', createdAt: '2024-05-06', region: '深圳', tokenLimit: { page: 1_000_000, mcp: 500_000 } },
  { id: '13', username: 'mahao', name: '马浩', role: '开发者', department: '总公司/硬件研发部', departmentId: '1-4', email: 'mahao@hisilicon.com', status: 'active', createdAt: '2024-06-12', region: '西安', tokenLimit: { page: 1_000_000, mcp: 500_000 } },
  { id: '14', username: 'zhulin', name: '朱琳', role: '开发者', department: '总公司/算法部', departmentId: '1-5', email: 'zhulin@hisilicon.com', status: 'disabled', createdAt: '2024-07-22', region: '上海', tokenLimit: { page: 1_000_000, mcp: 500_000 } },
  { id: '15', username: 'hujian', name: '胡健', role: '知识库管理员', department: '总公司/测试部', departmentId: '1-6', email: 'hujian@hisilicon.com', status: 'active', createdAt: '2024-08-30', region: '成都', tokenLimit: { page: 1_000_000, mcp: 500_000 } },
  { id: '16', username: 'guomei', name: '郭梅', role: '知识库管理员', department: '总公司/研发中心/前端组', departmentId: '1-1-1', email: 'guomei@hisilicon.com', status: 'active', createdAt: '2024-09-15', region: '深圳', tokenLimit: { page: 1_000_000, mcp: 500_000 } },
  { id: '17', username: 'linfei', name: '林飞', role: '内测人员', department: '总公司/市场部', departmentId: '1-2', email: 'linfei@hisilicon.com', status: 'active', createdAt: '2024-10-08', region: '北京', tokenLimit: { page: 500_000, mcp: 200_000 } },
  { id: '18', username: 'hebin', name: '何斌', role: '知识库终审管理员', department: '总公司/测试部', departmentId: '1-6', email: 'hebin@hisilicon.com', status: 'disabled', createdAt: '2024-11-20', region: '上海', tokenLimit: { page: 1_000_000, mcp: 500_000 } },
]
