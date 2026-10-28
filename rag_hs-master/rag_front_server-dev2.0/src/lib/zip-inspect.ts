const ZIP_EOCD = 0x06054b50
const ZIP_CENTRAL = 0x02014b50
const ARCHIVE_EXTS = new Set(['zip', 'rar', '7z', 'tar', 'gz', 'tgz'])

export function isArchiveFileName(fileName: string): boolean {
  const ext = fileName.split('.').pop()?.toLowerCase() || ''
  return ARCHIVE_EXTS.has(ext)
}

export function isZipFileName(fileName: string): boolean {
  return fileName.split('.').pop()?.toLowerCase() === 'zip'
}

function isJunkEntry(name: string): boolean {
  const n = name.replace(/\\/g, '/')
  if (n.startsWith('__MACOSX/') || n.includes('/__MACOSX/')) return true
  const base = n.split('/').pop() || ''
  return base === '.DS_Store' || base === 'Thumbs.db' || base === 'desktop.ini'
}

function findEocdOffset(view: DataView, size: number): number {
  const min = Math.max(0, size - 65557)
  for (let i = size - 22; i >= min; i--) {
    if (view.getUint32(i, true) === ZIP_EOCD) return i
  }
  return -1
}

/** 读取 ZIP 中央目录，统计真实文件数与目录层数（不含目录项 / macOS 垃圾文件）。 */
export async function inspectZipArchive(file: File): Promise<{ depth: number; fileCount: number }> {
  const buf = await file.arrayBuffer()
  const view = new DataView(buf)
  const bytes = new Uint8Array(buf)
  const eocd = findEocdOffset(view, bytes.length)
  if (eocd < 0) return { depth: 0, fileCount: 0 }

  const totalEntries = view.getUint16(eocd + 10, true)
  const cdOffset = view.getUint32(eocd + 16, true)
  if (cdOffset === 0xFFFFFFFF || totalEntries === 0xFFFF) return { depth: 0, fileCount: 0 }

  const decoder = new TextDecoder('utf-8')
  let offset = cdOffset
  let fileCount = 0
  let maxDepth = 0

  for (let i = 0; i < totalEntries && offset + 46 <= bytes.length; i++) {
    if (view.getUint32(offset, true) !== ZIP_CENTRAL) break
    const nameLen = view.getUint16(offset + 28, true)
    const extraLen = view.getUint16(offset + 30, true)
    const commentLen = view.getUint16(offset + 32, true)
    const name = decoder.decode(bytes.subarray(offset + 46, offset + 46 + nameLen)).replace(/\\/g, '/')
    offset += 46 + nameLen + extraLen + commentLen
    if (!name || isJunkEntry(name)) continue
    const isDir = name.endsWith('/')
    const parts = name.replace(/\/+$/, '').split('/').filter(Boolean)
    if (parts.length === 0) continue
    if (isDir) {
      maxDepth = Math.max(maxDepth, parts.length)
      continue
    }
    fileCount += 1
    maxDepth = Math.max(maxDepth, Math.max(0, parts.length - 1))
  }

  return { depth: maxDepth, fileCount }
}
