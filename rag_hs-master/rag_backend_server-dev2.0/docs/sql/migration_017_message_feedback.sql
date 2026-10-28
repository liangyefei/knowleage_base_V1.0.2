-- =====================================================================
-- migration_017_message_feedback
-- 对话回答反馈表：前端对 ASSISTANT 回答（ChatResource#sendMessage 输出）
--   进行"有帮助 / 没帮助"反馈；没帮助时记录原因（可多选）与具体描述。
--   单表 message_feedbacks，一消息一用户一行（UNIQUE(message_id, user_id)），
--   重复反馈 UPSERT 覆盖，保留最新态（用户可反复切换，统计天然去重）。
--   冗余 conversation_id 便于按会话聚合；ON DELETE CASCADE 随消息物理删除清理。
-- 幂等：可重复执行（IF NOT EXISTS）
-- 原因枚举（代码层 enum 管理，DB 不做 CHECK 硬约束）：
--   WRONG_ANSWER=回答错误  OFF_TOPIC=答非所问  IRRELEVANT=结果不相关
--   CODE_ERROR=代码错误    OUTDATED=信息过时
-- 同步基线：docs/sql/init.sql 已同步该表。
-- =====================================================================

CREATE TABLE IF NOT EXISTS "message_feedbacks" (
    "id"              VARCHAR   NOT NULL,
    "message_id"      VARCHAR   NOT NULL,
    "conversation_id" VARCHAR   NOT NULL,
    "user_id"         VARCHAR   NOT NULL,
    "rating"          VARCHAR   NOT NULL,
    "reasons"         VARCHAR,
    "comment"         TEXT,
    "created_at"      TIMESTAMP NOT NULL,
    "updated_at"      TIMESTAMP NOT NULL,
    CONSTRAINT "pk_message_feedbacks" PRIMARY KEY ("id"),
    CONSTRAINT "uq_message_feedbacks_msg_user" UNIQUE ("message_id", "user_id"),
    CONSTRAINT "fk_message_feedbacks_message"
        FOREIGN KEY ("message_id") REFERENCES "conversation_messages" ("id") ON DELETE CASCADE,
    CONSTRAINT "ck_message_feedbacks_rating" CHECK ("rating" IN ('HELPFUL','UNHELPFUL'))
);

CREATE INDEX IF NOT EXISTS "idx_message_feedbacks_conversation"
    ON "message_feedbacks" ("conversation_id");

CREATE INDEX IF NOT EXISTS "idx_message_feedbacks_rating_created"
    ON "message_feedbacks" ("rating", "created_at");

COMMENT ON TABLE "message_feedbacks"
    IS '对话回答反馈（有帮助/没帮助；一消息一用户一行，重复反馈覆盖保留最新态）';
COMMENT ON COLUMN "message_feedbacks"."id"              IS '主键 UUID';
COMMENT ON COLUMN "message_feedbacks"."message_id"      IS '反馈目标消息 id，关联 conversation_messages.id（仅 ASSISTANT 行；应用层校验角色与归属）';
COMMENT ON COLUMN "message_feedbacks"."conversation_id" IS '冗余所属会话 id（关联 conversations.id），便于按会话聚合免 join';
COMMENT ON COLUMN "message_feedbacks"."user_id"         IS '反馈人账号，取 JWT sub（OperatorIdHolder），前端不传';
COMMENT ON COLUMN "message_feedbacks"."rating"          IS '反馈评级：HELPFUL=有帮助 / UNHELPFUL=没帮助';
COMMENT ON COLUMN "message_feedbacks"."reasons"         IS '没帮助原因，逗号分隔多选：WRONG_ANSWER/OFF_TOPIC/IRRELEVANT/CODE_ERROR/OUTDATED；HELPFUL 行 NULL';
COMMENT ON COLUMN "message_feedbacks"."comment"         IS '具体描述，可空；应用层限 2000 字符';
COMMENT ON COLUMN "message_feedbacks"."created_at"      IS '首次反馈时间';
COMMENT ON COLUMN "message_feedbacks"."updated_at"      IS '最近反馈时间（UPSERT 覆盖时刷新）';
