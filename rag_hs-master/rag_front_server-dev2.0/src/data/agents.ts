// EXPORTS: IAgentCard, IAgentApiItem, IAgentSdkItem, IAgentDocItem, MOCK_AGENTS

export type IntegrationType = 'MCP' | 'REST API'

export interface IAgentApiItem {
  id: string
  name: string
  method: 'GET' | 'POST' | 'PUT' | 'DELETE'
  path: string
  description: string
}

export interface IAgentSdkItem {
  id: string
  name: string
  language: 'Python' | 'TypeScript' | 'Java' | 'Go' | 'C++' | '其他'
  version: string
  downloadUrl: string
}

export interface IAgentDocItem {
  id: string
  name: string
  type: '接入指南' | 'API参考' | '最佳实践' | '示例代码' | '更新日志'
  link: string
}

export interface IAgentCard {
  id: string
  name: string
  description: string
  integrationType: IntegrationType
  status: 'available' | 'coming-soon'
  features: string[]
  configFields: { key: string; label: string; type: 'text' | 'password' | 'number'; required: boolean }[]
  apiList: IAgentApiItem[]
  sdkList: IAgentSdkItem[]
  docList: IAgentDocItem[]
  customExampleCode?: string
}

export const MOCK_AGENTS: IAgentCard[] = [
  {
    id: '1',
    name: 'Claude Code',
    description: 'Anthropic的AI编程助手，通过MCP协议接入海思知识库',
    integrationType: 'MCP',
    status: 'available',
    features: ['代码生成与补全', '知识库检索问答', '代码审查与重构', '调试与问题定位'],
    configFields: [
      { key: 'apiKey', label: 'API Key', type: 'password', required: true },
      { key: 'model', label: '模型版本', type: 'text', required: false },
    ],
    apiList: [
      { id: 'a1', name: '知识检索', method: 'POST', path: '/api/v1/knowledge/search', description: '根据查询返回相关知识片段' },
      { id: 'a2', name: '代码生成', method: 'POST', path: '/api/v1/code/generate', description: '根据需求生成代码片段' },
      { id: 'a3', name: '代码审查', method: 'POST', path: '/api/v1/code/review', description: '审查代码并给出改进建议' },
    ],
    sdkList: [
      { id: 's1', name: 'Python SDK', language: 'Python', version: '1.2.0', downloadUrl: 'https://pypi.org/project/claude-code-sdk' },
      { id: 's2', name: 'TypeScript SDK', language: 'TypeScript', version: '1.1.0', downloadUrl: 'https://npmjs.com/package/claude-code-sdk' },
    ],
    docList: [
      { id: 'd1', name: '接入指南', type: '接入指南', link: '/docs/claude-code/guide' },
      { id: 'd2', name: 'API参考', type: 'API参考', link: '/docs/claude-code/api' },
      { id: 'd3', name: '最佳实践', type: '最佳实践', link: '/docs/claude-code/best-practices' },
    ],
  },
  {
    id: '2',
    name: 'Hermes',
    description: '字节跳动自研AI编程助手，深度集成海思开发工具链',
    integrationType: 'REST API',
    status: 'available',
    features: ['中文语境优化', '海思SDK深度集成', '硬件寄存器查询', '代码文档双向映射'],
    configFields: [
      { key: 'endpoint', label: '服务端点', type: 'text', required: true },
      { key: 'accessToken', label: '访问令牌', type: 'password', required: true },
    ],
    apiList: [
      { id: 'a1', name: '智能问答', method: 'POST', path: '/api/v1/qa/ask', description: '返回结构化回答' },
      { id: 'a2', name: '寄存器查询', method: 'GET', path: '/api/v1/hardware/register', description: '查询寄存器详细信息' },
      { id: 'a3', name: '代码映射', method: 'POST', path: '/api/v1/codemap/analyze', description: '分析代码并映射文档' },
      { id: 'a4', name: '文档上传', method: 'POST', path: '/api/v1/documents/upload', description: '上传文档到知识库' },
    ],
    sdkList: [
      { id: 's1', name: 'Python SDK', language: 'Python', version: '2.0.0', downloadUrl: 'https://pypi.org/project/hermes-sdk' },
      { id: 's2', name: 'Java SDK', language: 'Java', version: '1.5.0', downloadUrl: 'https://maven.org/hermes-sdk' },
      { id: 's3', name: 'C++ SDK', language: 'C++', version: '1.2.0', downloadUrl: 'https://github.com/hermes/cpp-sdk' },
    ],
    docList: [
      { id: 'd1', name: '接入指南', type: '接入指南', link: '/docs/hermes/guide' },
      { id: 'd2', name: 'API参考', type: 'API参考', link: '/docs/hermes/api' },
      { id: 'd3', name: '示例代码', type: '示例代码', link: '/docs/hermes/examples' },
      { id: 'd4', name: '更新日志', type: '更新日志', link: '/docs/hermes/changelog' },
    ],
  },
  {
    id: '3',
    name: 'Cursor',
    description: 'AI驱动的代码编辑器，通过MCP Server接入知识库',
    integrationType: 'MCP',
    status: 'available',
    features: ['编辑器内直接检索', '代码片段智能推荐', '文档实时引用', '多文件上下文理解'],
    configFields: [
      { key: 'mcpServerUrl', label: 'MCP Server地址', type: 'text', required: true },
      { key: 'apiKey', label: 'API密钥', type: 'password', required: true },
    ],
    apiList: [
      { id: 'a1', name: 'MCP握手', method: 'POST', path: '/mcp/handshake', description: '建立MCP连接' },
      { id: 'a2', name: '工具调用', method: 'POST', path: '/mcp/tools/call', description: '调用MCP工具' },
      { id: 'a3', name: '知识库查询', method: 'GET', path: '/mcp/knowledge/search', description: '查询知识库内容' },
    ],
    sdkList: [
      { id: 's1', name: 'TypeScript SDK', language: 'TypeScript', version: '0.9.0', downloadUrl: 'https://npmjs.com/package/cursor-mcp-sdk' },
      { id: 's2', name: 'Go SDK', language: 'Go', version: '0.8.0', downloadUrl: 'https://pkg.go.dev/cursor-mcp-sdk' },
    ],
    docList: [
      { id: 'd1', name: '接入指南', type: '接入指南', link: '/docs/cursor/guide' },
      { id: 'd2', name: '最佳实践', type: '最佳实践', link: '/docs/cursor/best-practices' },
    ],
  },
  {
    id: '4',
    name: 'Trae',
    description: '新一代AI编程IDE，支持海思芯片全栈开发',
    integrationType: 'MCP',
    status: 'coming-soon',
    features: ['全栈开发支持', '硬件仿真集成', '可视化调试', '团队协作'],
    configFields: [
      { key: 'apiKey', label: 'API Key', type: 'password', required: true },
    ],
    apiList: [
      { id: 'a1', name: '项目创建', method: 'POST', path: '/api/v1/projects', description: '创建新项目' },
    ],
    sdkList: [
      { id: 's1', name: 'Python SDK', language: 'Python', version: '0.5.0-beta', downloadUrl: 'https://pypi.org/project/trae-sdk' },
    ],
    docList: [
      { id: 'd1', name: '接入指南', type: '接入指南', link: '/docs/trae/guide' },
      { id: 'd2', name: '更新日志', type: '更新日志', link: '/docs/trae/changelog' },
    ],
  },
]
