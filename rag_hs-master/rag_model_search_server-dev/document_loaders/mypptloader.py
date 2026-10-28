# !/usr/bin/env python3
# @File : mypptloader.py
# @Function: PPT加载器 - 使用VLM多模态大模型解析PPT

import base64
import os
import shutil
import subprocess
import tempfile
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import fitz
from jinja2 import Template
from langchain.document_loaders.unstructured import UnstructuredFileLoader

from configs.logger_manager import logger
from configs.model_loader import get_vlm_config

# ✅ 导入 prompt 加载器
from configs.prompt_loader import get_prompt

from document_loaders.base_loader import HierarchyChunker, _save_chunks_to_json, call_vlm_model, get_pdf_page_count

"""
PPT加载器，用于加载PPT文件。
使用 VLM 多模态大模型解析每页幻灯片，生成带层级标记的 Markdown。
流程：PPT → LibreOffice转PDF → PDF转图片 → VLM解析
"""
# """
#     PPT加载器，用于加载PPT文件。
#     TODO CK
#     0. 改成多模态加载器，支持PPT。
#     1. 改名去除rapidocrxxx改成vlmxxx, 注意参数从configs导入。
#     2. word/pdf/ppt格式能否抽象代码，让复用程度更高？尝试一下。
# """


class PPTMarkdownExtractor:
    """
    PPT转Markdown提取器 - 输出带层级标记的Markdown
    流程：PPT → PDF → 图片 → VLM解析
    """

    def __init__(
            self,
            model_name: str = "qwen3.7-plus",
            max_retries: int = 3,
            retry_delay: int = 5,
            batch_size: int = 3,
            max_workers: int = 4,
            dpi: int = 200
    ):
        """
        初始化PPT提取器

        Args:
            model_name: VLM 模型名称（从配置加载）
            max_retries: 最大重试次数
            retry_delay: 重试延迟（秒）
            batch_size: 批量处理页数
            max_workers: 并行处理批次数
            dpi: PDF转图片的DPI
        """
        config = get_vlm_config(model_name)
        if not config:
            raise ValueError(f"配置中找不到 VLM 模型: {model_name}")

        self.api_key = config.get('api_key')
        self.model = config.get('model_id')
        self.endpoint = config.get('api_base_url')
        self.max_retries = config.get('max_retries', max_retries)
        self.retry_delay = retry_delay
        self.batch_size = batch_size
        self.max_workers = max_workers
        self.dpi = dpi

        self.headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json"
        }

    @staticmethod
    def create_extract_prompt(images_batch: list[str], page_numbers: list[int],
                              filename: str) -> list[dict]:
        """构建PPT提取提示词"""
        _CONTENT_KEY = 'content'

        # ✅ 从配置文件加载提示词模板
        system_prompt = get_prompt("ppt_extractor", "system_prompt")
        user_template_str = get_prompt("ppt_extractor", "user_template")

        # 使用 Jinja2 渲染用户模板
        template = Template(user_template_str)
        user_content = template.render(
            page_start=page_numbers[0],
            page_end=page_numbers[-1],
            filename=filename,
            page_numbers=page_numbers,
        )

        messages = [
            {"role": "system", _CONTENT_KEY: system_prompt},
            {"role": "user", _CONTENT_KEY: []}
        ]

        messages[1][_CONTENT_KEY].append({
            "type": "text",
            "text": user_content
        })

        for img_base64 in images_batch:
            messages[1][_CONTENT_KEY].append({
                "type": "image_url",
                "image_url": {
                    "url": f"data:image/png;base64,{img_base64}"
                }
            })

        return messages

    @staticmethod
    def _convert_ppt_to_pdf(ppt_path: str) -> str:
        """
        使用LibreOffice将PPT转换为PDF
        返回PDF文件路径
        """
        ppt_path = Path(ppt_path)

        if not ppt_path.exists():
            raise FileNotFoundError(f"PPT文件不存在: {ppt_path}")

        valid_extensions = {'.ppt', '.pptx'}
        if ppt_path.suffix.lower() not in valid_extensions:
            raise ValueError(f"不支持的文件格式: {ppt_path.suffix}，仅支持 {valid_extensions}")

        output_dir = Path(tempfile.gettempdir())
        # 使用 pid + uuid 唯一命名，避免并发处理同名文件时互相覆盖/被清理
        pdf_path = output_dir / f"{ppt_path.stem}_{os.getpid()}_{uuid.uuid4().hex[:8]}.pdf"

        if pdf_path.exists():
            pdf_path.unlink()

        logger.info(f"正在转换 PPT → PDF: {ppt_path.name}")

        try:
            libreoffice_bin = shutil.which("libreoffice") or "libreoffice"
            result = subprocess.run([
                libreoffice_bin,
                '--headless',
                '--convert-to', 'pdf',
                '--outdir', str(output_dir),
                str(ppt_path)
            ], capture_output=True, text=True, timeout=120)

            if pdf_path.exists():
                logger.info(f"PDF转换成功: {pdf_path.name}")
                return str(pdf_path)

            # 备用路径：LibreOffice 实际用源文件 stem 命名输出 PDF，可能在 outdir 或源文件目录
            fallback_pdf = output_dir / f"{ppt_path.stem}.pdf"
            if fallback_pdf.exists():
                fallback_pdf.rename(pdf_path)
                logger.info(f"PDF转换成功 (备用路径): {pdf_path.name}")
                return str(pdf_path)

            backup_pdf = ppt_path.parent / f"{ppt_path.stem}.pdf"
            if backup_pdf.exists():
                backup_pdf.rename(pdf_path)
                logger.info(f"PDF转换成功 (备用路径): {pdf_path.name}")
                return str(pdf_path)

            logger.error(f"PDF转换失败: {result.stderr}")
            raise RuntimeError(f"PDF转换失败: {result.stderr}")

        except subprocess.TimeoutExpired:
            raise TimeoutError("PPT转PDF超时（超过120秒）") from None
        except FileNotFoundError:
            raise FileNotFoundError("未找到LibreOffice，请安装: sudo apt-get install libreoffice") from None

    def call_model(self, messages: list[dict]) -> str:
        """调用多模态模型API"""
        return call_vlm_model(
            endpoint=self.endpoint,
            headers=self.headers,
            model=self.model,
            messages=messages,
            max_retries=self.max_retries,
            retry_delay=self.retry_delay
        )

    def extract_batch(self, images: list[str], page_numbers: list[int],
                      filename: str) -> tuple[str, int]:
        """提取一个批次的Markdown"""
        messages = self.create_extract_prompt(images, page_numbers, filename)
        markdown = self.call_model(messages)
        return markdown, page_numbers[-1]

    def extract_full_markdown(self, file_path: str) -> str:
        """
        提取完整的带层级标记的Markdown（并行 + 无批次依赖）
        支持 PPT 和 PPTX 文档
        流程：PPT → PDF → 图片 → VLM解析
        返回: 完整Markdown

        - 并行：所有批次并发调用 VLM，不再依赖 previous_context（batch N 不依赖 batch N-1 结果）
        - 流式渲染：每个 worker 独立 fitz.open 只渲染自己的批次，内存恒定 ≈ max_workers × batch_size 张图片
        """
        file_path = Path(file_path)
        filename = file_path.stem

        logger.info(f"检测到PPT文档: {file_path.name}")
        logger.info("正在转换为PDF...")

        # 步骤1: PPT → PDF
        pdf_path_to_use = self._convert_ppt_to_pdf(str(file_path))
        logger.info(f"PPT已转换为PDF: {Path(pdf_path_to_use).name}")

        try:
            # 步骤2: 获取总页数（流式渲染，不全量加载图片到内存）
            total_pages = get_pdf_page_count(pdf_path_to_use)
            logger.info(f"总页数: {total_pages}")

            # 预计算所有批次范围
            all_ranges = []
            for start in range(0, total_pages, self.batch_size):
                end = min(start + self.batch_size, total_pages)
                all_ranges.append((start, end))
            total_batches = len(all_ranges)

            logger.info(
                f"并行处理: {file_path.name}, 共 {total_pages} 页, "
                f"{total_batches} 批次, max_workers={self.max_workers}"
            )

            # 每个 worker 独立 fitz.open 只渲染自己的批次，流式渲染避免全量加载内存爆炸
            def process_one_batch(batch_idx, start_page, end_page):
                doc = fitz.open(pdf_path_to_use)
                try:
                    mat = fitz.Matrix(self.dpi / 72, self.dpi / 72)
                    batch_images = []
                    for p in range(start_page, end_page):
                        pix = doc[p].get_pixmap(matrix=mat)
                        batch_images.append(base64.b64encode(pix.tobytes("png")).decode("utf-8"))
                        del pix
                finally:
                    doc.close()
                page_numbers = list(range(start_page + 1, end_page + 1))
                markdown, _ = self.extract_batch(batch_images, page_numbers, filename)
                return batch_idx, markdown

            results = {}
            with ThreadPoolExecutor(max_workers=self.max_workers) as pool:
                futures = {
                    pool.submit(process_one_batch, idx, start, end): idx
                    for idx, (start, end) in enumerate(all_ranges, start=1)
                }
                for future in as_completed(futures):
                    batch_idx = futures[future]
                    try:
                        _, markdown = future.result()
                        results[batch_idx] = markdown
                        logger.info(f"批次 {batch_idx}/{total_batches} 完成")
                    except Exception as e:
                        logger.error(f"批次 {batch_idx}/{total_batches} 失败: {e}")
                        raise

            # 按顺序拼接结果
            all_markdown_parts = [results.get(idx, "") for idx in range(1, total_batches + 1)]
            full_markdown = "\n\n".join(all_markdown_parts)

            logger.info(f"Markdown提取完成！共 {total_pages} 页，{total_batches} 个批次")
            logger.info(f"Markdown总长度: {len(full_markdown)} 字符")

            return full_markdown

        finally:
            # 清理临时PDF
            if pdf_path_to_use and Path(pdf_path_to_use).exists():
                try:
                    Path(pdf_path_to_use).unlink()
                    logger.info(f"已清理临时PDF: {Path(pdf_path_to_use).name}")
                except OSError:
                    logger.debug("临时 PDF 清理失败（可能已被删除）")
                    pass


# ============================================================
# VLLMOCRPPTLoader - 主加载器
# ============================================================

class VLLMOCRPPTLoader(UnstructuredFileLoader):
    """
    PPT加载器 - 使用VLM多模态大模型解析PPT
    流程：PPT → PDF → 图片 → VLM解析 → 层级分块
    与 PDF/Word 解析方式对齐
    """

    def __init__(
            self,
            file_path: str,
            model_name: str = "qwen3.7-plus",
            max_tokens_per_chunk: int = 2048,
            max_workers: int = 1,
            **kwargs
    ):
        super().__init__(file_path, **kwargs)

        self.model_name = model_name
        self.max_tokens_per_chunk = max_tokens_per_chunk
        self.max_workers = max_workers

        self.extractor = PPTMarkdownExtractor(
            model_name=model_name,
            batch_size=3,
            max_workers=self.max_workers
        )
        self.chunker = HierarchyChunker(max_tokens_per_chunk=max_tokens_per_chunk)

    from langchain_core.documents import Document

    @classmethod
    def process_batch(cls, file_paths: list[str], max_workers: int = 3, **kwargs) -> dict[str, list]:
        """批量并发处理多个PPT文件"""
        results = {}
        total = len(file_paths)
        completed = 0

        logger.info(f"{'=' * 60}")
        logger.info(f"开始批量处理 {total} 个PPT文件，并发数: {max_workers}")
        logger.info(f"{'=' * 60}")

        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            future_to_path = {}
            for file_path in file_paths:
                loader = cls(
                    file_path=file_path,
                    max_workers=1,
                    model_name=kwargs.get('model_name', 'qwen3.7-plus'),
                    **kwargs
                )
                future = executor.submit(loader._process_single_ppt, file_path)
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
        """重写 load 方法，直接返回 _get_elements 的结果，避免父类合并"""
        return self._get_elements()

    def _process_single_ppt(self, file_path: str) -> tuple[str, list]:
        """处理单个PPT文件"""
        try:
            logger.info(f"{'=' * 60}")
            logger.info(f"开始解析PPT: {file_path}")
            logger.info(f"{'=' * 60}")

            # 步骤1-3: PPT → PDF → 图片 → VLM解析 → Markdown
            full_markdown = self.extractor.extract_full_markdown(file_path)

            file_name = Path(file_path).stem.replace(" ", "")

            # 步骤4: 层级分块
            chunks = self.chunker.chunk_markdown(full_markdown, file_name, source_type="ppt")

            logger.info(f"分块完成，共 {len(chunks)} 个块")

            # 步骤5: 转换为unstructured可识别的格式
            import re

            from langchain_core.documents import Document

            elements = []
            for chunk in chunks:
                content = chunk["content"]
                metadata = chunk["metadata"]

                # ============ 过滤目录块 ============
                if '[L2] 目录' in content:
                    continue
                # ====================================

                # ============ 删除 [L*] 和 [Page *] 标记 ============
                # 删除 [L1], [L2], [L3], [L4], [L5] 等
                content = re.sub(r'\[L[1-5]\]\s*', '', content)
                # 删除 [Page 1], [Page 2] 等
                content = re.sub(r'\[Page\s*\d+\]\s*', '', content)
                # ====================================================

                chunk_text = content
                doc = Document(
                    page_content=chunk_text,
                    metadata={
                        "source": file_path,
                        "file_name": file_name,
                        "file_type": Path(file_path).suffix.lower()[1:],  # ppt 或 pptx
                        "source_type": "ppt",
                        "chunk_index": len(elements) + 1,
                        "total_chunks": len(chunks),
                        "page_numbers": metadata.get('page_numbers', []),
                        "titles": metadata.get('hierarchy_titles', []),
                        "chunk_type": metadata.get('block_type', 'text'),
                    }
                )
                elements.append(doc)

            # ============ 保存 JSON ============
            _save_chunks_to_json(file_path, elements)
            # ==================================

            logger.info(f"{file_path} 处理完成，共生成 {len(elements)} 个元素")
            return file_path, elements

        except Exception as e:
            logger.error(f"{file_path} 处理失败: {e}")
            import traceback
            traceback.print_exc()
            raise

    def _get_elements(self) -> list:
        _, elements = self._process_single_ppt(self.file_path)
        return elements


# ============================================================
# 测试代码
# ============================================================

if __name__ == "__main__":
    logger.info("=== 测试PPT解析 ===")
    _loader = VLLMOCRPPTLoader(
        file_path="../tests/samples/ocr_test.pptx"
    )
    docs = _loader.load()
    logger.info(f"加载完成，共 {len(docs)} 个文档片段")

    for i, _doc in enumerate(docs[:3]):
        logger.info(f"\n--- 片段 {i + 1} ---")
        logger.info(f"内容预览: {_doc.page_content[:300]}...")
        if hasattr(_doc, 'metadata'):
            logger.info(f"元数据: {_doc.metadata}")
