<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { marked } from 'marked'
import {
  Send,
  ThumbsUp,
  ThumbsDown,
  Copy,
  Cpu,
  BookOpen,
  Brain,
  Search,
  X,
  FileText,
  Code2,
  FileCog,
  FileCode,
  ChevronDown,
  Loader2,
  Square,
} from 'lucide-vue-next'
import { toast } from '@/lib/toast'
import { cancelPendingToast } from '@/services/api-client'
import { copyText } from '@/lib/clipboard'
import { chatDocTitle, chatDocPageNum } from '@/lib/chat-docs'
import { filterCv610ProOnly, displayKbName } from '@/lib/utils'
import { useI18n } from '@/composables/useI18n'
import { useDocumentFilePreview } from '@/composables/useDocumentFilePreview'
import DocumentPreviewBody from '@/components/DocumentPreviewBody.vue'
import DocumentPreviewDrawer from '@/components/DocumentPreviewDrawer.vue'
import {
  createConversation,
  sendMessage as chatSendMessage,
  applyChatStreamEvent,
  finalizeThinking,
  isAbortError,
  uploadConversationFile,
  submitFeedback,
  BADCASE_REASON_MAP,
  type ChatStreamDoc,
} from '@/services/chat.service'
import {
  GLOBAL_DOC_TYPE_OPTIONS,
  getMetadataDictOptions,
  getKbOptions,
  type MetadataDictOption,
  type KbOption,
} from '@/services/knowledge.service'
import { getEnabledHotwords, pickHotQuestions } from '@/services/hotword.service'

// ── 类型 ──

interface AttachmentFile {
  id: string
  name: string
  size: number
  type: 'image' | 'document' | 'other'
  previewUrl?: string
  file: File
}

interface MessageAttachment {
  id: string
  name: string
  size: number
  type: 'image' | 'document' | 'other'
  previewUrl?: string
}

interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: string
  isStreaming?: boolean
  attachments?: MessageAttachment[]
  deepThinking?: boolean
  reasoning?: string
  reasoningDone?: boolean
  reasoningOpen?: boolean
  reasoningStartedAt?: number
  reasoningSeconds?: number
  docs?: ChatStreamDoc[]
  /** 反馈评级（后端返回值：HELPFUL / UNHELPFUL / null） */
  feedbackRating?: string | null
  /** UI 层：badcase 中文标签 */
  badcaseType?: string
  /** 反馈描述 */
  badcaseComment?: string
}

// ── 常量 ──

const { t } = useI18n()

const DOC_TYPE_ICONS: Record<string, typeof FileText> = {
  DOCUMENT: FileText,
  CODE: Code2,
  CONFIG: FileCog,
  OTHER: FileText,
}
const DOC_TYPES = [
  { value: 'all', labelKey: 'qa.filter.allTypes', icon: FileText },
  ...GLOBAL_DOC_TYPE_OPTIONS.map(opt => ({
    value: opt.value,
    labelKey: opt.labelKey,
    icon: DOC_TYPE_ICONS[opt.value] || FileText,
  })),
]

const sampleQuestions = ref<string[]>([])
const hotQuestionsExpanded = ref(false)
const displayedSampleQuestions = computed(() =>
  hotQuestionsExpanded.value ? sampleQuestions.value : sampleQuestions.value.slice(0, 4),
)

// ── 状态 ──

const INPUT_MAX_LEN = 500
const inputValue = ref('')
const inputRef = ref<HTMLTextAreaElement | null>(null)
const INPUT_MAX_H = 150
function autoResize() {
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  const sh = el.scrollHeight
  el.style.height = Math.min(sh, INPUT_MAX_H) + 'px'
  el.style.overflowY = sh > INPUT_MAX_H ? 'auto' : 'hidden'
}
watch(inputValue, () => requestAnimationFrame(autoResize))
const messages = ref<Message[]>([])
const isTyping = ref(false)
const generating = computed(() => isTyping.value || messages.value.some((m) => m.isStreaming))
let streamAbort: AbortController | null = null

const BADCASE_TYPES = ['回答错误', '答非所问', '结果不相关', '代码错误', '信息过时']
const feedbackMsgId = ref<string | null>(null)
const feedbackType = ref('')
const feedbackComment = ref('')

// 筛选条件
const selectedKbIds = ref<string[]>([])
const docType = ref('all')
const version = ref('latest')
const rightPanelOpen = ref(true)
const preview = useDocumentFilePreview()
const isXl = ref(false)
function applyXl() {
  isXl.value = window.innerWidth >= 1280
}
const metadataDictOptions = ref<MetadataDictOption[]>([])
const kbOptions = ref<KbOption[]>([])
const versionOptions = computed(() => {
  const opt = metadataDictOptions.value.find(o => o.metaKey === 'version')
  return opt?.options || []
})

// 工具栏
const deepThinking = ref(false)
const searchOfficial = ref(true)
const searchUnofficial = ref(true)
const attachments = ref<AttachmentFile[]>([])
const fileInputRef = ref<HTMLInputElement | null>(null)

const scrollRef = ref<HTMLDivElement | null>(null)

// 当前会话 id（首次发送时创建）
let activeConversationId: string | null = null

// ── 工具 ──

const scrollToBottom = async () => {
  await nextTick()
  if (scrollRef.value) {
    scrollRef.value.scrollTop = scrollRef.value.scrollHeight
  }
}

const renderMarkdown = (content: string): string => {
  if (!content) return ''
  try {
    const renderer = new marked.Renderer()
    const originalLink = renderer.link.bind(renderer)
    renderer.link = (token: Parameters<typeof originalLink>[0]) => {
      const html = originalLink(token)
      return html.replace(/^<a /, '<a target="_blank" rel="noopener noreferrer" ')
    }
    return marked.parse(content, { async: false, renderer }) as string
  } catch {
    return content
  }
}

const thinkingTitle = (msg: Message): string => {
  if (!msg.reasoningDone) return t('qa.thinkingInProgress')
  return t('qa.deepThink')
}

const thinkingCountLabel = (msg: Message): string => {
  const count = (msg.reasoning || '').length
  if (!count) return ''
  return msg.reasoningDone
    ? t('qa.thoughtCharCount', { count })
    : t('qa.thinkingCharCount', { count })
}

const formatFileSize = (bytes: number) => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

const handleFileSelect = (files: FileList | null) => {
  if (!files || files.length === 0) return
  const newAttachments: AttachmentFile[] = Array.from(files).map((file) => {
    const isImage = file.type.startsWith('image/')
    const attachment: AttachmentFile = {
      id: `file-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      name: file.name,
      size: file.size,
      file,
      type: isImage
        ? 'image'
        : file.type.includes('pdf') || file.type.includes('doc') || file.type.includes('text') || file.type.includes('markdown')
          ? 'document'
          : 'other',
    }
    if (isImage) attachment.previewUrl = URL.createObjectURL(file)
    return attachment
  })
  attachments.value = [...attachments.value, ...newAttachments]
  toast.success(t('chat.filesAdded', { count: newAttachments.length }))
}

const handleRemoveAttachment = (id: string) => {
  const target = attachments.value.find((a) => a.id === id)
  if (target?.previewUrl) URL.revokeObjectURL(target.previewUrl)
  attachments.value = attachments.value.filter((a) => a.id !== id)
}

const toggleKb = (kbId: string) => {
  if (selectedKbIds.value.includes(kbId)) {
    if (selectedKbIds.value.length <= 1) {
      toast.info(t('qa.pleaseSelectKb'))
      return
    }
    selectedKbIds.value = selectedKbIds.value.filter((id) => id !== kbId)
  } else {
    selectedKbIds.value.push(kbId)
  }
}

const toggleOfficial = () => {
  if (searchOfficial.value && !searchUnofficial.value) {
    toast.info(t('qa.pleaseSelectKnowledgeSource'))
    return
  }
  searchOfficial.value = !searchOfficial.value
}

const toggleUnofficial = () => {
  if (searchUnofficial.value && !searchOfficial.value) {
    toast.info(t('qa.pleaseSelectKnowledgeSource'))
    return
  }
  searchUnofficial.value = !searchUnofficial.value
}

// ── 发送消息 ──

const sendMessage = async () => {
  if (!searchOfficial.value && !searchUnofficial.value) {
    toast.error(t('qa.pleaseSelectKnowledgeSource'))
    return
  }
  const text = inputValue.value.trim().slice(0, INPUT_MAX_LEN)
  if ((!text && attachments.value.length === 0) || generating.value) return

  const pendingAttachments = [...attachments.value]
  const userMsg: Message = {
    id: `user-${Date.now()}`,
    role: 'user',
    content: text,
    timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }),
    attachments: pendingAttachments.map((a) => ({
      id: a.id,
      name: a.name,
      size: a.size,
      type: a.type,
      previewUrl: a.previewUrl,
    })),
  }
  messages.value.push(userMsg)
  inputValue.value = ''
  attachments.value = []
  isTyping.value = true
  streamAbort = new AbortController()
  scrollToBottom()

  let assistantMsg: Message | null = null

  try {
    // 1. 首次发送时创建会话
    if (!activeConversationId) {
      const conv = await createConversation({
        title: '',
        // title: text || t('qa.newChat'),
      })
      activeConversationId = conv.id
    }

    // 2. 上传附件（会话中上传）
    for (const att of pendingAttachments) {
      await uploadConversationFile(activeConversationId!, att.file)
    }

    // 3. 流式发送消息
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
      docs: [],
    }
    messages.value.push(assistantMsg)
    assistantMsg = messages.value[messages.value.length - 1]
    isTyping.value = false
    scrollToBottom()

    await chatSendMessage(
      {
        conversationId: activeConversationId!,
        content: text,
        searchKbEnabled: searchOfficial.value,
        searchExternalEnabled: searchUnofficial.value,
        kbIds: selectedKbIds.value.length ? selectedKbIds.value.join(',') : undefined,
        docTypes: docType.value === 'all'
          ? GLOBAL_DOC_TYPE_OPTIONS.map(o => o.value).join(',')
          : docType.value,
        version: version.value === 'latest' ? undefined : version.value,
        deepThinking: deepThinking.value,
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
        scrollToBottom()
      },
      streamAbort?.signal,
    )
    finalizeThinking(assistantMsg)
    assistantMsg.isStreaming = false
    assistantMsg.timestamp = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  } catch (err: any) {
    isTyping.value = false
    if (isAbortError(err)) {
      if (assistantMsg) {
        finalizeThinking(assistantMsg)
        assistantMsg.isStreaming = false
        assistantMsg.timestamp = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
      }
      return
    }
    if (assistantMsg) {
      assistantMsg.isStreaming = false
      finalizeThinking(assistantMsg)
    }
    toast.error(err?.message || t('api.error.sendFailed'))
  } finally {
    streamAbort = null
  }
}

function stopGenerate() {
  streamAbort?.abort()
}

const handleKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}

const askSample = (q: string) => {
  inputValue.value = q.slice(0, INPUT_MAX_LEN)
  sendMessage()
}

async function loadSampleQuestions() {
  try {
    // 按知识库列表顺序取当前选中的知识库（勾选顺序不代表列表顺序）
    const kbIds = kbOptions.value
      .filter((kb) => selectedKbIds.value.includes(kb.kbId))
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

watch(selectedKbIds, () => {
  loadSampleQuestions()
}, { deep: true })

const copyAnswer = async (content: string) => {
  try {
    await copyText(content)
    toast.success(t('qa.copied'))
  } catch {
    toast.error(t('common.copyFailed'))
  }
}

function openSource(doc: ChatStreamDoc, siblings?: ChatStreamDoc[]) {
  rightPanelOpen.value = true
  void preview.openDoc(doc, siblings)
}

const handleFeedback = async (msgId: string, type: 'helpful' | 'unhelpful') => {
  const msg = messages.value.find(m => m.id === msgId)
  if (!msg) return
  if (type === 'helpful') {
    feedbackMsgId.value = null
    try {
      const res = await submitFeedback({ messageId: msgId, rating: 'HELPFUL' })
      msg.feedbackRating = 'HELPFUL'
      msg.badcaseType = undefined
      msg.badcaseComment = undefined
      toast.success(res || t('qa.feedbackThanks'))
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
    feedbackMsgId.value = null
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || t('api.error.generic'))
  }
}

onMounted(async () => {
  applyXl()
  window.addEventListener('resize', applyXl)
  window.addEventListener('resize', autoResize)
  await nextTick()
  autoResize()
  try {
    metadataDictOptions.value = await getMetadataDictOptions()
  } catch {
    metadataDictOptions.value = []
  }
  try {
    kbOptions.value = filterCv610ProOnly(await getKbOptions())
    if (!selectedKbIds.value.length) {
      selectedKbIds.value = kbOptions.value.map((kb) => kb.kbId)
    }
    loadSampleQuestions()
  } catch {
    kbOptions.value = []
  }
})

onUnmounted(() => {
  streamAbort?.abort()
  window.removeEventListener('resize', applyXl)
  window.removeEventListener('resize', autoResize)
})
</script>

<template>
  <div class="h-[calc(100vh-8rem)] flex gap-4 overflow-hidden">
    <!-- 左侧筛选器 -->
    <div class="w-64 shrink-0 hidden lg:flex flex-col overflow-hidden rounded-lg border border-border bg-card">
      <div class="p-4 pb-3">
        <h3 class="text-sm font-semibold">{{ t('qa.filter.title') }}</h3>
      </div>
      <div class="flex-1 overflow-y-auto custom-scrollbar space-y-6 p-4 pt-0">
        <div>
          <label class="text-sm font-medium mb-2 block">{{ t('qa.filter.kb') }}</label>
          <div class="space-y-2">
            <label
              v-for="kb in kbOptions"
              :key="kb.kbId"
              class="flex items-center gap-2 text-sm cursor-pointer hover:text-primary transition-colors"
              @click.prevent="toggleKb(kb.kbId)"
            >
              <input type="checkbox" :checked="selectedKbIds.includes(kb.kbId)" class="rounded pointer-events-none" />
              {{ displayKbName(kb.kbName) || kb.kbId }}
            </label>
            <p v-if="kbOptions.length === 0" class="text-xs text-muted-foreground">{{ t('common.noData') }}</p>
          </div>
        </div>

        <div class="border-t border-border"></div>

        <div>
          <label class="text-sm font-medium mb-2 block">{{ t('docs.docTypeLabel') }}</label>
          <div class="space-y-1">
            <button
              v-for="opt in DOC_TYPES"
              :key="opt.value"
              type="button"
              @click="docType = opt.value"
              :class="['w-full flex items-center gap-2 px-3 py-2 rounded-md text-sm transition-colors', docType === opt.value ? 'bg-primary/10 text-primary font-medium' : 'hover:bg-muted text-muted-foreground']"
            >
              <component :is="opt.icon" class="size-4 shrink-0" />
              {{ t(opt.labelKey) }}
            </button>
          </div>
        </div>

        <div class="border-t border-border"></div>

        <div>
          <label class="text-sm font-medium mb-2 block">{{ t('qa.filter.versionFilter') }}</label>
          <select
            v-model="version"
            class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          >
            <option value="latest">{{ t('qa.filter.latestVersion') }}</option>
            <option v-for="item in versionOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
        </div>
      </div>
    </div>

    <!-- 中间对话区 -->
    <div class="flex-1 flex flex-col min-w-0 rounded-lg border border-border bg-card overflow-hidden">
      <div class="flex items-center gap-2 p-4 pb-3 border-b border-border">
        <Cpu class="size-4 text-primary" />
        <h3 class="text-sm font-semibold">{{ t('qa.title') }}</h3>
        <span class="ml-auto text-xs font-normal px-2 py-0.5 rounded-full border border-border">
          {{ t('qa.kbsSelected', { count: selectedKbIds.length }) }}
        </span>
      </div>

      <!-- 消息区 -->
      <div ref="scrollRef" class="flex-1 overflow-y-auto p-4 space-y-4 custom-scrollbar">
        <!-- 欢迎语 -->
        <div v-if="messages.length === 0" class="text-center py-12">
          <h2 class="text-xl font-bold text-foreground mb-2">{{ t('qa.welcome') }}</h2>
          <p class="text-sm text-muted-foreground mb-8 max-w-md mx-auto">{{ t('qa.welcomeMessage') }}</p>
          <p class="text-sm text-muted-foreground mb-3">{{ t('qa.tryTheseQuestions') }}</p>
          <div class="flex flex-wrap gap-2 justify-center max-w-lg mx-auto">
            <button
              v-for="q in displayedSampleQuestions"
              :key="q"
              type="button"
              @click="askSample(q)"
              class="px-3 py-2 text-xs rounded-lg border border-border bg-card text-muted-foreground hover:text-foreground hover:border-primary/30 transition-colors text-left"
            >
              {{ q }}
            </button>
          </div>
          <div v-if="sampleQuestions.length > 4" class="mt-2">
            <button
              type="button"
              @click="hotQuestionsExpanded = !hotQuestionsExpanded"
              class="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-primary transition-colors"
            >
              {{ hotQuestionsExpanded ? t('common.collapse') : t('common.expand') }}
              <ChevronDown :class="['size-3 transition-transform duration-200', hotQuestionsExpanded && 'rotate-180']" />
            </button>
          </div>
        </div>

        <!-- 消息列表 -->
        <div
          v-for="msg in messages"
          :key="msg.id"
          :class="['flex gap-3', msg.role === 'user' ? 'justify-end' : 'justify-start']"
        >
          <!-- AI头像 -->
          <div
            v-if="msg.role === 'assistant'"
            class="size-8 shrink-0 rounded-full bg-gradient-to-br from-blue-500 to-cyan-400 text-white flex items-center justify-center mt-1 text-xs font-medium"
          >
            {{ t('qa.avatar.ai') }}
          </div>

          <div
            :class="[
              'max-w-[85%] min-w-0',
              msg.role === 'user'
                ? 'px-4 py-3 rounded-2xl rounded-tr-sm bg-primary text-primary-foreground'
                : 'bg-card border border-border rounded-2xl rounded-tl-sm p-4',
            ]"
          >
            <!-- 用户消息 -->
            <template v-if="msg.role === 'user'">
              <div v-if="msg.attachments?.length" class="flex flex-wrap gap-1.5 mb-2">
                <span
                  v-for="att in msg.attachments"
                  :key="att.id"
                  class="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-white/15 text-xs"
                >
                  <FileText class="size-3 shrink-0" />
                  {{ att.name }}
                </span>
              </div>
              <p class="text-sm whitespace-pre-wrap">{{ msg.content }}</p>
            </template>

            <!-- AI 回答（Markdown 渲染） -->
            <template v-else>
              <div v-if="msg.deepThinking || msg.reasoning" class="border border-border rounded-xl bg-muted/30 overflow-hidden mb-2">
                <button
                  type="button"
                  class="w-full flex items-center gap-2 px-3 py-2 text-left hover:bg-muted/60 transition-colors"
                  @click="msg.reasoningOpen = !msg.reasoningOpen"
                >
                  <Brain :class="['size-4 text-muted-foreground shrink-0', !msg.reasoningDone && 'animate-pulse text-primary']" />
                  <span class="text-xs font-medium text-muted-foreground flex-1">{{ thinkingTitle(msg) }}</span>
                  <span v-if="thinkingCountLabel(msg)" class="text-[10px] text-muted-foreground tabular-nums">{{ thinkingCountLabel(msg) }}</span>
                  <ChevronDown :class="['size-3.5 text-muted-foreground transition-transform duration-200', msg.reasoningOpen && 'rotate-180']" />
                </button>
                <div v-if="msg.reasoningOpen && msg.reasoning" class="px-3 pb-3 pt-2 text-xs text-muted-foreground leading-relaxed whitespace-pre-wrap border-t border-border/50">
                  {{ msg.reasoning }}
                  <span v-if="!msg.reasoningDone" class="thinking-dots ml-1 text-foreground/50">
                    <span class="thinking-dot" style="animation-delay: 0s" />
                    <span class="thinking-dot" style="animation-delay: 0.2s" />
                    <span class="thinking-dot" style="animation-delay: 0.4s" />
                  </span>
                </div>
              </div>
              <div v-if="msg.isStreaming && !msg.content && !msg.deepThinking && !msg.reasoning" class="flex items-center gap-2 py-1">
                <span class="size-1.5 rounded-full bg-muted-foreground/50 animate-bounce" style="animation-delay: 0ms"></span>
                <span class="size-1.5 rounded-full bg-muted-foreground/50 animate-bounce" style="animation-delay: 150ms"></span>
                <span class="size-1.5 rounded-full bg-muted-foreground/50 animate-bounce" style="animation-delay: 300ms"></span>
              </div>
              <div v-else-if="msg.content" class="prose prose-sm max-w-none" v-html="renderMarkdown(msg.content)"></div>
              <span v-if="msg.isStreaming && (msg.content || msg.reasoningDone)" class="thinking-dots ml-1 text-primary">
                <span class="thinking-dot" style="animation-delay: 0s" />
                <span class="thinking-dot" style="animation-delay: 0.2s" />
                <span class="thinking-dot" style="animation-delay: 0.4s" />
              </span>
              <div v-if="msg.docs?.length" class="mt-3 pt-3 border-t border-border text-sm">
                <span class="text-muted-foreground">{{ t('qa.relatedKnowledge') }}</span>
                <span v-for="(doc, i) in msg.docs" :key="doc.id || i">
                  <button
                    type="button"
                    class="text-primary underline underline-offset-2 decoration-primary/40 hover:decoration-primary transition-colors"
                    @click="openSource(doc)"
                  >
                    {{ chatDocTitle(doc) }}<span v-if="chatDocPageNum(doc)" class="text-xs ml-0.5">（{{ t('qa.docPageShort', { page: chatDocPageNum(doc) }) }}）</span>
                  </button>
                  <span v-if="i < (msg.docs?.length || 0) - 1" class="text-muted-foreground">、</span>
                </span>
              </div>

              <div class="flex items-center gap-2 mt-3 pt-2 border-t border-border/50">
                <button
                  @click="copyAnswer(msg.content)"
                  class="p-1 rounded hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
                  :title="t('common.copy')"
                >
                  <Copy class="size-4" />
                </button>
                <button
                  @click="handleFeedback(msg.id, 'helpful')"
                  :class="[
                    'p-1 rounded hover:bg-accent transition-colors',
                    msg.feedbackRating === 'HELPFUL' ? 'text-emerald-500 bg-emerald-50' : 'text-muted-foreground hover:text-emerald-500'
                  ]"
                  :title="t('qa.helpful')"
                >
                  <ThumbsUp class="size-4" />
                </button>
                <div class="relative">
                  <button
                    @click="handleFeedback(msg.id, 'unhelpful')"
                    :class="[
                      'p-1 rounded hover:bg-accent transition-colors',
                      msg.feedbackRating === 'UNHELPFUL' ? 'text-red-500 bg-red-50' : 'text-muted-foreground hover:text-red-500'
                    ]"
                    :title="t('qa.unhelpful')"
                  >
                    <ThumbsDown class="size-4" />
                  </button>
                  <!-- Badcase 弹窗 -->
                  <div v-if="feedbackMsgId === msg.id" class="absolute top-full left-0 mt-1 w-72 bg-card border rounded-lg shadow-lg z-50 overflow-hidden">
                    <div class="px-3 py-2 border-b flex items-center justify-between">
                      <span class="text-xs font-medium">{{ t('qa.selectBadcaseType') }}</span>
                      <button class="size-5 flex items-center justify-center hover:bg-accent rounded" @click="feedbackMsgId = null"><X class="size-3" /></button>
                    </div>
                    <div class="p-3 space-y-3">
                      <div class="space-y-1.5">
                        <label class="text-xs text-muted-foreground">{{ t('qa.badcaseTypeLabel') }}</label>
                        <div class="flex flex-wrap gap-1.5">
                          <button
                            v-for="bt in BADCASE_TYPES"
                            :key="bt"
                            :class="[
                              'px-2.5 py-1 text-xs rounded-md border transition-colors',
                              feedbackType === bt
                                ? 'bg-red-50 border-red-300 text-red-600'
                                : 'bg-card border-border hover:border-primary/30 text-muted-foreground'
                            ]"
                            @click="feedbackType = bt"
                          >{{ bt }}</button>
                        </div>
                      </div>
                      <div class="space-y-1.5">
                        <div class="flex items-center justify-between">
                          <label class="text-xs text-muted-foreground">{{ t('qa.badcaseDescLabel') }}</label>
                          <span class="text-[11px] text-muted-foreground">{{ feedbackComment.length }}/200</span>
                        </div>
                        <textarea
                          v-model="feedbackComment"
                          class="w-full min-h-[72px] px-2.5 py-2 text-xs rounded-md bg-card border focus:outline-none focus:ring-1 focus:ring-primary resize-none"
                          :placeholder="t('qa.badcaseDescPlaceholder')"
                          maxlength="200"
                        />
                      </div>
                      <button
                        class="w-full h-8 bg-primary hover:bg-primary/90 text-primary-foreground rounded-md text-xs font-medium transition-colors disabled:opacity-50"
                        :disabled="!feedbackType"
                        @click="handleBadcaseSubmit(msg.id)"
                      >{{ msg.badcaseType ? t('qa.badcaseResubmit') : t('qa.badcaseSubmit') }}</button>
                      <p v-if="msg.badcaseType" class="text-[11px] text-muted-foreground text-center">{{ t('qa.badcaseSubmittedHint', { type: msg.badcaseType }) }}</p>
                    </div>
                  </div>
                </div>
                <span v-if="msg.timestamp" class="text-xs text-muted-foreground ml-auto">{{ msg.timestamp }}</span>
              </div>
            </template>
          </div>

          <!-- 用户头像 -->
          <div
            v-if="msg.role === 'user'"
            class="size-8 shrink-0 rounded-full bg-primary/10 text-primary flex items-center justify-center mt-1"
          >
            <span class="text-xs font-medium">{{ t('qa.me') }}</span>
          </div>
        </div>

        <!-- 打字中 -->
        <div v-if="isTyping" class="flex gap-3">
          <div class="size-8 shrink-0 rounded-full bg-gradient-to-br from-blue-500 to-cyan-400 text-white flex items-center justify-center mt-1 text-xs font-medium">
            {{ t('qa.avatar.ai') }}
          </div>
          <div class="bg-muted rounded-lg p-3">
            <div class="flex gap-1">
              <span class="size-2 rounded-full bg-muted-foreground/40 animate-bounce" style="animation-delay: 0ms"></span>
              <span class="size-2 rounded-full bg-muted-foreground/40 animate-bounce" style="animation-delay: 150ms"></span>
              <span class="size-2 rounded-full bg-muted-foreground/40 animate-bounce" style="animation-delay: 300ms"></span>
            </div>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="p-4 border-t">
        <div class="flex items-center gap-3 mb-2">
          <button
            type="button"
            @click="deepThinking = !deepThinking"
            :class="['flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-full border transition-colors whitespace-nowrap shrink-0', deepThinking ? 'bg-[#2463EB] border-[#2463EB] text-primary-foreground' : 'bg-background border-input text-muted-foreground hover:border-primary/30 hover:text-foreground']"
          >
            <Brain class="size-3.5 shrink-0" />
            {{ t('qa.deepThink') }}
          </button>
          <button
            type="button"
            @click="toggleOfficial"
            :class="['flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-full border transition-colors whitespace-nowrap shrink-0', searchOfficial ? 'bg-[#2463EB] border-[#2463EB] text-primary-foreground' : 'bg-background border-input text-muted-foreground hover:border-primary/30 hover:text-foreground']"
          >
            <BookOpen class="size-3.5 shrink-0" />
            {{ t('qa.officialKnowledge') }}
          </button>
          <button
            type="button"
            @click="toggleUnofficial"
            :class="['flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-full border transition-colors whitespace-nowrap shrink-0', searchUnofficial ? 'bg-[#2463EB] border-[#2463EB] text-primary-foreground' : 'bg-background border-input text-muted-foreground hover:border-primary/30 hover:text-foreground']"
          >
            <Search class="size-3.5 shrink-0" />
            {{ t('qa.unofficialKnowledge') }}
          </button>
        </div>

        <!-- 已选附件预览 -->
        <div v-if="attachments.length > 0" class="flex flex-wrap gap-2 mb-2">
          <div
            v-for="att in attachments"
            :key="att.id"
            class="relative rounded-lg overflow-hidden border border-border bg-muted/30 group"
          >
            <img v-if="att.type === 'image' && att.previewUrl" :src="att.previewUrl" :alt="att.name" class="w-20 h-20 object-cover block" />
            <div v-else class="flex items-center gap-2 px-3 py-2 w-44">
              <FileText class="size-4 text-muted-foreground shrink-0" />
              <div class="min-w-0 flex-1">
                <p class="text-xs font-medium truncate text-foreground">{{ att.name }}</p>
                <p class="text-[10px] text-muted-foreground">{{ formatFileSize(att.size) }}</p>
              </div>
            </div>
            <button
              type="button"
              @click="handleRemoveAttachment(att.id)"
              class="absolute top-1 right-1 size-5 rounded-full bg-foreground/80 text-background flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity hover:bg-foreground"
            >
              <X class="size-3" />
            </button>
          </div>
        </div>

        <input
          ref="fileInputRef"
          type="file"
          multiple
          accept="image/*,.pdf,.doc,.docx,.txt,.md,.csv,.c,.cpp,.py,.ts,.java,.md,.html,.xml,.json"
          @change="handleFileSelect(($event.target as HTMLInputElement).files)"
          class="hidden"
        />

        <div class="flex items-center gap-2">
          <div class="relative flex-1 min-w-0">
            <textarea
              ref="inputRef"
              v-model="inputValue"
              @keydown="handleKeydown"
              :placeholder="t('home.searchPlaceholder')"
              rows="1"
              :maxlength="INPUT_MAX_LEN"
              class="block w-full min-h-11 m-0 box-border px-4 py-3 pr-14 text-sm leading-5 rounded-xl border border-border bg-background resize-none outline-none focus:ring-2 focus:ring-ring/20"
            ></textarea>
            <span class="pointer-events-none absolute inset-y-0 right-3 flex items-center text-[11px] text-muted-foreground tabular-nums leading-none">{{ inputValue.length }}/{{ INPUT_MAX_LEN }}</span>
          </div>
          <button
            v-if="generating"
            type="button"
            @click="stopGenerate"
            class="box-border h-11 px-5 m-0 rounded-xl border border-transparent bg-primary text-primary-foreground hover:bg-primary/90 transition-colors inline-flex items-center justify-center gap-2 shrink-0 text-sm leading-none"
          >
            <Square class="size-3.5 fill-current" />
            {{ t('chat.stopGenerate') }}
          </button>
          <button
            v-else
            type="button"
            @click="sendMessage"
            :disabled="!inputValue.trim() && attachments.length === 0"
            class="box-border h-11 px-5 m-0 rounded-xl border border-transparent bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed transition-colors inline-flex items-center justify-center gap-2 shrink-0 text-sm leading-none"
          >
            <Send class="size-4" />
            {{ t('qa.send') }}
          </button>
        </div>
        <p class="text-xs text-muted-foreground mt-2 text-center">{{ t('qa.disclaimer') }}</p>
      </div>
    </div>

    <!-- 右侧源文件预览 -->
    <div
      v-if="rightPanelOpen"
      class="w-[min(36rem,42vw)] shrink-0 hidden xl:flex flex-col overflow-hidden rounded-lg border border-border bg-card"
    >
      <div class="flex items-center justify-between p-4 pb-3 border-b border-border shrink-0">
        <h3 class="text-sm font-semibold flex items-center gap-2 min-w-0">
          <FileCode class="size-4 text-primary shrink-0" />
          <span class="truncate">{{ preview.title || t('qa.sourceFilePreview') }}</span>
        </h3>
        <button type="button" class="size-7 inline-flex items-center justify-center rounded-md text-muted-foreground hover:text-foreground hover:bg-accent" :title="t('common.collapse')" @click="rightPanelOpen = false">
          <X class="size-4" />
        </button>
      </div>
      <DocumentPreviewBody
        v-if="preview.open"
        :key="`${preview.title}|${preview.previewBlobUrl}|${preview.previewType}|${preview.previewContent.slice(0, 32)}|${preview.highlightContent?.slice(0, 20)}`"
        :loading="preview.previewLoading"
        :preview-type="preview.previewType"
        :preview-content="preview.previewContent"
        :preview-is-html="preview.previewIsHtml"
        :preview-blob-url="preview.previewBlobUrl"
        :iframe-src="preview.iframeSrc"
        :preview-lines="preview.previewLines"
        :title="preview.title"
        :highlight-content="preview.highlightContent"
        :preview-pptx-buffer="preview.previewPptxBuffer"
        :preview-binary="preview.previewBinary"
        :dl-progress="preview.dlProgress"
      />
      <div v-else class="flex-1 flex flex-col items-center justify-center text-center py-12 px-6 text-muted-foreground">
        <FileText class="size-12 mb-3 opacity-40" />
        <p class="text-sm">{{ t('qa.sourceEmptyHint1') }}</p>
        <p class="text-xs mt-1">{{ t('qa.sourceEmptyHint2') }}</p>
      </div>
    </div>
    <button
      v-else
      type="button"
      class="hidden xl:flex flex-col items-center justify-center w-8 h-full bg-card border border-border rounded-lg text-muted-foreground hover:text-foreground hover:bg-muted/50 transition-colors"
      @click="rightPanelOpen = true"
      :title="t('qa.sourceFilePreview')"
    >
      <FileCode class="size-4" />
    </button>

    <DocumentPreviewDrawer
      :open="preview.open && !isXl"
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
  </div>
</template>

<style scoped>
/* 自定义滚动条样式 */
.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

.custom-scrollbar::-webkit-scrollbar-track {
  background: transparent;
}

.custom-scrollbar::-webkit-scrollbar-thumb {
  background: hsl(215 15% 30%);
  border-radius: 3px;
}

.custom-scrollbar::-webkit-scrollbar-thumb:hover {
  background: hsl(215 15% 40%);
}

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

/* 浅色主题适配 */
:global(.light) .custom-scrollbar::-webkit-scrollbar-thumb {
  background: hsl(215 15% 75%);
}

:global(.light) .custom-scrollbar::-webkit-scrollbar-thumb:hover {
  background: hsl(215 15% 65%);
}
</style>
