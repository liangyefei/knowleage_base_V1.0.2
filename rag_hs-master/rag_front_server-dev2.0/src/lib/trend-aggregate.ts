// 自定义「时间趋势」日期小工具
// 聚合粒度（日/周）由后端 /stats/hit-rate/trend 的 granularity 参数负责，
// 前端只负责按区间天数选粒度、展示与下钻传参。

/** M.DD 短日期文案，如 10.27 */
export function mdDate(s: string): string {
  const [y, m, d] = s.split('-').map(Number)
  return `${m}.${String(d).padStart(2, '0')}`
}

/** 区间自然日天数 N = to - from + 1（包含首尾） */
export function rangeDays(from: string, to: string): number {
  const [fy, fm, fd] = from.split('-').map(Number)
  const [ty, tm, td] = to.split('-').map(Number)
  const diff = Date.UTC(ty, tm - 1, td) - Date.UTC(fy, fm - 1, fd)
  return Math.floor(diff / 86400000) + 1
}
