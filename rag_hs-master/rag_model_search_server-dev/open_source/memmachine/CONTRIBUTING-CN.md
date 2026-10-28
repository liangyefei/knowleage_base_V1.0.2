
# 欢迎来到 MemMachine 项目

感谢你有兴趣为 MemMachine 做贡献！我们是一个社区驱动的开源项目，欢迎
所有人参与贡献。无论你是刚入门的开发者还是经验丰富的专家，你的帮助都
无比宝贵。

本指南将带你了解如何入门并完成你的首次贡献。

## 1. 开始之前

在你开始贡献之前，请花一点时间阅读以下重要文档：

- [**行为准则**](CODE_OF_CONDUCT.md)：我们社区的价值观，以及对所有贡献者
  的期望。
- [**治理规范**](GOVERNANCE.md)：我们项目管理所依据的规则与原则。

## 2. 如何贡献

我们的贡献主要分为两个领域：核心 Python 源代码和项目文档。为了让你的
贡献流程尽可能顺畅，我们为每个领域分别编写了专门的指南。

- **核心代码贡献**：如果你想贡献代码、修复 Bug 或提出新功能，请阅读我们的
  [**核心贡献指南**](CONTRIBUTING-CORE.md)。
- **文档贡献**：如果你想修改错别字、修复失效链接或添加新的指南，请阅读
  我们的 [**文档贡献指南**](CONTRIBUTING-DOCS.md)。

### 3. 通用贡献流程

所有贡献，无论类型如何，都应遵循以下通用流程：

1. **Fork 仓库**：在 GitHub 上将项目 Fork 到你自己的账号。
2. **克隆你的 Fork**：将你 Fork 的仓库克隆到本地机器：
   ```bash
   git clone https://github.com/YOUR_USERNAME/MemMachine.git
   cd MemMachine
   ```
3. **创建分支**：为你的修改创建一个新分支：
   ```bash
   git checkout -b feat/your-feature-name
   ```
4. **进行修改**：按照相关指南（核心代码或文档）中的说明进行操作。
4. **签署你的提交**：所有提交必须使用 `-sS` 标志进行签名。这能确保所有
   代码具备可验证的保管链。如需了解如何创建用于签名的 GPG 密钥，请参阅
   [Microsoft Commit Signing](https://github.com/microsoft/vscode/wiki/Commit-Signing)
   Wiki 页面，获取分步说明。
5. **推送并创建 Pull Request**：将你的修改推送到你 Fork 的仓库，并向我们的
   `main` 分支创建 Pull Request。

我们期待你的贡献！
