-- =====================================================================
-- migration_013_upload_session_purpose
-- tus 上传会话支持"文档编辑 / 版本覆盖"用途（断点续传扩展到单文档换文件）
--   1. batch_id 置可空：doc 用途会话不隶属导入批次（batch-import 仍必填，应用层校验）
--   2. purpose 列：BATCH_IMPORT / DOC_EDIT / VERSION_OVERRIDE（存量行默认 BATCH_IMPORT，行为不变）
--   3. target_document_id 列：doc 用途的目标文档（batch-import 为 NULL）
--   4. 断点发现端点索引：按目标文档 + 状态查活跃会话
-- 幂等：可重复执行（IF EXISTS / IF NOT EXISTS）
-- 同步基线：docs/sql/init.sql 不含 upload_sessions（建表 DDL 未入库），本脚本为唯一增量来源
-- =====================================================================

ALTER TABLE "upload_sessions" ALTER COLUMN "batch_id" DROP NOT NULL;

ALTER TABLE "upload_sessions"
    ADD COLUMN IF NOT EXISTS "purpose" VARCHAR NOT NULL DEFAULT 'BATCH_IMPORT';

ALTER TABLE "upload_sessions"
    ADD COLUMN IF NOT EXISTS "target_document_id" VARCHAR;

CREATE INDEX IF NOT EXISTS "idx_sessions_target_doc"
    ON "upload_sessions" ("target_document_id", "status");
