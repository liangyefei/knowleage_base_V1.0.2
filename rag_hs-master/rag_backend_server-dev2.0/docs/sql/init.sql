/*
 Navicat Premium Dump SQL

 Source Server         : local-pg
 Source Server Type    : PostgreSQL
 Source Server Version : 180004 (180004)
 Source Host           : localhost:5432
 Source Catalog        : hisilicon_knowledge
 Source Schema         : knowledge

 Target Server Type    : PostgreSQL
 Target Server Version : 180004 (180004)
 File Encoding         : 65001

 Date: 22/09/2026 11:32:53

 整改说明（代码扫描 SQLGuardian 合规）：
 - 目标库为华为 GaussDB（兼容 Oracle 语法）：CHAR/VARCHAR 一律改为 VARCHAR2；
 - 所有业务表为列存表（ORIENTATION=COLUMN）且压缩级别 LOW；
 - 非复制表显式指定 HASH 分布列（取主键/首列）；
 - 不使用 DROP TABLE / 数据库序列 / 外键；二级索引仅保留主键/唯一键；
 - INSERT 显式指定列；对象命名长度不超过 30 字符。
*/


-- ----------------------------
-- pg_trgm extension (provides gtrgm type, gin_trgm_ops, similarity etc.)
-- ----------------------------
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ----------------------------
-- Table structure for abac_attr_defs
-- ----------------------------
CREATE TABLE "abac_attr_defs" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "attr_key" varchar2(30) COLLATE "pg_catalog"."default" NOT NULL,
  "attr_name" varchar2(30) COLLATE "pg_catalog"."default" NOT NULL,
  "attr_type" varchar2(10) COLLATE "pg_catalog"."default" NOT NULL,
  "target_entity_types" varchar2(50) COLLATE "pg_catalog"."default" NOT NULL,
  "sort_order" int4 NOT NULL,
  "created_at" timestamp(6)
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "abac_attr_defs"."id" IS '主键';
COMMENT ON COLUMN "abac_attr_defs"."attr_key" IS '属性键，如 user.department、res.sensitivity';
COMMENT ON COLUMN "abac_attr_defs"."attr_name" IS '属性名称（国际化 key）';
COMMENT ON COLUMN "abac_attr_defs"."attr_type" IS '值类型：STRING / NUMBER / ARRAY';
COMMENT ON COLUMN "abac_attr_defs"."target_entity_types" IS '适用实体类型，逗号分隔：USER,KB,CATEGORY,DOCUMENT';
COMMENT ON COLUMN "abac_attr_defs"."sort_order" IS '排序号';
COMMENT ON COLUMN "abac_attr_defs"."created_at" IS '创建时间';
COMMENT ON TABLE "abac_attr_defs" IS 'ABAC 属性定义表';

-- ----------------------------
-- Records of abac_attr_defs
-- ----------------------------

-- ----------------------------
-- Table structure for abac_policies
-- ----------------------------
CREATE TABLE "abac_policies" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "kb_id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "name" varchar2(60) COLLATE "pg_catalog"."default" NOT NULL,
  "priority" int4 NOT NULL,
  "target_operation" varchar2(50) COLLATE "pg_catalog"."default" NOT NULL,
  "condition_expr" varchar2(500) COLLATE "pg_catalog"."default" NOT NULL,
  "effect" varchar2(5) COLLATE "pg_catalog"."default" NOT NULL,
  "enabled" bool NOT NULL DEFAULT true,
  "created_at" timestamp(6) NOT NULL,
  "updated_at" timestamp(6)
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "abac_policies"."id" IS '主键 UUID';
COMMENT ON COLUMN "abac_policies"."kb_id" IS '绑定知识库 ID';
COMMENT ON COLUMN "abac_policies"."name" IS '策略名称';
COMMENT ON COLUMN "abac_policies"."priority" IS '优先级（数值越大越优先评估）';
COMMENT ON COLUMN "abac_policies"."target_operation" IS '目标操作，支持通配符 "*"';
COMMENT ON COLUMN "abac_policies"."condition_expr" IS '条件表达式（结构化字符串，如 res.doc_type = "代码" AND user.dept = "研发部"）';
COMMENT ON COLUMN "abac_policies"."effect" IS '效果：ALLOW / DENY';
COMMENT ON COLUMN "abac_policies"."enabled" IS '启用状态';
COMMENT ON COLUMN "abac_policies"."created_at" IS '创建时间';
COMMENT ON COLUMN "abac_policies"."updated_at" IS '更新时间';
COMMENT ON TABLE "abac_policies" IS 'ABAC 策略表';

-- ----------------------------
-- Records of abac_policies
-- ----------------------------

-- ----------------------------
-- Table structure for acl_entries
-- ----------------------------
CREATE TABLE "acl_entries" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "resource_type" varchar2(8) COLLATE "pg_catalog"."default" NOT NULL,
  "resource_id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "subject_type" varchar2(4) COLLATE "pg_catalog"."default" NOT NULL,
  "subject_id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "permission" varchar2(20) COLLATE "pg_catalog"."default" NOT NULL,
  "effect" varchar2(5) COLLATE "pg_catalog"."default" NOT NULL,
  "created_at" timestamp(6) NOT NULL,
  "updated_at" timestamp(6)
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "acl_entries"."id" IS '主键';
COMMENT ON COLUMN "acl_entries"."resource_type" IS '资源类型：KB / CATEGORY / DOCUMENT';
COMMENT ON COLUMN "acl_entries"."resource_id" IS '资源 ID';
COMMENT ON COLUMN "acl_entries"."subject_type" IS '主体类型：USER / ROLE';
COMMENT ON COLUMN "acl_entries"."subject_id" IS '主体 ID（用户 ID 或角色 ID）';
COMMENT ON COLUMN "acl_entries"."permission" IS '权限码，如 doc:read';
COMMENT ON COLUMN "acl_entries"."effect" IS '效果：ALLOW / DENY';
COMMENT ON COLUMN "acl_entries"."created_at" IS '创建时间';
COMMENT ON COLUMN "acl_entries"."updated_at" IS '更新时间';
COMMENT ON TABLE "acl_entries" IS 'ACL 条目表';

-- ----------------------------
-- Records of acl_entries
-- ----------------------------

-- ----------------------------
-- Table structure for audit_logs
-- ----------------------------
CREATE TABLE "audit_logs" (
  "id" int8 NOT NULL GENERATED BY DEFAULT AS IDENTITY (
INCREMENT 1
MINVALUE  1
MAXVALUE 9223372036854775807
START 1
CACHE 1
),
  "client_ip" varchar2(64) COLLATE "pg_catalog"."default",
  "created_at" timestamp(6) NOT NULL,
  "details" text COLLATE "pg_catalog"."default",
  "operation" varchar2(32) COLLATE "pg_catalog"."default" NOT NULL,
  "operation_time" timestamp(6) NOT NULL,
  "operator_id" varchar2(128) COLLATE "pg_catalog"."default" NOT NULL,
  "resource_id" varchar2(128) COLLATE "pg_catalog"."default",
  "resource_type" varchar2(64) COLLATE "pg_catalog"."default" NOT NULL
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "audit_logs"."id" IS '主键，自增';
COMMENT ON COLUMN "audit_logs"."client_ip" IS '客户端 IP';
COMMENT ON COLUMN "audit_logs"."created_at" IS '记录创建时间（仅插入时设置，不可更新）';
COMMENT ON COLUMN "audit_logs"."details" IS '操作详情（JSON，含请求参数摘要和判定结果）';
COMMENT ON COLUMN "audit_logs"."operation" IS '操作类型（枚举名，如 LOGIN, CREATE, DELETE 等）';
COMMENT ON COLUMN "audit_logs"."operation_time" IS '操作时间';
COMMENT ON COLUMN "audit_logs"."operator_id" IS '操作人 ID';
COMMENT ON COLUMN "audit_logs"."resource_id" IS '目标资源 ID';
COMMENT ON COLUMN "audit_logs"."resource_type" IS '目标资源类型（如 USER, KB, DOCUMENT, ROLE, ACL, POLICY）';
COMMENT ON TABLE "audit_logs" IS '审计日志表（不可删除、不可修改）';

-- ----------------------------
-- Records of audit_logs
-- ----------------------------

-- ----------------------------
-- Table structure for categories
-- ----------------------------
CREATE TABLE "categories" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "created_at" timestamp(6) NOT NULL,
  "inherit_parent" bool NOT NULL DEFAULT true,
  "kb_id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "name" varchar2(50) COLLATE "pg_catalog"."default" NOT NULL,
  "dir_name" varchar2(255) COLLATE "pg_catalog"."default",
  "parent_id" varchar2(36) COLLATE "pg_catalog"."default",
  "sort_order" int4,
  "status" int2 NOT NULL,
  "updated_at" timestamp(6)
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "categories"."id" IS '主键';
COMMENT ON COLUMN "categories"."created_at" IS '创建时间';
COMMENT ON COLUMN "categories"."inherit_parent" IS '是否继承父类';
COMMENT ON COLUMN "categories"."kb_id" IS '知识库id';
COMMENT ON COLUMN "categories"."name" IS '类别名称（同级下唯一，软删除数据可重名）';
COMMENT ON COLUMN "categories"."dir_name" IS '物理目录段（创建时唯一化确定，终身不变；NULL=存量行回退按名称解析）';
COMMENT ON COLUMN "categories"."parent_id" IS '父类别 ID（NULL=根类别）';
COMMENT ON COLUMN "categories"."sort_order" IS '排序号';
COMMENT ON COLUMN "categories"."status" IS '状态：0=删除, 1=正常';
COMMENT ON COLUMN "categories"."updated_at" IS '更新时间';
COMMENT ON TABLE "categories" IS '类别表（树形结构）';

-- ----------------------------
-- Records of categories
-- ----------------------------

-- ----------------------------
-- Table structure for category_metadata
-- ----------------------------
CREATE TABLE "category_metadata" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "category_id" varchar2(36) COLLATE "pg_catalog"."default",
  "created_at" timestamp(6),
  "meta_key" varchar2(50) COLLATE "pg_catalog"."default",
  "meta_value" varchar2(200) COLLATE "pg_catalog"."default",
  "updated_at" timestamp(6)
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "category_metadata"."id" IS '主键';
COMMENT ON COLUMN "category_metadata"."category_id" IS '类别 ID，关联 categories.id';
COMMENT ON COLUMN "category_metadata"."created_at" IS '创建时间';
COMMENT ON COLUMN "category_metadata"."meta_key" IS '属性键';
COMMENT ON COLUMN "category_metadata"."meta_value" IS '属性值';
COMMENT ON COLUMN "category_metadata"."updated_at" IS '更新时间';
COMMENT ON TABLE "category_metadata" IS '类别元数据表（key-value 扩展）';

-- ----------------------------
-- Records of category_metadata
-- ----------------------------

-- ----------------------------
-- Table structure for conversation_messages
-- ----------------------------
CREATE TABLE "conversation_messages" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "conversation_id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "role" varchar2(10) COLLATE "pg_catalog"."default" NOT NULL,
  "content" text COLLATE "pg_catalog"."default" NOT NULL,
  "created_at" timestamp(6) NOT NULL,
  "search_kb_enabled" bool,
  "search_external_enabled" bool,
  "kb_ids" varchar2(500) COLLATE "pg_catalog"."default",
  "deep_thinking" bool,
  "doc_types" varchar2(500) COLLATE "pg_catalog"."default",
  "version" varchar2(64) COLLATE "pg_catalog"."default"
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "conversation_messages"."id" IS '主键 UUID';
COMMENT ON COLUMN "conversation_messages"."conversation_id" IS '所属会话 ID';
COMMENT ON COLUMN "conversation_messages"."role" IS '消息角色：USER / ASSISTANT / SYSTEM';
COMMENT ON COLUMN "conversation_messages"."content" IS '消息正文';
COMMENT ON COLUMN "conversation_messages"."created_at" IS '发送时间';
COMMENT ON COLUMN "conversation_messages"."search_kb_enabled" IS '消息级官方库开关（仅 USER 行写入；ASSISTANT 行 NULL）';
COMMENT ON COLUMN "conversation_messages"."search_external_enabled" IS '消息级非官方库开关（仅 USER 行写入；ASSISTANT 行 NULL）';
COMMENT ON COLUMN "conversation_messages"."kb_ids" IS '消息级知识库 id，逗号分隔（仅 USER 行写入；NULL/空=不限→走纯 LLM）';
COMMENT ON COLUMN "conversation_messages"."deep_thinking" IS '消息级深度思考（仅 USER 行写入；ASSISTANT 行 NULL）';
COMMENT ON COLUMN "conversation_messages"."doc_types" IS '消息级文档类型，逗号分隔（DOCUMENT/CODE/CONFIG/OTHER；仅 USER 行写入；NULL=全部类型）';
COMMENT ON COLUMN "conversation_messages"."version" IS '消息级文档版本（仅 USER 行写入；NULL=最新版；ASSISTANT 行 NULL）';

-- ----------------------------
-- Records of conversation_messages
-- ----------------------------

-- ----------------------------
-- Table structure for conversations
-- ----------------------------
CREATE TABLE "conversations" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "account" varchar2(100) COLLATE "pg_catalog"."default" NOT NULL,
  "title" varchar2(200) COLLATE "pg_catalog"."default" NOT NULL,
  "status" varchar2(20) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'ACTIVE'::character varying,
  "file_name" varchar2(200) COLLATE "pg_catalog"."default",
  "file_size" int8,
  "file_path" varchar2(500) COLLATE "pg_catalog"."default",
  "mime_type" varchar2(100) COLLATE "pg_catalog"."default",
  "file_type" varchar2(20) COLLATE "pg_catalog"."default",
  "created_at" timestamp(6) NOT NULL,
  "updated_at" timestamp(6),
  "temp_kb_id" varchar2(100) COLLATE "pg_catalog"."default",
  "title_manually_set" bool NOT NULL DEFAULT false,
  "pinned" bool NOT NULL DEFAULT false
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "conversations"."id" IS '主键 UUID';
COMMENT ON COLUMN "conversations"."account" IS '用户账号（后续对接 hispark 用户体系）';
COMMENT ON COLUMN "conversations"."title" IS '对话标题（首条问题截取）';
COMMENT ON COLUMN "conversations"."status" IS '状态：ACTIVE / ARCHIVED（软删除）';
COMMENT ON COLUMN "conversations"."file_name" IS '上传文件原始文件名（可空=无文件对话）';
COMMENT ON COLUMN "conversations"."file_size" IS '上传文件大小（bytes）';
COMMENT ON COLUMN "conversations"."file_path" IS '文件存储相对路径（SFS，kb/chat/{conversationId}/{文件名}）';
COMMENT ON COLUMN "conversations"."mime_type" IS '上传文件 MIME 类型';
COMMENT ON COLUMN "conversations"."file_type" IS '文件类型分类：DOCUMENT/CODE/CONFIG/OTHER';
COMMENT ON COLUMN "conversations"."created_at" IS '创建时间';
COMMENT ON COLUMN "conversations"."updated_at" IS '最后消息时间';
COMMENT ON COLUMN "conversations"."temp_kb_id" IS '模型服务临时知识库 ID（upload_temp_docs 返回值，file_chat 问答必需）；无文件会话为 NULL';
COMMENT ON COLUMN "conversations"."title_manually_set" IS '标题是否手动重命名过（rename 接口置 true）；true 后首条消息不再自动覆盖';
COMMENT ON COLUMN "conversations"."pinned" IS '是否置顶（pin 接口置 true）；列表查询置顶会话排前';

-- ----------------------------
-- Records of conversations
-- ----------------------------

-- ----------------------------
-- Table structure for document_contents
-- ----------------------------
CREATE TABLE "document_contents" (
  "document_id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "file_path" varchar2(500) COLLATE "pg_catalog"."default" NOT NULL,
  "content_text" text COLLATE "pg_catalog"."default",
  "content_status" varchar2(10) COLLATE "pg_catalog"."default" NOT NULL,
  "extracted_at" timestamp(6)
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("document_id");
COMMENT ON COLUMN "document_contents"."document_id" IS '文档 ID（关联 documents.id，物理删除级联清理）';
COMMENT ON COLUMN "document_contents"."file_path" IS '抽取源文件路径（与 documents.file_path 等值才参与内容检索，保证只命中审核通过的最新版）';
COMMENT ON COLUMN "document_contents"."content_text" IS '抽取文本（超 kb.search.content-max-chars 截断，默认 200 万字符）';
COMMENT ON COLUMN "document_contents"."content_status" IS '抽取状态：READY / FAILED / SKIPPED';
COMMENT ON COLUMN "document_contents"."extracted_at" IS '抽取完成时间';
COMMENT ON TABLE "document_contents" IS '文档内容检索表（上传/文件替换/版本覆盖时异步 Tika 抽取，支持全局搜索按内容模糊匹配）';

-- ----------------------------
-- Records of document_contents
-- ----------------------------


-- ----------------------------
-- Table structure for document_metadata
-- ----------------------------
CREATE TABLE "document_metadata" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "created_at" timestamp(6),
  "document_id" varchar2(36) COLLATE "pg_catalog"."default",
  "meta_key" varchar2(50) COLLATE "pg_catalog"."default",
  "meta_value" varchar2(200) COLLATE "pg_catalog"."default",
  "updated_at" timestamp(6)
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "document_metadata"."id" IS '主键';
COMMENT ON COLUMN "document_metadata"."created_at" IS '创建时间';
COMMENT ON COLUMN "document_metadata"."document_id" IS '文档 ID，关联 documents.id';
COMMENT ON COLUMN "document_metadata"."meta_key" IS '属性键，如 doc_type、sensitivity、chip_model';
COMMENT ON COLUMN "document_metadata"."meta_value" IS '属性值';
COMMENT ON COLUMN "document_metadata"."updated_at" IS '更新时间';
COMMENT ON TABLE "document_metadata" IS '文档元数据表（key-value 扩展）';

-- ----------------------------
-- Records of document_metadata
-- ----------------------------

-- ----------------------------
-- Table structure for document_outbox
-- ----------------------------
CREATE TABLE "document_outbox" (
  "id" varchar2(64) COLLATE "pg_catalog"."default" NOT NULL,
  "event_type" varchar2(64) COLLATE "pg_catalog"."default" NOT NULL,
  "kb_id" varchar2(64) COLLATE "pg_catalog"."default",
  "category_id" varchar2(64) COLLATE "pg_catalog"."default",
  "document_id" varchar2(64) COLLATE "pg_catalog"."default",
  "operator_id" varchar2(64) COLLATE "pg_catalog"."default",
  "payload" text COLLATE "pg_catalog"."default",
  "status" varchar2(16) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'PENDING'::character varying,
  "retry_count" int4 NOT NULL DEFAULT 0,
  "next_retry_at" timestamp(6),
  "created_at" timestamp(6) NOT NULL DEFAULT now(),
  "sent_at" timestamp(6)
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "document_outbox"."id" IS '主键（UUID）';
COMMENT ON COLUMN "document_outbox"."event_type" IS '事件类型（KnowledgeEvent.EventType）';
COMMENT ON COLUMN "document_outbox"."kb_id" IS '知识库 ID';
COMMENT ON COLUMN "document_outbox"."category_id" IS '分类 ID（可为空）';
COMMENT ON COLUMN "document_outbox"."document_id" IS '文档 ID';
COMMENT ON COLUMN "document_outbox"."operator_id" IS '操作员 ID';
COMMENT ON COLUMN "document_outbox"."payload" IS '消息体 JSON（KnowledgeEvent.payload 序列化，null 表示无载荷）';
COMMENT ON COLUMN "document_outbox"."status" IS '状态：PENDING 待发送 / SENT 已发送';
COMMENT ON COLUMN "document_outbox"."retry_count" IS '重试次数（发送失败保留 PENDING + 计数递增）';
COMMENT ON COLUMN "document_outbox"."next_retry_at" IS '下次重试时间（失败后按 retry_count 退避计算，null/已到点 → 可被 relay 扫描）';
COMMENT ON COLUMN "document_outbox"."created_at" IS '创建时间（relay 排序依据，DB 时间）';
COMMENT ON COLUMN "document_outbox"."sent_at" IS '发送成功时间';
COMMENT ON TABLE "document_outbox" IS '事务发件箱（Transactional Outbox）：业务事务内写入待发消息，relay 异步投递 DMS Kafka';

-- ----------------------------
-- Records of document_outbox
-- ----------------------------

-- ----------------------------
-- Table structure for document_versions
-- ----------------------------
CREATE TABLE "document_versions" (
  "document_id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "version_number" varchar2(64) COLLATE "pg_catalog"."default" NOT NULL,
  "change_summary" varchar2(255) COLLATE "pg_catalog"."default",
  "created_at" timestamp(6),
  "created_by" varchar2(50) COLLATE "pg_catalog"."default",
  "file_path" varchar2(500) COLLATE "pg_catalog"."default",
  "file_size" int8,
  "mime_type" varchar2(100) COLLATE "pg_catalog"."default",
  "was_published" bool,
  "status" varchar2(20) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'ACTIVE'::character varying
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("document_id");
COMMENT ON COLUMN "document_versions"."document_id" IS '文档 ID（联合主键）';
COMMENT ON COLUMN "document_versions"."version_number" IS '版本号（varchar2，与 documents.current_version 同源）';
COMMENT ON COLUMN "document_versions"."change_summary" IS '变更总结';
COMMENT ON COLUMN "document_versions"."created_at" IS '创建时间';
COMMENT ON COLUMN "document_versions"."created_by" IS '创建者';
COMMENT ON COLUMN "document_versions"."file_path" IS '文件路径';
COMMENT ON COLUMN "document_versions"."file_size" IS '文件大小';
COMMENT ON COLUMN "document_versions"."mime_type" IS '文件类型';
COMMENT ON COLUMN "document_versions"."was_published" IS '是否已发布';
COMMENT ON COLUMN "document_versions"."status" IS '版本行状态：ACTIVE-在线；ARCHIVED-已归档（版本级下线）；DEPRECATED-已废弃（版本覆盖新归档恒为 ACTIVE）';
COMMENT ON TABLE "document_versions" IS '文档版本表（document_id 为联合主键前缀，无需额外索引）';

-- ----------------------------
-- Records of document_versions
-- ----------------------------

-- ----------------------------
-- Table structure for documents
-- ----------------------------
CREATE TABLE "documents" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "category_id" varchar2(36) COLLATE "pg_catalog"."default",
  "created_at" timestamp(6) NOT NULL,
  "created_by" varchar2(50) COLLATE "pg_catalog"."default" NOT NULL,
  "current_version" varchar2(64) COLLATE "pg_catalog"."default",
  "file_path" varchar2(500) COLLATE "pg_catalog"."default" NOT NULL,
  "file_size" int8 NOT NULL,
  "inherit_parent" bool DEFAULT true,
  "kb_id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "mime_type" varchar2(100) COLLATE "pg_catalog"."default" NOT NULL,
  "status" varchar2(20) COLLATE "pg_catalog"."default" NOT NULL,
  "title" varchar2(200) COLLATE "pg_catalog"."default" NOT NULL,
  "updated_at" timestamp(6),
  "process_status" varchar2(15) COLLATE "pg_catalog"."default",
  "process_error" varchar2(255) COLLATE "pg_catalog"."default",
  "published_version" varchar2(64) COLLATE "pg_catalog"."default",
  "has_published_version" bool,
  "reviewed_by" varchar2(50) COLLATE "pg_catalog"."default",
  "review_comment" varchar2(200) COLLATE "pg_catalog"."default",
  "reviewed_at" timestamp(6),
  "doc_type" varchar2(8) COLLATE "pg_catalog"."default" NOT NULL,
  "author" varchar2(255) COLLATE "pg_catalog"."default",
  "tags" varchar2(255) COLLATE "pg_catalog"."default",
  "remark" varchar2(500) COLLATE "pg_catalog"."default",
  "chunk_count" int4,
  "process_error_code" varchar2(32) COLLATE "pg_catalog"."default",
  "processed_at" timestamp(6)
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "documents"."id" IS '主键';
COMMENT ON COLUMN "documents"."category_id" IS '所属类别 ID';
COMMENT ON COLUMN "documents"."created_at" IS '创建时间';
COMMENT ON COLUMN "documents"."created_by" IS '创建人';
COMMENT ON COLUMN "documents"."current_version" IS '当前最新版本号（varchar2，由上传/编辑/版本覆盖元数据提供）';
COMMENT ON COLUMN "documents"."file_path" IS '文件路径';
COMMENT ON COLUMN "documents"."file_size" IS '文件大小';
COMMENT ON COLUMN "documents"."inherit_parent" IS '是否继承父级 ACL，默认 true';
COMMENT ON COLUMN "documents"."kb_id" IS '知识库id';
COMMENT ON COLUMN "documents"."mime_type" IS '文档类型';
COMMENT ON COLUMN "documents"."status" IS '生命周期状态：DRAFT / REVIEWING / PUBLISHED / ARCHIVED / DEPRECATED';
COMMENT ON COLUMN "documents"."title" IS '文档标题';
COMMENT ON COLUMN "documents"."updated_at" IS '更新时间';
COMMENT ON COLUMN "documents"."process_status" IS '模型处理状态（模型服务回调驱动）：PENDING / PROCESSING / COMPLETED / FAILED';
COMMENT ON COLUMN "documents"."process_error" IS '模型处理失败原因';
COMMENT ON COLUMN "documents"."published_version" IS '最近一次审核通过的版本号（可空，用于追溯已发布内容）';
COMMENT ON COLUMN "documents"."has_published_version" IS '是否存在已发布历史版本';
COMMENT ON COLUMN "documents"."reviewed_by" IS '审核人';
COMMENT ON COLUMN "documents"."review_comment" IS '审核备注 / 驳回原因';
COMMENT ON COLUMN "documents"."reviewed_at" IS '审核时间';
COMMENT ON COLUMN "documents"."doc_type" IS '文档类型分类  DOCUMENT - 文档类 / CODE - 代码类 / CONFIG - 配置文件类 / OTHER - 其他';
COMMENT ON COLUMN "documents"."author" IS '文档作者（自由文本，不同于 created_by 操作员）';
COMMENT ON COLUMN "documents"."tags" IS '文档标签，逗号分隔多值，如 GPIO,UART,I2C';
COMMENT ON COLUMN "documents"."remark" IS '文档备注（自由文本，最长 500 字符）';
COMMENT ON COLUMN "documents"."chunk_count" IS '文档切片数量（模型回调 PROCESSING_COMPLETED 时回传）';
COMMENT ON COLUMN "documents"."process_error_code" IS '模型处理失败错误码（processStatus=FAILED 时回传，如 404/500）';
COMMENT ON COLUMN "documents"."processed_at" IS '模型处理完成/失败时间（回调时回传）';
COMMENT ON TABLE "documents" IS '文档表';

-- ----------------------------
-- Records of documents
-- ----------------------------

-- ----------------------------
-- Table structure for external_identities
-- ----------------------------
CREATE TABLE "external_identities" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "created_at" timestamp(6) NOT NULL,
  "external_id" varchar2(50) COLLATE "pg_catalog"."default" NOT NULL,
  "idp_name" varchar2(50) COLLATE "pg_catalog"."default" NOT NULL,
  "source" varchar2(50) COLLATE "pg_catalog"."default" NOT NULL,
  "user_id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "external_identities"."id" IS '主键';
COMMENT ON COLUMN "external_identities"."created_at" IS '创建时间';
COMMENT ON COLUMN "external_identities"."external_id" IS '外部系统用户 ID';
COMMENT ON COLUMN "external_identities"."idp_name" IS 'IdP 名称';
COMMENT ON COLUMN "external_identities"."source" IS '来源：oidc';
COMMENT ON COLUMN "external_identities"."user_id" IS '用户 ID';
COMMENT ON TABLE "external_identities" IS '外部身份关联表（同一用户可关联多个 IdP）';

-- ----------------------------
-- Records of external_identities
-- ----------------------------

-- ----------------------------
-- Table structure for hot_words
-- ----------------------------
CREATE TABLE "hot_words" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "content" varchar2(200) COLLATE "pg_catalog"."default" NOT NULL,
  "kb_id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "sort_order" int4 NOT NULL DEFAULT 0,
  "status" int2 NOT NULL,
  "created_by" varchar2(128) COLLATE "pg_catalog"."default" NOT NULL,
  "created_at" timestamp(6) NOT NULL,
  "updated_at" timestamp(6) NOT NULL
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
CREATE UNIQUE INDEX "uq_hot_words_kb_content" ON "hot_words" USING btree ("kb_id", "content");
COMMENT ON COLUMN "hot_words"."id" IS '主键，应用层生成（"hw"+32hex，IdGenerator.hotWord()），varchar2(36) 对齐既有 ID 风格';
COMMENT ON COLUMN "hot_words"."content" IS '热门词内容（用户问题文本），应用层限长 200 字符；同知识库内唯一（uq_hot_words_kb_content）';
COMMENT ON COLUMN "hot_words"."kb_id" IS '所属知识库 id，关联 knowledge_bases.id；必填。知识库为软删除，FK 仅做存在性约束，不 CASCADE，清理随应用层';
COMMENT ON COLUMN "hot_words"."sort_order" IS '排序号（界面"排序"字段），数值小者靠前；同 kb 内可重复，列表按 (sort_order, updated_at) 排序';
COMMENT ON COLUMN "hot_words"."status" IS '状态：1=启用中 / 0=已停用；每 kb 启用中最多 5 条（应用层事务内 advisory lock + 计数保证）';
COMMENT ON COLUMN "hot_words"."created_by" IS '创建人账号，取 JWT sub（OperatorIdHolder），前端不传';
COMMENT ON COLUMN "hot_words"."created_at" IS '创建时间';
COMMENT ON COLUMN "hot_words"."updated_at" IS '最近更新时间（列表"更新时间"列）';
COMMENT ON TABLE "hot_words" IS '热门词管理（高频用户问题词库；每知识库最多 5 条启用中，应用层 advisory lock 保证）';

-- ----------------------------
-- Records of hot_words
-- ----------------------------

-- ----------------------------
-- Table structure for import_batches
-- ----------------------------
CREATE TABLE "import_batches" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "kb_id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "category_id" varchar2(36) COLLATE "pg_catalog"."default",
  "batch_name" varchar2(255) COLLATE "pg_catalog"."default" NOT NULL,
  "total_files" int4 NOT NULL DEFAULT 0,
  "total_size" int8 NOT NULL DEFAULT 0,
  "status" varchar2(15) COLLATE "pg_catalog"."default" NOT NULL,
  "default_metadata" jsonb,
  "draft_conflict_policy" varchar2(15) COLLATE "pg_catalog"."default" NOT NULL,
  "created_by" varchar2(50) COLLATE "pg_catalog"."default" NOT NULL,
  "created_at" timestamp(6) NOT NULL,
  "updated_at" timestamp(6) NOT NULL
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "import_batches"."id" IS '批次 ID（IdGenerator 生成，tus upload session 归集键）';
COMMENT ON COLUMN "import_batches"."kb_id" IS '所属知识库 ID';
COMMENT ON COLUMN "import_batches"."category_id" IS '默认类别 ID（可空=根类别）';
COMMENT ON COLUMN "import_batches"."batch_name" IS '批次名称';
COMMENT ON COLUMN "import_batches"."total_files" IS '文件总数（动态聚合冗余，便于查询）';
COMMENT ON COLUMN "import_batches"."total_size" IS '总字节数（动态聚合冗余）';
COMMENT ON COLUMN "import_batches"."status" IS '批次状态：UPLOADING/COMPLETED/PARTIAL/ABORTED';
COMMENT ON COLUMN "import_batches"."default_metadata" IS '批次默认元数据：逐文件 document_metadata 默认值（product/chip_model/sensitivity/official/access_policy），complete 时合并逐文件覆盖后仅写各文档 document_metadata';
COMMENT ON COLUMN "import_batches"."draft_conflict_policy" IS '草稿态同名冲突策略：OVERRIDE/SKIP（默认 OVERRIDE，逐文件可覆盖）';
COMMENT ON COLUMN "import_batches"."created_by" IS '创建者用户 ID';
COMMENT ON COLUMN "import_batches"."created_at" IS '创建时间';
COMMENT ON COLUMN "import_batches"."updated_at" IS '更新时间';
COMMENT ON TABLE "import_batches" IS '导入批次：聚合一次初始化灌库的上千 tus 上传会话，批次进度由 upload_sessions 聚合';

-- ----------------------------
-- Records of import_batches
-- ----------------------------

-- ----------------------------
-- Table structure for kb_callback_processed
-- ----------------------------
CREATE TABLE "kb_callback_processed" (
  "message_id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "document_id" varchar2(36) COLLATE "pg_catalog"."default",
  "kb_id" varchar2(36) COLLATE "pg_catalog"."default",
  "event_type" varchar2(50) COLLATE "pg_catalog"."default" NOT NULL,
  "status" varchar2(15) COLLATE "pg_catalog"."default" NOT NULL,
  "received_at" timestamp(6) NOT NULL
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("message_id");
COMMENT ON COLUMN "kb_callback_processed"."message_id" IS '模型回调信封 messageId（主键，模型新生成，非原事件 ID）';
COMMENT ON COLUMN "kb_callback_processed"."document_id" IS '文档 ID（KB 级回调为 NULL）';
COMMENT ON COLUMN "kb_callback_processed"."kb_id" IS '知识库 ID';
COMMENT ON COLUMN "kb_callback_processed"."event_type" IS '回调事件类型 PROCESSING_COMPLETED/PROCESSING_FAILED';
COMMENT ON COLUMN "kb_callback_processed"."status" IS '落库的处理状态 COMPLETED/FAILED';
COMMENT ON COLUMN "kb_callback_processed"."received_at" IS '消费时间（DB 时间，清理依据）';
COMMENT ON TABLE "kb_callback_processed" IS '模型回调幂等去重表：按 messageId 去重，防重放回退，兼作回调审计';

-- ----------------------------
-- Records of kb_callback_processed
-- ----------------------------

-- ----------------------------
-- Table structure for kb_dict_items
-- ----------------------------
CREATE TABLE "kb_dict_items" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "type_code" varchar2(100) COLLATE "pg_catalog"."default" NOT NULL,
  "label" varchar2(200) COLLATE "pg_catalog"."default" NOT NULL,
  "value" varchar2(200) COLLATE "pg_catalog"."default" NOT NULL,
  "sort_order" int4 DEFAULT 0,
  "status" int2 DEFAULT 1,
  "created_at" timestamp(6),
  "updated_at" timestamp(6)
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");

-- ----------------------------
-- Records of kb_dict_items
-- ----------------------------
INSERT INTO "kb_dict_items" ("id", "type_code", "label", "value", "sort_order", "status", "created_at", "updated_at") VALUES ('00000000-0000-4000-8000-000000000101', 'sensitivity', '公开', 'PUBLIC', 1, 1, '2026-08-13 06:31:22.861198', '2026-08-13 06:31:22.861198');
INSERT INTO "kb_dict_items" ("id", "type_code", "label", "value", "sort_order", "status", "created_at", "updated_at") VALUES ('00000000-0000-4000-8000-000000000102', 'sensitivity', '内部', 'INTERNAL', 2, 1, '2026-08-13 06:31:22.864512', '2026-08-13 06:31:22.864512');
INSERT INTO "kb_dict_items" ("id", "type_code", "label", "value", "sort_order", "status", "created_at", "updated_at") VALUES ('00000000-0000-4000-8000-000000000103', 'sensitivity', '机密', 'CONFIDENTIAL', 3, 1, '2026-08-13 06:31:22.86671', '2026-08-13 06:31:22.86671');
INSERT INTO "kb_dict_items" ("id", "type_code", "label", "value", "sort_order", "status", "created_at", "updated_at") VALUES ('00000000-0000-4000-8000-000000000104', 'sensitivity', '绝密', 'TOP_SECRET', 4, 1, '2026-08-13 06:31:22.869264', '2026-08-13 06:31:22.869264');
INSERT INTO "kb_dict_items" ("id", "type_code", "label", "value", "sort_order", "status", "created_at", "updated_at") VALUES ('00000000-0000-4000-8000-000000000201', 'official', '官方', 'OFFICIAL', 1, 1, '2026-08-14 06:48:17.644732', '2026-08-14 06:48:17.644732');
INSERT INTO "kb_dict_items" ("id", "type_code", "label", "value", "sort_order", "status", "created_at", "updated_at") VALUES ('00000000-0000-4000-8000-000000000202', 'official', '非官方', 'NON_OFFICIAL', 2, 1, '2026-08-14 06:48:17.656932', '2026-08-14 06:48:17.656932');
INSERT INTO "kb_dict_items" ("id", "type_code", "label", "value", "sort_order", "status", "created_at", "updated_at") VALUES ('00000000-0000-4000-8000-000000000301', 'tag', 'GPIO', 'GPIO', 1, 1, '2026-08-14 06:48:17.673134', '2026-08-14 06:48:17.673134');
INSERT INTO "kb_dict_items" ("id", "type_code", "label", "value", "sort_order", "status", "created_at", "updated_at") VALUES ('00000000-0000-4000-8000-000000000302', 'tag', 'UART', 'UART', 2, 1, '2026-08-14 06:48:17.679826', '2026-08-14 06:48:17.679826');
INSERT INTO "kb_dict_items" ("id", "type_code", "label", "value", "sort_order", "status", "created_at", "updated_at") VALUES ('00000000-0000-4000-8000-000000000303', 'tag', 'I2C', 'I2C', 3, 1, '2026-08-14 06:48:17.685149', '2026-08-14 06:48:17.685149');
INSERT INTO "kb_dict_items" ("id", "type_code", "label", "value", "sort_order", "status", "created_at", "updated_at") VALUES ('00000000-0000-4000-8000-000000000304', 'tag', 'SPI', 'SPI', 4, 1, '2026-08-14 06:48:17.704689', '2026-08-14 06:48:17.704689');
INSERT INTO "kb_dict_items" ("id", "type_code", "label", "value", "sort_order", "status", "created_at", "updated_at") VALUES ('00000000-0000-4000-8000-000000000305', 'tag', 'NPU', 'NPU', 5, 1, '2026-08-14 06:48:17.708594', '2026-08-14 06:48:17.708594');
INSERT INTO "kb_dict_items" ("id", "type_code", "label", "value", "sort_order", "status", "created_at", "updated_at") VALUES ('00000000-0000-4000-8000-000000000306', 'tag', 'ISP', 'ISP', 6, 1, '2026-08-14 06:48:17.711806', '2026-08-14 06:48:17.711806');
INSERT INTO "kb_dict_items" ("id", "type_code", "label", "value", "sort_order", "status", "created_at", "updated_at") VALUES ('00000000-0000-4000-8000-000000000307', 'tag', 'Codec', 'Codec', 7, 1, '2026-08-14 06:48:17.720388', '2026-08-14 06:48:17.720388');
INSERT INTO "kb_dict_items" ("id", "type_code", "label", "value", "sort_order", "status", "created_at", "updated_at") VALUES ('00000000-0000-4000-8000-000000000308', 'tag', 'DDR', 'DDR', 8, 1, '2026-08-14 06:48:17.723874', '2026-08-17 15:06:59.920489');

-- ----------------------------
-- Table structure for kb_dict_types
-- ----------------------------
CREATE TABLE "kb_dict_types" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "type_code" varchar2(100) COLLATE "pg_catalog"."default" NOT NULL,
  "type_name" varchar2(200) COLLATE "pg_catalog"."default" NOT NULL,
  "controlled_meta_keys" varchar2(500) COLLATE "pg_catalog"."default",
  "status" int2 DEFAULT 1,
  "created_at" timestamp(6),
  "updated_at" timestamp(6),
  "is_preset" bool NOT NULL DEFAULT false
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");

-- ----------------------------
-- Records of kb_dict_types
-- ----------------------------
INSERT INTO "kb_dict_types" ("id", "type_code", "type_name", "controlled_meta_keys", "status", "created_at", "updated_at", "is_preset") VALUES ('00000000-0000-4000-8000-000000000004', 'sensitivity', '安全等级', 'sensitivity', 1, '2026-08-13 06:31:22.857607', '2026-08-13 06:31:22.857607', 't');
INSERT INTO "kb_dict_types" ("id", "type_code", "type_name", "controlled_meta_keys", "status", "created_at", "updated_at", "is_preset") VALUES ('00000000-0000-4000-8000-000000000001', 'product', '产品', 'product', 1, '2026-08-10 10:46:52', '2026-08-10 10:46:55', 't');
INSERT INTO "kb_dict_types" ("id", "type_code", "type_name", "controlled_meta_keys", "status", "created_at", "updated_at", "is_preset") VALUES ('00000000-0000-4000-8000-000000000002', 'version', '版本', 'version', 1, '2026-08-13 14:52:52', '2026-08-13 14:52:55', 't');
INSERT INTO "kb_dict_types" ("id", "type_code", "type_name", "controlled_meta_keys", "status", "created_at", "updated_at", "is_preset") VALUES ('00000000-0000-4000-8000-000000000010', 'official', '官方标识', 'official', 1, '2026-08-14 06:48:17.635992', '2026-08-14 06:48:17.635992', 't');
INSERT INTO "kb_dict_types" ("id", "type_code", "type_name", "controlled_meta_keys", "status", "created_at", "updated_at", "is_preset") VALUES ('00000000-0000-4000-8000-000000000020', 'tag', '标签', NULL, 1, '2026-08-14 06:48:17.662925', '2026-08-14 06:48:17.662925', 't');
INSERT INTO "kb_dict_types" ("id", "type_code", "type_name", "controlled_meta_keys", "status", "created_at", "updated_at", "is_preset") VALUES ('00000000-0000-4000-8000-000000000003', 'chip', '芯片', 'chip_model', 1, '2026-08-13 14:52:59', '2026-08-17 15:02:52.06423', 't');

-- ----------------------------
-- Table structure for knowledge_base_metadata
-- ----------------------------
CREATE TABLE "knowledge_base_metadata" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "created_at" timestamp(6),
  "kb_id" varchar2(36) COLLATE "pg_catalog"."default",
  "meta_key" varchar2(50) COLLATE "pg_catalog"."default",
  "meta_value" varchar2(200) COLLATE "pg_catalog"."default",
  "updated_at" timestamp(6)
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "knowledge_base_metadata"."id" IS '主键';
COMMENT ON COLUMN "knowledge_base_metadata"."created_at" IS '创建时间';
COMMENT ON COLUMN "knowledge_base_metadata"."kb_id" IS '知识库 ID，关联 knowledge_bases.id';
COMMENT ON COLUMN "knowledge_base_metadata"."meta_key" IS '属性键，如 org、default_sensitivity';
COMMENT ON COLUMN "knowledge_base_metadata"."meta_value" IS '属性值';
COMMENT ON COLUMN "knowledge_base_metadata"."updated_at" IS '更新时间';
COMMENT ON TABLE "knowledge_base_metadata" IS '知识库元数据表（key-value 扩展）';

-- ----------------------------
-- Records of knowledge_base_metadata
-- ----------------------------

-- ----------------------------
-- Table structure for knowledge_bases
-- ----------------------------
CREATE TABLE "knowledge_bases" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "created_at" timestamp(6) NOT NULL,
  "created_by" varchar2(50) COLLATE "pg_catalog"."default" NOT NULL,
  "description" varchar2(200) COLLATE "pg_catalog"."default",
  "name" varchar2(50) COLLATE "pg_catalog"."default" NOT NULL,
  "dir_name" varchar2(255) COLLATE "pg_catalog"."default",
  "status" int2 NOT NULL DEFAULT 1,
  "updated_at" timestamp(6),
  "process_status" varchar2(15) COLLATE "pg_catalog"."default",
  "process_error" varchar2(255) COLLATE "pg_catalog"."default",
  "process_error_code" varchar2(32) COLLATE "pg_catalog"."default",
  "processed_at" timestamp(6),
  CONSTRAINT "pk_knowledge_bases" PRIMARY KEY ("id")
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "knowledge_bases"."id" IS '主键';
COMMENT ON COLUMN "knowledge_bases"."created_at" IS '创建时间';
COMMENT ON COLUMN "knowledge_bases"."created_by" IS '创建者';
COMMENT ON COLUMN "knowledge_bases"."description" IS '描述';
COMMENT ON COLUMN "knowledge_bases"."name" IS '知识库名（活跃数据唯一，软删除数据可重名）';
COMMENT ON COLUMN "knowledge_bases"."dir_name" IS '物理目录段（创建时唯一化确定，终身不变；NULL=存量行回退按名称解析）';
COMMENT ON COLUMN "knowledge_bases"."status" IS '状态：0=删除, 1=正常';
COMMENT ON COLUMN "knowledge_bases"."updated_at" IS '更新时间';
COMMENT ON COLUMN "knowledge_bases"."process_status" IS '模型处理状态（回调驱动）：PENDING/COMPLETED/FAILED';
COMMENT ON COLUMN "knowledge_bases"."process_error" IS '模型处理失败原因（KB_CREATED/KB_DELETED 回调 FAILED 时记录）';
COMMENT ON COLUMN "knowledge_bases"."process_error_code" IS '模型处理失败错误码';
COMMENT ON COLUMN "knowledge_bases"."processed_at" IS '模型处理完成/失败时间（回调时回传）';
COMMENT ON TABLE "knowledge_bases" IS '知识库';

-- ----------------------------
-- Records of knowledge_bases
-- ----------------------------

-- ----------------------------
-- Table structure for menus
-- ----------------------------
CREATE TABLE "menus" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "component" varchar2(60) COLLATE "pg_catalog"."default",
  "created_at" timestamp(6),
  "icon" varchar2(20) COLLATE "pg_catalog"."default",
  "menu_name" varchar2(50) COLLATE "pg_catalog"."default" NOT NULL,
  "parent_id" varchar2(36) COLLATE "pg_catalog"."default",
  "path" varchar2(100) COLLATE "pg_catalog"."default",
  "permission_id" varchar2(36) COLLATE "pg_catalog"."default",
  "sort_order" int4,
  "status" int2 NOT NULL DEFAULT 1,
  "updated_at" timestamp(6),
  "visible" bool NOT NULL
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "menus"."id" IS '主键';
COMMENT ON COLUMN "menus"."component" IS '前端组件路径';
COMMENT ON COLUMN "menus"."created_at" IS '创建时间';
COMMENT ON COLUMN "menus"."icon" IS '图标标识';
COMMENT ON COLUMN "menus"."menu_name" IS '菜单名称（支持国际化 key）';
COMMENT ON COLUMN "menus"."parent_id" IS '父菜单 ID';
COMMENT ON COLUMN "menus"."path" IS '前端路由路径';
COMMENT ON COLUMN "menus"."permission_id" IS '关联权限 ID';
COMMENT ON COLUMN "menus"."sort_order" IS '排序号';
COMMENT ON COLUMN "menus"."status" IS '状态：0=禁用, 1=正常';
COMMENT ON COLUMN "menus"."updated_at" IS '更新时间';
COMMENT ON COLUMN "menus"."visible" IS '是否可见';
COMMENT ON TABLE "menus" IS '菜单表';

-- ----------------------------
-- Records of menus
-- ----------------------------

-- ----------------------------
-- Table structure for permissions
-- ----------------------------
CREATE TABLE "permissions" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "created_at" timestamp(6),
  "parent_id" varchar2(36) COLLATE "pg_catalog"."default",
  "permission_code" varchar2(20) COLLATE "pg_catalog"."default" NOT NULL,
  "permission_name" varchar2(50) COLLATE "pg_catalog"."default" NOT NULL,
  "sort_order" int4 NOT NULL,
  "type" varchar2(6) COLLATE "pg_catalog"."default" NOT NULL
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "permissions"."id" IS '主键';
COMMENT ON COLUMN "permissions"."created_at" IS '创建时间';
COMMENT ON COLUMN "permissions"."parent_id" IS '父权限 ID，支持树形结构';
COMMENT ON COLUMN "permissions"."permission_code" IS '权限码，唯一，如 kb:doc:read、sys:user';
COMMENT ON COLUMN "permissions"."permission_name" IS '权限名称';
COMMENT ON COLUMN "permissions"."sort_order" IS '排序号';
COMMENT ON COLUMN "permissions"."type" IS '类型：menu / button / api';
COMMENT ON TABLE "permissions" IS '权限表';

-- ----------------------------
-- Records of permissions
-- ----------------------------

-- ----------------------------
-- Table structure for review_history
-- ----------------------------
CREATE TABLE "review_history" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "action" varchar2(20) COLLATE "pg_catalog"."default",
  "comment" varchar2(500) COLLATE "pg_catalog"."default",
  "created_at" timestamp(6),
  "document_id" varchar2(36) COLLATE "pg_catalog"."default",
  "from_status" varchar2(20) COLLATE "pg_catalog"."default",
  "reviewer_id" varchar2(50) COLLATE "pg_catalog"."default",
  "to_status" varchar2(20) COLLATE "pg_catalog"."default",
  "version" varchar2(64) COLLATE "pg_catalog"."default"
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "review_history"."id" IS '主键';
COMMENT ON COLUMN "review_history"."action" IS '生命周期动作：SUBMIT_REVIEW / CANCEL_REVIEW / APPROVE / REJECT / ARCHIVE / DEPRECATE / EDIT / VERSION_OVERRIDE 等';
COMMENT ON COLUMN "review_history"."comment" IS '审核意见';
COMMENT ON COLUMN "review_history"."created_at" IS '创建时间';
COMMENT ON COLUMN "review_history"."document_id" IS '文档 ID，关联 documents.id';
COMMENT ON COLUMN "review_history"."from_status" IS '从哪个状态变更';
COMMENT ON COLUMN "review_history"."reviewer_id" IS '审核人用户 ID';
COMMENT ON COLUMN "review_history"."to_status" IS '变更到哪个状态';
COMMENT ON COLUMN "review_history"."version" IS '动作发生时文档的版本号（documents.current_version 快照）';
COMMENT ON TABLE "review_history" IS '审核历史表（记录不可删除、不可修改）';

-- ----------------------------
-- Records of review_history
-- ----------------------------

-- ----------------------------
-- Table structure for role_permissions
-- ----------------------------
CREATE TABLE "role_permissions" (
  "permission_id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "role_id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("permission_id");
COMMENT ON COLUMN "role_permissions"."permission_id" IS '权限id';
COMMENT ON COLUMN "role_permissions"."role_id" IS '角色id';
COMMENT ON TABLE "role_permissions" IS '角色-权限关联表';

-- ----------------------------
-- Records of role_permissions
-- ----------------------------

-- ----------------------------
-- Table structure for roles
-- ----------------------------
CREATE TABLE "roles" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "created_at" timestamp(6),
  "description" varchar2(200) COLLATE "pg_catalog"."default" NOT NULL,
  "is_preset" bool NOT NULL DEFAULT false,
  "role_key" varchar2(30) COLLATE "pg_catalog"."default" NOT NULL,
  "role_name" varchar2(50) COLLATE "pg_catalog"."default" NOT NULL,
  "status" int2 NOT NULL DEFAULT 1,
  "updated_at" timestamp(6)
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "roles"."id" IS '主键';
COMMENT ON COLUMN "roles"."created_at" IS '创建时间';
COMMENT ON COLUMN "roles"."description" IS '描述';
COMMENT ON COLUMN "roles"."is_preset" IS '是否预设角色：true=预设（不可删除）, false=自定义';
COMMENT ON COLUMN "roles"."role_key" IS '角色标识，唯一，如 sys_admin';
COMMENT ON COLUMN "roles"."role_name" IS '角色名称';
COMMENT ON COLUMN "roles"."status" IS '状态：0=禁用, 1=正常';
COMMENT ON COLUMN "roles"."updated_at" IS '更新时间';
COMMENT ON TABLE "roles" IS '角色表';

-- ----------------------------
-- Records of roles
-- ----------------------------

-- ----------------------------
-- Table structure for upload_sessions
-- ----------------------------
CREATE TABLE "upload_sessions" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "batch_id" varchar2(36) COLLATE "pg_catalog"."default",
  "kb_id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "category_id" varchar2(36) COLLATE "pg_catalog"."default",
  "file_name" varchar2(255) COLLATE "pg_catalog"."default" NOT NULL,
  "file_title" varchar2(200) COLLATE "pg_catalog"."default" NOT NULL,
  "mime_type" varchar2(100) COLLATE "pg_catalog"."default",
  "upload_length" int8 NOT NULL,
  "upload_offset" int8 NOT NULL DEFAULT 0,
  "draft_policy" varchar2(15) COLLATE "pg_catalog"."default" NOT NULL,
  "metadata" jsonb,
  "status" varchar2(15) COLLATE "pg_catalog"."default" NOT NULL,
  "document_id" varchar2(36) COLLATE "pg_catalog"."default",
  "error" varchar2(255) COLLATE "pg_catalog"."default",
  "temp_path" varchar2(500) COLLATE "pg_catalog"."default" NOT NULL,
  "expires_at" timestamp(6) NOT NULL,
  "created_by" varchar2(50) COLLATE "pg_catalog"."default" NOT NULL,
  "created_at" timestamp(6) NOT NULL,
  "updated_at" timestamp(6) NOT NULL,
  "entries_done" int4 NOT NULL DEFAULT 0,
  "skipped_count" int4 DEFAULT 0,
  "purpose" varchar2(128) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'BATCH_IMPORT'::character varying,
  "target_document_id" varchar2(128) COLLATE "pg_catalog"."default"
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "upload_sessions"."id" IS 'tus upload id（即 sessionId，IdGenerator 生成）';
COMMENT ON COLUMN "upload_sessions"."batch_id" IS '所属批次 ID';
COMMENT ON COLUMN "upload_sessions"."kb_id" IS '知识库 ID';
COMMENT ON COLUMN "upload_sessions"."category_id" IS '类别 ID（可空=批次默认类别）';
COMMENT ON COLUMN "upload_sessions"."file_name" IS '原始文件名（已清洗，防路径穿越）';
COMMENT ON COLUMN "upload_sessions"."file_title" IS '文档标题（文件名去扩展名）';
COMMENT ON COLUMN "upload_sessions"."mime_type" IS 'MIME 类型';
COMMENT ON COLUMN "upload_sessions"."upload_length" IS '文件总大小（tus Upload-Length，字节）';
COMMENT ON COLUMN "upload_sessions"."upload_offset" IS '已上传字节（断点；以临时文件实际大小为准）';
COMMENT ON COLUMN "upload_sessions"."draft_policy" IS '草稿态同名冲突策略：OVERRIDE/SKIP（create 时取批次默认或逐文件覆盖）';
COMMENT ON COLUMN "upload_sessions"."metadata" IS 'create 时解析后的逐文件 document_metadata（批次默认 ⊕ 逐文件覆盖，version 抽出走 current_version 不入此列），已通过字典校验 fail-closed';
COMMENT ON COLUMN "upload_sessions"."status" IS '会话状态：CREATED/UPLOADING/COMPLETED/SKIPPED/FAILED/ABORTED/EXPIRED';
COMMENT ON COLUMN "upload_sessions"."document_id" IS '完成后关联 documents.id';
COMMENT ON COLUMN "upload_sessions"."error" IS '失败原因（status=FAILED 时记录）';
COMMENT ON COLUMN "upload_sessions"."temp_path" IS '临时文件相对路径 .uploads/{id}';
COMMENT ON COLUMN "upload_sessions"."expires_at" IS '会话过期时间（清理 job 依据）';
COMMENT ON COLUMN "upload_sessions"."created_by" IS '创建者用户 ID';
COMMENT ON COLUMN "upload_sessions"."created_at" IS '创建时间';
COMMENT ON COLUMN "upload_sessions"."updated_at" IS '更新时间';
COMMENT ON COLUMN "upload_sessions"."entries_done" IS 'zip 异步解压已处理条目数（进度；单文件会话恒 0，PROCESSING 期间逐条目累加）';
COMMENT ON COLUMN "upload_sessions"."skipped_count" IS 'zip 解压跳过条目数（不支持类型/同名等）；单文件恒 0';
COMMENT ON TABLE "upload_sessions" IS 'tus 上传会话：一个文件一条，分片追加写 .uploads/{id}，complete 即落库 documents(DRAFT)';

-- ----------------------------
-- Records of upload_sessions
-- ----------------------------

-- ----------------------------
-- Table structure for user_metadata
-- ----------------------------
CREATE TABLE "user_metadata" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "created_at" timestamp(6),
  "meta_key" varchar2(20) COLLATE "pg_catalog"."default" NOT NULL,
  "meta_value" varchar2(50) COLLATE "pg_catalog"."default" NOT NULL,
  "updated_at" timestamp(6),
  "user_id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "user_metadata"."id" IS '主键';
COMMENT ON COLUMN "user_metadata"."created_at" IS '创建时间';
COMMENT ON COLUMN "user_metadata"."meta_key" IS '属性键，如 department, region, employment_type, clearance';
COMMENT ON COLUMN "user_metadata"."meta_value" IS '属性值';
COMMENT ON COLUMN "user_metadata"."updated_at" IS '更新时间';
COMMENT ON COLUMN "user_metadata"."user_id" IS '用户 ID，关联 users.id';
COMMENT ON TABLE "user_metadata" IS '用户元数据表';

-- ----------------------------
-- Records of user_metadata
-- ----------------------------

-- ----------------------------
-- Table structure for user_roles
-- ----------------------------
CREATE TABLE "user_roles" (
  "role_id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "user_id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("role_id");
COMMENT ON COLUMN "user_roles"."role_id" IS '角色id';
COMMENT ON COLUMN "user_roles"."user_id" IS '用户id';
COMMENT ON TABLE "user_roles" IS '用户-角色关联表';

-- ----------------------------
-- Records of user_roles
-- ----------------------------

-- ----------------------------
-- Table structure for users
-- ----------------------------
CREATE TABLE "users" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "username" varchar2(128) COLLATE "pg_catalog"."default" NOT NULL,
  "password" varchar2(256) COLLATE "pg_catalog"."default" NOT NULL,
  "real_name" varchar2(128) COLLATE "pg_catalog"."default",
  "email" varchar2(256) COLLATE "pg_catalog"."default",
  "phone" varchar2(64) COLLATE "pg_catalog"."default",
  "status" int2 NOT NULL DEFAULT 1,
  "source" varchar2(16) COLLATE "pg_catalog"."default" DEFAULT 'local'::character varying,
  "last_login" timestamp(6),
  "created_at" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updated_at" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "users"."id" IS '主键 UUID';
COMMENT ON COLUMN "users"."username" IS '用户名，全局唯一（含软删除，避免删除后账号被占用）';
COMMENT ON COLUMN "users"."password" IS 'BCrypt 哈希后的密码';
COMMENT ON COLUMN "users"."real_name" IS '真实姓名';
COMMENT ON COLUMN "users"."email" IS '邮箱';
COMMENT ON COLUMN "users"."phone" IS '电话';
COMMENT ON COLUMN "users"."status" IS '状态: 0=删除, 1=正常, 2=禁用';
COMMENT ON COLUMN "users"."source" IS '来源: local / oidc';
COMMENT ON COLUMN "users"."last_login" IS '最后登录时间';
COMMENT ON COLUMN "users"."created_at" IS '创建时间';
COMMENT ON COLUMN "users"."updated_at" IS '更新时间';
COMMENT ON TABLE "users" IS '用户表';

-- ----------------------------
-- Records of users
-- ----------------------------
INSERT INTO "users" ("id", "username", "password", "real_name", "email", "phone", "status", "source", "last_login", "created_at", "updated_at") VALUES ('fce87655-f518-4129-bf27-4823d5053e52', 'admin', '$2a$10$bOKF7MQ6X1upP9EOtWK7.uBGdIersOhW.bdGBVlE0affA6Lv19Vja', '管理员', 'admin@hisilicon.com', NULL, 1, 'local', '2026-09-21 12:34:45.312509', '2026-08-07 02:56:12.865732', '2026-08-07 02:56:12.865732');

-- ----------------------------
-- Indexes structure for table conversation_messages
-- ----------------------------
CREATE INDEX "idx_messages_conversation" ON "conversation_messages" USING btree (
  "conversation_id" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST,
  "created_at" "pg_catalog"."timestamp_ops" ASC NULLS LAST
);

-- ----------------------------
-- Primary Key structure for table conversation_messages
-- ----------------------------
ALTER TABLE "conversation_messages" ADD CONSTRAINT "conversation_messages_pkey" PRIMARY KEY ("id");

-- ----------------------------
-- Indexes structure for table conversations
-- ----------------------------
CREATE INDEX "idx_conversations_account" ON "conversations" USING btree (
  "account" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);
CREATE INDEX "idx_conversations_account_status" ON "conversations" USING btree (
  "account" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST,
  "status" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);
CREATE INDEX "idx_conversations_created_at" ON "conversations" USING btree (
  "created_at" "pg_catalog"."timestamp_ops" DESC NULLS FIRST
);

-- ----------------------------
-- Primary Key structure for table conversations
-- ----------------------------
ALTER TABLE "conversations" ADD CONSTRAINT "conversations_pkey" PRIMARY KEY ("id");

-- ----------------------------
-- Indexes structure for table document_contents
-- ----------------------------
CREATE INDEX "doc_contents_trgm_idx" ON "document_contents" USING gin (
  "content_text" COLLATE "pg_catalog"."default" "gin_trgm_ops"
);

-- ----------------------------
-- Primary Key structure for table document_contents
-- ----------------------------
ALTER TABLE "document_contents" ADD CONSTRAINT "document_contents_pkey" PRIMARY KEY ("document_id");

-- ----------------------------
-- Indexes structure for table document_outbox
-- ----------------------------
CREATE INDEX "idx_document_outbox_status_created" ON "document_outbox" USING btree (
  "status" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST,
  "created_at" "pg_catalog"."timestamp_ops" ASC NULLS LAST
);

-- ----------------------------
-- Primary Key structure for table document_outbox
-- ----------------------------
ALTER TABLE "document_outbox" ADD CONSTRAINT "document_outbox_pkey" PRIMARY KEY ("id");

-- ----------------------------
-- Indexes structure for table import_batches
-- ----------------------------
CREATE INDEX "idx_import_batches_created_by" ON "import_batches" USING btree (
  "created_by" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST,
  "created_at" "pg_catalog"."timestamp_ops" ASC NULLS LAST
);

-- ----------------------------
-- Primary Key structure for table import_batches
-- ----------------------------
ALTER TABLE "import_batches" ADD CONSTRAINT "import_batches_pkey" PRIMARY KEY ("id");

-- ----------------------------
-- Primary Key structure for table kb_callback_processed
-- ----------------------------
ALTER TABLE "kb_callback_processed" ADD CONSTRAINT "kb_callback_processed_pkey" PRIMARY KEY ("message_id");

-- ----------------------------
-- Indexes structure for table upload_sessions
-- ----------------------------
CREATE INDEX "idx_sessions_batch" ON "upload_sessions" USING btree (
  "batch_id" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST,
  "status" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);
CREATE INDEX "idx_sessions_expires" ON "upload_sessions" USING btree (
  "expires_at" "pg_catalog"."timestamp_ops" ASC NULLS LAST
);
CREATE INDEX "idx_sessions_target_doc" ON "upload_sessions" USING btree (
  "target_document_id" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST,
  "status" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);

-- ----------------------------
-- Primary Key structure for table upload_sessions
-- ----------------------------
ALTER TABLE "upload_sessions" ADD CONSTRAINT "upload_sessions_pkey" PRIMARY KEY ("id");

-- ----------------------------
-- Table structure for kb_sensitive_tags
-- ----------------------------
CREATE TABLE "kb_sensitive_tags" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "text_label" varchar2(255) COLLATE "pg_catalog"."default" NOT NULL,
  "label_identification" varchar2(64) COLLATE "pg_catalog"."default" NOT NULL,
  "remarks" varchar2(512) COLLATE "pg_catalog"."default",
  "status" int2 DEFAULT 1,
  "created_at" timestamp(6),
  "updated_at" timestamp(6)
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");

-- ----------------------------
-- Primary Key structure for table kb_sensitive_tags
-- ----------------------------
ALTER TABLE "kb_sensitive_tags" ADD CONSTRAINT "kb_sensitive_tags_pkey" PRIMARY KEY ("id");

-- ----------------------------
-- Table structure for kb_sensitive_lexicons
-- ----------------------------
CREATE TABLE "kb_sensitive_lexicons" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "tag_id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "word" varchar2(255) COLLATE "pg_catalog"."default" NOT NULL,
  "status" int2 NOT NULL DEFAULT 1,
  "created_at" timestamp(6),
  "created_by" varchar2(128) COLLATE "pg_catalog"."default",
  "remarks" varchar2(512) COLLATE "pg_catalog"."default"
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
COMMENT ON COLUMN "kb_sensitive_lexicons"."status" IS '状态：1=启用 / 2=停用（仅标签与词均启用时参与 DFA 匹配；词为硬删，无 0）';
COMMENT ON COLUMN "kb_sensitive_lexicons"."created_by" IS '创建人账号，取 JWT sub（OperatorIdHolder），前端不传；存量行为 NULL';
COMMENT ON COLUMN "kb_sensitive_lexicons"."remarks" IS '备注，可空；编辑接口全量替换';
CREATE INDEX "idx_sensitive_lexicons_tag" ON "kb_sensitive_lexicons" USING btree (
  "tag_id" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);

-- ----------------------------
-- Primary Key structure for table kb_sensitive_lexicons
-- ----------------------------
ALTER TABLE "kb_sensitive_lexicons" ADD CONSTRAINT "kb_sensitive_lexicons_pkey" PRIMARY KEY ("id");

-- ----------------------------
-- Table structure for kb_sensitive_question_logs
-- ----------------------------
CREATE TABLE "kb_sensitive_question_logs" (
  "id" varchar2(36) COLLATE "pg_catalog"."default" NOT NULL,
  "question" text COLLATE "pg_catalog"."default" NOT NULL,
  "account" varchar2(128) COLLATE "pg_catalog"."default",
  "word" varchar2(255) COLLATE "pg_catalog"."default",
  "label" varchar2(255) COLLATE "pg_catalog"."default",
  "identification" varchar2(64) COLLATE "pg_catalog"."default",
  "request_time" timestamp(6) NOT NULL
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");
CREATE INDEX "idx_sensitive_question_time" ON "kb_sensitive_question_logs" USING btree (
  "request_time" "pg_catalog"."timestamp_ops" DESC NULLS LAST
);
CREATE INDEX "idx_sensitive_question_account" ON "kb_sensitive_question_logs" USING btree (
  "account" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);

-- ----------------------------
-- Primary Key structure for table kb_sensitive_question_logs
-- ----------------------------
ALTER TABLE "kb_sensitive_question_logs" ADD CONSTRAINT "kb_sensitive_question_logs_pkey" PRIMARY KEY ("id");

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
    "id"              VARCHAR2(255)   NOT NULL,
    "message_id"      VARCHAR2(255)   NOT NULL,
    "conversation_id" VARCHAR2(255)   NOT NULL,
    "user_id"         VARCHAR2(255)   NOT NULL,
    "rating"          VARCHAR2(255)   NOT NULL,
    "reasons"         VARCHAR2(255),
    "comment"         TEXT,
    "created_at"      TIMESTAMP NOT NULL,
    "updated_at"      TIMESTAMP NOT NULL,
    CONSTRAINT "pk_message_feedbacks" PRIMARY KEY ("id"),
    CONSTRAINT "uq_message_feedbacks_msg_user" UNIQUE ("message_id", "user_id"),
    CONSTRAINT "ck_message_feedbacks_rating" CHECK ("rating" IN ('HELPFUL','UNHELPFUL'))
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");

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
    "id"         VARCHAR2(36)  NOT NULL,
    "content"    VARCHAR2(200) NOT NULL,
    "kb_id"      VARCHAR2(36)  NOT NULL,
    "sort_order" INT          NOT NULL DEFAULT 0,
    "status"     SMALLINT     NOT NULL,
    "created_by" VARCHAR2(128)  NOT NULL,
    "created_at" TIMESTAMP    NOT NULL,
    "updated_at" TIMESTAMP    NOT NULL,
    CONSTRAINT "pk_hot_words" PRIMARY KEY ("id"),
    CONSTRAINT "uq_hot_words_kb_content" UNIQUE ("kb_id", "content"),
    CONSTRAINT "ck_hot_words_status" CHECK ("status" IN (0, 1))
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("id");

COMMENT ON TABLE "hot_words"
    IS '热门词管理（高频用户问题词库；每知识库最多 5 条启用中，应用层 advisory lock 保证）';
COMMENT ON COLUMN "hot_words"."id"         IS '主键，应用层生成（"hw"+32hex，IdGenerator.hotWord()），varchar2(36) 对齐既有 ID 风格';
COMMENT ON COLUMN "hot_words"."content"    IS '热门词内容（用户问题文本），应用层限长 200 字符；同知识库内唯一（uq_hot_words_kb_content）';
COMMENT ON COLUMN "hot_words"."kb_id"      IS '所属知识库 id，关联 knowledge_bases.id；必填。知识库为软删除，FK 仅做存在性约束，不 CASCADE，清理随应用层';
COMMENT ON COLUMN "hot_words"."sort_order" IS '排序号（界面"排序"字段），数值小者靠前；同 kb 内可重复，列表按 (sort_order, updated_at) 排序';
COMMENT ON COLUMN "hot_words"."status"     IS '状态：1=启用中 / 0=已停用；每 kb 启用中最多 5 条（应用层事务内 advisory lock + 计数保证）';
COMMENT ON COLUMN "hot_words"."created_by" IS '创建人账号，取 JWT sub（OperatorIdHolder），前端不传';
COMMENT ON COLUMN "hot_words"."created_at" IS '创建时间';
COMMENT ON COLUMN "hot_words"."updated_at" IS '最近更新时间（列表"更新时间"列）';

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
-- 同步基线：docs/sql/migration_019_kb_stats.sql + migration_020_hit_rate_dashboard.sql + migration_021_kb_names_snapshot.sql。
-- =====================================================================

CREATE TABLE IF NOT EXISTS "qa_kb_stats" (
    "message_id"      VARCHAR2(255)   NOT NULL,
    "rag_triggered"   BOOLEAN,
    "kb_hit"          BOOLEAN,
    "no_hit_reason"   VARCHAR2(255),
    "retrieved_count" INT,
    "used_count"      INT,
    "hit_kbs"         TEXT,
    "rewritten_query" TEXT,
    "top_score"       NUMERIC,
    "route_reason"    VARCHAR2(255),
    "degraded"        BOOLEAN,
    "kb_stats_json"   TEXT,
    "query_text"      TEXT,
    "account"         VARCHAR2(128),
    "source"          VARCHAR2(32) DEFAULT 'CHAT_QA',
    "handle_status"   VARCHAR2(32) DEFAULT 'PENDING',
    "selected_kbs"    TEXT,
    "kb_names_json"   TEXT,
    "created_at"      TIMESTAMP NOT NULL,
    CONSTRAINT "pk_qa_kb_stats" PRIMARY KEY ("message_id")
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("message_id");

CREATE INDEX IF NOT EXISTS "idx_qa_kb_stats_kb_hit_created"
    ON "qa_kb_stats" ("kb_hit", "created_at");

CREATE INDEX IF NOT EXISTS "idx_qa_kb_stats_created"
    ON "qa_kb_stats" ("created_at");

CREATE INDEX IF NOT EXISTS "idx_qa_kb_stats_handle_status"
    ON "qa_kb_stats" ("handle_status");

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
COMMENT ON COLUMN "qa_kb_stats"."query_text"    IS '用户问题原文（落库时冗余，仪表盘/未命中列表单表查询）';
COMMENT ON COLUMN "qa_kb_stats"."account"       IS '提问账号（会话归属账号）';
COMMENT ON COLUMN "qa_kb_stats"."source"        IS '来源渠道：CHAT_QA 智能问答 / RECEPTION_BOT 前台机器人 / AGENT_CALL Agent 调用（缺省 CHAT_QA）';
COMMENT ON COLUMN "qa_kb_stats"."handle_status" IS '未命中处理状态：PENDING 待处理 / PROCESSING 处理中 / RESOLVED 已补充（仅 kb_hit=false 行有业务意义）';
COMMENT ON COLUMN "qa_kb_stats"."selected_kbs"  IS '用户选择的知识库 id 列表，逗号分隔（multi 路径就绪过滤后下发模型的列表；芯片维度命中率分母口径）';
COMMENT ON COLUMN "qa_kb_stats"."kb_names_json" IS '知识库名称快照（落库时 selected_kbs ∪ hit_kbs 的 id→name JSON；解析失败回退 id）';
COMMENT ON COLUMN "qa_kb_stats"."created_at"      IS '落库时间';

-- =====================================================================
-- migration_025_mcp_tokens
-- MCP 网关入站鉴权的平台自签令牌台账：签发 / 列表 / 注销。
-- 设计要点见 docs/sql/migration_025_mcp_tokens.sql 头部注释，此处为同一份 DDL。
--   1) 主键与分布列都用 "jti"：DWS 分布式表唯一索引必须含分布列，jti 必须全局唯一。
--   2) 不存明文，只存 SHA-256 摘要（token_hash）。
--   3) 不建 username 二级索引：遵循 migration_022「只允许主键/唯一键索引」。
-- =====================================================================

CREATE TABLE IF NOT EXISTS "mcp_tokens" (
    "jti"          VARCHAR2(64)  NOT NULL,
    "username"     VARCHAR2(64)  NOT NULL,
    "token_hash"   VARCHAR2(64)  NOT NULL,
    "label"        VARCHAR2(128),
    "created_at"   TIMESTAMP     NOT NULL,
    "expires_at"   TIMESTAMP     NOT NULL,
    "revoked_at"   TIMESTAMP,
    "last_used_at" TIMESTAMP,
    CONSTRAINT "pk_mcp_tokens" PRIMARY KEY ("jti")
)
WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)
DISTRIBUTE BY HASH ("jti");

COMMENT ON TABLE "mcp_tokens"
    IS 'MCP 网关入站令牌台账（type=mcp 的平台自签 JWT；注销状态以 Redis mcp:revoked:{jti} 为准，本表留痕）';
COMMENT ON COLUMN "mcp_tokens"."jti"          IS 'JWT ID，主键兼分布列（DWS 唯一索引必须含分布列，故不另设 id）';
COMMENT ON COLUMN "mcp_tokens"."username"     IS '令牌归属平台账号，取 JWT sub；注销/列举按此过滤';
COMMENT ON COLUMN "mcp_tokens"."token_hash"   IS '令牌明文的 SHA-256 十六进制摘要，用于校验与排障比对，不存明文';
COMMENT ON COLUMN "mcp_tokens"."label"        IS '用户自填备注（如「公司笔记本 Trae」），便于自助识别与吊销';
COMMENT ON COLUMN "mcp_tokens"."created_at"   IS '签发时间';
COMMENT ON COLUMN "mcp_tokens"."expires_at"   IS '过期时间（签发时间 + platform.mcp-token.expire-days，默认 30 天）';
COMMENT ON COLUMN "mcp_tokens"."revoked_at"   IS '注销时间；NULL=未注销。到期与注销都视为不可用';
COMMENT ON COLUMN "mcp_tokens"."last_used_at" IS '预留列：网关不回写（只在 Redis 记 mcp:token-lastused:{jti}），恒为 NULL';

