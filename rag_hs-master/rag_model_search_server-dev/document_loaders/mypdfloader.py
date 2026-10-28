import base64
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import fitz
from jinja2 import Template
from langchain.document_loaders.unstructured import UnstructuredFileLoader

from configs.logger_manager import logger
from configs.model_loader import get_vlm_config
from configs.prompt_loader import get_prompt

from document_loaders.base_loader import (
    HierarchyChunker,
    PDFBatchCache,
    _save_chunks_to_json,
    call_vlm_model,
    get_pdf_page_count,
    release_memory,
)

"""
PDF加载器，用于加载PDF文件。
TODO CK 
1. 改名去除rapidocrxxx改成vlmxxx, 注意参数从configs导入。
2. word/pdf/ppt格式能否抽象代码，让复用程度更高？尝试一下。
"""


# ============================================================
# PDF转Markdown提取器 - 完整保留你的代码
# ============================================================

# 全局锁：release_memory() 内部 gc.collect() 非完全线程安全，多 worker 并发调用需串行化
_release_memory_lock = threading.Lock()


class PDFMarkdownExtractor:
    """PDF转Markdown提取器 - 输出带层级标记的Markdown"""

    def __init__(
            self,
            api_key: str = None,  # 改为可选
            model: str = None,  # 改为可选
            endpoint: str = None,  # 改为可选
            batch_size: int = 3,
            max_retries: int = 3,
            retry_delay: int = 5,
            model_name: str = "qwen3.7-plus",
            dpi: int = 200,
            max_workers: int = 4,  # 批次并行度，默认 4 路并发
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
        self.dpi = dpi
        self.max_workers = max_workers

        self.headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json"
        }

    @staticmethod
    def create_extract_prompt(images_batch: list[str], page_numbers: list[int],
                              filename: str) -> list[dict]:
        """构建Markdown提取提示词 - 包含详细图片描述"""
        _CONTENT_KEY = 'content'
        system_prompt = get_prompt("pdf_extractor", "system_prompt")
        user_template_str = get_prompt("pdf_extractor", "user_template")
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

    def extract_full_markdown(self, pdf_path: str) -> str:
        """
        提取完整的带层级标记的Markdown（并行 + 断点续跑 + 周期回收内存）

        - 并行：所有批次并发调用 VLM，不再依赖 previous_context（batch N 不依赖 batch N-1 结果）
        - 断点续跑：每批 VLM 输出原子落盘；异常重投/重试时跳过已完成批次
        - 内存回收：每 50 批 release_memory()，缓解 PyMuPDF C 堆碎片导致的 RSS 不回落
        """
        filename = Path(pdf_path).stem

        cache = PDFBatchCache(
            pdf_path=pdf_path,
            batch_size=self.batch_size,
            dpi=self.dpi,
            model=self.model,
        )
        total_pages = get_pdf_page_count(pdf_path)
        cache.init(total_pages)

        # 预计算所有页面范围（仅页码，不渲染图片）
        all_ranges = []
        for start in range(0, total_pages, self.batch_size):
            end = min(start + self.batch_size, total_pages)
            all_ranges.append((start, end))
        total_batches = len(all_ranges)

        # 检查缓存，恢复已完成批次
        last_completed = cache.find_last_completed_batch()
        all_markdown_parts = []

        if last_completed > 0:
            # 安全加载：缺口处停止并把 last_completed 回退到缺口前，
            # 让 remaining 接管缺口及之后批次的重新处理（兜底 find 后文件被删的竞态）
            for i in range(1, last_completed + 1):
                if not cache.has_batch(i):
                    last_completed = i - 1
                    logger.warning(
                        f"缓存批次 {i}/{total_batches} 缺失，从该批次重新处理"
                    )
                    break
                all_markdown_parts.append(cache.load_batch(i))

        # 剩余待处理批次（排除缺口后已缓存的孤儿批次，避免重复调用 VLM）
        remaining = [
            (idx, start, end)
            for idx, (start, end) in enumerate(all_ranges, start=1)
            if idx > last_completed and not cache.has_batch(idx)
        ]
        orphan_count = (total_batches - last_completed) - len(remaining)

        if last_completed > 0:
            logger.info(
                f"断点续跑：已缓存 {last_completed}/{total_batches} 批"
                + (f"，孤儿缓存 {orphan_count} 批" if orphan_count > 0 else "")
                + f"，继续并行处理剩余 {len(remaining)} 批"
            )
        else:
            logger.info(
                f"并行处理 PDF: {pdf_path}, 共 {total_pages} 页, "
                f"{total_batches} 批次, max_workers={self.max_workers}"
            )

        if remaining:
            # 每个 worker 独立渲染自己的批次 + 调用 VLM，完全并行无依赖
            def process_one_batch(batch_idx, start_page, end_page):
                # 每个 worker 独立打开 PDF（fitz 不支持跨线程共享 Document）
                doc = fitz.open(pdf_path)
                try:
                    mat = fitz.Matrix(self.dpi / 72, self.dpi / 72)
                    batch_images = []
                    page_numbers = []
                    for p in range(start_page, end_page):
                        pix = doc[p].get_pixmap(matrix=mat)
                        batch_images.append(
                            base64.b64encode(pix.tobytes("png")).decode("utf-8")
                        )
                        page_numbers.append(p + 1)
                        del pix
                finally:
                    doc.close()

                markdown, _ = self.extract_batch(
                    batch_images, page_numbers, filename
                )
                cache.save_batch(batch_idx, markdown)

                if batch_idx % 50 == 0:
                    with _release_memory_lock:
                        release_memory()

                return batch_idx, markdown

            results = {}
            with ThreadPoolExecutor(max_workers=self.max_workers) as pool:
                futures = {
                    pool.submit(process_one_batch, idx, start, end): idx
                    for idx, start, end in remaining
                }
                for future in as_completed(futures):
                    batch_idx = futures[future]
                    try:
                        _, markdown = future.result()
                        results[batch_idx] = markdown
                        logger.info(
                            f"批次 {batch_idx}/{total_batches} 完成"
                        )
                    except Exception as e:
                        logger.error(
                            f"批次 {batch_idx}/{total_batches} 失败: {e}"
                        )
                        raise

            # 按顺序拼接结果：缺口后可能混有已缓存的孤儿批次，优先从缓存读
            for idx in range(last_completed + 1, total_batches + 1):
                if cache.has_batch(idx):
                    all_markdown_parts.append(cache.load_batch(idx))
                elif idx in results:
                    all_markdown_parts.append(results[idx])
                else:
                    raise RuntimeError(
                        f"批次 {idx}/{total_batches} 既无缓存又未处理，数据不一致"
                    )

        full_markdown = "\n\n".join(all_markdown_parts)

        logger.info(
            f"Markdown提取完成！共 {total_pages} 页，{total_batches} 个批次"
        )
        logger.info(f"Markdown总长度: {len(full_markdown)} 字符")

        return full_markdown


# ============================================================
# VLLMOCRPDFLoader - 主加载器（接口保持不变，支持并发）
# ============================================================

class VLLMOCRPDFLoader(UnstructuredFileLoader):
    """
    整合后的PDF加载器
    使用多模态大模型解析PDF，使用层级分块器进行分块
    支持PDF级别并发处理
    """

    def __init__(
            self,
            file_path: str,
            api_key: str = None,
            model: str = None,  # 改为 None，不再使用默认值
            endpoint: str = None,  # 改为 None
            model_name: str = "qwen3.7-plus",  # 新增参数
            max_tokens_per_chunk: int = 2048,
            max_workers: int = 1,
            batch_workers: int = 4,  # 批次级并行度，默认 4 路并发 VLM
            **kwargs
    ):
        """
        初始化PDF加载器

        Args:
            file_path: PDF文件路径
            api_key: API密钥
            model: 模型名称
            endpoint: API端点
            max_tokens_per_chunk: 每个分块的最大token数
            max_workers: 并发处理PDF的数量，默认1（串行），大于1时启用并发
            batch_workers: 单PDF内批次并行度，默认4（4路VLM并发）
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
            max_workers=batch_workers,
        )
        self.chunker = HierarchyChunker(max_tokens_per_chunk=self.max_tokens_per_chunk)

    from langchain_core.documents import Document

    @classmethod
    def process_batch(cls, file_paths: list[str], max_workers: int = 3, **kwargs) -> dict[str, list]:
        """
        批量并发处理多个PDF文件（新增方法）

        Args:
            file_paths: PDF文件路径列表
            max_workers: 最大并发数
            **kwargs: 传递给 RapidOCRPDFLoader 的参数

        Returns:
            Dict[str, List]: {file_path: elements列表}
        """
        results = {}
        total = len(file_paths)
        completed = 0

        logger.info(f"{'=' * 60}")
        logger.info(f"开始批量处理 {total} 个PDF文件，并发数: {max_workers}")
        logger.info(f"{'=' * 60}")

        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            future_to_path = {}
            for file_path in file_paths:
                loader = cls(file_path=file_path, max_workers=1,
                             model_name=kwargs.get('model_name', 'qwen3.7-plus'), **kwargs)
                future = executor.submit(loader._process_single_pdf, file_path)
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

    def _process_single_pdf(self, file_path: str) -> tuple[str, list]:
        """
        处理单个PDF（内部方法，供并发调用）
        返回: (file_path, elements列表)
        """
        _PDF_KEY = 'pdf'
        try:
            logger.info(f"{'=' * 60}")
            logger.info(f"开始解析PDF: {file_path}")
            logger.info(f"{'=' * 60}")

            # 1. 使用多模态大模型解析PDF
            full_markdown = self.extractor.extract_full_markdown(file_path)

            # 获取文件名
            file_name = Path(file_path).stem.replace(" ", "")

            # 2. 使用层级分块器进行分块
            chunks = self.chunker.chunk_markdown(full_markdown, file_name, source_type=_PDF_KEY)

            logger.info(f"分块完成，共 {len(chunks)} 个块")

            # 3. 转换为unstructured可识别的格式
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
                        "file_type": _PDF_KEY,
                        "source_type": _PDF_KEY,
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
        如果max_workers > 1，内部使用并发处理
        """
        if self.max_workers <= 1:
            _, elements = self._process_single_pdf(self.file_path)
            return elements

        _, elements = self._process_single_pdf(self.file_path)
        return elements


# ============================================================
# 测试代码
# ============================================================

if __name__ == "__main__":
    # 测试串行模式（和原来一样）
    logger.info("=== 串行模式测试 ===")
    _loader = VLLMOCRPDFLoader(
        file_path="D:/任务/海思知识库项目/解析文档/pdf/Amlogic MBOX现场分析指南.pdf",
        max_workers=1
    )
    docs = _loader.load()
    logger.info(f"加载完成，共 {len(docs)} 个文档片段")
