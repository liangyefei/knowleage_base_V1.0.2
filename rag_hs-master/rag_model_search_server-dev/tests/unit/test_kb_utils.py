"""
知识库工具函数单元测试

测试 server.knowledge_base.utils 模块中的纯逻辑函数:
- validate_kb_name: 知识库名称合法性校验
- get_LoaderClass: 根据扩展名获取加载器类名
- make_text_splitter: 构造文本切分器
- get_kb_path 等路径拼接函数
- KnowledgeFile: 知识库文件对象构造

这些测试为纯逻辑测试,不依赖外部服务(数据库/模型/向量库)。
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

# 将项目根目录加入 sys.path,保证可以直接 import server/configs 包
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock, patch

import pytest
from langchain.docstore.document import Document

from configs import CHUNK_SIZE, OVERLAP_SIZE
from server.knowledge_base.utils import (
    LOADER_DICT,
    SUPPORTED_EXTS,
    KnowledgeFile,
    files2docs_in_thread,
    get_doc_path,
    get_file_path,
    get_kb_path,
    get_loader,
    get_LoaderClass,
    get_vs_path,
    list_files_from_folder,
    make_text_splitter,
    validate_kb_name,
)


def test_validate_kb_name_valid():
    """合法知识库名称应通过校验(返回 True)。"""
    assert validate_kb_name("my_kb_123") is True


def test_validate_kb_name_invalid():
    """
    含路径穿越关键字 ../ 的名称应被拒绝(返回 False)。

    注:按源码实现,validate_kb_name 仅检查 ../ 子串,
    纯 .. 或空格等并不会被判为非法(均返回 True)。
    此处只对源码真正拦截的 ../ 场景做断言。
    """
    assert validate_kb_name("../") is False
    assert validate_kb_name("../../etc") is False


def test_validate_kb_name_edge_cases():
    """
    边界情况:仅含 .. 或空格的名称按源码实现会通过校验(返回 True)。
    此用例如实记录源码行为,不做价值判断。
    """
    assert validate_kb_name("..") is True
    assert validate_kb_name("my kb") is True


def test_supported_exts_contains_common():
    """SUPPORTED_EXTS 应包含 .txt/.pdf/.md/.csv 等常见扩展名。"""
    assert isinstance(SUPPORTED_EXTS, list)
    assert ".txt" in SUPPORTED_EXTS
    assert ".pdf" in SUPPORTED_EXTS
    assert ".md" in SUPPORTED_EXTS
    assert ".csv" in SUPPORTED_EXTS


def test_loader_dict_structure():
    """LOADER_DICT 是 dict,其值为扩展名列表;SUPPORTED_EXTS 由其汇总。"""
    assert isinstance(LOADER_DICT, dict)
    for loader_name, exts in LOADER_DICT.items():
        assert isinstance(loader_name, str)
        assert isinstance(exts, list)


def test_get_loader_class_txt():
    """
    get_LoaderClass(.txt) 应返回 LOADER_DICT 中包含 .txt 的加载器名称(字符串)。
    据源码,该函数返回的是 LOADER_DICT 的 key(加载器类名字符串),而非类对象。
    """
    result = get_LoaderClass(".txt")
    assert result is not None
    assert isinstance(result, str)
    assert result in LOADER_DICT
    assert ".txt" in LOADER_DICT[result]


def test_get_loader_class_unknown_ext():
    """
    未知扩展名:get_LoaderClass 未匹配到加载器时隐式返回 None。
    """
    result = get_LoaderClass(".unknown_ext_xyz")
    assert result is None


def test_make_text_splitter_returns_object():
    """
    make_text_splitter 应返回带 split_text 方法的切分器对象,
    且 split_text 对中文文本切分后返回 list。

    注:任务要求 splitter_name=ChineseRecursive,但 text_splitter_dict 与
    text_splitter 模块中实际类名为 ChineseRecursiveTextSplitter。
    传入 ChineseRecursive 时源码会触发异常并回退到 RecursiveCharacterTextSplitter,
    仍可正常返回带 split_text 的切分器。此处使用 ChineseRecursiveTextSplitter
    以走正常路径并保证 split_text 可用。
    """
    splitter = make_text_splitter("ChineseRecursiveTextSplitter", CHUNK_SIZE, OVERLAP_SIZE)
    assert splitter is not None
    assert hasattr(splitter, "split_text")

    chunks = splitter.split_text("第一章 标题\n正文内容\n第二章")
    assert isinstance(chunks, list)
    assert len(chunks) >= 1


def test_kb_path_consistency():
    """get_kb_path 返回的路径应包含知识库名称。"""
    path = get_kb_path("test_kb")
    assert "test_kb" in path


def test_doc_path_consistency():
    """get_doc_path 返回的路径应包含 content 子目录与知识库名称。"""
    path = get_doc_path("test_kb")
    assert "test_kb" in path
    assert path.endswith("content")


def test_vs_path_consistency():
    """get_vs_path 返回的路径应包含 vector_store 与 vector_name。"""
    path = get_vs_path("test_kb", "my_vec")
    assert "test_kb" in path
    assert "vector_store" in path
    assert "my_vec" in path


def test_file_path_consistency():
    """get_file_path 返回的路径应包含知识库名称与文档名。"""
    path = get_file_path("test_kb", "doc.txt")
    assert "test_kb" in path
    assert "doc.txt" in path


def test_knowledge_file_creation():
    """
    KnowledgeFile(doc.txt, test_kb) 应构造成功,
    且 kb_name/filename/ext/document_loader_name 等字段正确。

    注:源码中字段名为 kb_name 与 filename(非 file_name)。
    """
    kf = KnowledgeFile("doc.txt", "test_kb")
    assert kf.kb_name == "test_kb"
    assert kf.filename == "doc.txt"
    assert kf.ext == ".txt"
    assert kf.document_loader_name is not None
    assert isinstance(kf.document_loader_name, str)


def test_knowledge_file_unsupported_ext_raises():
    """不支持的扩展名构造 KnowledgeFile 应抛出 ValueError。"""
    try:
        KnowledgeFile("doc.unsupported_ext", "test_kb")
        raised = False
    except ValueError:
        raised = True
    assert raised, "不支持的扩展名应抛出 ValueError"


# ==================== get_loader 分支测试 ====================

class TestGetLoader:
    """get_loader 的三个分支：LOADER_DICT 内走 document_loaders、外走 langchain、异常回退 UnstructuredFileLoader。"""

    @staticmethod
    def test_loader_in_dict_imports_document_loaders(monkeypatch, tmp_path):
        """loader_name 在 LOADER_DICT 中时，应 import 'document_loaders' 模块。"""
        file_path = tmp_path / "f.txt"
        file_path.write_text("hello")

        # 用一个假模块替换 importlib.import_module，记录被调用的模块名
        call_log = []

        def fake_import(name, *args, **kwargs):
            call_log.append(name)
            mod = MagicMock()
            mod.UnstructuredFileLoader = MagicMock(return_value=MagicMock(spec=["load"]))
            return mod

        monkeypatch.setattr("server.knowledge_base.utils.importlib.import_module", fake_import)
        loader = get_loader("UnstructuredFileLoader", str(file_path))
        assert "document_loaders" in call_log
        assert loader is not None

    @staticmethod
    def test_loader_not_in_dict_imports_langchain(monkeypatch, tmp_path):
        """loader_name 不在 LOADER_DICT 中时，应 import 'langchain.document_loaders' 模块。"""
        file_path = tmp_path / "f.txt"
        file_path.write_text("hello")

        call_log = []

        def fake_import(name, *args, **kwargs):
            call_log.append(name)
            mod = MagicMock()
            mod.SomeUnknownLoader = MagicMock(return_value=MagicMock(spec=["load"]))
            return mod

        monkeypatch.setattr("server.knowledge_base.utils.importlib.import_module", fake_import)
        # 用一个不在 LOADER_DICT 的 loader_name
        get_loader("SomeUnknownLoader", str(file_path))
        assert "langchain.document_loaders" in call_log

    @staticmethod
    def test_loader_exception_falls_back_to_unstructured(monkeypatch, tmp_path):
        """importlib 或 getattr 出错时，回退到 langchain.document_loaders.UnstructuredFileLoader。"""
        file_path = tmp_path / "f.txt"
        file_path.write_text("hello")

        call_log = []

        def fake_import(name, *args, **kwargs):
            call_log.append(name)
            # 第一次 import 抛异常触发 fallback，第二次返回带 UnstructuredFileLoader 的 mock
            if name == "document_loaders":
                raise ImportError("fake error")
            mod = MagicMock()
            mod.UnstructuredFileLoader = MagicMock(return_value=MagicMock(spec=["load"]))
            return mod

        monkeypatch.setattr("server.knowledge_base.utils.importlib.import_module", fake_import)
        loader = get_loader("UnstructuredFileLoader", str(file_path))
        # 应至少调用了 document_loaders（抛错）和 langchain.document_loaders（fallback）
        assert "langchain.document_loaders" in call_log
        assert loader is not None


# ==================== make_text_splitter 分支测试 ====================

class TestMakeTextSplitterBranches:
    @staticmethod
    def test_chinese_recursive_splitter():
        """ChineseRecursiveTextSplitter 分支：返回带 split_text 的切分器。"""
        splitter = make_text_splitter("ChineseRecursiveTextSplitter", CHUNK_SIZE, OVERLAP_SIZE)
        assert splitter is not None
        assert hasattr(splitter, "split_text")
        chunks = splitter.split_text("第一章 标题\n正文内容\n第二章")
        assert isinstance(chunks, list)

    @staticmethod
    def test_unknown_splitter_falls_back_to_recursive(monkeypatch):
        """未知 splitter_name 触发异常，回退到 RecursiveCharacterTextSplitter。"""
        # 传入一个不存在的 splitter_name，源码外层 except 会回退到 RecursiveCharacterTextSplitter
        splitter = make_text_splitter("NonExistentSplitter", CHUNK_SIZE, OVERLAP_SIZE)
        assert splitter is not None
        assert hasattr(splitter, "split_text")


# ==================== KnowledgeFile 文件元数据方法测试 ====================

class TestKnowledgeFileMetadata:
    @staticmethod
    def test_file_exist_true(monkeypatch, tmp_path):
        """文件存在时 file_exist 返回 True，mtime/size 为正。"""
        kb_content = tmp_path / "kb" / "content"
        kb_content.mkdir(parents=True)
        file_path = kb_content / "test.txt"
        file_path.write_text("hello world")

        monkeypatch.setattr("server.knowledge_base.utils.KB_ROOT_PATH", str(tmp_path))
        kf = KnowledgeFile("test.txt", "kb")
        assert kf.file_exist() is True
        assert kf.get_mtime() > 0
        assert kf.get_size() == len("hello world")

    @staticmethod
    def test_file_exist_false_when_missing(monkeypatch, tmp_path):
        """文件不存在时 file_exist 返回 False。"""
        kb_content = tmp_path / "kb" / "content"
        kb_content.mkdir(parents=True)

        monkeypatch.setattr("server.knowledge_base.utils.KB_ROOT_PATH", str(tmp_path))
        kf = KnowledgeFile("missing.txt", "kb")
        assert kf.file_exist() is False

    @staticmethod
    def test_get_mtime_raises_when_missing(monkeypatch, tmp_path):
        """文件不存在时 get_mtime 抛 OSError。"""
        kb_content = tmp_path / "kb" / "content"
        kb_content.mkdir(parents=True)

        monkeypatch.setattr("server.knowledge_base.utils.KB_ROOT_PATH", str(tmp_path))
        kf = KnowledgeFile("missing.txt", "kb")
        with pytest.raises(OSError):
            kf.get_mtime()

    @staticmethod
    def test_get_size_raises_when_missing(monkeypatch, tmp_path):
        """文件不存在时 get_size 抛 OSError。"""
        kb_content = tmp_path / "kb" / "content"
        kb_content.mkdir(parents=True)

        monkeypatch.setattr("server.knowledge_base.utils.KB_ROOT_PATH", str(tmp_path))
        kf = KnowledgeFile("missing.txt", "kb")
        with pytest.raises(OSError):
            kf.get_size()


# ==================== list_files_from_folder 测试 ====================

class TestListFilesFromFolder:
    @staticmethod
    def test_lists_files_and_subdirs(monkeypatch, tmp_path):
        """应列出顶层文件与子目录内文件，排除 temp/tmp 前缀文件。"""
        content = tmp_path / "kb" / "content"
        content.mkdir(parents=True)
        (content / "a.txt").write_text("a")
        (content / "b.pdf").write_text("b")
        (content / "temp_skip.tmp").write_text("t")  # temp 前缀，应跳过
        (content / "~$lock.docx").write_text("l")  # ~$ 前缀，应跳过
        sub = content / "sub"
        sub.mkdir()
        (sub / "c.txt").write_text("c")

        monkeypatch.setattr("server.knowledge_base.utils.KB_ROOT_PATH", str(tmp_path))
        result = list_files_from_folder("kb")

        assert "a.txt" in result
        assert "b.pdf" in result
        assert "sub/c.txt" in result
        assert not any("temp_skip" in r for r in result), f"temp 前缀文件应被跳过: {result}"
        assert not any("~$lock" in r for r in result), f"~$ 前缀文件应被跳过: {result}"

    @staticmethod
    def test_empty_dir_returns_empty_list(monkeypatch, tmp_path):
        """空目录返回空列表。"""
        content = tmp_path / "kb" / "content"
        content.mkdir(parents=True)

        monkeypatch.setattr("server.knowledge_base.utils.KB_ROOT_PATH", str(tmp_path))
        result = list_files_from_folder("kb")
        assert not result


# ==================== files2docs_in_thread 测试 ====================

class TestFiles2DocsInThread:
    _FILE_NAME_KEY = 'test.txt'
    _KB_NAME_KEY = 'kb'

    @staticmethod
    def test_tuple_form(monkeypatch, tmp_path):
        """tuple (filename, kb_name) 形态：mock file2text 后应产出成功结果。"""
        _KB_KEY = TestFiles2DocsInThread._KB_NAME_KEY
        content = tmp_path / _KB_KEY / "content"
        content.mkdir(parents=True)
        (content / TestFiles2DocsInThread._FILE_NAME_KEY).write_text("hello")

        monkeypatch.setattr("server.knowledge_base.utils.KB_ROOT_PATH", str(tmp_path))
        # mock file2text 避免真实加载文档
        monkeypatch.setattr(
            "server.knowledge_base.utils.KnowledgeFile.file2text",
            lambda self, **kw: [Document(page_content="fake", metadata={})],
        )

        files = [(TestFiles2DocsInThread._FILE_NAME_KEY, _KB_KEY)]
        results = list(files2docs_in_thread(files))
        assert len(results) == 1
        status, (kb_name, filename, docs) = results[0]
        assert status is True
        assert kb_name == _KB_KEY
        assert filename == TestFiles2DocsInThread._FILE_NAME_KEY
        assert isinstance(docs, list)

    @staticmethod
    def test_dict_form(monkeypatch, tmp_path):
        """dict {filename, kb_name} 形态：mock file2text 后应产出成功结果。"""
        content = tmp_path / TestFiles2DocsInThread._KB_NAME_KEY / "content"
        content.mkdir(parents=True)
        (content / TestFiles2DocsInThread._FILE_NAME_KEY).write_text("hello")

        monkeypatch.setattr("server.knowledge_base.utils.KB_ROOT_PATH", str(tmp_path))
        monkeypatch.setattr(
            "server.knowledge_base.utils.KnowledgeFile.file2text",
            lambda self, **kw: [Document(page_content="fake", metadata={})],
        )

        files = [{"filename": TestFiles2DocsInThread._FILE_NAME_KEY, "kb_name": TestFiles2DocsInThread._KB_NAME_KEY}]
        results = list(files2docs_in_thread(files))
        assert len(results) == 1
        status, (kb_name, filename, _docs) = results[0]
        assert status is True
        assert kb_name == TestFiles2DocsInThread._KB_NAME_KEY
        assert filename == TestFiles2DocsInThread._FILE_NAME_KEY

    @staticmethod
    def test_knowledgefile_form(monkeypatch, tmp_path):
        """KnowledgeFile 实例形态：mock file2text 后应产出成功结果。"""
        content = tmp_path / TestFiles2DocsInThread._KB_NAME_KEY / "content"
        content.mkdir(parents=True)
        (content / TestFiles2DocsInThread._FILE_NAME_KEY).write_text("hello")

        monkeypatch.setattr("server.knowledge_base.utils.KB_ROOT_PATH", str(tmp_path))
        monkeypatch.setattr(
            "server.knowledge_base.utils.KnowledgeFile.file2text",
            lambda self, **kw: [Document(page_content="fake", metadata={})],
        )

        kf = KnowledgeFile(TestFiles2DocsInThread._FILE_NAME_KEY, TestFiles2DocsInThread._KB_NAME_KEY)
        files = [kf]
        results = list(files2docs_in_thread(files))
        assert len(results) == 1
        status, (kb_name, _filename, _docs) = results[0]
        assert status is True
        assert kb_name == TestFiles2DocsInThread._KB_NAME_KEY

    @staticmethod
    def test_unsupported_ext_yields_error(monkeypatch, tmp_path):
        """不支持的扩展名构造 KnowledgeFile 失败时，应 yield (False, (kb_name, filename, error))。"""
        monkeypatch.setattr("server.knowledge_base.utils.KB_ROOT_PATH", str(tmp_path))
        # .unknown_ext 不在 SUPPORTED_EXTS，KnowledgeFile 构造抛 ValueError
        files = [("bad.unknown_ext", "kb")]
        results = list(files2docs_in_thread(files))
        assert len(results) == 1
        status, (kb_name, filename, error) = results[0]
        assert status is False
        assert kb_name == "kb"
        assert filename == "bad.unknown_ext"
        assert isinstance(error, str)

    @staticmethod
    def test_file2text_error_yields_false(monkeypatch, tmp_path):
        """file2text 抛异常时，应 yield (False, (kb_name, filename, error_msg))。"""
        content = tmp_path / "kb" / "content"
        content.mkdir(parents=True)
        (content / "test.txt").write_text("hello")

        def raise_file2text(self, **kw):
            raise RuntimeError("boom")

        monkeypatch.setattr("server.knowledge_base.utils.KB_ROOT_PATH", str(tmp_path))
        monkeypatch.setattr("server.knowledge_base.utils.KnowledgeFile.file2text", raise_file2text)

        files = [("test.txt", "kb")]
        results = list(files2docs_in_thread(files))
        assert len(results) == 1
        status, (_kb_name, _filename, error) = results[0]
        assert status is False
        assert "boom" in error or "加载文档" in error
