"""文件级多版本管理：版本登记 / 查询 / 回滚 / 清理（纯 String 版本标签方案）。

核心约定：
- 版本标识 = file_version_label（String，后端约定，格式任意不递增，如
  "v1.0" / "rev-202610"）；空则模型端用 sha[:8] 兜底，永不为空。
- 内容寻址 = content_sha256（blob 寻址键，同内容天然去重）。
- 时序依据 = id 自增主键 + create_time（替代原 INT version 的单调递增）。
  "最新版本" = 同 (kb_name, file_name) 下 id desc 第一行（未删）。

登记时机：
- 覆盖写入前必须先登记旧内容（register_superseded，旧内容即将丢失）；
- 上传成功后 best-effort 登记当前版本（record_current，带 label/tags）。
回滚 = 把目标版本 blob 写回当前路径，再走常规向量化/重索引链路（作为
新版本登记；内容哈希决定相同内容 chunk 零成本复用）。
清理 = 每文件保留最近 N 版（默认 3）：版本区 blob（引用计数）+ history
记录；mempalace chunk 由重索引 wipe 管理（MemPalace 已合并进 dual-mcp，
本模块不再删 chunk）。
"""
import hashlib
import os
import shutil
import threading
import time
from contextlib import contextmanager

from sqlalchemy import func, text
from sqlalchemy import inspect as sa_inspect

from configs import FILE_VERSION_KEEP, KW_ROOT_PATH, logger
from server.db.base import Base, engine
from server.db.models.file_version_history_model import FileVersionHistoryModel
from server.db.session import session_scope, with_session


def _ensure_table():
    """部署环境无 init_db 步骤，导入时自愈建表（幂等）。"""
    try:
        Base.metadata.create_all(engine, tables=[FileVersionHistoryModel.__table__])
    except Exception as e:
        logger.warning(f"[file-version] 建表自检失败: {e}")


def _ensure_columns():
    """幂等补充 file_version_label / tags 两列（存量表由旧 version 方案迁移）。

    旧表已有 version(INT) 列；纯 String 方案不再使用它（保留为死数据，不
    主动 DROP 避免误删）。新增的两列若缺失则 ALTER TABLE 补上（DEFAULT ''
    向后兼容存量行）。
    """
    try:
        insp = sa_inspect(engine)
        tbl = FileVersionHistoryModel.__tablename__
        if not insp.has_table(tbl):
            return  # _ensure_table 会建表（含新列）
        cols = {c["name"] for c in insp.get_columns(tbl)}
        missing = []
        if "file_version_label" not in cols:
            missing.append("file_version_label VARCHAR(64) DEFAULT ''")
        if "tags" not in cols:
            missing.append("tags VARCHAR(255) DEFAULT ''")
        if not missing:
            return
        with engine.begin() as conn:
            for col_def in missing:
                conn.execute(text(f"ALTER TABLE {tbl} ADD COLUMN {col_def}"))
        logger.info(f"[file-version] 补列完成: {missing}")
    except Exception as e:
        logger.warning(f"[file-version] 补列自检失败（存量表可能缺列）: {e}")


_ensure_table()
_ensure_columns()


@contextmanager
def _db():
    with session_scope() as session:
        yield session


def _ci_eq(column, value: str):
    """大小写不敏感的精确匹配（替代 ilike，避免 _ / % 通配符误匹配近名文件）。

    P1-2：SQL ILIKE 中 _ 匹配任意单字符、% 匹配任意序列，文件名含 _ 会误匹配
    近名文件（如 "rev-1_0" 匹配 "rev-1X0"）。改用 func.lower == func.lower
    做精确匹配（大小写不敏感，无通配符语义）。版本历史表小，性能无虞。
    """
    return func.lower(column) == func.lower(value)


def get_history_max_id(kb_name: str, file_name: str = None) -> int:
    """获取 (kb, file?) 当前最大 history id（删除路径标 deleted 的上界）。

    P1-3：delete/archive/recreate 删 Milvus chunk 与 mark_history_deleted 之间
    若有并发 upload 写入新 history 行，mark_history_deleted 的 WHERE 无 id 上界
    会误标新行 → 新内容被标 deleted → 后续同内容重传 record_current 查不到
    非 deleted top → 重插行 + 重复 add。调用方在删除操作前捕获 max_id 传入
    mark_history_deleted 作上界（id <= max_id 才标）。
    """
    try:
        with _db() as db:
            q = (db.query(FileVersionHistoryModel.id)
                 .filter(_ci_eq(FileVersionHistoryModel.kb_name, kb_name),
                         FileVersionHistoryModel.status != "deleted"))
            if file_name:
                q = q.filter(_ci_eq(FileVersionHistoryModel.file_name, file_name))
            row = q.order_by(FileVersionHistoryModel.id.desc()).first()
            return row[0] if row else 0
    except Exception as e:
        logger.warning(f"[file-version] 获取 {kb_name}/{file_name or '*'} max_id 失败: {e}")
        return 0


def _version_area_root() -> str:
    """版本区根目录 = KW_ROOT_PATH/file-versions。"""
    return os.path.join(KW_ROOT_PATH, "file-versions")


def _blob_path(kb_name: str, sha: str) -> str:
    return os.path.join(_version_area_root(), kb_name, sha[:2], sha)


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _sha256_file(path: str) -> str | None:
    try:
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        return h.hexdigest()
    except OSError:
        return None


def _tags_to_str(tags) -> str:
    """规范化标签为逗号分隔串（去空白/去重/去空/保序）。

    接受 list/tuple/逗号串，统一输出 "GPIO,UART" 形式。
    """
    if not tags:
        return ""
    if isinstance(tags, str):
        items = tags.split(",")
    else:
        items = list(tags)
    seen, out = set(), []
    for t in items:
        t = str(t).strip()
        if t and t not in seen:
            seen.add(t)
            out.append(t)
    return ",".join(out)


def _tags_from_str(s: str) -> list[str]:
    """逗号分隔串 → 标签列表（去空白/去重/去空/保序）。"""
    return [t for t in _tags_to_str(s).split(",") if t]


@with_session
def get_current_version(session, kb_name: str, file_name: str) -> str:
    """当前版本标签 = history 表中该文件 id desc 第一行（未删）的 label。

    无记录返回 ""（调用方据此判断"无版本历史"）。注意不能用
    knowledge_file.file_version：update_doc 是 delete+add 路径，记录被删
    重建后恒为 default，从不递增。
    """
    row = (session.query(FileVersionHistoryModel)
           .filter(_ci_eq(FileVersionHistoryModel.kb_name, kb_name),
                   _ci_eq(FileVersionHistoryModel.file_name, file_name),
                   FileVersionHistoryModel.status != "deleted")
           .order_by(FileVersionHistoryModel.id.desc())
           .first())
    return (row.file_version_label or "") if row else ""


def register_superseded(kb_name: str, file_name: str, file_path: str) -> str | None:
    """覆盖写入前登记当前（即将被取代的）内容；返回该内容的版本标签。

    按内容 sha 查历史行：已在历史中（正常覆盖场景的当前版本、或回滚残留
    的旧版本）→ 幂等返回该行 label，**绝不新增行**（磁盘内容 ≠ 最新版本
    时如新增会污染版本序列）；不在历史中（存量文件首次覆盖）→ 登记为
    新行（label 兜底 sha[:8]，tags 空）。文件不存在或读失败返回 None。

    P1-4：archive/delete/recreate 标 history deleted 后，同内容重传时本函数
    查 status != deleted 无行 → 原逻辑插新行 → record_current 命中此行
    is_new=False → 跳过 Milvus add → "已入库但搜不到"。改为：查非 deleted
    无行时再查 deleted 同 sha 行，若存在则不插新行（返回 label），让
    record_current 查不到非 deleted top → is_new=True → 正常 add。
    """
    try:
        if not os.path.isfile(file_path):
            return None
        with open(file_path, "rb") as f:
            data = f.read()
        sha = _sha256_bytes(data)
        blob = _blob_path(kb_name, sha)
        if not os.path.isfile(blob):
            os.makedirs(os.path.dirname(blob), exist_ok=True)
            tmp = blob + ".tmp"
            with open(tmp, "wb") as f:
                f.write(data)
            os.replace(tmp, blob)
        with _db() as db:
            row = (db.query(FileVersionHistoryModel)
                   .filter(_ci_eq(FileVersionHistoryModel.kb_name, kb_name),
                           _ci_eq(FileVersionHistoryModel.file_name, file_name),
                           FileVersionHistoryModel.content_sha256 == sha,
                           FileVersionHistoryModel.status != "deleted")
                   .order_by(FileVersionHistoryModel.id.desc())
                   .first())
            if row:
                return row.file_version_label or sha[:8]
            # P1-4：无非 deleted 行时查 deleted 同 sha 行（archive 后同内容重传）
            deleted_row = (db.query(FileVersionHistoryModel)
                           .filter(_ci_eq(FileVersionHistoryModel.kb_name, kb_name),
                                   _ci_eq(FileVersionHistoryModel.file_name, file_name),
                                   FileVersionHistoryModel.content_sha256 == sha,
                                   FileVersionHistoryModel.status == "deleted")
                           .order_by(FileVersionHistoryModel.id.desc())
                           .first())
            if deleted_row:
                # 内容曾被有意移除（archive/delete/recreate），不重插行（否则
                # record_current 会命中此行误判 is_new=False 跳过 Milvus add）。
                # 返回 label，让 record_current 查不到非 deleted top → is_new=True
                # → 正常 add
                return deleted_row.file_version_label or sha[:8]
            label = sha[:8]  # 存量文件首次覆盖，无后端 label → 兜底
            _insert_history(db, kb_name, file_name, file_path,
                            file_version_label=label, tags_str="",
                            sha=sha, size=len(data))
            return label
    except Exception as e:
        logger.warning(f"[file-version] 登记 {kb_name}/{file_name} 旧版本失败"
                       f"（不影响上传，该版本不可回滚）: {e}")
        return None


def record_current(kb_name: str, file_name: str, file_path: str,
                   **kwargs) -> tuple[str, bool] | None:
    """上传成功后把当前内容登记为新版本行（label 来自后端，空则 sha[:8] 兜底）。

    后 3 个参数（通过 kwargs 传入，避免签名参数过多）:
        file_version_label: str - 版本 label（默认 ""，空则用 sha[:8] 兜底）
        tags: list[str] | None - 标签列表（默认 None）
        note: str - 备注（默认 ""）

    P2 版本共存：返回二元组 (label, is_new)。
    - is_new=True：本次登记新增了 history 行（新内容/新版本），调用方应
      走 update_doc/add_doc 把 chunk 写入 Milvus。
    - is_new=False：最新行 sha 与当前内容相同（同内容重传/回滚恢复后的
      重复写入），不新增行，调用方应跳过 Milvus add（避免重复加 chunk）。
    失败（文件不存在 / sha 算不出 / 异常）→ 返回 None（保持原失败语义，
    调用方必须先判 None 再解包 Tuple）。best-effort，失败仅告警。
    """
    file_version_label = kwargs.get("file_version_label", "")
    tags = kwargs.get("tags")
    note = kwargs.get("note", "")
    try:
        if not os.path.isfile(file_path):
            return None
        sha = _sha256_file(file_path)
        if not sha:
            return None
        size = os.path.getsize(file_path)
        label = (file_version_label or "").strip() or sha[:8]
        tags_str = _tags_to_str(tags)
        with _db() as db:
            top = (db.query(FileVersionHistoryModel)
                   .filter(_ci_eq(FileVersionHistoryModel.kb_name, kb_name),
                           _ci_eq(FileVersionHistoryModel.file_name, file_name),
                           FileVersionHistoryModel.status != "deleted")
                   .order_by(FileVersionHistoryModel.id.desc())
                   .first())
            if top and top.content_sha256 == sha:
                # 同内容重传：不新增行，返回旧 label + is_new=False
                return (top.file_version_label or sha[:8], False)
            _insert_history(db, kb_name, file_name, file_path,
                            file_version_label=label, tags_str=tags_str,
                            sha=sha, size=size, note=note)
            return (label, True)
    except Exception as e:
        logger.warning(f"[file-version] 登记 {kb_name}/{file_name} 当前版本失败: {e}")
        return None


def _insert_history(db, kb_name: str, file_name: str, file_path: str,
                    **kwargs):
    """始终 INSERT 新行（纯 String 方案无 version 唯一约束，id 自增保证唯一）。

    在调用方 with _db() as db 事务内执行，随块退出统一提交。

    后 5 个参数（通过 kwargs 传入，避免签名参数过多）:
        file_version_label: str - 版本 label（必传）
        tags_str: str - 序列化后的 tags 字符串（必传）
        sha: str - 文件内容 sha256（必传）
        size: int - 文件大小（必传）
        note: str - 备注（默认 ""）
    """
    file_version_label = kwargs.get("file_version_label", "")
    tags_str = kwargs.get("tags_str", "")
    sha = kwargs.get("sha", "")
    size = kwargs.get("size", 0)
    note = kwargs.get("note", "")
    db.add(FileVersionHistoryModel(
        kb_name=kb_name, file_name=file_name, file_path=file_path,
        file_version_label=file_version_label, tags=tags_str,
        content_sha256=sha, size=size, note=note, status="superseded"))


def list_versions(kb_name: str, file_name: str) -> list[dict]:
    """列出文件历史版本（id desc，第一行为当前版本）。

    每项含 file_version_label/tags/sha/size/note/create_time/is_current/
    rollbackable。label 为 "" 的（理论不存在，兜底 sha[:8] 已填）前端可
    显示为"未命名版本"。
    """
    with _db() as db:
        rows = (db.query(FileVersionHistoryModel)
                .filter(_ci_eq(FileVersionHistoryModel.kb_name, kb_name),
                        _ci_eq(FileVersionHistoryModel.file_name, file_name),
                        FileVersionHistoryModel.status != "deleted")
                .order_by(FileVersionHistoryModel.id.desc())
                .all())
        current_id = rows[0].id if rows else 0
        return [{
            "version": r.file_version_label or "",
            "tags": _tags_from_str(r.tags or ""),
            "content_sha256": r.content_sha256,
            "size": r.size,
            "note": r.note,
            "create_time": str(r.create_time) if r.create_time else "",
            "is_current": r.id == current_id,
            "rollbackable": r.id != current_id and os.path.isfile(_blob_path(kb_name, r.content_sha256)),
        } for r in rows]


def get_rollback_target(kb_name: str, file_name: str,
                        file_version_label: str) -> dict | None:
    """取可回滚目标（label 命中且 blob 存在；同 label 多行取 id desc 第一行）。"""
    if not file_version_label:
        return None
    with _db() as db:
        row = (db.query(FileVersionHistoryModel)
               .filter(_ci_eq(FileVersionHistoryModel.kb_name, kb_name),
                       _ci_eq(FileVersionHistoryModel.file_name, file_name),
                       FileVersionHistoryModel.file_version_label == file_version_label,
                       FileVersionHistoryModel.status != "deleted")
               .order_by(FileVersionHistoryModel.id.desc())
               .first())
        if not row:
            return None
        blob = _blob_path(kb_name, row.content_sha256)
        if not os.path.isfile(blob):
            return None
        return {"file_path": row.file_path, "blob_path": blob,
                "sha256": row.content_sha256, "from_version": file_version_label}


def restore_version(kb_name: str, file_name: str,
                    file_version_label: str) -> str | None:
    """把目标版本内容写回当前路径，返回 file_path；失败返回 None。"""
    target = get_rollback_target(kb_name, file_name, file_version_label)
    if not target:
        return None
    file_path = target["file_path"]
    os.makedirs(os.path.dirname(file_path), exist_ok=True)
    shutil.copyfile(target["blob_path"], file_path)
    return file_path


def cleanup_old_versions(kb_name: str) -> dict:
    """每文件保留最近 FILE_VERSION_KEEP 版：blob（引用计数）+ 记录。

    mempalace chunk 不再由本模块删（MemPalace 合并进 dual-mcp，重索引 wipe
    管理）。best-effort 幂等：任一步失败保留记录（status 不变），下次重试。
    返回 {file: [被清理的版本 label]}。
    """
    cleaned: dict = {}
    try:
        with _db() as db:
            files = []
            for r in (db.query(FileVersionHistoryModel.file_name)
                      .filter(_ci_eq(FileVersionHistoryModel.kb_name, kb_name),
                              FileVersionHistoryModel.status != "deleted")
                      .distinct().all()):
                files.append(r[0])
            for file_name in files:
                rows = (db.query(FileVersionHistoryModel)
                        .filter(_ci_eq(FileVersionHistoryModel.kb_name, kb_name),
                                _ci_eq(FileVersionHistoryModel.file_name, file_name),
                                FileVersionHistoryModel.status != "deleted")
                        .order_by(FileVersionHistoryModel.id.desc())
                        .all())
                stale = rows[FILE_VERSION_KEEP:]
                if not stale:
                    continue
                removed = []
                for row in stale:
                    if _drop_version(db, kb_name, file_name, row):
                        removed.append(row.file_version_label or row.content_sha256[:8])
                if removed:
                    cleaned[file_name] = removed
    except Exception as e:
        logger.warning(f"[file-version] 清理 {kb_name} 旧版本失败（下次重试）: {e}")
    return cleaned


def _drop_version(db, kb_name: str, file_name: str, row) -> bool:
    """删除单个历史版本：blob 引用计数为 0 时删 blob，再标记记录 deleted。

    mempalace chunk 由 dual-mcp 重索引 wipe 管理，本模块不删（_mempalace_delete_chunks
    已降级为 no-op，保留函数签名兼容历史调用）。
    """
    # blob（引用计数：还有其他行引用同一 sha 时不删）
    others = (db.query(FileVersionHistoryModel)
              .filter(_ci_eq(FileVersionHistoryModel.kb_name, kb_name),
                      FileVersionHistoryModel.content_sha256 == row.content_sha256,
                      FileVersionHistoryModel.id != row.id,
                      FileVersionHistoryModel.status != "deleted")
              .count())
    if not others:
        try:
            blob = _blob_path(kb_name, row.content_sha256)
            if os.path.isfile(blob):
                os.remove(blob)
        except OSError as e:
            logger.warning(f"[file-version] 删 blob 失败（保留记录待重试）: {e}")
            return False
    row.status = "deleted"
    return True


def _mempalace_delete_chunks(kb_name: str, source_file: str,
                             file_version_label: str = "") -> bool:
    """no-op：MemPalace 已合并进 dual-mcp，chunk 由重索引 wipe 管理。

    保留函数签名避免外部调用点报错；恒返回 True（视为无 chunk 需删）。
    """
    return True


_version_map_cache: dict = {}  # {kb_name: (timestamp, map)}
_VERSION_MAP_TTL = 60  # 秒


def build_files_version_map(kb_name: str) -> dict:
    """构建 dual-mcp 版本透出映射：{代码文件相对路径: 当前版本 label(String)}。

    相对路径基准 = /projects/<kbId>/code/（dual-mcp 索引根）。当前版本 label
    取 history 中该文件 id desc 第一行（record_current 已保证当前版本有记录；
    无记录的文件不在映射中，dual-mcp 侧视为无版本）。

    P2：带 TTL 缓存（60 秒），避免检索路每次 _search_milvus_impl / do_search
    都查 DB。缓存失效点由调用方（upload_docs/update_docs/delete_doc 端点成功
    后）显式 pop，或自然 TTL 过期。
    """
    now = time.time()
    cached = _version_map_cache.get(kb_name)
    if cached and now - cached[0] < _VERSION_MAP_TTL:
        return cached[1]
    out: dict = {}
    with _db() as db:
        rows = (db.query(FileVersionHistoryModel)
                .filter(_ci_eq(FileVersionHistoryModel.kb_name, kb_name),
                        FileVersionHistoryModel.status != "deleted")
                .order_by(FileVersionHistoryModel.id.desc())
                .all())
        seen: set = set()
        for r in rows:
            rel = r.file_name
            if rel in seen:
                continue  # 已取 id desc 第一行（最新），后续同 rel 跳过
            seen.add(rel)
            out[rel] = r.file_version_label or r.content_sha256[:8]
    _version_map_cache[kb_name] = (now, out)
    return out


def invalidate_version_map_cache(kb_name: str) -> None:
    """端点写入/删除成功后显式失效该 kb 的版本映射缓存。

    build_files_version_map 走 60 秒 TTL，上传新版本后旧缓存会让默认当前
    版本过滤漏掉新 label（新 chunk 被排除，最多 60 秒内检索到旧版本）。
    upload_docs / update_docs / delete_docs 等变更 history 的端点成功后
    调本函数 pop 缓存，下次检索重建。
    """
    _version_map_cache.pop(kb_name, None)


def mark_history_deleted(kb_name: str, file_name: str = None,
                         max_id: int = None) -> int:
    """标 history 行 status=deleted。file_name=None 标整库。返回标记行数。best-effort。

    Bug A 修复：delete/archive/recreate 删了 Milvus chunk 却没标 history deleted，
    导致 record_current 的 sha 去重命中旧 top 行 → is_new=False → 跳过 Milvus add
    → 文件"已入库但搜不到"。本函数在删除路径成功后调用，保证 record_current
    去重前提不被破坏。行保留（可追溯），只改 status。

    P1-3：max_id 上界防并发误标新行。调用方在删除操作前捕获 max_id 传入，
    本函数只标 id <= max_id 的行，避免并发 upload 新写入的行被误标 deleted
    （否则新内容被标 deleted，后续同内容重传 record_current 查不到非 deleted
    top → 重插行 + 重复 add）。
    """
    try:
        with _db() as db:
            q = (db.query(FileVersionHistoryModel)
                 .filter(_ci_eq(FileVersionHistoryModel.kb_name, kb_name),
                         FileVersionHistoryModel.status != "deleted"))
            if file_name:
                q = q.filter(_ci_eq(FileVersionHistoryModel.file_name, file_name))
            if max_id is not None:
                q = q.filter(FileVersionHistoryModel.id <= max_id)
            cnt = q.update({"status": "deleted"}, synchronize_session=False)
            db.commit()
            if cnt:
                logger.info(f"[file-version] 标记 {kb_name}/{file_name or '*'} "
                            f"history deleted: {cnt} 行 (max_id={max_id})")
            return cnt
    except Exception as e:
        logger.warning(f"[file-version] 标记 {kb_name}/{file_name} history deleted 失败: {e}")
        return 0


_VERSION_DIFF_MAX_BYTES = 5 * 1024 * 1024  # 单侧内容超此值降级统计级差异


def _read_version_bytes(kb_name: str, row: dict, current_row: dict | None) -> bytes | None:
    """读某版本内容：blob 优先；当前版本 blob 懒创建缺失时读活文件（校验 sha）。"""
    _FILE_PATH_KEY = 'file_path'
    blob = _blob_path(kb_name, row["sha"])
    if os.path.isfile(blob):
        try:
            with open(blob, "rb") as f:
                return f.read()
        except OSError:
            return None
    _is_current_version = current_row is not None and row["id"] == current_row["id"]
    if (_is_current_version and row[_FILE_PATH_KEY]
            and os.path.isfile(row[_FILE_PATH_KEY])):
        try:
            with open(row[_FILE_PATH_KEY], "rb") as f:
                data = f.read()
            if _sha256_bytes(data) == row["sha"]:
                return data
        except OSError:
            logger.debug("读取历史文件失败: {}", row.get(_FILE_PATH_KEY))
            pass
    return None


def diff_versions(kb_name: str, file_name: str, from_version=None, to_version=None,
                  max_chars: int = 8000) -> dict:
    """对比同一文件两个版本的内容差异（供智能问答注入与前端展示）。

    版本参数接受 label 字符串/None：双缺省 → 最新 vs 次新；只给一个 →
    该版本 vs 其前一版（id desc 序列中的下一行）。返回 dict（found=False
    表示该文件无版本记录）：
    {found, file_name, from, to, from_sha, to_sha, from_size, to_size,
     identical, added, removed, diff, truncated, note}
    二进制（utf-8 解码失败）/ 超大（>5MB）/ 内容副本缺失 → 统计级（diff
    为空，note 说明原因）。from/to 字段为 label 字符串（直接透出，不转换）。
    """
    _NOTE_KEY = 'note'
    _LABEL_KEY = 'label'
    _SHA_KEY = 'sha'
    _SIZE_KEY = 'size'
    import difflib
    out = {"found": False, "file_name": file_name}
    if not file_name:
        out[_NOTE_KEY] = "未识别出文件名"
        return out
    with _db() as db:
        # session 关闭后 ORM 实例属性不可访问（DetachedInstanceError），物化为普通 dict
        rows = []
        for r in (db.query(FileVersionHistoryModel)
                  .filter(_ci_eq(FileVersionHistoryModel.kb_name, kb_name),
                          _ci_eq(FileVersionHistoryModel.file_name, file_name),
                          FileVersionHistoryModel.status != "deleted")
                  .order_by(FileVersionHistoryModel.id.desc())
                  .all()):
            rows.append({"id": r.id, _LABEL_KEY: r.file_version_label or "", _SHA_KEY: r.content_sha256,
                         _SIZE_KEY: r.size or 0, "file_path": r.file_path or ""})
    if not rows:
        return out
    out["found"] = True

    def _row_of(v):
        if v is None:
            return None
        _text = str(v).strip()
        if not _text:
            return None
        for r in rows:
            if r[_LABEL_KEY] == _text:
                return r
        return None

    if from_version is not None and to_version is not None:
        f_row, t_row = _row_of(from_version), _row_of(to_version)
    elif to_version is not None or from_version is not None:
        t_row = _row_of(to_version if to_version is not None else from_version)
        f_row = None
        if t_row is not None:
            for i, r in enumerate(rows):
                if r is t_row:
                    f_row = rows[i + 1] if i + 1 < len(rows) else None
                    break
    else:
        t_row = rows[0]
        f_row = rows[1] if len(rows) > 1 else None
    if t_row is None or f_row is None:
        out.update({"from": None, "to": None,
                    _NOTE_KEY: f"版本不存在或已被保留策略清理（可用版本：{', '.join(r[_LABEL_KEY] for r in rows if r[_LABEL_KEY])}）"})
        return out

    out.update({"from": f_row[_LABEL_KEY], "to": t_row[_LABEL_KEY],
                "from_sha": f_row[_SHA_KEY], "to_sha": t_row[_SHA_KEY],
                "from_size": f_row[_SIZE_KEY], "to_size": t_row[_SIZE_KEY],
                "identical": f_row[_SHA_KEY] == t_row[_SHA_KEY],
                "added": 0, "removed": 0, "diff": "", "truncated": False})
    if out["identical"]:
        out[_NOTE_KEY] = "两版本内容完全相同"
        return out

    f_data = _read_version_bytes(kb_name, f_row, rows[0])
    t_data = _read_version_bytes(kb_name, t_row, rows[0])
    if f_data is None or t_data is None:
        out[_NOTE_KEY] = "版本内容副本缺失（懒创建未补写），暂无法对比"
        return out
    if max(len(f_data), len(t_data)) > _VERSION_DIFF_MAX_BYTES:
        out[_NOTE_KEY] = f"文件过大（>{_VERSION_DIFF_MAX_BYTES // 1048576}MB），仅提供统计级差异"
        return out
    try:
        f_text, t_text = f_data.decode("utf-8"), t_data.decode("utf-8")
    except UnicodeDecodeError:
        out[_NOTE_KEY] = "二进制文件，仅提供统计级差异（大小/哈希）"
        return out

    a, b = f_text.splitlines(keepends=True), t_text.splitlines(keepends=True)
    sm = difflib.SequenceMatcher(None, a, b, autojunk=False)
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag in ("delete", "replace"):
            out["removed"] += i2 - i1
        if tag in ("insert", "replace"):
            out["added"] += j2 - j1
    diff_text = "".join(difflib.unified_diff(
        a, b, fromfile=f"{file_name} {out['from']}", tofile=f"{file_name} {out['to']}", n=3))
    if len(diff_text) > max_chars:
        diff_text = diff_text[:max_chars] + "\n…（diff 过长已截断，完整差异请减小版本跨度或看统计）"
        out["truncated"] = True
    out["diff"] = diff_text
    return out


@with_session
def remove_kb_history(session, kb_name: str) -> int:
    """删除知识库时清理版本记录与版本区 blob。

    history 残留会让同名重建库的版本标签混乱（内容哈希幂等只防重复内容，
    防不住 label 复用）。返回删除的记录数。
    """
    n = (session.query(FileVersionHistoryModel)
         .filter(_ci_eq(FileVersionHistoryModel.kb_name, kb_name))
         .delete(synchronize_session=False))
    try:
        shutil.rmtree(os.path.join(_version_area_root(), kb_name), ignore_errors=True)
    except OSError as e:
        logger.warning(f"[file-version] 清理版本区目录失败（残留可后续手动删）: {e}")
    return n


def cleanup_async(kb_name: str) -> None:
    """后台线程清理旧版本（上传链路末尾调用，不阻塞响应）。"""

    def _run():
        cleaned = cleanup_old_versions(kb_name)
        if cleaned:
            logger.info(f"[file-version] {kb_name} 清理旧版本: {cleaned}")

    threading.Thread(target=_run, daemon=True,
                     name="file-version-cleanup").start()
