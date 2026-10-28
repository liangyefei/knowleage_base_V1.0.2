// 热门词管理数据层（mock 数据 + CRUD 函数）

export type HotWordSource = 'ai' | 'manual' | 'mining'
export type HotWordStatus = 'active' | 'disabled'

export interface IHotWord {
  id: string
  question: string
  source: HotWordSource
  status: HotWordStatus
  clickCount: number
  sortOrder: number
  chip?: string
  category?: string
  createdAt: string
  updatedAt: string
}

let idCounter = 8

export let MOCK_HOT_WORDS: IHotWord[] = [
  {
    id: '1',
    question: '谛听的NPU算力是多少？',
    source: 'mining',
    status: 'active',
    clickCount: 1286,
    sortOrder: 1,
    chip: 'CV620',
    category: '性能参数',
    createdAt: '2024-01-10 09:20',
    updatedAt: '2024-01-20 14:30',
  },
  {
    id: '2',
    question: '如何配置GPIO中断？',
    source: 'manual',
    status: 'active',
    clickCount: 954,
    sortOrder: 2,
    chip: 'CV620',
    category: '外设配置',
    createdAt: '2024-01-08 11:00',
    updatedAt: '2024-01-18 16:45',
  },
  {
    id: '3',
    question: 'UART最高支持多少波特率？',
    source: 'mining',
    status: 'active',
    clickCount: 812,
    sortOrder: 3,
    chip: 'CV610',
    category: '外设配置',
    createdAt: '2024-01-12 10:15',
    updatedAt: '2024-01-22 09:10',
  },
  {
    id: '4',
    question: 'I2C总线速率是多少？',
    source: 'ai',
    status: 'active',
    clickCount: 673,
    sortOrder: 4,
    chip: 'CV720',
    category: '通信接口',
    createdAt: '2024-01-15 13:40',
    updatedAt: '2024-01-25 11:20',
  },
  {
    id: '5',
    question: '时钟树配置流程',
    source: 'manual',
    status: 'disabled',
    clickCount: 598,
    sortOrder: 5,
    chip: 'CV620',
    category: '系统时钟',
    createdAt: '2024-01-05 08:30',
    updatedAt: '2024-01-15 17:00',
  },
  {
    id: '6',
    question: 'DDR内存初始化步骤',
    source: 'mining',
    status: 'disabled',
    clickCount: 521,
    sortOrder: 6,
    chip: 'CV620',
    category: '存储子系统',
    createdAt: '2024-01-09 14:20',
    updatedAt: '2024-01-19 10:50',
  },
  {
    id: '7',
    question: 'SPI Flash启动模式配置',
    source: 'ai',
    status: 'disabled',
    clickCount: 345,
    sortOrder: 7,
    chip: 'CV610',
    category: '启动引导',
    createdAt: '2024-01-14 16:00',
    updatedAt: '2024-01-24 08:15',
  },
]

export function addHotWord(
  item: Omit<IHotWord, 'id' | 'clickCount' | 'createdAt' | 'updatedAt' | 'sortOrder'> & { sortOrder?: number },
): IHotWord {
  const now = new Date().toISOString().slice(0, 16).replace('T', ' ')
  const maxSort = MOCK_HOT_WORDS.length > 0 ? Math.max(...MOCK_HOT_WORDS.map((q) => q.sortOrder)) : 0
  const newItem: IHotWord = {
    ...item,
    id: String(idCounter++),
    clickCount: 0,
    sortOrder: item.sortOrder ?? maxSort + 1,
    createdAt: now,
    updatedAt: now,
  }
  MOCK_HOT_WORDS = [newItem, ...MOCK_HOT_WORDS]
  return newItem
}

export function updateHotWord(id: string, patch: Partial<IHotWord>): IHotWord | null {
  const idx = MOCK_HOT_WORDS.findIndex((q) => q.id === id)
  if (idx === -1) return null
  const now = new Date().toISOString().slice(0, 16).replace('T', ' ')
  MOCK_HOT_WORDS[idx] = { ...MOCK_HOT_WORDS[idx], ...patch, updatedAt: now }
  return MOCK_HOT_WORDS[idx]
}

export function deleteHotWord(id: string): boolean {
  const before = MOCK_HOT_WORDS.length
  MOCK_HOT_WORDS = MOCK_HOT_WORDS.filter((q) => q.id !== id)
  return MOCK_HOT_WORDS.length < before
}

export function toggleHotWordStatus(id: string): IHotWord | null {
  const item = MOCK_HOT_WORDS.find((q) => q.id === id)
  if (!item) return null
  return updateHotWord(id, {
    status: item.status === 'active' ? 'disabled' : 'active',
  })
}

export async function generateAiHotWords(topic: string): Promise<string[]> {
  await new Promise((r) => setTimeout(r, 1200))
  const templates: Record<string, string[]> = {
    性能参数: [
      `${topic}的最大主频是多少？`,
      `${topic}的算力指标如何？`,
      `${topic}的TDP功耗是多少？`,
    ],
    外设配置: [
      `如何配置${topic}的GPIO？`,
      `${topic}支持哪些串口？`,
      `${topic}的DMA怎么使用？`,
    ],
    默认: [
      `如何使用${topic}？`,
      `${topic}的使用注意事项？`,
      `${topic}常见问题排查？`,
    ],
  }
  const key = Object.keys(templates).find((k) => topic.includes(k)) || '默认'
  return templates[key]
}

export function getHotWordsList(): IHotWord[] {
  return [...MOCK_HOT_WORDS]
}
