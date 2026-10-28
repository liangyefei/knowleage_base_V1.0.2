#!/usr/bin/env python3
# @File : mineru_loader.py
# @Function: MinerU 统一文档加载器 - PDF/Word/PPT → MinerU → Markdown + 图片 → VLLMMarkdownLoader 解析

import os
import shutil
import subprocess
import tempfile
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from langchain.document_loaders.unstructured import UnstructuredFileLoader
from langchain_core.documents import Document

from configs.logger_manager import logger
from document_loaders.base_loader import _save_chunks_to_json
from document_loaders.mymdloader import VLLMMarkdownLoader

"""
MinerU 统一文档加载器

流程：
1. 如果是 Word/PPT → LibreOffice 转 PDF
2. PDF → MinerU API → 下载 markdown + images
3. 调用 VLLMMarkdownLoader 解析 markdown（图片识别 + HTML 转换 + 层级分块）
4. 覆写 metadata 为原始文件信息
5. 返回 Documents

替代原有的 VLLMOCRPDFLoader / VLLMOCRDocLoader / VLLMOCRPPTLoader，
通过 LOADER_DICT 映射 .pdf/.doc/.docx/.ppt/.pptx 到本加载器。
"""

# Word 扩展名
_WORD_EXTS = {'.doc', '.docx'}
# PPT 扩展名
_PPT_EXTS = {'.ppt', '.pptx'}
# 需要转换的扩展名
_CONVERT_EXTS = _WORD_EXTS | _PPT_EXTS


def _convert_to_pdf(file_path: str) -> str:
    """
    使用 LibreOffice 将 Word/PPT 转换为 PDF
    返回 PDF 文件路径（临时文件）
    """
    src_path = Path(file_path)

    if not src_path.exists():
        raise FileNotFoundError(f"文件不存在: {src_path}")

    ext = src_path.suffix.lower()
    valid_exts = _WORD_EXTS | _PPT_EXTS
    if ext not in valid_exts:
        raise ValueError(f"不支持的文件格式: {ext}，仅支持 {valid_exts}")

    output_dir = Path(tempfile.gettempdir())
    # 使用 pid + uuid 唯一命名，避免并发处理同名文件时互相覆盖/被清理
    pdf_path = output_dir / f"{src_path.stem}_{os.getpid()}_{uuid.uuid4().hex[:8]}.pdf"

    # 如果已存在则删除（确保重新生成）
    if pdf_path.exists():
        pdf_path.unlink()

    logger.info(f"正在转换 {ext} → PDF: {src_path.name}")

    # 查找 libreoffice 绝对路径，避免依赖 PATH
    libreoffice_bin = shutil.which("libreoffice") or shutil.which("soffice")
    if not libreoffice_bin:
        raise FileNotFoundError("未找到 LibreOffice，请安装: sudo apt-get install libreoffice")

    try:
        result = subprocess.run([
            libreoffice_bin,
            '--headless',
            '--convert-to', 'pdf',
            '--outdir', str(output_dir),
            str(src_path)
        ], capture_output=True, text=True, timeout=120)

        if pdf_path.exists():
            logger.info(f"PDF 转换成功: {pdf_path.name}")
            return str(pdf_path)

        # 备用路径：LibreOffice 实际用源文件 stem 命名输出 PDF，可能在源文件目录或 outdir
        # 检查 outdir 下按 stem 命名的 PDF（LibreOffice 可能忽略我们的唯一命名期望）
        fallback_pdf = output_dir / f"{src_path.stem}.pdf"
        if fallback_pdf.exists():
            shutil.move(str(fallback_pdf), str(pdf_path))
            logger.info(f"PDF 转换成功 (备用路径): {pdf_path.name}")
            return str(pdf_path)

        # 再检查源文件目录
        backup_pdf = src_path.parent / f"{src_path.stem}.pdf"
        if backup_pdf.exists():
            shutil.move(str(backup_pdf), str(pdf_path))
            logger.info(f"PDF 转换成功 (备用路径): {pdf_path.name}")
            return str(pdf_path)

        logger.error(f"PDF 转换失败: {result.stderr}")
        raise RuntimeError(f"PDF 转换失败: {result.stderr}")

    except subprocess.TimeoutExpired as e:
        raise TimeoutError(f"{ext} 转 PDF 超时（超过120秒）") from e
    except FileNotFoundError as e:
        raise FileNotFoundError("未找到 LibreOffice，请安装: sudo apt-get install libreoffice") from e


class MinerUDocumentLoader(UnstructuredFileLoader):
    """
    MinerU 统一文档加载器

    支持 PDF/Word/PPT，统一走 MinerU 解析 → VLLMMarkdownLoader 分块。
    通过 doc_hash 缓存，同一文件重复入库时跳过 MinerU 调用。
    """

    def __init__(
        self,
        file_path: str,
        model_name: str = "qwen3.7-plus",
        max_tokens_per_chunk: int = 2048,
        max_workers: int = 3,
        **kwargs
    ):
        """
        初始化 MinerU 文档加载器

        Args:
            file_path: 文件路径（PDF/Word/PPT）
            model_name: VLM 模型名称（用于图片识别，从配置加载）
            max_tokens_per_chunk: 每个分块的最大 token 数
            max_workers: VLM 图片识别并发度
            **kwargs: 传递给父类的参数
        """
        super().__init__(file_path, **kwargs)

        self.model_name = model_name
        self.max_tokens_per_chunk = max_tokens_per_chunk
        self.max_workers = max_workers

    @staticmethod
    def _get_source_type(ext: str) -> str:
        """根据扩展名返回 source_type"""
        if ext in _WORD_EXTS:
            return "word"
        elif ext in _PPT_EXTS:
            return "ppt"
        else:
            return "pdf"

    @classmethod
    def process_batch(
        cls,
        file_paths: list[str],
        max_workers: int = 3,
        **kwargs
    ) -> dict[str, list]:
        """批量并发处理多个文件"""
        results = {}
        total = len(file_paths)
        completed = 0

        logger.info(f"{'=' * 60}")
        logger.info(f"开始批量处理 {total} 个文件，并发数: {max_workers}")
        logger.info(f"{'=' * 60}")

        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            future_to_path = {}
            for file_path in file_paths:
                loader = cls(
                    file_path=file_path,
                    max_workers=kwargs.get('max_workers', 3),
                    model_name=kwargs.get('model_name', 'qwen3.7-plus'),
                    **{k: v for k, v in kwargs.items()
                       if k not in ('max_workers', 'model_name')}
                )
                future = executor.submit(loader._process_single_file, file_path)
                future_to_path[future] = file_path

            for future in as_completed(future_to_path):
                file_path = future_to_path[future]
                completed += 1
                try:
                    path, elements = future.result()
                    results[path] = elements
                    logger.info(f"进度: {completed}/{total} - {path} 完成")
                except Exception as e:
                    logger.error(f"{file_path} 处理失败: {e}")
                    results[file_path] = []

        logger.info(f"{'=' * 60}")
        logger.info(f"批量处理完成！成功: {len([r for r in results.values() if r])} 个")
        logger.info(f"{'=' * 60}")

        return results

    def load(self) -> list[Document]:
        """重写 load 方法，直接返回 _get_elements 的结果"""
        return self._get_elements()

    def _process_single_file(self, file_path: str) -> tuple[str, list[Document]]:
        """
        处理单个文件（内部方法）

        返回: (file_path, elements 列表)
        """
        try:
            logger.info(f"{'=' * 60}")
            logger.info(f"开始解析文件 (MinerU): {file_path}")
            logger.info(f"{'=' * 60}")

            original_path = Path(file_path)
            original_ext = original_path.suffix.lower()
            original_stem = original_path.stem
            source_type = self._get_source_type(original_ext)

            # 1. Word/PPT → PDF
            pdf_path = file_path
            is_converted = False
            if original_ext in _CONVERT_EXTS:
                logger.info(f"检测到 {original_ext} 文件，正在转换为 PDF...")
                pdf_path = _convert_to_pdf(file_path)
                is_converted = True
                logger.info(f"已转换为 PDF: {Path(pdf_path).name}")

            try:
                # 2. 调用 MinerU 解析（带缓存）
                #    缓存命中分支使用原始 file_path（永不会被清理）算 hash，
                #    避免临时 PDF 在并发场景下被其他任务 finally 块清理后导致 hash 计算失败。
                from document_loaders.mineru_client import (
                    get_cached_md_path,
                    get_mineru_output_dir,
                    is_mineru_cached,
                    parse_with_mineru,
                )

                # 缓存判断：优先用原始 file_path（对 docx/pptx 等转换前的文件更稳定）
                cache_lookup_path = file_path if is_converted else pdf_path
                if is_mineru_cached(cache_lookup_path):
                    logger.info(f"MinerU 缓存命中，跳过解析: {Path(cache_lookup_path).name}")
                    md_path = get_cached_md_path(cache_lookup_path)
                else:
                    # 缓存未命中：用临时 PDF 调 MinerU，该路径此时一定存在
                    logger.info(f"调用 MinerU 解析: {Path(pdf_path).name}")
                    md_path = parse_with_mineru(pdf_path)

                if md_path is None:
                    output_dir = get_mineru_output_dir(cache_lookup_path)
                    raise RuntimeError(f"MinerU 解析后未找到 markdown 文件: {output_dir}")

                logger.info(f"MinerU markdown 路径: {md_path}")

                # 3. 调用 VLLMMarkdownLoader 解析 markdown
                logger.info("开始调用 VLLMMarkdownLoader 解析 markdown...")
                md_loader = VLLMMarkdownLoader(
                    file_path=str(md_path),
                    model_name=self.model_name,
                    max_tokens_per_chunk=self.max_tokens_per_chunk,
                    max_workers=self.max_workers,
                )
                elements = md_loader.load()

                logger.info(f"VLLMMarkdownLoader 解析完成，共 {len(elements)} 个元素")

                # 4. 覆写 metadata 为原始文件信息
                for doc in elements:
                    doc.metadata["source"] = file_path
                    doc.metadata["file_name"] = original_stem.replace(" ", "")
                    doc.metadata["file_type"] = original_ext[1:]  # 去掉点号
                    doc.metadata["source_type"] = source_type
                    # MinerU 输出无页码信息
                    doc.metadata.setdefault("page_numbers", [])

                # 5. 保存 JSON
                _save_chunks_to_json(file_path, elements)

                logger.info(f"{file_path} 处理完成，共生成 {len(elements)} 个元素")
                return file_path, elements

            finally:
                # 清理临时 PDF（Word/PPT 转换的）
                if is_converted and pdf_path and Path(pdf_path).exists():
                    try:
                        Path(pdf_path).unlink()
                        logger.info(f"已清理临时 PDF: {Path(pdf_path).name}")
                    except Exception as e:
                        logger.warning(f"清理临时 PDF 失败: {Path(pdf_path).name}: {e}")

        except Exception as e:
            logger.error(f"{file_path} 处理失败: {e}")
            import traceback
            traceback.print_exc()
            raise

    def _get_elements(self) -> list:
        """重写父类方法 - 返回分块后的元素列表"""
        _, elements = self._process_single_file(self.file_path)
        return elements


# ============================================================
# 兼容别名：保留原 Loader 类名，兼容外部引用
# ============================================================

VLLMOCRPDFLoader = MinerUDocumentLoader
VLLMOCRDocLoader = MinerUDocumentLoader
VLLMOCRPPTLoader = MinerUDocumentLoader


# ============================================================
# 测试代码
# ============================================================

if __name__ == "__main__":
    logger.info("=== 测试 MinerU PDF 解析 ===")
    _loader = MinerUDocumentLoader(
        file_path="D:/任务/海思知识库项目/解析文档/pdf/Amlogic MBOX现场分析指南.pdf",
        max_workers=3
    )
    docs = _loader.load()
    logger.info(f"加载完成，共 {len(docs)} 个文档片段")
    for i, _doc in enumerate(docs[:3]):
        logger.info(f"\n--- 片段 {i + 1} ---")
        logger.info(f"内容预览: {_doc.page_content[:300]}...")
        logger.info(f"元数据: {_doc.metadata}")
