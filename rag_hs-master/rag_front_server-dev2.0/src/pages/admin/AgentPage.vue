<script setup lang="ts">
import { ref, computed } from 'vue'
import {
  Bot, Plus, Pencil, Trash2, Search, Copy, Check,
  Code2, Plug, X, Puzzle, Server,
} from 'lucide-vue-next'
import { toast } from '@/lib/toast'
import { copyText } from '@/lib/clipboard'
import { MOCK_AGENTS, type IAgentCard } from '@/data/agents'
import { useI18n } from '@/composables/useI18n'

const { t } = useI18n()

function isValidUrl(url: string): boolean {
  if (!url.trim()) return false
  try {
    const u = new URL(url)
    return u.protocol === 'http:' || u.protocol === 'https:'
  } catch {
    return false
  }
}

// ── State ──
const agents = ref<IAgentCard[]>(MOCK_AGENTS)
const selectedAgent = ref<string>(MOCK_AGENTS[0]?.id || '')
const searchKeyword = ref('')
const statusFilter = ref<'all' | 'available' | 'coming-soon'>('all')
const copiedCode = ref<string | null>(null)

// ── Agent dialog ──
interface AgentFormData {
  name: string
  integrationType: 'MCP' | 'REST API'
  accessUrl: string
  description: string
  featuresCount: number
  status: 'available' | 'coming-soon'
}

const defaultFormData: AgentFormData = {
  name: '',
  integrationType: 'MCP',
  accessUrl: '',
  description: '',
  featuresCount: 1,
  status: 'coming-soon',
}

const agentDialogMode = ref<'add' | 'edit'>('add')
const agentDialogOpen = ref(false)
const agentForm = ref<AgentFormData>({ ...defaultFormData })
const editingAgentId = ref<string | null>(null)
const deleteTargetId = ref<string | null>(null)
const touched = ref<Record<string, boolean>>({})

// ── 详情面板编辑状态 ──
const isEditingConfig = ref(false)
const editConfigUrl = ref('')
const isEditingExample = ref(false)
const editExampleCode = ref('')

// ── Computed ──
const filteredAgents = computed(() =>
  agents.value.filter((a) => {
    const matchKeyword = !searchKeyword.value.trim() ||
      a.name.toLowerCase().includes(searchKeyword.value.toLowerCase()) ||
      a.description.toLowerCase().includes(searchKeyword.value.toLowerCase())
    const matchStatus = statusFilter.value === 'all' || a.status === statusFilter.value
    return matchKeyword && matchStatus
  })
)

const currentAgent = computed(() => agents.value.find((a) => a.id === selectedAgent.value))

const isMcp = computed(() => currentAgent.value?.integrationType === 'MCP')

const configUrl = computed(() => {
  if (!currentAgent.value) return ''
  const urlKey = isMcp.value ? 'mcpServerUrl' : 'endpoint'
  const urlField = currentAgent.value.configFields.find((f) => f.key === urlKey)
  if (urlField && urlField.label && (urlField.label.startsWith('http://') || urlField.label.startsWith('https://'))) {
    return urlField.label
  }
  const name = currentAgent.value.name.toLowerCase().replace(/\s+/g, '-')
  return isMcp.value
    ? `https://mcp.hisilicon.example.com/${name}`
    : `https://api.hisilicon.example.com/v1/${name}`
})

const exampleCode = computed(() => {
  if (!currentAgent.value) return ''
  if (currentAgent.value.customExampleCode !== undefined) {
    return currentAgent.value.customExampleCode
  }
  if (isMcp.value) {
    return `{
  "mcpServers": {
    "hisilicon-kb": {
      "url": "${configUrl.value}",
      "transport": "sse",
      "headers": {
        "Authorization": "<your-api-key>"
      }
    }
  }
}`
  }
  return `curl -X POST ${configUrl.value}/search \\
  -H "Authorization: Bearer <your-access-token>" \\
  -H "Content-Type: application/json" \\
  -d '{"query": "Hi3559 ISP寄存器", "limit": 10}'`
})

const nameError = computed(() => !agentForm.value.name.trim())
const urlError = computed(() => !isValidUrl(agentForm.value.accessUrl))
const hasError = computed(() => nameError.value || urlError.value)

// ── Copy ──
const handleCopy = async (code: string, id: string) => {
  if (!code) {
    toast.error(t('agent.toastCopyFailed'))
    return
  }
  try {
    await copyText(code)
    copiedCode.value = id
    toast.success(t('agent.toastCopied'))
    setTimeout(() => { copiedCode.value = null }, 2000)
  } catch {
    toast.error(t('agent.toastCopyFailed'))
  }
}

// ── 配置URL编辑 ──
const startEditConfig = () => {
  if (!currentAgent.value) return
  editConfigUrl.value = configUrl.value
  isEditingConfig.value = true
}

const cancelEditConfig = () => {
  isEditingConfig.value = false
  editConfigUrl.value = ''
}

const saveEditConfig = () => {
  if (!currentAgent.value) return
  if (!editConfigUrl.value.trim()) {
    toast.error(t('agent.toastConfigEmpty'))
    return
  }
  const urlKey = isMcp.value ? 'mcpServerUrl' : 'endpoint'
  agents.value = agents.value.map((a) => {
    if (a.id !== currentAgent.value!.id) return a
    const hasUrlField = a.configFields.some((f) => f.key === urlKey)
    const newFields = hasUrlField
      ? a.configFields.map((f) =>
          f.key === urlKey ? { ...f, label: editConfigUrl.value.trim() } : f
        )
      : [
          { key: urlKey, label: editConfigUrl.value.trim(), type: 'text' as const, required: true },
          ...a.configFields,
        ]
    return { ...a, configFields: newFields }
  })
  isEditingConfig.value = false
  toast.success(t('agent.toastConfigSaved'))
}

// ── 请求示例编辑 ──
const startEditExample = () => {
  editExampleCode.value = exampleCode.value
  isEditingExample.value = true
}

const cancelEditExample = () => {
  isEditingExample.value = false
  editExampleCode.value = ''
}

const saveEditExample = () => {
  if (!currentAgent.value) return
  if (!editExampleCode.value.trim()) {
    toast.error(t('agent.toastExampleEmpty'))
    return
  }
  agents.value = agents.value.map((a) =>
    a.id === currentAgent.value!.id ? { ...a, customExampleCode: editExampleCode.value } : a
  )
  isEditingExample.value = false
  toast.success(t('agent.toastExampleSaved'))
}

// ── Agent CRUD ──
const openAddAgent = () => {
  agentDialogMode.value = 'add'
  agentForm.value = { ...defaultFormData }
  editingAgentId.value = null
  touched.value = {}
  agentDialogOpen.value = true
}

const openEditAgent = (agent: IAgentCard) => {
  agentDialogMode.value = 'edit'
  editingAgentId.value = agent.id
  const isMcpAgent = agent.integrationType === 'MCP'
  const urlKey = isMcpAgent ? 'mcpServerUrl' : 'endpoint'
  const urlField = agent.configFields.find((f) => f.key === urlKey)
  const name = agent.name.toLowerCase().replace(/\s+/g, '-')
  const accessUrl = (urlField?.label && (urlField.label.startsWith('http://') || urlField.label.startsWith('https://')))
    ? urlField.label
    : (isMcpAgent ? `https://mcp.hisilicon.example.com/${name}` : `https://api.hisilicon.example.com/v1/${name}`)
  agentForm.value = {
    name: agent.name,
    integrationType: agent.integrationType,
    accessUrl,
    description: agent.description,
    featuresCount: agent.features.length,
    status: agent.status,
  }
  touched.value = {}
  agentDialogOpen.value = true
}

const handleSubmitAgent = () => {
  touched.value = { name: true, accessUrl: true }
  if (hasError.value) {
    toast.error(t('agent.formRequiredFields'))
    return
  }

  const featureLabels = Array.from({ length: agentForm.value.featuresCount }, (_, i) =>
    t('agent.featureLabel', { index: i + 1 })
  )

  if (agentDialogMode.value === 'add') {
    const newAgent: IAgentCard = {
      id: Date.now().toString(),
      name: agentForm.value.name.trim(),
      description: agentForm.value.description.trim() || t('agent.noDescription'),
      integrationType: agentForm.value.integrationType,
      status: agentForm.value.status,
      features: featureLabels,
      configFields: agentForm.value.integrationType === 'MCP'
        ? [
            { key: 'mcpServerUrl', label: agentForm.value.accessUrl.trim(), type: 'text' as const, required: true },
            { key: 'apiKey', label: t('agent.apiKey'), type: 'password' as const, required: true },
          ]
        : [
            { key: 'endpoint', label: agentForm.value.accessUrl.trim(), type: 'text' as const, required: true },
            { key: 'accessToken', label: t('agent.accessToken'), type: 'password' as const, required: true },
          ],
      apiList: [],
      sdkList: [],
      docList: [],
    }
    agents.value = [newAgent, ...agents.value]
    selectedAgent.value = newAgent.id
    toast.success(t('agent.toastAdded'))
  } else if (editingAgentId.value) {
    const urlKey = agentForm.value.integrationType === 'MCP' ? 'mcpServerUrl' : 'endpoint'
    agents.value = agents.value.map((a) =>
      a.id === editingAgentId.value
        ? {
            ...a,
            name: agentForm.value.name.trim(),
            description: agentForm.value.description.trim() || a.description,
            integrationType: agentForm.value.integrationType,
            status: agentForm.value.status,
            features: featureLabels,
            configFields: a.configFields.some((f) => f.key === urlKey)
              ? a.configFields.map((f) => f.key === urlKey ? { ...f, label: agentForm.value.accessUrl.trim() } : f)
              : [{ key: urlKey, label: agentForm.value.accessUrl.trim(), type: 'text' as const, required: true }, ...a.configFields],
          }
        : a
    )
    toast.success(t('agent.toastUpdated'))
  }

  agentDialogOpen.value = false
}

const handleDeleteAgent = () => {
  if (!deleteTargetId.value) return
  const target = agents.value.find((a) => a.id === deleteTargetId.value)
  agents.value = agents.value.filter((a) => a.id !== deleteTargetId.value)
  if (selectedAgent.value === deleteTargetId.value) {
    const remaining = agents.value.filter((a) => a.id !== deleteTargetId.value)
    selectedAgent.value = remaining[0]?.id || ''
  }
  toast.success(t('agent.toastDeleted', { name: target?.name ?? '' }))
  deleteTargetId.value = null
}
</script>

<template>
  <div class="space-y-6">
    <!-- Header -->
    <div class="flex items-start justify-between gap-4">
      <div>
        <h1 class="text-2xl font-bold tracking-tight flex items-center gap-2">
          <Bot class="size-6 text-primary" />
          {{ t('agent.pageTitle') }}
        </h1>
        <p class="text-sm text-muted-foreground mt-1">
          {{ t('agent.pageSubtitle') }}
        </p>
      </div>
      <button @click="openAddAgent" class="px-4 py-2 bg-primary text-primary-foreground hover:bg-primary/90 rounded-md text-sm font-medium transition-colors flex items-center gap-2">
        <Plus class="size-4" />
        {{ t('agent.addAgent') }}
      </button>
    </div>

    <!-- Toolbar -->
    <div class="rounded-lg border border-border bg-card p-3">
      <div class="flex flex-col md:flex-row items-start md:items-center gap-3">
        <div class="flex-1 max-w-[320px]">
          <div class="relative">
            <Search class="absolute left-3 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
            <input
              v-model="searchKeyword"
              type="text"
              :placeholder="t('agent.searchPlaceholder')"
              class="w-full h-9 pl-9 pr-4 rounded-md border border-border bg-background text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
            />
          </div>
        </div>
        <div class="flex items-center gap-2">
          <span class="text-sm text-muted-foreground">{{ t('agent.statusLabel') }}</span>
          <select
            v-model="statusFilter"
            class="h-9 w-[140px] rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          >
            <option value="all">{{ t('agent.statusAll') }}</option>
            <option value="available">{{ t('agent.statusAvailable') }}</option>
            <option value="coming-soon">{{ t('agent.statusComingSoon') }}</option>
          </select>
        </div>
        <div class="text-xs text-muted-foreground md:ml-auto">
          {{ t('agent.totalCount', { count: filteredAgents.length }) }}
        </div>
      </div>
    </div>

    <!-- Agent cards grid -->
    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
      <div
        v-for="agent in filteredAgents"
        :key="agent.id"
        @click="selectedAgent = agent.id"
        :class="[
          'rounded-lg border bg-card p-4 transition-all hover:shadow-md cursor-pointer',
          selectedAgent === agent.id ? 'border-primary ring-1 ring-primary' : 'border-border',
        ]"
      >
        <div class="flex items-start gap-3">
          <div class="size-10 rounded-lg bg-primary/10 flex items-center justify-center shrink-0">
            <Bot class="size-5 text-primary" />
          </div>
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2">
              <h3
                class="font-semibold text-sm hover:text-primary transition-colors"
              >
                {{ agent.name }}
              </h3>
              <span class="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] border border-border text-muted-foreground">
                {{ agent.integrationType }}
              </span>
            </div>
            <p class="text-xs text-muted-foreground mt-1 line-clamp-2">
              {{ agent.description }}
            </p>
          </div>
          <div class="flex items-center gap-1 shrink-0">
            <button
              @click.stop="openEditAgent(agent)"
              class="size-7 flex items-center justify-center rounded hover:bg-accent transition-colors"
              :title="t('common.edit')"
            >
              <Pencil class="size-3.5 text-muted-foreground" />
            </button>
            <button
              @click.stop="deleteTargetId = agent.id"
              class="size-7 flex items-center justify-center rounded hover:bg-red-50 hover:text-red-500 transition-colors"
              :title="t('common.delete')"
            >
              <Trash2 class="size-3.5 text-muted-foreground" />
            </button>
          </div>
        </div>
        <div class="flex items-center justify-between mt-3 pt-3 border-t border-border">
          <span class="text-xs text-muted-foreground">{{ t('agent.featureCount', { count: agent.features.length }) }}</span>
          <span
            :class="[
              'inline-flex items-center px-1.5 py-0.5 rounded text-[10px] border',
              agent.status === 'available' ? 'text-emerald-500 border-emerald-500/30' : 'text-muted-foreground border-border',
            ]"
          >
            {{ agent.status === 'available' ? t('agent.statusAvailable') : t('agent.statusComingSoon') }}
          </span>
        </div>
      </div>
    </div>

    <!-- Agent detail -->
    <template v-if="currentAgent">
      <div class="rounded-lg border border-border bg-card overflow-hidden">
        <div class="p-6 space-y-5">
          <!-- 标题区 -->
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-2">
              <Puzzle class="size-5 text-primary" />
              <span class="text-lg font-semibold">
                {{ isMcp ? t('agent.mcpConfigTitle') : t('agent.restApiConfigTitle') }}
              </span>
              <span class="text-muted-foreground">{{ currentAgent.name }}</span>
            </div>
            <div class="flex items-center gap-2">
              <template v-if="!isEditingConfig">
                <button
                  @click="startEditConfig"
                  class="inline-flex items-center gap-1.5 px-2.5 h-7 rounded-md border border-border hover:bg-accent text-xs transition-colors"
                >
                  <Pencil class="size-3.5" />
                  {{ t('common.edit') }}
                </button>
              </template>
              <template v-else>
                <button
                  @click="cancelEditConfig"
                  class="inline-flex items-center gap-1.5 px-2.5 h-7 rounded-md border border-border hover:bg-accent text-xs transition-colors"
                >
                  {{ t('common.cancel') }}
                </button>
                <button
                  @click="saveEditConfig"
                  class="inline-flex items-center gap-1.5 px-2.5 h-7 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-xs transition-colors"
                >
                  {{ t('common.save') }}
                </button>
              </template>
            </div>
          </div>

          <!-- 配置字段行 -->
          <div class="grid grid-cols-1 gap-4">
            <div class="space-y-2">
              <div class="flex items-center gap-1.5 text-sm text-muted-foreground">
                <Server class="size-3.5" />
                <span>{{ isMcp ? t('agent.mcpServerUrl') : t('agent.endpointLabel') }}</span>
              </div>
              <div class="relative">
                <input
                  :value="configUrl"
                  :readonly="!isEditingConfig"
                  :class="['w-full h-9 rounded-md border border-border bg-background px-3 pr-10 text-sm font-mono outline-none', !isEditingConfig ? 'bg-muted/30' : 'focus:border-ring focus:ring-2 focus:ring-ring/20']"
                />
                <button
                  v-if="!isEditingConfig"
                  @click="handleCopy(configUrl, 'config-url')"
                  class="!absolute right-1 top-1/2 -translate-y-1/2 h-7 w-7 flex items-center justify-center rounded hover:bg-accent"
                  :title="t('agent.copyCode')"
                >
                  <Check v-if="copiedCode === 'config-url'" class="size-3.5 text-emerald-500" />
                  <Copy v-else class="size-3.5 text-muted-foreground" />
                </button>
                <input
                  v-if="isEditingConfig"
                  v-model="editConfigUrl"
                  class="!absolute inset-0 w-full h-9 rounded-md border border-border bg-background px-3 pr-10 text-sm font-mono outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
                  :placeholder="isMcp ? t('agent.mcpServerUrl') : t('agent.endpointLabel')"
                />
              </div>
            </div>
          </div>

          <!-- 请求示例代码块 -->
          <div class="rounded-lg border overflow-hidden">
            <div class="flex items-center justify-between px-4 py-2.5 border-b bg-muted/30">
              <div class="flex items-center gap-2 text-sm font-medium">
                <Code2 class="size-4 text-muted-foreground" />
                {{ t('agent.requestExample') }}
                <span class="text-muted-foreground font-normal">
                  ({{ isMcp ? 'mcp.json' : 'cURL' }})
                </span>
              </div>
              <div class="flex items-center gap-2">
                <template v-if="isEditingExample">
                  <button
                    @click="cancelEditExample"
                    class="inline-flex items-center gap-1.5 px-2.5 h-7 rounded-md border border-border hover:bg-accent text-xs transition-colors"
                  >
                    <X class="size-3.5" />
                    {{ t('common.cancel') }}
                  </button>
                  <button
                    @click="saveEditExample"
                    class="inline-flex items-center gap-1.5 px-2.5 h-7 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-xs transition-colors"
                  >
                    <Check class="size-3.5" />
                    {{ t('common.save') }}
                  </button>
                </template>
                <template v-else>
                  <button
                    @click="startEditExample"
                    class="inline-flex items-center gap-1.5 px-2.5 h-7 rounded-md border border-border hover:bg-accent text-xs transition-colors"
                  >
                    <Pencil class="size-3.5" />
                    {{ t('common.edit') }}
                  </button>
                </template>
              </div>
            </div>
            <textarea
              v-if="isEditingExample"
              v-model="editExampleCode"
              class="w-full p-4 font-mono text-xs leading-relaxed min-h-[200px] resize-y outline-none bg-background rounded-none border-0 focus-visible:ring-0"
            ></textarea>
            <pre v-else class="p-4 overflow-x-auto bg-muted/10 font-mono text-xs leading-relaxed"><code>{{ exampleCode }}</code></pre>
          </div>
        </div>
      </div>
    </template>

    <!-- ── Dialogs ── -->

    <!-- Agent dialog -->
    <div v-if="agentDialogOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="agentDialogOpen = false">
      <div class="w-full max-w-xl max-h-[85vh] rounded-lg border border-border bg-card shadow-xl flex flex-col overflow-hidden" @click.stop>
        <div class="p-6 border-b border-border">
          <div class="flex items-center justify-between">
            <div>
              <h3 class="text-lg font-semibold">{{ agentDialogMode === 'add' ? t('agent.addAgent') : t('agent.editAgent') }}</h3>
              <p class="text-sm text-muted-foreground mt-0.5">{{ agentDialogMode === 'add' ? t('agent.dialogAddSubtitle') : t('agent.dialogEditSubtitle') }}</p>
            </div>
            <button @click="agentDialogOpen = false" class="size-8 flex items-center justify-center rounded hover:bg-accent"><X class="size-4" /></button>
          </div>
        </div>
        <div class="flex-1 overflow-y-auto p-6 space-y-4">
          <!-- Agent名称 -->
          <div class="space-y-2">
            <label class="text-sm font-medium">{{ t('agent.formName') }} <span class="text-red-500">*</span></label>
            <input
              v-model="agentForm.name"
              type="text"
              :placeholder="t('agent.formNamePlaceholder')"
              :class="['w-full h-9 rounded-md border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20', touched.name && nameError ? 'border-red-500 focus-visible:ring-red-500' : 'border-border']"
              @blur="touched.name = true"
            />
            <p v-if="touched.name && nameError" class="text-xs text-red-500">{{ t('agent.formRequiredError') }}</p>
          </div>

          <!-- 接入类型 + 功能特性数量 -->
          <div class="grid grid-cols-2 gap-4">
            <div class="space-y-2">
              <label class="text-sm font-medium">{{ t('agent.formIntegrationType') }}</label>
              <select v-model="agentForm.integrationType" class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20">
                <option value="MCP">{{ t('agent.mcpProtocol') }}</option>
                <option value="REST API">REST API</option>
              </select>
            </div>
            <div class="space-y-2">
              <label class="text-sm font-medium">{{ t('agent.formFeaturesCount') }}</label>
              <input
                v-model.number="agentForm.featuresCount"
                type="number"
                min="1"
                max="20"
                class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
              />
            </div>
          </div>

          <!-- 接入地址 -->
          <div class="space-y-2">
            <label class="text-sm font-medium">{{ t('agent.formAccessUrl') }} <span class="text-red-500">*</span></label>
            <input
              v-model="agentForm.accessUrl"
              type="url"
              :placeholder="t('agent.formAccessUrlPlaceholder')"
              :class="['w-full h-9 rounded-md border bg-background px-3 text-sm font-mono outline-none focus:border-ring focus:ring-2 focus:ring-ring/20', touched.accessUrl && urlError ? 'border-red-500 focus-visible:ring-red-500' : 'border-border']"
              @blur="touched.accessUrl = true"
            />
            <p v-if="touched.accessUrl && urlError" class="text-xs text-red-500">{{ t('agent.formUrlError') }}</p>
          </div>

          <!-- 描述 -->
          <div class="space-y-2">
            <label class="text-sm font-medium">{{ t('agent.formDescription') }}</label>
            <textarea v-model="agentForm.description" :placeholder="t('agent.formDescriptionPlaceholder')" rows="3" class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 resize-none"></textarea>
          </div>

          <!-- 连接状态 -->
          <div class="flex items-center justify-between pt-2">
            <div class="space-y-0.5">
              <label class="text-sm font-medium">{{ t('agent.formConnectionStatus') }}</label>
              <p class="text-xs text-muted-foreground">
                {{ agentForm.status === 'available' ? t('agent.statusConnected') : t('agent.statusComingSoon') }}
              </p>
            </div>
            <div class="flex items-center gap-2">
              <button
                type="button"
                role="switch"
                :aria-checked="agentForm.status === 'available'"
                :class="['relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors', agentForm.status === 'available' ? 'bg-primary' : 'bg-muted']"
                @click="agentForm.status = agentForm.status === 'available' ? 'coming-soon' : 'available'"
              >
                <span :class="['pointer-events-none block size-4 rounded-full bg-white shadow-lg transition-transform', agentForm.status === 'available' ? 'translate-x-4' : 'translate-x-0']" />
              </button>
              <span class="text-xs text-muted-foreground cursor-pointer" @click="agentForm.status = agentForm.status === 'available' ? 'coming-soon' : 'available'">
                {{ agentForm.status === 'available' ? t('agent.statusConnected') : t('agent.statusComingSoon') }}
              </span>
            </div>
          </div>
        </div>
        <div class="flex justify-end gap-2 p-6 border-t border-border">
          <button @click="agentDialogOpen = false" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors">{{ t('common.cancel') }}</button>
          <button
            @click="handleSubmitAgent"
            :disabled="hasError && Object.keys(touched).length > 0"
            class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-sm transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >{{ agentDialogMode === 'add' ? t('common.add') : t('common.save') }}</button>
        </div>
      </div>
    </div>

    <!-- Agent delete confirmation -->
    <div v-if="deleteTargetId" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="deleteTargetId = null">
      <div class="w-full max-w-md rounded-lg border border-border bg-card p-6 shadow-xl" @click.stop>
        <div class="mb-4">
          <h3 class="text-lg font-semibold">{{ t('agent.confirmDelete') }}</h3>
          <p class="text-sm text-muted-foreground mt-1">
            {{ t('agent.confirmDeleteMsg') }}
          </p>
        </div>
        <div class="flex justify-end gap-2">
          <button @click="deleteTargetId = null" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors">{{ t('common.cancel') }}</button>
          <button @click="handleDeleteAgent" class="px-4 py-2 rounded-md bg-red-500 text-white hover:bg-red-600 text-sm transition-colors">{{ t('common.delete') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>
