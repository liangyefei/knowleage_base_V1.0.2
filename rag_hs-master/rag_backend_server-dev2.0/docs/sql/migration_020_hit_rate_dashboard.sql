-- =====================================================================
-- migration_020_hit_rate_dashboard
-- 命中率仪表盘：qa_kb_stats 增列支撑仪表盘统计查询。
--   query_text / account：提问内容与账号（落库时冗余，仪表盘单表查询免 join）；
--   source：来源渠道（CHAT_QA=智能问答 / RECEPTION_BOT=前台机器人 / AGENT_CALL=Agent 调用，
--           缺省 CHAT_QA；聊天接口可选 source 参数透传）；
--   handle_status：未命中处理状态（PENDING 待处理 / PROCESSING 处理中 / RESOLVED 已补充，
--           仅 kb_hit=false 行有业务意义，缺省 PENDING）；
--   selected_kbs：用户提问时选择的知识库 id 列表（逗号分隔，multi 路径就绪过滤后的
--           knowledge_base_names），芯片（=知识库）维度命中率的分母口径依据。
-- 幂等：可重复执行（IF NOT EXISTS）
-- 同步基线：docs/sql/init.sql 已同步。
-- =====================================================================

ALTER TABLE "qa_kb_stats" ADD COLUMN IF NOT EXISTS "query_text"     TEXT;
ALTER TABLE "qa_kb_stats" ADD COLUMN IF NOT EXISTS "account"        VARCHAR;
ALTER TABLE "qa_kb_stats" ADD COLUMN IF NOT EXISTS "source"         VARCHAR DEFAULT 'CHAT_QA';
ALTER TABLE "qa_kb_stats" ADD COLUMN IF NOT EXISTS "handle_status"  VARCHAR DEFAULT 'PENDING';
ALTER TABLE "qa_kb_stats" ADD COLUMN IF NOT EXISTS "selected_kbs"   TEXT;

CREATE INDEX IF NOT EXISTS "idx_qa_kb_stats_created"
    ON "qa_kb_stats" ("created_at");

CREATE INDEX IF NOT EXISTS "idx_qa_kb_stats_handle_status"
    ON "qa_kb_stats" ("handle_status");

COMMENT ON COLUMN "qa_kb_stats"."query_text"    IS '用户问题原文（落库时冗余，仪表盘/未命中列表单表查询）';
COMMENT ON COLUMN "qa_kb_stats"."account"       IS '提问账号（会话归属账号）';
COMMENT ON COLUMN "qa_kb_stats"."source"        IS '来源渠道：CHAT_QA 智能问答 / RECEPTION_BOT 前台机器人 / AGENT_CALL Agent 调用（缺省 CHAT_QA）';
COMMENT ON COLUMN "qa_kb_stats"."handle_status" IS '未命中处理状态：PENDING 待处理 / PROCESSING 处理中 / RESOLVED 已补充（仅 kb_hit=false 行有业务意义）';
COMMENT ON COLUMN "qa_kb_stats"."selected_kbs"  IS '用户选择的知识库 id 列表，逗号分隔（multi 路径就绪过滤后下发模型的列表；芯片维度命中率分母口径）';
