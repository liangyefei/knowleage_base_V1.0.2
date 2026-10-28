-- =====================================================================
-- migration_021_kb_names_snapshot
-- 命中率仪表盘：qa_kb_stats 增加知识库名称快照列。
--   kb_names_json：落库时对 selected_kbs ∪ hit_kbs 中出现的 kb id 解析名称的
--   JSON 快照（{"diting":"谛树",...}）。统计是对当时事实的记录——知识库后续
--   改名/删除不影响历史行展示。解析失败回退存 id 本身（保证 id→name 至少恒等）。
--   查询侧经 jsonb_each_text 展开 distinct 键值对做名称映射，无需跨模块查询。
-- 幂等：可重复执行（IF NOT EXISTS）
-- 同步基线：docs/sql/init.sql 已同步。
-- =====================================================================

ALTER TABLE "qa_kb_stats" ADD COLUMN IF NOT EXISTS "kb_names_json" TEXT;

COMMENT ON COLUMN "qa_kb_stats"."kb_names_json" IS
    '知识库名称快照（落库时 selected_kbs ∪ hit_kbs 的 id→name JSON；解析失败回退 id）';
