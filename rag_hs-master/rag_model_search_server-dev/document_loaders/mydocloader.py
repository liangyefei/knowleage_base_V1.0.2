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
Word加载器，用于加载Word文件。
TODO CK 
1. 改名去除rapidocrxxx改成vlmxxx, 注意参数从configs导入。
2. word/pdf/ppt格式能否抽象代码，让复用程度更高？尝试一下。
"""


# ============================================================
# PDF/Word转Markdown提取器 - 支持PDF和Word
# ============================================================

class PDFMarkdownExtractor:
    """PDF/Word转Markdown提取器 - 输出带层级标记的Markdown，支持PDF和Word"""

    def __init__(
            self,
            api_key: str = None,
            model: str = None,
            endpoint: str = None,
            batch_size: int = 3,
            max_retries: int = 3,
            retry_delay: int = 5,
            model_name: str = "qwen3.7-plus",
            max_workers: int = 4,
            dpi: int = 200
    ):
        if model_name:
            config = get_vlm_config(model_name)
            if not config:
                raise ValueError(f"配置中找不到 VLM 模型: {model_name}")
            self.api_key = config.get('api_key')
            self.model = config.get('model_id')
            self.endpoint = config.get('api_base_url')
            self.max_retries = config.get('max_retries', max_retries)
        else:
            # 兼容旧方式：使用传入的参数
            self.api_key = api_key
            self.model = model
            self.endpoint = endpoint
            self.max_retries = max_retries

        self.batch_size = batch_size
        self.retry_delay = retry_delay
        self.max_workers = max_workers
        self.dpi = dpi

        self.headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json"
        }

    @staticmethod
    def create_extract_prompt(images_batch: list[str], page_numbers: list[int],
                              filename: str) -> list[dict]:
        """构建Markdown提取提示词 - 包含详细图片描述"""
        _CONTENT_KEY = 'content'

        # ✅ 从配置文件加载提示词模板（Word 和 PDF 使用相同的模板）
        system_prompt = get_prompt("word_extractor", "system_prompt")
        user_template_str = get_prompt("word_extractor", "user_template")

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
    def _is_word_file(file_path: str) -> bool:
        """判断是否为Word文件"""
        ext = Path(file_path).suffix.lower()
        return ext in {'.doc', '.docx'}

    @staticmethod
    def _convert_word_to_pdf(word_path: str) -> str:
        """
        使用LibreOffice将Word转换为PDF
        返回PDF文件路径
        """
        word_path = Path(word_path)

        if not word_path.exists():
            raise FileNotFoundError(f"Word文件不存在: {word_path}")

        # 检查文件扩展名
        valid_extensions = {'.doc', '.docx'}
        if word_path.suffix.lower() not in valid_extensions:
            raise ValueError(f"不支持的文件格式: {word_path.suffix}，仅支持 {valid_extensions}")

        # 使用临时目录
        output_dir = Path(tempfile.gettempdir())
        # 使用 pid + uuid 唯一命名，避免并发处理同名文件时互相覆盖/被清理
        pdf_path = output_dir / f"{word_path.stem}_{os.getpid()}_{uuid.uuid4().hex[:8]}.pdf"

        # 如果已存在则删除（确保重新生成）
        if pdf_path.exists():
            pdf_path.unlink()

        logger.info(f"正在转换 Word → PDF: {word_path.name}")

        try:
            # 使用LibreOffice转换
            libreoffice_bin = shutil.which("libreoffice") or "libreoffice"
            result = subprocess.run([
                libreoffice_bin,
                '--headless',
                '--convert-to', 'pdf',
                '--outdir', str(output_dir),
                str(word_path)
            ], capture_output=True, text=True, timeout=120)

            # 检查转换结果
            if pdf_path.exists():
                logger.info(f"PDF转换成功: {pdf_path.name}")
                return str(pdf_path)

            # 备用路径：LibreOffice 实际用源文件 stem 命名输出 PDF，可能在 outdir 或源文件目录
            fallback_pdf = output_dir / f"{word_path.stem}.pdf"
            if fallback_pdf.exists():
                fallback_pdf.rename(pdf_path)
                logger.info(f"PDF转换成功 (备用路径): {pdf_path.name}")
                return str(pdf_path)

            backup_pdf = word_path.parent / f"{word_path.stem}.pdf"
            if backup_pdf.exists():
                # 移动到目标目录
                backup_pdf.rename(pdf_path)
                logger.info(f"PDF转换成功 (备用路径): {pdf_path.name}")
                return str(pdf_path)

            logger.error(f"PDF转换失败: {result.stderr}")
            raise RuntimeError(f"PDF转换失败: {result.stderr}")

        except subprocess.TimeoutExpired:
            raise TimeoutError("Word转PDF超时（超过120秒）") from None
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
        支持 PDF 和 Word 文档
        返回: 完整Markdown

        - 并行：所有批次并发调用 VLM，不再依赖 previous_context（batch N 不依赖 batch N-1 结果）
        - 流式渲染：每个 worker 独立 fitz.open 只渲染自己的批次，内存恒定 ≈ max_workers × batch_size 张图片
        """
        file_path = Path(file_path)
        filename = file_path.stem

        # 判断是否需要转换Word为PDF
        pdf_path_to_use = None
        is_word = self._is_word_file(str(file_path))

        if is_word:
            logger.info(f"检测到Word文档: {file_path.name}")
            logger.info("正在转换为PDF...")
            pdf_path_to_use = self._convert_word_to_pdf(str(file_path))
            logger.info(f"Word已转换为PDF: {Path(pdf_path_to_use).name}")
        else:
            pdf_path_to_use = str(file_path)

        try:
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

            if is_word:
                logger.info("📌 注意: 源文件为Word文档，已转换为PDF后解析")

            return full_markdown

        finally:
            # 如果是Word文档转换的PDF，清理临时文件
            if is_word and pdf_path_to_use and Path(pdf_path_to_use).exists():
                try:
                    Path(pdf_path_to_use).unlink()
                    logger.info(f"已清理临时PDF: {Path(pdf_path_to_use).name}")
                except OSError:
                    logger.debug("临时 PDF 清理失败（可能已被删除）")
                    pass


# ============================================================
# RapidOCRPDFLoader - 主加载器（支持PDF和Word）
# ============================================================

class VLLMOCRPDFLoader(UnstructuredFileLoader):
    """
    整合后的PDF/Word加载器
    使用多模态大模型解析文档，使用层级分块器进行分块
    支持并发处理
    """

    def __init__(
            self,
            file_path: str,
            api_key: str = None,
            model: str = None,
            endpoint: str = None,
            model_name: str = "qwen3.7-plus",
            max_tokens_per_chunk: int = 2048,
            max_workers: int = 1,
            **kwargs
    ):
        """
        初始化加载器

        Args:
            file_path: 文件路径（PDF或Word）
            api_key: API密钥
            model: 模型名称
            endpoint: API端点
            model_name: 模型名称（从配置加载）
            max_tokens_per_chunk: 每个分块的最大token数
            max_workers: 并发处理数量，默认1（串行）
            **kwargs: 传递给父类的参数
        """
        super().__init__(file_path, **kwargs)

        self.api_key = api_key
        self.model = model
        self.endpoint = endpoint
        self.max_tokens_per_chunk = max_tokens_per_chunk
        self.max_workers = max_workers
        self.model_name = model_name

        # 初始化提取器和分块器（每个实例独立）
        self.extractor = PDFMarkdownExtractor(
            api_key=self.api_key,
            model=self.model,
            endpoint=self.endpoint,
            model_name=self.model_name,
            batch_size=3,
            max_workers=self.max_workers
        )
        self.chunker = HierarchyChunker(max_tokens_per_chunk=self.max_tokens_per_chunk)

    from langchain_core.documents import Document

    @classmethod
    def process_batch(cls, file_paths: list[str], max_workers: int = 3, **kwargs) -> dict[str, list]:
        """
        批量并发处理多个文件（新增方法）
        支持 PDF 和 Word

        Args:
            file_paths: 文件路径列表（PDF或Word）
            max_workers: 最大并发数
            **kwargs: 传递给 RapidOCRPDFLoader 的参数

        Returns:
            Dict[str, List]: {file_path: elements列表}
        """
        results = {}
        total = len(file_paths)
        completed = 0

        logger.info(f"{'=' * 60}")
        logger.info(f"开始批量处理 {total} 个文件，并发数: {max_workers}")
        logger.info(f"{'=' * 60}")

        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            future_to_path = {}
            for file_path in file_paths:
                loader = cls(file_path=file_path, max_workers=1,
                             model_name=kwargs.get('model_name', 'qwen3.7-plus'), **kwargs)
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
        """重写 load 方法，直接返回 _get_elements 的结果，避免父类合并"""
        return self._get_elements()

    def _process_single_file(self, file_path: str) -> tuple[str, list]:
        """
        处理单个文件（内部方法，供并发调用）
        支持 PDF 和 Word
        返回: (file_path, elements列表)
        """
        try:
            logger.info(f"{'=' * 60}")
            logger.info(f"开始解析文件: {file_path}")
            logger.info(f"{'=' * 60}")

            # 1. 使用多模态大模型解析（自动识别PDF或Word）
            full_markdown = self.extractor.extract_full_markdown(file_path)

            # 获取文件名
            file_name = Path(file_path).stem.replace(" ", "")

            # 2. 使用层级分块器进行分块
            chunks = self.chunker.chunk_markdown(full_markdown, file_name, source_type="word")

            logger.info(f"分块完成，共 {len(chunks)} 个块")

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
                        "file_type": Path(file_path).suffix.lower()[1:],  # doc 或 docx
                        "source_type": "word",
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
        """
        重写父类方法 - 返回分块后的元素列表
        保持接口不变，外部调用无感知
        """
        _, elements = self._process_single_file(self.file_path)
        return elements


# ============================================================
# 测试代码
# ============================================================

if __name__ == "__main__":
    # 测试Word（如果有Word文件）
    logger.info("=== 测试Word解析 ===")
    _loader = VLLMOCRPDFLoader(
        file_path="D:/任务/海思知识库项目/测试embedding-reranker模型/data/你的word文档.docx",
        max_workers=1
    )
    docs = _loader.load()
    logger.info(f"加载完成，共 {len(docs)} 个文档片段")


VLLMOCRDocLoader = VLLMOCRPDFLoader
