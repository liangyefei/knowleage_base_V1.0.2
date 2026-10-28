-- =====================================================================
-- migration_019_kb_stats
-- 问答 KB 统计表：multi_knowledge_base_chat 路径模型收尾事件下发的
--   kb_stats 指标落库（检索触发/命中、召回与使用条数、命中知识库、
--   改写后查询、top 分数、路由原因、降级标记、原始 JSON 留档）。
--   一条 ASSISTANT 回答最多一行，message_id 主键直接关联
--   conversation_messages(id)（即平台侧 assistantMessageId；模型响应中的
--   message_id 恒为空串，禁止用于关联），ON DELETE CASCADE 随消息清理。
--   无 kb_stats 的回答（file_chat / 纯 LLM / 闲聊）不落行。
-- 幂等：可重复执行（IF NOT EXISTS）
-- 同步基线：docs/sql/init.sql 已同步该表。
-- =====================================================================

CREATE TABLE IF NOT EXISTS "qa_kb_stats" (
    "message_id"      VARCHAR   NOT NULL,
    "rag_triggered"   BOOLEAN,
    "kb_hit"          BOOLEAN,
    "no_hit_reason"   VARCHAR,
    "retrieved_count" INT,
    "used_count"      INT,
    "hit_kbs"         TEXT,
    "rewritten_query" TEXT,
    "top_score"       NUMERIC,
    "route_reason"    VARCHAR,
    "degraded"        BOOLEAN,
    "kb_stats_json"   TEXT,
    "created_at"      TIMESTAMP NOT NULL,
    CONSTRAINT "pk_qa_kb_stats" PRIMARY KEY ("message_id"),
    CONSTRAINT "fk_qa_kb_stats_message"
        FOREIGN KEY ("message_id") REFERENCES "conversation_messages" ("id") ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS "idx_qa_kb_stats_kb_hit_created"
    ON "qa_kb_stats" ("kb_hit", "created_at");

COMMENT ON TABLE "qa_kb_stats"
    IS '问答 KB 统计（multi_knowledge_base_chat 收尾事件 kb_stats 落库；一回答一行，无 kb_stats 不落）';
COMMENT ON COLUMN "qa_kb_stats"."message_id"      IS 'ASSISTANT 消息 id（平台侧 assistantMessageId=conversation_messages.id；模型 message_id 恒为空串不使用），主键';
COMMENT ON COLUMN "qa_kb_stats"."rag_triggered"   IS '是否触发 RAG 检索';
COMMENT ON COLUMN "qa_kb_stats"."kb_hit"          IS '知识库是否命中';
COMMENT ON COLUMN "qa_kb_stats"."no_hit_reason"   IS '未命中原因（kb_hit=false 时模型给出，如闲聊路由/检索无结果）';
COMMENT ON COLUMN "qa_kb_stats"."retrieved_count" IS '召回片段条数';
COMMENT ON COLUMN "qa_kb_stats"."used_count"      IS '实际注入上下文的片段条数';
COMMENT ON COLUMN "qa_kb_stats"."hit_kbs"         IS '命中的知识库列表，逗号分隔';
COMMENT ON COLUMN "qa_kb_stats"."rewritten_query" IS '改写后的检索查询（可空）';
COMMENT ON COLUMN "qa_kb_stats"."top_score"       IS 'top 片段相似度分数';
COMMENT ON COLUMN "qa_kb_stats"."route_reason"    IS '路由原因（可空）';
COMMENT ON COLUMN "qa_kb_stats"."degraded"        IS '检索/生成是否降级';
COMMENT ON COLUMN "qa_kb_stats"."kb_stats_json"   IS '模型下发的 kb_stats 原始 JSON 留档';
COMMENT ON COLUMN "qa_kb_stats"."created_at"      IS '落库时间';
