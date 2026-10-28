<script setup lang="ts">
import { ref, computed, nextTick, watch, onMounted, onUnmounted } from 'vue'
import { marked } from 'marked'
import {
  Bot, Send, X, ThumbsUp, ThumbsDown, Copy, Check,
  FileText, Lightbulb, Code2, BookOpen,
  Table2, FileSpreadsheet, ExternalLink, Square,
  Compass, ChevronDown,
} from 'lucide-vue-next'
import { cn, filterCv610ProOnly } from '@/lib/utils'
import { toast } from '@/lib/toast'
import { cancelPendingToast } from '@/services/api-client'
import { copyText } from '@/lib/clipboard'
import { chatDocTitle, chatDocPageNum } from '@/lib/chat-docs'
import { MOCK_DOC_DETAILS, type IDocDetail } from '@/data/frontend'
import { useI18n } from '@/composables/useI18n'
import { useDocumentFilePreview } from '@/composables/useDocumentFilePreview'
import DocumentPreviewDrawer from '@/components/DocumentPreviewDrawer.vue'
import {
  createConversation,
  sendMessage as chatSendMessage,
  applyChatStreamEvent,
  finalizeThinking,
  isAbortError,
  submitFeedback,
  BADCASE_REASON_MAP,
  type ChatStreamDoc,
} from '@/services/chat.service'
import { getKbOptions } from '@/services/knowledge.service'
import { getEnabledHotwords, pickHotQuestions } from '@/services/hotword.service'

// ── Types ──
type ParagraphType = 'text' | 'heading' | 'list-item' | 'code' | 'related-knowledge'

interface Paragraph {
  type: ParagraphType
  content: string
  level?: number
  refs?: { title: string; refType: 'doc' | 'code' | 'pdf' | 'table' | 'register'; refId: string }[]
  codeLang?: string
}

interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  paragraphs?: Paragraph[]
  /** 反馈评级（后端返回值：HELPFUL / UNHELPFUL / null） */
  feedbackRating?: string | null
  /** UI 层：badcase 中文标签（从 feedbackReasons 反向映射） */
  badcaseType?: string
  /** 反馈描述 */
  badcaseComment?: string
  timestamp: string
  isStreaming?: boolean
  isError?: boolean
  relatedQuestions?: string[]
  docs?: ChatStreamDoc[]
  routeReasoning?: string
  routeReasoningDone?: boolean
  routeReasoningOpen?: boolean
  routeReasoningStartedAt?: number
}

const { t, locale } = useI18n()
const preview = useDocumentFilePreview()

const quickQuestions = ref<string[]>([])
const quickQuestionsExpanded = ref(false)
const displayedQuickQuestions = computed(() =>
  quickQuestionsExpanded.value ? quickQuestions.value : quickQuestions.value.slice(0, 4),
)

const BADCASE_TYPES = ['回答错误', '答非所问', '结果不相关', '代码错误', '信息过时']

const REF_TYPE_LABEL: Record<string, string> = {
  doc: 'bot.refTechDoc',
  code: 'bot.refCodeFile',
  pdf: 'bot.refPdfDoc',
  table: 'bot.refDataTable',
  register: 'bot.refRegisterManual',
}

const copyIcon = '<svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>'
const checkIcon = '<svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="text-green-500"><polyline points="20 6 9 17 4 12"/></svg>'
const copiedCodeId = ref<string | null>(null)

// ── State ──
const isOpen = ref(false)
function getInitialMessages(): Message[] {
  return [
    {
      id: 'welcome',
      role: 'assistant',
      content: t('bot.welcomeMsg'),
      timestamp: '10:00',
    },
  ]
}

const messages = ref<Message[]>(getInitialMessages())

watch(locale, () => {
  messages.value = getInitialMessages()
  activeConversationId = null
  hasUnread.value = false
  lastReadMessageCount.value = 0
})

// 关闭悬浮窗时，新消息或有正在回答中的消息则标记未读红点
watch(isOpen, (val) => {
  if (val) {
    if (selectedKbIds.value.length) loadSampleQuestions(selectedKbIds.value)
  } else {
    const hasNewMessages = messages.value.length > lastReadMessageCount.value
    const hasStreaming = messages.value.some((m) => m.isStreaming)
    if (hasNewMessages || hasStreaming) {
      hasUnread.value = true
    }
  }
})
const INPUT_MAX_LEN = 500
const inputValue = ref('')
const isTyping = ref(false)
const isBusy = computed(() => isTyping.value || messages.value.some((m) => m.isStreaming))
let streamAbort: AbortController | null = null
const hasUnread = ref(false)
/** 上次打开弹窗时的消息数量，用于判断关闭后是否有新消息 */
const lastReadMessageCount = ref(0)
const feedbackMsgId = ref<string | null>(null)
const feedbackType = ref('')
const feedbackComment = ref('')
const scrollRef = ref<HTMLElement | null>(null)
const docDetail = ref<IDocDetail | null>(null)
const selectedKbIds = ref<string[]>([])
let activeConversationId: string | null = null

function isUnhelpfulActive(msg: Message) {
  return msg.feedbackRating === 'UNHELPFUL' || feedbackMsgId.value === msg.id
}

/** 收起差评面板；未提交类型时一并取消点踩高亮 */
function closeBadFeedback(opts?: { keepFeedback?: boolean }) {
  const id = feedbackMsgId.value
  if (id && !opts?.keepFeedback) {
    messages.value = messages.value.map((m) =>
      m.id === id && m.feedbackRating === 'UNHELPFUL' ? { ...m, feedbackRating: null, badcaseType: undefined, badcaseComment: undefined } : m,
    )
  }
  feedbackMsgId.value = null
}

function onBadFeedbackPointerDown(e: PointerEvent) {
  if (!feedbackMsgId.value) return
  const root = document.querySelector(`[data-bad-feedback="${feedbackMsgId.value}"]`)
  if (root?.contains(e.target as Node)) return
  closeBadFeedback()
}

watch(feedbackMsgId, (id, _, onCleanup) => {
  if (!id) return
  // capture：点外部任意处（含滚动区）即可收起
  const bind = () => document.addEventListener('pointerdown', onBadFeedbackPointerDown, true)
  nextTick(bind)
  onCleanup(() => document.removeEventListener('pointerdown', onBadFeedbackPointerDown, true))
})

onMounted(async () => {
  try {
    const opts = await getKbOptions()
    const filtered = filterCv610ProOnly(opts)
    selectedKbIds.value = filtered.map((o) => o.kbId).filter(Boolean)
    loadSampleQuestions(filtered.map((o) => o.kbId))
  } catch {
    selectedKbIds.value = []
  }
  nextTick(() => setupCopyDelegate())
})

onUnmounted(() => {
  document.removeEventListener('pointerdown', onBadFeedbackPointerDown, true)
  copyCleanup?.()
  streamAbort?.abort()
})

// ── Auto-scroll ──
watch([messages, () => isTyping.value], async () => {
  await nextTick()
  if (scrollRef.value) {
    scrollRef.value.scrollTop = scrollRef.value.scrollHeight
  }
}, { deep: true })

// ── Helpers ──
function isStructuredAnswer(msg: Message) {
  return msg.paragraphs && msg.paragraphs.length > 0 && !msg.isStreaming
}

function getAnswerText(msg: Message) {
  if (msg.content?.trim()) return msg.content
  if (isStructuredAnswer(msg)) {
    return msg.paragraphs!.map((p) => p.content).join('\n')
  }
  return msg.content || ''
}

function renderMarkdown(content: string): string {
  if (!content) return ''
  try {
    const renderer = new marked.Renderer()
    const originalLink = renderer.link.bind(renderer)
    renderer.link = (token: Parameters<typeof originalLink>[0]) => {
      const html = originalLink(token)
      return html.replace(/^<a /, '<a target="_blank" rel="noopener noreferrer" ')
    }
    const originalCode = renderer.code.bind(renderer)
    renderer.code = (token: Parameters<typeof originalCode>[0]) => {
      let html = originalCode(token)
      html = html.replace(/^<pre/, '<div class="relative group"><pre class="overflow-x-auto"')
      html = html.replace('</pre>', `</pre><button type="button" class="code-copy-btn absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity z-10 p-1.5 rounded hover:bg-foreground/10 bg-background/50 backdrop-blur border border-border" title="${t('common.copy')}">${copyIcon}</button></div>`)
      return html
    }
    const originalTable = renderer.table.bind(renderer)
    renderer.table = (token: Parameters<typeof originalTable>[0]) => {
      return `<div class="overflow-x-auto">${originalTable(token)}</div>`
    }
    return marked.parse(content, { async: false, renderer }) as string
  } catch {
    return content
  }
}

function getRefTypeIcon(refType: string) {
  const map: Record<string, any> = { doc: BookOpen, code: Code2, pdf: FileText, table: Table2, register: FileSpreadsheet }
  return map[refType] || FileText
}

function nowTime(): string {
  const d = new Date()
  return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`
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

// ── Actions ──
async function ensureKbIds() {
  if (selectedKbIds.value.length) return
  try {
    const opts = await getKbOptions()
    selectedKbIds.value = filterCv610ProOnly(opts).map((o) => o.kbId).filter(Boolean)
  } catch {
    selectedKbIds.value = []
  }
}

async function loadSampleQuestions(kbIds: string[]) {
  try {
    if (!kbIds.length) {
      quickQuestions.value = []
      return
    }
    const list = await getEnabledHotwords(kbIds)
    quickQuestions.value = pickHotQuestions(list, kbIds).map((item) => item.content)
  } catch {
    quickQuestions.value = []
  }
}

async function sendToChat(text: string) {
  if (!text.trim() || isBusy.value) return

  const userMsg: Message = {
    id: `user-${Date.now()}`,
    role: 'user',
    content: text,
    timestamp: nowTime(),
  }
  messages.value = [...messages.value, userMsg]
  isTyping.value = true
  streamAbort = new AbortController()

  let assistantMsg: Message | null = null
  try {
    await ensureKbIds()
    if (!activeConversationId) {
      const conv = await createConversation({ title: '' /* title: text */ })
      activeConversationId = conv.id
    }

    assistantMsg = {
      id: `assistant-${Date.now()}`,
      role: 'assistant',
      content: '',
      timestamp: nowTime(),
      isStreaming: true,
      docs: [],
      routeReasoning: '',
      routeReasoningDone: false,
      routeReasoningOpen: true,
      routeReasoningStartedAt: Date.now(),
    }
    messages.value = [...messages.value, assistantMsg]
    assistantMsg = messages.value[messages.value.length - 1]
    isTyping.value = false

    await chatSendMessage(
      {
        conversationId: activeConversationId!,
        content: text,
        searchKbEnabled: true,
        searchExternalEnabled: true,
        kbIds: selectedKbIds.value.join(','),
        deepThinking: false,
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
    finalizeThinking(assistantMsg)
    assistantMsg.isStreaming = false
    assistantMsg.timestamp = nowTime()
  } catch (err: unknown) {
    if (isAbortError(err)) {
      if (assistantMsg) {
        finalizeThinking(assistantMsg)
        assistantMsg.isStreaming = false
        assistantMsg.timestamp = nowTime()
      }
    } else {
      const errText = (err instanceof Error ? err.message : '')?.trim() || t('api.error.sendFailed')
      if (assistantMsg) {
        assistantMsg.isStreaming = false
        assistantMsg.isError = true
        assistantMsg.content = errText
        assistantMsg.timestamp = nowTime()
      } else {
        messages.value = [...messages.value, {
          id: `assistant-err-${Date.now()}`,
          role: 'assistant',
          content: errText,
          timestamp: nowTime(),
          isError: true,
        }]
      }
    }
  } finally {
    isTyping.value = false
    streamAbort = null
    messages.value = messages.value.map((m) => (
      m.isStreaming ? { ...m, isStreaming: false } : m
    ))
    // 流完成后，若弹窗已关闭则显示红点；若用户正在查看则更新已读位置
    if (isOpen.value) {
      lastReadMessageCount.value = messages.value.length
    } else {
      hasUnread.value = true
    }
  }
}

function stopGenerate() {
  streamAbort?.abort()
}

function handleSend() {
  const q = inputValue.value.trim().slice(0, INPUT_MAX_LEN)
  if (!q || isBusy.value) return
  inputValue.value = ''
  void sendToChat(q)
}

function handleQuickQuestion(q: string) {
  if (isBusy.value) return
  void sendToChat(q.slice(0, INPUT_MAX_LEN))
}

function handleKeyDown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

async function handleCopy(text: string) {
  if (!text?.trim()) {
    toast.error(t('common.copyFailed'))
    return
  }
  try {
    await copyText(text)
    toast.success(t('common.copied.to.clipboard'))
  } catch {
    toast.error(t('common.copyFailed'))
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
    const pre = wrapper.querySelector('pre')
    const codeText = pre?.querySelector('code')?.textContent ?? pre?.textContent ?? ''
    if (!codeText) return
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

async function handleCodeCopy(codeId: string, code: string) {
  try {
    await copyText(code)
    copiedCodeId.value = codeId
    toast.success(t('common.copySuccess'))
    setTimeout(() => {
      copiedCodeId.value = null
    }, 1500)
  } catch {
    toast.error(t('common.copyFailed'))
  }
}

async function handleFeedback(msgId: string, type: 'helpful' | 'unhelpful') {
  const msg = messages.value.find(m => m.id === msgId)
  if (!msg) return
  if (type === 'helpful') {
    feedbackMsgId.value = null
    try {
      const res = await submitFeedback({ messageId: msgId, rating: 'HELPFUL' })
      msg.feedbackRating = 'HELPFUL'
      msg.badcaseType = undefined
      msg.badcaseComment = undefined
      toast.success(res || t('bot.feedbackThanks'))
    } catch (err: any) {
      cancelPendingToast()
      toast.error(err?.message || t('api.error.generic'))
    }
  } else {
    feedbackType.value = msg.badcaseType ?? ''
    feedbackComment.value = msg.badcaseComment ?? ''
    feedbackMsgId.value = msgId
    await nextTick()
    if (scrollRef.value) {
      scrollRef.value.scrollTop = scrollRef.value.scrollHeight
    }
  }
}

async function handleBadcaseSubmit(msgId: string) {
  if (!feedbackType.value) {
    toast.error(t('qa.badcaseTypeRequired'))
    return
  }
  const reason = BADCASE_REASON_MAP[feedbackType.value] || feedbackType.value
  try {
    const res = await submitFeedback({
      messageId: msgId,
      rating: 'UNHELPFUL',
      reasons: reason,
      comment: feedbackComment.value.trim() || undefined,
    })
    const msg = messages.value.find(m => m.id === msgId)
    if (msg) {
      msg.feedbackRating = 'UNHELPFUL'
      msg.badcaseType = feedbackType.value
      msg.badcaseComment = feedbackComment.value.trim()
    }
    toast.success(res || t('qa.badcaseSubmitted'))
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || t('api.error.generic'))
  }
  closeBadFeedback({ keepFeedback: true })
}

function handleOpen() {
  isOpen.value = true
  hasUnread.value = false
  lastReadMessageCount.value = messages.value.length
}

function openDocDetail(ref: { refId: string; title: string; refType?: string }) {
  const doc = MOCK_DOC_DETAILS[ref.refId]
  if (doc) {
    docDetail.value = doc
  } else {
    docDetail.value = {
      id: ref.refId,
      title: ref.title,
      type: 'manual',
      typeLabel: t('bot.refTechDoc'),
      chipModel: ['CV610'],
      version: 'v1.0.0',
      updatedAt: '2024-03-01',
      author: t('bot.docAuthor'),
      sections: [{ heading: t('bot.overview'), content: t('bot.docPreview', { title: ref.title }) }],
    }
  }
}

async function copyFullText() {
  if (!docDetail.value) return
  const text = docDetail.value.sections
    .map((s) => `${s.heading}\n${s.content}${s.code ? '\n' + s.code : ''}`)
    .join('\n\n')
  try {
    await copyText(text)
    toast.success(t('bot.copyAll'))
  } catch {
    toast.error(t('common.copyFailed'))
  }
}
</script>

<template>
  <!-- Floating button -->
  <button
    @click="handleOpen"
    :class="[
      'fixed bottom-6 right-6 z-50 size-14 rounded-full bg-[#23395D] text-white shadow-lg hover:shadow-xl hover:bg-[#1A2B47] transition-all duration-300 flex items-center justify-center',
      isOpen && 'hidden',
    ]"
    :aria-label="t('bot.openAssistant')"
  >
    <Bot class="size-6" />
    <span
      v-if="hasUnread"
      class="absolute top-0 right-0 size-3 bg-red-500 rounded-full border-2 border-white"
    />
  </button>

  <!-- Chat window -->
  <div
    :class="[
      'fixed bottom-6 right-6 z-50 w-[360px] bg-white rounded-2xl shadow-2xl border border-[#E8E4D9] overflow-hidden transition-all duration-300 flex flex-col',
      isOpen ? 'opacity-100 scale-100' : 'opacity-0 scale-95 pointer-events-none',
    ]"
    style="height: 560px; max-height: calc(100vh - 48px);"
  >
    <!-- Header -->
    <div class="flex items-center justify-between px-4 py-3 bg-[#23395D] text-white shrink-0">
      <div class="flex items-center gap-2.5">
        <div class="size-8 rounded-full bg-white/15 flex items-center justify-center">
          <Bot class="size-4.5" />
        </div>
        <div>
          <p class="text-sm font-semibold leading-tight">{{ t('bot.knowledgeAssistant') }}</p>
          <p class="text-[10px] text-white/70">{{ t('bot.onlineStatus') }}</p>
        </div>
      </div>
      <div class="flex items-center gap-0.5">
        <button
          class="size-7 hover:bg-white/10 text-white/80 hover:text-white rounded flex items-center justify-center transition-colors"
          @click="isOpen = false"
          :aria-label="t('bot.close')"
        >
          <X class="size-3.5" />
        </button>
      </div>
    </div>

      <!-- Messages -->
      <div class="flex-1 min-h-0 w-full bg-[#FAF9F5] overflow-y-auto" ref="scrollRef">
        <div class="px-4 py-3">
          <div class="space-y-4">
            <div
              v-for="msg in messages"
              :key="msg.id"
              :class="['flex gap-2.5', msg.role === 'user' && 'flex-row-reverse']"
            >
              <!-- Avatar -->
              <div
                :class="[
                  'size-7 rounded-full shrink-0 flex items-center justify-center mt-0.5',
                  msg.role === 'assistant'
                    ? 'bg-[#23395D]/10 text-[#23395D]'
                    : 'bg-[#E8E4D9] text-[#5A6B85]',
                ]"
              >
                <Bot v-if="msg.role === 'assistant'" class="size-3.5" />
                <span v-else class="text-[10px] font-medium">{{ t('bot.me') }}</span>
              </div>

              <!-- Bubble -->
              <div
                :class="[
                  'max-w-[260px] min-w-0 rounded-2xl px-3.5 py-2.5 overflow-hidden',
                  msg.role === 'user'
                    ? 'bg-[#23395D] text-white rounded-tr-sm'
                    : 'bg-white border border-[#E8E4D9] rounded-tl-sm',
                ]"
              >
                <!-- Structured answer -->
                <template v-if="msg.role === 'assistant' && isStructuredAnswer(msg)">
                  <div class="space-y-0.5">
                    <template v-for="(p, pi) in (msg.paragraphs || []).filter(p => p.type !== 'related-knowledge')" :key="pi">
                      <!-- Heading -->
                      <h4 v-if="p.type === 'heading'" :class="['font-semibold text-[#23395D] mt-3 mb-1.5 break-words [overflow-wrap:anywhere]', p.level === 4 ? 'text-sm' : 'text-base']">
                        {{ p.content }}
                      </h4>
                      <!-- List item -->
                      <div v-else-if="p.type === 'list-item'" class="flex gap-2 items-start text-[12px] leading-relaxed text-[#3A4F6B]">
                        <span class="text-[#23395D] mt-0.5 shrink-0">•</span>
                        <div class="flex-1 min-w-0 break-words [overflow-wrap:anywhere]">
                          <span>{{ p.content }}</span>
                          <span v-if="p.refs?.length" class="ml-1">
                            （
                            <template v-for="(ref, ri) in p.refs!" :key="ri">
                              <button @click="openDocDetail(ref)" class="text-[#23395D] underline underline-offset-2 hover:text-[#23395D]/80 font-medium">
                                {{ ref.title }}
                              </button>
                              <span v-if="ri < p.refs!.length - 1">、</span>
                            </template>
                            ）
                          </span>
                        </div>
                      </div>
                      <!-- Code block -->
                      <div v-else-if="p.type === 'code'" class="my-2 rounded-md overflow-hidden border border-[#E8E4D9]">
                        <div class="flex items-center justify-between px-2 py-1 bg-[#F4F3EE] border-b border-[#E8E4D9]">
                          <span class="text-[10px] text-[#6B7A90] font-mono">{{ p.codeLang || 'Code' }}</span>
                          <div class="flex items-center gap-1">
                            <button
                              type="button"
                              class="size-5 hover:bg-[#23395D]/20 rounded flex items-center justify-center transition-colors group"
                              @click="handleCodeCopy(`code-${pi}`, p.content)"
                              :aria-label="t('common.copy')"
                              :title="t('common.copy')"
                            >
                              <Check v-if="copiedCodeId === `code-${pi}`" class="size-3 text-green-500" />
                              <Copy v-else class="size-3 text-[#6B7A90] group-hover:text-[#D4E0F0] transition-colors" />
                            </button>
                            <button
                              v-if="p.refs?.[0]"
                              @click="openDocDetail(p.refs[0])"
                              class="text-[10px] text-[#23395D] hover:underline flex items-center gap-1"
                            >
                              <ExternalLink class="size-2.5" />
                              {{ p.refs[0].title }}
                            </button>
                          </div>
                        </div>
                        <pre class="p-2 bg-[#1E2B3D] text-[11px] leading-relaxed text-[#D4E0F0] overflow-x-auto font-mono"><code>{{ p.content }}</code></pre>
                      </div>
                      <!-- Text -->
                      <p v-else class="text-[12px] leading-relaxed text-[#3A4F6B] break-words [overflow-wrap:anywhere]">
                        {{ p.content }}
                        <span v-if="p.refs?.length" class="ml-1">
                          （
                          <template v-for="(ref, ri) in p.refs!" :key="ri">
                            <button @click="openDocDetail(ref)" class="text-[#23395D] underline underline-offset-2 hover:text-[#23395D]/80 font-medium">
                              {{ ref.title }}
                            </button>
                            <span v-if="ri < p.refs!.length - 1">、</span>
                          </template>
                          ）
                        </span>
                      </p>
                    </template>

                    <!-- Reference docs section -->
                    <template v-for="(p, pi) in (msg.paragraphs || []).filter(p => p.type === 'related-knowledge' && p.refs?.length)" :key="'ref-' + pi">
                      <div class="mt-2 pt-2 border-t border-[#E8E4D9]/70">
                        <p class="text-[10px] text-[#6B7A90] font-medium mb-1.5 flex items-center gap-1">
                          <FileText class="size-3" />
                          {{ t('bot.refDoc') }}
                        </p>
                        <div class="space-y-1.5">
                          <div
                            v-for="(ref, ri) in p.refs!"
                            :key="ri"
                            @click="openDocDetail(ref)"
                            class="w-full flex items-center gap-2 p-2 rounded-md bg-[#F8F7F2] hover:bg-[#F0EDE3] cursor-pointer transition-colors border border-[#E8E4D9]/50 text-left"
                          >
                            <component :is="getRefTypeIcon(ref.refType)" class="size-3.5 text-[#23395D] shrink-0" />
                            <div class="flex-1 min-w-0">
                              <p class="text-[11px] font-medium truncate text-[#23395D]">{{ ref.title }}</p>
                              <p class="text-[10px] text-[#9AA8BC]">{{ t(REF_TYPE_LABEL[ref.refType] || 'bot.refMaterial') }}</p>
                            </div>
                            <ExternalLink class="size-3 text-[#9AA8BC] shrink-0" />
                          </div>
                        </div>
                      </div>
                    </template>
                  </div>
                </template>

                <!-- Simple text / streamed answer -->
                <template v-else-if="msg.role === 'assistant'">
                  <p v-if="msg.isError" class="text-sm leading-relaxed text-red-600">{{ msg.content }}</p>
                  <template v-else>
                    <!-- 问题分析 -->
                    <div v-if="msg.routeReasoning" class="mb-2 border border-[#DCE9F7] rounded-lg bg-[#F0F6FD] overflow-hidden">
                      <button
                        type="button"
                        class="w-full flex items-center gap-1.5 px-2.5 py-1.5 text-left hover:bg-[#E2ECF8] transition-colors"
                        @click="msg.routeReasoningOpen = !msg.routeReasoningOpen"
                      >
                        <Compass :class="cn('size-3.5 text-[#4A6FA5] shrink-0', !msg.routeReasoningDone && 'animate-pulse text-[#2463EB]')" />
                        <span class="text-[11px] font-medium text-[#4A6FA5] flex-1">{{ routeReasoningTitle(msg) }}</span>
                        <span v-if="routeReasoningCountLabel(msg)" class="text-[10px] text-[#8BA5C5] tabular-nums">{{ routeReasoningCountLabel(msg) }}</span>
                        <ChevronDown :class="cn('size-3 text-[#8BA5C5] transition-transform duration-200', msg.routeReasoningOpen && 'rotate-180')" />
                      </button>
                      <div v-if="msg.routeReasoningOpen && msg.routeReasoning" class="px-2.5 pb-2 pt-1.5 text-[11px] text-[#4A6FA5] whitespace-pre-wrap leading-relaxed border-t border-[#DCE9F7]/70">
                        {{ msg.routeReasoning }}
                        <span v-if="!msg.routeReasoningDone" class="thinking-dots ml-1" style="color: #2463EB">
                          <span class="thinking-dot" style="animation-delay: 0s" />
                          <span class="thinking-dot" style="animation-delay: 0.2s" />
                          <span class="thinking-dot" style="animation-delay: 0.4s" />
                        </span>
                      </div>
                    </div>
                    <div
                      v-if="msg.content"
                      class="text-sm leading-relaxed text-[#3A4F6B] prose prose-sm max-w-none break-words [overflow-wrap:anywhere] [&_code]:break-words [&_code]:[white-space:pre-wrap] [&_code]:[overflow-wrap:anywhere] [&_pre]:max-w-full [&_pre]:[overflow-wrap:normal] [&_table]:max-w-full [&_img]:max-w-full"
                      v-html="renderMarkdown(msg.content)"
                    />
                    <span
                      v-if="msg.isStreaming"
                      class="thinking-dots ml-1"
                      style="color: #23395D"
                    >
                      <span class="thinking-dot" style="animation-delay: 0s" />
                      <span class="thinking-dot" style="animation-delay: 0.2s" />
                      <span class="thinking-dot" style="animation-delay: 0.4s" />
                    </span>
                    <div v-if="msg.docs?.length" class="mt-2 pt-2 border-t border-[#E8E4D9]/70 text-xs">
                      <span class="text-[#6B7A90]">{{ t('qa.relatedKnowledge') }}</span>
                      <span v-for="(doc, i) in msg.docs" :key="doc.id || i">
                        <button
                          type="button"
                          class="text-[#2563EB] underline underline-offset-2 decoration-[#2563EB]/50 hover:decoration-[#2563EB] transition-colors"
                          @click="preview.openDoc(doc, msg.docs)"
                        >
                          {{ chatDocTitle(doc) }}<span v-if="chatDocPageNum(doc)" class="text-[10px] ml-0.5">（{{ t('qa.docPageShort', { page: chatDocPageNum(doc) }) }}）</span>
                        </button>
                        <span v-if="i < (msg.docs?.length || 0) - 1" class="text-[#9AA8BC]">、</span>
                      </span>
                    </div>
                  </template>
                </template>
                <p v-else class="text-sm leading-relaxed break-words [overflow-wrap:anywhere]" :class="msg.role === 'user' ? '' : 'text-[#3A4F6B]'">
                  {{ msg.content }}
                </p>

                <!-- Feedback & copy row -->
                <div
                  v-if="msg.role === 'assistant' && !msg.isStreaming && msg.id !== 'welcome'"
                  class="mt-2 pt-2 border-t border-[#E8E4D9]/50 space-y-2"
                  :data-bad-feedback="feedbackMsgId === msg.id ? msg.id : undefined"
                >
                  <div class="flex items-center gap-1">
                    <button
                      type="button"
                      :class="[
                        'size-6 hover:bg-[#23395D]/8 rounded flex items-center justify-center transition-colors',
                        msg.feedbackRating === 'HELPFUL' && 'bg-emerald-50 text-emerald-500',
                      ]"
                      @click="handleFeedback(msg.id, 'helpful')"
                      :aria-label="t('bot.helpful')"
                    >
                      <ThumbsUp
                        :class="[
                          'size-3',
                          msg.feedbackRating === 'HELPFUL'
                            ? 'text-emerald-500 fill-emerald-500'
                            : 'text-[#9AA8BC] hover:text-[#23395D]',
                        ]"
                      />
                    </button>
                    <button
                      type="button"
                      :class="[
                        'size-6 rounded flex items-center justify-center transition-colors',
                        isUnhelpfulActive(msg)
                          ? 'bg-red-100 text-red-600 ring-1 ring-red-200'
                          : 'hover:bg-[#23395D]/8',
                      ]"
                      @click="handleFeedback(msg.id, 'unhelpful')"
                      :aria-label="t('bot.unhelpful')"
                    >
                      <ThumbsDown
                        :class="[
                          'size-3.5',
                          isUnhelpfulActive(msg)
                            ? 'text-red-600 fill-red-600'
                            : 'text-[#9AA8BC] hover:text-[#23395D]',
                        ]"
                      />
                    </button>
                    <button
                      type="button"
                      class="size-6 hover:bg-[#23395D]/8 ml-auto rounded flex items-center justify-center transition-colors"
                      @click="handleCopy(getAnswerText(msg))"
                      :aria-label="t('common.copy')"
                      :title="t('common.copy')"
                    >
                      <Copy class="size-3 text-[#9AA8BC] hover:text-[#23395D]" />
                    </button>
                  </div>
                  <!-- 差评类型选择弹窗 -->
                  <div
                    v-if="feedbackMsgId === msg.id"
                    class="w-full rounded-lg border border-[#E0DCD0] bg-white shadow-sm overflow-hidden"
                  >
                    <div class="px-3 py-2 border-b border-[#E8E4D9] flex items-center justify-between bg-[#FAF9F5]">
                      <span class="text-[11px] font-medium text-[#23395D]">{{ t('bot.selectProblemType') }}</span>
                      <button
                        type="button"
                        class="size-5 hover:bg-[#23395D]/5 rounded flex items-center justify-center"
                        :aria-label="t('common.close')"
                        @click.stop="closeBadFeedback()"
                      >
                        <X class="size-3 text-[#5A6B85]" />
                      </button>
                    </div>
                    <div class="p-3 space-y-3">
                      <div class="space-y-1.5">
                        <label class="text-[11px] text-[#5A6B85]">{{ t('qa.badcaseTypeLabel') }}</label>
                        <div class="flex flex-wrap gap-1.5">
                          <button
                            v-for="bt in BADCASE_TYPES"
                            :key="bt"
                            type="button"
                            :class="[
                              'px-2 py-1 text-[11px] rounded-md border transition-colors',
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
                          <label class="text-[11px] text-[#5A6B85]">{{ t('qa.badcaseDescLabel') }}</label>
                          <span class="text-[10px] text-[#9AA8BC]">{{ feedbackComment.length }}/200</span>
                        </div>
                        <textarea
                          v-model="feedbackComment"
                          class="w-full min-h-[56px] px-2 py-1.5 text-[11px] rounded-md bg-white border border-[#E0DCD0] focus:outline-none focus:ring-1 focus:ring-[#2563EB] resize-none text-[#2D3E5A]"
                          :placeholder="t('qa.badcaseDescPlaceholder')"
                          maxlength="200"
                        />
                      </div>
                      <button
                        type="button"
                        class="w-full h-7 bg-[#23395D] hover:bg-[#23395D]/90 text-white rounded-md text-[11px] font-medium transition-colors disabled:opacity-50"
                        :disabled="!feedbackType"
                        @click="handleBadcaseSubmit(msg.id)"
                      >{{ msg.badcaseType ? t('qa.badcaseResubmit') : t('qa.badcaseSubmit') }}</button>
                      <p v-if="msg.badcaseType" class="text-[10px] text-[#9AA8BC] text-center">{{ t('qa.badcaseSubmittedHint', { type: msg.badcaseType }) }}</p>
                    </div>
                  </div>
                </div>

                <!-- Related questions -->
                <div
                  v-if="msg.role === 'assistant' && msg.relatedQuestions?.length && !msg.isStreaming"
                  class="mt-2.5 pt-2 border-t border-[#E8E4D9]/50"
                >
                  <p class="text-[10px] text-[#6B7A90] mb-1.5">{{ t('bot.relatedQuestions') }}</p>
                  <div class="flex flex-wrap gap-1">
                    <button
                      v-for="(q, qi) in msg.relatedQuestions!"
                      :key="qi"
                      :disabled="isBusy"
                      class="text-[10px] px-2 py-1 rounded-full border border-[#E8E4D9] bg-white hover:bg-[#23395D]/5 hover:border-[#23395D]/30 hover:text-[#23395D] transition-colors text-[#5A6B85] disabled:opacity-50"
                      @click="handleQuickQuestion(q)"
                    >
                      {{ q }}
                    </button>
                  </div>
                </div>
              </div>
            </div>

            <!-- Typing indicator -->
            <div v-if="isTyping" class="flex gap-2.5">
              <div class="size-7 rounded-full shrink-0 flex items-center justify-center bg-[#23395D]/10 text-[#23395D] mt-0.5">
                <Bot class="size-3.5" />
              </div>
              <div class="bg-white border border-[#E8E4D9] rounded-2xl rounded-tl-sm px-3.5 py-3">
                <div class="flex gap-1">
                  <span class="size-2 rounded-full bg-[#9AA8BC]/50 animate-bounce" style="animation-delay: 0ms;" />
                  <span class="size-2 rounded-full bg-[#9AA8BC]/50 animate-bounce" style="animation-delay: 150ms;" />
                  <span class="size-2 rounded-full bg-[#9AA8BC]/50 animate-bounce" style="animation-delay: 300ms;" />
                </div>
              </div>
            </div>
          </div>

          <!-- Quick questions -->
          <div v-if="messages.length <= 1" class="mt-4 space-y-2">
            <p class="text-[11px] text-[#6B7A90] flex items-center gap-1">
              <Lightbulb class="size-3 text-amber-500" />
              {{ t('bot.tryAskThese') }}
            </p>
            <div class="flex flex-wrap gap-1.5">
              <button
                v-for="(q, i) in displayedQuickQuestions"
                :key="i"
                :disabled="isBusy"
                @click="handleQuickQuestion(q)"
                class="text-[11px] px-2.5 py-1 rounded-full border border-[#E8E4D9] bg-white hover:bg-[#23395D]/5 hover:border-[#23395D]/30 hover:text-[#23395D] transition-colors text-[#5A6B85] disabled:opacity-50"
              >
                {{ q }}
              </button>
            </div>
            <div v-if="quickQuestions.length > 4">
              <button
                type="button"
                @click="quickQuestionsExpanded = !quickQuestionsExpanded"
                class="inline-flex items-center gap-1 text-[10px] text-[#9AA8BC] hover:text-[#23395D] transition-colors"
              >
                {{ quickQuestionsExpanded ? t('common.collapse') : t('common.expand') }}
                <ChevronDown :class="['size-3 transition-transform duration-200', quickQuestionsExpanded && 'rotate-180']" />
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- Input area -->
      <div class="p-3 border-t border-[#E8E4D9] shrink-0 bg-white rounded-b-xl">
        <div class="flex gap-2">
          <div class="relative flex-1">
            <input
              v-model="inputValue"
              @keydown="handleKeyDown"
              type="text"
              :maxlength="INPUT_MAX_LEN"
              :placeholder="t('bot.inputPlaceholderEnter')"
              class="h-9 w-full text-sm bg-[#F8F7F2] border border-[#E8E4D9] text-[#23395D] placeholder:text-[#9AA8BC] rounded-md pl-3 pr-14 outline-none focus:ring-2 focus:ring-[#23395D]/30"
            />
            <span class="pointer-events-none absolute right-2.5 top-1/2 -translate-y-1/2 text-[10px] text-[#9AA8BC] tabular-nums leading-none">{{ inputValue.length }}/{{ INPUT_MAX_LEN }}</span>
          </div>
          <button
            v-if="isBusy"
            type="button"
            class="size-9 bg-[#23395D] hover:bg-[#1A2B47] text-white rounded-md flex items-center justify-center transition-colors"
            @click="stopGenerate"
            :aria-label="t('chat.stopGenerate')"
            :title="t('chat.stopGenerate')"
          >
            <Square class="size-3.5 fill-current" />
          </button>
          <button
            v-else
            type="button"
            class="size-9 bg-[#23395D] hover:bg-[#1A2B47] text-white rounded-md flex items-center justify-center transition-colors disabled:opacity-50"
            :disabled="!inputValue.trim()"
            @click="handleSend"
            :aria-label="t('bot.send')"
          >
            <Send class="size-4" />
          </button>
        </div>
        <p class="text-[10px] text-[#9AA8BC] mt-2 text-center">{{ t('bot.disclaimer') }}</p>
      </div>
  </div>

  <!-- Doc detail dialog -->
  <Teleport to="body">
    <div v-if="docDetail" class="fixed inset-0 z-[60] flex items-center justify-center p-4">
      <div class="absolute inset-0 bg-black/30" @click="docDetail = null" />
      <div class="relative w-full max-w-lg max-h-[85vh] bg-white rounded-xl shadow-2xl flex flex-col overflow-hidden">
        <!-- Header -->
        <div class="flex items-start justify-between px-5 py-4 border-b border-[#E8E4D9] shrink-0">
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2 mb-1">
              <h3 class="text-base font-semibold text-[#23395D] truncate">{{ docDetail.title }}</h3>
              <span class="shrink-0 text-[10px] px-1.5 py-0.5 rounded font-medium bg-[#23395D]/10 text-[#23395D]">{{ docDetail.typeLabel }}</span>
            </div>
            <div class="flex items-center gap-3 text-xs text-[#9AA8BC]">
              <span>{{ docDetail.version }}</span>
              <span>{{ docDetail.updatedAt }}</span>
              <span v-if="docDetail.chipModel?.length">{{ docDetail.chipModel.join(' / ') }}</span>
            </div>
          </div>
          <button @click="docDetail = null" class="p-1.5 rounded-lg hover:bg-[#23395D]/5 text-[#5A6B85] hover:text-[#23395D] transition-colors shrink-0" :aria-label="t('bot.close')">
            <X class="size-5" />
          </button>
        </div>
        <!-- Body -->
        <div class="flex-1 overflow-y-auto px-5 py-4 space-y-4">
          <div v-for="(section, idx) in docDetail.sections" :key="idx" class="space-y-2">
            <h4 class="text-sm font-semibold text-[#23395D] border-l-2 border-[#23395D] pl-2">{{ section.heading }}</h4>
            <div class="text-[13px] text-[#3A4F6B] leading-relaxed whitespace-pre-line pl-3">{{ section.content }}</div>
            <pre v-if="section.code" class="bg-[#1E2B3D] rounded-lg p-3 text-xs font-mono overflow-x-auto text-[#D4E0F0]"><code>{{ section.code }}</code></pre>
          </div>
        </div>
        <!-- Footer -->
        <div class="flex items-center justify-between px-5 py-3 border-t border-[#E8E4D9] shrink-0">
          <span class="text-xs text-[#9AA8BC]">{{ t('bot.authorLabel') }}{{ docDetail.author }}</span>
          <div class="flex items-center gap-2">
            <button @click="copyFullText" class="text-xs text-[#23395D] hover:underline flex items-center gap-1"><Copy class="size-3" /> {{ t('bot.copyAll') }}</button>
            <button @click="docDetail = null" class="text-xs px-3 py-1.5 rounded-md bg-[#23395D] hover:bg-[#1A2B47] text-white transition-colors">{{ t('bot.close') }}</button>
          </div>
        </div>
      </div>
    </div>
  </Teleport>

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
