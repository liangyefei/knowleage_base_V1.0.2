#!/usr/bin/env python3
"""
ConfigFileLoader — 使用 tree-sitter 对代码/配置文件进行语法解析，按语法节点切分。
支持 30+ 种语言：.py/.c/.h/.cpp/.ts/.js/.go/.rs/.java/.rb/.sh/.dts/.cmake/.xml/Makefile 等。

切分策略：
1. tree-sitter 解析 → 自适应递归切分（大块往下钻到语法边界，小块累积合并）
2. 粘性前缀合并（comment/identifier 不独立，拼到后面的块前面）
3. 均匀累积合并（到 FLUSH_THRESHOLD 主动 flush，预判超 MAX 则先 flush）
4. tree-sitter 不可用时走正则/专用扫描器兜底
"""
import warnings

warnings.filterwarnings("ignore")
import os
import re

import chardet
from langchain.docstore.document import Document
from langchain.document_loaders import UnstructuredFileLoader

from configs import log_verbose, logger

try:
    from tree_sitter_language_pack import get_parser
    _TS_AVAILABLE = True
except Exception:
    _TS_AVAILABLE = False

# 分块参数
MAX_CHUNK_CHARS = 2048
FLUSH_THRESHOLD = 1024
_UTF8_KEY = 'utf-8'

# 文件扩展名/文件名 → tree-sitter 语言名
# C/H 用 cpp 解析器：C 语法是 C++ 子集，cpp 能正确处理 extern "C" 等 C++ 语法
LANG_MAP = {
    ".sh": "bash",
    ".dts": "devicetree",
    ".dtsi": "devicetree",
    ".ini": "ini",
    ".cmake": "cmake",
    ".xml": "xml",
    "makefile": "make",
    ".c": "cpp",
    ".h": "cpp",
    ".js": "javascript",
    ".mjs": "javascript",
    ".ts": "typescript",
    ".tsx": "tsx",
    ".cpp": "cpp",
    ".hpp": "cpp",
    ".cc": "cpp",
    ".hh": "cpp",
    ".cxx": "cpp",
    ".hxx": "cpp",
    ".cs": "csharp",
    ".cob": "cobol",
    ".cpy": "cobol",
    ".go": "go",
    ".java": "java",
    ".kt": "kotlin",
    ".lua": "lua",
    ".pl": "perl",
    ".pm": "perl",
    ".rb": "ruby",
    ".rs": "rust",
    ".scala": "scala",
    ".php": "php",
    ".proto": "proto",
    ".rst": "rst",
    ".swift": "swift",
    ".tex": "latex",
    ".sol": "solidity",
    ".py": "python",
}

# tree-sitter 不可用时的正则/专用兜底分块
REGEX_PATTERNS = {
    # ini: 按 [section] 分块
    "ini": r'(\[[^\]]+\]\s*\n(?:(?!\[).)*?)',
}


class ConfigFileLoader(UnstructuredFileLoader):
    """使用 tree-sitter 或正则表达式解析代码/配置文件，按语法节点切分为 Document"""

    def __init__(self, file_path: str, **kwargs):
        super().__init__(file_path=file_path, **kwargs)

    @staticmethod
    def _merge_sticky_prefix(chunks: list[tuple[str, str, int]]) -> list[tuple[str, str, int]]:
        """粘性前缀合并：comment/identifier 不独立成块，拼到下一个非前缀块前面。"""
        STICKY_PREFIX_TYPES = {"comment", "comment_block", "property_identifier", "type_identifier"}
        merged = []
        pending_prefix = None
        pending_prefix_type = None

        for text, ntype, size in chunks:
            if ntype in STICKY_PREFIX_TYPES:
                if pending_prefix is not None:
                    pending_prefix = pending_prefix + text
                    pending_prefix_type = pending_prefix_type + "+" + ntype
                else:
                    pending_prefix = text
                    pending_prefix_type = ntype
                continue

            if pending_prefix is not None:
                combined = pending_prefix + text
                merged.append((combined, pending_prefix_type + "+" + ntype, len(combined)))
                pending_prefix = None
                pending_prefix_type = None
            else:
                merged.append((text, ntype, size))

        if pending_prefix is not None:
            merged.append((pending_prefix, pending_prefix_type, len(pending_prefix)))

        return merged

    @staticmethod
    def _merge_accumulate(chunks: list[tuple[str, str, int]], flush_at: int) -> list[str]:
        """均匀累积合并：累积到 flush_at 主动 flush，预判超 MAX 则先 flush。"""
        if not chunks:
            return []

        final = []
        buf_text, buf_type, buf_size = chunks[0]

        for text, ntype, size in chunks[1:]:
            # 预判：合并后超过 MAX → 先 flush
            if buf_size + size > MAX_CHUNK_CHARS:
                final.append(buf_text)
                buf_text, buf_type, buf_size = text, ntype, size
                continue

            # buf 已达 flush 阈值 → 先 flush
            if buf_size >= flush_at:
                final.append(buf_text)
                buf_text, buf_type, buf_size = text, ntype, size
                continue

            # 否则：吞入 buf，继续累积
            buf_text = buf_text + text
            buf_size = len(buf_text)
            buf_type = buf_type + "+" + ntype

        if buf_text:
            final.append(buf_text)

        return final

    @staticmethod
    def _split_devicetree(source_text: str) -> list[str]:
        """
        用花括号深度计数切分 devicetree，支持任意层嵌套。
        只在 depth==0 时按 ';' 切分顶层语句：
        - /dts-v1/; /memreserve/; 等顶层指令各自成块
        - / { node1 { ... }; &label { ... }; }; 根节点整块保留
        """
        chunks = []
        depth = 0
        start = 0
        for i, c in enumerate(source_text):
            if c == "{":
                depth += 1
            elif c == "}":
                depth = max(0, depth - 1)
            elif c == ";" and depth == 0:
                block = source_text[start:i + 1].strip()
                if block:
                    chunks.append(block)
                start = i + 1
        tail = source_text[start:].strip()
        if tail:
            chunks.append(tail)
        return chunks

    @staticmethod
    def _force_split(text: str) -> list[str]:
        """
        硬上限保护：强制将文本切分为不超过 MAX_CHUNK_CHARS 的块。
        优先按行边界切分，单行超长时按字符强制切。
        仅用于 _load_as_plain_text（不支持的文件类型兜底）。
        """
        max_len = MAX_CHUNK_CHARS
        if len(text) <= max_len:
            return [text]

        chunks = []
        lines = text.split("\n")
        buf = ""

        for line in lines:
            if len(line) > max_len:
                # 先 flush buf
                if buf:
                    chunks.append(buf)
                    buf = ""
                # 单行超长，按字符强制切
                for i in range(0, len(line), max_len):
                    chunks.append(line[i:i + max_len])
            else:
                candidate = (buf + "\n" + line) if buf else line
                if len(candidate) > max_len:
                    if buf:
                        chunks.append(buf)
                    buf = line
                else:
                    buf = candidate

        if buf:
            chunks.append(buf)

        return chunks

    def load(self) -> list[Document]:
        """加载文件并按语法节点切分为 Document 列表"""
        docs = []
        try:
            lang_name = self._get_language_name()
            if lang_name is None:
                logger.warning(f"ConfigFileLoader: 不支持的文件类型 {self.file_path}, 按纯文本读取")
                return self._load_as_plain_text()

            logger.info(f"ConfigFileLoader: type={lang_name}, file={self.file_path}")

            # 检测编码并读取文件（嗅探 + 全文降级兜底，保证不崩）
            source_text = self._read_with_fallback()

            if not source_text.strip():
                logger.warning(f"ConfigFileLoader: 文件内容为空 {self.file_path}")
                return []

            # 尝试 tree-sitter 解析，不可用则回退到正则分块
            chunks = self._parse_with_tree_sitter(lang_name, source_text)
            if not chunks:
                chunks = self._parse_with_regex(lang_name, source_text)
            if not chunks:
                # 都没切出来，整篇文本作为一个 Document
                chunks = [source_text.strip()]

            file_basename = os.path.basename(self.file_path)
            for i, chunk in enumerate(chunks):
                doc = Document(
                    page_content=chunk,
                    metadata={
                        "source": self.file_path,
                        "file_name": file_basename,
                        "file_type": os.path.splitext(file_basename)[1].lstrip(".") or "makefile",
                        "source_type": lang_name,
                        "chunk_index": i + 1,
                        "total_chunks": len(chunks),
                    }
                )
                docs.append(doc)

        except Exception as e:
            msg = f"从配置文件知识库加载文档 {self.file_path} 时出错：{e}"
            logger.error(f'{e.__class__.__name__}: {msg}', exc_info=e if log_verbose else None)
            raise RuntimeError(f"Error loading {self.file_path}") from e

        return docs

    def _detect_encoding(self) -> str:
        """嗅探前 16KB 检测编码：utf-8 → gb18030 → chardet 兜底。

        注意：只读前 16KB，文件后续若有嗅探不到的非 UTF-8 字节，
        需由 _read_with_fallback 全文读取时逐编码降级兜底。
        """
        with open(self.file_path, "rb") as f:
            raw = f.read(16384)
        # 1. utf-8 严格校验（多数源码即此编码，零额外开销）
        try:
            raw.decode(_UTF8_KEY)
            return _UTF8_KEY
        except UnicodeDecodeError:
            logger.debug("编码检测 utf-8 不匹配，尝试 gb18030")
            pass
        try:
            raw.decode("gb18030")
            return "gb18030"
        except UnicodeDecodeError:
            logger.debug("编码检测 gb18030 不匹配，使用 chardet")
            pass
        # 3. chardet 精确识别（繁中 big5 / 日文 shift_jis / 韩文 euc-kr 等）
        result = chardet.detect(raw)
        return result.get("encoding") or _UTF8_KEY

    def _read_with_fallback(self) -> str:
        """用检测到的编码读取全文，失败时逐编码降级，保证不崩。

        嗅探只读前 16KB，文件后续可能有嗅探不到的非 UTF-8 字节，
        全文读取时若 UnicodeDecodeError 则降级重试，最终 latin-1 兜底
        （单字节映射，绝不抛 UnicodeDecodeError）。
        """
        detected = self._detect_encoding()
        # 去重保序：检测到的编码优先，再依次 utf-8/gb18030/latin-1 兜底
        candidates = []
        for enc in [detected, "utf-8", "gb18030", "latin-1"]:
            if enc and enc not in candidates:
                candidates.append(enc)
        for enc in candidates:
            try:
                with open(self.file_path, encoding=enc) as f:
                    text = f.read()
                if enc != detected:
                    logger.info(f"ConfigFileLoader: 嗅探={detected} 但全文读取失败，"
                                f"降级用 {enc} 成功: {self.file_path}")
                else:
                    logger.info(f"ConfigFileLoader detected encoding: {enc} for {self.file_path}")
                return text
            except UnicodeDecodeError:
                continue
        # 理论上 latin-1 不会失败；极端情况用 utf-8 + replace 兜底（不丢消息）
        logger.warning(f"ConfigFileLoader: 所有编码读取均失败，用 utf-8+replace 兜底: {self.file_path}")
        with open(self.file_path, encoding="utf-8", errors="replace") as f:
            return f.read()

    def _get_language_name(self) -> str:
        """根据文件扩展名或文件名获取 tree-sitter 语言名"""
        filename = os.path.basename(self.file_path).lower()
        ext = os.path.splitext(filename)[1]
        if ext in LANG_MAP:
            return LANG_MAP[ext]
        if filename in LANG_MAP:
            return LANG_MAP[filename]
        return None

    def _load_as_plain_text(self) -> list[Document]:
        """退回纯文本读取"""
        text = self._read_with_fallback()
        # 按行边界切分，确保块不超过 MAX_CHUNK_CHARS
        chunks = self._force_split(text)
        return [Document(page_content=chunk, metadata={"source": self.file_path}) for chunk in chunks]

    def _parse_with_tree_sitter(self, lang_name: str, source_text: str) -> list[str]:
        """尝试用 tree-sitter 解析，返回切分后的文本块列表。失败返回空列表。"""
        if not _TS_AVAILABLE:
            logger.info("ConfigFileLoader: tree-sitter 不可用，跳过语法解析")
            return []

        try:
            parser = get_parser(lang_name)
        except Exception as e:
            logger.info(f"ConfigFileLoader: tree-sitter 获取解析器失败 '{lang_name}' ({e}), 尝试兜底分块")
            return []

        try:
            source_bytes = source_text.encode("utf-8")
            tree = parser.parse(source_bytes)
            root = tree.root_node
            chunks = self._tree_sitter_chunk(root, source_bytes)
            if chunks:
                logger.info(f"ConfigFileLoader: tree-sitter 解析成功, {len(chunks)} 个语法块")
            return chunks
        except Exception as e:
            logger.warning(f"ConfigFileLoader: tree-sitter 解析失败 ({e}), 尝试正则分块")
            return []

    def _tree_sitter_chunk(self, root, source_bytes: bytes) -> list[str]:
        """自适应递归切分 + 粘性前缀合并 + 均匀累积合并。

        1. adaptive_chunk：递归 AST，大块往下钻到语法边界切
        2. merge_sticky_prefix：comment/identifier 不独立，拼到后面的块
        3. merge_accumulate：累积到 FLUSH_THRESHOLD flush，预判超 MAX 先 flush
        """
        raw_chunks = self._adaptive_chunk(root, source_bytes, MAX_CHUNK_CHARS)
        merged = self._merge_sticky_prefix(raw_chunks)
        final = self._merge_accumulate(merged, FLUSH_THRESHOLD)
        return final

    def _adaptive_chunk(self, node, source_bytes: bytes, max_size: int) -> list[tuple[str, str, int]]:
        """自适应递归切分：块太大就往下钻，够小就停。

        返回 [(text, node_type, size), ...]
        前缀（export interface { 等）拼到第一个子块前面，
        后缀（分号/括号等）拼到当前子块后面，不重复。
        """
        _REPLACE_ERR = 'replace'
        node_text = source_bytes[node.start_byte:node.end_byte].decode(_UTF8_KEY, errors=_REPLACE_ERR)
        node_size = len(node_text)

        # 够小 → 停
        if node_size <= max_size:
            return [(node_text, node.type, node_size)]

        # 太大 → 递归子节点
        children = node.named_children
        if not children:
            return [(node_text, node.type + "(leaf)", node_size)]

        chunks = []

        for i, child in enumerate(children):
            sub = self._adaptive_chunk(child, source_bytes, max_size)
            if not sub:
                continue

            # 前缀：仅第一个 child，取 node.start_byte → child.start_byte
            if i == 0 and node.start_byte < child.start_byte:
                prefix = source_bytes[node.start_byte:child.start_byte].decode(_UTF8_KEY, errors=_REPLACE_ERR)
                if prefix.strip():
                    first_text, first_type, first_size = sub[0]
                    sub[0] = (prefix + first_text, first_type, first_size + len(prefix))

            # 后缀：当前 child.end_byte → 下一个 child.start_byte（或 node.end_byte）
            if i + 1 < len(children):
                suffix_end = children[i + 1].start_byte
            else:
                suffix_end = node.end_byte
            if child.end_byte < suffix_end:
                suffix = source_bytes[child.end_byte:suffix_end].decode(_UTF8_KEY, errors=_REPLACE_ERR)
                if suffix.strip():
                    last_text, last_type, last_size = sub[-1]
                    sub[-1] = (last_text + suffix, last_type + "+suffix", last_size + len(suffix))

            chunks.extend(sub)

        return chunks

    def _parse_with_regex(self, lang_name: str, source_text: str) -> list[str]:
        """用正则或专用扫描器按语法特征分块。失败返回空列表。"""
        # devicetree 走花括号配对扫描（正则无法处理多层嵌套）
        if lang_name == "devicetree":
            chunks = self._split_devicetree(source_text)
            if chunks:
                logger.info(f"ConfigFileLoader: devicetree 花括号配对成功, {len(chunks)} 个块")
            return chunks

        pattern = REGEX_PATTERNS.get(lang_name)
        if pattern is None:
            # 没有对应的正则模式，返回空列表，由调用方 fallback 为整篇文本
            return []

        try:
            matches = re.findall(pattern, source_text, re.DOTALL)
            chunks = [m.strip() for m in matches if m.strip()]
            if chunks:
                logger.info(f"ConfigFileLoader: 正则分块成功, {len(chunks)} 个块")
            return chunks
        except Exception as e:
            logger.warning(f"ConfigFileLoader: 正则分块失败 ({e})")
            return []
