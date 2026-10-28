-- =====================================================================
-- migration_019_conflict_detection
-- 冲突检测模块：知识库内文档间内容冲突（事实/语义/版本/跨模态/近重复五类）
--   手动全量扫描（立即检测） + 文档切片就绪后的自动增量扫描。
-- 7 张表：
--   1. conflict_scan_jobs        检测批次/任务（QUEUED/RUNNING/终态机）
--   2. conflict_records          冲突记录（聚合根，PENDING/RESOLVED/IGNORED/EXPIRED）
--   3. conflict_record_items      冲突证据片段快照（模型回调落库，事后不级联）
--   4. conflict_resolutions      处理记录（解决/忽略）
--   5. conflict_badcase_tickets  Badcase 工单（本地占位；工单系统后续设计，见 §3.7）
--   6. conflict_settings          自动检测开关（GLOBAL 单行；kb_id 预留 KB 级）
--   7. conflict_record_scans      冲突-批次多对多（指纹去重复发/再次命中）
--
-- 设计要点（与 docs/海思知识库架构设计-冲突检测模块设计.md §7 对齐）：
--   1. 枚举值均为 VARCHAR，DB 层用 CHECK 约束枚举集合；语义由代码层 enum 管理。
--   2. conflict_records 的 (kb_id, fingerprint) 部分唯一索引（仅 PENDING），
--      数据库层兜底并发去重，避免同 kb 同指纹出现两条活跃冲突。
--   3. 证据采用快照：文档删除/版本覆盖不级联影响冲突记录（§3.5）；
--      document_id 可空（快照失效/反查不到时），但 doc_name_snapshot 必留。
--   4. FK 仅做存在性约束，不 ON DELETE CASCADE（除 conflict_record_items /
--      conflict_resolutions / conflict_record_scans 随 record 物理删除清理外）。
--   5. 幂等：可重复执行（IF NOT EXISTS）。
--   6. ID 由应用层生成（varchar(36)，对齐既有风格），不使用 DB 序列。
-- 同步基线：docs/sql/init.sql 应同步本脚本。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. conflict_scan_jobs（检测批次/任务）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "conflict_scan_jobs" (
    "id"                  VARCHAR(36)  NOT NULL,
    "batch_no"            VARCHAR(20)  NOT NULL,
    "kb_id"               VARCHAR(36),
    "trigger_type"         VARCHAR(10)  NOT NULL,
    "mode"                VARCHAR(12)  NOT NULL,
    "scope_nodes"         VARCHAR(1000),
    "scope_fingerprint"   VARCHAR(128),
    "status"              VARCHAR(12)  NOT NULL,
    "total_documents"     INT          NOT NULL DEFAULT 0,
    "scanned_chunks"      INT          NOT NULL DEFAULT 0,
    "new_conflict_count"  INT          NOT NULL DEFAULT 0,
    "hit_conflict_count"  INT          NOT NULL DEFAULT 0,
    "failed_items"        INT          NOT NULL DEFAULT 0,
    "error"               VARCHAR(500),
    "started_at"          TIMESTAMP,
    "finished_at"         TIMESTAMP,
    "created_by"          VARCHAR(50)  NOT NULL,
    "created_at"          TIMESTAMP    NOT NULL,
    "updated_at"          TIMESTAMP    NOT NULL,
    CONSTRAINT "pk_conflict_scan_jobs" PRIMARY KEY ("id"),
    CONSTRAINT "uq_conflict_scan_jobs_batch_no" UNIQUE ("batch_no"),
    CONSTRAINT "ck_conflict_scan_jobs_trigger" CHECK ("trigger_type" IN ('MANUAL','AUTO')),
    CONSTRAINT "ck_conflict_scan_jobs_mode"    CHECK ("mode" IN ('FULL','INCREMENTAL')),
    CONSTRAINT "ck_conflict_scan_jobs_status"  CHECK ("status" IN ('QUEUED','RUNNING','COMPLETED','PARTIAL','FAILED','ABORTED'))
);

CREATE INDEX IF NOT EXISTS "idx_conflict_scan_jobs_status_created"
    ON "conflict_scan_jobs" ("status", "created_at");
-- 同范围并发守卫：仅 QUEUED/RUNNING 参与（部分索引）
CREATE INDEX IF NOT EXISTS "idx_conflict_scan_jobs_scope_fp"
    ON "conflict_scan_jobs" ("scope_fingerprint")
    WHERE "scope_fingerprint" IS NOT NULL AND "status" IN ('QUEUED','RUNNING');
CREATE INDEX IF NOT EXISTS "idx_conflict_scan_jobs_kb_status"
    ON "conflict_scan_jobs" ("kb_id", "status")
    WHERE "kb_id" IS NOT NULL;

COMMENT ON TABLE "conflict_scan_jobs" IS '冲突检测批次/任务（手动 FULL=立即检测；自动 INCREMENTAL=切片就绪后触发）';
COMMENT ON COLUMN "conflict_scan_jobs"."id"                 IS '主键，应用层生成（值对象 ConflictScanJobId）';
COMMENT ON COLUMN "conflict_scan_jobs"."batch_no"           IS '批次号，展示用 CHK-yyyyMMdd-NN（日内序号），唯一';
COMMENT ON COLUMN "conflict_scan_jobs"."kb_id"              IS '所属知识库 id；手动跨 KB 时置空（scope_nodes 记录多 KB），应用层按 KB 拆成多个提交';
COMMENT ON COLUMN "conflict_scan_jobs"."trigger_type"        IS '触发类型：MANUAL=用户立即检测 / AUTO=切片 COMPLETED 后自动触发';
COMMENT ON COLUMN "conflict_scan_jobs"."mode"                IS '扫描模式：FULL=全量（勾选子树） / INCREMENTAL=增量（changed 单文档）';
COMMENT ON COLUMN "conflict_scan_jobs"."scope_nodes"         IS '勾选节点快照 [{kbId,categoryId?}]（手动）；增量为单文档定位 JSON';
COMMENT ON COLUMN "conflict_scan_jobs"."scope_fingerprint"   IS '范围归一化文档集 hash（手动同范围并发守卫用，AUTO 为 NULL）；建议带算法前缀 sha256，128 足以容纳任意前缀+hex';
COMMENT ON COLUMN "conflict_scan_jobs"."status"              IS '任务状态：QUEUED/RUNNING/COMPLETED/PARTIAL/FAILED/ABORTED';
COMMENT ON COLUMN "conflict_scan_jobs"."total_documents"     IS '范围内可检测文档数（PUBLISHED+COMPLETED）';
COMMENT ON COLUMN "conflict_scan_jobs"."scanned_chunks"     IS '回调回传的切片数（默认 0）';
COMMENT ON COLUMN "conflict_scan_jobs"."new_conflict_count" IS '本次新建冲突数（PENDING）';
COMMENT ON COLUMN "conflict_scan_jobs"."hit_conflict_count"  IS '命中既有 PENDING 冲突数（指纹去重）';
COMMENT ON COLUMN "conflict_scan_jobs"."failed_items"        IS '失败证据 / 失败文档数（结构非法 / evidence<2 / document_id 反查不到）';
COMMENT ON COLUMN "conflict_scan_jobs"."error"               IS '失败原因（提交失败 / 回调非法时回写）';
COMMENT ON COLUMN "conflict_scan_jobs"."started_at"          IS '检测开始时间（模型受理时写 RUNNING）';
COMMENT ON COLUMN "conflict_scan_jobs"."finished_at"        IS '检测结束时间（终态写）';
COMMENT ON COLUMN "conflict_scan_jobs"."created_by"          IS '创建人：MANUAL=操作人账号（JWT sub）；AUTO=SYSTEM';
COMMENT ON COLUMN "conflict_scan_jobs"."created_at"          IS '创建时间';
COMMENT ON COLUMN "conflict_scan_jobs"."updated_at"          IS '最近更新时间';

-- ---------------------------------------------------------------------
-- 2. conflict_records（冲突记录 —— 聚合根）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "conflict_records" (
    "id"                   VARCHAR(36)  NOT NULL,
    "kb_id"                VARCHAR(36)  NOT NULL,
    "first_job_id"         VARCHAR(36)  NOT NULL,
    "last_seen_job_id"     VARCHAR(36),
    "fingerprint"          VARCHAR(128) NOT NULL,
    "topic"                VARCHAR(500) NOT NULL,
    "type"                 VARCHAR(20)  NOT NULL,
    "raw_type"             VARCHAR(50),
    "severity"             VARCHAR(10)  NOT NULL DEFAULT 'MEDIUM',
    "summary"              TEXT,
    "location_count"        INT          NOT NULL DEFAULT 0,
    "scope_label"           VARCHAR(100),
    "detected_started_at"   TIMESTAMP,
    "detected_finished_at"   TIMESTAMP,
    "last_seen_at"          TIMESTAMP,
    "badcase_status"        VARCHAR(12)  NOT NULL DEFAULT 'NONE',
    "badcase_ticket_id"     VARCHAR(36),
    "badcase_submitted_by"  VARCHAR(50),
    "badcase_submitted_at" TIMESTAMP,
    "created_at"            TIMESTAMP    NOT NULL,
    "updated_at"            TIMESTAMP    NOT NULL,
    CONSTRAINT "pk_conflict_records" PRIMARY KEY ("id"),
    CONSTRAINT "ck_conflict_records_type"     CHECK ("type" IN ('FACT','SEMANTIC','VERSION','CROSS_MODAL','NEAR_DUPLICATE')),
    CONSTRAINT "ck_conflict_records_severity"  CHECK ("severity" IN ('HIGH','MEDIUM','LOW')),
    CONSTRAINT "ck_conflict_records_badcase"   CHECK ("badcase_status" IN ('NONE','SUBMITTED')),
    CONSTRAINT "fk_conflict_records_first_job"
        FOREIGN KEY ("first_job_id") REFERENCES "conflict_scan_jobs" ("id")
);

-- 同 kb 同指纹唯一（需求简化后无状态机，全量唯一索引防重复）
CREATE UNIQUE INDEX IF NOT EXISTS "uk_conflict_records_kb_fp"
    ON "conflict_records" ("kb_id", "fingerprint");
CREATE INDEX IF NOT EXISTS "idx_conflict_records_type"
    ON "conflict_records" ("type");
CREATE INDEX IF NOT EXISTS "idx_conflict_records_topic"
    ON "conflict_records" USING btree ("topic");
CREATE INDEX IF NOT EXISTS "idx_conflict_records_badcase"
    ON "conflict_records" ("badcase_status");

COMMENT ON TABLE "conflict_records" IS '冲突记录（聚合根；同 kb 同指纹唯一 uk_conflict_records_kb_fp）';
COMMENT ON COLUMN "conflict_records"."id"                   IS '主键，应用层生成（值对象 ConflictRecordId）';
COMMENT ON COLUMN "conflict_records"."kb_id"                IS '所属知识库 id（判定按 KB 隔离）';
COMMENT ON COLUMN "conflict_records"."first_job_id"         IS '首次发现该冲突的批次 id（列表"检测开始/结束时间"取该 job）';
COMMENT ON COLUMN "conflict_records"."last_seen_job_id"     IS '最近一次命中该冲突的批次 id';
COMMENT ON COLUMN "conflict_records"."fingerprint"          IS '冲突指纹 sha256(type||排序文档ID集合||归一化主题)，去重用';
COMMENT ON COLUMN "conflict_records"."topic"                 IS '冲突主题（列表主列，keyword 模糊搜索列）';
COMMENT ON COLUMN "conflict_records"."type"                  IS '冲突类型：FACT/SEMANTIC/VERSION/CROSS_MODAL/NEAR_DUPLICATE';
COMMENT ON COLUMN "conflict_records"."raw_type"              IS '模型回传的原始类型字符串（未知枚举兜底时留存，平台落 type=SEMANTIC）';
COMMENT ON COLUMN "conflict_records"."severity"              IS '严重级别：HIGH/MEDIUM/LOW（默认 MEDIUM）';
COMMENT ON COLUMN "conflict_records"."summary"               IS '判定理由（详情展示）';
COMMENT ON COLUMN "conflict_records"."location_count"        IS '冲突位置数（证据数，顶部"N 位置"）';
COMMENT ON COLUMN "conflict_records"."scope_label"           IS '范围标签（按证据文档数生成，如"跨文档多处"）';
COMMENT ON COLUMN "conflict_records"."detected_started_at"    IS '冗余首次 job 的 started_at（列表列）';
COMMENT ON COLUMN "conflict_records"."detected_finished_at"  IS '冗余首次 job 的 finished_at（列表列）';
COMMENT ON COLUMN "conflict_records"."last_seen_at"          IS '最近一次命中时间（同指纹再次被扫描到时刷新）';
COMMENT ON COLUMN "conflict_records"."badcase_status"        IS 'Badcase 提报状态：NONE=未提交 / SUBMITTED=已提交（一冲突最多一工单）';
COMMENT ON COLUMN "conflict_records"."badcase_ticket_id"     IS '关联工单号 conflict_badcase_tickets.id；SUBMITTED 时回填';
COMMENT ON COLUMN "conflict_records"."badcase_submitted_by"  IS '提交人账号（列表"提交人"列）';
COMMENT ON COLUMN "conflict_records"."badcase_submitted_at"  IS '提交时间';
COMMENT ON COLUMN "conflict_records"."created_at"            IS '创建时间（首次落库）';
COMMENT ON COLUMN "conflict_records"."updated_at"            IS '最近更新时间';

-- ---------------------------------------------------------------------
-- 3. conflict_record_items（冲突证据片段快照）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "conflict_record_items" (
    "id"                     VARCHAR(36) NOT NULL,
    "record_id"              VARCHAR(36) NOT NULL,
    "document_id"            VARCHAR(36),
    "doc_name_snapshot"      VARCHAR(200) NOT NULL,
    "version_snapshot"        VARCHAR(64),
    "publish_status_snapshot" VARCHAR(20) NOT NULL,
    "chunk_index"            INT,
    "page_numbers"           VARCHAR(255),
    "titles_path"            VARCHAR(500),
    "line_hint"              INT,
    "excerpt"                TEXT        NOT NULL,
    "is_baseline"            BOOLEAN     NOT NULL DEFAULT FALSE,
    "chunk_updated_at"       TIMESTAMP,
    "sort_no"                INT         NOT NULL DEFAULT 0,
    "created_at"             TIMESTAMP   NOT NULL,
    CONSTRAINT "pk_conflict_record_items" PRIMARY KEY ("id"),
    CONSTRAINT "fk_conflict_record_items_record"
        FOREIGN KEY ("record_id") REFERENCES "conflict_records" ("id") ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS "idx_conflict_record_items_record"
    ON "conflict_record_items" ("record_id", "sort_no");

COMMENT ON TABLE "conflict_record_items" IS '冲突证据片段快照（模型回调落库，事后文档删除/版本覆盖不级联）';
COMMENT ON COLUMN "conflict_record_items"."id"                     IS '主键，应用层生成';
COMMENT ON COLUMN "conflict_record_items"."record_id"             IS '所属冲突记录 id（ON DELETE CASCADE 随 record 物理删除）';
COMMENT ON COLUMN "conflict_record_items"."document_id"           IS '来源文档 id（供前端跳转当前文档；快照失效/反查不到时为 NULL）';
COMMENT ON COLUMN "conflict_record_items"."doc_name_snapshot"     IS '文档名快照（当时的 title/file_name）';
COMMENT ON COLUMN "conflict_record_items"."version_snapshot"      IS '版本快照（取 current_version / published_version）';
COMMENT ON COLUMN "conflict_record_items"."publish_status_snapshot" IS '快照时文档发布状态（PUBLISHED 等）';
COMMENT ON COLUMN "conflict_record_items"."chunk_index"           IS '片段在文档中的 chunk 序号';
COMMENT ON COLUMN "conflict_record_items"."page_numbers"          IS '页码数组 （如 [88]）';
COMMENT ON COLUMN "conflict_record_items"."titles_path"           IS '章节路径快照（titles 用 " > " 拼接，如 "第9章 UART > 9.5 电气特性"）';
COMMENT ON COLUMN "conflict_record_items"."line_hint"            IS '行号（模型可选回传；不提供则为 NULL，平台不臆造）';
COMMENT ON COLUMN "conflict_record_items"."excerpt"               IS '冲突原文摘录';
COMMENT ON COLUMN "conflict_record_items"."is_baseline"           IS '是否基准版本（模型回传，多条中至多一条 true）';
COMMENT ON COLUMN "conflict_record_items"."chunk_updated_at"       IS '片段更新时间快照（缺失时平台用文档 updated_at 兜底）';
COMMENT ON COLUMN "conflict_record_items"."sort_no"               IS '证据展示顺序（基准置前）';
COMMENT ON COLUMN "conflict_record_items"."created_at"           IS '创建时间';

-- ---------------------------------------------------------------------
-- 4. conflict_resolutions（处理记录）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "conflict_resolutions" (
    "id"             VARCHAR(36)  NOT NULL,
    "record_id"      VARCHAR(36)  NOT NULL,
    "action"         VARCHAR(12)  NOT NULL,
    "remark"         VARCHAR(1000) NOT NULL,
    "handled_by"     VARCHAR(50)  NOT NULL,
    "handled_by_name" VARCHAR(100),
    "handled_at"     TIMESTAMP    NOT NULL,
    CONSTRAINT "pk_conflict_resolutions" PRIMARY KEY ("id"),
    CONSTRAINT "ck_conflict_resolutions_action" CHECK ("action" IN ('RESOLVED','IGNORED')),
    CONSTRAINT "fk_conflict_resolutions_record"
        FOREIGN KEY ("record_id") REFERENCES "conflict_records" ("id") ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS "idx_conflict_resolutions_record"
    ON "conflict_resolutions" ("record_id", "handled_at");

COMMENT ON TABLE "conflict_resolutions" IS '冲突处理记录（标记已解决/已忽略；详情"处理记录"区域读此表）';
COMMENT ON COLUMN "conflict_resolutions"."id"              IS '主键，应用层生成';
COMMENT ON COLUMN "conflict_resolutions"."record_id"      IS '所属冲突 id（ON DELETE CASCADE 随 record 物理删除）';
COMMENT ON COLUMN "conflict_resolutions"."action"         IS '处理动作：RESOLVED=已解决 / IGNORED=已忽略';
COMMENT ON COLUMN "conflict_resolutions"."remark"          IS '处理备注（必填，1~1000 字，接口层校验）';
COMMENT ON COLUMN "conflict_resolutions"."handled_by"      IS '处理人账号（JWT sub）';
COMMENT ON COLUMN "conflict_resolutions"."handled_by_name" IS '处理人显示名（冗余，避免跨系统 join 用户）';
COMMENT ON COLUMN "conflict_resolutions"."handled_at"      IS '处理时间';

-- ---------------------------------------------------------------------
-- 5. conflict_badcase_tickets（Badcase 工单 —— 本地占位）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "conflict_badcase_tickets" (
    "id"              VARCHAR(36)  NOT NULL,
    "record_id"       VARCHAR(36)  NOT NULL,
    "kb_id"           VARCHAR(36)  NOT NULL,
    "topic_snapshot"  VARCHAR(500) NOT NULL,
    "type_snapshot"   VARCHAR(20)  NOT NULL,
    "description"     VARCHAR(1000) NOT NULL,
    "status"          VARCHAR(20)  NOT NULL DEFAULT 'SUBMITTED',
    "external_ref"    VARCHAR(64),
    "submitted_by"    VARCHAR(50)  NOT NULL,
    "created_at"      TIMESTAMP    NOT NULL,
    CONSTRAINT "pk_conflict_badcase_tickets" PRIMARY KEY ("id"),
    CONSTRAINT "uq_conflict_badcase_tickets_record" UNIQUE ("record_id"),
    CONSTRAINT "ck_conflict_badcase_tickets_type" CHECK ("type_snapshot" IN ('FACT','SEMANTIC','VERSION','CROSS_MODAL','NEAR_DUPLICATE')),
    CONSTRAINT "fk_conflict_badcase_tickets_record"
        FOREIGN KEY ("record_id") REFERENCES "conflict_records" ("id") ON DELETE CASCADE
);

COMMENT ON TABLE "conflict_badcase_tickets" IS 'Badcase 工单（本地占位：本期仅存"描述+提交人+状态"；工单系统后续设计，远程对接经 external_ref 回填）';
COMMENT ON COLUMN "conflict_badcase_tickets"."id"             IS '工单号（本地生成 BC-yyyyMMdd-xxxx）';
COMMENT ON COLUMN "conflict_badcase_tickets"."record_id"     IS '来源冲突 id（唯一 uk_conflict_badcase_tickets_record，一冲突最多一工单）';
COMMENT ON COLUMN "conflict_badcase_tickets"."kb_id"          IS '所属知识库 id（冗余，便于按 KB 聚合）';
COMMENT ON COLUMN "conflict_badcase_tickets"."topic_snapshot" IS '冲突主题快照（提交时 record.topic 副本）';
COMMENT ON COLUMN "conflict_badcase_tickets"."type_snapshot"  IS '冲突类型快照（提交时 record.type 副本）';
COMMENT ON COLUMN "conflict_badcase_tickets"."description"    IS '问题描述（必填，1~1000 字）';
COMMENT ON COLUMN "conflict_badcase_tickets"."status"          IS '工单状态：本期固定 SUBMITTED（本地）；对接外部工单系统后扩展 ACCEPTED/CLOSED';
COMMENT ON COLUMN "conflict_badcase_tickets"."external_ref"  IS '外部工单系统单号（远程实现回填；本地实现为 NULL）';
COMMENT ON COLUMN "conflict_badcase_tickets"."submitted_by"   IS '提交人账号（JWT sub）';
COMMENT ON COLUMN "conflict_badcase_tickets"."created_at"     IS '提交时间';

-- ---------------------------------------------------------------------
-- 6. conflict_settings（自动检测开关 —— 全局单行）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "conflict_settings" (
    "id"             VARCHAR(36) NOT NULL,
    "kb_id"          VARCHAR(36),
    "auto_enabled"   BOOLEAN     NOT NULL DEFAULT FALSE,
    "updated_by"     VARCHAR(50),
    "updated_at"     TIMESTAMP,
    CONSTRAINT "pk_conflict_settings" PRIMARY KEY ("id"),
    CONSTRAINT "uq_conflict_settings_kb" UNIQUE ("kb_id")
);

COMMENT ON TABLE "conflict_settings" IS '冲突检测设置（本期仅 GLOBAL 单行；kb_id 预留 KB 级开关，一期固定 NULL）';
COMMENT ON COLUMN "conflict_settings"."id"           IS '主键，本期固定常量 GLOBAL';
COMMENT ON COLUMN "conflict_settings"."kb_id"        IS '预留 KB 级开关；一期全局行为 NULL（唯一索引允许多行 NULL，PG 语义）';
COMMENT ON COLUMN "conflict_settings"."auto_enabled"  IS '自动冲突检测开关（默认 false；开启后切片 COMPLETED 回调旁路投递增量检测）';
COMMENT ON COLUMN "conflict_settings"."updated_by"   IS '最近切换人账号';
COMMENT ON COLUMN "conflict_settings"."updated_at"    IS '最近切换时间';

-- ---------------------------------------------------------------------
-- 7. conflict_record_scans（冲突-批次多对多）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "conflict_record_scans" (
    "id"         VARCHAR(36) NOT NULL,
    "record_id"  VARCHAR(36) NOT NULL,
    "job_id"     VARCHAR(36) NOT NULL,
    "seen_at"    TIMESTAMP   NOT NULL,
    CONSTRAINT "pk_conflict_record_scans" PRIMARY KEY ("id"),
    CONSTRAINT "uq_conflict_record_scans_record_job" UNIQUE ("record_id", "job_id"),
    CONSTRAINT "fk_conflict_record_scans_record"
        FOREIGN KEY ("record_id") REFERENCES "conflict_records" ("id") ON DELETE CASCADE,
    CONSTRAINT "fk_conflict_record_scans_job"
        FOREIGN KEY ("job_id") REFERENCES "conflict_scan_jobs" ("id") ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS "idx_conflict_record_scans_job"
    ON "conflict_record_scans" ("job_id");

COMMENT ON TABLE "conflict_record_scans" IS '冲突-批次多对多（一条冲突被多个批次再次命中时关联；防 PENDING 重复新建）';
COMMENT ON COLUMN "conflict_record_scans"."id"        IS '主键，应用层生成';
COMMENT ON COLUMN "conflict_record_scans"."record_id" IS '冲突 id';
COMMENT ON COLUMN "conflict_record_scans"."job_id"     IS '批次 id';
COMMENT ON COLUMN "conflict_record_scans"."seen_at"   IS '该批次命中该冲突的时间';
