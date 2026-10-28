-- =====================================================================
-- local-postgres-mcp_tokens
-- 用途：本项目 **本地开发/联调用的 PostgreSQL 版本** mcp_tokens 建表脚本。
--
-- 为什么需要这份：docs/sql/migration_025_mcp_tokens.sql 是 GaussDB(DWS) 方言
--   （VARCHAR2 / WITH (ORIENTATION=COLUMN) / DISTRIBUTE BY HASH），PostgreSQL 18
--   解析不了，本地联调建不出表。生产/测试环境**仍然只执行 migration_025**，
--   本文件不是迁移脚本，不参与发布，也不改任何 DWS 惯例。
--
-- 列、类型、可空性、主键名与 migration_025 一一对应（VARCHAR2(n) -> VARCHAR(n)，
--   DWS 的分布键/列存/压缩属性在 PG 无对应物，直接省略）。
--
-- 执行方式（迁移脚本不带 schema 前缀，必须指定 search_path=knowledge）：
--   Get-Content -Raw .\docs\sql\local-postgres-mcp_tokens.sql |
--     podman exec -i -e PGOPTIONS='-c search_path=knowledge' postgres psql `
--       -U postgres -d hisilicon_knowledge -v ON_ERROR_STOP=1 -f -
--
-- 幂等性：CREATE TABLE IF NOT EXISTS，重复执行无副作用。
-- 目标库：PostgreSQL（本地 podman 的 postgres 容器）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS "mcp_tokens" (
    "jti"          VARCHAR(64)  NOT NULL,
    "username"     VARCHAR(64)  NOT NULL,
    "token_hash"   VARCHAR(64)  NOT NULL,
    "label"        VARCHAR(128),
    "created_at"   TIMESTAMP    NOT NULL,
    "expires_at"   TIMESTAMP    NOT NULL,
    "revoked_at"   TIMESTAMP,
    "last_used_at" TIMESTAMP,
    CONSTRAINT "pk_mcp_tokens" PRIMARY KEY ("jti")
);

COMMENT ON TABLE "mcp_tokens"
    IS 'MCP 网关入站令牌台账（type=mcp 的平台自签 JWT；注销状态以 Redis mcp:revoked:{jti} 为准，本表留痕）';
COMMENT ON COLUMN "mcp_tokens"."jti"          IS 'JWT ID，主键（DWS 侧兼分布列，故不另设 id）';
COMMENT ON COLUMN "mcp_tokens"."username"     IS '令牌归属平台账号，取 JWT sub；注销/列举按此过滤';
COMMENT ON COLUMN "mcp_tokens"."token_hash"   IS '令牌明文的 SHA-256 十六进制摘要，用于校验与排障比对，不存明文';
COMMENT ON COLUMN "mcp_tokens"."label"        IS '用户自填备注（如「公司笔记本 Trae」），便于自助识别与吊销';
COMMENT ON COLUMN "mcp_tokens"."created_at"   IS '签发时间';
COMMENT ON COLUMN "mcp_tokens"."expires_at"   IS '过期时间（签发时间 + platform.mcp-token.expire-days，默认 30 天）';
COMMENT ON COLUMN "mcp_tokens"."revoked_at"   IS '注销时间；NULL=未注销。到期与注销都视为不可用';
COMMENT ON COLUMN "mcp_tokens"."last_used_at" IS '预留列：网关不回写（只在 Redis 记 mcp:token-lastused:{jti}），恒为 NULL';
-- =====================================================================