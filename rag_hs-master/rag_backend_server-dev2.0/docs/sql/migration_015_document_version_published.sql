-- migration_015: document_versions.status 在线值 ACTIVE → PUBLISHED
--       背景：前端统一按 search/detail 返回的 versionStatus 显示文档状态，
--       草稿文档当前行曾返回 "ACTIVE" 被误显示为「发布中」。
--       修复：1) 在线值改名 PUBLISHED（历史版本行语义）；2) 当前版本行 versionStatus
--       改为镜像 documents.status（代码层，见 DocumentQueryService）。
--       本脚本迁移存量行并更新列注释；migration_014 的默认值由 init.sql 更新为 'PUBLISHED'。

UPDATE document_versions SET status = 'PUBLISHED' WHERE status = 'ACTIVE';

ALTER TABLE document_versions
    ALTER COLUMN status SET DEFAULT 'PUBLISHED';

COMMENT ON COLUMN document_versions.status IS '版本行状态：PUBLISHED-已发布在线；ARCHIVED-已归档（版本级下线）；DEPRECATED-已废弃（版本覆盖新归档恒为 PUBLISHED）';

-- ======================== 回滚（仅当镜像回退到 <=1.0.9 时执行） ========================
-- 1.0.9 及更早版本的幂等守卫比较 status='ACTIVE'，迁移后版本级归档/废弃会静默跳过；
-- 回滚镜像前必须先反向迁移：
--   UPDATE document_versions SET status = 'ACTIVE' WHERE status = 'PUBLISHED';
--   ALTER TABLE document_versions ALTER COLUMN status SET DEFAULT 'ACTIVE';
