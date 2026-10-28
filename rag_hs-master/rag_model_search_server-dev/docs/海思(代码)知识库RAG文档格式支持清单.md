# 海思代码知识库 RAG 文档格式支持清单

> 本清单梳理自 `codebase-memory-mcp` (CBM) 项目所支持的代码与配置文件格式（CBM 源码已合并至 `dual-mcp` 镜像，构建源码在独立仓库 `gitcode.com/hi_kb_base/rag_mcp_server`）。权威来源：
> - `internal/cbm/cbm.h` 中的 `CBMLanguage` 枚举
> - `README.md` 的 "Language Support" 章节
> - `docs/CONFIGURATION.md` 的扩展名映射机制
>
> 每个语言在 `internal/cbm/` 目录下均有对应的 `grammar_<lang>.c` 文件（vendored tree-sitter 文法，编译进单一二进制）。

## 一、总览

| 指标 | 数值 | 说明 |
|---|---|---|
| 声明支持语言数 | 158 | README 官方徽章 |
| 枚举定义条目数 | 163 | `CBM_LANG_*` 枚举（部分语言含多种方言/变体） |
| Hybrid LSP 语言数 | 10 | 带语义类型解析的增强语言 |
| 文法实现方式 | vendored tree-sitter | 全部静态编译进二进制，无外部依赖 |
| 扩展名自定义 | 支持 | 通过 JSON 配置文件追加映射 |

**Hybrid LSP（带语义类型解析）的 10 种语言**：Python、TypeScript / JavaScript / JSX / TSX、PHP、C#、Go、C / C++、Java、Kotlin、Rust、Perl。

## 二、按类别清单

### 2.1 主流编程语言（含 Hybrid LSP）

| 语言 | 别名 | 典型扩展名 |
|---|---|---|
| Go | `go` | `.go` |
| Python | `python` | `.py` |
| JavaScript | `javascript` | `.js` `.mjs` `.cjs` |
| TypeScript | `typescript` | `.ts` |
| TSX | `tsx` | `.tsx` |
| Rust | `rust` | `.rs` |
| Java | `java` | `.java` |
| C++ | `c++`、`cpp` | `.cpp` `.cc` `.cxx` `.hpp` |
| C# | `c#`、`csharp` | `.cs` |
| PHP | `php` | `.php` `.phtml` |
| C | `c` | `.c` `.h` |
| Perl | `perl` | `.pl` `.pm` |

### 2.2 其他通用编程语言

Lua、Scala、Kotlin、Ruby、Bash、Zsh、Zig、Elixir、Haskell、OCaml、Objective-C、Swift、Dart、Groovy、Erlang、R、Clojure、F#、Julia、Vim script、Nix、Common Lisp、Elm、Fortran、CUDA、COBOL、Verilog、Emacs Lisp、D、Nim、Scheme、Fennel、Fish、Awk、Tcl、Ada、Agda、Racket、Odin、ReScript、PureScript、Nickel、Crystal、Teal、Hare、Pony、Luau、Janet、Sway、Pascal、PowerShell、Mojo。

### 2.3 Web / 前端 / 模板

HTML、CSS、SCSS、Vue、Svelte、Astro、Blade、Liquid、Jinja2、Templ、Go template、GraphQL、Prisma、QML、Hyprlang。

### 2.4 标记 / 配置 / 数据

JSON、JSON5、Jsonnet、YAML、TOML、XML、INI、.env（dotenv）、.properties、SSH config、Kconfig、KDL、RON、Thrift、Cap'n Proto、Protobuf、CSV、PO（gettext）、BibTeX、Beancount、Diff、Regex、JSDoc、Linker Script、GN、Bitbake、Pkl。

### 2.5 基础设施 / IaC

Dockerfile、HCL（别名 `terraform`）、Kubernetes manifest（`CBM_LANG_K8S`，按 `apiVersion:` 检测）、Kustomize（`kustomization.yaml`）、Makefile、CMake、Meson、Just、Starlark、Bicep、Puppet、Devicetree。

### 2.6 着色器 / 硬件描述 / 汇编

GLSL、HLSL、WGSL、VHDL、SystemVerilog、Verilog、Assembly、NASM、ISPC、TableGen。

### 2.7 科学 / 数学 / 金融

MATLAB、Lean、FORM、MAGMA、Wolfram、Solidity、Typst、R、TLA+、Move、Cairo、FunC、Squirrel、Slang、Smithy、WIT、LLVM IR。

### 2.8 文档

Markdown、reStructuredText (RST)、Mermaid。

### 2.9 Salesforce / InterSystems 生态

Apex、SOQL、SOSL、CFML（tag 方言 `.cfm`）、CFScript（`.cfc`）、ObjectScript UDL（`.cls`）、ObjectScript routine（`.mac` / `.int` / `.rtn` / `.inc`）、ObjectScript Studio Export XML。

### 2.10 Git 元数据

`.gitattributes`、`.gitignore`。

### 2.11 Go 生态附属

Go module（`go.mod`）、Pine（TradingView 指标 / 策略）。

## 三、自定义扩展名映射

通过两个可选 JSON 文件，可将额外扩展名映射到内置语言（详见 `docs/CONFIGURATION.md`）：

| 作用范围 | 文件路径 | 优先级 |
|---|---|---|
| 全局 | `$XDG_CONFIG_HOME/codebase-memory-mcp/config.json`（回退 `~/.config/codebase-memory-mcp/config.json`） | 低 |
| 项目级 | `<repo_root>/.codebase-memory.json` | 高（覆盖全局同名键） |

**配置格式**：

```json
{
  "extra_extensions": {
    ".blade.php": "php",
    ".mjs": "javascript",
    ".twig": "html"
  }
}
```

**规则**：
- 扩展名 key 必须以 `.` 开头。
- 语言名大小写不敏感。
- 未知语言名或不以 `.` 开头的扩展名会被跳过并记日志。
- 项目级配置覆盖全局配置中的同名键。

**接受的语言名（含别名）**：

`bash`（`sh`）、`c`、`c++`（`cpp`）、`c#`（`csharp`）、`clojure`、`cmake`、`cobol`、`common lisp`（`commonlisp`、`lisp`）、`css`、`cuda`、`dart`、`dockerfile`、`elixir`、`elm`、`emacs lisp`（`emacslisp`）、`erlang`、`f#`（`fsharp`）、`form`、`fortran`、`glsl`、`go`、`graphql`、`groovy`、`haskell`、`hcl`（`terraform`）、`html`、`ini`、`java`、`javascript`、`json`、`julia`、`kotlin`、`lean`、`lua`、`magma`、`makefile`、`markdown`、`matlab`、`meson`、`nix`、`objective-c`（`objc`）、`ocaml`、`perl`、`php`、`protobuf`、`python`、`r`、`ruby`、`rust`、`scala`、`scss`、`sql`、`svelte`、`swift`、`toml`、`tsx`、`typescript`、`verilog`、`vimscript`、`vue`、`wolfram`、`xml`、`yaml`、`zig`。

## 四、解析质量分级（README 基准测试）

基准测试来自 64 个真实开源仓库（节点数 78–49K）。

| 评级 | 覆盖度 | 代表语言 |
|---|---|---|
| Excellent | ≥ 90% | Lua, Kotlin, C++, Perl, Objective-C, Groovy, C, Bash, Zig, Swift, CSS, YAML, TOML, HTML, SCSS, HCL, Dockerfile |
| Good | 75–89% | Python, TypeScript, TSX, Go, Rust, Java, R, Dart, JavaScript, Erlang, Elixir, Scala, Ruby, PHP, C#, SQL |
| Functional | < 75% | OCaml, Haskell |

其余约 120+ 种语言（Ada、Agda、Apex、Assembly、Astro、AWK、Beancount、BibTeX、Bicep、Bitbake、Blade、Cairo、Cap'n Proto、Clojure、CMake、COBOL、Common Lisp、Crystal、CSV、CUDA、D、Devicetree、Diff、.env、Elm、Emacs Lisp、F#、Fennel、Fish、FORM、Fortran、FunC、GDScript、.gitattributes、.gitignore、Gleam、GLSL、GN、Go module、Go template、GraphQL、Hare、HLSL、Hyprlang、INI、ISPC、Janet、Jinja2、JSDoc、JSON、JSON5、Jsonnet、Julia、Just、Kconfig、KDL、Lean 4、Linker Script、Liquid、LLVM IR、Luau、Magma、Makefile、Markdown、MATLAB、Mermaid、Meson、Move、Nickel、Nim、Nix、Odin、Pascal、Pkl、PO、Pony、PowerShell、Prisma、.properties、Protobuf、Puppet、PureScript、Racket、Regex、requirements.txt、ReScript、RON、reStructuredText、Scheme、Slang、Smali、Smithy、Solidity、SOQL、SOSL、Squirrel、SSH config、Starlark、Svelte、Sway、SystemVerilog、TableGen、Tcl、Teal、Templ、Thrift、TLA+、Typst、Verilog、VHDL、Vim script、Vue、WGSL、WIT、Wolfram、XML、Zsh）未纳入基准测试，但已支持语法解析。

## 五、架构与索引能力

- **解析引擎**：Pure C 实现，零外部依赖；Linux 内核（28M LOC、75K 文件）3 分钟内全量索引。
- **两层架构**：
  1. **Tree-sitter pass** — 快速语法解析，158 种语言全覆盖，提取定义、调用、import。
  2. **Hybrid LSP pass** — 类型感知，在 tree-sitter 之上按语言运行，用 import 图 + 文件内 / 跨文件定义注册表精化 `CALLS`、`USAGE`、`RESOLVED_CALLS` 边。未实现 Hybrid LSP 的语言回退到文本解析，保证总有可用结果。
- **抽取产物**：函数 / 类 / 字段、调用链、import、HTTP 路由、跨服务链接、pub/sub channel、环境变量访问、类型引用等，持久化为知识图谱（SQLite，WAL 模式）。
- **索引对象**：除源码外，Dockerfile / Kubernetes manifest / Kustomize overlay 也作为图节点纳入，带 `Resource` / `Module` 节点与 `IMPORTS` 边。

## 六、版本与来源

- 项目：`codebase-memory-mcp`（DeusData）
- License：MIT
- 平台：macOS / Linux / Windows（单一静态二进制）
- 研究预印本：arXiv:2603.27277《Codebase-Memory: Tree-Sitter-Based Knowledge Graphs for LLM Code Exploration via MCP》
