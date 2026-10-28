#!/usr/bin/env python3
# @Time : 2024/5/21 16:55:58
# @Author : Hetao
# @File : [Code] --> mycodeloader.py
# @Software: PyCharm
# @Function: TODO
import warnings

warnings.filterwarnings("ignore")
from pathlib import Path

from langchain.docstore.document import Document
from langchain.document_loaders import UnstructuredFileLoader
from langchain_community.document_loaders.generic import GenericLoader
from langchain_community.document_loaders.parsers import LanguageParser

from configs import log_verbose, logger
from server.knowledge_base.utils import CODE_DICT, CODE_TYPE_DICT


class GenericCodeLoader(UnstructuredFileLoader):
    def __init__(self, file_path: str, ):
        super().__init__(
            file_path=file_path,
        )

    def load(self) -> list[Document]:
        """Load data into document objects."""

        docs = []
        try:
            # 能送到这里的代码必然是支持的格式之一
            language = self.__set_language_types()
            logger.info(f"type: {language}, {self.file_path}")
            loader = GenericLoader.from_filesystem(self.file_path,
                                                   parser=LanguageParser(language=language, parser_threshold=100),
                                                   show_progress=True
                                                   )
            logger.info(f"%%%%%%%%%%%%%%%%%%%%%%%%%loader:{loader}")
            docs = loader.load()
        except Exception as e:
            msg = f"从代码知识库加载文档 {self.file_path} 时出错：{e}"
            logger.error(f'{e.__class__.__name__}: {msg}', exc_info=e if log_verbose else None)
            raise RuntimeError(f"Error loading {self.file_path}") from e

        return docs

    def __set_language_types(self):
        ext = Path(self.file_path).suffix
        for code_type, ext_list in CODE_DICT.items():
            if ext in ext_list:
                return CODE_TYPE_DICT[code_type]
        return None
