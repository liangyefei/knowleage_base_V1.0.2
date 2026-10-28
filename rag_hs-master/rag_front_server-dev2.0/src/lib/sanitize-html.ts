/**
 * 清洗 marked/mammoth 等渲染出的 HTML，防止文档内嵌的原生 HTML 污染全局页面：
 * - <style>：会全局生效，改写整个页面样式（边距/布局错乱的根因）
 * - <link>/<script>/<iframe>/<object>/<embed>/<meta>/<title>/<base>/<noscript>/<template>
 * - on* 事件属性、javascript: 协议链接
 * - 内联 style 中的 position: fixed/absolute/sticky（可逃逸预览容器覆盖整页）
 */

const BLOCKED_TAGS = [
  'style', 'link', 'script', 'iframe', 'object', 'embed',
  'meta', 'title', 'base', 'noscript', 'template',
]

const BLOCKED_SELECTOR = BLOCKED_TAGS.join(',')

export function sanitizeRenderedHtml(html: string): string {
  if (!html) return ''
  try {
    const doc = new DOMParser().parseFromString(`<div>${html}</div>`, 'text/html')
    const root = doc.body.firstElementChild
    if (!root) return ''

    root.querySelectorAll(BLOCKED_SELECTOR).forEach((el) => el.remove())

    root.querySelectorAll('*').forEach((el) => {
      const toRemove: string[] = []
      for (const attr of el.attributes) {
        const name = attr.name.toLowerCase()
        if (name.startsWith('on')) {
          toRemove.push(attr.name)
          continue
        }
        if (name === 'href' && /^\s*javascript:/i.test(attr.value)) {
          toRemove.push(attr.name)
          continue
        }
        if (name === 'style' && /position\s*:\s*(fixed|absolute|sticky)/i.test(attr.value)) {
          toRemove.push(attr.name)
        }
      }
      toRemove.forEach((n) => el.removeAttribute(n))
    })

    return root.innerHTML
  } catch {
    // DOMParser 异常时降级为去除 <style>/<script> 标签的简单处理
    return html
      .replace(/<style[\s\S]*?<\/style>/gi, '')
      .replace(/<script[\s\S]*?<\/script>/gi, '')
  }
}
