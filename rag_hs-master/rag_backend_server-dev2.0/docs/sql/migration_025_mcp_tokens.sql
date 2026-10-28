-- =====================================================================
-- migration_025_mcp_tokens
-- 用途：MCP 网关入站鉴权的平台自签令牌台账（签发 / 列表 / 注销）。
--
-- 背景：MCP 客户端（Trae / Claude Desktop 等）不是浏览器，无法走 OAuth 授权码
--   跳转，只能在 mcpServers 配置里贴一个长期 Bearer 令牌。为兼顾可用性与可撤销性，
--   平台在 OIDC 登录态下签发专用 JWT（claim type=mcp，默认 30 天），把 jti 落库，
--   注销时把 jti 写进 Redis（网关即时 fail-closed 拒绝），本表留存审计痕迹。
--
-- 设计决策：
--   1. 主键/分布列都用 "jti"（而非惯例的 "id"）。
--      ⚠️ 依据 migration_023 已记录的 DWS 约束：表按 HASH 分布时，唯一索引/主键
--      必须包含分布列。jti 必须全局唯一（网关按 jti 查吊销、按 jti 注销），
--      若仍按 HASH("id") 分布则 jti 唯一性只能靠应用层保证，不可接受。
--      故本表不设冗余 "id" 列，jti 即主键。
--   2. 不存令牌明文，只存 SHA-256 十六进制摘要（token_hash，64 字符）。
--      明文仅在签发响应里返回一次，之后任何接口都取不回来。
--   3. 不建 username 二级索引：migration_022 整改规则「只允许主键/唯一键索引」。
--      按账号列举/批量注销走全表扫描；本表规模为「用户数 × 每人几个令牌」，
--      且 HASH("jti") 分布下按 username 查询本就跨节点，索引收益有限，不值得违规。
--   4. last_used_at 列保留但不回写：网关每次调用只在 Redis 记 mcp:token-lastused:{jti}，
--      避免 MCP 高频调用打穿数据库。需要精确最近使用时间时查 Redis。
--
-- 幂等性：CREATE TABLE IF NOT EXISTS，重复执行无副作用。
-- 目标库：GaussDB(DWS)，与 docs/sql/init.sql 同一套 DDL 惯例。
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
-- =====================================================================