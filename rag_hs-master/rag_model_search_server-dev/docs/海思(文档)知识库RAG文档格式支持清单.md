# 海思知识库 RAG 支持文档格式清单

> 本文档基于 `server/knowledge_base/utils.py` 中 `LOADER_DICT`（最终生效的第二个定义，第 142-200 行）与 `CODE_DICT`（第 205-229 行）梳理而成，列出 RAG 知识库当前支持的文件扩展名及对应的加载器。

## 一、总体说明

- **扩展名校验入口**：`KnowledgeFile.__init__` 在加载文件前会校验 `self.ext in SUPPORTED_EXTS`，不在清单内的格式会抛出 `暂未支持的文件格式`。
- **加载器选择规则**：`get_LoaderClass(file_extension)` 遍历 `LOADER_DICT`，返回**第一个**包含该扩展名的加载器；因此同一扩展名若在多个加载器中重复出现，仅前者生效。
- **降级策略**：若 `document_loaders` 模块找不到对应自定义加载器，会回退到 `langchain.document_loaders.UnstructuredFileLoader`。
- **OCR 能力**：PDF、Word、PPT、图片走 `VLLMOCR*` 系列加载器，依赖 VLLM OCR 服务；Word 的 `.doc`/`.docx` 可通过 LibreOffice 转 PDF 再解析。

## 二、支持的文件格式总览

### 1. 办公文档与电子书

| 扩展名 | 说明 | 实际生效加载器 |
| --- | --- | --- |
| `.pdf` | PDF 文档 | `VLLMOCRPDFLoader` |
| `.doc`, `.docx` | Word 文档（`.doc` 经 LibreOffice 转 PDF） | `VLLMOCRDocLoader` |
| `.ppt`, `.pptx` | PowerPoint 演示文稿 | `VLLMOCRPPTLoader` |
| `.odt` | OpenDocument 文本 | `UnstructuredFileLoader`（重复项优先匹配） |
| `.epub` | EPUB 电子书 | `UnstructuredFileLoader`（重复项优先匹配） |
| `.enex` | Evernote 导出文件 | `EverNoteLoader` |

### 2. 网页与标记类

| 扩展名 | 说明 | 实际生效加载器 |
| --- | --- | --- |
| `.html`, `.htm` | HTML 网页 | `UnstructuredHTMLLoader` |
| `.mhtml` | MIME HTML 归档 | `MHTMLLoader` |
| `.md` | Markdown 文档 | `UnstructuredMarkdownLoader` |
| `.rst` | reStructuredText | `UnstructuredFileLoader`（重复项优先匹配） |
| `.xml` | XML 文档 | `UnstructuredFileLoader`（重复项优先匹配） |
| `.tex` | LaTeX 文档 | `GenericCodeLoader`（按代码处理） |

### 3. 纯文本与配置类

| 扩展名 | 说明 | 实际生效加载器 |
| --- | --- | --- |
| `.txt` | 纯文本 | `UnstructuredFileLoader` |
| `.rtf` | Rich Text 格式 | `UnstructuredFileLoader`（重复项优先匹配） |
| `.tsv` | Tab 分隔值 | `UnstructuredFileLoader`（重复项优先匹配） |
| `.toml` | TOML 配置 | `TomlLoader` |
| `.srt` | SubRip 字幕 | `SRTLoader` |

### 4. 结构化数据

| 扩展名 | 说明 | 实际生效加载器 |
| --- | --- | --- |
| `.csv` | 逗号分隔值 | `CSVLoader` |
| `.xlsx`, `.xls` | Excel 表格 | `ExcelChineseLoader` |
| `.json` | JSON 文件 | `JSONLoader` |
| `.jsonl` | JSON Lines | `JSONLinesLoader` |
| `.ipynb` | Jupyter Notebook | `NotebookLoader` |

### 5. 邮件

| 扩展名 | 说明 | 实际生效加载器 |
| --- | --- | --- |
| `.eml` | 邮件（MIME） | `UnstructuredFileLoader`（重复项优先匹配） |
| `.msg` | Outlook 邮件 | `UnstructuredFileLoader`（重复项优先匹配） |

### 6. 图片（OCR 识别）

| 扩展名 | 说明 | 实际生效加载器 |
| --- | --- | --- |
| `.png` | PNG 图片 | `VLLMOCRLoader` |
| `.jpg`, `.jpeg` | JPEG 图片 | `VLLMOCRLoader` |
| `.bmp` | BMP 位图 | `VLLMOCRLoader` |

### 7. 源代码（`GenericCodeLoader`）

> 代码类文件按语言走 `GenericCodeLoader`，并支持通过 `RecursiveCharacterTextSplitter.from_language` 进行语言感知切分（见 `CODE_DICT` / `CODE_TYPE_DICT`）。`.py` 扩展名因 `PythonLoader` 排在字典前部，会优先走 `PythonLoader`。

| 语言 | 扩展名 |
| --- | --- |
| C | `.c`, `.h` |
| C++ | `.cpp`, `.hpp`, `.cc`, `.hh`, `.cxx`, `.hxx` |
| C# | `.cs` |
| COBOL | `.cob`, `.cpy` |
| Go | `.go` |
| Java | `.java` |
| JavaScript | `.js`, `.mjs` |
| Kotlin | `.kt` |
| Lua | `.lua` |
| Perl | `.pl`, `.pm` |
| PHP | `.php` |
| Protocol Buffers | `.proto` |
| Python | `.py`（实际生效加载器为 `PythonLoader`） |
| Ruby | `.rb` |
| Rust | `.rs` |
| Scala | `.scala` |
| Solidity | `.sol` |
| Swift | `.swift` |
| TypeScript | `.ts`, `.tsx` |
| LaTeX | `.tex` |
| reStructuredText | `.rst`（`GenericCodeLoader` 中也声明，但实际被 `UnstructuredFileLoader` 先匹配） |
| Markdown | `.md`（仅声明在 `CODE_DICT` 中用于切分，不进入 `GenericCodeLoader`） |

## 三、加载器与扩展名映射表（含被覆盖的重复声明）

> 下表按 `LOADER_DICT` 中加载器的出现顺序列出。**生效加载器**列标注了该扩展名实际命中的加载器；若与所在行加载器不同，说明该扩展名被靠前的加载器优先匹配。

| 加载器 | 声明的扩展名 | 备注 |
| --- | --- | --- |
| `UnstructuredHTMLLoader` | `.html`, `.htm` | 生效 |
| `MHTMLLoader` | `.mhtml` | 生效 |
| `UnstructuredMarkdownLoader` | `.md` | 生效 |
| `JSONLoader` | `.json` | 生效；默认 `jq_schema="."` |
| `JSONLinesLoader` | `.jsonl` | 生效；默认 `jq_schema="."` |
| `CSVLoader` | `.csv` | 生效；自动用 `chardet` 识别编码 |
| `VLLMOCRPDFLoader` | `.pdf` | 生效，依赖 OCR 服务 |
| `VLLMOCRPPTLoader` | `.ppt`, `.pptx` | 生效，依赖 OCR 服务 |
| `VLLMOCRLoader` | `.png`, `.jpg`, `.jpeg`, `.bmp` | 生效，依赖 OCR 服务 |
| `UnstructuredFileLoader` | `.eml`, `.msg`, `.rst`, `.rtf`, `.xml`, `.epub`, `.odt`, `.tsv`, `.txt` | 这些扩展名的**实际生效加载器** |
| `UnstructuredEmailLoader` | `.eml`, `.msg` | 被上一行 `UnstructuredFileLoader` 先匹配，不生效 |
| `UnstructuredEPubLoader` | `.epub` | 被先匹配，不生效 |
| `ExcelChineseLoader` | `.xlsx`, `.xls` | 生效；`.xlsd` 已被删除（见注释） |
| `NotebookLoader` | `.ipynb` | 生效 |
| `UnstructuredODTLoader` | `.odt` | 被先匹配，不生效 |
| `PythonLoader` | `.py` | 生效（优先于 `GenericCodeLoader`） |
| `UnstructuredRSTLoader` | `.rst` | 被先匹配，不生效 |
| `UnstructuredRTFLoader` | `.rtf` | 被先匹配，不生效 |
| `SRTLoader` | `.srt` | 生效 |
| `TomlLoader` | `.toml` | 生效 |
| `UnstructuredTSVLoader` | `.tsv` | 被先匹配，不生效 |
| `VLLMOCRDocLoader` | `.docx`, `.doc` | 生效；`.doc` 可经 LibreOffice 转 PDF |
| `UnstructuredXMLLoader` | `.xml` | 被先匹配，不生效 |
| `EverNoteLoader` | `.enex` | 生效 |
| `GenericCodeLoader` | 见“源代码”章节 | `.py` 被 `PythonLoader` 先匹配；`.rst` 被先匹配 |

## 四、已注释/移除的格式

以下格式在代码中以注释形式存在，**当前未启用**：

- `FilteredCSVLoader` → `.csv`（自定义分割 CSV，未启用）
- `UnstructuredExcelLoader` → `.xlsx`, `.xls`, `.xlsd`（已改用 `ExcelChineseLoader`，且删除 `.xlsd`）
- `RapidOCRDocLoader` / `UnstructuredWordDocumentLoader` → `.docx`, `.doc`（已改用 `VLLMOCRDocLoader`）
- `RapidOCRLoader` → `.png`, `.jpg`, `.jpeg`, `.bmp`（第一个 `LOADER_DICT` 中声明，已被第二个定义覆盖，改用 `VLLMOCRLoader`）
- `UnstructuredPowerPointLoader` → `.ppt`, `.pptx`（已改用 `VLLMOCRPPTLoader`）
- `GenericCodeLoader` 中 `.html`、`.md` 因 “No parser available for markdown” 被注释

## 五、扩展名总数

- `LOADER_DICT` 去重后实际支持的扩展名约 **50 个**（含源代码、文档、表格、图片、邮件等）。
- `CODE_DICT` 中声明的代码扩展名约 **31 个**，用于语言感知切分；其中 `.md`、`.html` 不在 `GenericCodeLoader` 的 `LOADER_DICT` 声明中。
