# MinerUDocumentLoader 统一处理 PDF/Word/PPT，兼容别名指向原类名
# 让本地 document_loaders 包也能解析 LOADER_DICT 的扩展名
# TODO CK haisi_rag_models_server\server\knowledge_base\utils.py 里面要求的包是否都导入了
from langchain.document_loaders import (
                                        CSVLoader,
                                        JSONLoader,
                                        MHTMLLoader,
                                        NotebookLoader,
                                        PythonLoader,
                                        SRTLoader,
                                        TomlLoader,
                                        UnstructuredFileLoader,
                                        UnstructuredHTMLLoader,
                                        UnstructuredMarkdownLoader,
                                        UnstructuredODTLoader,
                                        UnstructuredRSTLoader,
                                        UnstructuredRTFLoader,
                                        UnstructuredTSVLoader,
                                        UnstructuredXMLLoader,
)

from .mineru_loader import MinerUDocumentLoader, VLLMOCRDocLoader, VLLMOCRPDFLoader, VLLMOCRPPTLoader
from .mycodeloader import GenericCodeLoader
from .myconfigloader import ConfigFileLoader
from .myexcelloader import ExcelChineseLoader
from .myimgloader import VLLMOCRLoader
from .mymdloader import VLLMMarkdownLoader

