// EXPORTS: IConflict, IConflictFragment, IConflictHandleInfo, IConflictHistory, ConflictType, ConflictStatus, DocStatus, FragmentFormat, ConflictTrigger, MOCK_CONFLICTS, MOCK_CONFLICT_HISTORY, IMissRecord, MissSource, MOCK_MISS_RECORDS, ConfidenceStatus, IConfidenceItem, MOCK_CONFIDENCE_ITEMS

// ============================================================
// [2026-10-12 冲突检测改版] 冲突检测新数据模型（自 React 原型迁移）
// 1. 冲突类型：（内容冲突/逻辑违背/参数不一致）→（事实/语义/版本/跨模态/近重复）冲突
// 2. 解决状态：（待处理/已处理/已驳回）→（待处理/已解决/已忽略/已过期）
// 3. 检测时间由单一 detectedTime 拆分为 startTime/endTime（检测开始/结束时间）
// 4. 片段 fragments 增加 docId/docVersion/docStatus/format/chapter/location/updatedTime，
//    支持"单文档多处"与"跨文档多处"两种冲突形态，文档状态为必展示字段
// 5. 新增检测历史 MOCK_CONFLICT_HISTORY（批次/触发方式/文件数/Token消耗/各状态发现数）
// ============================================================

export type ConflictType = '事实冲突' | '语义冲突' | '版本冲突' | '跨模态冲突' | '近重复冲突'
export type ConflictStatus = '待处理' | '已解决' | '已忽略' | '已过期'
export type DocStatus = '草稿' | '审核中' | '已通过' | '已归档' | '已废弃'
export type FragmentFormat = '文本' | '表格' | '图片' | '代码'
export type ConflictTrigger = '自动巡检' | '手动触发' | '发布前检查'

// 冲突片段：一条冲突由若干片段构成，片段归属于具体文档（含版本与文档状态）
export interface IConflictFragment {
  id: string
  docId: string
  docTitle: string
  docVersion: string
  docStatus: DocStatus
  format: FragmentFormat
  chapter: string
  location: string
  content: string
  updatedTime: string
}

// 处理记录：提交冲突处理后回填
export interface IConflictHandleInfo {
  action: Exclude<ConflictStatus, '待处理'>
  handler: string
  time: string
  remark: string
  // 标记已解决时，以该片段作为基准（正确表述）
  baselineFragmentId?: string
}

export interface IConflict {
  id: string
  topic: string
  type: ConflictType
  status: ConflictStatus
  batchId: string
  startTime: string
  endTime: string
  confidence: number
  fragments: IConflictFragment[]
  handleInfo?: IConflictHandleInfo
  // 列表「是否提交badcase / 提交人」列数据
  badcaseSubmitted?: boolean
  badcaseSubmitter?: string
}

// 检测历史记录
export interface IConflictHistory {
  id: string
  trigger: ConflictTrigger
  startTime: string
  endTime: string
  durationMinutes: number
  fileCount: number
  tokenCount: number
  foundCounts: { pending: number; resolved: number; ignored: number; expired: number }
}

// 知识库文档演示数据池（供冲突片段引用）
const DOCS: Record<string, { title: string; status: DocStatus }> = {
  D001: { title: '谛听用户手册', status: '已通过' },
  D002: { title: '谛听规格书', status: '已通过' },
  D003: { title: 'GPIO驱动开发指南', status: '草稿' },
  D004: { title: 'SDK开发指南', status: '审核中' },
  D005: { title: '低功耗设计应用笔记', status: '草稿' },
  D006: { title: '谛听数据手册', status: '已通过' },
  D007: { title: '系统引脚分布图', status: '已归档' },
  D008: { title: '硬件集成清单', status: '审核中' },
  D009: { title: 'I2C接口应用笔记', status: '草稿' },
  D010: { title: '存储映射说明', status: '已通过' },
  D011: { title: '中断编程参考', status: '草稿' },
}

function frag(
  id: string,
  docId: string,
  docVersion: string,
  format: FragmentFormat,
  chapter: string,
  location: string,
  content: string,
  updatedTime: string,
): IConflictFragment {
  const doc = DOCS[docId]
  return { id, docId, docTitle: doc.title, docStatus: doc.status, docVersion, format, chapter, location, content, updatedTime }
}

export const MOCK_CONFLICTS: IConflict[] = [
  {
    id: 'C-20261012-01',
    topic: 'GPIO寄存器基地址不一致',
    type: '事实冲突',
    status: '待处理',
    batchId: 'CHK-20261012-01',
    startTime: '2026-10-12 08:00',
    endTime: '2026-10-12 08:06',
    confidence: 96,
    badcaseSubmitted: true,
    badcaseSubmitter: 'admin',
    fragments: [
      frag('C1F1', 'D001', 'v2.1', '文本', '第5章 GPIO控制器 > 5.2 寄存器描述', '第3段',
        'GPIOA~GPIOE 端口基地址从 0x1A01_0000 开始，每组端口地址间隔 0x1000，方向寄存器偏移 0x10。',
        '2026-09-28 14:30'),
      frag('C1F2', 'D003', 'v1.4', '代码', '附录B 驱动示例 > gpio_reg.h', '第18-24行',
        '#define GPIO_BASE        0x1A00_0000\n#define GPIO_DIR_OFFSET  0x20',
        '2025-11-02 09:12'),
    ],
  },
  {
    id: 'C-20261010-03',
    topic: 'NPU算力规格多处表述矛盾',
    type: '事实冲突',
    status: '待处理',
    batchId: 'CHK-20261008-02',
    startTime: '2026-10-08 17:20',
    endTime: '2026-10-08 17:26',
    confidence: 91,
    fragments: [
      frag('C2F1', 'D002', 'v3.0', '文本', '2.3 NPU处理单元', '第1段',
        'NPU支持INT8/FP16混合精度运算，INT8算力最高可达4TOPS。',
        '2026-09-30 11:05'),
      frag('C2F2', 'D004', 'v2.2', '表格', '3.1 算力参数表', '行2',
        'INT8 算力 2TOPS（典型值），FP16 算力 1TOPS',
        '2026-10-02 16:40'),
      frag('C2F3', 'D008', 'v0.9', '文本', '1.2 平台概述', '第2段',
        '板载NPU算力2TOPS，满足端侧推理需求。',
        '2026-10-05 10:22'),
    ],
  },
  {
    id: 'C-20261009-02',
    topic: '时钟使能逻辑同文档前后矛盾',
    type: '语义冲突',
    status: '待处理',
    batchId: 'CHK-20261012-01',
    startTime: '2026-10-12 08:00',
    endTime: '2026-10-12 08:06',
    confidence: 84,
    fragments: [
      frag('C3F1', 'D001', 'v2.1', '文本', '第8章 CRM时钟控制 > 8.1 时钟使能', '第2段',
        '向外设时钟使能位写入1开启对应外设时钟，写入0关闭。',
        '2026-09-28 14:30'),
      frag('C3F2', 'D001', 'v2.1', '文本', '第12章 PMU低功耗 > 12.4 低功耗唤醒', '第5段',
        '唤醒后外设时钟使能位为低有效，0表示时钟开启。',
        '2026-09-28 14:30'),
      frag('C3F3', 'D001', 'v2.1', '文本', '附录A 寄存器汇总 > A.3 配置注意事项', '第1段',
        'CLK_EN 位高有效，与CRM章节描述一致，配置时请注意。',
        '2026-10-09 08:45'),
    ],
  },
  {
    id: 'C-20260930-04',
    topic: 'UART波特率上限参数不一致',
    type: '事实冲突',
    status: '已解决',
    batchId: 'CHK-20260930-01',
    startTime: '2026-09-30 08:00',
    endTime: '2026-09-30 08:06',
    confidence: 88,
    fragments: [
      frag('C4F1', 'D001', 'v2.1', '表格', '第9章 UART接口 > 9.5 电气特性', '行4',
        '支持波特率 9600 ~ 921600 bps',
        '2026-09-20 15:10'),
      frag('C4F2', 'D006', 'v1.8', '表格', '4.2 串行接口参数', '行7',
        'UART 最高支持 3Mbps',
        '2026-08-15 10:00'),
    ],
    handleInfo: {
      action: '已解决',
      handler: '产品经理',
      time: '2026-10-01 10:24',
      remark: '以用户手册为准，数据手册中 3Mbps 为早期工程样片参数，已在数据手册中补充样片说明。',
      baselineFragmentId: 'C4F1',
    },
    badcaseSubmitted: true,
    badcaseSubmitter: 'admin',
  },
  {
    id: 'C-20261005-02',
    topic: '看门狗超时计算公式新旧版本不一致',
    type: '版本冲突',
    status: '待处理',
    batchId: 'CHK-20261005-01',
    startTime: '2026-10-05 08:00',
    endTime: '2026-10-05 08:07',
    confidence: 79,
    fragments: [
      frag('C5F1', 'D005', 'v1.0', '文本', '2.2 超时时间计算', '公式段',
        'T_OUT = T_CLK × 2^PR × 256，PR 为预分频寄存器值。',
        '2026-10-04 17:35'),
      frag('C5F2', 'D005', 'v0.9', '文本', '2.2 超时时间计算', '公式段',
        'T_OUT = T_CLK × (PR + 1) × 4096',
        '2026-09-12 09:50'),
    ],
  },
  {
    id: 'C-20261008-05',
    topic: '引脚复用表与引脚分布图描述不一致',
    type: '跨模态冲突',
    status: '待处理',
    batchId: 'CHK-20261008-02',
    startTime: '2026-10-08 17:20',
    endTime: '2026-10-08 17:26',
    confidence: 82,
    fragments: [
      frag('C6F1', 'D006', 'v1.8', '表格', '3.4 引脚复用表', '行12',
        'PB5 默认复用为 SPI1_SCK，可重映射为 TIM3_CH2。',
        '2026-08-15 10:00'),
      frag('C6F2', 'D007', 'v1.2', '图片', '图3-2 系统引脚分布图', '图注',
        'PB5 引脚标注为 PWM 输出通道（图内标注与复用表不符）。',
        '2026-03-11 13:20'),
    ],
  },
  {
    id: 'C-20260925-06',
    topic: 'DMA章节与附录B大面积重复表述',
    type: '近重复冲突',
    status: '已忽略',
    batchId: 'CHK-20260925-01',
    startTime: '2026-09-25 14:10',
    endTime: '2026-09-25 14:15',
    confidence: 93,
    fragments: [
      frag('C7F1', 'D001', 'v2.1', '文本', '第10章 DMA控制器', '第1-4段',
        'DMA控制器支持8个独立通道，每个通道可独立配置优先级、传输宽度与突发长度……',
        '2026-09-28 14:30'),
      frag('C7F2', 'D001', 'v2.1', '文本', '附录B DMA寄存器速查 > B.1 使用说明', '第1-3段',
        'DMA控制器支持8个独立通道，每个通道可独立配置优先级、传输宽度与突发长度……',
        '2026-09-28 14:30'),
    ],
    handleInfo: {
      action: '已忽略',
      handler: '产品经理',
      time: '2026-09-26 09:15',
      remark: '经确认为有意保留的速查章节，重复属正常设计，无需合并。',
    },
  },
  {
    id: 'C-20260915-03',
    topic: 'I2C时序参数新旧版本冲突',
    type: '版本冲突',
    status: '已过期',
    batchId: 'CHK-20260915-01',
    startTime: '2026-09-15 16:40',
    endTime: '2026-09-15 16:45',
    confidence: 76,
    fragments: [
      frag('C8F1', 'D009', 'v1.1', '文本', '2.5 时序参数', '第1段',
        '标准模式100kHz、快速模式400kHz，并支持Fast+模式（1MHz）。',
        '2025-06-20 11:30'),
      frag('C8F2', 'D001', 'v2.1', '表格', '第11章 I2C接口 > 11.3 时序参数', '行3',
        '仅支持标准模式与快速模式，最高 400kHz',
        '2026-09-28 14:30'),
    ],
    handleInfo: {
      action: '已过期',
      handler: '产品经理',
      time: '2026-09-20 11:02',
      remark: '来源文档《I2C接口应用笔记》已废弃归档，不再跟踪该冲突。',
    },
  },
  {
    id: 'C-20260930-02',
    topic: 'QSPI与SPI最高时钟频率参数矛盾',
    type: '事实冲突',
    status: '已解决',
    batchId: 'CHK-20260930-01',
    startTime: '2026-09-30 08:00',
    endTime: '2026-09-30 08:06',
    confidence: 71,
    fragments: [
      frag('C9F1', 'D010', 'v2.0', '文本', '4.1 QSPI地址映射', '第2段',
        'QSPI接口支持四线I/O，突发传输最高时钟104MHz。',
        '2026-07-22 16:18'),
      frag('C9F2', 'D006', 'v1.8', '表格', '5.6 SPI接口参数', '行5',
        'SPI 最高时钟 50MHz',
        '2026-08-15 10:00'),
    ],
    handleInfo: {
      action: '已解决',
      handler: '产品经理',
      time: '2026-10-02 15:40',
      remark: '50MHz 为单线模式工作上限，104MHz 为QSPI四线突发峰值，两者口径不同，已在存储映射说明中补充口径注释。',
      baselineFragmentId: 'C9F1',
    },
  },
  {
    id: 'C-20261012-02',
    topic: '中断优先级仲裁规则描述矛盾',
    type: '事实冲突',
    status: '待处理',
    batchId: 'CHK-20261012-01',
    startTime: '2026-10-12 08:00',
    endTime: '2026-10-12 08:06',
    confidence: 87,
    fragments: [
      frag('C10F1', 'D011', 'v1.3', '文本', '1.4 优先级仲裁规则', '第2段',
        '相同软件优先级时，硬件中断号较小的中断优先响应。',
        '2026-10-11 19:26'),
      frag('C10F2', 'D011', 'v1.3', '代码', '2.1 NVIC配置示例', '示例代码注释',
        '// 注意：同优先级冲突时，中断号较大的请求先被响应（见下面的仲裁示例）',
        '2026-10-11 19:26'),
    ],
  },
  {
    id: 'C-20261012-03',
    topic: 'ADC采样精度规格书与用户手册不一致',
    type: '事实冲突',
    status: '待处理',
    batchId: 'CHK-20261012-01',
    startTime: '2026-10-12 08:00',
    endTime: '2026-10-12 08:06',
    confidence: 90,
    fragments: [
      frag('C11F1', 'D002', 'v3.0', '表格', '4.3 ADC参数表', '行2',
        'ADC 分辨率 12bit，有效位 10.5 ENOB',
        '2026-09-30 11:05'),
      frag('C11F2', 'D001', 'v2.1', '文本', '第6章 ADC控制器 > 6.2 性能指标', '第1段',
        '12bit SAR ADC，有效精度 11.2 ENOB',
        '2026-09-28 14:30'),
    ],
  },
  {
    id: 'C-20261012-04',
    topic: 'Flash 容量两份文档标注不一致',
    type: '事实冲突',
    status: '待处理',
    batchId: 'CHK-20261012-01',
    startTime: '2026-10-12 08:00',
    endTime: '2026-10-12 08:06',
    confidence: 94,
    fragments: [
      frag('C12F1', 'D002', 'v3.0', '文本', '1.1 存储资源', '第2段',
        '片上 Flash 512KB，SRAM 128KB。',
        '2026-09-30 11:05'),
      frag('C12F2', 'D006', 'v1.8', '表格', '2.1 内部存储参数', '行1',
        'On-chip Flash 1MB',
        '2026-08-15 10:00'),
    ],
  },
  {
    id: 'C-20261012-05',
    topic: 'GPIO 驱动电流新旧版本矛盾',
    type: '版本冲突',
    status: '待处理',
    batchId: 'CHK-20261012-01',
    startTime: '2026-10-12 08:00',
    endTime: '2026-10-12 08:06',
    confidence: 78,
    fragments: [
      frag('C13F1', 'D003', 'v1.4', '文本', '3.2 端口驱动能力', '第1段',
        '单脚最大驱动电流 8mA，总驱动电流 120mA。',
        '2025-11-02 09:12'),
      frag('C13F2', 'D003', 'v1.3', '文本', '3.2 端口驱动能力', '第1段',
        '单脚最大驱动电流 4mA，总驱动电流 64mA。',
        '2025-06-18 14:50'),
    ],
  },
  {
    id: 'C-20261012-06',
    topic: '复位电平有效极性描述相反',
    type: '语义冲突',
    status: '待处理',
    batchId: 'CHK-20261012-01',
    startTime: '2026-10-12 08:00',
    endTime: '2026-10-12 08:06',
    confidence: 89,
    fragments: [
      frag('C14F1', 'D001', 'v2.1', '文本', '第7章 复位控制器 > 7.1 复位源', '第2段',
        'NRST 引脚低电平有效，输入低电平持续 20μs 以上触发全片复位。',
        '2026-09-28 14:30'),
      frag('C14F2', 'D001', 'v2.1', '文本', '附录A 寄存器汇总 > A.1 复位寄存器', '注脚',
        'NRST 高电平有效，建议上拉 10kΩ。',
        '2026-09-28 14:30'),
    ],
  },
  {
    id: 'C-20261011-01',
    topic: 'PWM 通道数与应用笔记描述不符',
    type: '事实冲突',
    status: '已解决',
    batchId: 'CHK-20261011-01',
    startTime: '2026-10-11 08:00',
    endTime: '2026-10-11 08:05',
    confidence: 83,
    fragments: [
      frag('C15F1', 'D002', 'v3.0', '表格', '5.2 定时器资源', '行3',
        'PWM 通道 16 路，支持互补输出与死区插入。',
        '2026-09-30 11:05'),
      frag('C15F2', 'D005', 'v1.0', '文本', '1.3 PWM 模块概述', '第1段',
        '板载 8 路独立 PWM，无互补输出。',
        '2026-10-04 17:35'),
    ],
    handleInfo: {
      action: '已解决',
      handler: '产品经理',
      time: '2026-10-11 14:20',
      remark: '规格书描述为总通道数（含互补），应用笔记仅统计独立通道，已在应用笔记中补充口径说明。',
      baselineFragmentId: 'C15F1',
    },
    badcaseSubmitted: false,
  },
  {
    id: 'C-20261011-02',
    topic: 'CAN-FD 支持范围前后矛盾',
    type: '语义冲突',
    status: '待处理',
    batchId: 'CHK-20261011-01',
    startTime: '2026-10-11 08:00',
    endTime: '2026-10-11 08:05',
    confidence: 85,
    fragments: [
      frag('C16F1', 'D001', 'v2.1', '文本', '第13章 CAN 控制器 > 13.1 概述', '第1段',
        '支持 CAN2.0A/B 与 CAN-FD，CAN-FD 数据段最高 5Mbps。',
        '2026-09-28 14:30'),
      frag('C16F2', 'D001', 'v2.1', '文本', '第13章 CAN 控制器 > 13.4 限制', '第3段',
        'CAN-FD 模式不支持，仅兼容 CAN2.0A。',
        '2026-09-28 14:30'),
    ],
  },
  {
    id: 'C-20261010-01',
    topic: 'SDIO 时钟模式图片与文本描述不一致',
    type: '跨模态冲突',
    status: '待处理',
    batchId: 'CHK-20261010-01',
    startTime: '2026-10-10 08:00',
    endTime: '2026-10-10 08:06',
    confidence: 80,
    fragments: [
      frag('C17F1', 'D006', 'v1.8', '表格', '6.2 SDIO 接口参数', '行4',
        'SDIO 高速模式最高 50MHz，支持 4bit 总线。',
        '2026-08-15 10:00'),
      frag('C17F2', 'D007', 'v1.2', '图片', '图4-1 SDIO 时序图', '图注',
        '图中标注 SDR104 模式 208MHz，1bit 总线（与参数表不符）。',
        '2026-03-11 13:20'),
    ],
  },
  {
    id: 'C-20261010-02',
    topic: '低功耗模式休眠电流规格书与数据手册矛盾',
    type: '事实冲突',
    status: '待处理',
    batchId: 'CHK-20261010-01',
    startTime: '2026-10-10 08:00',
    endTime: '2026-10-10 08:06',
    confidence: 92,
    fragments: [
      frag('C18F1', 'D002', 'v3.0', '表格', '3.3 低功耗参数', '行2',
        'Deep-sleep 典型 120μA（Ta=25℃）',
        '2026-09-30 11:05'),
      frag('C18F2', 'D005', 'v1.0', '文本', '2.1 休眠模式', '第1段',
        'Deep-sleep 典型 85μA，最大 150μA。',
        '2026-10-04 17:35'),
    ],
  },
  {
    id: 'C-20261010-03',
    topic: 'Ethernet MAC 地址过滤条数新旧版本不一致',
    type: '版本冲突',
    status: '已忽略',
    batchId: 'CHK-20261010-01',
    startTime: '2026-10-10 08:00',
    endTime: '2026-10-10 08:06',
    confidence: 74,
    fragments: [
      frag('C19F1', 'D004', 'v2.2', '文本', '4.5 MAC 过滤', '第2段',
        '支持 16 个完美过滤条目和 512-bit 哈希过滤。',
        '2026-10-02 16:40'),
      frag('C19F2', 'D004', 'v2.0', '文本', '4.5 MAC 过滤', '第2段',
        '支持 4 个完美过滤条目和 64-bit 哈希过滤。',
        '2026-07-10 10:15'),
    ],
    handleInfo: {
      action: '已忽略',
      handler: '产品经理',
      time: '2026-10-10 16:30',
      remark: '旧版本为早期工程样片限制，已在新版本中升级，无需处理。',
    },
  },
  {
    id: 'C-20261009-01',
    topic: '外部中断触发方式描述矛盾',
    type: '语义冲突',
    status: '待处理',
    batchId: 'CHK-20261009-01',
    startTime: '2026-10-09 08:00',
    endTime: '2026-10-09 08:05',
    confidence: 81,
    fragments: [
      frag('C20F1', 'D001', 'v2.1', '文本', '第14章 EXTI > 14.1 触发方式', '第1段',
        '每个外部中断独立配置上升沿/下降沿/双边沿触发。',
        '2026-09-28 14:30'),
      frag('C20F2', 'D011', 'v1.3', '代码', '1.2 EXTI 配置示例', '注释',
        '// 仅支持电平触发，不支持边沿触发',
        '2026-10-11 19:26'),
    ],
  },
  {
    id: 'C-20261009-02',
    topic: '看门狗最大超时时间两处表述矛盾',
    type: '事实冲突',
    status: '待处理',
    batchId: 'CHK-20261009-01',
    startTime: '2026-10-09 08:00',
    endTime: '2026-10-09 08:05',
    confidence: 86,
    fragments: [
      frag('C21F1', 'D001', 'v2.1', '表格', '第15章 WDT > 15.3 参数', '行2',
        '最大超时 26.8s（PR=7, IL=12）',
        '2026-09-28 14:30'),
      frag('C21F2', 'D005', 'v1.0', '文本', '3.4 看门狗超时', '第2段',
        '最大超时 13.4s',
        '2026-10-04 17:35'),
    ],
  },
  {
    id: 'C-20261008-01',
    topic: 'RTC 校准精度参数前后矛盾',
    type: '事实冲突',
    status: '已解决',
    batchId: 'CHK-20261008-02',
    startTime: '2026-10-08 17:20',
    endTime: '2026-10-08 17:26',
    confidence: 77,
    fragments: [
      frag('C22F1', 'D001', 'v2.1', '文本', '第16章 RTC > 16.4 校准', '第2段',
        '支持 ±2ppm 数字校准，步长 0.238ppm。',
        '2026-09-28 14:30'),
      frag('C22F2', 'D002', 'v3.0', '表格', '7.1 RTC 参数', '行3',
        '校准精度 ±5ppm',
        '2026-09-30 11:05'),
    ],
    handleInfo: {
      action: '已解决',
      handler: '产品经理',
      time: '2026-10-09 10:00',
      remark: '规格书为全温范围最差值，用户手册为常温典型值，已在规格书补充条件说明。',
      baselineFragmentId: 'C22F1',
    },
    badcaseSubmitted: true,
    badcaseSubmitter: 'admin',
  },
  {
    id: 'C-20261008-02',
    topic: 'USB 枚举速度参数两处不一致',
    type: '事实冲突',
    status: '待处理',
    batchId: 'CHK-20261008-02',
    startTime: '2026-10-08 17:20',
    endTime: '2026-10-08 17:26',
    confidence: 88,
    fragments: [
      frag('C23F1', 'D001', 'v2.1', '表格', '第17章 USB > 17.2 参数', '行2',
        '支持 HS（480Mbps）与 FS（12Mbps）',
        '2026-09-28 14:30'),
      frag('C23F2', 'D008', 'v0.9', '文本', '2.3 USB 接口', '第1段',
        '板载 USB 仅支持 FS（12Mbps）',
        '2026-10-05 10:22'),
    ],
  },
  {
    id: 'C-20261007-01',
    topic: 'I2S 采样率支持范围规格书与驱动矛盾',
    type: '事实冲突',
    status: '待处理',
    batchId: 'CHK-20261007-01',
    startTime: '2026-10-07 08:00',
    endTime: '2026-10-07 08:05',
    confidence: 84,
    fragments: [
      frag('C24F1', 'D002', 'v3.0', '表格', '8.2 I2S 参数', '行2',
        '采样率 8/16/22.05/32/44.1/48/96/192 kHz',
        '2026-09-30 11:05'),
      frag('C24F2', 'D003', 'v1.4', '代码', '附录C 音频驱动 > i2s.h', '宏定义',
        '#define I2S_SAMPLE_RATE_MAX  96000',
        '2025-11-02 09:12'),
    ],
  },
  {
    id: 'C-20261007-02',
    topic: '电源电压范围数据手册与规格书矛盾',
    type: '事实冲突',
    status: '待处理',
    batchId: 'CHK-20261007-01',
    startTime: '2026-10-07 08:00',
    endTime: '2026-10-07 08:05',
    confidence: 95,
    fragments: [
      frag('C25F1', 'D006', 'v1.8', '表格', '1.2 推荐工作条件', '行1',
        'VDD 1.62V ~ 3.6V',
        '2026-08-15 10:00'),
      frag('C25F2', 'D002', 'v3.0', '表格', '1.1 极限参数', '行2',
        'VDD 1.71V ~ 3.6V（推荐 1.8V/3.3V）',
        '2026-09-30 11:05'),
    ],
  },
  {
    id: 'C-20261005-01',
    topic: 'TIMER 输入捕获通道数矛盾',
    type: '事实冲突',
    status: '已过期',
    batchId: 'CHK-20261005-01',
    startTime: '2026-10-05 08:00',
    endTime: '2026-10-05 08:07',
    confidence: 70,
    fragments: [
      frag('C26F1', 'D002', 'v3.0', '表格', '5.2 定时器资源', '行2',
        '每个通用定时器 4 路输入捕获',
        '2026-09-30 11:05'),
      frag('C26F2', 'D004', 'v2.2', '文本', '3.6 输入捕获', '第1段',
        '通用定时器支持 2 路输入捕获',
        '2026-10-02 16:40'),
    ],
    handleInfo: {
      action: '已过期',
      handler: '产品经理',
      time: '2026-10-06 09:30',
      remark: 'SDK 开发指南已更新至 v2.3 修正该描述，旧版本冲突不再跟踪。',
    },
  },
]

export const MOCK_CONFLICT_HISTORY: IConflictHistory[] = [
  { id: 'CHK-20261012-01', trigger: '自动巡检', startTime: '2026-10-12 08:00', endTime: '2026-10-12 08:06', durationMinutes: 6, fileCount: 186, tokenCount: 42500, foundCounts: { pending: 4, resolved: 2, ignored: 1, expired: 1 } },
  { id: 'CHK-20261008-02', trigger: '发布前检查', startTime: '2026-10-08 17:20', endTime: '2026-10-08 17:26', durationMinutes: 6, fileCount: 64, tokenCount: 18200, foundCounts: { pending: 3, resolved: 1, ignored: 1, expired: 0 } },
  { id: 'CHK-20261005-01', trigger: '自动巡检', startTime: '2026-10-05 08:00', endTime: '2026-10-05 08:07', durationMinutes: 7, fileCount: 179, tokenCount: 39800, foundCounts: { pending: 2, resolved: 1, ignored: 2, expired: 1 } },
  { id: 'CHK-20260930-01', trigger: '自动巡检', startTime: '2026-09-30 08:00', endTime: '2026-09-30 08:06', durationMinutes: 6, fileCount: 182, tokenCount: 41000, foundCounts: { pending: 3, resolved: 2, ignored: 0, expired: 1 } },
  { id: 'CHK-20260925-01', trigger: '手动触发', startTime: '2026-09-25 14:10', endTime: '2026-09-25 14:15', durationMinutes: 5, fileCount: 95, tokenCount: 21300, foundCounts: { pending: 1, resolved: 1, ignored: 1, expired: 1 } },
  { id: 'CHK-20260920-01', trigger: '自动巡检', startTime: '2026-09-20 08:00', endTime: '2026-09-20 08:07', durationMinutes: 7, fileCount: 175, tokenCount: 38600, foundCounts: { pending: 3, resolved: 1, ignored: 2, expired: 0 } },
  { id: 'CHK-20260915-01', trigger: '发布前检查', startTime: '2026-09-15 16:40', endTime: '2026-09-15 16:45', durationMinutes: 5, fileCount: 58, tokenCount: 15900, foundCounts: { pending: 2, resolved: 1, ignored: 0, expired: 0 } },
  { id: 'CHK-20260910-01', trigger: '自动巡检', startTime: '2026-09-10 08:00', endTime: '2026-09-10 08:07', durationMinutes: 7, fileCount: 168, tokenCount: 36400, foundCounts: { pending: 1, resolved: 2, ignored: 3, expired: 1 } },
]

export type MissSource = '智能问答' | '前台机器人' | 'Agent调用'

export interface IMissRecord {
  id: string
  question: string
  user: string
  queryTime: string
  source: MissSource
  chip: string
  status: '待处理' | '已补充' | '已忽略'
}

export const MOCK_MISS_RECORDS: IMissRecord[] = [
  {
    id: 'MISS-20240115-001',
    question: 'CV720芯片支持的DDR5最高频率是多少？',
    user: 'zhangsan@hisilicon.com',
    queryTime: '2024-01-15 14:32:18',
    source: '智能问答',
    chip: 'CV720',
    status: '待处理',
  },
  {
    id: 'MISS-20240115-002',
    question: 'ws63的蓝牙协议版本是多少？支持LE Audio吗？',
    user: 'lisi@hisilicon.com',
    queryTime: '2024-01-15 11:20:05',
    source: '前台机器人',
    chip: 'ws63',
    status: '待处理',
  },
  {
    id: 'MISS-20240114-003',
    question: 'Hi3519DV500 ISP的3A算法参数怎么配置？',
    user: 'wangwu@hisilicon.com',
    queryTime: '2024-01-14 16:45:33',
    source: '智能问答',
    chip: 'Hi3519DV500',
    status: '已补充',
  },
  {
    id: 'MISS-20240114-004',
    question: '谛听芯片的NPU是否支持Transformer模型推理？',
    user: 'zhaoliu@hisilicon.com',
    queryTime: '2024-01-14 09:12:47',
    source: 'Agent调用',
    chip: '谛听',
    status: '待处理',
  },
  {
    id: 'MISS-20240113-005',
    question: 'CV610的SPI接口最大支持多少MHz时钟频率？',
    user: 'chenqi@hisilicon.com',
    queryTime: '2024-01-13 15:08:22',
    source: '智能问答',
    chip: 'CV610',
    status: '已忽略',
  },
  {
    id: 'MISS-20240113-006',
    question: '如何在CV620上配置PCIe的EP模式？',
    user: 'sunba@hisilicon.com',
    queryTime: '2024-01-13 10:30:15',
    source: '前台机器人',
    chip: 'CV620',
    status: '待处理',
  },
  {
    id: 'MISS-20240112-007',
    question: 'CV610pro的安全启动流程是怎样的？',
    user: 'zhoujiu@hisilicon.com',
    queryTime: '2024-01-12 18:22:40',
    source: '智能问答',
    chip: 'CV610pro',
    status: '已补充',
  },
  {
    id: 'MISS-20240112-008',
    question: '谛听芯片支持多少路摄像头输入？',
    user: 'wushi@hisilicon.com',
    queryTime: '2024-01-12 08:55:11',
    source: 'Agent调用',
    chip: '谛听',
    status: '待处理',
  },
]

export type ConfidenceStatus = '待处理' | '已处理' | '已忽略' | '不正确'

export interface IConfidenceItem {
  id: string
  content: string
  document: string
  confidence: number
  updateTime: string
  source: string
  chunkId: string
  status: ConfidenceStatus
}

export const MOCK_CONFIDENCE_ITEMS: IConfidenceItem[] = [
  { id: '1', content: '谛听芯片内置高性能NPU处理器，支持INT8/FP16混合精度运算，算力可达4TOPS', document: '谛听规格书', confidence: 95, updateTime: '2024-01-15', source: '官方文档', chunkId: 'chunk-001', status: '已处理' },
  { id: '2', content: 'GPIO方向控制寄存器地址0x1A01_0000，每一位对应一个引脚方向', document: 'GPIO用户手册', confidence: 92, updateTime: '2024-01-14', source: '官方文档', chunkId: 'chunk-002', status: '已处理' },
  { id: '3', content: 'UART最高支持921600波特率，需配置分频寄存器', document: 'UART应用笔记', confidence: 58, updateTime: '2024-01-13', source: '社区文档', chunkId: 'chunk-003', status: '待处理' },
  { id: '4', content: 'I2C总线支持标准模式和快速模式，最高速率400kHz', document: 'I2C协议规范', confidence: 88, updateTime: '2024-01-12', source: '官方文档', chunkId: 'chunk-004', status: '已忽略' },
  { id: '5', content: 'SPI接口支持主从模式，最高时钟频率可达50MHz', document: 'SPI用户手册', confidence: 45, updateTime: '2024-01-11', source: '第三方', chunkId: 'chunk-005', status: '待处理' },
  { id: '6', content: 'DDR3内存控制器支持32bit位宽，最高频率1600MHz', document: 'DDR应用指南', confidence: 75, updateTime: '2024-01-10', source: '官方文档', chunkId: 'chunk-006', status: '待处理' },
  { id: '7', content: 'PWM控制器有8个通道，每个通道独立配置', document: 'PWM数据手册', confidence: 35, updateTime: '2024-01-09', source: '社区文档', chunkId: 'chunk-007', status: '待处理' },
  { id: '8', content: '定时器共16个GPIO引脚，支持上下拉配置', document: 'GPIO用户手册', confidence: 90, updateTime: '2024-01-08', source: '官方文档', chunkId: 'chunk-008', status: '已处理' },
]
