#!/usr/bin/env python3
# @Time : 2024/2/23 15:05:28
# @Author : Hetao
# @File : [Code] --> doc2docx.py
# @Software: PyCharm
# @Function: doc格式批量转docx

import os
import logging

from win32com import client


def getAllFilesList(filepath):
    """
    获取指定目录下的所有doc文件列表
    :param filepath: 指定目录
    :return: 指定目录下的所有doc文件列表
    """
    files = []
    for file in os.listdir(filepath):
        if file.endswith(".doc"):
            files.append(filepath + file)

    return files


if __name__ == '__main__':
    _filepath = "D:" + os.sep + "sampple" + os.sep + "doc" + os.sep
    _files = getAllFilesList(_filepath)

    word = client.Dispatch("Word.Application")

    i = 0
    for _file in _files:
        try:
            doc = word.Documents.Open(_file)  # 打开word文件
            doc.SaveAs(f"{_file}x", 12)  # 另存为后缀为“.docx”的文件，其中参数12指的doc文件
            doc.Close()  # 关闭原来的word文件
            logging.info("%s转换成功！", _file)
            i += 1
        except Exception:
            logging.info("%s转换失败！", _file)
            _files.append(_file)  # 将文件名添加到files列表中重新读取


    logging.info("转换文件%s个", i)
    word.Quit()
