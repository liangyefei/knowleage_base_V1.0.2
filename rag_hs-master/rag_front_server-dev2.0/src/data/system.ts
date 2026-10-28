// EXPORTS: IDepartment, IAuditLog, IMenuItem, ISensitiveWord, IRegion,
//   MOCK_DEPARTMENTS, MOCK_AUDIT_LOGS, MOCK_MENU_TREE, MOCK_SENSITIVE_WORDS,
//   SENSITIVE_CATEGORIES, flattenDepartments,
//   RegionLevel, RegionCategory, REGION_LEVELS, REGION_CATEGORIES,
//   MOCK_REGIONS, flattenRegions,
//   IDictType, IDictItem, MOCK_DICT_TYPES

export interface IDepartment {
  id: string
  name: string
  parentId: string | null
  leader: string
  phone: string
  createdAt: string
  children?: IDepartment[]
}

// 审计操作类型收窄为 5 类核心类型；操作目标（actionLabel）限定为各类型下的枚举动作
export type AuditActionType =
  | 'login' // 登录认证：登录 / 登出
  | 'document' // 文档管理：上传 / 删除 / 修改元数据
  | 'approval' // 知识库审批：提交初审 / 提交终审 / 初审驳回 / 终审驳回 / 初审通过 / 终审通过
  | 'badcase_submit' // 提交Badcase单：问答页面 / MCP调用问答 / 未命中问答 / 冲突检测
  | 'badcase_handle' // Badcase处理：按处理状态（待处理 / 处理中 / 已处理 / 不予处理）

export interface IAuditLog {
  id: string
  operator: string
  operatorId: string
  actionType: AuditActionType
  actionLabel: string
  target: string
  ip: string
  result: 'success' | 'denied'
  reason?: string
  params?: Record<string, unknown>
  timestamp: string
}

export interface IMenuItem {
  id: string
  name: string
  path: string
  icon?: string
  type: 'menu' | 'button'
  parentId: string | null
  sort: number
  status: 'enabled' | 'disabled'
  perms?: string
  remark?: string
  children?: IMenuItem[]
}

export interface ISensitiveWord {
  id: string
  word: string
  category: 'political' | 'porn' | 'violence' | 'discrimination' | 'advertising' | 'other'
  status: 'enabled' | 'disabled'
  createdAt: string
  creator: string
  remark?: string
}

export const SENSITIVE_CATEGORIES: { value: ISensitiveWord['category']; label: string }[] = [
  { value: 'political', label: '政治敏感' },
  { value: 'porn', label: '色情低俗' },
  { value: 'violence', label: '暴力恐怖' },
  { value: 'discrimination', label: '歧视侮辱' },
  { value: 'advertising', label: '广告推广' },
  { value: 'other', label: '其他' },
]

export const MOCK_SENSITIVE_WORDS: ISensitiveWord[] = [
  { id: 'sw1', word: '台湾独立', category: 'political', status: 'enabled', createdAt: '2026-06-12 09:12:34', creator: 'admin', remark: '' },
  { id: 'sw2', word: '台独势力', category: 'political', status: 'enabled', createdAt: '2026-06-12 09:12:50', creator: 'admin', remark: '' },
  { id: 'sw3', word: '疆独', category: 'political', status: 'enabled', createdAt: '2026-06-15 14:20:00', creator: 'admin', remark: '' },
  { id: 'sw4', word: '藏独', category: 'political', status: 'enabled', createdAt: '2026-06-15 14:20:15', creator: 'admin', remark: '' },
  { id: 'sw5', word: '法轮功', category: 'political', status: 'enabled', createdAt: '2026-06-20 10:05:22', creator: 'admin', remark: '' },
  { id: 'sw6', word: '色情视频', category: 'porn', status: 'enabled', createdAt: '2026-06-25 16:40:18', creator: 'admin', remark: '' },
  { id: 'sw7', word: '成人网站', category: 'porn', status: 'enabled', createdAt: '2026-06-25 16:40:33', creator: 'admin', remark: '' },
  { id: 'sw8', word: '裸聊', category: 'porn', status: 'enabled', createdAt: '2026-06-30 11:28:45', creator: 'admin', remark: '' },
  { id: 'sw9', word: '露点', category: 'porn', status: 'disabled', createdAt: '2026-07-02 08:55:10', creator: 'admin', remark: '待审核' },
  { id: 'sw10', word: '炸药制作', category: 'violence', status: 'enabled', createdAt: '2026-07-05 13:15:27', creator: 'admin', remark: '' },
  { id: 'sw11', word: '管制刀具', category: 'violence', status: 'enabled', createdAt: '2026-07-05 13:15:42', creator: 'admin', remark: '' },
  { id: 'sw12', word: '自杀方法', category: 'violence', status: 'enabled', createdAt: '2026-07-08 17:22:09', creator: 'admin', remark: '' },
  { id: 'sw13', word: '恐怖组织', category: 'violence', status: 'enabled', createdAt: '2026-07-10 09:48:31', creator: 'admin', remark: '' },
  { id: 'sw14', word: '种族歧视', category: 'discrimination', status: 'enabled', createdAt: '2026-07-15 10:30:00', creator: 'admin', remark: '' },
  { id: 'sw15', word: '地域黑', category: 'discrimination', status: 'enabled', createdAt: '2026-07-15 10:30:18', creator: 'admin', remark: '' },
  { id: 'sw16', word: '性别歧视', category: 'discrimination', status: 'enabled', createdAt: '2026-07-18 14:55:26', creator: 'admin', remark: '' },
  { id: 'sw17', word: '加微信赚钱', category: 'advertising', status: 'enabled', createdAt: '2026-07-22 11:12:40', creator: 'admin', remark: '' },
  { id: 'sw18', word: '刷单返利', category: 'advertising', status: 'enabled', createdAt: '2026-07-22 11:12:55', creator: 'admin', remark: '' },
  { id: 'sw19', word: '贷款咨询', category: 'advertising', status: 'disabled', createdAt: '2026-07-28 16:05:13', creator: 'admin', remark: '误报已下线' },
  { id: 'sw20', word: '代开发票', category: 'advertising', status: 'enabled', createdAt: '2026-08-01 09:20:47', creator: 'admin', remark: '' },
  { id: 'sw21', word: '其他违禁词测试', category: 'other', status: 'disabled', createdAt: '2026-08-03 15:40:08', creator: 'admin', remark: '测试用例' },
]

export const MOCK_DEPARTMENTS: IDepartment[] = [
  {
    id: '1',
    name: '总公司',
    parentId: null,
    leader: '张总',
    phone: '0755-88880001',
    createdAt: '2024-01-15',
    children: [
      {
        id: '1-1',
        name: '研发中心',
        parentId: '1',
        leader: '李总监',
        phone: '0755-88881001',
        createdAt: '2024-01-20',
        children: [
          { id: '1-1-1', name: '前端组', parentId: '1-1', leader: '王工', phone: '0755-88881101', createdAt: '2024-02-01' },
          { id: '1-1-2', name: '后端组', parentId: '1-1', leader: '赵工', phone: '0755-88881102', createdAt: '2024-02-01' },
          { id: '1-1-3', name: '芯片组', parentId: '1-1', leader: '陈工', phone: '0755-88881103', createdAt: '2024-02-05' },
        ],
      },
      {
        id: '1-2',
        name: '市场部',
        parentId: '1',
        leader: '刘经理',
        phone: '0755-88882001',
        createdAt: '2024-01-22',
      },
      {
        id: '1-3',
        name: '质量部',
        parentId: '1',
        leader: '孙经理',
        phone: '0755-88883001',
        createdAt: '2024-01-25',
      },
      {
        id: '1-4',
        name: '硬件研发部',
        parentId: '1',
        leader: '周经理',
        phone: '0755-88884001',
        createdAt: '2024-01-26',
      },
      {
        id: '1-5',
        name: '算法部',
        parentId: '1',
        leader: '吴经理',
        phone: '0755-88885001',
        createdAt: '2024-01-27',
      },
      {
        id: '1-6',
        name: '测试部',
        parentId: '1',
        leader: '郑经理',
        phone: '0755-88886001',
        createdAt: '2024-01-28',
      },
    ],
  },
]

export const flattenDepartments = (list: IDepartment[]): IDepartment[] => {
  const result: IDepartment[] = []
  const walk = (items: IDepartment[]) => {
    items.forEach((item) => {
      result.push(item)
      if (item.children?.length) walk(item.children)
    })
  }
  walk(list)
  return result
}

const AUDIT_TEMPLATES: Array<Omit<IAuditLog, 'id' | 'timestamp'>> = [
  // ===== 登录认证：登录 / 登出 =====
  { operator: 'admin', operatorId: 'u001', actionType: 'login', actionLabel: '登录', target: '系统登录', ip: '192.168.1.101', result: 'success', params: { method: '账号密码', device: 'Chrome/Windows' } },
  { operator: 'zhangsan', operatorId: 'u002', actionType: 'login', actionLabel: '登出', target: '系统登录', ip: '192.168.1.102', result: 'success', params: { sessionId: 'sess_8821', duration: '46 分钟' } },
  { operator: 'sunqi', operatorId: 'u006', actionType: 'login', actionLabel: '登录', target: '系统登录', ip: '192.168.1.106', result: 'denied', reason: '密码错误', params: { failedAttempts: 3 } },
  { operator: 'qianyi', operatorId: 'u010', actionType: 'login', actionLabel: '登录', target: '系统登录', ip: '10.0.0.55', result: 'denied', reason: 'IP 不在白名单', params: { ip: '10.0.0.55' } },
  // ===== 文档管理：上传 / 删除 / 修改元数据 =====
  { operator: 'zhangsan', operatorId: 'u002', actionType: 'document', actionLabel: '上传', target: '谛听技术手册_v2.1.pdf', ip: '192.168.1.102', result: 'success', params: { fileSize: '2.3MB', kb: '谛听', type: 'pdf' } },
  { operator: 'zhouba', operatorId: 'u007', actionType: 'document', actionLabel: '上传', target: '原理图资料 x 12', ip: '192.168.1.107', result: 'success', params: { count: 12, totalSize: '18.5MB', batch: true } },
  { operator: 'wujiu', operatorId: 'u008', actionType: 'document', actionLabel: '上传', target: '旧版硬件设计说明.doc', ip: '192.168.1.108', result: 'denied', reason: '文件类型不支持，仅支持 pdf/docx/pptx', params: { ext: 'doc' } },
  { operator: 'wangwu', operatorId: 'u004', actionType: 'document', actionLabel: '修改元数据', target: 'CV610应用笔记.docx', ip: '192.168.1.104', result: 'success', params: { updatedFields: ['标签', '版本', '芯片型号'] } },
  { operator: 'lisi', operatorId: 'u003', actionType: 'document', actionLabel: '修改元数据', target: 'ws63寄存器手册.pdf', ip: '192.168.1.103', result: 'success', params: { updatedFields: ['安全等级', '知识属性'] } },
  { operator: 'admin', operatorId: 'u001', actionType: 'document', actionLabel: '删除', target: '草稿_测试文档.docx', ip: '192.168.1.101', result: 'success', params: { status: '草稿', doubleConfirm: true } },
  { operator: 'wangwu', operatorId: 'u004', actionType: 'document', actionLabel: '删除', target: '谛听数据手册.pdf', ip: '192.168.1.104', result: 'denied', reason: '仅草稿/已归档/已废弃状态可删除', params: { status: '已发布' } },
  // ===== 知识库审批 =====
  { operator: 'zhangsan', operatorId: 'u002', actionType: 'approval', actionLabel: '提交初审', target: 'CV610应用笔记.docx', ip: '192.168.1.102', result: 'success', params: { kb: 'CV610', nextStatus: '初审中' } },
  { operator: 'zhouba', operatorId: 'u007', actionType: 'approval', actionLabel: '提交初审', target: 'ws63 SDK 说明.docx', ip: '192.168.1.107', result: 'denied', reason: '文档状态非草稿，不可提交', params: { status: '已发布' } },
  { operator: 'lisi', operatorId: 'u003', actionType: 'approval', actionLabel: '初审通过', target: 'CV610应用笔记.docx', ip: '192.168.1.103', result: 'success', params: { nextStatus: '终审中', chunks: 42 } },
  { operator: 'lisi', operatorId: 'u003', actionType: 'approval', actionLabel: '初审驳回', target: 'ws63寄存器手册.pdf', ip: '192.168.1.103', result: 'success', params: { reason: '章节缺失寄存器默认值', nextStatus: '草稿' } },
  { operator: 'admin', operatorId: 'u001', actionType: 'approval', actionLabel: '提交终审', target: '谛听技术手册_v2.1.pdf', ip: '192.168.1.101', result: 'success', params: { nextStatus: '终审中' } },
  { operator: 'admin', operatorId: 'u001', actionType: 'approval', actionLabel: '终审通过', target: '谛听技术手册_v2.1.pdf', ip: '192.168.1.101', result: 'success', params: { nextStatus: '已发布', vectorized: true } },
  { operator: 'admin', operatorId: 'u001', actionType: 'approval', actionLabel: '终审驳回', target: 'CV610 NPU 驱动手册.pdf', ip: '192.168.1.101', result: 'success', params: { reason: '存在未处理的事实冲突', nextStatus: '草稿' } },
  { operator: 'sunqi', operatorId: 'u006', actionType: 'approval', actionLabel: '提交终审', target: 'ws63 SDK 说明.docx', ip: '192.168.1.106', result: 'denied', reason: '当前角色无终审提交权限', params: { role: '内测人员' } },
  // ===== 提交Badcase单 =====
  { operator: 'sunqi', operatorId: 'u006', actionType: 'badcase_submit', actionLabel: '问答页面提交Badcase单', target: '回答结果不相关', ip: '192.168.1.106', result: 'success', params: { badcaseId: 'bc_0342', kb: '谛听' } },
  { operator: 'zhengshi', operatorId: 'u009', actionType: 'badcase_submit', actionLabel: 'MCP调用问答提交Badcase单', target: '代码示例与驱动版本不符', ip: '192.168.1.109', result: 'success', params: { badcaseId: 'bc_0343', source: 'MCP 调用问答' } },
  { operator: 'wujiu', operatorId: 'u008', actionType: 'badcase_submit', actionLabel: '未命中问答提交Badcase单', target: 'INT8 量化算子缺失', ip: '192.168.1.108', result: 'success', params: { badcaseId: 'bc_0344', hit: false } },
  { operator: 'sunqi', operatorId: 'u006', actionType: 'badcase_submit', actionLabel: '冲突检测提交Badcase单', target: '版本冲突 - NPU 驱动手册', ip: '192.168.1.106', result: 'success', params: { badcaseId: 'bc_0345', conflictType: '版本冲突' } },
  { operator: 'qianyi', operatorId: 'u010', actionType: 'badcase_submit', actionLabel: '问答页面提交Badcase单', target: '答非所问 - GPIO 复用', ip: '192.168.1.110', result: 'denied', reason: '当日 Token 限额已用尽', params: { usedToken: '100万', limitToken: '100万' } },
  { operator: 'zhouba', operatorId: 'u007', actionType: 'badcase_submit', actionLabel: '未命中问答提交Badcase单', target: '寄存器默认值缺失', ip: '192.168.1.107', result: 'denied', reason: '非该知识库成员，无权提交', params: { kb: 'CV610' } },
  // ===== Badcase处理（操作目标即处理状态）=====
  { operator: 'lisi', operatorId: 'u003', actionType: 'badcase_handle', actionLabel: '处理中', target: 'bc_0342', ip: '192.168.1.103', result: 'success', params: { from: '待处理', to: '处理中' } },
  { operator: 'lisi', operatorId: 'u003', actionType: 'badcase_handle', actionLabel: '已处理', target: 'bc_0342', ip: '192.168.1.103', result: 'success', params: { from: '处理中', to: '已处理', remark: '已补充手册章节' } },
  { operator: 'admin', operatorId: 'u001', actionType: 'badcase_handle', actionLabel: '不予处理', target: 'bc_0291', ip: '192.168.1.101', result: 'success', params: { from: '待处理', to: '不予处理', reason: '重复工单' } },
  { operator: 'sunqi', operatorId: 'u006', actionType: 'badcase_handle', actionLabel: '待处理', target: 'bc_0344', ip: '192.168.1.106', result: 'success', params: { from: '处理中', to: '待处理', reason: '需补充复现信息' } },
  { operator: 'wangwu', operatorId: 'u004', actionType: 'badcase_handle', actionLabel: '已处理', target: 'bc_0306', ip: '192.168.1.104', result: 'denied', reason: '非该工单负责人，无权处理', params: { owner: 'lisi' } },
  { operator: 'admin', operatorId: 'u001', actionType: 'badcase_handle', actionLabel: '已处理', target: 'bc_0331 ~ bc_0338', ip: '192.168.1.101', result: 'success', params: { count: 8, batch: true } },
]

// 时间按天分布（每 5 条往前推 1 天，当天 09/11/13/15/17 点错峰），便于演示按时间范围检索
const generateTimeDesc = (index: number): string => {
  const base = new Date('2026-08-10T00:00:00')
  base.setDate(base.getDate() - Math.floor(index / 5))
  base.setHours(9 + (index % 5) * 2, (index * 7) % 60, (index * 13) % 60)
  return base.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).replace(/\//g, '-')
}

export const MOCK_AUDIT_LOGS: IAuditLog[] = AUDIT_TEMPLATES.map((tpl, i) => ({
  ...tpl,
  id: `log_${String(i + 1).padStart(4, '0')}`,
  timestamp: generateTimeDesc(i),
}))

export const MOCK_MENU_TREE: IMenuItem[] = [
  {
    id: 'm1',
    name: '仪表盘',
    path: '/dashboard',
    icon: 'LayoutDashboard',
    type: 'menu',
    parentId: null,
    sort: 1,
    status: 'enabled',
    perms: 'dashboard:view',
  },
  {
    id: 'm2',
    name: '智能问答',
    path: '/qa',
    icon: 'MessageCircle',
    type: 'menu',
    parentId: null,
    sort: 2,
    status: 'enabled',
    perms: 'qa:view',
  },
  {
    id: 'm3',
    name: '知识库管理',
    path: '/knowledge',
    icon: 'FolderOpen',
    type: 'menu',
    parentId: null,
    sort: 3,
    status: 'enabled',
    perms: 'knowledge:view',
    children: [
      { id: 'm3-1', name: '文档列表', path: '/knowledge/docs', type: 'menu', parentId: 'm3', sort: 1, status: 'enabled', perms: 'knowledge:docs:view' },
      { id: 'm3-2', name: '上传文档', path: '/knowledge/upload', type: 'menu', parentId: 'm3', sort: 2, status: 'enabled', perms: 'knowledge:upload' },
      { id: 'm3-3', name: '切片查看', path: '/knowledge/chunk', type: 'menu', parentId: 'm3', sort: 3, status: 'enabled', perms: 'knowledge:chunk:view' },
      { id: 'm3-4', name: '版本对比', path: '/knowledge/diff', type: 'menu', parentId: 'm3', sort: 4, status: 'enabled', perms: 'knowledge:diff:view' },
    ],
  },
  {
    id: 'm4',
    name: '知识图谱',
    path: '/graph',
    icon: 'Network',
    type: 'menu',
    parentId: null,
    sort: 4,
    status: 'enabled',
    perms: 'graph:view',
  },
  {
    id: 'm5',
    name: '代码文档映射',
    path: '/codemap',
    icon: 'Code2',
    type: 'menu',
    parentId: null,
    sort: 5,
    status: 'enabled',
    perms: 'codemap:view',
  },
  {
    id: 'm6',
    name: '质量治理',
    path: '/quality',
    icon: 'Bug',
    type: 'menu',
    parentId: null,
    sort: 6,
    status: 'enabled',
    perms: 'quality:view',
    children: [
      { id: 'm6-1', name: 'Badcase管理', path: '/quality/badcase', type: 'menu', parentId: 'm6', sort: 1, status: 'enabled', perms: 'quality:badcase:view' },
      { id: 'm6-2', name: '命中率', path: '/quality/hitrate', type: 'menu', parentId: 'm6', sort: 2, status: 'enabled', perms: 'quality:hitrate:view' },
      { id: 'm6-3', name: '冲突检测', path: '/quality/conflict', type: 'menu', parentId: 'm6', sort: 3, status: 'enabled', perms: 'quality:conflict:view' },
      { id: 'm6-4', name: '置信度', path: '/quality/confidence', type: 'menu', parentId: 'm6', sort: 4, status: 'enabled', perms: 'quality:confidence:view' },
    ],
  },
  {
    id: 'm7',
    name: '发布管理',
    path: '/release',
    icon: 'Rocket',
    type: 'menu',
    parentId: null,
    sort: 7,
    status: 'enabled',
    perms: 'release:view',
  },
  {
    id: 'm8',
    name: 'Agent集成',
    path: '/agent',
    icon: 'Bot',
    type: 'menu',
    parentId: null,
    sort: 8,
    status: 'enabled',
    perms: 'agent:view',
  },
  {
    id: 'm9',
    name: '系统管理',
    path: '/system',
    icon: 'Settings',
    type: 'menu',
    parentId: null,
    sort: 9,
    status: 'enabled',
    perms: 'system:view',
    children: [
      { id: 'm9-1', name: '账号权限管理', path: '/permission', type: 'menu', parentId: 'm9', sort: 1, status: 'enabled', perms: 'system:permission:view' },
      { id: 'm9-2', name: '部门管理', path: '/system/department', type: 'menu', parentId: 'm9', sort: 2, status: 'enabled', perms: 'system:department:view' },
      { id: 'm9-3', name: '菜单管理', path: '/system/menu', type: 'menu', parentId: 'm9', sort: 3, status: 'enabled', perms: 'system:menu:view' },
      { id: 'm9-4', name: '字典管理', path: '/system/dict', type: 'menu', parentId: 'm9', sort: 4, status: 'enabled', perms: 'system:dict:view' },
      { id: 'm9-5', name: '审计日志', path: '/system/audit-log', type: 'menu', parentId: 'm9', sort: 5, status: 'enabled', perms: 'system:audit:view' },
    ],
  },
]

// ========== 行政区域 ==========
export type RegionLevel = 'country' | 'province' | 'city' | 'district' | 'overseas' | 'area'
export type RegionCategory = 'domestic' | 'overseas' | 'special'

export interface IRegion {
  id: string
  code: string
  name: string
  level: RegionLevel
  category: RegionCategory
  parentId: string | null
  status: 'enabled' | 'disabled'
  sort: number
  remark?: string
  createdAt: string
  children?: IRegion[]
}

export const REGION_LEVELS: { value: RegionLevel; labelKey: string }[] = [
  { value: 'country', labelKey: 'region.levelCountry' },
  { value: 'province', labelKey: 'region.levelProvince' },
  { value: 'city', labelKey: 'region.levelCity' },
  { value: 'district', labelKey: 'region.levelDistrict' },
  { value: 'overseas', labelKey: 'region.levelOverseas' },
  { value: 'area', labelKey: 'region.levelArea' },
]

export const REGION_CATEGORIES: { value: RegionCategory; labelKey: string }[] = [
  { value: 'domestic', labelKey: 'region.categoryDomestic' },
  { value: 'overseas', labelKey: 'region.categoryOverseas' },
  { value: 'special', labelKey: 'region.categorySpecial' },
]

export const MOCK_REGIONS: IRegion[] = [
  {
    id: 'r-cn',
    code: 'CN',
    name: '中国',
    level: 'country',
    category: 'domestic',
    parentId: null,
    status: 'enabled',
    sort: 1,
    createdAt: '2024-01-01',
    remark: '中华人民共和国',
    children: [
      {
        id: 'r-gd',
        code: 'GD',
        name: '广东省',
        level: 'province',
        category: 'domestic',
        parentId: 'r-cn',
        status: 'enabled',
        sort: 1,
        createdAt: '2024-01-02',
        children: [
          {
            id: 'r-gz',
            code: '440100',
            name: '广州市',
            level: 'city',
            category: 'domestic',
            parentId: 'r-gd',
            status: 'enabled',
            sort: 1,
            createdAt: '2024-01-03',
            children: [
              { id: 'r-tianhe', code: '440106', name: '天河区', level: 'district', category: 'domestic', parentId: 'r-gz', status: 'enabled', sort: 1, createdAt: '2024-01-04' },
              { id: 'r-yuexiu', code: '440104', name: '越秀区', level: 'district', category: 'domestic', parentId: 'r-gz', status: 'enabled', sort: 2, createdAt: '2024-01-04' },
              { id: 'r-nansha', code: '440115', name: '南沙区', level: 'district', category: 'domestic', parentId: 'r-gz', status: 'enabled', sort: 3, createdAt: '2024-01-04' },
            ],
          },
          {
            id: 'r-sz',
            code: '440300',
            name: '深圳市',
            level: 'city',
            category: 'domestic',
            parentId: 'r-gd',
            status: 'enabled',
            sort: 2,
            createdAt: '2024-01-03',
            children: [
              { id: 'r-nanshan', code: '440305', name: '南山区', level: 'district', category: 'domestic', parentId: 'r-sz', status: 'enabled', sort: 1, createdAt: '2024-01-04' },
              { id: 'r-futian', code: '440304', name: '福田区', level: 'district', category: 'domestic', parentId: 'r-sz', status: 'enabled', sort: 2, createdAt: '2024-01-04' },
              { id: 'r-longhua', code: '440309', name: '龙华区', level: 'district', category: 'domestic', parentId: 'r-sz', status: 'enabled', sort: 3, createdAt: '2024-01-04' },
            ],
          },
        ],
      },
      {
        id: 'r-sh',
        code: 'SH',
        name: '上海市',
        level: 'province',
        category: 'domestic',
        parentId: 'r-cn',
        status: 'enabled',
        sort: 2,
        createdAt: '2024-01-02',
        children: [
          {
            id: 'r-sh-city',
            code: '310100',
            name: '上海市',
            level: 'city',
            category: 'domestic',
            parentId: 'r-sh',
            status: 'enabled',
            sort: 1,
            createdAt: '2024-01-03',
            children: [
              { id: 'r-pudong', code: '310115', name: '浦东新区', level: 'district', category: 'domestic', parentId: 'r-sh-city', status: 'enabled', sort: 1, createdAt: '2024-01-04' },
              { id: 'r-xuhui', code: '310104', name: '徐汇区', level: 'district', category: 'domestic', parentId: 'r-sh-city', status: 'enabled', sort: 2, createdAt: '2024-01-04' },
            ],
          },
        ],
      },
      {
        id: 'r-bj',
        code: 'BJ',
        name: '北京市',
        level: 'province',
        category: 'domestic',
        parentId: 'r-cn',
        status: 'enabled',
        sort: 3,
        createdAt: '2024-01-02',
        children: [
          {
            id: 'r-bj-city',
            code: '110100',
            name: '北京市',
            level: 'city',
            category: 'domestic',
            parentId: 'r-bj',
            status: 'enabled',
            sort: 1,
            createdAt: '2024-01-03',
            children: [
              { id: 'r-haidian', code: '110108', name: '海淀区', level: 'district', category: 'domestic', parentId: 'r-bj-city', status: 'enabled', sort: 1, createdAt: '2024-01-04' },
              { id: 'r-chaoyang', code: '110105', name: '朝阳区', level: 'district', category: 'domestic', parentId: 'r-bj-city', status: 'enabled', sort: 2, createdAt: '2024-01-04' },
            ],
          },
        ],
      },
      {
        id: 'r-zj',
        code: 'ZJ',
        name: '浙江省',
        level: 'province',
        category: 'domestic',
        parentId: 'r-cn',
        status: 'enabled',
        sort: 4,
        createdAt: '2024-01-02',
        children: [
          {
            id: 'r-hz',
            code: '330100',
            name: '杭州市',
            level: 'city',
            category: 'domestic',
            parentId: 'r-zj',
            status: 'enabled',
            sort: 1,
            createdAt: '2024-01-03',
            children: [
              { id: 'r-xihu', code: '330106', name: '西湖区', level: 'district', category: 'domestic', parentId: 'r-hz', status: 'enabled', sort: 1, createdAt: '2024-01-04' },
              { id: 'r-binjiang', code: '330108', name: '滨江区', level: 'district', category: 'domestic', parentId: 'r-hz', status: 'enabled', sort: 2, createdAt: '2024-01-04' },
            ],
          },
        ],
      },
      {
        id: 'r-hk',
        code: 'HK',
        name: '香港特别行政区',
        level: 'province',
        category: 'special',
        parentId: 'r-cn',
        status: 'enabled',
        sort: 5,
        createdAt: '2024-01-02',
        remark: '特别行政区',
      },
    ],
  },
  {
    id: 'r-us',
    code: 'US',
    name: '美国',
    level: 'overseas',
    category: 'overseas',
    parentId: null,
    status: 'enabled',
    sort: 10,
    createdAt: '2024-01-10',
    children: [
      { id: 'r-us-ca', code: 'US-CA', name: '加利福尼亚州', level: 'overseas', category: 'overseas', parentId: 'r-us', status: 'enabled', sort: 1, createdAt: '2024-01-11' },
      { id: 'r-us-tx', code: 'US-TX', name: '德克萨斯州', level: 'overseas', category: 'overseas', parentId: 'r-us', status: 'enabled', sort: 2, createdAt: '2024-01-11' },
    ],
  },
  {
    id: 'r-jp',
    code: 'JP',
    name: '日本',
    level: 'overseas',
    category: 'overseas',
    parentId: null,
    status: 'enabled',
    sort: 11,
    createdAt: '2024-01-10',
    children: [
      { id: 'r-tokyo', code: 'JP-13', name: '东京都', level: 'overseas', category: 'overseas', parentId: 'r-jp', status: 'enabled', sort: 1, createdAt: '2024-01-11' },
      { id: 'r-osaka', code: 'JP-27', name: '大阪府', level: 'overseas', category: 'overseas', parentId: 'r-jp', status: 'enabled', sort: 2, createdAt: '2024-01-11' },
    ],
  },
  {
    id: 'r-apac',
    code: 'APAC',
    name: '亚太地区',
    level: 'area',
    category: 'special',
    parentId: null,
    status: 'enabled',
    sort: 20,
    createdAt: '2024-02-01',
    remark: '区域聚合，用于安全策略批量配置',
  },
  {
    id: 'r-emea',
    code: 'EMEA',
    name: '欧洲中东非洲',
    level: 'area',
    category: 'special',
    parentId: null,
    status: 'enabled',
    sort: 21,
    createdAt: '2024-02-01',
    remark: '区域聚合，用于安全策略批量配置',
  },
]

// 扁平化区域列表
export const flattenRegions = (list: IRegion[]): IRegion[] => {
  const result: IRegion[] = []
  const walk = (items: IRegion[]) => {
    items.forEach((item) => {
      result.push(item)
      if (item.children?.length) walk(item.children)
    })
  }
  walk(list)
  return result
}

// ========== 字典 ==========
export interface IDictItem {
  id: string
  label: string
  value: string
  sort: number
  status: 'enabled' | 'disabled'
  remark?: string
}

export interface IDictType {
  id: string
  name: string
  code: string
  status: 'enabled' | 'disabled'
  description: string
  builtin: boolean
  items: IDictItem[]
}

export const MOCK_DICT_TYPES: IDictType[] = [
  {
    id: 'dt-access-policy',
    name: '访问权限策略',
    code: 'access_policy',
    status: 'enabled',
    description: '知识库与文档的访问控制策略，用于角色赋权',
    builtin: true,
    items: [
      { id: 'di-ap1', label: '允许问答检索', value: 'allow_qa_search', sort: 1, status: 'enabled' },
      { id: 'di-ap2', label: '允许文档下载', value: 'allow_download', sort: 2, status: 'enabled' },
      { id: 'di-ap3', label: '允许原文查看', value: 'allow_view_source', sort: 3, status: 'enabled' },
      { id: 'di-ap4', label: '允许数据导出', value: 'allow_export', sort: 4, status: 'enabled' },
      { id: 'di-ap5', label: '仅内网访问', value: 'intranet_only', sort: 5, status: 'enabled' },
    ],
  },
]
