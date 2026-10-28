# """
# 图片加载器，用于加载图片文件。
# TODO CK 改成多模态加载器，支持图片。改名去除rapidocrxxx改成vlmxxx注意参数从configs导入。
# """

# !/usr/bin/env python3
# _*_ coding: utf-8 _*_
# @File : myimgloader.py
# @Function: 图片加载器 - 使用 VLM 多模态大模型解析图片

import base64
from pathlib import Path

from jinja2 import Template
from langchain.document_loaders.unstructured import UnstructuredFileLoader

from configs import MIN_IMAGE_PIXELS
from configs.logger_manager import logger
from configs.model_loader import get_vlm_config

# ✅ 导入 prompt 加载器
from configs.prompt_loader import get_prompt
from document_loaders.base_loader import call_vlm_model

from document_loaders.base_loader import _save_chunks_to_json

# 小图标过滤默认阈值：从 kb_config.yaml 的 DOCUMENT_LOADER_CONFIG.MIN_IMAGE_PIXELS 读取
# 100x100=10000 过滤 favicon/小 logo/bullet，不误杀带文字图表（通常 >200x200）
_MIN_IMAGE_PIXELS_DEFAULT = MIN_IMAGE_PIXELS

# 小图片 placeholder 入库内容：代码风格标识符，无业务关键词，
# embedding 后落在"标识符"语义空间，跟自然语言 query 几乎不匹配，检索污染极低
_PLACEHOLDER_CONTENT = "[SKIP:IMG_TOO_SMALL]"


def _get_image_pixel_count(image_path: str) -> int:
    """获取图片像素数（宽×高）。读取失败返回 -1。"""
    try:
        from PIL import Image
        with Image.open(image_path) as im:
            w, h = im.size
            return w * h
    except Exception:
        return -1


class VLLMImageExtractor:
    """
    图片提取器 - 使用 VLM 多模态大模型解析图片
    生成包含图片描述和 OCR 文字的 Markdown 内容
    """

    def __init__(
            self,
            model_name: str = "qwen3.7-plus",
            max_retries: int = 3,
            retry_delay: int = 5,
            min_image_pixels: int = _MIN_IMAGE_PIXELS_DEFAULT
    ):
        """
        初始化图片提取器

        Args:
            model_name: VLM 模型名称（从配置加载）
            max_retries: 最大重试次数
            retry_delay: 重试延迟（秒）
            min_image_pixels: 像素数（宽×高）小于此值的图片视为小图标，
                              直接返回 "null" 不调用 VLM
        """
        config = get_vlm_config(model_name)
        if not config:
            raise ValueError(f"配置中找不到 VLM 模型: {model_name}")

        self.api_key = config.get('api_key')
        self.model = config.get('model_id')
        self.endpoint = config.get('api_base_url')
        self.max_retries = config.get('max_retries', max_retries)
        self.retry_delay = retry_delay
        self.min_image_pixels = min_image_pixels

        self.headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json"
        }

    @staticmethod
    def image_to_base64(image_path: str) -> str:
        """将图片文件转换为 base64 编码"""
        with open(image_path, 'rb') as f:
            image_data = f.read()
        return base64.b64encode(image_data).decode('utf-8')

    @staticmethod
    def create_extract_prompt(image_base64: str, filename: str) -> list[dict]:
        """构建图片解析提示词"""

        # ✅ 从配置文件加载提示词模板
        system_prompt = get_prompt("image_extractor", "system_prompt")
        user_template_str = get_prompt("image_extractor", "user_template")

        # 使用 Jinja2 渲染用户模板
        template = Template(user_template_str)
        user_content = template.render(filename=filename)

        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": [
                {"type": "text", "text": user_content},
                {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{image_base64}"}}
            ]}
        ]

        return messages

    def call_model(self, messages: list[dict]) -> str:
        """调用 VLM 模型 API"""
        return call_vlm_model(
            endpoint=self.endpoint,
            headers=self.headers,
            model=self.model,
            messages=messages,
            max_retries=self.max_retries,
            retry_delay=self.retry_delay
        )

    def extract_image(self, image_path: str) -> str:
        """
        提取图片内容，生成描述

        小图片（像素数 < min_image_pixels）直接返回空串，不调用 VLM：
        - 小图标不可能包含有用信息，调用 VLM 反而会报错
        - 返回空串让上层 _get_elements 据此跳过该图片，不产生切片，
          避免 "null" 字符串被当作真实内容静默入库

        Args:
            image_path: 图片文件路径

        Returns:
            图片描述文本；小图片或 VLM 返回空时返回空串
        """
        filename = Path(image_path).stem

        # 小图片过滤：像素数过小直接返回空串，不调用 VLM
        pixels = _get_image_pixel_count(image_path)
        if 0 <= pixels < self.min_image_pixels:
            logger.info(
                f"图片过小（{pixels}像素 < {self.min_image_pixels}），跳过 VLM: {image_path}"
            )
            return ""

        logger.info(f"正在解析图片: {image_path}")

        # 1. 转换图片为 base64
        image_base64 = self.image_to_base64(image_path)

        # 2. 构建提示词
        messages = self.create_extract_prompt(image_base64, filename)

        # 3. 调用模型
        description = self.call_model(messages)

        logger.info(f"图片解析完成，内容长度: {len(description)} 字符")

        return description


class VLLMOCRLoader(UnstructuredFileLoader):
    """
    图片加载器 - 使用 VLM 多模态大模型解析图片
    生成包含图片描述和 OCR 文字的完整内容
    """



    def __init__(
            self,
            file_path: str,
            model_name: str = "qwen3.7-plus",
            min_image_pixels: int = _MIN_IMAGE_PIXELS_DEFAULT,
            **kwargs
    ):
        """
        初始化图片加载器

        Args:
            file_path: 图片文件路径
            model_name: VLM 模型名称
            min_image_pixels: 像素数小于此值的图片跳过 VLM，返回 "null"
            **kwargs: 传递给父类的参数
        """
        super().__init__(file_path, **kwargs)
        self.model_name = model_name

        # 初始化提取器
        self.extractor = VLLMImageExtractor(
            model_name=model_name, min_image_pixels=min_image_pixels
        )

    from langchain_core.documents import Document



    def load(self) -> list[Document]:
        """重写 load 方法，直接返回 _get_elements 的结果，避免父类合并"""
        return self._get_elements()



    def _get_elements(self) -> list:
        """
        重写父类方法 - 返回分块后的元素列表
        """
        _IMAGE_KEY = 'image'
        try:
            logger.info(f"开始解析图片: {self.file_path}")

            # 1. 使用 VLM 解析图片
            description = self.extractor.extract_image(self.file_path)

            # 2. 空描述（小图片或 VLM 返回空）返回 placeholder Document 占位，
            #    避免返回 [] 触发 add_doc 报错（update_docs_use_path 会把 False 算成失败文件）
            stripped = (description or "").strip()
            if not stripped or stripped == "null":
                filename = Path(self.file_path).stem
                from langchain_core.documents import Document
                placeholder_doc = Document(
                    page_content=_PLACEHOLDER_CONTENT,
                    metadata={
                        "source": self.file_path,
                        "file_name": filename,
                        "file_type": Path(self.file_path).suffix.lower()[1:],
                        "source_type": _IMAGE_KEY,
                        "chunk_index": 1,
                        "total_chunks": 1,
                        "content_type": "small_image_placeholder",
                        "image_type": "",
                        "image_topic": "",
                    }
                )
                elements = [placeholder_doc]
                _save_chunks_to_json(self.file_path, elements)
                logger.info(f"图片跳过 VLM（像素过小或空返回），返回 placeholder 占位: {self.file_path}")
                return elements

            # 3. 构建最终内容（添加图片标识）
            filename = Path(self.file_path).stem

            # 4. 使用 partition_text 分割为元素
            from langchain_core.documents import Document
            doc = Document(
                page_content=description,
                metadata={
                    "source": self.file_path,
                    "file_name": filename,
                    "file_type": Path(self.file_path).suffix.lower()[1:],  # png/jpg/jpeg/bmp
                    "source_type": _IMAGE_KEY,
                    "chunk_index": 1,
                    "total_chunks": 1,
                    "content_type": _IMAGE_KEY,
                    "image_type": "",  # 可后续从描述中提取
                    "image_topic": "",  # 可后续从描述中提取
                }
            )
            elements = [doc]

            # ============ 保存 JSON ============
            _save_chunks_to_json(self.file_path, elements)
            # ==================================

            logger.info(f"图片解析完成，生成 {len(elements)} 个元素")
            return elements

        except Exception as e:
            logger.error(f"图片解析失败: {e}")
            import traceback
            traceback.print_exc()
            raise


# ============================================================
# 测试代码
# ============================================================

if __name__ == "__main__":
    logger.info("=== 测试图片解析 ===")
    loader = VLLMOCRLoader(
        file_path="../tests/samples/ocr_test.jpg"
    )
    docs = loader.load()
    logger.info(f"加载完成，共 {len(docs)} 个文档片段")

    for i, _doc in enumerate(docs):
        logger.info(f"\n--- 片段 {i + 1} ---")
        logger.info(f"内容:\n{_doc.page_content}")
        if hasattr(_doc, 'metadata'):
            logger.info(f"元数据: {_doc.metadata}")
