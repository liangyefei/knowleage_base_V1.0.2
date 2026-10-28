-- =====================================================================
-- migration_020_badcase_module
-- Badcase 管理模块：多来源工单归集（页面问答 / MCP 调用问答 / 冲突检测 / 知识切片）
--   来源-问题类型联动筛选、差异化详情区块（JSONB 载荷）、四态状态处理 + 备注。
-- 2 张表：
--   1. badcase_tickets     Badcase 工单主表（聚合根，含最新处理冗余字段）
--   2. badcase_handlings   处理记录历史（每次状态变更留一条）
--
-- 设计要点（与 docs/海思知识库架构设计-Badcase模块设计.md §4 对齐）：
--   1. 枚举值均为 VARCHAR，DB 层用 CHECK 约束枚举集合；语义由代码层 enum 管理。
--   2. 差异化区块数据用 source_payload JSONB（§3.1）：PAGE_QA/MCP_QA=问答对话、
--      CONFLICT_DETECTION=冲突证据、KNOWLEDGE_SLICE=切片内容；不建子表。
--   3. 最新处理人 / 备注 / 时间冗余在主表（latest_*），列表 / 详情免 join handlings；
--      handlings 表仅做历史追溯，本期不提供历史列表接口。
--   4. 状态四态：PENDING / PROCESSING / RESOLVED / REJECTED，任意状态可改任意目标状态
--      （不做流转守卫，由前端下拉约束 + 后端枚举校验）。
--   5. ID 由应用层生成（varchar(36)，对齐既有风格），不使用 DB 序列。
--   6. 幂等：可重复执行（IF NOT EXISTS）。
-- 同步基线：docs/sql/init.sql 应同步本脚本。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. badcase_tickets（Badcase 工单主表 —— 聚合根）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "badcase_tickets" (
    "id"                        VARCHAR(36)   NOT NULL,
    "ticket_no"                 VARCHAR(20)   NOT NULL,
    "source"                    VARCHAR(30)   NOT NULL,
    "problem_type"              VARCHAR(40)   NOT NULL,
    "description"               VARCHAR(1000) NOT NULL,
    "status"                    VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    "kb_id"                     VARCHAR(36),
    "kb_name_snapshot"          VARCHAR(200),
    "related_doc_ids"           TEXT,
    "related_doc_names"         TEXT,
    "source_payload"            TEXT,
    "latest_handling_remark"    VARCHAR(1000),
    "latest_handled_by"         VARCHAR(50),
    "latest_handled_by_name"    VARCHAR(100),
    "latest_handled_at"         TIMESTAMP,
    "submitted_by"              VARCHAR(50)   NOT NULL,
    "submitted_by_name"         VARCHAR(100),
    "submitted_at"              TIMESTAMP     NOT NULL,
    "created_at"                TIMESTAMP     NOT NULL,
    "updated_at"                TIMESTAMP     NOT NULL,
    CONSTRAINT "pk_badcase_tickets" PRIMARY KEY ("id"),
    CONSTRAINT "uq_badcase_tickets_no" UNIQUE ("ticket_no"),
    CONSTRAINT "ck_badcase_tickets_source"
        CHECK ("source" IN ('PAGE_QA','MCP_QA','CONFLICT_DETECTION','KNOWLEDGE_SLICE')),
    CONSTRAINT "ck_badcase_tickets_status"
        CHECK ("status" IN ('PENDING','PROCESSING','RESOLVED','REJECTED'))
);

CREATE INDEX IF NOT EXISTS "idx_badcase_tickets_source_status"
    ON "badcase_tickets" ("source", "status");
CREATE INDEX IF NOT EXISTS "idx_badcase_tickets_status"
    ON "badcase_tickets" ("status");
CREATE INDEX IF NOT EXISTS "idx_badcase_tickets_kb"
    ON "badcase_tickets" ("kb_id")
    WHERE "kb_id" IS NOT NULL;
CREATE INDEX IF NOT EXISTS "idx_badcase_tickets_type"
    ON "badcase_tickets" ("problem_type");
CREATE INDEX IF NOT EXISTS "idx_badcase_tickets_submitted_at"
    ON "badcase_tickets" ("submitted_at" DESC);

COMMENT ON TABLE "badcase_tickets" IS 'Badcase 工单主表（4 来源统一模型；差异化区块存 source_payload JSONB）';
COMMENT ON COLUMN "badcase_tickets"."id"                      IS '主键，应用层生成（值对象 BadcaseTicketId）';
COMMENT ON COLUMN "badcase_tickets"."ticket_no"              IS '工单号（BC-yyyyMMdd-xxxx，唯一展示用）';
COMMENT ON COLUMN "badcase_tickets"."source"                 IS '来源：PAGE_QA=页面问答 / MCP_QA=MCP 调用问答 / CONFLICT_DETECTION=冲突检测 / KNOWLEDGE_SLICE=知识切片';
COMMENT ON COLUMN "badcase_tickets"."problem_type"            IS '问题类型码（见 BadcaseProblemType 枚举；后端校验 source+type 组合合法性）';
COMMENT ON COLUMN "badcase_tickets"."description"             IS '问题描述（必填，1~1000 字，keyword 模糊搜索列）';
COMMENT ON COLUMN "badcase_tickets"."status"                 IS '处理状态：PENDING=待处理 / PROCESSING=处理中 / RESOLVED=已处理 / REJECTED=不予处理（默认 PENDING）';
COMMENT ON COLUMN "badcase_tickets"."kb_id"                   IS '关联知识库 id（可空，问答类工单可能未关联特定知识库）';
COMMENT ON COLUMN "badcase_tickets"."kb_name_snapshot"       IS '知识库名称快照（避免 join KB 表）';
COMMENT ON COLUMN "badcase_tickets"."related_doc_ids"        IS '涉及文档 id 数组 JSON，如 ["uuid-1","uuid-2"]（text 列，应用层 ObjectMapper 序列化；不用 jsonb 类型，避免 Hibernate Reactive 反序列化 JsonArray 报错）';
COMMENT ON COLUMN "badcase_tickets"."related_doc_names"      IS '涉及文档名称快照数组 JSON（text 列，同上）';
COMMENT ON COLUMN "badcase_tickets"."source_payload"          IS '差异化区块 JSON：PAGE_QA/MCP_QA={question,answer}、CONFLICT_DETECTION={conflictTopic,evidence[]}、KNOWLEDGE_SLICE={sliceContent,documentId,chunkIndex}（text 列，同上）';
COMMENT ON COLUMN "badcase_tickets"."latest_handling_remark" IS '最新处理备注冗余（列表/详情直接读，免 join handlings）';
COMMENT ON COLUMN "badcase_tickets"."latest_handled_by"      IS '最新处理人账号冗余';
COMMENT ON COLUMN "badcase_tickets"."latest_handled_by_name" IS '最新处理人显示名冗余';
COMMENT ON COLUMN "badcase_tickets"."latest_handled_at"       IS '最新处理时间冗余';
COMMENT ON COLUMN "badcase_tickets"."submitted_by"           IS '提交人账号（JWT sub）';
COMMENT ON COLUMN "badcase_tickets"."submitted_by_name"      IS '提交人显示名快照';
COMMENT ON COLUMN "badcase_tickets"."submitted_at"           IS '提交时间';
COMMENT ON COLUMN "badcase_tickets"."created_at"              IS '创建时间';
COMMENT ON COLUMN "badcase_tickets"."updated_at"              IS '最近更新时间';

-- ---------------------------------------------------------------------
-- 2. badcase_handlings（处理记录历史）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "badcase_handlings" (
    "id"               VARCHAR(36)   NOT NULL,
    "ticket_id"        VARCHAR(36)   NOT NULL,
    "action"           VARCHAR(20)   NOT NULL,
    "remark"           VARCHAR(1000) NOT NULL,
    "handled_by"       VARCHAR(50)   NOT NULL,
    "handled_by_name"  VARCHAR(100),
    "handled_at"       TIMESTAMP     NOT NULL,
    CONSTRAINT "pk_badcase_handlings" PRIMARY KEY ("id"),
    CONSTRAINT "ck_badcase_handlings_action"
        CHECK ("action" IN ('PENDING','PROCESSING','RESOLVED','REJECTED')),
    CONSTRAINT "fk_badcase_handlings_ticket"
        FOREIGN KEY ("ticket_id") REFERENCES "badcase_tickets" ("id") ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS "idx_badcase_handlings_ticket"
    ON "badcase_handlings" ("ticket_id", "handled_at");

COMMENT ON TABLE "badcase_handlings" IS 'Badcase 处理记录历史（每次状态变更留一条；详情/列表读主表 latest_* 冗余，本表仅做追溯）';
COMMENT ON COLUMN "badcase_handlings"."id"              IS '主键，应用层生成';
COMMENT ON COLUMN "badcase_handlings"."ticket_id"       IS '所属工单 id（ON DELETE CASCADE 随 ticket 物理删除）';
COMMENT ON COLUMN "badcase_handlings"."action"          IS '本次操作的目标状态：PENDING/PROCESSING/RESOLVED/REJECTED';
COMMENT ON COLUMN "badcase_handlings"."remark"          IS '处理备注（必填，1~1000 字，接口层校验）';
COMMENT ON COLUMN "badcase_handlings"."handled_by"      IS '处理人账号（JWT sub）';
COMMENT ON COLUMN "badcase_handlings"."handled_by_name" IS '处理人显示名（冗余）';
COMMENT ON COLUMN "badcase_handlings"."handled_at"      IS '处理时间';
