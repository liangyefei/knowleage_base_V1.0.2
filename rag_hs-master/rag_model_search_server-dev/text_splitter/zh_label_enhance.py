#!/usr/bin/env python3
# @Time : 2024/3/27 9:25:57
# @Author : Hetao
# @File : [Code] --> zh_label_enhance.py
# @Software: PyCharm
# @Function: 对芯片型号做标注增强，参考 zh_title_enhance.py 实现
#            （原省份/项目标签逻辑已迁移为芯片型号，与 CHIP_TYPE_DICT 白名单对齐）
from langchain.docstore.document import Document

from configs import logger
from server.chat.extract_chip_labels import extract_chip_label


def zh_label_enhance(docs: Document, filename) -> Document:
    """对芯片型号做标注增强。

    从文件名提取芯片型号（基于 CHIP_TYPE_DICT 白名单），
    命中时为文档内容加芯片型号前缀，提高检索时芯片维度匹配精度。
    无命中时不修改文档内容。
    """
    if len(docs) > 0:
        chips = extract_chip_label(filename)
        if chips:
            chip_str = "、".join(chips)
            for doc in docs:
                doc.page_content = f"这是{chip_str}芯片的待检索信息。{doc.page_content}"
        return docs
    else:
        logger.warning("文件不存在")
        return docs

