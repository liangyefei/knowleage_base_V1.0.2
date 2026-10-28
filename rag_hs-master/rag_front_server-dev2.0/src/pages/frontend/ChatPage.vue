<script lang="ts">
import type { ChatStreamDoc } from '@/services/chat.service'

interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: string
  isStreaming?: boolean
  isError?: boolean
  deepThinking?: boolean
  reasoning?: string
  reasoningDone?: boolean
  reasoningOpen?: boolean
  reasoningStartedAt?: number
  reasoningSeconds?: number
  routeReasoning?: string
  routeReasoningDone?: boolean
  routeReasoningOpen?: boolean
  routeReasoningStartedAt?: number
  docs?: ChatStreamDoc[]
  feedbackRating?: string | null
  feedbackReasons?: string | null
  feedbackUpdatedAt?: string | null
  badcaseType?: string
  badcaseComment?: string
  isHint?: boolean
}

/** 后台流追踪：跨页面导航持久化，切换会话/页面时不中断流，回来时恢复。 */
export const bgStreams = new Map<string, {
  abort: AbortController
  userMsg: Message
  assistantMsg: Message
}>()
</script>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onUnmounted, useTemplateRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { marked } from 'marked'
import {
  Send, ThumbsUp, ThumbsDown, Cpu, Plus, Trash2, Search,
  MessageSquare, Menu, X, Brain, Compass,
  ChevronDown, ChevronRight, Check, MoreHorizontal, Pencil, AlertCircle, Square,
  Pin, PinOff, Globe,
} from 'lucide-vue-next'
import { cn, filterCv610ProOnly, displayKbName } from '@/lib/utils'
import { toast } from '@/lib/toast'
import { copyText } from '@/lib/clipboard'
import { chatDocTitle, chatDocPageNum, kbDocsOnly, internetSearchResults } from '@/lib/chat-docs'
import {
  createConversation,
  sendMessage as chatSendMessage,
  applyChatStreamEvent,
  finalizeThinking,
  isAbortError,
  getConversations,
  getMessages,
  deleteConversation as apiDeleteConversation,
  renameConversation,
  pinConversation,
  unpinConversation,
  submitFeedback,
  BADCASE_REASON_MAP,
  BADCASE_REASON_REVERSE,
  type Conversation,
} from '@/services/chat.service'
import { cancelPendingToast, type PageResult } from '@/services/api-client'
import { getEnabledHotwords, pickHotQuestions } from '@/services/hotword.service'
import { getKbOptions, type KbOption } from '@/services/knowledge.service'
import { useI18n } from '@/composables/useI18n'
import { useSearchLenLimit } from '@/composables/useSearchLenLimit'
import { useDocumentFilePreview } from '@/composables/useDocumentFilePreview'
import DocumentPreviewDrawer from '@/components/DocumentPreviewDrawer.vue'

const sampleQuestions = ref<string[]>([])
const hotQuestionsExpanded = ref(false)
const displayedSampleQuestions = computed(() =>
  hotQuestionsExpanded.value ? sampleQuestions.value : sampleQuestions.value.slice(0, 4),
)

const BADCASE_TYPES = ['回答错误', '答非所问', '结果不相关', '代码错误', '信息过时']

// State
const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const preview = useDocumentFilePreview()
const isMobile = ref(false)

const CONV_PAGE_SIZE = 20
const MSG_PAGE_SIZE = 20
const conversations = ref<Conversation[]>([])
const convPage = ref(1)
const convHasMore = ref(true)
const convLoading = ref(false)
const convLoadingMore = ref(false)
const convListRef = ref<HTMLElement | null>(null)
const activeConvId = ref<string | null>(null)
const INPUT_MAX_LEN = 500
const inputValue = ref('')
const messages = ref<Message[]>([])
const isStreaming = ref(false)
const msgPage = ref(1)
const msgHasMore = ref(false)
const msgLoadingMore = ref(false)
let streamAbort: AbortController | null = null

const sidebarOpen = ref(false)
const searchConv = ref('')
useSearchLenLimit(searchConv, () => t('common.searchMaxLen', { max: 100 }))
const TITLE_MAX_LEN = 100
const editingConvId = ref<string | null>(null)
const editingTitle = ref('')
const menuOpenId = ref<string | null>(null)
const deleteConfirmId = ref<string | null>(null)
const feedbackMsgId = ref<string | null>(null)
const feedbackType = ref('')
const feedbackComment = ref('')
const deepThinking = ref(false)
// 智能搜索（联网搜索）二态开关：false=关闭（默认）/ true=开启
const internetSearch = ref(false)
// 智能搜索结果侧栏（DeepSeek 风格）：点击回答处“搜索到 N 个网页”打开
const searchPanelOpen = ref(false)
const searchPanelDocs = ref<ChatStreamDoc[]>([])
const searchPanelItems = computed(() => internetSearchResults(searchPanelDocs.value))
const openSearchResults = (docs?: ChatStreamDoc[]) => {
  searchPanelDocs.value = docs || []
  searchPanelOpen.value = true
}
const searchScope = ref({
  official: true,
  external: true,
  selectedKbIds: [] as string[],
})
const searchScopeOpen = ref(false)
const searchScopeRoot = ref<HTMLElement | null>(null)
const kbOptions = ref<KbOption[]>([])

function onPointerDownOutside(e: PointerEvent) {
  const target = e.target as Node
  if (searchScopeOpen.value && searchScopeRoot.value && !searchScopeRoot.value.contains(target)) {
    searchScopeOpen.value = false
  }
  if (menuOpenId.value) {
    const menuRoot = (e.target as HTMLElement | null)?.closest?.('[data-conv-menu]')
    if (!menuRoot) menuOpenId.value = null
  }
}

const filteredConversations = computed(() => {
  const q = searchConv.value.trim().toLowerCase()
  const list = q
    ? conversations.value.filter((c) => c.title.toLowerCase().includes(q))
    : conversations.value
  return [...list].sort((a, b) => {
    if (a.pinned && !b.pinned) return -1
    if (!a.pinned && b.pinned) return 1
    return 0
  })
})

const copyIcon = '<svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>'
const checkIcon = '<svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="text-green-500"><polyline points="20 6 9 17 4 12"/></svg>'

const renderMarkdown = (content: string): string => {
  if (!content) return ''
  try {
    const renderer = new marked.Renderer()
    const originalLink = renderer.link.bind(renderer)
    renderer.link = (token: Parameters<typeof originalLink>[0]) => {
      const html = originalLink(token)
      return html.replace(/^<a /, '<a target="_blank" rel="noopener noreferrer" ')
    }
    const originalCode = renderer.code.bind(renderer)
    // marked v18: renderer.code 只接收 1 个 token 对象参数（非旧版 (code, infostring, escaped) 三参数签名）
    renderer.code = (token: Parameters<typeof originalCode>[0]) => {
      let html = originalCode(token)
      html = html.replace(/^<pre/, '<div class="relative group"><pre class="overflow-x-auto"')
      html = html.replace('</pre>', `</pre><button type="button" class="code-copy-btn absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity z-10 p-1.5 rounded hover:bg-foreground/10 bg-background/50 backdrop-blur border border-border" title="${t('common.copy')}">${copyIcon}</button></div>`)
      return html
    }
    return marked.parse(content, { async: false, renderer }) as string
  } catch {
    return content
  }
}

let copyCleanup: (() => void) | null = null
function setupCopyDelegate() {
  const root = scrollRef.value as HTMLElement | null
  if (!root) return
  const handler = (e: MouseEvent) => {
    const btn = (e.target as HTMLElement).closest('.code-copy-btn') as HTMLElement | null
    if (!btn) return
    e.stopPropagation()
    const wrapper = btn.closest('.relative.group') as HTMLElement | null
    if (!wrapper) return
    const pre = wrapper.querySelector('pre') as HTMLElement | null
    if (!pre) return
    const codeText = pre.querySelector('code')?.textContent ?? pre.textContent ?? ''
    if (!codeText) return
    // 线上 HTTP 环境下 navigator.clipboard 为 undefined，必须走 lib/clipboard 的降级方案
    void copyText(codeText).then(() => {
      btn.innerHTML = checkIcon
      btn.classList.remove('opacity-0', 'group-hover:opacity-100')
      btn.classList.add('opacity-100')
      toast.success(t('common.copySuccess'))
      setTimeout(() => {
        btn.innerHTML = copyIcon
        btn.classList.add('opacity-0', 'group-hover:opacity-100')
        btn.classList.remove('opacity-100')
      }, 1500)
    }).catch(() => toast.error(t('common.copyFailed')))
  }
  root.addEventListener('click', handler)
  copyCleanup = () => root.removeEventListener('click', handler)
}

const thinkingTitle = (msg: Message): string => {
  if (!msg.reasoningDone) return t('qa.thinkingInProgress')
  return t('chat.deepThinking')
}

const thinkingCountLabel = (msg: Message): string => {
  const count = (msg.reasoning || '').length
  if (!count) return ''
  return msg.reasoningDone
    ? t('qa.thoughtCharCount', { count })
    : t('qa.thinkingCharCount', { count })
}

const routeReasoningTitle = (msg: Message): string => {
  if (!msg.routeReasoningDone) return t('qa.routeAnalyzing')
  return t('qa.routeAnalysis')
}

const routeReasoningCountLabel = (msg: Message): string => {
  const count = (msg.routeReasoning || '').length
  if (!count) return ''
  return msg.routeReasoningDone
    ? t('qa.routeAnalysisCharCount', { count })
    : t('qa.routeAnalyzingCharCount', { count })
}

// ── 会话列表（对接后端） ──

function getConvHasMore(result: PageResult<Conversation>): boolean {
  // 后端提供了可靠的 totalRow，用分页信息判断
  if (result.totalRow > 0) return result.pageNumber < result.totalPage
  // 后端未提供总数，拿满一页就认为还有
  return result.records.length >= CONV_PAGE_SIZE
}

const loadConversations = async () => {
  convPage.value = 1
  convLoading.value = true
  try {
    const result = await getConversations({ page: 1, pageSize: CONV_PAGE_SIZE })
    conversations.value = result.records
    convHasMore.value = getConvHasMore(result)
  } catch {
    conversations.value = []
  } finally {
    convLoading.value = false
    checkAutoLoad()
  }
}

const loadMoreConversations = async () => {
  if (convLoadingMore.value || !convHasMore.value) return
  convLoadingMore.value = true
  try {
    const nextPage = convPage.value + 1
    const result = await getConversations({ page: nextPage, pageSize: CONV_PAGE_SIZE })
    conversations.value = [...conversations.value, ...result.records]
    convPage.value = nextPage
    convHasMore.value = getConvHasMore(result)
  } catch {
    // ignore
  } finally {
    convLoadingMore.value = false
    checkAutoLoad()
  }
}

const newConversation = () => {
  // 不中断当前流——让它后台运行
  streamAbort = null
  isStreaming.value = false

  activeConvId.value = null
  messages.value = []
  msgPage.value = 1
  msgHasMore.value = false
  editingConvId.value = null
  menuOpenId.value = null
  if (isMobile.value) sidebarOpen.value = false
  router.replace({ query: {} })
}

const mapMessage = (dto: { id: string; role: 'user' | 'assistant'; content: string; timestamp?: string; reasoning?: string; routeReasoning?: string; docs?: ChatStreamDoc[]; deepThinking?: boolean; feedbackRating?: string | null; feedbackReasons?: string | null; feedbackComment?: string | null; feedbackUpdatedAt?: string | null }, i: number) => ({
  id: dto.id || `hist-${i}`,
  role: dto.role,
  content: dto.content,
  timestamp: dto.timestamp || '',
  reasoning: dto.reasoning,
  routeReasoning: dto.routeReasoning,
  docs: dto.docs,
  deepThinking: dto.deepThinking,
  reasoningDone: Boolean(dto.reasoning),
  routeReasoningDone: Boolean(dto.routeReasoning),
  routeReasoningOpen: false,
  feedbackRating: dto.feedbackRating ?? null,
  feedbackReasons: dto.feedbackReasons ?? null,
  feedbackUpdatedAt: dto.feedbackUpdatedAt ?? null,
  badcaseType: dto.feedbackReasons ? BADCASE_REASON_REVERSE[dto.feedbackReasons] : undefined,
  badcaseComment: dto.feedbackComment ?? undefined,
})

const switchConversation = async (id: string) => {
  // 不中断当前流——让它后台运行，回来后从后端或 bgStreams 恢复
  streamAbort = null
  isStreaming.value = false

  activeConvId.value = id
  msgPage.value = 1
  msgHasMore.value = false
  menuOpenId.value = null
  if (isMobile.value) sidebarOpen.value = false
  router.replace({ query: { cid: id } })
  try {
    const result = await getMessages({ conversationId: id, page: 1, pageSize: MSG_PAGE_SIZE })
    let msgs: Message[] = result.records.map(mapMessage)

    // 后台流恢复：如果后端消息中缺少助手回答，检查 bgStreams
    const bg = bgStreams.get(id)
    if (bg) {
      const lastBackend = msgs[msgs.length - 1]
      if (lastBackend?.role === 'user') {
        // 后端缺少助手回答 → 用 bgStream 实时对象追加（同一引用，流继续写入 UI 可见）
        msgs = [...msgs, bg.assistantMsg]
      } else if (lastBackend?.role === 'assistant' && !lastBackend.content) {
        // 后端有空助手消息 → 替换为实时对象
        msgs = [...msgs.slice(0, -1), bg.assistantMsg]
      }
      // 流仍在运行时恢复 isStreaming 和 streamAbort
      if (!bg.abort.signal.aborted) {
        isStreaming.value = true
        streamAbort = bg.abort
      }
    }

    messages.value = msgs
    msgPage.value = result.pageNumber
    msgHasMore.value = result.pageNumber < result.totalPage

    // 检测对话是否未完成（仅刷新页面导致的中断，切换对话时有 bgStream 保活不在此列）
    const hasLiveStream = bg && !bg.abort.signal.aborted
    if (!hasLiveStream) {
      const lastMsg = messages.value[messages.value.length - 1]
      if (lastMsg?.role === 'user') {
        // 最后一条是用户消息、无助手回复 → 显示中断提示
        messages.value = [...messages.value, {
          id: `hint-${Date.now()}`,
          role: 'assistant',
          content: t('chat.answerInterrupted'),
          timestamp: '',
          isHint: true,
        }]
      } else if (lastMsg?.role === 'assistant' && !lastMsg.content && messages.value.length >= 2) {
        // 助手消息为空 → 移除并显示中断提示
        messages.value = [...messages.value.slice(0, -1), {
          id: `hint-${Date.now()}`,
          role: 'assistant',
          content: t('chat.answerInterrupted'),
          timestamp: '',
          isHint: true,
        }]
      }
    }
  } catch {
    messages.value = []
  }
  checkMsgAutoLoad()
}

const checkMsgAutoLoad = async () => {
  await nextTick()
  const el = scrollRef.value as HTMLElement | null
  if (!el || msgLoadingMore.value || !msgHasMore.value) return
  if (el.scrollHeight <= el.clientHeight + 50) {
    loadMoreMessages()
  }
}

const loadMoreMessages = async () => {
  if (msgLoadingMore.value || !msgHasMore.value || !activeConvId.value) return
  msgLoadingMore.value = true
  try {
    const nextPage = msgPage.value + 1
    const result = await getMessages({ conversationId: activeConvId.value, page: nextPage, pageSize: MSG_PAGE_SIZE })
    const newMsgs = result.records.map((dto, i) => mapMessage(dto, messages.value.length + i))
    messages.value = [...messages.value, ...newMsgs]
    msgPage.value = nextPage
    msgHasMore.value = result.pageNumber < result.totalPage
  } catch {
    // ignore
  } finally {
    msgLoadingMore.value = false
    checkMsgAutoLoad()
  }
}

const onMsgScroll = () => {
  const el = scrollRef.value as HTMLElement | null
  if (!el || msgLoadingMore.value || !msgHasMore.value) return
  // 滚动到底部附近时加载更多历史消息
  if (el.scrollHeight - el.scrollTop - el.clientHeight < 100) {
    loadMoreMessages()
  }
}

const deleteConversation = async (id: string) => {
  try {
    const message = await apiDeleteConversation(id)
    if (activeConvId.value === id) {
      streamAbort?.abort()
      streamAbort = null
      isStreaming.value = false
      activeConvId.value = null
      messages.value = []
    }
    // 清理后台流
    bgStreams.get(id)?.abort.abort()
    bgStreams.delete(id)
    await loadConversations()
    toast.success(message || t('chat.deleted'))
  } catch (err: any) {
    toast.error(err?.message || '删除失败')
  }
}

const requestDelete = (id: string) => {
  menuOpenId.value = null
  deleteConfirmId.value = id
}

const confirmDelete = async () => {
  const id = deleteConfirmId.value
  deleteConfirmId.value = null
  if (id) await deleteConversation(id)
}

let titleMaxToastAt = 0

function notifyTitleMaxLen() {
  const now = Date.now()
  if (now - titleMaxToastAt < 1500) return
  titleMaxToastAt = now
  toast.info(t('chat.titleMaxLen', { max: TITLE_MAX_LEN }))
}

const startRename = (id: string, title: string) => {
  editingConvId.value = id
  editingTitle.value = (title || '').slice(0, TITLE_MAX_LEN)
  menuOpenId.value = null
}

const onRenameTitleInput = () => {
  if (editingTitle.value.length <= TITLE_MAX_LEN) return
  editingTitle.value = editingTitle.value.slice(0, TITLE_MAX_LEN)
  notifyTitleMaxLen()
}

const finishRename = async () => {
  const id = editingConvId.value
  let title = editingTitle.value.trim()
  if (editingTitle.value.length > TITLE_MAX_LEN || title.length > TITLE_MAX_LEN) {
    notifyTitleMaxLen()
    title = title.slice(0, TITLE_MAX_LEN)
    editingTitle.value = title
  }
  editingConvId.value = null
  if (!id || !title) return
  try {
    const message = await renameConversation(id, title)
    const conv = conversations.value.find((c) => c.id === id)
    if (conv) conv.title = title
    toast.success(message || t('chat.renamed'))
  } catch (err: any) {
    toast.error(err?.message || t('chat.renameFailed'))
  }
}

const cancelRename = () => {
  editingConvId.value = null
}

const togglePin = async (conv: Conversation) => {
  menuOpenId.value = null
  const nextPinned = !conv.pinned
  try {
    const message = nextPinned
      ? await pinConversation(conv.id)
      : await unpinConversation(conv.id)
    conv.pinned = nextPinned
    toast.success(message || (nextPinned ? t('chat.pinned') : t('chat.unpinned')))
  } catch (err: any) {
    toast.error(err?.message || (nextPinned ? t('chat.pinFailed') : t('chat.unpinFailed')))
  }
}

// ── 发送消息（对接后端） ──

const handleSend = async (questionText?: string) => {
  const text = (questionText ?? inputValue.value).trim().slice(0, INPUT_MAX_LEN)
  if (!text || isStreaming.value) return

  const userMsg: Message = {
    id: `user-${Date.now()}`,
    role: 'user',
    content: text,
    timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }),
  }
  messages.value = [...messages.value, userMsg]
  if (!questionText) inputValue.value = ''
  isStreaming.value = true
  streamAbort = new AbortController()

  let assistantMsg: Message | null = null
  let sendConvId: string | null = null

  try {
    // 1. 首次发送时创建会话
    if (!activeConvId.value) {
      const conv = await createConversation({
        title: '',
        // title: text || t('chat.newChat'),
      })
      activeConvId.value = conv.id
      // 直接插入列表顶部，避免再发一次 GET /chat/conversations
      conversations.value = [conv, ...conversations.value.filter((c) => c.id !== conv.id)]
      // 把 cid 写入 URL，刷新时可定位到该会话
      router.replace({ query: { cid: conv.id } })
    }
    sendConvId = activeConvId.value!

    // 2. 流式发送消息
    assistantMsg = {
      id: `assistant-${Date.now()}`,
      role: 'assistant',
      content: '',
      timestamp: '',
      isStreaming: true,
      deepThinking: deepThinking.value,
      reasoning: '',
      reasoningDone: false,
      reasoningOpen: true,
      reasoningStartedAt: deepThinking.value ? Date.now() : undefined,
      routeReasoning: '',
      routeReasoningDone: false,
      routeReasoningOpen: true,
      routeReasoningStartedAt: Date.now(),
      docs: [],
    }
    messages.value = [...messages.value, assistantMsg]
    assistantMsg = messages.value[messages.value.length - 1]

    // 注册后台流（切换会话时不中断，可在回来后恢复）
    bgStreams.set(sendConvId, { abort: streamAbort!, userMsg, assistantMsg })

    await chatSendMessage(
      {
        conversationId: sendConvId,
        content: text,
        searchKbEnabled: searchScope.value.official,
        searchExternalEnabled: searchScope.value.external,
        kbIds: searchScope.value.selectedKbIds.join(','),
        deepThinking: deepThinking.value,
        // 自动模式（null）传 undefined，JSON 序列化时自动省略该字段
        enableInternetSearch: internetSearch.value,
      },
      (ev) => {
        // 捕获服务端返回的消息 ID，用于后续反馈接口
        if (ev.kind === 'ids') {
          userMsg.id = ev.userMessageId
          if (assistantMsg) assistantMsg.id = ev.assistantMessageId
          return
        }
        if (!assistantMsg) return
        applyChatStreamEvent(assistantMsg, ev)
      },
      streamAbort?.signal,
    )
    // 流正常完成
    bgStreams.delete(sendConvId)
    finalizeThinking(assistantMsg)
    assistantMsg.isStreaming = false
    assistantMsg.timestamp = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    // 仅当用户仍在该会话时更新侧边栏
    if (activeConvId.value === sendConvId) {
      const conv = conversations.value.find((c) => c.id === sendConvId)
      if (conv) { conv.lastMessage = assistantMsg.content.slice(0, 30); conv.updatedAt = '刚刚' }
    }
  } catch (err: any) {
    bgStreams.delete(sendConvId!)
    if (isAbortError(err)) {
      if (assistantMsg) {
        finalizeThinking(assistantMsg)
        assistantMsg.isStreaming = false
        assistantMsg.timestamp = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
      }
    } else {
    const errText = (err?.message as string)?.trim() || t('api.error.sendFailed')
    if (assistantMsg) {
      assistantMsg.isStreaming = false
      assistantMsg.isError = true
      assistantMsg.content = errText
      assistantMsg.timestamp = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    } else {
      messages.value = [...messages.value, {
        id: `assistant-err-${Date.now()}`,
        role: 'assistant',
        content: errText,
        timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }),
        isError: true,
      }]
    }
    }
  } finally {
    // 仅当仍在该会话时重置 UI 状态（后台流完成不干扰当前会话）
    if (activeConvId.value === sendConvId) {
      isStreaming.value = false
      streamAbort = null
    }
  }
}

function stopGenerate() {
  streamAbort?.abort()
}

const handleSampleClick = (q: string) => { inputValue.value = q.slice(0, INPUT_MAX_LEN) }

async function loadSampleQuestions() {
  try {
    // 按知识库列表顺序取当前选中的知识库（勾选顺序不代表列表顺序）
    const kbIds = kbOptions.value
      .filter((kb) => searchScope.value.selectedKbIds.includes(kb.kbId))
      .map((kb) => kb.kbId)
    if (!kbIds.length) {
      sampleQuestions.value = []
      return
    }
    const list = await getEnabledHotwords(kbIds)
    sampleQuestions.value = pickHotQuestions(list, kbIds).map((item) => item.content)
  } catch {
    sampleQuestions.value = []
  }
}

watch(() => searchScope.value.selectedKbIds, () => {
  loadSampleQuestions()
}, { deep: true })

const handleFeedback = async (msgId: string, type: 'helpful' | 'unhelpful') => {
  const msg = messages.value.find(m => m.id === msgId)
  if (!msg) return
  if (type === 'helpful') {
    feedbackMsgId.value = null
    try {
      await submitFeedback({ messageId: msgId, rating: 'HELPFUL' })
      msg.feedbackRating = 'HELPFUL'
      msg.badcaseType = undefined
      msg.badcaseComment = undefined
      toast.success(t('chat.feedbackThanks'))
    } catch (err: any) {
      cancelPendingToast()
      toast.error(err?.message || t('api.error.generic'))
    }
  } else {
    feedbackType.value = msg.badcaseType ?? ''
    feedbackComment.value = msg.badcaseComment ?? ''
    feedbackMsgId.value = msgId
  }
}
const handleBadcaseSubmit = async (msgId: string) => {
  if (!feedbackType.value) {
    toast.error(t('qa.badcaseTypeRequired'))
    return
  }
  const reason = BADCASE_REASON_MAP[feedbackType.value] || feedbackType.value
  try {
    await submitFeedback({
      messageId: msgId,
      rating: 'UNHELPFUL',
      reasons: reason,
      comment: feedbackComment.value.trim() || undefined,
    })
    const msg = messages.value.find(m => m.id === msgId)
    if (msg) {
      msg.feedbackRating = 'UNHELPFUL'
      msg.feedbackReasons = reason
      msg.badcaseType = feedbackType.value
      msg.badcaseComment = feedbackComment.value.trim()
    }
    toast.success(t('qa.badcaseSubmitted'))
    feedbackMsgId.value = null
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || t('api.error.generic'))
  }
}

const toggleKb = (kbId: string) => {
  const idx = searchScope.value.selectedKbIds.indexOf(kbId)
  if (idx >= 0) {
    if (searchScope.value.selectedKbIds.length <= 1) {
      toast.info(t('qa.pleaseSelectKb'))
      return
    }
    searchScope.value.selectedKbIds = searchScope.value.selectedKbIds.filter((id) => id !== kbId)
  } else {
    searchScope.value.selectedKbIds = [...searchScope.value.selectedKbIds, kbId]
  }
}

const toggleOfficial = () => {
  if (searchScope.value.official && !searchScope.value.external) {
    toast.info(t('qa.pleaseSelectKnowledgeSource'))
    return
  }
  searchScope.value.official = !searchScope.value.official
}

const toggleExternal = () => {
  if (searchScope.value.external && !searchScope.value.official) {
    toast.info(t('qa.pleaseSelectKnowledgeSource'))
    return
  }
  searchScope.value.external = !searchScope.value.external
}

const getSearchScopeLabel = (): string => {
  const parts: string[] = []
  if (searchScope.value.official) parts.push(t('chat.officialDoc'))
  if (searchScope.value.external) parts.push(t('chat.unofficialDoc'))
  if (kbOptions.value.length > 0 && searchScope.value.selectedKbIds.length === kbOptions.value.length) parts.push(t('chat.allKbs'))
  else if (searchScope.value.selectedKbIds.length > 0) parts.push(t('chat.kbCount', { count: searchScope.value.selectedKbIds.length }))
  else parts.push(t('chat.kb'))
  return parts.length ? parts.join(' + ') : t('chat.noScope')
}

const selectAllKbs = () => { searchScope.value.selectedKbIds = filterCv610ProOnly(kbOptions.value).map((kb) => kb.kbId) }
const clearKbs = () => { searchScope.value.selectedKbIds = [] }

let autoSent = false
onMounted(async () => {
  isMobile.value = window.innerWidth < 768
  document.addEventListener('pointerdown', onPointerDownOutside)
  void loadConversations()
  try {
    kbOptions.value = filterCv610ProOnly(await getKbOptions())
    if (!searchScope.value.selectedKbIds.length) selectAllKbs()
    loadSampleQuestions()
  } catch {
    kbOptions.value = []
  }
  const cid = route.query.cid as string
  const q = route.query.q as string
  if (cid) {
    // 刷新或直接访问已有对话：切换到该对话
    switchConversation(cid)
  } else if (q && !autoSent && !isStreaming.value) {
    autoSent = true
    void handleSend(q)
  }
  // 初始化 textarea 高度
  await nextTick()
  autoResize()
  setupCopyDelegate()
  // 页面尺寸/缩放变化时重新计算（换行数变了 scrollHeight 也会变）
  window.addEventListener('resize', autoResize)
})

const onConvScroll = () => {
  const el = convListRef.value
  // 搜索模式下不触发懒加载（搜索是本地过滤，无需从后端加载更多）
  if (!el || convLoadingMore.value || !convHasMore.value || searchConv.value.trim()) return
  if (el.scrollHeight - el.scrollTop - el.clientHeight < 50) {
    loadMoreConversations()
  }
}

// 内容未填满容器时，自动触发加载更多
const checkAutoLoad = async () => {
  await nextTick()
  const el = convListRef.value
  if (!el || convLoadingMore.value || !convHasMore.value || searchConv.value.trim()) return
  if (el.scrollHeight <= el.clientHeight) {
    loadMoreConversations()
  }
}

onUnmounted(() => {
  // 不中断流：后台流通过 bgStreams 追踪，页面回来后在 switchConversation 中恢复
  document.removeEventListener('pointerdown', onPointerDownOutside)
  window.removeEventListener('resize', autoResize)
  copyCleanup?.()
})

// Auto-scroll
const scrollRef = useTemplateRef('scrollRef')
const inputRef = ref<HTMLTextAreaElement | null>(null)

// Textarea auto-resize: 最小 44px(h-11), 最大 150px, 超出后滚动
const INPUT_MAX_H = 150
function autoResize() {
  const el = inputRef.value
  if (!el) return
  // 先设 auto 让 scrollHeight 反映真实内容高度
  el.style.height = 'auto'
  const sh = el.scrollHeight
  el.style.height = Math.min(sh, INPUT_MAX_H) + 'px'
  el.style.overflowY = sh > INPUT_MAX_H ? 'auto' : 'hidden'
}
// 内容变化时延迟一帧再算（等 v-model 更新 DOM）
watch(inputValue, () => requestAnimationFrame(autoResize))
watch([messages, () => isStreaming.value], async () => {
  await nextTick()
  if (scrollRef.value && !msgLoadingMore.value) {
    const el = scrollRef.value as HTMLElement
    el.scrollTop = el.scrollHeight
  }
})
</script>

<template>
  <div class="h-full min-h-0 overflow-hidden flex bg-frontend-bg">
    <!-- Left sidebar - conversations -->
    <div :class="cn('shrink-0 flex flex-col min-h-0 overflow-hidden bg-frontend-surface border-r border-frontend-border', isMobile ? (sidebarOpen ? 'fixed inset-y-0 left-0 top-16 z-40 w-72' : 'hidden') : 'w-64')">
      <div class="p-3 space-y-3 border-b border-frontend-border shrink-0">
        <button @click="newConversation" class="w-full h-10 bg-frontend-accent hover:bg-frontend-accent/90 text-white rounded-lg flex items-center justify-center gap-2 transition-colors text-sm font-medium">
          <Plus class="size-4" /> {{ t('chat.newChat') }}
        </button>
        <div class="relative">
          <Search class="absolute left-3 top-1/2 -translate-y-1/2 size-3.5 text-frontend-subtle" />
          <input v-model="searchConv" :placeholder="t('chat.history')" class="h-9 pl-9 text-sm bg-frontend-bg border border-frontend-border rounded-lg text-frontend-fg placeholder:text-frontend-subtle outline-none focus:ring-2 focus:ring-frontend-accent/20 w-full px-3" />
        </div>
      </div>
      <div ref="convListRef" class="flex-1 overflow-y-auto" @scroll="onConvScroll">
        <div class="p-2 space-y-1">
          <div v-for="conv in filteredConversations" :key="conv.id" @click="switchConversation(conv.id)" :class="cn('group relative flex items-start gap-2 px-3 py-2.5 rounded-lg cursor-pointer transition-colors', activeConvId === conv.id ? 'bg-frontend-accent/10' : 'hover:bg-frontend-accent/5')">
            <MessageSquare :class="cn('size-4 shrink-0 mt-0.5', activeConvId === conv.id ? 'text-frontend-fg' : 'text-frontend-subtle')" />
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-1 min-w-0">
                <Pin v-if="conv.pinned && editingConvId !== conv.id" class="size-3 text-frontend-fg shrink-0" />
                <input
                  v-if="editingConvId === conv.id"
                  v-model="editingTitle"
                  @input="onRenameTitleInput"
                  @keydown.enter.prevent="finishRename"
                  @keydown.esc="cancelRename"
                  @blur="finishRename"
                  @click.stop
                  autofocus
                  class="h-7 px-2 text-sm font-medium bg-white border border-frontend-accent/30 rounded outline-none focus:ring-1 focus:ring-frontend-accent/30 w-full"
                />
                <span v-else :class="cn('text-sm font-medium truncate', activeConvId === conv.id ? 'text-frontend-fg' : 'text-frontend-accent-light')">{{ conv.title }}</span>
              </div>
              <template v-if="editingConvId !== conv.id">
                <p class="text-xs text-frontend-subtle truncate mt-0.5">{{ conv.lastMessage }}</p>
                <p class="text-[10px] text-frontend-subtle mt-1">{{ conv.updatedAt }}</p>
              </template>
            </div>
            <div v-if="editingConvId !== conv.id" class="relative shrink-0" data-conv-menu>
              <button @click.stop="menuOpenId = menuOpenId === conv.id ? null : conv.id" class="opacity-0 group-hover:opacity-100 p-1 rounded hover:bg-frontend-accent/10 text-frontend-subtle hover:text-frontend-fg transition-all size-6 flex items-center justify-center" :title="t('chat.moreActions')">
                <MoreHorizontal class="size-3.5" />
              </button>
              <div v-if="menuOpenId === conv.id" @click.stop class="absolute right-0 top-full mt-1 w-36 rounded-lg border border-frontend-border bg-white shadow-lg z-30 overflow-hidden">
                <button @click="togglePin(conv)" class="w-full px-3 py-2 text-left text-xs text-[#2C3E55] hover:bg-frontend-accent/5 transition-colors flex items-center gap-2">
                  <PinOff v-if="conv.pinned" class="size-3.5" />
                  <Pin v-else class="size-3.5" />
                  {{ conv.pinned ? t('chat.unpin') : t('chat.pin') }}
                </button>
                <button @click="startRename(conv.id, conv.title)" class="w-full px-3 py-2 text-left text-xs text-[#2C3E55] hover:bg-frontend-accent/5 transition-colors flex items-center gap-2">
                  <Pencil class="size-3.5" /> {{ t('chat.rename') }}
                </button>
                <div class="h-px bg-frontend-border" />
                <button @click="requestDelete(conv.id)" class="w-full px-3 py-2 text-left text-xs text-red-500 hover:bg-red-50 transition-colors flex items-center gap-2">
                  <Trash2 class="size-3.5" /> {{ t('chat.deleteConversation') }}
                </button>
              </div>
            </div>
          </div>
          <!-- 首次加载中 -->
          <div v-if="convLoading" class="text-center py-8 text-xs text-frontend-subtle">{{ t('common.loading') }}</div>
          <div v-else-if="filteredConversations.length === 0" class="text-center py-8 text-xs text-frontend-subtle">{{ t('common.noData') }}</div>
          <!-- 加载更多提示 -->
          <div v-if="convHasMore && !convLoading" class="py-3 text-center">
            <span v-if="convLoadingMore" class="text-xs text-frontend-subtle">{{ t('common.loading') }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Mobile overlay -->
    <div v-if="isMobile && sidebarOpen" class="fixed inset-0 bg-black/20 z-30 top-16" @click="sidebarOpen = false" />

    <!-- Center chat area -->
    <div class="flex-1 flex flex-col min-w-0 min-h-0">
      <div class="h-12 flex items-center px-4 border-b border-frontend-border bg-frontend-surface shrink-0">
        <button v-if="isMobile" @click="sidebarOpen = true" class="mr-2 text-frontend-fg hover:bg-frontend-accent/5 p-1.5 rounded-lg">
          <Menu class="size-5" />
        </button>
        <div class="flex items-center gap-2">
          <Cpu class="size-4 text-frontend-fg" />
          <span class="text-sm font-semibold text-frontend-fg">{{ activeConvId ? (conversations.find(c => c.id === activeConvId)?.title || t('chat.title')) : t('chat.newChat') }}</span>
        </div>
      </div>

      <!-- Messages area -->
      <div ref="scrollRef" class="flex-1 overflow-y-auto min-h-0" @scroll="onMsgScroll">
        <div class="max-w-3xl mx-auto px-4 py-6 space-y-6">
          <!-- Welcome -->
          <div v-if="messages.length === 0" class="text-center py-12">
            <div class="size-16 rounded-2xl bg-frontend-accent/5 flex items-center justify-center mx-auto mb-4">
              <Cpu class="size-8 text-frontend-fg" />
            </div>
            <h2 class="text-xl font-bold text-frontend-fg mb-2">{{ t('chat.welcome') }}</h2>
            <p class="text-sm text-frontend-muted mb-8 max-w-md mx-auto">{{ t('chat.welcomeDesc') }}</p>
            <p class="text-sm text-frontend-muted mb-3">{{ t('qa.tryTheseQuestions') }}</p>
            <div class="flex flex-wrap gap-2 justify-center max-w-lg mx-auto">
              <button v-for="(q, i) in displayedSampleQuestions" :key="i" @click="handleSampleClick(q)" class="px-3 py-2 text-xs rounded-lg border border-frontend-input-border bg-frontend-surface text-frontend-muted hover:text-frontend-fg hover:border-frontend-accent/30 transition-colors text-left">{{ q }}</button>
            </div>
            <div v-if="sampleQuestions.length > 4" class="mt-2">
              <button
                type="button"
                @click="hotQuestionsExpanded = !hotQuestionsExpanded"
                class="inline-flex items-center gap-1 text-xs text-frontend-muted hover:text-frontend-fg transition-colors"
              >
                {{ hotQuestionsExpanded ? t('common.collapse') : t('common.expand') }}
                <ChevronDown :class="['size-3 transition-transform duration-200', hotQuestionsExpanded && 'rotate-180']" />
              </button>
            </div>
          </div>

          <!-- Messages -->
          <div v-for="msg in messages" :key="msg.id" :class="cn('flex gap-3', msg.role === 'user' ? 'justify-end' : 'justify-start')">
            <div v-if="msg.role === 'assistant'" class="size-8 shrink-0 rounded-full bg-frontend-accent/10 flex items-center justify-center mt-1">
              <Cpu class="size-4 text-frontend-fg" />
            </div>
            <div :class="cn('max-w-[85%] min-w-0', msg.role === 'user' ? 'items-end' : '')">
              <div v-if="msg.role === 'user'" class="flex flex-col items-end gap-1.5">
                <div v-if="msg.content" class="bg-frontend-accent text-white px-4 py-2.5 rounded-2xl rounded-tr-sm break-all">
                  <p class="text-sm leading-relaxed">{{ msg.content }}</p>
                </div>
              </div>
              <div v-else class="space-y-3">
                <div v-if="msg.isError" class="flex items-start gap-2 rounded-xl border border-red-200 bg-red-50 px-3 py-2.5">
                  <AlertCircle class="size-4 text-red-500 shrink-0 mt-0.5" />
                  <p class="text-sm text-red-700 leading-relaxed">{{ msg.content }}</p>
                </div>
                <div v-else-if="msg.isHint" class="flex items-center justify-center py-2">
                  <span class="text-xs text-frontend-subtle bg-frontend-accent/5 rounded-full px-4 py-1.5">{{ msg.content }}</span>
                </div>
                <template v-else>
                <div v-if="msg.routeReasoning" class="border border-[#DCE9F7] rounded-xl bg-[#F0F6FD] overflow-hidden mb-1">
                  <button
                    type="button"
                    class="w-full flex items-center gap-2 px-3 py-2 text-left hover:bg-[#E2ECF8] transition-colors"
                    @click="msg.routeReasoningOpen = !msg.routeReasoningOpen"
                  >
                    <Compass :class="cn('size-4 text-[#4A6FA5] shrink-0', !msg.routeReasoningDone && 'animate-pulse text-[#2463EB]')" />
                    <span class="text-xs font-medium text-[#4A6FA5] flex-1">{{ routeReasoningTitle(msg) }}</span>
                    <span v-if="routeReasoningCountLabel(msg)" class="text-[10px] text-[#8BA5C5] tabular-nums">{{ routeReasoningCountLabel(msg) }}</span>
                    <ChevronDown :class="cn('size-3.5 text-[#8BA5C5] transition-transform duration-200', msg.routeReasoningOpen && 'rotate-180')" />
                  </button>
                  <div v-if="msg.routeReasoningOpen && msg.routeReasoning" class="px-3 pb-3 pt-2 text-xs text-[#4A6FA5] whitespace-pre-wrap leading-relaxed border-t border-[#DCE9F7]/70">
                    {{ msg.routeReasoning }}
                    <span v-if="!msg.routeReasoningDone" class="thinking-dots ml-1" style="color: #2463EB">
                      <span class="thinking-dot" style="animation-delay: 0s" />
                      <span class="thinking-dot" style="animation-delay: 0.2s" />
                      <span class="thinking-dot" style="animation-delay: 0.4s" />
                    </span>
                  </div>
                </div>
                <div v-if="msg.deepThinking || msg.reasoning" class="border border-[#E8E4D9] rounded-xl bg-[#F8F7F2] overflow-hidden mb-1">
                  <button
                    type="button"
                    class="w-full flex items-center gap-2 px-3 py-2 text-left hover:bg-[#F0EDE3] transition-colors"
                    @click="msg.reasoningOpen = !msg.reasoningOpen"
                  >
                    <Brain :class="cn('size-4 text-[#6B7A90] shrink-0', !msg.reasoningDone && 'animate-pulse text-[#23395D]')" />
                    <span class="text-xs font-medium text-[#6B7A90] flex-1">{{ thinkingTitle(msg) }}</span>
                    <span v-if="thinkingCountLabel(msg)" class="text-[10px] text-[#9AA8BC] tabular-nums">{{ thinkingCountLabel(msg) }}</span>
                    <ChevronDown :class="cn('size-3.5 text-[#9AA8BC] transition-transform duration-200', msg.reasoningOpen && 'rotate-180')" />
                  </button>
                  <div v-if="msg.reasoningOpen && msg.reasoning" class="px-3 pb-3 pt-2 text-xs text-[#6B7A90] whitespace-pre-wrap leading-relaxed border-t border-[#E8E4D9]/70">
                    {{ msg.reasoning }}
                    <span v-if="!msg.reasoningDone" class="thinking-dots ml-1" style="color: #23395D">
                      <span class="thinking-dot" style="animation-delay: 0s" />
                      <span class="thinking-dot" style="animation-delay: 0.2s" />
                      <span class="thinking-dot" style="animation-delay: 0.4s" />
                    </span>
                  </div>
                </div>
                <div v-if="msg.content" class="prose prose-sm max-w-none break-words [overflow-wrap:anywhere] [&_code]:break-words [&_code]:[white-space:pre-wrap] [&_code]:[overflow-wrap:anywhere] [&_pre]:max-w-full [&_pre]:[overflow-wrap:normal] [&_table]:max-w-full [&_img]:max-w-full" v-html="renderMarkdown(msg.content)"></div>
                <span v-if="msg.isStreaming && (!msg.deepThinking || msg.reasoningDone)" class="thinking-dots ml-1 text-frontend-accent">
                  <span class="thinking-dot" style="animation-delay: 0s" />
                  <span class="thinking-dot" style="animation-delay: 0.2s" />
                  <span class="thinking-dot" style="animation-delay: 0.4s" />
                </span>
                <!-- 智能搜索入口（DeepSeek 风格）：搜索到 N 个网页 -->
                <button
                  v-if="internetSearchResults(msg.docs).length"
                  type="button"
                  class="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg border border-[#E0DCD0] bg-[#FAF9F5] text-xs text-[#5A6B85] hover:border-[#2463EB]/40 hover:text-[#2463EB] transition-colors w-fit"
                  @click="openSearchResults(msg.docs)"
                >
                  <Globe class="size-3.5 shrink-0" />
                  <span>{{ t('chat.foundWebPages', { n: internetSearchResults(msg.docs).length }) }}</span>
                  <ChevronRight class="size-3.5 shrink-0" />
                </button>
                <div v-if="kbDocsOnly(msg.docs).length" class="mt-3 pt-3 border-t border-[#E8E4D9] text-sm">
                  <span class="text-[#9AA8BC]">{{ t('qa.relatedKnowledge') }}</span>
                  <span v-for="(doc, i) in kbDocsOnly(msg.docs)" :key="doc.id || i">
                    <button
                      type="button"
                      class="text-[#2563EB] underline underline-offset-2 decoration-[#2563EB]/50 hover:decoration-[#2563EB] transition-colors"
                      @click="preview.openDoc(doc, kbDocsOnly(msg.docs))"
                    >
                      {{ chatDocTitle(doc) }}<span v-if="chatDocPageNum(doc)" class="text-xs ml-0.5">（{{ t('qa.docPageShort', { page: chatDocPageNum(doc) }) }}）</span>
                    </button>
                    <span v-if="i < kbDocsOnly(msg.docs).length - 1" class="text-[#9AA8BC]">、</span>
                  </span>
                </div>

                <!-- Feedback -->
                <div v-if="!msg.isStreaming" class="flex items-center gap-2 pt-1">
                  <span class="text-xs text-frontend-subtle">{{ t('chat.helpfulQuestion') }}</span>
                  <button @click="handleFeedback(msg.id, 'helpful')"
                    :class="[
                      'h-7 px-2 text-xs rounded transition-colors flex items-center gap-1',
                      msg.feedbackRating === 'HELPFUL'
                        ? 'text-emerald-500 bg-emerald-50 font-medium'
                        : 'text-frontend-subtle hover:text-emerald-500 hover:bg-emerald-50'
                    ]">
                    <ThumbsUp class="size-3.5" /> {{ t('chat.helpful') }}
                  </button>
                  <div class="relative">
                    <button @click="handleFeedback(msg.id, 'unhelpful')"
                      :class="[
                        'h-7 px-2 text-xs rounded transition-colors flex items-center gap-1',
                        msg.feedbackRating === 'UNHELPFUL'
                          ? 'text-red-500 bg-red-50 font-medium'
                          : 'text-frontend-subtle hover:text-red-500 hover:bg-red-50'
                      ]">
                      <ThumbsDown class="size-3.5" /> {{ t('chat.unhelpful') }}
                    </button>
                    <div v-if="feedbackMsgId === msg.id" class="absolute top-full right-0 mt-1 w-72 bg-white border border-[#E0DCD0] rounded-lg shadow-lg z-30 overflow-hidden">
                      <div class="px-3 py-2 border-b border-[#E8E4D9] flex items-center justify-between">
                        <span class="text-xs font-medium text-[#23395D]">{{ t('qa.selectBadcaseType') }}</span>
                        <button class="size-5 flex items-center justify-center hover:bg-[#23395D]/5 rounded" @click="feedbackMsgId = null"><X class="size-3 text-[#5A6B85]" /></button>
                      </div>
                      <div class="p-3 space-y-3">
                        <div class="space-y-1.5">
                          <label class="text-xs text-[#5A6B85]">{{ t('qa.badcaseTypeLabel') }}</label>
                          <div class="flex flex-wrap gap-1.5">
                            <button
                              v-for="bt in BADCASE_TYPES"
                              :key="bt"
                              :class="[
                                'px-2.5 py-1 text-xs rounded-md border transition-colors',
                                feedbackType === bt
                                  ? 'bg-red-50 border-red-300 text-red-600'
                                  : 'bg-white border-[#E0DCD0] hover:border-[#23395D]/30 text-[#5A6B85] hover:text-[#23395D]'
                              ]"
                              @click="feedbackType = bt"
                            >{{ bt }}</button>
                          </div>
                        </div>
                        <div class="space-y-1.5">
                          <div class="flex items-center justify-between">
                            <label class="text-xs text-[#5A6B85]">{{ t('qa.badcaseDescLabel') }}</label>
                            <span class="text-[11px] text-[#9AA8BC]">{{ feedbackComment.length }}/200</span>
                          </div>
                          <textarea
                            v-model="feedbackComment"
                            class="w-full min-h-[72px] px-2.5 py-2 text-xs rounded-md bg-white border border-[#E0DCD0] focus:outline-none focus:ring-1 focus:ring-[#2563EB] resize-none text-[#2D3E5A]"
                            :placeholder="t('qa.badcaseDescPlaceholder')"
                            maxlength="200"
                          />
                        </div>
                        <button
                          class="w-full h-8 bg-[#23395D] hover:bg-[#23395D]/90 text-white rounded-md text-xs font-medium transition-colors disabled:opacity-50"
                          :disabled="!feedbackType"
                          @click="handleBadcaseSubmit(msg.id)"
                        >{{ msg.badcaseType ? t('qa.badcaseResubmit') : t('qa.badcaseSubmit') }}</button>
                        <p v-if="msg.badcaseType" class="text-[11px] text-[#9AA8BC] text-center">{{ t('qa.badcaseSubmittedHint', { type: msg.badcaseType }) }}</p>
                      </div>
                    </div>
                  </div>
                </div>
                </template>
              </div>
            </div>
            <div v-if="msg.role === 'user'" class="size-8 shrink-0 rounded-full bg-frontend-accent/10 flex items-center justify-center mt-1">
              <span class="text-xs font-medium text-frontend-fg">{{ t('chat.me') }}</span>
            </div>
          </div>
          <!-- 加载更多历史消息 -->
          <div v-if="msgHasMore" class="text-center py-2">
            <span v-if="msgLoadingMore" class="text-xs text-frontend-subtle">{{ t('common.loading') }}</span>
          </div>
        </div>
      </div>

      <!-- Input area -->
      <div class="p-4 border-t border-frontend-border bg-frontend-surface shrink-0">
        <div class="max-w-3xl mx-auto">
          <!-- Toolbar -->
          <div class="flex items-center gap-3 mb-2">
            <button type="button" @click="deepThinking = !deepThinking" :class="cn('flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-full border transition-colors whitespace-nowrap shrink-0', deepThinking ? 'bg-[#2463EB] border-[#2463EB] text-white' : 'bg-[#FAF9F5] border-[#E0DCD0] text-[#6B7A90] hover:border-[#23395D]/20 hover:text-[#23395D]')">
              <Brain class="size-3.5 shrink-0" /> {{ t('chat.deepThinking') }}
            </button>
            <!-- 智能搜索（二态开关：选中高亮，默认关闭） -->
            <button type="button" @click="internetSearch = !internetSearch" :class="cn('flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-full border transition-colors whitespace-nowrap shrink-0', internetSearch ? 'bg-[#2463EB] border-[#2463EB] text-white' : 'bg-[#FAF9F5] border-[#E0DCD0] text-[#6B7A90] hover:border-[#23395D]/20 hover:text-[#23395D]')">
              <Globe class="size-3.5 shrink-0" /> {{ t('chat.internetSearch') }}
            </button>
            <div ref="searchScopeRoot" class="relative shrink-0">
              <button type="button" @click="searchScopeOpen = !searchScopeOpen" :class="cn('flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-full border transition-colors whitespace-nowrap', (searchScope.official || searchScope.external || searchScope.selectedKbIds.length) ? 'bg-[#23395D]/10 border-[#23395D]/30 text-[#23395D]' : 'bg-[#FAF9F5] border-[#E0DCD0] text-[#6B7A90] hover:border-[#23395D]/20 hover:text-[#23395D]')">
                <Search class="size-3.5 shrink-0" /> {{ t('chat.searchScope') }}：{{ getSearchScopeLabel() }}
                <ChevronDown v-if="searchScopeOpen" class="size-3 shrink-0" />
                <ChevronRight v-else class="size-3 shrink-0" />
              </button>
              <div v-if="searchScopeOpen" class="absolute bottom-full left-0 mb-2 w-64 rounded-lg border border-[#E0DCD0] bg-white shadow-lg z-50 overflow-hidden">
                <div class="p-3 space-y-2">
                  <p class="text-[11px] font-medium text-[#23395D] uppercase tracking-wider">{{ t('chat.searchScope') }}</p>
                  <label class="flex items-center gap-2 py-1.5 cursor-pointer hover:bg-[#FAF9F5] -mx-2 px-2 rounded" @click.prevent="toggleOfficial">
                    <input type="checkbox" :checked="searchScope.official" class="sr-only" />
                    <div :class="cn('size-4 rounded border flex items-center justify-center transition-colors', searchScope.official ? 'bg-[#23395D] border-[#23395D]' : 'border-[#D0CCBF]')">
                      <Check v-if="searchScope.official" class="size-3 text-white" />
                    </div>
                    <span class="text-sm text-[#2C3E55]">{{ t('chat.officialDoc') }}</span>
                  </label>
                  <label class="flex items-center gap-2 py-1.5 cursor-pointer hover:bg-[#FAF9F5] -mx-2 px-2 rounded" @click.prevent="toggleExternal">
                    <input type="checkbox" :checked="searchScope.external" class="sr-only" />
                    <div :class="cn('size-4 rounded border flex items-center justify-center transition-colors', searchScope.external ? 'bg-[#23395D] border-[#23395D]' : 'border-[#D0CCBF]')">
                      <Check v-if="searchScope.external" class="size-3 text-white" />
                    </div>
                    <span class="text-sm text-[#2C3E55]">{{ t('chat.unofficialDoc') }}</span>
                  </label>
                  <div class="pt-1 border-t border-[#EFECE2]">
                    <div class="flex items-center justify-between py-1.5">
                      <button type="button" @click="selectAllKbs" class="text-[11px] text-[#23395D] hover:underline">{{ t('common.selectAll') }}</button>
                      <button type="button" @click="clearKbs" class="text-[11px] text-[#9AA8BC] hover:underline">{{ t('chat.clear') }}</button>
                    </div>
                    <div class="grid grid-cols-2 gap-1 mt-1 max-h-40 overflow-y-auto">
                      <label v-for="kb in kbOptions" :key="kb.kbId" class="flex items-center gap-1.5 py-1 cursor-pointer hover:bg-[#FAF9F5] rounded px-1 min-w-0" @click.prevent="toggleKb(kb.kbId)">
                        <input type="checkbox" class="sr-only" :checked="searchScope.selectedKbIds.includes(kb.kbId)" />
                        <div :class="cn('size-3.5 rounded border flex items-center justify-center transition-colors shrink-0', searchScope.selectedKbIds.includes(kb.kbId) ? 'bg-[#23395D] border-[#23395D]' : 'border-[#D0CCBF]')">
                          <Check v-if="searchScope.selectedKbIds.includes(kb.kbId)" class="size-2.5 text-white" />
                        </div>
                        <span class="text-xs text-[#2C3E55] font-mono truncate">{{ displayKbName(kb.kbName) || kb.kbId }}</span>
                      </label>
                      <div v-if="kbOptions.length === 0" class="col-span-2 text-center py-2 text-xs text-[#9AA8BC]">{{ t('common.noData') }}</div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div class="flex items-center gap-2">
            <div class="relative flex-1 min-w-0">
              <textarea
                ref="inputRef"
                v-model="inputValue"
                @keydown.enter.exact.prevent="handleSend()"
                :placeholder="t('home.searchPlaceholder')"
                rows="1"
                :maxlength="INPUT_MAX_LEN"
                class="block w-full min-h-11 m-0 box-border px-4 py-3 pr-14 text-sm text-frontend-fg leading-5 rounded-xl border border-frontend-input-border bg-frontend-input-bg resize-none outline-none focus:ring-2 focus:ring-frontend-accent/20 focus:border-frontend-accent/30 transition-none"
              />
              <span class="pointer-events-none absolute right-3 bottom-2 text-[11px] text-frontend-subtle tabular-nums">{{ inputValue.length }}/{{ INPUT_MAX_LEN }}</span>
            </div>
            <button v-if="isStreaming" type="button" @click="stopGenerate" class="box-border h-11 px-5 m-0 bg-frontend-accent hover:bg-frontend-accent/90 text-white rounded-xl border border-transparent inline-flex items-center justify-center gap-2 transition-colors shrink-0 text-sm leading-none">
              <Square class="size-3.5 fill-current" /> {{ t('chat.stopGenerate') }}
            </button>
            <button v-else type="button" @click="handleSend()" :disabled="!inputValue.trim()" class="box-border h-11 px-5 m-0 bg-frontend-accent hover:bg-frontend-accent/90 text-white rounded-xl border border-transparent inline-flex items-center justify-center gap-2 transition-colors disabled:opacity-50 shrink-0 text-sm leading-none">
              <Send class="size-4" /> {{ t('chat.send') }}
            </button>
          </div>
          <p class="text-xs text-frontend-subtle mt-2 text-center">{{ t('chat.disclaimer') }}</p>
        </div>
      </div>
    </div>
  </div>

  <!-- 删除会话确认弹窗 -->
  <div v-if="deleteConfirmId" class="fixed inset-0 z-50 flex items-center justify-center bg-black/20" @click.self="deleteConfirmId = null">
    <div class="bg-white rounded-lg shadow-lg max-w-sm w-full mx-4 p-5">
      <div class="flex items-center gap-2 mb-3">
        <AlertCircle class="size-5 text-red-500 shrink-0" />
        <h3 class="text-sm font-semibold text-frontend-fg">{{ t('chat.confirmDeleteTitle') }}</h3>
      </div>
      <p class="text-sm text-frontend-muted mb-5">{{ t('chat.confirmDeleteDesc') }}</p>
      <div class="flex justify-end gap-2">
        <button @click="deleteConfirmId = null" class="h-8 px-4 rounded-md border border-frontend-border text-sm text-frontend-muted hover:bg-frontend-accent/5 transition-colors">{{ t('common.cancel') }}</button>
        <button @click="confirmDelete" class="h-8 px-4 rounded-md bg-red-500 text-white text-sm hover:bg-red-600 transition-colors">{{ t('common.delete') }}</button>
      </div>
    </div>
  </div>

  <!-- 智能搜索结果侧栏（从顶栏下方开始，避免被 sticky header 遮挡；无遮罩，点击空白不关闭） -->
  <div v-if="searchPanelOpen" class="fixed top-16 bottom-0 right-0 z-40 w-full sm:w-96 bg-white border-l border-frontend-border shadow-xl flex flex-col">
    <div class="h-12 flex items-center justify-between px-4 border-b border-frontend-border shrink-0">
      <div class="flex items-center gap-2">
        <Globe class="size-4 text-[#2463EB]" />
        <span class="text-sm font-semibold text-frontend-fg">{{ t('chat.searchResults') }}</span>
        <span class="text-xs text-frontend-subtle tabular-nums">{{ searchPanelItems.length }}</span>
      </div>
      <button type="button" class="p-1.5 rounded hover:bg-frontend-accent/10 text-frontend-subtle hover:text-frontend-fg transition-colors" :title="t('common.close')" @click="searchPanelOpen = false">
        <X class="size-4" />
      </button>
    </div>
    <div class="flex-1 overflow-y-auto min-h-0 p-3 space-y-3">
      <a
        v-for="(r, i) in searchPanelItems"
        :key="i"
        :href="r.url"
        target="_blank"
        rel="noopener noreferrer"
        class="block rounded-xl border border-frontend-border p-3 hover:border-[#2463EB]/40 hover:bg-[#F0F6FD] transition-colors"
      >
        <div class="flex items-center gap-1.5 text-xs text-frontend-subtle mb-1.5">
          <Globe class="size-3 shrink-0" />
          <span class="truncate">{{ r.source }}</span>
        </div>
        <p class="text-sm font-medium text-[#23395D] leading-snug line-clamp-2 mb-1.5" :title="r.title">{{ r.title }}</p>
        <p class="text-xs text-frontend-subtle leading-relaxed line-clamp-3" :title="r.snippet">{{ r.snippet }}</p>
      </a>
    </div>
  </div>

  <DocumentPreviewDrawer
    :open="preview.open"
    :title="preview.title"
    :loading="preview.previewLoading"
    :preview-type="preview.previewType"
    :preview-content="preview.previewContent"
    :preview-is-html="preview.previewIsHtml"
    :preview-blob-url="preview.previewBlobUrl"
    :iframe-src="preview.iframeSrc"
    :preview-lines="preview.previewLines"
    :highlight-content="preview.highlightContent"
    :copy-text="preview.copyText"
    :preview-pptx-buffer="preview.previewPptxBuffer"
    :preview-binary="preview.previewBinary"
    :dl-progress="preview.dlProgress"
    @close="preview.closePreview"
  />
</template>

<style scoped>
.thinking-dots {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  vertical-align: middle;
}

.thinking-dot {
  display: inline-block;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background-color: currentColor;
  animation: thinking-bounce 1.2s ease-in-out infinite both;
}

@keyframes thinking-bounce {
  0%, 60%, 100% {
    transform: translateY(0) scale(0.4);
    opacity: 0.3;
  }
  30% {
    transform: translateY(-6px) scale(1);
    opacity: 1;
  }
}
</style>
