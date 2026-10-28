<script setup lang="ts">
import { ref, nextTick } from 'vue'
import { Eye, Send, Bot, User, Copy, ExternalLink } from 'lucide-vue-next'
import { toast } from '@/lib/toast'
import { copyText } from '@/lib/clipboard'

interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  sources?: { title: string; score: number }[]
  timestamp: Date
}

const INPUT_MAX_LEN = 500
const inputValue = ref('')
const isLoading = ref(false)
const messages = ref<Message[]>([
  {
    id: '1',
    role: 'assistant',
    content: '您好！我是海思芯片AI助手，基于CV系列芯片技术文档为您提供专业解答。您可以询问关于芯片规格、寄存器、外设接口、驱动开发等问题。',
    timestamp: new Date(),
  },
])

const suggestedQuestions = [
  'CV610的NPU算力是多少？',
  'UART寄存器基地址是多少？',
  '如何配置GPIO中断？',
  '支持哪些视频编码格式？',
]

const handleSend = () => {
  if (!inputValue.value.trim() || isLoading.value) return

  const content = inputValue.value.trim().slice(0, INPUT_MAX_LEN)
  const userMsg: Message = {
    id: Date.now().toString(),
    role: 'user',
    content,
    timestamp: new Date(),
  }
  messages.value.push(userMsg)
  const question = content
  inputValue.value = ''
  isLoading.value = true

  setTimeout(() => {
    const assistantMsg: Message = {
      id: (Date.now() + 1).toString(),
      role: 'assistant',
      content: `关于"${question}"的解答：\n\n根据CV620技术文档，相关规格参数如下：\n\n1. **核心配置**：四核Cortex-A55 + 独立NPU\n2. **NPU算力**：4TOPS INT8\n3. **内存支持**：LPDDR4X，最大支持8GB\n4. **视频编解码**：支持H.265/H.264编解码\n\n以上信息来源于技术文档第3.2节，详细内容请参考官方数据手册。`,
      sources: [
        { title: 'CV620 技术规格书 v1.2.pdf', score: 0.92 },
        { title: 'NPU 编程指南.pdf', score: 0.78 },
        { title: '系统架构设计文档.docx', score: 0.65 },
      ],
      timestamp: new Date(),
    }
    messages.value.push(assistantMsg)
    isLoading.value = false

    nextTick(() => {
      const container = document.querySelector('.chat-messages')
      if (container) {
        container.scrollTop = container.scrollHeight
      }
    })
  }, 1200)
}

const handleSuggestion = (q: string) => {
  inputValue.value = q.slice(0, INPUT_MAX_LEN)
  handleSend()
}

const handleCopy = (text: string) => {
  copyText(text).then(() => toast.success('已复制到剪贴板')).catch(() => toast.error('复制失败'))
}

const handleKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}
</script>

<template>
  <div class="space-y-4">
    <!-- Page header -->
    <div class="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
      <div>
        <h1 class="text-2xl font-bold tracking-tight flex items-center gap-2">
          <Eye class="size-6 text-primary" />
          机器人预览
        </h1>
        <p class="text-sm text-muted-foreground mt-1">
          预览Agent的实际对话效果，测试配置是否符合预期
        </p>
      </div>
      <div class="flex gap-2">
        <button class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors flex items-center gap-2">
          <Copy class="size-4" />
          复制嵌入代码
        </button>
        <button class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-sm transition-colors flex items-center gap-2">
          <ExternalLink class="size-4" />
          在新窗口打开
        </button>
      </div>
    </div>

    <!-- Chat preview -->
    <div class="rounded-lg border border-border bg-card overflow-hidden flex flex-col" style="height: calc(100vh - 16rem);">
      <!-- Chat header -->
      <div class="px-4 py-3 border-b border-border flex items-center gap-3 bg-muted/30">
        <div class="size-9 rounded-full bg-primary/10 text-primary flex items-center justify-center">
          <Bot class="size-5" />
        </div>
        <div class="flex-1">
          <div class="font-medium text-sm">海思芯片AI助手</div>
          <div class="text-xs text-emerald-500 flex items-center gap-1">
            <span class="size-1.5 rounded-full bg-emerald-500"></span>
            在线
          </div>
        </div>
      </div>

      <!-- Messages -->
      <div class="flex-1 overflow-y-auto p-4 space-y-4 chat-messages">
        <div
          v-for="msg in messages"
          :key="msg.id"
          :class="[
            'flex gap-3 max-w-[85%]',
            msg.role === 'user' ? 'ml-auto flex-row-reverse' : '',
          ]"
        >
          <div
            :class="[
              'size-8 rounded-full shrink-0 flex items-center justify-center',
              msg.role === 'assistant' ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground',
            ]"
          >
            <Bot v-if="msg.role === 'assistant'" class="size-4" />
            <User v-else class="size-4" />
          </div>
          <div :class="['flex-1 min-w-0', msg.role === 'user' ? 'text-right' : '']">
            <div
              :class="[
                'inline-block p-3 rounded-lg text-sm whitespace-pre-wrap text-left',
                msg.role === 'assistant'
                  ? 'bg-muted text-foreground'
                  : 'bg-primary text-primary-foreground',
              ]"
            >
              {{ msg.content }}
            </div>

            <!-- Sources -->
            <div v-if="msg.sources && msg.sources.length > 0" class="mt-2 space-y-1">
              <div class="text-xs text-muted-foreground mb-1">引用来源：</div>
              <div
                v-for="(src, idx) in msg.sources"
                :key="idx"
                class="text-xs p-2 rounded bg-muted/50 flex items-center justify-between gap-2"
              >
                <span class="truncate">{{ src.title }}</span>
                <span class="text-primary shrink-0 font-mono">{{ Math.round(src.score * 100) }}%</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Loading indicator -->
        <div v-if="isLoading" class="flex gap-3 max-w-[85%]">
          <div class="size-8 rounded-full bg-primary/10 text-primary shrink-0 flex items-center justify-center">
            <Bot class="size-4" />
          </div>
          <div class="bg-muted rounded-lg p-3">
            <div class="flex gap-1">
              <span class="size-2 rounded-full bg-muted-foreground/40 animate-bounce" style="animation-delay: 0ms;"></span>
              <span class="size-2 rounded-full bg-muted-foreground/40 animate-bounce" style="animation-delay: 150ms;"></span>
              <span class="size-2 rounded-full bg-muted-foreground/40 animate-bounce" style="animation-delay: 300ms;"></span>
            </div>
          </div>
        </div>
      </div>

      <!-- Suggested questions -->
      <div v-if="messages.length <= 1" class="px-4 pb-2">
        <div class="text-xs text-muted-foreground mb-2">试试这些问题：</div>
        <div class="flex flex-wrap gap-2">
          <button
            v-for="q in suggestedQuestions"
            :key="q"
            @click="handleSuggestion(q)"
            class="px-3 py-1.5 text-xs rounded-full border border-border hover:bg-accent hover:border-primary/50 transition-colors"
          >
            {{ q }}
          </button>
        </div>
      </div>

      <!-- Input area -->
      <div class="p-3 border-t border-border">
        <div class="flex gap-2 items-end">
          <div class="relative flex-1">
            <textarea
              v-model="inputValue"
              @keydown="handleKeydown"
              rows="1"
              :maxlength="INPUT_MAX_LEN"
              placeholder="输入您的问题..."
              class="w-full rounded-md border border-border bg-background px-3 pt-2 pb-5 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 resize-none max-h-32"
            ></textarea>
            <span class="pointer-events-none absolute bottom-1.5 right-2.5 text-[11px] text-muted-foreground tabular-nums leading-none">{{ inputValue.length }}/{{ INPUT_MAX_LEN }}</span>
          </div>
          <button
            @click="handleSend"
            :disabled="!inputValue.trim() || isLoading"
            :class="[
              'p-2.5 rounded-md transition-colors',
              inputValue.trim() && !isLoading
                ? 'bg-primary text-primary-foreground hover:bg-primary/90'
                : 'bg-muted text-muted-foreground cursor-not-allowed',
            ]"
          >
            <Send class="size-4" />
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
