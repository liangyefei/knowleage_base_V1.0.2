#!/usr/bin/env python3
# @Time : 2024/3/27 10:46:40
# @Author : Hetao
# @File : [Code] --> read_all_files_name.py
# @Software: PyCharm
# @Function: TODO

import os
from pathlib import Path

from configs.logger_manager import logger


def get_all_file_names(folder_path):
    file_names = []

    # 使用os.walk()遍历文件夹
    for _, _, filenames in os.walk(folder_path):
        for filename in filenames:
            ext = Path(filename).suffix
            if ext in [".pdf", ".doc", ".docx"]:
                file_names.append(filename)
                logger.info(filename)

    return file_names

# 指定要遍历的文件夹路径
_folder_path = '/mnt/d/data/release/haisi'

# 调用函数并获取所有文件名
all_files = get_all_file_names(_folder_path)
