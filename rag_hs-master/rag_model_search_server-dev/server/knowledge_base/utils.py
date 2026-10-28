import importlib
import json
import os
from collections.abc import Generator
from pathlib import Path

import chardet
import langchain.document_loaders
from langchain.docstore.document import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter, TextSplitter as _TextSplitter

from configs import (
    CHUNK_SIZE,
    KB_ROOT_PATH,
    LLM_MODELS,
    OVERLAP_SIZE,
    TEXT_SPLITTER_NAME,
    ZH_LABEL_ENHANCE,
    ZH_TITLE_ENHANCE,
    log_verbose,
    logger,
    text_splitter_dict,
)
from server.utils import get_model_worker_config, run_in_thread_pool
from text_splitter import zh_label_enhance as func_zh_label_enhance
from text_splitter import zh_title_enhance as func_zh_title_enhance


def validate_kb_name(knowledge_base_id: str) -> bool:
    # 检查是否包含预期外的字符或路径攻击关键字
    if "../" in knowledge_base_id:
        return False
    return True


def get_kb_path(knowledge_base_name: str):
    return os.path.join(KB_ROOT_PATH, knowledge_base_name)


def get_doc_path(knowledge_base_name: str):
    return os.path.join(get_kb_path(knowledge_base_name), "content")


def get_vs_path(knowledge_base_name: str, vector_name: str):
    return os.path.join(get_kb_path(knowledge_base_name), "vector_store", vector_name)


def get_file_path(knowledge_base_name: str, doc_name: str):
    return os.path.join(get_doc_path(knowledge_base_name), doc_name)


def list_kbs_from_folder():
    """提取文件夹名"""
    return [f for f in os.listdir(KB_ROOT_PATH)
            if os.path.isdir(os.path.join(KB_ROOT_PATH, f))]


def list_files_from_folder(kb_name: str):
    doc_path = get_doc_path(kb_name)
    result = []

    def is_skiped_path(path: str):
        tail = os.path.basename(path).lower()
        for x in ["temp", "tmp", ".", "~$"]:
            if tail.startswith(x):
                return True
        return False

    def process_entry(entry):
        if is_skiped_path(entry.path):
            return

        if entry.is_symlink():
            target_path = os.path.realpath(entry.path)
            with os.scandir(target_path) as target_it:
                for target_entry in target_it:
                    process_entry(target_entry)
        elif entry.is_file():
            file_path = (Path(os.path.relpath(entry.path, doc_path)).as_posix())  # 路径统一为 posix 格式
            result.append(file_path)
        elif entry.is_dir():
            with os.scandir(entry.path) as it:
                for sub_entry in it:
                    process_entry(sub_entry)

    with os.scandir(doc_path) as it:
        for entry in it:
            process_entry(entry)

    return result


# https://api.python.langchain.com/en/latest/base/langchain_text_splitters.base.Language.html#langchain_text_splitters.base.Language
# https://python.langchain.com/v0.1/docs/integrations/document_loaders/source_code/
LOADER_DICT = {"UnstructuredHTMLLoader": ['.html', '.htm'],
               "MHTMLLoader": ['.mhtml'],
               "VLLMMarkdownLoader": ['.md'],
               "JSONLoader": [".json"],
               "JSONLinesLoader": [".jsonl"],
               "CSVLoader": [".csv"],
               # "FilteredCSVLoader": [".csv"], 如果使用自定义分割csv
               # MinerUDocumentLoader 统一处理 PDF/Word/PPT，兼容别名 VLLMOCRPDFLoader 等
               "MinerUDocumentLoader": [".pdf", ".docx", ".doc", ".ppt", ".pptx"],
               "VLLMOCRLoader": ['.png', '.jpg', '.jpeg', '.bmp'],
               "UnstructuredFileLoader": ['.eml', '.msg', '.rst',
                                          '.rtf',
                                          '.epub', '.odt', '.tsv','.txt'],
               "UnstructuredEmailLoader": ['.eml', '.msg'],
               "UnstructuredEPubLoader": ['.epub'],
               "ExcelChineseLoader": ['.xlsx', '.xls'],  # 删除.xlsd格式，需求未包含改格式
               "NotebookLoader": ['.ipynb'],
               "UnstructuredODTLoader": ['.odt'],
               # Bug 1 修复：注释掉 PythonLoader，让 .py 走 ConfigFileLoader（tree-sitter Python 支持）。
               # 原 PythonLoader 用 LanguageParser 已按函数/类切分，但输出无 chunk_index，
               # 导致 docs2texts 又按 CHUNK_SIZE=700 二次切碎（函数被切断），与 .c/.go 走
               # ConfigFileLoader（有 chunk_index 跳过二次切分）不一致。改走 ConfigFileLoader
               # 后输出有 chunk_index，与其他代码文件处理一致。
               # 注意：ConfigFileLoader 无 HierarchyChunker 双层硬保护，超大函数（>2048 token）
               # 可能超 embedding 限制；该隐患对 .c/.go 同样存在，通用修复需给 ConfigFileLoader
               # 加硬保护（超 2048 token 按 _split_text_by_char 拆），不在本次范围。
               "UnstructuredRSTLoader": ['.rst'],
               "UnstructuredRTFLoader": ['.rtf'],
               "SRTLoader": ['.srt'],
               "TomlLoader": ['.toml'],
               "UnstructuredTSVLoader": ['.tsv'],
               "UnstructuredXMLLoader": [],
               "EverNoteLoader": ['.enex'],
               "ConfigFileLoader": ['.sh', '.dts', '.dtsi', '.ini', '.cmake', '.xml', 'makefile',
                                   '.c', '.h',  # C
                                   '.js', '.mjs',  # JavaScript
                                   '.ts', '.tsx',  # TypeScript
                                   '.cpp', '.hpp', '.cc', '.hh', '.cxx', '.hxx',  # C++
                                   '.cs',  # C#
                                   '.cob', '.cpy',  # COBOL
                                   '.go',  # Go
                                   '.java',  # Java
                                   '.kt',  # Kotlin
                                   '.lua',  # Lua
                                   '.pl', '.pm',  # Perl
                                   '.rb',  # Ruby
                                   '.rs',  # Rust
                                   '.scala',  # Scala
                                   '.php',  # PHP
                                   '.proto',  # Proto
                                   '.rst',  # RST
                                   '.swift',  # Swift
                                   '.tex',  # LaTeX
                                   '.sol',  # Solidity
                                   '.py',  # Python
                                   ],
               }
SUPPORTED_EXTS = [ext for sublist in LOADER_DICT.values() for ext in sublist]

# TODO CK 检查支持的代码类型是否都导入了对应的加载器，代码解析链路是否通畅
# GenericCodeLoader 已废弃，所有代码文件改用 ConfigFileLoader + tree-sitter-language-pack
# CODE_DICT / CODE_TYPE_DICT 仅用于 RecursiveCharacterTextSplitter 的语言识别（如仍需要）
CODE_DICT = {}
SUPPORTED_CODE_EXTS = []
CODE_TYPE_DICT = {}

# patch json.dumps to disable ensure_ascii
def _new_json_dumps(obj, **kwargs):
    kwargs["ensure_ascii"] = False
    return _origin_json_dumps(obj, **kwargs)


if json.dumps is not _new_json_dumps:
    _origin_json_dumps = json.dumps
    json.dumps = _new_json_dumps


class JSONLinesLoader(langchain.document_loaders.JSONLoader):
    """
    行式 Json 加载器，要求文件扩展名为 .jsonl
    """
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._json_lines = True


langchain.document_loaders.JSONLinesLoader = JSONLinesLoader


def get_LoaderClass(file_extension, filename=None):
    # 先按文件名匹配（处理 Makefile 等无扩展名文件）
    if filename:
        basename = os.path.basename(filename).lower()
        for LoaderClass, extensions in LOADER_DICT.items():
            if basename in extensions:
                logger.info(f"###get_LoaderClass:{LoaderClass}->{basename}")
                return LoaderClass
    # 再按扩展名匹配
    for LoaderClass, extensions in LOADER_DICT.items():
        if file_extension in extensions:
            logger.info(f"###get_LoaderClass:{LoaderClass}->{file_extension}")
            return LoaderClass
    return None


# 把一些向量化共用逻辑从KnowledgeFile抽取出来，等langchain支持内存文件的时候，可以将非磁盘文件向量化
def get_loader(loader_name: str, file_path: str, loader_kwargs: dict = None):
    """
    根据loader_name和文件路径或内容返回文档加载器。
    """
    _ENCODING_KEY = 'encoding'
    loader_kwargs = loader_kwargs or {}
    try:
        # if loader_name in ["RapidOCRPDFLoader",
        #                    "RapidOCRLoader",
        #                    "FilteredCSVLoader",
        #                    "RapidOCRDocLoader",
        #                    "RapidOCRPPTLoader",
        #                    "ExcelChineseLoader",
        #                    "GenericCodeLoader",
        #                    ]:
        if loader_name in LOADER_DICT.keys():
            # 加载本地解析器
            document_loaders_module = importlib.import_module('document_loaders')
        else:
            # 加载langchain官网库
            document_loaders_module = importlib.import_module('langchain.document_loaders')
        DocumentLoader = getattr(document_loaders_module, loader_name)
    except Exception as e:
        # 如果找不到自定义的文件解析器则用默认的：UnstructuredFileLoader
        msg = f"为文件{file_path}查找加载器{loader_name}时出错：{e}"
        logger.error(f'{e.__class__.__name__}: {msg}', exc_info=e if log_verbose else None)
        document_loaders_module = importlib.import_module('langchain.document_loaders')
        DocumentLoader = document_loaders_module.UnstructuredFileLoader

    if loader_name == "UnstructuredFileLoader":
        # TODO UnicodeDecodeError 'gb2312' codec can't decode byte 0x86 in position 34639: illegal multibyte sequence
        loader_kwargs.setdefault("autodetect_encoding", True)
    elif loader_name == "CSVLoader":
        if not loader_kwargs.get(_ENCODING_KEY):
            # 如果未指定 encoding，自动识别文件编码类型，避免langchain loader 加载文件报编码错误
            with open(file_path, 'rb') as struct_file:
                encode_detect = chardet.detect(struct_file.read())
            if encode_detect is None:
                encode_detect = {_ENCODING_KEY: "utf-8"}
            loader_kwargs[_ENCODING_KEY] = encode_detect[_ENCODING_KEY]
        # TODO 支持更多的自定义CSV读取逻辑
    elif loader_name == "JSONLoader":
        loader_kwargs.setdefault("jq_schema", ".")
        loader_kwargs.setdefault("text_content", False)
    elif loader_name == "JSONLinesLoader":
        loader_kwargs.setdefault("jq_schema", ".")
        loader_kwargs.setdefault("text_content", False)

    loader = DocumentLoader(file_path, **loader_kwargs)
    return loader


def make_text_splitter(
        splitter_name: str = TEXT_SPLITTER_NAME,
        chunk_size: int = CHUNK_SIZE,
        chunk_overlap: int = OVERLAP_SIZE,
        llm_model: str = LLM_MODELS[0],
        ext: str = None
):
    """
    根据参数获取特定的分词器
    """
    _TOKENIZER_NAME_OR_PATH_KEY = 'tokenizer_name_or_path'
    splitter_name = splitter_name or "SpacyTextSplitter"
    try:
        if splitter_name == "MarkdownHeaderTextSplitter":  # MarkdownHeaderTextSplitter特殊判定
            headers_to_split_on = text_splitter_dict[splitter_name]['headers_to_split_on']
            text_splitter = langchain.text_splitter.MarkdownHeaderTextSplitter(
                    headers_to_split_on=headers_to_split_on)
        else:

            try:  # 优先使用用户自定义的text_splitter
                text_splitter_module = importlib.import_module('text_splitter')
                TextSplitter = getattr(text_splitter_module, splitter_name)
            except (ImportError, AttributeError):  # 否则使用langchain的text_splitter
                text_splitter_module = importlib.import_module('langchain.text_splitter')
                TextSplitter = getattr(text_splitter_module, splitter_name)

            if splitter_name == "ChineseRecursiveTextSplitter":
                # 纯正则分割器，不需要 tokenizer。历史实现走 huggingface 分支加载
                # tokenizer，tokenizer_name_or_path 为空且 MODEL_PATH 未配本地模型时
                # AutoTokenizer.from_pretrained(None) 会尝试连 huggingface.co 报错。
                text_splitter = TextSplitter(
                        chunk_size=chunk_size,
                        chunk_overlap=chunk_overlap
                )
            elif text_splitter_dict[splitter_name]["source"] == "tiktoken":  # 从tiktoken加载
                try:
                    language = None
                    if splitter_name == "RecursiveCharacterTextSplitter":
                        for code_type, ext_list in CODE_DICT.items():
                            if ext in ext_list:
                                language = CODE_TYPE_DICT[code_type]
                        text_splitter = RecursiveCharacterTextSplitter.from_language(
                                language=language,
                                chunk_size=chunk_size,
                                chunk_overlap=chunk_overlap
                        )
                        logger.info(f"@@@text_splitter: RecursiveCharacterTextSplitter, language_type: {language}")
                    else:
                        text_splitter = TextSplitter.from_tiktoken_encoder(
                                encoding_name=text_splitter_dict[splitter_name][_TOKENIZER_NAME_OR_PATH_KEY],
                                pipeline="zh_core_web_sm",
                                chunk_size=chunk_size,
                                chunk_overlap=chunk_overlap
                        )
                except Exception:
                    text_splitter = TextSplitter.from_tiktoken_encoder(
                            encoding_name=text_splitter_dict[splitter_name][_TOKENIZER_NAME_OR_PATH_KEY],
                            chunk_size=chunk_size,
                            chunk_overlap=chunk_overlap
                    )
            elif text_splitter_dict[splitter_name]["source"] == "huggingface":  # 从huggingface加载
                if text_splitter_dict[splitter_name][_TOKENIZER_NAME_OR_PATH_KEY] == "":
                    config = get_model_worker_config(llm_model)
                    text_splitter_dict[splitter_name][_TOKENIZER_NAME_OR_PATH_KEY] = \
                        config.get("model_path")

                if text_splitter_dict[splitter_name][_TOKENIZER_NAME_OR_PATH_KEY] == "gpt2":
                    from transformers import GPT2TokenizerFast
                    tokenizer = GPT2TokenizerFast.from_pretrained("gpt2")
                else:  # 字符长度加载
                    from transformers import AutoTokenizer
                    tokenizer = AutoTokenizer.from_pretrained(
                            text_splitter_dict[splitter_name][_TOKENIZER_NAME_OR_PATH_KEY],
                            trust_remote_code=True
                    )
                text_splitter = TextSplitter.from_huggingface_tokenizer(
                        tokenizer=tokenizer,
                        chunk_size=chunk_size,
                        chunk_overlap=chunk_overlap
                )
            else:
                try:
                    text_splitter = TextSplitter(
                            pipeline="zh_core_web_sm",
                            chunk_size=chunk_size,
                            chunk_overlap=chunk_overlap
                    )
                except Exception:
                    text_splitter = TextSplitter(
                            chunk_size=chunk_size,
                            chunk_overlap=chunk_overlap
                    )
    except Exception as e:
        logger.error(str(e))
        try:
            text_splitter_module = importlib.import_module('langchain.text_splitter')
            TextSplitter = text_splitter_module.RecursiveCharacterTextSplitter
            text_splitter = TextSplitter(chunk_size=chunk_size, chunk_overlap=chunk_overlap)
        except Exception as exc:
            logger.error(str(exc))
            raise RuntimeError(str(exc)) from exc

    # If you use SpacyTextSplitter you can use GPU to do split likes Issue #1287
    logger.info(f"add {text_splitter.__str__()}")
    return text_splitter


class KnowledgeFile:
    def __init__(
            self,
            filename: str,
            knowledge_base_name: str,
            loader_kwargs: dict = None,
    ):
        """
        对应知识库目录中的文件，必须是磁盘上存在的才能进行向量化等操作。
        """
        if loader_kwargs is None:
            loader_kwargs = {}
        self.kb_name = knowledge_base_name
        self.filename = str(Path(filename).as_posix())  # 确保路径始终以正斜杠 / 分隔
        self.ext = os.path.splitext(filename)[-1].lower()
        basename = os.path.basename(filename).lower()
        # 检查扩展名或文件名（如 Makefile）是否在支持列表中
        if self.ext not in SUPPORTED_EXTS and basename not in SUPPORTED_EXTS:
            raise ValueError(f"暂未支持的文件格式 {self.filename}")
        self.loader_kwargs = loader_kwargs
        self.filepath = get_file_path(knowledge_base_name, filename)
        self.docs = None
        self.splited_docs = None
        self.document_loader_name = get_LoaderClass(self.ext, filename)  # UnstructuredFileLoader ...
        self.text_splitter_name = TEXT_SPLITTER_NAME

    def file2docs(self, refresh: bool = False):
        if self.docs is None or refresh:
            logger.info(f"{self.document_loader_name} used for {self.filepath}")
            loader = get_loader(loader_name=self.document_loader_name,
                                file_path=self.filepath,
                                loader_kwargs=self.loader_kwargs
                                )
            self.docs = loader.load()
        return self.docs

    def docs2texts(self, docs: list[Document] = None, **kwargs):
        zh_title_enhance = kwargs.get("zh_title_enhance", ZH_TITLE_ENHANCE)
        zh_label_enhance = kwargs.get("zh_label_enhance", ZH_LABEL_ENHANCE)
        refresh = kwargs.get("refresh", False)
        chunk_size = kwargs.get("chunk_size", CHUNK_SIZE)
        chunk_overlap = kwargs.get("chunk_overlap", OVERLAP_SIZE)
        text_splitter = kwargs.get("text_splitter", None)
        try:
            docs = docs or self.file2docs(refresh=refresh)
            if not docs:
                return []
            # 判断 docs 是否已被 loader 自带分块：md/pdf/doc/ppt 走 VLLM 系列 loader 时，
            # 已由 HierarchyChunker 按层级分好块（每块 <=2048 token，输出 metadata 含 chunk_index）。
            # 此时跳过二次切分，否则 ChineseRecursiveTextSplitter 按 CHUNK_SIZE=700 再切会
            # 切碎层级信息 + chunk 翻倍 + overlap 重复内容，与 HierarchyChunker 双层硬保护冲突。
            already_chunked = any(d.metadata.get("chunk_index") for d in docs)
            # 快速路径：自带分块的扩展名 + 图片/excel/csv（loader 自身处理分页）均跳过二次切分
            skip_split_exts = {".csv", ".png", ".jpg", ".jpeg", ".bmp",
                               ".xlsx", ".xls", ".pdf", ".ppt", ".pptx",
                               ".doc", ".docx", ".md"}
            if not already_chunked and self.ext not in skip_split_exts:
                if text_splitter is None:
                    text_splitter = make_text_splitter(
                            splitter_name=self.text_splitter_name,
                            chunk_size=chunk_size,
                            chunk_overlap=chunk_overlap,
                            ext=self.ext
                    )
                # Bug 5 修复：禁用原 MarkdownHeaderTextSplitter "只取 docs[0]" 的特殊分支。
                # 原逻辑 split_text(docs[0].page_content) 丢弃 docs[1:] 全部内容。
                # 统一走 split_documents；MarkdownHeaderTextSplitter 无此方法时
                # 回退为拼接所有 docs 后 split_text（保留功能且不丢内容）。
                if hasattr(text_splitter, "split_documents"):
                    docs = text_splitter.split_documents(docs)
                else:
                    combined = "\n\n".join(d.page_content for d in docs)
                    docs = text_splitter.split_text(combined)
                logger.info(f"文档二次切分完成: {len(docs)} 个切片 "
                            f"(ext={self.ext}, splitter={self.text_splitter_name})")
            else:
                logger.info(f"跳过二次切分 (ext={self.ext}, already_chunked={already_chunked}, "
                            f"docs={len(docs)})")
        except Exception as e:
            # Bug 1 修复：原 logger.info 级别过低且吞异常，导致分块失败时 docs 保持未分块
            # 的整篇文档继续入库 → 必超 2048 token → embedding 失败，且日志几乎看不到原因。
            # 改为 error + raise，让分块失败显式中断流程，由上层事务处理。
            logger.error(f"文档切分失败 ext={self.ext} splitter={self.text_splitter_name}: {e}",
                         exc_info=True)
            raise
        if not docs:
            return []

        logger.info(f"文档切分示例：{docs[0]}")
        if zh_title_enhance:
            docs = func_zh_title_enhance(docs)
        if zh_label_enhance:
            docs = func_zh_label_enhance(docs, self.filename)
        self.splited_docs = docs
        return self.splited_docs

    def file2text(self, **kwargs):
        zh_title_enhance = kwargs.get("zh_title_enhance", ZH_TITLE_ENHANCE)
        zh_label_enhance = kwargs.get("zh_label_enhance", ZH_LABEL_ENHANCE)
        refresh = kwargs.get("refresh", False)
        chunk_size = kwargs.get("chunk_size", CHUNK_SIZE)
        chunk_overlap = kwargs.get("chunk_overlap", OVERLAP_SIZE)
        text_splitter = kwargs.get("text_splitter", None)
        if self.splited_docs is None or refresh:
            docs = self.file2docs()
            self.splited_docs = self.docs2texts(
                    docs=docs,
                    zh_title_enhance=zh_title_enhance,
                    zh_label_enhance=zh_label_enhance,
                    refresh=refresh,
                    chunk_size=chunk_size,
                    chunk_overlap=chunk_overlap,
                    text_splitter=text_splitter
            )
        return self.splited_docs

    def file_exist(self):
        return os.path.isfile(self.filepath)

    def get_mtime(self):
        return os.path.getmtime(self.filepath)

    def get_size(self):
        return os.path.getsize(self.filepath)


def files2docs_in_thread(
        files: list[KnowledgeFile | tuple[str, str] | dict],
        chunk_size: int = CHUNK_SIZE,
        chunk_overlap: int = OVERLAP_SIZE,
        zh_title_enhance: bool = ZH_TITLE_ENHANCE,
) -> Generator:
    """
    利用多线程批量将磁盘文件转化成langchain Document.
    如果传入参数是Tuple，形式为(filename, kb_name)
    生成器返回值为 status, (kb_name, file_name, docs | error)
    """
    def file2docs(*, file: KnowledgeFile, **kwargs) -> tuple[bool, tuple[str, str, list[Document]]]:
        try:
            return True, (file.kb_name, file.filename, file.file2text(**kwargs))
        except Exception as e:
            msg = f"从知识库 {file.kb_name} 加载文档 {file.filename} 时出错：{e}"
            logger.error(f'{e.__class__.__name__}: {msg}',
                         exc_info=e if log_verbose else None)
            return False, (file.kb_name, file.filename, msg)

    kwargs_list = []
    for _, file in enumerate(files):
        kwargs = {}
        try:
            if isinstance(file, tuple) and len(file) >= 2:
                filename = file[0]
                kb_name = file[1]
                file = KnowledgeFile(filename=filename, knowledge_base_name=kb_name)
            elif isinstance(file, dict):
                filename = file.pop("filename")
                kb_name = file.pop("kb_name")
                kwargs.update(file)
                file = KnowledgeFile(filename=filename, knowledge_base_name=kb_name)
            kwargs["file"] = file
            kwargs["chunk_size"] = chunk_size
            kwargs["chunk_overlap"] = chunk_overlap
            kwargs["zh_title_enhance"] = zh_title_enhance
            kwargs_list.append(kwargs)
        except Exception as e:
            yield False, (kb_name, filename, str(e))

    for result in run_in_thread_pool(func=file2docs, params=kwargs_list):
        yield result


if __name__ == "__main__":

    kb_file = KnowledgeFile(
            filename="/home/congyin/Code/Project_Langchain_0814/Langchain-Chatchat/knowledge_base/csv1/content/gm.csv",
            knowledge_base_name="samples")
    _docs = kb_file.file2docs()
