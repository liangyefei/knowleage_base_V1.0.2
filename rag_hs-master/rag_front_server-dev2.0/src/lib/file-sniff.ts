/**
 * 文件字节头嗅探：按真实内容识别常见类型，
 * 避免把非 PDF 字节喂给浏览器 PDF 查看器（报「未能加载 PDF 文档」）、
 * 或把二进制文件误按文本渲染（乱码）。
 */

export type SniffedKind = 'pdf' | 'image' | 'binary' | 'text'

/** 读取 blob 前 16 字节识别类型 */
export async function sniffBlobKind(blob: Blob): Promise<SniffedKind> {
  const head = new Uint8Array(await blob.slice(0, 16).arrayBuffer())
  if (head.length === 0) return 'text'
  // %PDF
  if (head[0] === 0x25 && head[1] === 0x50 && head[2] === 0x44 && head[3] === 0x46) return 'pdf'
  // 常见图片：PNG / JPEG / GIF / BMP / WebP(RIFF....WEBP)
  if (head[0] === 0x89 && head[1] === 0x50 && head[2] === 0x4e && head[3] === 0x47) return 'image'
  if (head[0] === 0xff && head[1] === 0xd8) return 'image'
  if (head[0] === 0x47 && head[1] === 0x49 && head[2] === 0x46) return 'image'
  if (head[0] === 0x42 && head[1] === 0x4d) return 'image'
  if (head[0] === 0x52 && head[1] === 0x49 && head[2] === 0x46 && head[3] === 0x46
    && head[8] === 0x57 && head[9] === 0x45 && head[10] === 0x42 && head[11] === 0x50) return 'image'
  // PK zip（docx/xlsx/pptx/zip 等）与 CFB（旧版 doc/xls/ppt）均不可按文本展示
  if (head[0] === 0x50 && head[1] === 0x4b) return 'binary'
  if (head[0] === 0xd0 && head[1] === 0xcf) return 'binary'
  // 含 NUL 字节 → 二进制（GBK 等非 UTF-8 文本不含 NUL，不受影响）
  if (head.includes(0)) return 'binary'
  return 'text'
}
