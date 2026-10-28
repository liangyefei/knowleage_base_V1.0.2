<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import {
  Bot, Copy, Check, Terminal, Zap, ShieldCheck, Sparkles,
  Puzzle, Plug, KeyRound, ClipboardCheck, ListChecks, Info,
} from 'lucide-vue-next'
import { toast } from '@/lib/toast'
import { copyText } from '@/lib/clipboard'
import { useI18n } from '@/composables/useI18n'

// ── 运行时配置类型（对应 public/mcp-env.json） ──
interface McpEnvFile {
  environments: Record<string, { label: string }>
  hostMapping: { hosts: string[]; envKey: string }[]
  _defaults?: { unknownHostFallbackEnv?: string }
}

function resolveEnvKey(config: McpEnvFile, hostname: string): string {
  // 按 hostname 匹配
  const matched = config.hostMapping.find((entry) => entry.hosts.includes(hostname))
  if (matched) return matched.envKey
  // fallback
  return config._defaults?.unknownHostFallbackEnv ?? 'dev'
}

// ── Agent tool definitions ──
interface AgentTool {
  id: string
  name: string
  tag: string
  tagType: 'mcp' | 'rest'
  featureKeys: string[]
  status: 'available' | 'coming'
  configLang: string
}

const AGENT_TOOLS: AgentTool[] = [
  { id: 'claude', name: 'Claude Code', tag: 'MCP',       tagType: 'mcp',   featureKeys: ['f0','f1','f2','f3'], status: 'available', configLang: 'json' },
  { id: 'hermes', name: 'Hermes',      tag: 'MCP',       tagType: 'mcp',   featureKeys: ['f0','f1','f2','f3'], status: 'available', configLang: 'json' },
  { id: 'cursor', name: 'Cursor',      tag: 'MCP',       tagType: 'mcp',   featureKeys: ['f0','f1','f2','f3'], status: 'available', configLang: 'json' },
  { id: 'trae',   name: 'Trae',        tag: 'MCP',       tagType: 'mcp',   featureKeys: ['f0','f1','f2','f3'], status: 'coming',    configLang: 'json' },
]

// ── State ──
const { t } = useI18n()
const selectedAgent = ref('claude')
const copiedCode = ref<string | null>(null)

const runtimeConfig = ref<McpEnvFile>({ environments: {}, hostMapping: [], _defaults: { unknownHostFallbackEnv: 'dev' } })
const currentEnvKey = ref<string>('dev')

onMounted(async () => {
  try {
    const resp = await fetch('/mcp-env.json', { cache: 'no-cache' })
    runtimeConfig.value = await resp.json()
    currentEnvKey.value = resolveEnvKey(runtimeConfig.value, window.location.hostname)
  } catch (e) {
    console.error('[AgentIntegration] mcp-env.json 加载失败，请确认文件存在于 public/ 目录')
  }
})

const currentEnvLabel = computed(() =>
  runtimeConfig.value.environments[currentEnvKey.value]?.label ?? currentEnvKey.value.toUpperCase(),
)

// ── 配置示例：统一走 MCP 网关端点（host 取当前页面访问地址，开发/生产同域反代） ──
const MCP_GATEWAY_URL = `${window.location.origin}/hisilicon-knowledge-mcp/mcp`
const MCP_JSON_EXAMPLE = `{
  "mcpServers": {
    "hisilicon-knowledge-mcp": { "type": "http", "url": "${MCP_GATEWAY_URL}" }
  }
}`
const HERMES_YAML_EXAMPLE = `mcp_servers:
  hisilicon-knowledge-mcp:
    url: ${MCP_GATEWAY_URL}`

const currentAgent = computed(() =>
  AGENT_TOOLS.find((a) => a.id === selectedAgent.value) ?? AGENT_TOOLS[0],
)
const currentConfigExample = computed(() =>
  currentAgent.value.id === 'hermes' ? HERMES_YAML_EXAMPLE : MCP_JSON_EXAMPLE,
)

// ── 三步引导（文档 §7） ──
const guideSteps = computed(() => [
  { icon: KeyRound, title: t('integration.guide.step1.title'), desc: t('integration.guide.step1.desc') },
  { icon: ClipboardCheck, title: t('integration.guide.step2.title'), desc: t('integration.guide.step2.desc') },
  { icon: ListChecks, title: t('integration.guide.step3.title'), desc: t('integration.guide.step3.desc') },
])
const guideNotes = computed(() => [1, 2, 3, 4].map((n) => t(`integration.guide.note${n}`)))

// ── Actions ──
const handleCopy = async (code: string, id: string) => {
  if (!code) { toast.error(t('common.copyFailed')); return }
  try {
    await copyText(code)
    copiedCode.value = id
    toast.success(t('integration.copied'))
    setTimeout(() => { copiedCode.value = null }, 2000)
  } catch { toast.error(t('common.copyFailed')) }
}
</script>

<template>
  <div class="min-h-screen bg-frontend-bg flex flex-col">
    <!-- Page header -->
    <section class="w-full py-4 bg-gradient-to-b from-frontend-surface to-frontend-bg border-b border-frontend-border shrink-0">
      <div class="max-w-[1400px] mx-auto px-4 md:px-6">
        <div class="flex items-center justify-between gap-6 flex-wrap">
          <div class="min-w-0">
            <div class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-frontend-accent/5 text-frontend-fg text-[11px] font-medium mb-2">
              <Sparkles class="size-3" />
              {{ t('integration.badge') }}
            </div>
            <h1 class="text-xl font-bold text-frontend-fg leading-tight mb-1">
              {{ t('integration.pageTitle') }}
            </h1>
            <p class="text-xs text-frontend-muted line-clamp-1">
              {{ t('integration.pageSubtitle') }}
            </p>
          </div>
          <div class="flex items-center gap-2 shrink-0">
            <!-- 当前环境标签（由 hostMapping 配置决定） -->
            <div class="flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-frontend-surface border border-frontend-border">
              <span class="text-[11px] font-semibold px-1.5 py-0.5 text-frontend-muted">
                {{ currentEnvLabel }}
              </span>
            </div>
            <div class="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg bg-frontend-surface border border-frontend-border text-xs text-frontend-muted">
              <Puzzle class="size-3.5 text-frontend-fg" />
              MCP
            </div>
            <div class="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg bg-frontend-surface border border-frontend-border text-xs text-frontend-muted">
              <Plug class="size-3.5 text-frontend-fg" />
              REST API
            </div>
            <div class="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg bg-frontend-surface border border-frontend-border text-xs text-frontend-muted">
              <ShieldCheck class="size-3.5 text-frontend-fg" />
              {{ t('integration.auth') }}
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- Main content -->
    <div class="max-w-[1400px] w-full mx-auto px-4 md:px-6 py-3 flex flex-col gap-3 shrink-0">
      <!-- Supported AI tools -->
      <section>
        <h2 class="text-sm font-semibold text-frontend-fg mb-2">{{ t('integration.supportedTools') }}</h2>
        <div class="grid grid-cols-2 md:grid-cols-4 gap-2.5">
          <div
            v-for="tool in AGENT_TOOLS"
            :key="tool.id"
            :class="[
              'cursor-pointer transition-all duration-200 bg-frontend-surface hover:shadow-sm rounded-xl border p-3',
              selectedAgent === tool.id
                ? 'border-frontend-fg ring-1 ring-frontend-fg'
                : 'border-frontend-border hover:border-frontend-accent/30',
            ]"
            @click="selectedAgent = tool.id"
          >
            <div class="flex items-start justify-between mb-2">
              <div class="flex items-center gap-2">
                <div class="size-7 rounded-md bg-frontend-accent/10 flex items-center justify-center shrink-0">
                  <Bot class="size-4 text-frontend-fg" />
                </div>
                <span class="font-semibold text-frontend-fg text-sm truncate">{{ tool.name }}</span>
              </div>
              <span
                :class="[
                  'text-[10px] px-1.5 py-0 h-4 rounded border font-medium shrink-0',
                  tool.tagType === 'mcp'
                    ? 'bg-frontend-accent/5 text-frontend-fg border-frontend-accent/20'
                    : 'bg-emerald-50 text-emerald-700 border-emerald-200',
                ]"
              >
                {{ tool.tag }}
              </span>
            </div>
            <p class="text-[11px] text-frontend-muted leading-snug line-clamp-2 mb-2 h-8">
              {{ t(`integration.agent.${tool.id}.desc`) }}
            </p>
            </div>
        </div>
      </section>

      <!-- Three-step guide -->
      <section>
        <h2 class="text-sm font-semibold text-frontend-fg mb-2">{{ t('integration.guide.title') }}</h2>
        <div class="grid grid-cols-1 md:grid-cols-3 gap-2.5">
          <div
            v-for="(step, i) in guideSteps"
            :key="i"
            class="bg-frontend-surface border border-frontend-border rounded-xl p-3.5"
          >
            <div class="flex items-center gap-2 mb-1.5">
              <div class="relative size-7 rounded-md bg-frontend-accent/10 flex items-center justify-center shrink-0">
                <component :is="step.icon" class="size-4 text-frontend-fg" />
                <span class="absolute -top-1.5 -right-1.5 size-4 rounded-full bg-frontend-fg text-frontend-bg text-[9px] font-bold flex items-center justify-center">{{ i + 1 }}</span>
              </div>
              <span class="font-semibold text-frontend-fg text-sm">{{ step.title }}</span>
            </div>
            <p class="text-[11px] text-frontend-muted leading-relaxed">{{ step.desc }}</p>
          </div>
        </div>
        <div class="mt-2.5 bg-frontend-accent/5 border border-frontend-border rounded-xl p-3">
          <div class="flex items-center gap-1.5 mb-1.5">
            <Info class="size-3.5 text-frontend-fg" />
            <span class="text-xs font-semibold text-frontend-fg">{{ t('integration.guide.notes.title') }}</span>
          </div>
          <ul class="space-y-1">
            <li v-for="(note, i) in guideNotes" :key="i" class="text-[11px] text-frontend-muted leading-relaxed flex gap-1.5">
              <span class="shrink-0">·</span>
              <span>{{ note }}</span>
            </li>
          </ul>
        </div>
      </section>

      <!-- Integration guide -->
      <section class="shrink-0 pb-6">
        <div class="bg-frontend-surface border border-frontend-border rounded-xl">
          <div class="p-4">
            <div class="flex items-center gap-2 mb-3">
              <Zap class="size-3.5 text-frontend-fg" />
              <h3 class="text-sm font-semibold text-frontend-fg">{{ t('integration.howTo') }}</h3>
              <span class="text-[10px] px-1.5 h-4 rounded border bg-frontend-accent/5 text-frontend-fg border-frontend-accent/20 flex items-center ml-1">
                {{ currentEnvLabel }}
              </span>
            </div>

            <div class="mb-3">
              <div class="text-xs font-medium text-frontend-fg mb-1.5">
                {{ currentAgent.name }} - {{ currentAgent.tag }}
              </div>
              <div class="p-2.5 rounded-lg bg-frontend-input-bg border border-frontend-border/60">
                <p class="text-xs text-frontend-muted mb-1.5 line-clamp-2">{{ t(`integration.agent.${currentAgent.id}.desc`) }}</p>
                <div class="flex flex-wrap gap-1">
                  <span
                    v-for="fk in currentAgent.featureKeys.slice(0, 4)"
                    :key="fk"
                    class="text-[10px] px-1.5 py-0.5 rounded-full bg-frontend-surface border border-frontend-border text-frontend-muted"
                  >
                    {{ t(`integration.agent.${currentAgent.id}.${fk}`) }}
                  </span>
                </div>
              </div>
            </div>

            <div class="border-t border-frontend-border pt-3">
              <div class="flex items-center justify-between mb-2">
                <div class="flex items-center gap-2">
                  <Terminal class="size-3.5 text-frontend-fg" />
                  <span class="text-xs font-medium text-frontend-fg">{{ t('integration.configExample') }}</span>
                </div>
                <button
                  type="button"
                  class="h-6 text-[11px] gap-1 text-frontend-muted hover:text-frontend-fg hover:bg-frontend-accent/5 px-2 rounded flex items-center transition-colors"
                  @click.stop="handleCopy(currentConfigExample, `config-${currentAgent.id}`)"
                >
                  <Check v-if="copiedCode === `config-${currentAgent.id}`" class="size-3" />
                  <Copy v-else class="size-3" />
                  {{ copiedCode === `config-${currentAgent.id}` ? t('integration.copied') : t('common.copy') }}
                </button>
              </div>
              <pre class="p-3 rounded-lg bg-frontend-input-bg border border-frontend-border overflow-auto text-[11px] leading-relaxed text-frontend-muted font-mono select-text max-h-[260px]"><code class="select-text whitespace-pre-wrap break-all">{{ currentConfigExample }}</code></pre>
            </div>
          </div>
        </div>
      </section>

    </div>
  </div>
</template>
