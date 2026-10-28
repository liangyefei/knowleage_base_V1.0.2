// EXPORTS: IDashboardKpi, IDashboardCategory, IDashboardBadcaseStat, IDashboardQuickEntry, MOCK_DASHBOARD_KPIS, MOCK_DASHBOARD_CATEGORIES, MOCK_DASHBOARD_BADCASE_STATS, MOCK_DASHBOARD_QUICK_ENTRIES

export interface IDashboardKpi {
  id: string
  label: string
  value: number
  unit: string
  trend: number
  trendLabel: string
}

export interface IDashboardCategory {
  id: string
  name: string
  count: number
  percentage: number
  color: string
}

export interface IDashboardBadcaseStat {
  id: string
  type: string
  count: number
  trend: 'up' | 'down'
  changeRate: number
}

export interface IDashboardQuickEntry {
  id: string
  title: string
  description: string
  icon: string
  path: string
}

export const MOCK_DASHBOARD_KPIS: IDashboardKpi[] = [
  {
    id: '1',
    label: 'dashboard.knowledgeTotal',
    value: 12856,
    unit: 'dashboard.unit.item',
    trend: 12.5,
    trendLabel: 'dashboard.vsLastWeek',
  },
  {
    id: '2',
    label: 'dashboard.chipSeriesCount',
    value: 8,
    unit: 'dashboard.unit.series',
    trend: 0,
    trendLabel: 'dashboard.vsLastWeek',
  },
  {
    id: '3',
    label: 'dashboard.docCount',
    value: 3246,
    unit: 'dashboard.unit.doc',
    trend: 8.3,
    trendLabel: 'dashboard.vsLastWeek',
  },
  {
    id: '4',
    label: 'dashboard.codeCount',
    value: 5672,
    unit: 'dashboard.unit.snippet',
    trend: 15.7,
    trendLabel: 'dashboard.vsLastWeek',
  },
]

export const MOCK_DASHBOARD_CATEGORIES: IDashboardCategory[] = [
  { id: '1', name: 'dashboard.category.techDoc', count: 3246, percentage: 35.2, color: '#3B82F6' },
  { id: '2', name: 'dashboard.category.code', count: 5672, percentage: 28.5, color: '#10B981' },
  { id: '3', name: 'dashboard.category.config', count: 1280, percentage: 12.4, color: '#F59E0B' },
  { id: '4', name: 'dashboard.category.hardware', count: 1850, percentage: 15.8, color: '#8B5CF6' },
  { id: '5', name: 'dashboard.category.faq', count: 808, percentage: 8.1, color: '#EF4444' },
]

export const MOCK_DASHBOARD_BADCASE_STATS: IDashboardBadcaseStat[] = [
  { id: '1', type: 'dashboard.badcase.wrongAnswer', count: 23, trend: 'down', changeRate: 12 },
  { id: '2', type: 'dashboard.badcase.offTopic', count: 18, trend: 'up', changeRate: 8 },
  { id: '3', type: 'dashboard.badcase.irrelevant', count: 15, trend: 'down', changeRate: 5 },
]

export const MOCK_DASHBOARD_QUICK_ENTRIES: IDashboardQuickEntry[] = [
  {
    id: '1',
    title: 'dashboard.quickQA',
    description: 'dashboard.quickQADesc',
    icon: 'MessageCircle',
    path: '/qa',
  },
  {
    id: '2',
    title: 'dashboard.quickDocs',
    description: 'dashboard.quickDocsDesc',
    icon: 'FolderOpen',
    path: '/knowledge/docs',
  },
  {
    id: '3',
    title: 'dashboard.quickGraph',
    description: 'dashboard.quickGraphDesc',
    icon: 'Network',
    path: '/graph',
  },
  {
    id: '4',
    title: 'dashboard.quickSettings',
    description: 'dashboard.quickSettingsDesc',
    icon: 'Settings',
    path: '/settings',
  },
]