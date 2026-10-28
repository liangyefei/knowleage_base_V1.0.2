"""task_factory 健壮性测试。"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path
from unittest.mock import patch

# 确保项目根在 sys.path
_PROJECT_ROOT = Path(__file__).resolve().parents[2]
if str(_PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT))

from server.comm.task_factory import (
    CreateKnowledgeTask,
    _UploadDocumentTask,
)
from server.utils import BaseResponse


class TestUploadTaskFilePathMissing:
    """P2: _UploadDocumentTask payload 缺失 filePath 时 no-op 200，不抛 KeyError。"""

    @staticmethod
    def test_upload_task_missing_filepath_no_op():
        """payload 不含 filePath → 返回 code=200，不抛 KeyError。"""
        task = _UploadDocumentTask()
        envelope = {
            "kbId": "kb1",
            "documentId": "d1",
            "eventType": "DOCUMENT_PUBLISHED",
            "payload": {"documentId": "d1"},  # 无 filePath
        }
        res = task.request(envelope)
        assert res.code == 200, f"预期 code=200（no-op），实际: {res.code}"
        assert "filePath" in res.msg or "缺失" in res.msg, f"预期 msg 提示缺失，实际: {res.msg}"


class TestTriggerAsyncFailure:
    """P2: trigger_index_async / trigger_reindex_async 失败不影响主操作返回值。"""

    @staticmethod
    def test_create_task_trigger_index_failure_returns_200():
        """create_kb 成功但 trigger_index_async 抛异常 → 仍返回 code=200。"""
        _KBID_KEY = 'kbId'
        _KB_NAME_KEY = 'kb1'
        task = CreateKnowledgeTask()
        envelope = {_KBID_KEY: _KB_NAME_KEY, "eventType": "KB_CREATED", "payload": {_KBID_KEY: _KB_NAME_KEY}}

        # create_kb 在模块级 from server.knowledge_base.kb_api import create_kb → mock task_factory.create_kb
        # trigger_index_async 在函数内 lazy import → mock dual_mcp_client.trigger_index_async
        with patch("server.comm.task_factory.create_kb") as mock_create, \
             patch("server.comm.dual_mcp_client.trigger_index_async") as mock_trigger:
            mock_create.return_value = BaseResponse(code=200, msg="ok", data={_KBID_KEY: _KB_NAME_KEY})
            mock_trigger.side_effect = RuntimeError("trigger failed")
            res = task.request(envelope)
        assert res.code == 200, f"预期 code=200（trigger 失败不影响），实际: {res.code}"
        # 确认 trigger 确实被调用（排除因未走到 trigger 而通过的假阳性）
        mock_trigger.assert_called_once_with(_KB_NAME_KEY)

    @staticmethod
    def test_upload_task_trigger_reindex_failure_returns_200():
        """upload 成功但 trigger_reindex_async 抛异常 → 仍返回 code=200。"""
        _DOC_ID_KEY = 'documentId'
        _FILE_ID_KEY = 'd1'
        task = _UploadDocumentTask()
        envelope = {
            "kbId": "kb1",
            _DOC_ID_KEY: _FILE_ID_KEY,
            "eventType": "DOCUMENT_PUBLISHED",
            "payload": {
                _DOC_ID_KEY: _FILE_ID_KEY,
                "filePath": "kb1/test.txt",  # 相对路径，符合 resolve_sfs_path 约定
                "fileType": "code",
            },
        }

        # resolve_sfs_path mock 避免依赖 SFS_MOUNT_PATH 配置；
        # os.path.isfile mock 让代码走过文件存在检查到达 upload（避免文件不存在早退 no-op 假阳性）；
        # upload_docs_use_path mock 返回 200；is_code_file=True 使 trigger 被调用；trigger 抛异常。
        with patch("server.comm.task_factory.resolve_sfs_path", return_value="/mocked/abs/test.txt"), \
             patch("os.path.isfile", return_value=True), \
             patch("server.comm.task_factory.upload_docs_use_path") as mock_upload, \
             patch("server.comm.dual_mcp_client.is_code_file", return_value=True), \
             patch("server.comm.dual_mcp_client.trigger_reindex_async") as mock_trigger:
            mock_upload.return_value = BaseResponse(code=200, msg="ok", data={_DOC_ID_KEY: _FILE_ID_KEY})
            mock_trigger.side_effect = RuntimeError("trigger failed")
            res = task.request(envelope)
        assert res.code == 200, f"预期 code=200（trigger 失败不影响），实际: {res.code}"
        # 确认 trigger 确实被调用（排除因未走到 trigger 而通过的假阳性）
        mock_trigger.assert_called_once_with("kb1")
