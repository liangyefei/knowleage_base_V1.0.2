# MemMachine 代理指南

本文件帮助自主编程代理在本仓库中高效工作。它汇总了构建/检查/测试命令，
以及 Python 和 TypeScript REST 客户端包的代码风格期望。

## 仓库导航

- `packages/server/`：server Python 包（server、SDK、memory 层）。
- `packages/common/`：common Python 包（共享 REST API）。
- `packages/client/`：client Python 包。
- `packages/ts-client/`：client TypeScript 包。
- `packages/meta/`：meta Python 包（同时包含 server 和 client）。
- `integrations/`、`examples/`、`evaluation/`、`tools/`：示例和辅助工具。
- `STYLE_GUIDE.md`：更高层面的风格说明和工具参考。

## 环境设置

- 推荐：在仓库根目录执行 `uv sync`。
- 包含可选扩展：`uv sync --all-extras`。
- Python 版本：3.12+（见 `pyproject.toml`）。

## 构建、检查和测试命令

### Python（仓库根目录）

- 安装依赖：`uv sync`。
- 检查：`uv run ruff check`（或 `ruff check`）。
- 格式化：`uv run ruff format`（或 `ruff format`）。
- 类型检查：`uv run ty check packages`（或 `ty check packages`）。
- 运行所有测试：`uv run pytest`（或 `pytest`）。
- 运行单个测试文件：
  `uv run pytest packages/server/server_tests/memmachine_server/common/test_utils.py`。
- 运行单个测试函数：
  `uv run pytest packages/server/server_tests/memmachine_server/common/test_utils.py::test_chunk_text`。
- 按关键字运行测试：
  `uv run pytest -k "create_memory"`。
- 仅运行带标记的测试：
  `uv run pytest -m integration` 或 `uv run pytest -m slow`。

### Docker 镜像构建

- 构建/推送容器镜像：`./build-docker.sh`（交互式）或使用 `build-docker.sh` 中
  记录的参数。

### TypeScript REST 客户端（按包）

包含 package.json 的路径：
- `packages/ts-client/`

常用命令（在上述任一目录中执行）：

- 安装依赖：`npm install`。
- 构建：`npm run build`。
- 检查：`npm run lint`（或用 `npm run lint:fix` 修复）。
- 格式化：`npm run format`（或 `npm run format:check`）。
- 测试：`npm run test`。
- 按名称运行单个 Jest 测试：`npm run test -- -t "test name"`。
- 单个 Jest 测试文件：`npm run test -- path/to/file.test.ts`。

## 代码风格指南

### 通用原则

- 可读性优先于技巧；与邻近代码模式保持一致。
- 倾向于职责清晰、可测试的小函数。
- 为公开 API 和不易理解的逻辑保持文档说明。

### Python 格式化和导入

- 格式化由 Ruff 强制执行。提交前运行 `ruff format`。
- 导入由 Ruff 排序（标准库 → 第三方 → 本地）。
- 避免通配符导入；保持导入列表最小化且明确。

### Python 类型和 API

- 处处使用类型注解；优先使用 `list[str]`、`dict[str, int]` 等。
- 仅在不可避免时使用 `typing.Any`；说明原因。
- 结构化输入/输出优先使用 `pydantic` 模型。
- 保持异步边界清晰；避免在异步代码中执行阻塞调用。
- 在异步模块中一致地使用 `async def` + `await`。

### Python 命名约定

- 模块/文件：`snake_case.py`。
- 函数/变量：`snake_case`。
- 类/异常：`PascalCase`。
- 常量：`UPPER_SNAKE_CASE`。
- 测试：`test_*.py`，其中函数为 `test_*`。

### 错误处理和日志

- 抛出带有清晰消息的明确异常。
- 用 `raise ... from err` 保留上下文。
- 仅在 API 边界捕获宽泛异常；记录时附带上下文。
- 避免静默失败；从外部 I/O 层返回有意义的错误。

### 测试期望

- 使用 pytest；优先使用 fixture 完成共享初始化。
- 保持测试确定性；用 mock/fixture 隔离外部服务。
- 使用 `@pytest.mark.slow` 或 `@pytest.mark.integration` 标记慢测试或
  集成测试。

### TypeScript REST 客户端风格

- 通过 ESLint 检查；通过 Prettier 格式化（见 package.json 脚本）。
- 优先使用显式类型而非 `any`；DTO 使用 interface/type。
- 保持异步调用处于 `await` 状态；用带类型的错误路径处理错误。
- 类型/类使用 `PascalCase`，函数/变量使用 `camelCase`。

### 文档（Markdown）

- 行宽在合理时保持在约 80 字符。
- 使用带语言标识的围栏代码块。
- 使用具有描述性的链接文本；避免在正文中使用裸 URL。
- 无序列表使用 `-`，有序列表使用一致的编号。

## 代理注意事项

- 本仓库是以 Python 为主的工作区，附带可选的 TypeScript 客户端。
- 如果修改了受检查的文件，请重新运行 Ruff 以避免 CI 失败。
- 避免重新格式化无关文件；将改动范围限制在你的变更之内。
