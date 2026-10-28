-- =====================================================================
-- migration_016_kb_category_dir_name
-- 知识库/目录创建时同名物理目录自动唯一化（改名快照语义保持）
--   knowledge_bases / categories 新增 dir_name 列：创建时在父目录下探测
--   唯一化后的物理目录段（{seg}/{seg}_1…），持久化后终身不变；
--   上传路径解析优先取 dir_name，NULL（存量行）回退按名称解析，行为不变。
--   详见 docs/设计-知识库目录改名存储路径方案.md
-- 幂等：可重复执行（IF NOT EXISTS）
-- 存量数据不回填（NULL = 维持既有名称视图行为），无数据迁移。
-- 同步基线：docs/sql/init.sql 已同步该两列。
-- =====================================================================

ALTER TABLE "knowledge_bases"
    ADD COLUMN IF NOT EXISTS "dir_name" VARCHAR;

ALTER TABLE "categories"
    ADD COLUMN IF NOT EXISTS "dir_name" VARCHAR;

COMMENT ON COLUMN "knowledge_bases"."dir_name"
    IS '物理目录段（创建时唯一化确定，终身不变；NULL=存量行回退按名称解析）';
COMMENT ON COLUMN "categories"."dir_name"
    IS '物理目录段（创建时唯一化确定，终身不变；NULL=存量行回退按名称解析）';
