#!/usr/bin/env python3
# @Function: SFS 挂载目录相对路径解析工具
import os
import re
import urllib.parse

from configs import SFS_MOUNT_PATH

_WIN_DRIVE_RE = re.compile(r'^[a-zA-Z]:')


def resolve_sfs_path(file_path: str) -> str:
    """将 SFS 挂载目录下的相对路径解析为绝对路径。

    Args:
        file_path: SFS 挂载目录下的相对路径，
            如 "kb/ef6eeb8e/cat-001/需求文档v3.xlsx"

    Returns:
        拼接 SFS_MOUNT_PATH 后的绝对路径字符串，统一使用正斜杠。

    Raises:
        ValueError: file_path 为空/None、为绝对路径或包含 ".." 路径段时抛出。
    """
    if file_path is None or file_path == '':
        raise ValueError("file_path must be a non-empty relative path")

    # 后端可能对路径做了 URL 编码（空格→%20、+ 等），统一解码为真实字符，
    # 避免 os.path.isfile 因 %20 与磁盘真实空格不匹配而误判文件不存在。
    file_path = urllib.parse.unquote(file_path)

    # 绝对路径检测：POSIX 以 / 开头，或 Windows 盘符（C:/D: 等，不区分大小写）
    if file_path.startswith('/') or _WIN_DRIVE_RE.match(file_path):
        raise ValueError(f"file_path must be a relative path, got: {file_path}")

    # 防穿越：按 / 或 \ 分割后任一段为 ".."
    segments = re.split(r'[\\/]', file_path)
    if any(seg == '..' for seg in segments):
        raise ValueError(f"file_path must not contain '..' segments, got: {file_path}")

    abs_path = os.path.join(SFS_MOUNT_PATH, file_path)
    return abs_path.replace('\\', '/')
