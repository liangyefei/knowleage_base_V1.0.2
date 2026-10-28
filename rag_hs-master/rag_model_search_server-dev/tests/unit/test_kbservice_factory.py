"""KBServiceFactory 白盒单元测试。

覆盖 server.knowledge_base.kb_service.base.KBServiceFactory.get_service_by_name
的 vs_type 分发逻辑。

注: 任务描述提到 monkeypatch configs.DEFAULT_VS_TYPE，但源码中
get_service_by_name 的 vs_type 来自 load_kb_from_db(kb_name) 而非
DEFAULT_VS_TYPE。故本测试 mock load_kb_from_db 的返回值以控制 vs_type
（以源码实际行为为准）。

为避免触发真实 kb_service 子模块的重依赖导入（pymilvus / chromadb /
pgvector）与真实中间件连接，向 sys.modules 注入 fake 模块，使各 service
类替换为 MagicMock。同时 neutralize base.kbs_config 作为安全网（尽管
get_service_by_name 路径不读取 kbs_config）。禁止真实中间件连接。
"""
# pylint: disable=no-use-sys-path-insert
import sys
import types
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from unittest.mock import MagicMock

import pytest

# base.py 顶层导入了 embeddings_api / knowledge_base_repository 等较重依赖，
# 若环境缺失可能 import 失败。此处守卫导入，失败则整组用例 skip。
try:
    from server.knowledge_base.kb_service.base import KBServiceFactory
    _IMPORT_ERROR = None
except Exception as _e:  # noqa: BLE001
    KBServiceFactory = None
    _IMPORT_ERROR = _e

_SKIP_REASON = f"无法导入 KBServiceFactory（重依赖缺失？）: {_IMPORT_ERROR}"


def _inject_fake_service_module(mocker, module_path: str, class_name: str):
    """向 sys.modules 注入 fake 模块，使其 class_name 指向一个 MagicMock 类。

    get_service 内 `from ... import XxxKBService` 会先查 sys.modules，命中
    fake 模块后 getattr 取出 mock 类，不再触发真实子模块导入，从而禁止
    真实中间件（Milvus/PG/Chroma）连接。
    """
    fake_mod = types.ModuleType(module_path)
    mock_cls = MagicMock(name=class_name)
    setattr(fake_mod, class_name, mock_cls)
    mocker.patch.dict(sys.modules, {module_path: fake_mod})
    return mock_cls


@pytest.mark.skipif(KBServiceFactory is None, reason=_SKIP_REASON)
class TestGetServiceByName:
    """get_service_by_name 按 load_kb_from_db 返回的 vs_type 分发到对应 service 类。"""

    _KB_NAME_KEY = 'my_kb'

    @staticmethod
    def test_chromadb_dispatch(mocker):
        """vs_type=chromadb → 实例化 ChromaKBService(kb_name, embed_model=...)。"""
        mock_cls = _inject_fake_service_module(
            mocker,
            "server.knowledge_base.kb_service.chromadb_kb_service",
            "ChromaKBService",
        )
        mocker.patch(
            "server.knowledge_base.kb_service.base.load_kb_from_db",
            return_value=(TestGetServiceByName._KB_NAME_KEY, "chromadb", "text-embedding-v3"),
        )

        result = KBServiceFactory.get_service_by_name(TestGetServiceByName._KB_NAME_KEY)

        mock_cls.assert_called_once_with(TestGetServiceByName._KB_NAME_KEY, embed_model="text-embedding-v3")
        assert result is mock_cls.return_value

    @staticmethod
    def test_milvus_dispatch(mocker):
        """vs_type=milvus → 实例化 MilvusKBService(kb_name, embed_model=...)。"""
        _MY_KB_KEY = 'my_kb'
        mock_cls = _inject_fake_service_module(
            mocker,
            "server.knowledge_base.kb_service.milvus_kb_service",
            "MilvusKBService",
        )
        mocker.patch(
            "server.knowledge_base.kb_service.base.load_kb_from_db",
            return_value=(_MY_KB_KEY, "milvus", "text-embedding-v3"),
        )

        result = KBServiceFactory.get_service_by_name(_MY_KB_KEY)

        mock_cls.assert_called_once_with(_MY_KB_KEY, embed_model="text-embedding-v3")
        assert result is mock_cls.return_value

    @staticmethod
    def test_not_in_db_returns_none(mocker):
        """kb 不在 DB（load_kb_from_db 首元素为 None）→ 返回 None。"""
        mocker.patch(
            "server.knowledge_base.kb_service.base.load_kb_from_db",
            return_value=(None, None, None),
        )

        result = KBServiceFactory.get_service_by_name("missing_kb")

        assert result is None

    @staticmethod
    def test_pg_dispatch(mocker):
        """vs_type=pg → 实例化 PGKBService(kb_name, embed_model=...)。"""
        mock_cls = _inject_fake_service_module(
            mocker,
            "server.knowledge_base.kb_service.pg_kb_service",
            "PGKBService",
        )
        mocker.patch(
            "server.knowledge_base.kb_service.base.load_kb_from_db",
            return_value=(TestGetServiceByName._KB_NAME_KEY, "pg", "text-embedding-v3"),
        )

        result = KBServiceFactory.get_service_by_name(TestGetServiceByName._KB_NAME_KEY)

        mock_cls.assert_called_once_with(TestGetServiceByName._KB_NAME_KEY, embed_model="text-embedding-v3")
        assert result is mock_cls.return_value

    @staticmethod
    def test_unsupported_vs_type_raises(mocker):
        """不支持的 vs_type：getattr(SupportedVSType, '<UPPER>') 抛 AttributeError。

        源码 get_service 先 `getattr(SupportedVSType, vector_store_type.upper())`
        将字符串映射为类常量；未知 vs_type 无对应类属性，直接 AttributeError，
        不会进入任何 service 分支（故无需 fake 模块）。
        """
        mocker.patch(
            "server.knowledge_base.kb_service.base.load_kb_from_db",
            return_value=("my_kb", "unsupported_vs", "text-embedding-v3"),
        )

        with pytest.raises(AttributeError):
            KBServiceFactory.get_service_by_name("my_kb")

    @pytest.fixture(autouse=True)
    @staticmethod
    def _neutralize_kbs_config(mocker):
        """安全网：neutralize base.kbs_config，避免任何意外读取真实中间件配置。

        get_service_by_name 路径本身不读 kbs_config，真实 service 子模块也
        已被 fake 模块替换不会加载，此 patch 仅作双保险。
        """
        mocker.patch("server.knowledge_base.kb_service.base.kbs_config", {})