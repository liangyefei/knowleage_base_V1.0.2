-- =====================================================================
-- 2.0.0.alpha.8_sensitive
-- 敏感词管理三表：标签（分类）+ 词库（词）+ 不合规提问记录（审计）。
--   管理端维护标签/词库，启动与变更时重建内存 DFA 树（hutool WordTree）；
--   对话提问侧命中 → 记录到 kb_sensitive_question_logs 并拒绝（A0431）；
--   回答侧命中 → 终止生成，涉敏回答不落库。
--
-- 设计决策：
--   1. 标签状态（SMALLINT，语义由代码层管理）：
--      1 = 启用  2 = 停用  0 = 删除（软删；删除标签时由应用层级联删除其词库）。
--   2. 词库表 tag_id 不加 FK：标签删除为软删 + 应用层级联删词，
--      FK 强约束反而阻断"先删词后软删标签"的应用层顺序，仅建 tag_id 索引。
--   3. 词唯一性（同标签内不重复）由应用层校验（存在性检查后写入，
--      命中 A0429 重复错误）；未在 DB 层加 UNIQUE(tag_id, word) 硬约束，
--      避免并发写入竞争时抛 500（管理端低频操作，应用层校验足够）。
--   4. 词级启用状态 status：1 = 启用 / 2 = 停用（词为硬删，无 0）。
--      仅"标签启用 且 词启用"的词参与内存 DFA 匹配（缓存回源双条件过滤）；
--      词唯一性跨状态：停用词仍占用唯一性（不可重复添加，只能重新启用）。
--   5. 提问记录 question 用 TEXT：命中提问原文可能较长，不限 255。
--      账号/命中词/标签冗余存储，记录即审计快照，不随标签/词后续删除变化。
--
-- 幂等：可重复执行（IF NOT EXISTS）。
-- 同步基线：docs/sql/init.sql 已同步该三表。
-- =====================================================================

-- ----------------------------
-- 敏感词标签（分类）
-- ----------------------------
CREATE TABLE IF NOT EXISTS "kb_sensitive_tags" (
    "id"                   VARCHAR(36)  NOT NULL,
    "text_label"           VARCHAR(255) NOT NULL,
    "label_identification" VARCHAR(64)  NOT NULL,
    "remarks"              VARCHAR(512),
    "status"               SMALLINT     NOT NULL DEFAULT 1,
    "created_at"           TIMESTAMP(6),
    "updated_at"           TIMESTAMP(6),
    CONSTRAINT "pk_kb_sensitive_tags" PRIMARY KEY ("id")
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");

-- ----------------------------
-- 敏感词词库（一行一个词，归属标签；带词级启用状态）
-- ----------------------------
CREATE TABLE IF NOT EXISTS "kb_sensitive_lexicons" (
    "id"         VARCHAR(36)  NOT NULL,
    "tag_id"     VARCHAR(36)  NOT NULL,
    "word"       VARCHAR(255) NOT NULL,
    "status"     SMALLINT     NOT NULL DEFAULT 1,
    "created_at" TIMESTAMP(6),
    "created_by" VARCHAR(128),
    "remarks"    VARCHAR(512),
    CONSTRAINT "pk_kb_sensitive_lexicons" PRIMARY KEY ("id")
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");

-- ----------------------------
-- 不合规提问记录（提问侧敏感词命中审计）
-- ----------------------------
CREATE TABLE IF NOT EXISTS "kb_sensitive_question_logs" (
    "id"             VARCHAR(36)  NOT NULL,
    "question"       TEXT         NOT NULL,
    "account"        VARCHAR(128),
    "word"           VARCHAR(255),
    "label"          VARCHAR(255),
    "identification" VARCHAR(64),
    "request_time"   TIMESTAMP(6) NOT NULL,
    CONSTRAINT "pk_kb_sensitive_question_logs" PRIMARY KEY ("id")
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");

-- ----------------------------
-- 列注释
-- ----------------------------
COMMENT ON TABLE "kb_sensitive_tags" IS '敏感词标签（敏感词分类，管理端维护；启用中的标签+词库构建内存 DFA 匹配树）';
COMMENT ON COLUMN "kb_sensitive_tags"."id"                   IS '主键，应用层生成，varchar(36) 对齐既有 ID 风格';
COMMENT ON COLUMN "kb_sensitive_tags"."text_label"           IS '标签名称（界面展示名），启用中标签内唯一（应用层校验，命中 A0429）';
COMMENT ON COLUMN "kb_sensitive_tags"."label_identification" IS '标签标识（唯一键，应用层校验；词库导出/提问记录冗余携带）';
COMMENT ON COLUMN "kb_sensitive_tags"."remarks"              IS '备注，可空';
COMMENT ON COLUMN "kb_sensitive_tags"."status"               IS '状态：1=启用 / 2=停用 / 0=删除（软删，删除时应用层级联删除词库）';
COMMENT ON COLUMN "kb_sensitive_tags"."created_at"           IS '创建时间';
COMMENT ON COLUMN "kb_sensitive_tags"."updated_at"           IS '最近更新时间';

COMMENT ON TABLE "kb_sensitive_lexicons" IS '敏感词词库（一行一个词；标签启用且词启用的词参与 DFA 匹配，Redis 缓存键 sensitive:active-words）';
COMMENT ON COLUMN "kb_sensitive_lexicons"."id"         IS '主键，应用层生成，varchar(36) 对齐既有 ID 风格';
COMMENT ON COLUMN "kb_sensitive_lexicons"."tag_id"     IS '归属标签 id，应用层级联维护（删除标签时级联删词）；仅索引不建 FK（标签为软删）';
COMMENT ON COLUMN "kb_sensitive_lexicons"."word"       IS '敏感词文本，同标签内唯一（应用层校验，命中 A0429）；匹配区分大小写（对齐 hutool WordTree 语义）';
COMMENT ON COLUMN "kb_sensitive_lexicons"."status"     IS '状态：1=启用 / 2=停用（词为硬删，无 0）；仅双启用（标签+词）参与匹配，唯一性跨状态';
COMMENT ON COLUMN "kb_sensitive_lexicons"."created_at" IS '创建时间';
COMMENT ON COLUMN "kb_sensitive_lexicons"."created_by" IS '创建人账号，取 JWT sub（OperatorIdHolder），前端不传；存量行为 NULL';
COMMENT ON COLUMN "kb_sensitive_lexicons"."remarks"    IS '备注，可空；编辑接口全量替换';

COMMENT ON TABLE "kb_sensitive_question_logs" IS '不合规提问记录（提问侧敏感词命中审计快照；回答侧命中不记录、不落库回答）';
COMMENT ON COLUMN "kb_sensitive_question_logs"."id"             IS '主键，应用层生成，varchar(36) 对齐既有 ID 风格';
COMMENT ON COLUMN "kb_sensitive_question_logs"."question"       IS '命中的提问原文（TEXT，可能较长）';
COMMENT ON COLUMN "kb_sensitive_question_logs"."account"        IS '提问人账号，可空（记录失败不阻断对话主流程）';
COMMENT ON COLUMN "kb_sensitive_question_logs"."word"           IS '命中的敏感词';
COMMENT ON COLUMN "kb_sensitive_question_logs"."label"          IS '命中词所属标签名称（记录时快照）';
COMMENT ON COLUMN "kb_sensitive_question_logs"."identification" IS '命中词所属标签标识（记录时快照）';
COMMENT ON COLUMN "kb_sensitive_question_logs"."request_time"   IS '提问时间（列表默认按此倒序）';
