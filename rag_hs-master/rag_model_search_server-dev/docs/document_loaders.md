# 文档加载器与注册机制说明

> 范围：`haisi_rag_models_server` 项目的 `document_loaders/` 模块、`server/knowledge_base/utils.py` 的加载器注册与解析逻辑、`configs/` 的配置驱动。
>
> 目标：讲清"一个上传文件从落盘到变成 `Document` 列表"全链路的扩展名映射、类解析、配置来源，便于新增文件类型与排查加载失败。

---

## 1. 目录结构

[document_loaders/__init__.py](file:///d:/Code/haisi/haisi_rag_models_server/document_loaders/__init__.py) 仅显式导出 5 个自定义加载器：

```python
from .mypdfloader import RapidOCRPDFLoader      # .pdf
from .myexcelloader import ExcelChineseLoader   # .xlsx/.xls/.xlsd
from .myimgloader import RapidOCRLoader        # .png/.jpg/.jpeg/.bmp
from .mydocloader import RapidOCRDocLoader      # .docx（实为 RapidOCRPDFLoader 别名）
from .mypptloader import RapidOCRPPTLoader      # .ppt/.pptx
```

其余文件：

| 文件 | 内容 | 是否导出 |
|---|---|---|
| [FilteredCSVloader.py](file:///d:/Code/haisi/haisi_rag_models_server/document_loaders/FilteredCSVloader.py) | `FilteredCSVLoader(CSVLoader)`，按指定列读 CSV | 否（LOADER_DICT 中也被注释） |
| [ocr.py](file:///d:/Code/haisi/haisi_rag_models_server/document_loaders/ocr.py) | `get_ocr()` 工厂，优先 `rapidocr_paddle`(CUDA)，回退 `rapidocr_onnxruntime` | 被 `myimgloader`/`mypptloader` 复用 |
| [mydocloader.py](file:///d:/Code/haisi/haisi_rag_models_server/document_loaders/mydocloader.py) | `RapidOCRPDFLoader`（PDF+Word 版），末尾 `RapidOCRDocLoader = RapidOCRPDFLoader` | 经别名导出 |
| [mypdfloader.py](file:///d:/Code/haisi/haisi_rag_models_server/document_loaders/mypdfloader.py) | `RapidOCRPDFLoader`（PDF 专版，与 mydocloader 几乎重复） | 直接导出 |

---

## 2. 加载器继承关系

所有自定义加载器都继承自 langchain 的 `UnstructuredFileLoader`，通过覆写 `_get_elements()` 注入自定义解析逻辑（OCR / 大模型转 Markdown）；唯一例外是 `FilteredCSVLoader`，它继承 `CSVLoader`。

```
UnstructuredFileLoader (langchain)
├── RapidOCRPDFLoader      # PDF→图片→多模态大模型→带层级标记 Markdown→HierarchyChunker 分块
├── RapidOCRDocLoader      # = RapidOCRPDFLoader 别名（mydocloader 末尾），支持 .docx
├── RapidOCRLoader         # 图片→OCR（paddle/onnxruntime）→text→partition_text
├── RapidOCRPPTLoader      # PPT→逐页图片→RapidOCR→text
└── ExcelChineseLoader    # Excel→openpyxl 解析→大模型增强结构化

CSVLoader (langchain)
└── FilteredCSVLoader      # 按指定列读取 CSV（未注册生效）
```

> `RapidOCRPDFLoader` 的解析管线：`PDFMarkdownExtractor`（PDF→图片→VLM 多模态推理→带 `[L1]~[L5]` 层级标记 + `[Page N]` 页码的 Markdown）→ `HierarchyChunker`（按层级标记分块，L1 单独成块，L2 超标按 L3 拆，依此类推，每块继承父级标题）。Word 文档先用 LibreOffice 转 PDF 再走同一管线。

---

## 3. 注册机制（核心）

注册逻辑集中在 [server/knowledge_base/utils.py](file:///d:/Code/haisi/haisi_rag_models_server/server/knowledge_base/utils.py)。

### 3.1 扩展名映射表 `LOADER_DICT`

[utils.py L142-L174](file:///d:/Code/haisi/haisi_rag_models_server/server/knowledge_base/utils.py#L142-L174)：`{loader类名: [扩展名列表]}` 字典。**注意**：L94-L140 有一份初始定义，L142 重新赋值**覆盖**了它，实际生效的是 L142 这份。

关键映射（生效版）：

| 扩展名 | loader 类名 | 解析来源 |
|---|---|---|
| `.pdf` | `RapidOCRPDFLoader` | 本地 `document_loaders` 包 |
| `.docx` | `RapidOCRDocLoader` | 本地（别名） |
| `.doc` | `UnstructuredWordDocumentLoader` | langchain 官方 |
| `.xlsx/.xls/.xlsd` | `ExcelChineseLoader` | 本地 |
| `.png/.jpg/.jpeg/.bmp` | `RapidOCRLoader` | 本地 |
| `.ppt/.pptx` | `RapidOCRPPTLoader` | 本地（L172 也有 `UnstructuredPowerPointLoader` 同扩展名，但 RapidOCR 在前先匹配） |
| `.txt/.rtf/.xml/.tsv/.eml/.msg/.rst/.epub/.odt` | `UnstructuredFileLoader` | 本地（**见已知问题 §6.1**） |
| `.csv` | `CSVLoader` | langchain 官方（`FilteredCSVLoader` 被注释） |
| `.json/.jsonl` | `JSONLoader` / `JSONLinesLoader` | langchain 官方 |
| `.md` | `UnstructuredMarkdownLoader` | langchain 官方 |
| `.html/.htm` | `UnstructuredHTMLLoader` | langchain 官方 |
| 代码（`.py/.java/.go/...`） | 不在 `LOADER_DICT` | 走 `CODE_DICT` + `make_text_splitter` 的 `Language` 分词 |

[SUPPORTED_EXTS](file:///d:/Code/haisi/haisi_rag_models_server/server/knowledge_base/utils.py#L175)（L175）= `LOADER_DICT` 所有扩展名扁平列表，供 `KnowledgeFile` 校验扩展名合法性。

### 3.2 类名解析 `get_LoaderClass`

[utils.py L256-L260](file:///d:/Code/haisi/haisi_rag_models_server/server/knowledge_base/utils.py#L256-L260)：

```python
def get_LoaderClass(file_extension):
    for LoaderClass, extensions in LOADER_DICT.items():
        if file_extension in extensions:
            logger.info(f"###get_LoaderClass:{LoaderClass}->{file_extension}")
            return LoaderClass
```

顺序遍历，返回**第一个**匹配的类名。由于 `LOADER_DICT` 是 dict（Python 3.7+ 保序），扩展名同时出现在多个 loader 时以**定义顺序**为准（如 `.ppt` 被 `RapidOCRPPTLoader` 先匹配，L172 的 `UnstructuredPowerPointLoader` 永远不会被选中）。

### 3.3 实例化 `get_loader`（双源解析 + 异常兜底）

[utils.py L264-L313](file:///d:/Code/haisi/haisi_rag_models_server/server/knowledge_base/utils.py#L264-L313)：

```python
def get_loader(loader_name, file_path, loader_kwargs=None):
    loader_kwargs = loader_kwargs or {}
    try:
        if loader_name in LOADER_DICT.keys():          # ① 配置驱动：本地包 or langchain 官方
            document_loaders_module = importlib.import_module('document_loaders')
        else:
            document_loaders_module = importlib.import_module('langchain.document_loaders')
        DocumentLoader = getattr(document_loaders_module, loader_name)
    except Exception as e:
        # ② 兜底：回退到 langchain 的 UnstructuredFileLoader
        document_loaders_module = importlib.import_module('langchain.document_loaders')
        DocumentLoader = getattr(document_loaders_module, "UnstructuredFileLoader")

    # ③ 特殊参数注入
    if loader_name == "UnstructuredFileLoader":
        loader_kwargs.setdefault("autodetect_encoding", True)
    elif loader_name == "CSVLoader":
        # 未指定 encoding 时自动探测文件编码
        ...
    elif loader_name in ("JSONLoader", "JSONLinesLoader"):
        loader_kwargs.setdefault("jq_schema", ".")
        loader_kwargs.setdefault("text_content", False)

    loader = DocumentLoader(file_path, **loader_kwargs)
    return loader
```

三个要点：

1. **双源解析**：loader 名在 `LOADER_DICT` → 从本地 `document_loaders` 包取（自定义实现）；否则从 `langchain.document_loaders` 取（官方实现）。
2. **异常兜底**：任何解析失败都回退到 langchain 的 `UnstructuredFileLoader`（日志里 `AttributeError: ... has no attribute 'UnstructuredFileLoader'` 后还能"成功 200"就是这个兜底导致的，掩盖了真实失败）。
3. **参数注入**：按 loader 名注入默认参数（编码探测、jq_schema 等）。

---

## 4. 配置驱动

### 4.1 KB 配置：`params/kb_config.yaml`

[configs/kb_loader.py](file:///d:/Code/haisi/haisi_rag_models_server/configs/kb_loader.py) 的 `KBLoader` 类从 `params/kb_config.yaml` 加载 `KB_CONFIG`（基于 mtime 热加载），导出全局常量：

| 常量 | 默认值 | 用途 |
|---|---|---|
| `CHUNK_SIZE` | 700 | 单块最大字符数 |
| `OVERLAP_SIZE` | 150 | 相邻块重合字符数 |
| `ZH_TITLE_ENHANCE` | True | 中文标题加强 |
| `ZH_LABEL_ENHANCE` | — | 中文标签加强 |
| `KB_ROOT_PATH` | "" | 知识库根目录 |
| `TEXT_SPLITTER_NAME` | `ChineseRecursiveTextSplitter` | 默认分词器 |
| `text_splitter_dict` | {} | 分词器配置（source/tokenizer_name_or_path 等） |

[utils.py L2-L13](file:///d:/Code/haisi/haisi_rag_models_server/server/knowledge_base/utils.py#L2-L13) 从 `configs` 导入这些常量，分词器选择在 [make_text_splitter](file:///d:/Code/haisi/haisi_rag_models_server/server/knowledge_base/utils.py#L316-L414)（L316）实现（同样双源：优先用户自定义 `text_splitter` 模块，回退 `langchain.text_splitter`）。

### 4.2 模型配置：`params/model_config.yaml`

加载器内部各自调用 `load_model_config()` 读取 VLM/LLM 配置：

| 调用点 | 读取段 | 默认模型 | 用途 |
|---|---|---|---|
| [mypdfloader.py](file:///d:/Code/haisi/haisi_rag_models_server/document_loaders/mypdfloader.py#L15) `load_model_config` | `VLM_API_CONFIG` | `doubao-seed-2.0-lite` | PDF→Markdown 多模态推理 |
| [mydocloader.py](file:///d:/Code/haisi/haisi_rag_models_server/document_loaders/mydocloader.py)（同上） | `VLM_API_CONFIG` | `doubao-seed-2.0-lite` | PDF/Word→Markdown |
| [myexcelloader.py](file:///d:/Code/haisi/haisi_rag_models_server/document_loaders/myexcelloader.py#L23) `load_model_config` | `ONLINE_LLM_MODEL` | `doubao-1.5-pro-32k-api` | Excel 结构化解析 |

另有 [configs/model_loader.py](file:///d:/Code/haisi/haisi_rag_models_server/configs/model_loader.py) 的 `ModelLoader` 是全局模型配置加载器（同样基于 mtime 热加载），但加载器内部目前各自用本地 `load_model_config`，未统一走 `ModelLoader`。

---

## 5. 调用链

以 `POST /kw/knowledge_base/upload_docs` 为例：

```
kb_doc_api.upload_docs                          (kb_doc_api.py L218)
  └─ 文件落盘到 KB_ROOT_PATH/<kb>/content/
  └─ update_docs                               (kb_doc_api.py L540)
      └─ KnowledgeFile(filename, kb_name)      (utils.py L417)
      │   ├─ self.ext = os.path.splitext(...)  L430
      │   ├─ if ext not in SUPPORTED_EXTS: raise ValueError   L431-L432
      │   ├─ self.document_loader_name = get_LoaderClass(ext) L437  ← 扩展名→类名
      │   └─ self.text_splitter_name = TEXT_SPLITTER_NAME    L438
      └─ files2docs_in_thread(kb_files, ...)   (utils.py L522)  ← 多线程
          └─ KnowledgeFile.file2docs()         (utils.py L441)
              ├─ get_loader(loader_name, file_path, loader_kwargs)  L444  ← 双源解析+实例化
              └─ loader.load() → List[Document]                L448
          └─ file2text() → docs2texts()        (utils.py L451)
              ├─ make_text_splitter(...)      L467  ← 分词器（双源）
              └─ text_splitter.split_documents(docs)  L476
      └─ kb.update_doc(kb_file, ...)           ← 向量化入 Milvus
```

关键日志标识：`###get_LoaderClass:<类名>-><扩展名>`（L259）、`<类名> used for <路径>`（L443）、`处理完成，共生成 N 个元素`（loader 内部）。

---

## 6. 已知问题与改进建议

### 6.1 `.txt` 等通用扩展名加载失败（已修复）

**现象**：上传 `.txt` 时日志报 `AttributeError: module 'document_loaders' has no attribute 'UnstructuredFileLoader'`，随后 `get_loader` 异常兜底走 langchain 的 `UnstructuredFileLoader`，继续报 `BadZipFile: File is not a zip file`，最终 `list_files` 返回空。

**根因（两层）**：

1. **注册层**：`LOADER_DICT` 把 `.txt/.rtf/.xml/...` 映射到 `UnstructuredFileLoader`（[utils.py L152-L154](file:///d:/Code/haisi/haisi_rag_models_server/server/knowledge_base/utils.py#L152-L154)），`get_loader` 因类名在 `LOADER_DICT` 而从**本地** `document_loaders` 包取该类，但 `__init__.py` 未导出 `UnstructuredFileLoader`（只导出 5 个 RapidOCR/Excel 类）→ `AttributeError` → 触发 `get_loader` 异常兜底（[utils.py L285-L290](file:///d:/Code/haisi/haisi_rag_models_server/server/knowledge_base/utils.py#L285-L290)），掩盖真实失败。
2. **依赖层**：`UnstructuredFileLoader._get_elements` 调 `unstructured.partition.auto.partition`，其 `contains_verb` 调 nltk `pos_tag` → `_download_nltk_package_if_not_present` → `nltk.find("tokenizers/punkt_tab")`；环境里 `/root/nltk_data/tokenizers/punkt_tab.zip` 损坏（737KB，非有效 zip），打开时抛 `BadZipFile`。

**修复**：
- 代码层（§6.1 本身）：在 [document_loaders/__init__.py](file:///d:/Code/haisi/haisi_rag_models_server/document_loaders/__init__.py) 末尾补一行 re-export：
  ```python
  from langchain.document_loaders.unstructured import UnstructuredFileLoader
  ```
  让本地包能解析该名字，消除 `AttributeError` 与异常兜底。
- 环境层（§6.1 修复后暴露）：删损坏的 `punkt_tab.zip`，重新下载有效版本（528KB，PK magic）：
  ```bash
  rm -f /root/nltk_data/tokenizers/punkt_tab.zip
  curl -sSL -o /root/nltk_data/tokenizers/punkt_tab.zip \
    https://raw.githubusercontent.com/nltk/nltk_data/gh-pages/packages/tokenizers/punkt_tab.zip
  ```

**验证**：上传 `verify.txt`（含中文），`###get_LoaderClass:UnstructuredFileLoader->.txt` 后**无 AttributeError、无 BadZipFile**，29s 完成，`list_files respond:['verify.txt']`。

### 6.2 `FilteredCSVloader.py` import 笔误（已修复）

[FilteredCSVloader.py L3](file:///d:/Code/haisi/haisi_rag_models_server/document_loaders/FilteredCSVloader.py#L3) `from langchain.document_loaders import CSVLoader` 是 langchain 0.1 旧路径，0.2 起已废弃。已改为 `from langchain_community.document_loaders import CSVLoader`（`langchain-community==0.2.4` 见 [docker/requirements.txt](file:///d:/Code/haisi/haisi_rag_models_server/docker/requirements.txt) L105，且 `startup.py`/`configs/logger_manager.py` 均将其列为依赖检查项）。

> 说明：L7 `from langchain.docstore.document import Document` 是项目 18+ 文件共用的既有约定（见 `server/knowledge_base/utils.py` L18、`kb_doc_api.py` L21 等），非笔误，保留。该 loader 仍未在 `__init__.py` 导出、`LOADER_DICT` 中被注释，实际不可用——如需启用需另补导出与注册。

### 6.3 `mypdfloader.py` 与 `mydocloader.py` 重复

两文件都定义了 `RapidOCRPDFLoader`（PDF→Markdown→层级分块的完整实现），代码高度重复。`__init__.py` 从 `mypdfloader` 导入 `RapidOCRPDFLoader`、从 `mydocloader` 导入 `RapidOCRDocLoader`（后者实为 mydocloader 内 `RapidOCRPDFLoader` 的别名）。建议合并为单一实现（mydocloader 的 PDF+Word 版更完整），`mypdfloader` 保留为薄封装或删除。

### 6.4 `get_loader` 兜底掩盖错误

异常兜底（L285-L290）把所有加载器解析失败都回退到 langchain `UnstructuredFileLoader`，导致上传接口返回 200 但 `list_files` 为空，问题被隐藏（§6.1 就是典型）。建议兜底前明确区分"类名不存在"（应直接失败并返回 failed_files）与"实例化失败"（可兜底重试）。

---

## 7. 扩展指南：新增文件类型

1. **实现加载器**：在 `document_loaders/` 下新建 `myxxxloader.py`，继承 `UnstructuredFileLoader` 覆写 `_get_elements()`（或继承合适的 langchain loader）。
2. **导出**：在 [document_loaders/__init__.py](file:///d:/Code/haisi/haisi_rag_models_server/document_loaders/__init__.py) 加 `from .myxxxloader import XxxLoader`。
3. **注册**：在 [utils.py `LOADER_DICT`](file:///d:/Code/haisi/haisi_rag_models_server/server/knowledge_base/utils.py#L142-L174) 加 `"XxxLoader": ['.xxx']`。
4. **配置**（如需模型）：在 `params/model_config.yaml` 加模型段，在 loader 内 `load_model_config` 读取。
5. **验证**：上传一个 `.xxx` 文件，看日志 `###get_LoaderClass:XxxLoader->.xxx` + `处理完成，共生成 N 个元素`（N>0）。
