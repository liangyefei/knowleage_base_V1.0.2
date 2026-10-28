#!/usr/bin/env python3
# @File : base_loader.py
# @Function: 文档加载器基础组件 - 供 PDF/Word/PPT 加载器复用

import base64
import ctypes
import gc
import hashlib
import json
import os
import re
import shutil
import time
from collections.abc import Iterator
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

# G.TYP.03: 签名默认值抽常量
_DEFAULT_START_POS = 0
_DEFAULT_DEPTH = 0
_DEFAULT_SOURCE_TYPE = "document"

import fitz
import requests
from langchain_core.documents import Document

from configs.kb_loader import KW_ROOT_PATH
from configs.logger_manager import logger

# ============================================================
# 图片处理基础方法
# ============================================================

def pdf_to_images(pdf_path: str, dpi: int = 200) -> list[str]:
    """
    将PDF每一页转换为base64编码的图片

    Args:
        pdf_path: PDF文件路径
        dpi: 图片DPI

    Returns:
        base64编码的图片列表
    """
    images_base64 = []
    doc = fitz.open(pdf_path)

    for page in doc:
        mat = fitz.Matrix(dpi / 72, dpi / 72)
        pix = page.get_pixmap(matrix=mat)
        img_data = pix.tobytes("png")
        img_base64 = base64.b64encode(img_data).decode('utf-8')
        images_base64.append(img_base64)

    doc.close()
    return images_base64


def get_pdf_page_count(pdf_path: str) -> int:
    """获取 PDF 总页数（仅读取元数据，不渲染，毫秒级）"""
    doc = fitz.open(pdf_path)
    try:
        return len(doc)
    finally:
        doc.close()


def iter_batch_images(
        pdf_path: str,
        batch_size: int = 3,
        dpi: int = 200,
        start_page: int = 0
) -> Iterator[tuple[list[str], list[int]]]:
    """流式 PDF→图片生成器：每次只渲染并 yield 一个批次，用完即释放。

    替代 ``pdf_to_images`` 的全量加载，内存恒定 ≈ 一个批次的图片数。
    支持 ``start_page`` 跳过已完成批次，配合 :class:`PDFBatchCache` 实现断点续跑。

    Args:
        pdf_path: PDF 文件路径
        batch_size: 每批页数
        dpi: 渲染 DPI
        start_page: 起始页（0-based），用于断点续跑时跳过已渲染页
    Yields:
        ``(batch_base64: List[str], page_numbers: List[int])``
    """
    doc = fitz.open(pdf_path)
    try:
        total = len(doc)
        mat = fitz.Matrix(dpi / 72, dpi / 72)
        for start in range(start_page, total, batch_size):
            end = min(start + batch_size, total)
            batch: list[str] = []
            pages: list[int] = []
            for p in range(start, end):
                pix = doc[p].get_pixmap(matrix=mat)
                batch.append(base64.b64encode(pix.tobytes("png")).decode('utf-8'))
                pages.append(p + 1)
                del pix
            yield batch, pages
    finally:
        doc.close()


def release_memory() -> None:
    """周期性回收内存，缓解 PyMuPDF C 层堆碎片导致的 RSS 不回落。

    Python 引用计数释放 ``pix`` 后，glibc malloc 不会立即把内存归还给 OS，
    导致 RSS 曲线锯齿爬升、监控误判 OOM 风险。每 N 个批次调用一次。
    非 Linux 环境静默跳过 malloc_trim。
    """
    gc.collect()
    try:
        ctypes.CDLL("libc.so.6").malloc_trim(0)
    except (OSError, AttributeError):
        logger.debug("malloc_trim 不可用（非 Linux 环境），跳过")
        pass


# ============================================================
# PDF 批次缓存 + 断点续跑
# ============================================================

class PDFBatchCache:
    """PDF 批次级中间产物缓存，支持断点续跑（并行处理，批次间无依赖）。

    缓存目录::

        {KW_ROOT_PATH}/pdf_cache/{doc_hash}/
            ├── meta.json          # 参数指纹（batch_size/dpi/model/total_pages），用于缓存失效判断
            ├── batch_0001.md      # 批次 1 的 VLM 输出（原子写：.tmp → rename）
            ├── batch_0002.md
            └── ...

    - ``doc_hash`` = PDF 内容 SHA256 前 16 位，内容变化即生成新目录，避免脏读。
    - 每批 VLM 返回后原子落盘；异常重投/重试时跳过已完成批次，从断点续跑。
    - 并行处理：所有批次独立调用 VLM，不再传递 previous_context（batch N 不依赖 batch N-1）。
    """

    def __init__(self, pdf_path: str, batch_size: int, dpi: int, model: str):
        self.pdf_path = pdf_path
        self.cache_dir = Path(KW_ROOT_PATH) / "pdf_cache" / self._doc_hash(pdf_path)
        self.meta: dict = {
            "pdf_path": str(pdf_path),
            "batch_size": batch_size,
            "dpi": dpi,
            "model": model or "",
            "total_pages": None,
        }

    @staticmethod
    def _doc_hash(pdf_path: str) -> str:
        """流式计算 PDF 内容指纹，避免一次性载入大文件到内存"""
        h = hashlib.sha256()
        with open(pdf_path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):  # 1MB 分片
                h.update(chunk)
        return h.hexdigest()[:16]

    def init(self, total_pages: int) -> None:
        """初始化缓存目录。若现有缓存参数不兼容则重建。"""
        self.meta["total_pages"] = total_pages
        if self._is_compatible(total_pages):
            logger.info(f"命中 PDF 缓存目录: {self.cache_dir}")
            return
        if self.cache_dir.exists():
            shutil.rmtree(self.cache_dir, ignore_errors=True)
            logger.info(f"缓存参数变更或损坏，重建缓存目录: {self.cache_dir}")
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self._save_meta()

    def has_batch(self, batch_idx: int) -> bool:
        return self._batch_path(batch_idx).exists()

    def load_batch(self, batch_idx: int) -> str:
        with open(self._batch_path(batch_idx), encoding="utf-8") as f:
            return f.read()

    def save_batch(self, batch_idx: int, content: str) -> None:
        """原子写：先 ``.tmp`` 再 ``os.replace``，防止写到一半崩溃留半截"""
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        final = self._batch_path(batch_idx)
        tmp = final.with_suffix(".md.tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(content)
        os.replace(tmp, final)

    def find_last_completed_batch(self) -> int:
        """返回从 1 开始最大连续已完成 batch_idx（无缺口），无则返回 0。

        忽略 ``.tmp`` 半成品。若存在缺口（如 batch_0091 缺失但 batch_0092 存在），
        返回缺口前最后一个连续索引（90），确保缺口批次会被重新处理，
        而非因 load_batch(91) 找不到文件直接报错。
        """
        if not self.cache_dir.exists():
            return 0
        existing = set()
        for p in self.cache_dir.glob("batch_*.md"):
            try:
                idx = int(p.stem.split("_")[1])
                existing.add(idx)
            except (ValueError, IndexError):
                continue
        if not existing:
            return 0
        # 从 1 开始找最大连续索引，遇到缺口即停（缺口批次交由 remaining 重处理）
        max_contiguous = 0
        for i in range(1, max(existing) + 1):
            if i in existing:
                max_contiguous = i
            else:
                break
        return max_contiguous

    def _meta_path(self) -> Path:
        return self.cache_dir / "meta.json"

    def _batch_path(self, batch_idx: int) -> Path:
        return self.cache_dir / f"batch_{batch_idx:04d}.md"

    def _is_compatible(self, total_pages: int) -> bool:
        meta_path = self._meta_path()
        if not meta_path.exists():
            return False
        try:
            with open(meta_path, encoding="utf-8") as f:
                cached = json.load(f)
        except (json.JSONDecodeError, OSError):
            return False
        return (
            cached.get("batch_size") == self.meta["batch_size"]
            and cached.get("dpi") == self.meta["dpi"]
            and cached.get("model") == self.meta["model"]
            and cached.get("total_pages") == total_pages
        )

    def _save_meta(self) -> None:
        with open(self._meta_path(), "w", encoding="utf-8") as f:
            json.dump(self.meta, f, ensure_ascii=False, indent=2)


def call_vlm_model(
        endpoint: str,
        headers: dict,
        model: str,
        messages: list[dict],
        **kwargs
) -> str:
    """
    调用多模态模型API的统一方法

    Args:
        endpoint: API端点
        headers: 请求头
        model: 模型名称
        messages: 消息列表
        max_retries: 最大重试次数（默认 3，通过 kwargs 传入）
        retry_delay: 重试延迟（秒，默认 5，通过 kwargs 传入）
        max_tokens: 最大输出token数（默认 16384，通过 kwargs 传入）
        temperature: 温度参数（默认 0.1，通过 kwargs 传入）

    Returns:
        模型返回的内容
    """
    max_retries = kwargs.get("max_retries", 3)
    retry_delay = kwargs.get("retry_delay", 5)
    max_tokens = kwargs.get("max_tokens", 16384)
    temperature = kwargs.get("temperature", 0.1)

    payload = {
        "model": model,
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens
    }

    for attempt in range(max_retries):
        try:
            url = endpoint if endpoint.endswith("/chat/completions") else f"{endpoint}/chat/completions"
            response = requests.post(
                url,
                headers=headers,
                json=payload,
                timeout=(30, 300)
            )
            response.raise_for_status()
            result = response.json()
            return result["choices"][0]["message"]["content"]
        except requests.exceptions.Timeout:
            logger.warning(f"请求超时 (尝试 {attempt + 1}/{max_retries})")
            if attempt < max_retries - 1:
                time.sleep(retry_delay * (attempt + 1))
            else:
                raise
        except Exception as e:
            logger.error(f"API调用失败 (尝试 {attempt + 1}/{max_retries}): {e}")
            if attempt < max_retries - 1:
                time.sleep(retry_delay * (attempt + 1))
            else:
                raise

    raise RuntimeError("API调用失败，已达到最大重试次数")


def _save_chunks_to_json(file_path: str, elements: list[Document]):
    """保存 chunks 到 JSON 文件"""
    if not elements:
        return

    # 解析路径
    file_path_obj = Path(file_path)
    kb_name = file_path_obj.parent.parent.name  # content 的父级目录名
    file_stem = file_path_obj.stem.replace(" ", "")  # 文件名（不含扩展名）
    file_ext = file_path_obj.suffix.lower()[1:]  # 扩展名（不含点）
    source_type = elements[0].metadata.get("source_type", "unknown") if elements else "unknown"

    # 构建 JSON 数据
    data = {
        "source": file_path,
        "file_name": file_stem,
        "file_type": file_ext,
        "source_type": source_type,
        "total_chunks": len(elements),
        "chunks": []
    }

    for doc in elements:
        chunk_data = {
            "chunk_index": doc.metadata.get("chunk_index", 0),
            "text": doc.page_content,
            "metadata": doc.metadata
        }
        data["chunks"].append(chunk_data)

    # 保存路径：KW_ROOT_PATH/kb_name/chunk_json/
    output_dir = Path(KW_ROOT_PATH) / kb_name / "chunk_json"
    output_dir.mkdir(parents=True, exist_ok=True)

    # 命名：{文件名}_{后缀名}.json
    json_filename = f"{file_stem}_{file_ext}.json"
    json_path = output_dir / json_filename

    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

    logger.info(f"   📄 已保存 JSON: {json_path}")


# ============================================================
# 层级分块器
# ============================================================

class HierarchyChunker:
    """
    基于层级标记的分块器

    分块逻辑：
    1. 每个 L1 单独成块（L1本身的内容）
    2. L1 下的每个 L2 单独成块，继承 L1 作为父级
    3. L2 超标 → 按 L3 拆分，继承 L1 + L2
    4. L3 超标 → 按 L4 拆分，继承 L1 + L2 + L3
    """

    def __init__(self, max_tokens_per_chunk: int = 2048):
        self.max_tokens_per_chunk = max_tokens_per_chunk
        self.min_chunk_chars = 10

    @staticmethod
    def _estimate_tokens(text: str) -> int:
        if not text:
            return 0
        chinese_chars = len(re.findall(r'[\u4e00-\u9fff]', text))
        english_words = len(re.findall(r'[a-zA-Z]+', text))
        return int(chinese_chars * 1.8 + english_words * 1.3 + len(text) * 0.1)

    @staticmethod
    def _extract_page_for_block(text: str, block_start_pos: int) -> list[int]:
        pattern = r'\[Page\s+(\d+)\]'
        matches = list(re.finditer(pattern, text))
        for match in reversed(matches):
            if match.start() < block_start_pos:
                return [int(match.group(1))]
        return []

    @staticmethod
    def _clean_page_markers(text: str) -> str:
        return re.sub(r'\[Page\s+\d+\]\s*', '', text)

    @staticmethod
    def _parse_all_headings(text: str) -> list[dict]:
        """解析文档中所有标题及其位置"""
        pattern = r'\[(L[1-5])\]\s*([^\n]+)'
        matches = list(re.finditer(pattern, text))

        headings = []
        for match in matches:
            headings.append({
                "level": match.group(1),
                "title": match.group(2).strip(),
                "pos": match.start()
            })

        return headings

    @staticmethod
    def _get_hierarchy_for_position(all_headings: list[dict], pos: int, current_level: str) -> list[dict]:
        """
        获取某个位置的父级标题
        逐层查找最近的父级标题
        """
        level_num = int(current_level[1])
        parents = []

        # 从高到低逐层查找
        for target_level_num in range(1, level_num):
            candidates = [h for h in all_headings
                          if int(h["level"][1]) == target_level_num and h["pos"] < pos]
            if candidates:
                # 取最近的（位置最大的）
                nearest = max(candidates, key=lambda x: x["pos"])
                parents.append(nearest)

        return parents

    @staticmethod
    def _split_text_by_char(text: str, max_chars: int) -> list[str]:
        """按字符数拆分文本，尽量在换行符处切分（用于单段超大块的兜底拆分）。

        保证每个子块字符数 <= max_chars，避免超过 milvus text 字段 65535 限制
        或 embedding API 单条 token 限制。
        """
        # 防御：max_chars 为 0/负数时会导致 start 不前进、死循环
        max_chars = max(int(max_chars), 1)
        if len(text) <= max_chars:
            return [text]
        parts = []
        start = 0
        while start < len(text):
            end = start + max_chars
            if end >= len(text):
                parts.append(text[start:])
                break
            # 尽量在换行符处切，避免切断单词/中文字符
            nl = text.rfind('\n', start, end)
            if nl > start + max_chars // 2:
                end = nl + 1
            parts.append(text[start:end])
            start = end
        return parts

    @staticmethod
    def _merge_hierarchy(base_hierarchy: list[dict], sub_hierarchy: list[dict]) -> list[dict]:
        """合并两个层级列表，按 (level, title) 去重"""
        seen = set()
        result = []
        for h in base_hierarchy + sub_hierarchy:
            key = (h["level"], h["title"])
            if key not in seen:
                seen.add(key)
                result.append(h)
        return result

    @staticmethod
    def _has_any_real_text(text: str) -> bool:
        """是否存在任何非空真实文本（不设最小长度阈值，用于短文档兜底）

        标题行 [L*] xxx 去掉前缀后的 xxx 也算真实文本，
        这样只有标题无正文的极简文档（如 MEMORY.md 仅含 '# Long-Term Memory'）
        也能通过兜底产出 1 个 chunk 入库，避免分块失败导致入库报错。
        """
        if not text:
            return False
        for line in text.split('\n'):
            line = line.strip()
            if not line:
                continue
            if re.match(r'^\[L[1-5]\]', line):
                # 标题行：去掉 [L*] 前缀，剩余文本非空也算真实文本
                title_text = re.sub(r'^\[L[1-5]\]\s*', '', line).strip()
                if title_text:
                    return True
                continue
            return True
        return False

    def chunk_markdown(self, markdown_content: str, filename: str = "document", source_type: str = "document") -> list[dict]:  # pylint: disable=line-too-long
        """
        分块主函数 - 接收Markdown内容字符串，返回分块列表

        Args:
            markdown_content: Markdown内容
            filename: 文件名
            source_type: 来源类型（pdf, word, ppt）
        """
        _CONTENT_KEY = 'content'
        full_markdown = markdown_content

        logger.info(f"开始分块: {filename}")
        logger.info(f"文档大小: {len(full_markdown)} 字符")

        l1_count = len(re.findall(r'\[L1\]', full_markdown))
        l2_count = len(re.findall(r'\[L2\]', full_markdown))
        l3_count = len(re.findall(r'\[L3\]', full_markdown))
        l4_count = len(re.findall(r'\[L4\]', full_markdown))

        logger.info("检测到层级:")
        logger.info(f"  [L1]: {l1_count} 个")
        logger.info(f"  [L2]: {l2_count} 个")
        logger.info(f"  [L3]: {l3_count} 个")
        logger.info(f"  [L4]: {l4_count} 个")

        logger.info("【步骤1】获取基础块...")
        blocks = self._get_content_blocks(full_markdown)
        logger.info(f"  基础块数: {len(blocks)}")

        logger.info("【步骤2】检查块大小...")
        final_blocks = []
        for block in blocks:
            tokens = self._estimate_tokens(block[_CONTENT_KEY])
            if tokens <= self.max_tokens_per_chunk:
                final_blocks.append(block)
                logger.info(f"  {block.get('title', '无标题')[:30]}... ({tokens} tokens) 通过")
            else:
                logger.warning(f"  {block.get('title', '无标题')[:30]}... ({tokens} tokens) 超标，拆分...")
                sub_blocks = self._split_oversized_block(block, self.max_tokens_per_chunk, full_markdown)
                final_blocks.extend(sub_blocks)
                logger.info(f"     拆分为 {len(sub_blocks)} 个子块")

        if not final_blocks:
            # 兜底：短文档（正文 < min_chunk_chars 被过滤）保留原文入库，避免分块失败导致无法入库
            fallback_content = self._clean_page_markers(full_markdown.strip())
            if self._has_any_real_text(fallback_content):
                logger.warning("常规分块为空，回退为整文档单块入库")
                final_blocks = [{
                    "title": "",
                    _CONTENT_KEY: fallback_content,
                    "level": None,
                    "hierarchy": [],
                    "page_numbers": self._extract_page_for_block(full_markdown, 0),
                    "is_fallback": True,
                }]
            else:
                logger.error("分块失败：文档无实质内容")
                return []

        # 硬保护：chunk 超 milvus text 65535 字符 或 embedding 2048 token 限制，强制按字符拆分
        # 兜底 _split_by_paragraph / _split_oversized_block 仍可能产生的超大块
        MILVUS_TEXT_MAX = 65535
        EMBED_TOKEN_MAX = self.max_tokens_per_chunk  # 2048
        # 按 token 反推字符上限：_estimate_tokens(纯中文) ≈ chars * 1.9
        # 用 max_tokens // 2 保证拆分后纯中文 token 估算 < max_tokens
        # 下限 1：max_tokens 配置过小时不用 1024 抬高（否则会超 token 限制），
        # _split_text_by_char 内部已对 max_chars 做下限保护
        SAFE_CHAR_LIMIT = max(EMBED_TOKEN_MAX // 2, 1)
        hard_split_blocks = []
        for block in final_blocks:
            content = block.get(_CONTENT_KEY, "")
            tokens = self._estimate_tokens(content)
            if len(content) > MILVUS_TEXT_MAX or tokens > EMBED_TOKEN_MAX:
                logger.warning(
                    f"chunk 超限（{len(content)}字符/{tokens}token），强制按字符拆分"
                )
                for sub in self._split_text_by_char(content, SAFE_CHAR_LIMIT):
                    new_block = dict(block)
                    new_block[_CONTENT_KEY] = sub
                    hard_split_blocks.append(new_block)
            else:
                hard_split_blocks.append(block)
        final_blocks = hard_split_blocks

        final_chunks = []
        for i, block in enumerate(final_blocks):
            content = block.get(_CONTENT_KEY, "")
            if not content:
                continue
            # 兜底块已通过 _has_any_real_text 检查，此处用宽松标准，
            # 避免短文档被 min_chunk_chars 二次过滤再次丢失
            if block.get("is_fallback"):
                if not self._has_any_real_text(content):
                    continue
            else:
                if not self._has_real_content(content):
                    continue

            metadata = self._build_metadata(block, filename, source_type)
            metadata["block_index"] = i + 1

            final_chunks.append({
                "block_id": f"chunk_{i + 1:04d}",
                _CONTENT_KEY: content,
                "metadata": metadata
            })

        # 最后兜底：所有块被严格过滤后若为空，保留首个有真实文本的块，
        # 避免短文档（基础块数 >=1 但内容 < min_chunk_chars）不入库
        if not final_chunks and final_blocks:
            for block in final_blocks:
                content = block.get(_CONTENT_KEY, "")
                if content and self._has_any_real_text(content):
                    metadata = self._build_metadata(block, filename, source_type)
                    metadata["block_index"] = 1
                    final_chunks.append({
                        "block_id": "chunk_0001",
                        _CONTENT_KEY: content,
                        "metadata": metadata
                    })
                    logger.warning(f"常规分块全部被严格过滤丢弃，保留首个有真实文本的块（短文档兜底）: {len(content)} 字符")
                    break

        logger.info(f"成功分块，共 {len(final_chunks)} 个块")
        return final_chunks

    def _split_by_level(self, full_text: str, target_level: str, start_pos: int = _DEFAULT_START_POS, end_pos: Optional[int] = None) -> list[dict]:
        """按指定层级切分"""
        _TITLE_KEY = 'title'
        _CONTENT_KEY = 'content'
        _LEVEL_KEY = 'level'
        _HIERARCHY_KEY = 'hierarchy'
        _PAGE_NUMBERS_KEY = 'page_numbers'
        _POS_KEY = 'pos'
        if end_pos is None:
            end_pos = len(full_text)

        text = full_text[start_pos:end_pos]
        all_headings = self._parse_all_headings(full_text)
        local_headings = self._parse_all_headings(text)

        if not local_headings:
            return [{
                _TITLE_KEY: "",
                _CONTENT_KEY: self._clean_page_markers(text.strip()),
                _LEVEL_KEY: None,
                _HIERARCHY_KEY: [],
                _PAGE_NUMBERS_KEY: self._extract_page_for_block(full_text, start_pos)
            }]

        target_headings = [h for h in local_headings if h["level"] == target_level]

        if not target_headings:
            levels = list(set(h[_LEVEL_KEY] for h in local_headings))
            if not levels:
                return [{
                    _TITLE_KEY: "",
                    _CONTENT_KEY: self._clean_page_markers(text.strip()),
                    _LEVEL_KEY: None,
                    _HIERARCHY_KEY: [],
                    _PAGE_NUMBERS_KEY: self._extract_page_for_block(full_text, start_pos)
                }]
            lowest = sorted(levels)[-1]
            target_headings = [h for h in local_headings if h[_LEVEL_KEY] == lowest]

        blocks = []
        current_level_num = int(target_level[1])

        for i, h in enumerate(target_headings):
            actual_start = start_pos + h[_POS_KEY]

            if i + 1 < len(target_headings):
                actual_end = start_pos + target_headings[i + 1][_POS_KEY]
            else:
                actual_end = end_pos

            # 检查中间是否有更高层级的标题
            for h2 in local_headings:
                h2_level_num = int(h2[_LEVEL_KEY][1])
                h2_actual_pos = start_pos + h2[_POS_KEY]
                if h2_level_num < current_level_num and h2_actual_pos > actual_start and h2_actual_pos < actual_end:
                    actual_end = h2_actual_pos
                    break

            content = full_text[actual_start:actual_end].strip()
            hierarchy = self._get_hierarchy_for_position(all_headings, actual_start, target_level)
            page_numbers = self._extract_page_for_block(full_text, actual_start)
            clean_content = self._clean_page_markers(content)

            blocks.append({
                _TITLE_KEY: h[_TITLE_KEY],
                _CONTENT_KEY: clean_content,
                _LEVEL_KEY: h[_LEVEL_KEY],
                "start_pos": actual_start,
                "end_pos": actual_end,
                _HIERARCHY_KEY: hierarchy,
                _PAGE_NUMBERS_KEY: page_numbers
            })

        return blocks

    def _split_by_paragraph(self, block: dict, max_tokens: int) -> list[dict]:
        content = block["content"]
        paragraphs = content.split('\n\n')

        blocks = []
        current_parts = []
        current_tokens = 0
        title = block.get("title", "")
        hierarchy = block.get("hierarchy", [])
        level = block.get("level")
        page_numbers = block.get("page_numbers", [])

        def _make_block(parts):
            return {
                "title": title,
                "content": '\n\n'.join(parts),
                "level": level,
                "hierarchy": hierarchy,
                "page_numbers": page_numbers,
                "is_paragraph_split": True
            }

        # 单段超大块按字符拆分的字符上限：
        # _estimate_tokens(纯中文) ≈ chars * 1.9，所以 max_chars = max_tokens / 2
        # 保证拆分后每个子块 token 估算 <= max_tokens
        # 下限 1（而非 1024）：当 max_tokens 被配成 <2048 时，1024 字符纯中文
        # 已超 max_tokens，反而违反限制。_split_text_by_char 内部已对 max_chars 做下限保护
        char_limit = max(max_tokens // 2, 1)

        for para in paragraphs:
            para = para.strip()
            if not para:
                continue

            para_tokens = self._estimate_tokens(para)

            # 单段超大块（自身超过 max_tokens）→ 先提交累积块，再按字符强制拆分
            # 避免 _split_by_paragraph 对无 \n\n 分隔的超长段落返回原样超大块
            if para_tokens > max_tokens:
                if current_parts:
                    blocks.append(_make_block(current_parts))
                    current_parts = []
                    current_tokens = 0
                for sub in self._split_text_by_char(para, char_limit):
                    blocks.append(_make_block([sub]))
            elif current_tokens + para_tokens <= max_tokens:
                current_parts.append(para)
                current_tokens += para_tokens
            else:
                if current_parts:
                    blocks.append(_make_block(current_parts))
                current_parts = [para]
                current_tokens = para_tokens

        if current_parts:
            blocks.append(_make_block(current_parts))

        return blocks if blocks else [block]

    def _split_oversized_block(self, block: dict, max_tokens: int, full_text: Optional[str] = None, depth: int = _DEFAULT_DEPTH) -> list[dict]:
        # 深度兜底：正常最多 4 层（L1→L5），超过说明层级拆分未生效，走段落拆分避免无限递归
        _HIERARCHY_KEY = 'hierarchy'
        _PAGE_NUMBERS_KEY = 'page_numbers'
        _CONTENT_KEY = 'content'
        if depth > 5:
            para_blocks = self._split_by_paragraph(block, max_tokens)
            for para in para_blocks:
                para[_HIERARCHY_KEY] = block.get(_HIERARCHY_KEY, [])
                para[_PAGE_NUMBERS_KEY] = block.get(_PAGE_NUMBERS_KEY, [])
            return para_blocks

        if full_text is None:
            full_text = block.get(_CONTENT_KEY, "")

        content = block[_CONTENT_KEY]
        current_level = block.get("level")
        hierarchy = block.get(_HIERARCHY_KEY, [])
        page_numbers = block.get(_PAGE_NUMBERS_KEY, [])

        next_level_map = {
            "L1": "L2",
            "L2": "L3",
            "L3": "L4",
            "L4": "L5",
            "L5": None
        }

        next_level = next_level_map.get(current_level)

        if next_level and any(f'[{next_level}]' in line for line in content.split('\n')):
            start_pos = block.get("start_pos", 0)
            end_pos = block.get("end_pos", len(full_text))

            sub_blocks = self._split_by_level(full_text, next_level, start_pos, end_pos)
            result = []
            for sub in sub_blocks:
                # 使用去重合并
                sub[_HIERARCHY_KEY] = self._merge_hierarchy(hierarchy, sub.get(_HIERARCHY_KEY, []))
                sub[_PAGE_NUMBERS_KEY] = page_numbers + sub.get(_PAGE_NUMBERS_KEY, [])
                sub_tokens = self._estimate_tokens(sub[_CONTENT_KEY])
                if sub_tokens <= max_tokens:
                    result.append(sub)
                else:
                    result.extend(self._split_oversized_block(sub, max_tokens, full_text, depth + 1))
            return result
        else:
            para_blocks = self._split_by_paragraph(block, max_tokens)
            for para in para_blocks:
                para[_HIERARCHY_KEY] = hierarchy
                para[_PAGE_NUMBERS_KEY] = page_numbers
            return para_blocks

    def _has_real_content(self, text: str) -> bool:
        if not text:
            return False

        lines = text.split('\n')
        real_lines = []
        for line in lines:
            line = line.strip()
            if not line:
                continue
            if re.match(r'^\[L[1-5]\]', line):
                continue
            real_lines.append(line)

        real_content = ' '.join(real_lines)
        return len(real_content) > self.min_chunk_chars

    def _get_content_blocks(self, full_text: str) -> list[dict]:
        """获取基础块"""
        _TITLE_KEY = 'title'
        _L1_KEY = "L1"
        _L2_KEY = "L2"
        _CONTENT_KEY = 'content'
        _LEVEL_KEY = 'level'
        _HIERARCHY_KEY = 'hierarchy'
        _PAGE_NUMBERS_KEY = 'page_numbers'
        _POS_KEY = 'pos'
        all_headings = self._parse_all_headings(full_text)

        if not all_headings:
            return [{
                _TITLE_KEY: "",
                _CONTENT_KEY: self._clean_page_markers(full_text.strip()),
                _LEVEL_KEY: None,
                _HIERARCHY_KEY: [],
                _PAGE_NUMBERS_KEY: self._extract_page_for_block(full_text, _DEFAULT_START_POS)
            }]

        has_l2 = any(h[_LEVEL_KEY] == _L2_KEY for h in all_headings)

        if not has_l2:
            has_l1 = any(h[_LEVEL_KEY] == _L1_KEY for h in all_headings)
            if has_l1:
                blocks = self._split_by_level(full_text, _L1_KEY)
                return [b for b in blocks if self._has_real_content(b[_CONTENT_KEY])]
            else:
                levels = list(set(h[_LEVEL_KEY] for h in all_headings))
                if levels:
                    lowest = sorted(levels)[-1]
                    blocks = self._split_by_level(full_text, lowest)
                    return [b for b in blocks if self._has_real_content(b[_CONTENT_KEY])]
                else:
                    return [{
                        _TITLE_KEY: "",
                        _CONTENT_KEY: self._clean_page_markers(full_text.strip()),
                        _LEVEL_KEY: None,
                        _HIERARCHY_KEY: [],
                        _PAGE_NUMBERS_KEY: self._extract_page_for_block(full_text, _DEFAULT_START_POS)
                    }]

        blocks = []
        l1_headings = [h for h in all_headings if h[_LEVEL_KEY] == _L1_KEY]

        for l1 in l1_headings:
            # 找这个L1下面的L2
            l2_under_l1 = [
                h for h in all_headings
                if h[_LEVEL_KEY] == _L2_KEY and h[_POS_KEY] > l1[_POS_KEY]
            ]
            # 找到这个L1范围结束位置（下一个L1的位置）
            next_l1 = [h for h in all_headings if h[_LEVEL_KEY] == _L1_KEY and h[_POS_KEY] > l1[_POS_KEY]]
            l1_end = next_l1[0][_POS_KEY] if next_l1 else len(full_text)

            # 过滤出属于这个L1的L2
            l2_under_l1 = [h for h in l2_under_l1 if h[_POS_KEY] < l1_end]

            if l2_under_l1:
                # L1本身的内容：从L1标题到第一个L2之前
                first_l2_pos = l2_under_l1[0][_POS_KEY]
                l1_content = full_text[l1[_POS_KEY]:first_l2_pos].strip()
            else:
                # 没有L2，整个L1内容
                l1_content = full_text[l1[_POS_KEY]:l1_end].strip()

            if l1_content and self._has_real_content(l1_content):
                hierarchy = self._get_hierarchy_for_position(all_headings, l1[_POS_KEY], _L1_KEY)
                page_numbers = self._extract_page_for_block(full_text, l1[_POS_KEY])
                clean_content = self._clean_page_markers(l1_content)

                blocks.append({
                    _TITLE_KEY: l1[_TITLE_KEY],
                    _CONTENT_KEY: clean_content,
                    _LEVEL_KEY: _L1_KEY,
                    "start_pos": l1[_POS_KEY],
                    "end_pos": first_l2_pos if l2_under_l1 else l1_end,
                    _HIERARCHY_KEY: hierarchy,
                    _PAGE_NUMBERS_KEY: page_numbers
                })

        # 按L2切分
        l2_blocks = self._split_by_level(full_text, _L2_KEY)

        for l2 in l2_blocks:
            if self._has_real_content(l2[_CONTENT_KEY]):
                blocks.append(l2)

        # 按 start_pos 排序，恢复文档从上到下的原始顺序
        # 上面的收集逻辑先输出所有 L1 自身内容，再输出所有 L2 块，
        # 导致 chunk_index 编号不对应文档顺序。按 start_pos 排序可修正。
        blocks.sort(key=lambda b: b.get("start_pos", 0))

        return blocks

    def _build_metadata(self, block: dict, filename: str, source_type: str = _DEFAULT_SOURCE_TYPE) -> dict:
        _LEVEL_KEY = 'level'
        _CONTENT_KEY = 'content'
        _HIERARCHY_KEY = 'hierarchy'
        _PAGE_NUMBERS_KEY = 'page_numbers'
        _TITLE_KEY = 'title'
        _TEXT_KEY = 'text'
        content = block.get(_CONTENT_KEY, "")
        hierarchy = block.get(_HIERARCHY_KEY, [])
        page_numbers = block.get(_PAGE_NUMBERS_KEY, [])

        hierarchy_path = []
        parent_titles = []
        for h in hierarchy:
            level = h.get(_LEVEL_KEY, "")
            title = h.get(_TITLE_KEY, "")
            hierarchy_path.append({_LEVEL_KEY: level, _TITLE_KEY: title})
            parent_titles.append(title)

        current_title = block.get(_TITLE_KEY, "")
        current_level = block.get(_LEVEL_KEY, "")

        block_type = _TEXT_KEY
        if re.search(r'```[\s\S]*?```', content):
            block_type = "code"
        if re.search(r'\|.*\|.*\|', content):
            block_type = "table" if block_type == _TEXT_KEY else "mixed"
        if re.search(r'!\[.*\]\(.*\)', content):
            block_type = "image" if block_type == _TEXT_KEY else "mixed"

        return {
            "filename": filename,
            "page_numbers": page_numbers,
            "hierarchy": hierarchy_path,
            "hierarchy_titles": parent_titles,
            "current_title": current_title,
            "current_level": current_level,
            "block_type": block_type,
            "token_count": self._estimate_tokens(content),
            "is_paragraph_split": block.get("is_paragraph_split", False),
            "chunk_time": datetime.now(timezone.utc).isoformat(),
            "source_type": source_type
        }
