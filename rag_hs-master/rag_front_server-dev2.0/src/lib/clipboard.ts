/** 跨 HTTP / 非安全上下文可用的复制工具，禁止直接访问 undefined.writeText */

function copyViaTextarea(text: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '')
    ta.style.cssText = 'position:fixed;left:-9999px;top:0;opacity:0;'
    document.body.appendChild(ta)
    ta.focus()
    ta.select()
    ta.setSelectionRange(0, ta.value.length)
    try {
      const ok = document.execCommand('copy')
      document.body.removeChild(ta)
      if (ok) resolve()
      else reject(new Error('copy failed'))
    } catch (err) {
      if (ta.parentNode) document.body.removeChild(ta)
      reject(err)
    }
  })
}

/**
 * 复制文本到剪贴板。
 * 优先 Clipboard API（需安全上下文）；否则用 textarea + execCommand。
 */
export async function copyText(text: string): Promise<void> {
  const value = String(text ?? '')
  // 用可选链取函数，避免 navigator.clipboard 为 undefined 时读 writeText 抛错
  const writeText = typeof navigator !== 'undefined'
    ? navigator.clipboard?.writeText
    : undefined

  if (typeof writeText === 'function' && typeof window !== 'undefined' && window.isSecureContext) {
    try {
      await writeText.call(navigator.clipboard, value)
      return
    } catch {
      // 权限拒绝等 → 走兜底
    }
  }

  await copyViaTextarea(value)
}
