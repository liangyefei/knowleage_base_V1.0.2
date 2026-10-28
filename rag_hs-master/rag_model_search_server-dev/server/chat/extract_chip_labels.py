#!/usr/bin/env python3
# @Time : 2026/8/4
# @Author : Hetao
# @File : [Code] --> extract_chip_labels.py
# @Software: PyCharm
# @Function: 从文本中提取芯片类型标签（基于 kb_config.yaml 的 CHIP_TYPE_DICT 匹配）

from configs import CHIP_TYPE_DICT


def extract_chip_label(text: str):
    """从文本中提取命中的芯片类型标签。

    以 kb_config.yaml 中 CHIP_TYPE_DICT 的键为匹配词，键出现在文本中即命中。
    返回命中键的列表，便于后续用 CHIP_TYPE_DICT[key] 拼接子知识库集合名。

    Args:
        text: 输入文本（通常是用户查询或文件路径）
    Returns:
        list[str]: 命中的芯片类型键列表，无命中时返回空列表
    """
    return [key for key in CHIP_TYPE_DICT if key in text]


def find_chip_keys_in_text(text, chips_dict):
    """从文本中查找命中的芯片键。"""
    return [key for key in chips_dict if key in text]
