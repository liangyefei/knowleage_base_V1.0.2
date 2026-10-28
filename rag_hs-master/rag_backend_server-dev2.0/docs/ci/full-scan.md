# 全量代码扫描操作文档（rag_backend_server）

> 本文档是本仓库对《全量代码扫描操作文档》的适配版。
> 原文档来自一个 **Python 工程**（用 `open_source/` 排除、走 pylint/flake8），
> 本仓库是 **Gradle + Java 多模块**工程，仓库地址、主线分支、排除口径**全部不同**，
> 因此只能沿用其"机制"，不能照抄其命令。关键差异见 [§7](#7-与原文档的差异对照)。

---

## 一、原理（机制不变）

### 1.1 为什么要全量扫描

代码检查平台（基于 Jenkins）默认**按 MR 的变动文件**触发扫描：每次增量 MR 只扫本次改动的文件。
存量问题不会被覆盖——只有某个老文件被再次改动时才会暴露。

因此定期（每月 / 每迭代 / 发版前）需要一次**全量扫描**，把仓库里所有自主维护的代码一次性送检。

### 1.2 触发机制：Jenkins 如何决定扫哪些文件

1. **创建 MR**（源分支 → 目标分支）。
2. **评论触发（关键）**：在 MR 评论区手动回复 `build`（内容**仅** `build` 一词），COO_CI 机器人监听并启动流水线。
   **仅创建 MR 不会自动触发扫描。**
3. **CI 启动**：COO_CI 回复「CI 启动」，附 Jenkins 构建与 CI Deck 报告链接。
4. **计算 MR diff**：Jenkins 拉取源分支与目标分支的**差异文件列表**（等价 `git diff target...src --name-only`）。
5. **执行静态检查**：对 diff 命中的文件按语言分发到对应检查器（Java → sonarqube / codemars 等）。
6. **CI 结束**：COO_CI 回复「CI 结束」，附结果。

**关键点**：
- **触发 = 创建 MR + 评论区回复 `build`，缺一不可。**
- **扫描范围 = MR 的 diff 文件集合，而不是整个分支。** 这就回答了"如何全量扫描"：**只要让一次 MR 的 diff 文件集合 = 你想扫的全部代码即可。**

### 1.3 核心思路：构造"全量 diff"的 MR

MR 的 diff 由「源分支」相对「目标分支」的差异决定：

- 目标分支 == 源分支 → diff 为空，扫不到任何文件。
- 目标分支**为空（0 文件）** → diff = 源分支的全部文件。
- 目标分支是某个旧基线 → diff = 自旧基线以来的增量。

所以构造"全量 diff"的方法就是：**目标分支保持空，源分支放入待扫的全量代码。**

| 分支 | 作用 | 内容 |
| --- | --- | --- |
| `dev-scan/dev-scan-base` | MR **目标**（基线） | **0 文件**，但基于 `main` 创建（保留共同祖先） |
| `dev-scan/dev-scan-src` | MR **源** | 待扫代码（本仓库：659 个 `src/main/java` 文件） |

因为 base 是空的，MR `src → base` 的 diff 就是 src 的全部文件。

### 1.4 为什么必须有专用分支（本项目实测结论）

**必须有。** 我实测了所有"省掉专用分支"的路径，全部失败：

| 尝试的做法 | 实测结果 |
| --- | --- |
| 扫描 MR 直接瞄准 `origin/main` | `main` 与 `dev2.0` **无共同祖先**；Jenkins `git merge` 报 `refusing to merge unrelated histories`（`CH.1010204.453`） |
| 先把 `dev2.0` 合进 `main`，再从 `dev2.0` 发 MR | 合并后 `main` 已含代码，后续 PR 的 `diff` = **0 行**，扫不到任何东西 |
| 目标分支 = 源分支本身 | diff = **0 行** |

根因：diff = 源相对目标的差异。要"全量"，**目标分支就必须为空**。任何非空的正常分支（`main` / `dev2.0`）都会让 diff 退化为增量或直接报错。

### 1.5 为什么 base 必须与 src 有共同祖先（本项目踩坑点）

Jenkins 扫描前会 `git merge` 把源分支合到目标分支来计算 diff。Git 要求两分支**必须有共同祖先**，否则：

```
fatal: refusing to merge unrelated histories
```

错误码 `CH.1010204.453`。

**本仓库的真实情况**（用 `git merge-base --is-ancestor` 实测）：

- `origin/main` 是**独立根提交**（`853d5fd`，历史上只含 `README.md`），与 `origin/dev2.0` **完全没有共同祖先**。
- `origin/dev2.0` 与 `origin/litianguo`、`origin/liuxin` 共享祖先（`4932133`）。

**结论（硬约束）**：

> **base 的谱系必须与该 MR 最终要合入的分支一致。**

本项目流程面向 `main`，所以 base **必须建在 `main` 上**（`git checkout -b <base> origin/main`），
而不是 `dev2.0`。若 base 建在 `dev2.0` 谱系却去合 `main`，**必然报 unrelated histories**（已实测复现）。

**切勿用 `git checkout --orphan` 创建 base**——那是独立历史，与任何分支都无共同祖先。

### 1.6 为什么排除某些内容

不纳入扫描的典型内容：

- **非源码文件**（配置、文档、SQL、构建脚本）：不在 Java 静态检查范围。
- **测试代码**（`src/test/java`、`src/integrationTest/java`）：本仓库口径只扫 `src/main/java`。
- **非本仓库模块的示例代码**（`examples/`）：脚手架示例，非平台业务代码。

### 1.7 分支命名约定

- 使用**独立命名空间** `dev-scan/`，避免与他人的扫描分支冲突。
- `dev-scan-src` / `dev-scan-base` 是语义命名，一眼可识别用途。

> ⚠️ **命名空间陷阱（实测）**：本机存在**本地分支 `litianguo`**。若用个人名做命名空间（`litianguo/dev-scan-base`），
> git 会报 `cannot lock ref ... 'refs/heads/litianguo' exists`（ref 命名空间冲突）。
> 用 `dev-scan/` 可规避此问题。

---

## 二、扫描口径

### 2.1 口径定义

> **只扫 `src/main/java` 下的 `.java` 文件，且只扫 `settings.gradle.kts` 中已声明的模块。**

`settings.gradle.kts` 声明的 15 个模块（嵌套 `commons:*` 已展开为路径）：

| 模块 | `src/main/java` 文件数 |
| --- | --- |
| `knowledge` | 345 |
| `llm` | 124 |
| `system` | 51 |
| `commons/commons-web` | 36 |
| `commons/commons-ai` | 26 |
| `commons/commons-dict` | 26 |
| `commons/commons-errorcode` | 16 |
| `hisilicon-knowledge-mcp-gateway` | 10 |
| `commons/commons-i18n` | 8 |
| `commons/commons-oidc` | 7 |
| `commons/commons-sensitive` | 4 |
| `commons/commons-abac` | 3 |
| `hisilicon-knowledge-platform-service` | 1 |
| `hisilicon-knowledge-core-service` | 1 |
| `hisilicon-knowledge-iam-service` | 1 |
| **合计** | **659** |

> 数值以 `origin/dev2.0` 为准（2026-10-22 实测）。代码变动后该数值会变化，**校验时以脚本实际输出为准**。

### 2.2 自然排除的内容

| 内容 | 数量 | 排除原因 |
| --- | --- | --- |
| `examples/user` | 17 | 不在 `settings.gradle.kts` 模块内（脚手架示例） |
| `*/src/test/java` | 169 | 口径只扫 `src/main/java` |
| `*/src/integrationTest/java` | 14 | 同上 |
| 非 `.java` 文件（`resources`、`gradle`、`docs` 等） | — | 非 Java 静态检查范围 |

**实测确认**：`src/main/java` 下**没有**非 `.java` 文件，无需额外处理。

### 2.3 口径维护

`settings.gradle.kts` **增删模块时**，需同步修改：

1. `scripts/do-full-scan.ps1` 顶部的 `$ScanModules` 数组；
2. 本文档 §2.1 的表格。

> 脚本的自校验会断言"src 中不存在口径外文件"，因此若漏改，脚本会**直接报错**而不是静默扫错范围。

---

## 三、操作步骤

### 3.1 一键脚本（推荐）

```powershell
# 在仓库根目录执行
.\scripts\do-full-scan.ps1
```

参数（一般用默认值即可）：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `-MainBranch` | `dev2.0` | 代码来源分支 |
| `-BaseLineage` | `main` | **base 的谱系，必须与 MR 目标分支一致** |
| `-SrcBranch` | `dev-scan/dev-scan-src` | 源分支名 |
| `-BaseBranch` | `dev-scan/dev-scan-base` | 目标（空基线）分支名 |

脚本会：

1. 在**临时克隆**中构造 base 与 src（**不污染你的工作区**，可放心在有未提交改动时执行）；
2. 自校验（base=0 文件、src 仅含口径内文件、共同祖先非空、diff 数一致、merge 模拟通过）；
3. 校验通过后把两个分支导出到本仓库（**仅本地分支**）；
4. 打印后续手工步骤。

**脚本可重复执行**（幂等）：每次都会把两个扫描分支重建为最新内容。

### 3.2 推送分支

> ⚠️ **前置条件：鉴权**。脚本只创建本地分支，**不推送**。
> 推送需你先配好 GitCode 凭据。若 `ssh -T git@gitcode.com` 报 `Permission denied (publickey)`，
> 说明本机 SSH 公钥未配置（检查 `~/.ssh` 是否有私钥、公钥是否已加到 GitCode 账号）。
> 也可改用 HTTPS 方式（`~/.ssh` 无密钥时 HTTP 端点仍可达）。

```powershell
git push origin dev-scan/dev-scan-src --force-with-lease
git push origin dev-scan/dev-scan-base            # 仅首次需要
```

> 用 `--force-with-lease` 而非 `--force`：src 分支每次都要覆盖为最新内容，但该参数会在远程有新提交时拒绝覆盖，更安全。

### 3.3 在 GitCode 创建 MR

**方式 A：网页创建（推荐）**

1. 打开仓库：<https://gitcode.com/hi_kb_base/rag_backend_server>
2. 「合并请求 / Merge Requests」→「新建合并请求」
3. 源分支 `dev-scan/dev-scan-src`，目标分支 `dev-scan/dev-scan-base`
4. 标题填：`全量代码扫描 YYYY-MM-DD`（日期便于追溯）
5. 检查「变动文件 / Files changed」数量应**约等于 §2.1 的总数（当前 659）**
   - 若**远小于**该值 → base 被污染（非空），按 §5 重置 base
   - 若报 `refusing to merge unrelated histories` → base 谱系不对，见 §1.5

**方式 B：push 时自动创建 MR（GitLab 风格 push options，GitCode 支持不完整，失败则回退方式 A）**

```powershell
git push origin dev-scan/dev-scan-src --force-with-lease `
  -o merge_request.create `
  -o merge_request.target=dev-scan/dev-scan-base `
  -o merge_request.title="全量代码扫描"
```

### 3.4 触发扫描（关键一步）

> ⚠️ **仅创建 MR 不会触发扫描。** 必须在 **MR 评论区回复 `build`**（内容**仅** `build` 一词，不要有多余字符或空行）。
> COO_CI 机器人收到后才会启动 Jenkins。回复后留意 COO_CI 的「CI 启动」评论确认已触发。

### 3.5 查看报告

- COO_CI 回复「CI 结束」后，点击评论里的「Jenkins 构建」/「CI Deck 报告」链接查看明细。
- 结束语形如：`Codearts Check:执行 SUCCESS,质量 未通过` / `平台测试:未执行`。

### 3.6 收尾

> ⚠️ **不要点「合并」。** MR 仅用于触发扫描，**保持 base 为空基线**，下次扫描才能再次生成全量 diff。
> 扫描报告归档后**关闭 MR 即可**。

---

## 四、验证清单

脚本已自动校验以下各项；手工复核可用右侧命令。

| 检查项 | 期望值 | 命令 |
| --- | --- | --- |
| base 文件数 | 0 | `git ls-tree -r --name-only dev-scan/dev-scan-base \| Measure-Object -Line` |
| base 与 main 共同祖先 | 非空（输出 commit hash） | `git merge-base dev-scan/dev-scan-base origin/main` |
| src 文件数 | 659（随代码变动） | `git ls-tree -r --name-only dev-scan/dev-scan-src \| Measure-Object -Line` |
| src 无非 java 文件 | 无输出 | `git ls-tree -r --name-only dev-scan/dev-scan-src \| Select-String -NotMatch '\.java$'` |
| src 无口径外文件 | 无输出 | `git ls-tree -r --name-only dev-scan/dev-scan-src \| Select-String '^examples/\|/src/test/\|/src/integrationTest/'` |
| src 与 base 共同祖先 | 非空（= base 自身） | `git merge-base dev-scan/dev-scan-src dev-scan/dev-scan-base` |
| MR diff 数 | 等于 src 文件数 | `git diff --name-only dev-scan/dev-scan-base...dev-scan/dev-scan-src \| Measure-Object -Line` |
| merge 可执行 | exit 0 | `git merge --no-commit --no-ff dev-scan/dev-scan-src; git merge --abort` |
| MR 变动文件数 | 约等于 src 文件数 | GitCode MR 页面「Files changed」 |

---

## 五、重置被污染的 base

若 base 被误合并（文件数非 0），或需要换谱系，**重新跑一次脚本即可**——脚本用 `-B` 重建分支，天然幂等：

```powershell
.\scripts\do-full-scan.ps1
```

重建后若远程 base 也已被污染，需强推覆盖：

```powershell
git push origin dev-scan/dev-scan-base --force-with-lease
```

---

## 六、注意事项

1. **不要真的合并 MR 到 base**：MR 仅用于触发 Jenkins 扫描，扫描完成后关闭即可。base 必须始终为空基线。
2. **base 谱系必须与 MR 目标一致（关键）**：本项目 `main` 与 `dev2.0` **无共同祖先**，
   base 建错谱系会报 `refusing to merge unrelated histories`（`CH.1010204.453`）。**切勿用 `--orphan`。**
3. **命名空间用 `dev-scan/`**：本机已有本地分支 `litianguo`，用它做命名空间会导致 ref 冲突（见 §1.7）。
4. **每次都要 force push src**：src 需每次刷新为最新主线内容；用 `--force-with-lease` 更安全。
5. **`--cached` 相关**：脚本内 `git rm -rf --cached .` 只作用于**临时克隆的 base 分支**，
   **绝不可在主线上执行**；临时克隆用完即删，不影响你的工作区与本地文件。
6. **口径变更要同步两处**：`settings.gradle.kts` 增删模块时，同步改脚本 `$ScanModules` 与本文档 §2.1。
7. **脚本自校验失败即中止**：任一断言不通过，脚本以非零退出且**不会**导出分支——不要绕过校验强推。

---

## 七、与原文档的差异对照

| 项 | 原文档（Python 工程） | 本仓库（Gradle 多模块） |
| --- | --- | --- |
| 仓库 | `rag_model_search_server` | `rag_backend_server` |
| 主线分支 | `dev` | **`dev2.0`**（`origin/dev` 实测只有 576 文件、**0 个 java**，扫它等于扫空） |
| 命名空间 | `hetaoist` | **`dev-scan`**（`litianguo` 会 ref 冲突） |
| 排除项 | `open_source/`（1323 文件，第三方开源） | **本仓库无 `open_source` 目录**；改为"非 settings 模块 + 非 `src/main/java`" |
| base 谱系 | 基于 `dev` | 基于 **`main`**（因 MR 目标为 `main`，且 `main` 与 `dev2.0` 无共同祖先） |
| 扫描语言/检查器 | Python（pylint / flake8） | Java（sonarqube / codemars 等，由平台分发） |
| 文件数 | 约 600+ | **659**（`src/main/java`，仅 settings 模块） |
| 一键脚本 | `do-full-scan.ps1`（硬编码排除 `open_source`） | `scripts/do-full-scan.ps1`（参数化 + 自校验 + 临时克隆隔离） |

**相同的部分**（平台级约定，无需适配）：触发方式（MR + 评论 `build`）、COO_CI 机器人、base 必须为空且与 src 有共同祖先、检查器规则集。

---

## 八、常见问题

- **回复 `build` 后 CI 没启动？** 确认评论内容**仅为** `build`（无多余字符/空行）；确认 MR 状态为 Open；若仍无响应，联系平台管理员确认机器人服务。注意：**仅创建 MR 不会触发**。
- **MR diff 文件数远小于预期？** 大概率 base 被合并污染（非空），按 §5 重建 base 后重发 MR。
- **报 `fatal: refusing to merge unrelated histories`（CH.1010204.453）？** base 与目标分支无共同祖先。检查 `-BaseLineage` 是否与该 MR 目标分支一致（本项目应为 `main`）；确认 base **不是**用 `--orphan` 创建的。
- **`cannot lock ref ... 'refs/heads/litianguo' exists`？** 命名空间与本地分支冲突。改用 `dev-scan/` 命名空间（脚本默认已是）。
- **git push 报 `Permission denied (publickey)`？** 本机 SSH 未配置。配置 SSH 公钥到 GitCode 账号，或改用 HTTPS 凭据。
- **脚本报"导出分支失败"？** 检查是否有权限写本地分支；或 `dev-scan/*` 已存在且被其他操作占用（脚本会用 force 覆盖，正常情况不会失败）。
- **扫描报告为空？** 确认检查器规则集已对目标仓库启用；确认 diff 文件语言与检查器匹配（Java → sonarqube 等）。
- **`src 中口径外文件` 断言失败？** `settings.gradle.kts` 已增删模块但脚本 `$ScanModules` 未同步，见 §2.3。

---

## 九、安全提示

- 脚本的 `git rm -rf --cached .` **只在临时克隆的 base 分支**上执行，用完即删，**不会**影响你的工作区、本地文件或任何主线分支。
- `--force-with-lease` 会在远程有新提交时拒绝覆盖；若确需覆盖他人改动，先与对方确认。
- MR 仅用于触发扫描，**不要点「合并」**；报告归档后关闭 MR 即可。
- 脚本**不做任何推送**，网络写操作完全由人工执行。