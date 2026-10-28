-- =====================================================================
-- migration_019_conflict_detection_alter_v2.sql
-- 需求简化：去掉冲突状态机（PENDING/RESOLVED/IGNORED/EXPIRED）
--   - 删除 conflict_records.status / expired_at 列
--   - 删除相关 CHECK 约束、状态相关索引
--   - 新增全量唯一索引（同 kb 同指纹防重复）
-- 适用：本地已经按 migration_019 建过表、需要升级到 v2 的环境
-- 上线全新环境时直接跑 migration_019_conflict_detection.sql（已包含这些改动）
-- =====================================================================

BEGIN;

-- 1. 删旧索引（依赖 status 列）
DROP INDEX IF EXISTS "uk_conflict_records_kb_fp_pending";
DROP INDEX IF EXISTS "idx_conflict_records_status_type";
DROP INDEX IF EXISTS "idx_conflict_records_expire";

-- 2. 删状态 CHECK 约束
ALTER TABLE "conflict_records" DROP CONSTRAINT IF EXISTS "ck_conflict_records_status";

-- 3. 删状态列
ALTER TABLE "conflict_records" DROP COLUMN IF EXISTS "status";
ALTER TABLE "conflict_records" DROP COLUMN IF EXISTS "expired_at";

-- 4. 新增全量唯一索引（同 kb 同指纹唯一，替代原 PENDING 部分唯一索引）
CREATE UNIQUE INDEX IF NOT EXISTS "uk_conflict_records_kb_fp"
    ON "conflict_records" ("kb_id", "fingerprint");

-- 5. 新增/调整索引（列表查询按 type / badcase_status 过滤）
CREATE INDEX IF NOT EXISTS "idx_conflict_records_type"
    ON "conflict_records" ("type");
CREATE INDEX IF NOT EXISTS "idx_conflict_records_badcase"
    ON "conflict_records" ("badcase_status");

-- 6. 清理列注释
COMMENT ON TABLE "conflict_records" IS '冲突记录（聚合根；同 kb 同指纹唯一 uk_conflict_records_kb_fp）';
COMMENT ON COLUMN "conflict_records"."last_seen_at" IS '最近一次命中时间（同指纹再次被扫描到时刷新）';

-- 7. JSON 列转 VARCHAR（Hibernate Reactive 对 json/jsonb 列读取有 bug，统一当字符串存）
ALTER TABLE "conflict_scan_jobs"
    ALTER COLUMN "scope_nodes" TYPE VARCHAR(1000) USING "scope_nodes"::VARCHAR;

ALTER TABLE "conflict_record_items"
    ALTER COLUMN "page_numbers" TYPE VARCHAR(255) USING "page_numbers"::VARCHAR;

COMMIT;
