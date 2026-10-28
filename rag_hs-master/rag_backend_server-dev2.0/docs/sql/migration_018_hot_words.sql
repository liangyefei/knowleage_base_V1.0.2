-- =====================================================================
-- migration_018_hot_words
-- 热门词管理表：管理高频用户问题词库，支持手动维护（AI 智能生成为后续功能），
--   对话/检索场景按知识库取启用中的热门词，优化检索命中率。
-- 单表 hot_words，一行一个热门词。
--
-- 设计决策（已确认）：
--   1. 启用上限：每个知识库（kb_id）独立计数，各自最多 5 条启用中。
--      PostgreSQL 无原生"每分组最多 N 条"约束，故不在 DB 层硬约束；
--      由应用层在事务内保证（实现时的验收依据）：
--        事务内先 SELECT pg_advisory_xact_lock(hashtext(kb_id))
--        再 COUNT(*) WHERE kb_id = ? AND status = 1，
--        启用操作时计数 >= 5 则拒绝。advisory 锁串行化同 kb 的启用操作，
--        跨 kb 互不影响，成本极低（启用/停用为低频管理操作）。
--   2. 内容唯一性：同一知识库内去重，UNIQUE(kb_id, content)。
--   3. kb_id 必填（NOT NULL），热门词必须归属知识库；FK 关联 knowledge_bases(id)
--      （该表此前在 DB 层缺主键，本脚本一并补建 pk_knowledge_bases）。
--   4. 分类、点击量字段本期不做；后续需要时直接加列即可，不动现有约束。
--
-- 幂等：可重复执行（IF NOT EXISTS）
-- 状态枚举（SMALLINT，DB 用 CHECK 硬约束，语义由代码层 enum 管理）：
--   1 = 启用中（每 kb 最多 5 条）  0 = 已停用
-- 同步基线：docs/sql/init.sql 已同步该表。
-- =====================================================================

CREATE TABLE IF NOT EXISTS "hot_words" (
    "id"         VARCHAR(36)  NOT NULL,
    "content"    VARCHAR(200) NOT NULL,
    "kb_id"      VARCHAR(36)  NOT NULL,
    "sort_order" INT          NOT NULL DEFAULT 0,
    "status"     SMALLINT     NOT NULL,
    "created_by" VARCHAR      NOT NULL,
    "created_at" TIMESTAMP    NOT NULL,
    "updated_at" TIMESTAMP    NOT NULL,
    CONSTRAINT "pk_hot_words" PRIMARY KEY ("id"),
    CONSTRAINT "uq_hot_words_kb_content" UNIQUE ("kb_id", "content"),
    CONSTRAINT "fk_hot_words_kb"
        FOREIGN KEY ("kb_id") REFERENCES "knowledge_bases" ("id"),
    CONSTRAINT "ck_hot_words_status" CHECK ("status" IN (0, 1))
);

CREATE INDEX IF NOT EXISTS "idx_hot_words_kb_status_sort"
    ON "hot_words" ("kb_id", "status", "sort_order");

COMMENT ON TABLE "hot_words"
    IS '热门词管理（高频用户问题词库；每知识库最多 5 条启用中，应用层 advisory lock 保证）';
COMMENT ON COLUMN "hot_words"."id"         IS '主键，应用层生成（"hw"+32hex，IdGenerator.hotWord()），varchar(36) 对齐既有 ID 风格';
COMMENT ON COLUMN "hot_words"."content"    IS '热门词内容（用户问题文本），应用层限长 200 字符；同知识库内唯一（uq_hot_words_kb_content）';
COMMENT ON COLUMN "hot_words"."kb_id"      IS '所属知识库 id，关联 knowledge_bases.id；必填。知识库为软删除，FK 仅做存在性约束，不 CASCADE，清理随应用层';
COMMENT ON COLUMN "hot_words"."sort_order" IS '排序号（界面"排序"字段），数值小者靠前；同 kb 内可重复，列表按 (sort_order, updated_at) 排序';
COMMENT ON COLUMN "hot_words"."status"     IS '状态：1=启用中 / 0=已停用；每 kb 启用中最多 5 条（应用层事务内 advisory lock + 计数保证）';
COMMENT ON COLUMN "hot_words"."created_by" IS '创建人账号，取 JWT sub（OperatorIdHolder），前端不传';
COMMENT ON COLUMN "hot_words"."created_at" IS '创建时间';
COMMENT ON COLUMN "hot_words"."updated_at" IS '最近更新时间（列表"更新时间"列）';
