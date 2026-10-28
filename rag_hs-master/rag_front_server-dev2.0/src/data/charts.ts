// EXPORTS: ITrendPoint, MOCK_HIT_RATE_TREND

export interface ITrendPoint {
  date: string
  hitRate: number
  queryCount: number
}

export const MOCK_HIT_RATE_TREND: ITrendPoint[] = [
  { date: '06-01', hitRate: 78.5, queryCount: 1240 },
  { date: '06-05', hitRate: 80.2, queryCount: 1356 },
  { date: '06-10', hitRate: 82.1, queryCount: 1489 },
  { date: '06-15', hitRate: 81.6, queryCount: 1520 },
  { date: '06-18', hitRate: 83.4, queryCount: 1678 },
  { date: '06-22', hitRate: 85.7, queryCount: 1845 },
  { date: '06-25', hitRate: 84.9, queryCount: 1756 },
  { date: '06-28', hitRate: 87.3, queryCount: 1923 },
  { date: '06-30', hitRate: 88.1, queryCount: 2087 },
  { date: '07-02', hitRate: 89.5, queryCount: 2156 },
]
