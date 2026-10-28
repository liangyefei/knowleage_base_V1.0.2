# MemMachine

<div align="center">

![MemMachine: Long Term Memory for AI Agents](https://raw.githubusercontent.com/MemMachine/MemMachine/main/assets/img/MemMachine_Hero_Banner.png)

**面向 AI 智能体的开源记忆层。**

*不要再构建无状态智能体了。只需 5 行代码，即可为你的 AI 提供持久化记忆。*

<br/>

![GitHub Release Version](https://img.shields.io/github/v/release/memmachine/memmachine?display_name=release)
![GitHub License](https://img.shields.io/github/license/MemMachine/MemMachine)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/MemMachine/MemMachine)
![Discord](https://img.shields.io/discord/1412878659479666810)
<br/>
![Docker Pulls](https://img.shields.io/docker/pulls/memmachine/memmachine)
![GitHub Downloads](https://img.shields.io/github/downloads/memmachine/memmachine/total?label=GitHub%20Downloads)
<br/>
![PyPI Downloads - memmachine-client](https://img.shields.io/pypi/dm/memmachine-client?label=PyPI%20Downloads%3A%20memmachine-client)
![PyPI Downloads - memmachine-server](https://img.shields.io/pypi/dm/memmachine-server?label=PyPI%20Downloads%3A%20memmachine-server)

</div>

## 什么是 MemMachine？

MemMachine 是一个面向 AI 智能体和 LLM 驱动应用的开源**长期记忆层**。它使你的 AI 能够从过往会话中**学习、存储和回忆**信息——将无状态的聊天机器人转变为个性化、具备上下文感知能力的助手。

### 核心能力

- **情景记忆（Episodic Memory）**：基于图谱的对话上下文，可跨会话持久化保存
- **画像记忆（Profile Memory）**：长期用户事实和偏好，存储于 SQL 中
- **工作记忆（Working Memory）**：当前会话的短期上下文
- **智能体记忆持久化**：即使经历重启、会话切换乃至模型更换，记忆依然得以保留

## 快速开始

5 分钟内即可上手运行：

> **前提条件：** 此代码需要运行中的 MemMachine Server。
> [在本地启动一个服务器](https://docs.memmachine.ai/getting_started/quickstart) 或在 [MemMachine Platform](https://console.memmachine.ai/) 上创建免费账号。

```bash
pip install memmachine-client
```

```python
from memmachine_client import import MemMachineClient

# Initialize the client
client = MemMachineClient(base_url="http://localhost:8080")

# Get or create a project
project = client.get_or_create_project(org_id="my_org", project_id="my_project")

# Create a memory instance for a user session
memory = project.memory(
    group_id="default",
    agent_id="travel_agent",
    user_id="alice",
    session_id="session_001"
)

# Add a memory
memory.add("I prefer aisle seats on flights", metadata={"category": "travel"})
# => [AddMemoryResult(uid='...')]

# Search memories
results = memory.search("What are my flight preferences?")
print(results.content.episodic_memory.long_term_memory.episodes[0].content)
# => "I prefer aisle seats on flights"
```

如需完整的安装方式（Docker、自托管、云），请访问
[快速开始指南](https://docs.memmachine.ai/getting_started/quickstart)。

## 集成

MemMachine 可与你喜爱的 AI 框架无缝协作：

<div align="center">

| 框架 | 描述 |
|-----------|-------------|
| [**LangChain**](integrations/langchain/) | LangChain 智能体的记忆提供方 |
| [**LangGraph**](integrations/langgraph/) | LangGraph 工作流的有状态记忆 |
| [**CrewAI**](integrations/crewai/) | CrewAI 多智能体系统的持久化记忆 |
| [**LlamaIndex**](integrations/llamaindex/) | LlamaIndex 应用的记忆集成 |
| [**AWS Strands**](integrations/aws_strands_agent_sdk/) | AWS Strands Agent SDK 的记忆 |
| [**n8n**](integrations/n8n/) | 无代码工作流自动化集成 |
| [**Dify**](integrations/dify/) | Dify AI 应用的记忆后端 |
| [**FastGPT**](integrations/fastgpt/) | 与 FastGPT 平台的集成 |

</div>

## MCP Server 支持

MemMachine 内置了原生的 **Model Context Protocol (MCP)** server，可与 Claude Desktop、Cursor 及其他兼容 MCP 的客户端无缝集成：

```bash
# Stdio mode (for Claude Desktop)
memmachine-mcp-stdio

# HTTP mode (for web clients)
memmachine-mcp-http
```

请参阅 [MCP 文档](https://docs.memmachine.ai/integrations/mcp) 了解配置步骤。

## MemMachine 适合谁？

- **开发者**：构建 AI 智能体、助手或自主工作流
- **研究人员**：探索智能体架构和认知模型
- **团队**：需要为其 LLM 应用提供持久化、跨会话记忆

## 核心特性

- **多种记忆类型**：工作记忆（短期）、情景记忆（长期对话）、画像记忆（用户事实）
- **开发者友好的 API**：Python SDK、RESTful API、TypeScript SDK 以及 MCP server 接口
- **灵活的存储**：图数据库（Neo4j）用于情景记忆，SQL 用于画像记忆
- **LLM 无关**：兼容 OpenAI、Anthropic、Bedrock、Ollama 以及任何 LLM 提供方
- **自托管或云**：可在本地运行、在 Docker 中运行，或使用我们的托管服务

更多信息，请参阅 [API 参考指南](https://docs.memmachine.ai/api_reference)。

## 架构

<div align="center">

![MemMachine Architecture](https://raw.githubusercontent.com/MemMachine/MemMachine/main/assets/img/MemMachine_Architecture.png)

</div>

1. **智能体通过 API 层交互**：用户与智能体交互，智能体通过 RESTful API、Python SDK 或 MCP Server 连接到 MemMachine。
2. **MemMachine 管理记忆**：处理交互并将其存储为情景记忆（对话上下文）和画像记忆（长期用户事实）。
3. **数据持久化**：情景记忆存储在图数据库中；画像记忆存储在 SQL 中。

## 用例与示例智能体

MemMachine 灵活的记忆架构可应用于任何领域。浏览我们的[示例](examples/README.md)，了解具备记忆能力的智能体如何运作：

| 智能体 | 描述 |
|-------|-------------|
| **CRM 智能体** | 回顾客户历史和交易阶段，帮助销售团队更快成交 |
| **医疗导航助手** | 记住病史并跟踪治疗进展 |
| **个人理财顾问** | 存储投资组合偏好和风险承受能力，提供个性化洞察 |
| **写作助手** | 学习你的风格指南和术语，产出一致的内容 |

## 基于 MemMachine 构建

你在自己的项目中使用了 MemMachine 吗？我们很乐意展示你的案例！

- 在 [GitHub Discussions → Showcase](https://github.com/MemMachine/MemMachine/discussions/categories/showcase) 分享你的项目
- 在我们的 [Discord #showcase 频道](https://discord.gg/usydANvKqD) 留言

## 不断成长的社区

MemMachine 是一个由构建者和开发者组成的、不断成长的社区。请点击上方的 ⭐ **Star** 按钮帮助我们壮大社区！

<img src="https://starchart.cc/MemMachine/MemMachine.svg?variant=light" alt="MemMachine Star History" height="300"/>

## 文档

- **[主站](https://memmachine.ai)** – 了解 MemMachine
- **[文档与 API 参考](https://docs.memmachine.ai)** – 完整文档
- **[快速开始指南](https://docs.memmachine.ai/getting_started/quickstart)** – 几分钟内上手

## 社区与支持

- **Discord**：加入我们的社区，获取支持、更新和讨论：
    [https://discord.gg/usydANvKqD](https://discord.gg/usydANvKqD)
- **Issue 与功能请求**：使用 GitHub
    [Issues](https://github.com/MemMachine/MemMachine/issues)

## 贡献

欢迎贡献代码！请参阅我们的 [CONTRIBUTING.md](CONTRIBUTING.md) 了解贡献指南。

## 参考文献

```bibtex
@misc{luo2025agentlightningtrainai,
  title={Agent Lightning: Train ANY AI Agents with Reinforcement Learning},
  author={Xufang Luo and Yuge Zhang and Zhiyuan He and Zilong Wang and Siyun Zhao and Dongsheng Li and Luna K. Qiu and Yuqing Yang},
  year={2025},
  eprint={2508.03680},
  archivePrefix={arXiv},
  primaryClass={cs.AI},
  url={https://arxiv.org/abs/2508.03680},
}
```

## 许可证

MemMachine 基于 [Apache 2.0 License](LICENSE) 发布。
