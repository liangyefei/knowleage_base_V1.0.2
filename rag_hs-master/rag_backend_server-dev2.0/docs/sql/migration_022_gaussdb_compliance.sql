-- =====================================================================
-- migration_022_gaussdb_compliance
-- 代码扫描（SQLGuardian）整改：对【已发布环境】执行数据库合规变更。
-- 全新环境请直接执行 docs/sql/init.sql（已在建表时完成列存/分布/类型合规），
-- 本脚本不修改任何历史 migration 脚本（migration_013/015/017/018 保持原样）。
--
-- 整改规则与动作：
--   1. 数据库禁止使用外键        → 删除各表 FOREIGN KEY 约束（关联完整性由应用层保证）
--   2. 只允许主键/唯一键索引     → 删除非主键/唯一键的二级索引
--                                  （hot_words 的 (kb_id,status,sort_order) 查询路径
--                                   由 uq_hot_words_kb_content 的 kb_id 前缀部分兜底；
--                                   如仍需排序索引，需评审后以唯一键或应用层方案替代）
--   3. 对象命名长度过长          → 删除/重命名超长命名的索引
--   4. VARCHAR2 / 列存+LOW压缩 / HASH分布列
--                                → 已发布环境如需迁移到 GaussDB 列存表，须按文末模板
--                                  CTAS 重建（ALTER 无法改表方向），由 DBA 在停机窗口执行；
--                                  本脚本不做破坏性重建。
--
-- 幂等：全部使用 IF EXISTS。
-- =====================================================================

-- 1) 删除外键约束 -----------------------------------------------------------------
ALTER TABLE "conversation_messages" DROP CONSTRAINT IF EXISTS "conversation_messages_conversation_id_fkey";
ALTER TABLE "message_feedbacks"     DROP CONSTRAINT IF EXISTS "fk_message_feedbacks_message";
ALTER TABLE "hot_words"             DROP CONSTRAINT IF EXISTS "fk_hot_words_kb";
ALTER TABLE "qa_kb_stats"           DROP CONSTRAINT IF EXISTS "fk_qa_kb_stats_message";

-- 2) 删除非主键/唯一键索引 ---------------------------------------------------------
DROP INDEX IF EXISTS "idx_hot_words_kb_status_sort";
DROP INDEX IF EXISTS "idx_kb_callback_processed_received";
DROP INDEX IF EXISTS "idx_message_feedbacks_conversation";
DROP INDEX IF EXISTS "idx_message_feedbacks_rating_created";

-- 3) 过长对象名重命名（>30 字符）---------------------------------------------------
ALTER INDEX IF EXISTS "document_contents_content_text_trgm_idx" RENAME TO "doc_contents_trgm_idx";

-- 4) 列存 + LOW 压缩 + HASH 分布（模板，DBA 停机窗口执行）---------------------------
-- 以 documents 为例（其余表同构替换 表名/分布列）：
--   CREATE TABLE "documents_new"
--   (与 init.sql 中 documents 列定义一致，CHAR/VARCHAR 已改 VARCHAR2)
--   WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
--   DISTRIBUTE BY HASH ("id");
--   INSERT INTO "documents_new" (显式列清单) SELECT (同列清单) FROM "documents";
--   DROP TABLE "documents"; ALTER TABLE "documents_new" RENAME TO "documents";
-- 重建后需重建主键/唯一键约束并重新 ANALYZE。
-- =====================================================================
