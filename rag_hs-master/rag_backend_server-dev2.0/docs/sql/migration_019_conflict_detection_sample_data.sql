-- =====================================================================
-- 冲突检测模块示例数据（与 migration_019_conflict_detection.sql 配套）
-- 场景：谛听知识库（kb-diting）一次手动全量扫描 + 一次自动增量扫描，
--      发现 2 条冲突（一条 FACT 已提交 badcase、一条 SEMANTIC 已解决）。
-- 执行前提：documents / knowledge_bases 已存在对应 id（下面的示例 id 为
--   演示用占位，实际按环境替换）。可重复执行（先 DELETE 示例 id 再 INSERT）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 0. 清理旧示例数据（按示例 id 精准删除，避免污染）
-- ---------------------------------------------------------------------
DELETE FROM "conflict_record_scans"
 WHERE "record_id" IN ('cr-diting-uart-001','cr-diting-baud-002')
    OR "job_id" IN ('cj-manual-0001','cj-auto-0001');

DELETE FROM "conflict_record_items"
 WHERE "record_id" IN ('cr-diting-uart-001','cr-diting-baud-002');

DELETE FROM "conflict_badcase_tickets"
 WHERE "record_id" IN ('cr-diting-uart-001','cr-diting-baud-002');

DELETE FROM "conflict_resolutions"
 WHERE "record_id" IN ('cr-diting-uart-001','cr-diting-baud-002');

DELETE FROM "conflict_records"
 WHERE "id" IN ('cr-diting-uart-001','cr-diting-baud-002');

DELETE FROM "conflict_scan_jobs"
 WHERE "id" IN ('cj-manual-0001','cj-auto-0001');

DELETE FROM "conflict_settings" WHERE "id" = 'GLOBAL';

-- ---------------------------------------------------------------------
-- 1. conflict_settings：全局开关（默认关闭，示例打开便于演示）
-- ---------------------------------------------------------------------
INSERT INTO "conflict_settings" (
    "id", "kb_id", "auto_enabled", "updated_by", "updated_at"
) VALUES (
    'GLOBAL', NULL, TRUE, 'admin', '2026-10-15 10:00:00'
);

-- ---------------------------------------------------------------------
-- 2. conflict_scan_jobs
-- ---------------------------------------------------------------------
-- 2.1 立即检测（MANUAL / FULL）：勾选【谛听】知识库全量扫描，已完成
INSERT INTO "conflict_scan_jobs" (
    "id", "batch_no", "kb_id", "trigger_type", "mode",
    "scope_nodes", "scope_fingerprint", "status",
    "total_documents", "scanned_chunks",
    "new_conflict_count", "hit_conflict_count", "failed_items",
    "error", "started_at", "finished_at",
    "created_by", "created_at", "updated_at"
) VALUES (
    'cj-manual-0001', 'CHK-20261015-01', 'kb-diting', 'MANUAL', 'FULL',
    '[{"kbId":"kb-diting"}]'::json,
    'fp:sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08',
    'COMPLETED',
    126, 8342,
    2, 0, 0,
    NULL,
    '2026-10-15 09:30:00', '2026-10-15 09:32:15',
    'zhangsan', '2026-10-15 09:30:00', '2026-10-15 09:32:15'
);

-- 2.2 自动增量（AUTO / INCREMENTAL）：用户手册 v2.1 切片就绪后触发，已完成
INSERT INTO "conflict_scan_jobs" (
    "id", "batch_no", "kb_id", "trigger_type", "mode",
    "scope_nodes", "scope_fingerprint", "status",
    "total_documents", "scanned_chunks",
    "new_conflict_count", "hit_conflict_count", "failed_items",
    "error", "started_at", "finished_at",
    "created_by", "created_at", "updated_at"
) VALUES (
    'cj-auto-0001', 'CHK-20261015-02', 'kb-diting', 'AUTO', 'INCREMENTAL',
    '{"documentId":"doc-user-manual-v21","file_name":"/kb-files/谛听用户手册 v2.1.pdf","current_version":"v2.1"}'::json,
    NULL,
    'COMPLETED',
    1, 134,
    0, 1, 0,
    NULL,
    '2026-10-15 11:05:00', '2026-10-15 11:05:40',
    'SYSTEM', '2026-10-15 11:05:00', '2026-10-15 11:05:40'
);

-- ---------------------------------------------------------------------
-- 3. conflict_records
-- ---------------------------------------------------------------------
-- 3.1 FACT 冲突：UART 波特率上限不一致，已提交 badcase
INSERT INTO "conflict_records" (
    "id", "kb_id", "first_job_id", "last_seen_job_id",
    "fingerprint", "topic", "type", "raw_type", "severity", "summary",
    "location_count", "scope_label",
    "detected_started_at", "detected_finished_at", "last_seen_at",
    "badcase_status", "badcase_ticket_id", "badcase_submitted_by", "badcase_submitted_at",
    "created_at", "updated_at"
) VALUES (
    'cr-diting-uart-001', 'kb-diting', 'cj-manual-0001', 'cj-auto-0001',
    'fp:sha256:a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2',
    'UART 波特率上限参数不一致',
    'FACT', NULL, 'HIGH',
    '用户手册称最高 921600bps，数据手册称最高 3Mbps，二者矛盾。',
    2, '跨文档多处',
    '2026-10-15 09:30:00', '2026-10-15 09:32:15', '2026-10-15 11:05:40',
    'SUBMITTED', 'BC-20261015-0037', 'zhangsan', '2026-10-15 14:20:00',
    '2026-10-15 09:32:15', '2026-10-15 14:20:00'
);

-- 3.2 NEAR_DUPLICATE 冲突：近重复内容，未提 badcase
INSERT INTO "conflict_records" (
    "id", "kb_id", "first_job_id", "last_seen_job_id",
    "fingerprint", "topic", "type", "raw_type", "severity", "summary",
    "location_count", "scope_label",
    "detected_started_at", "detected_finished_at", "last_seen_at",
    "badcase_status", "badcase_ticket_id", "badcase_submitted_by", "badcase_submitted_at",
    "created_at", "updated_at"
) VALUES (
    'cr-diting-baud-002', 'kb-diting', 'cj-manual-0001', 'cj-manual-0001',
    'fp:sha256:f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a10000',
    '安装指引段落语义近重复',
    'NEAR_DUPLICATE', NULL, 'LOW',
    '"快速开始"与"安装指南"两章描述相近，建议合并去重。',
    2, '跨文档多处',
    '2026-10-15 09:30:00', '2026-10-15 09:32:15', '2026-10-15 09:32:15',
    'NONE', NULL, NULL, NULL,
    '2026-10-15 09:32:15', '2026-10-15 16:10:00'
);

-- ---------------------------------------------------------------------
-- 4. conflict_record_items（证据快照，每条冲突 2 条）
-- ---------------------------------------------------------------------
-- 4.1 UART 冲突证据 1：用户手册（基准）
INSERT INTO "conflict_record_items" (
    "id", "record_id", "document_id",
    "doc_name_snapshot", "version_snapshot", "publish_status_snapshot",
    "chunk_index", "page_numbers", "titles_path", "line_hint",
    "excerpt", "is_baseline", "chunk_updated_at", "sort_no", "created_at"
) VALUES (
    'ci-uart-001-a', 'cr-diting-uart-001', 'doc-user-manual-v21',
    '谛听用户手册 v2.1.pdf', 'v2.1', 'PUBLISHED',
    213, '[88]'::json, '第 9 章 UART 接口 > 9.5 电气特性', 4,
    '支持波特率 9600 ~ 921600 bps', TRUE,
    '2026-09-20 15:10:00', 1, '2026-10-15 09:32:15'
);

-- 4.2 UART 冲突证据 2：数据手册
INSERT INTO "conflict_record_items" (
    "id", "record_id", "document_id",
    "doc_name_snapshot", "version_snapshot", "publish_status_snapshot",
    "chunk_index", "page_numbers", "titles_path", "line_hint",
    "excerpt", "is_baseline", "chunk_updated_at", "sort_no", "created_at"
) VALUES (
    'ci-uart-001-b', 'cr-diting-uart-001', 'doc-datasheet-v13',
    '谛听数据手册 v1.3.pdf', 'v1.3', 'PUBLISHED',
    74, '[31]'::json, '4.2 串行接口参数', 7,
    'UART 最高支持 3Mbps', FALSE,
    '2026-08-15 10:00:00', 2, '2026-10-15 09:32:15'
);

-- 4.3 近重复冲突证据 1
INSERT INTO "conflict_record_items" (
    "id", "record_id", "document_id",
    "doc_name_snapshot", "version_snapshot", "publish_status_snapshot",
    "chunk_index", "page_numbers", "titles_path", "line_hint",
    "excerpt", "is_baseline", "chunk_updated_at", "sort_no", "created_at"
) VALUES (
    'ci-baud-002-a', 'cr-diting-baud-002', 'doc-user-manual-v21',
    '谛听用户手册 v2.1.pdf', 'v2.1', 'PUBLISHED',
    45, '[12]'::json, '第 2 章 快速开始', NULL,
    '解压安装包后执行 ./install.sh 按提示完成安装...', FALSE,
    '2026-09-20 15:10:00', 1, '2026-10-15 09:32:15'
);

-- 4.4 近重复冲突证据 2
INSERT INTO "conflict_record_items" (
    "id", "record_id", "document_id",
    "doc_name_snapshot", "version_snapshot", "publish_status_snapshot",
    "chunk_index", "page_numbers", "titles_path", "line_hint",
    "excerpt", "is_baseline", "chunk_updated_at", "sort_no", "created_at"
) VALUES (
    'ci-baud-002-b', 'cr-diting-baud-002', 'doc-install-guide-v10',
    '谛听安装指南 v1.0.pdf', 'v1.0', 'PUBLISHED',
    12, '[3]'::json, '1.2 安装步骤', NULL,
    '解压安装包后运行 install.sh，跟随向导完成部署...', FALSE,
    '2026-07-01 09:00:00', 2, '2026-10-15 09:32:15'
);

-- ---------------------------------------------------------------------
-- 5. conflict_resolutions（处理记录：近重复那条已解决）
-- ---------------------------------------------------------------------
INSERT INTO "conflict_resolutions" (
    "id", "record_id", "action", "remark", "handled_by", "handled_by_name", "handled_at"
) VALUES (
    'rs-baud-002-01', 'cr-diting-baud-002', 'RESOLVED',
    '已确认两处为同一安装指引的不同表述，保留用户手册版本，后续在安装指南增加跳转引用。',
    'lisi', '李四', '2026-10-15 16:10:00'
);

-- ---------------------------------------------------------------------
-- 6. conflict_badcase_tickets（UART 那条已提 badcase）
-- ---------------------------------------------------------------------
INSERT INTO "conflict_badcase_tickets" (
    "id", "record_id", "kb_id", "topic_snapshot", "type_snapshot",
    "description", "status", "external_ref", "submitted_by", "created_at"
) VALUES (
    'BC-20261015-0037', 'cr-diting-uart-001', 'kb-diting',
    'UART 波特率上限参数不一致', 'FACT',
    '冲突判定与实际情况不符，3Mbps 为早期工程样片参数，非现售规格。请数据手册侧确认修订。',
    'SUBMITTED', NULL, 'zhangsan', '2026-10-15 14:20:00'
);

-- ---------------------------------------------------------------------
-- 7. conflict_record_scans（多对多关联）
-- ---------------------------------------------------------------------
-- UART 冲突被手动批次首次发现、又被自动增量批次再次命中
INSERT INTO "conflict_record_scans" ("id", "record_id", "job_id", "seen_at")
VALUES
    ('crs-uart-manual-01', 'cr-diting-uart-001', 'cj-manual-0001', '2026-10-15 09:32:15'),
    ('crs-uart-auto-01',   'cr-diting-uart-001', 'cj-auto-0001',  '2026-10-15 11:05:40');

-- 近重复冲突仅被手动批次发现一次
INSERT INTO "conflict_record_scans" ("id", "record_id", "job_id", "seen_at")
VALUES
    ('crs-baud-manual-01', 'cr-diting-baud-002', 'cj-manual-0001', '2026-10-15 09:32:15');
