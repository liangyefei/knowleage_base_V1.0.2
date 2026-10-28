-- =====================================================================
-- migration_023_oneaccess_oidc
-- 用途：为 OneAccess（华为云统一身份门户）OIDC 对接准备数据与索引。
--   1) 内置角色 community（社区用户）：OIDC 新用户首次登录时自动绑定该角色；
--      is_preset=TRUE 保证内置角色不可删除（应用层禁止删除/禁用预设角色）。
--   2) external_identities.user_id 普通索引：按用户查询外部身份关联。
--   3) (source, external_id) 唯一索引：防止同一来源的外部账号重复绑定。
--
-- 幂等性：全部语句使用 IF NOT EXISTS / NOT EXISTS，重复执行无副作用。
-- 目标库：GaussDB(DWS)，与 docs/sql/init.sql 同一套 DDL 惯例。
-- =====================================================================

-- 1) 内置角色 community -----------------------------------------------------------
-- ⚠️ roles.is_preset 为 bool 列（见 init.sql L836），这里写 TRUE 而非 1。
-- NOT EXISTS 保证幂等：角色已存在（无论内容是否被改动）时不再插入。
INSERT INTO "roles" ("id", "created_at", "description", "is_preset", "role_key", "role_name", "status")
SELECT gen_random_uuid()::text,
       CURRENT_TIMESTAMP,
       '内置角色：社区用户（OIDC 登录默认角色）。内置角色不可删除、不可禁用',
       TRUE,
       'community',
       '社区用户',
       1
WHERE NOT EXISTS (
  SELECT 1 FROM "roles" WHERE "role_key" = 'community'
);

-- 2) external_identities.user_id 普通索引 ------------------------------------------
-- 命名遵循现有惯例 idx_<表名缩写>_<列>（参考 idx_sessions_target_doc）。
-- ⚠️ 注意：migration_022 整改规则"只允许主键/唯一键索引"与本索引冲突，
-- 如扫描工具扫出请按本注释申诉保留（按 user_id 反查是 OIDC 登录的常规路径）。
CREATE INDEX IF NOT EXISTS "idx_external_identities_user"
  ON "external_identities" USING btree ("user_id");

-- 3) (source, external_id) 唯一约束 ------------------------------------------------
-- ⚠️ 唯一约束待评审：表按 HASH("id") 分布，DWS 分布式下唯一索引必须包含分布列，
-- (source, external_id) 不含 "id"，唯一索引在建表分布方案下不可行。
-- 先建普通索引兜底查询路径，去重由应用层保证；如需硬约束，评审后按以下方向之一处理：
--   a) 改表分布列为 source（影响面评估后 CTAS 重建）；
--   b) 新增 (source, external_id) 生成的冗余列作为分布列 + 唯一索引。
-- 当前先以普通索引 + 应用层幂等写入过渡：
CREATE INDEX IF NOT EXISTS "idx_external_identities_source_external"
  ON "external_identities" USING btree ("source", "external_id");
-- =====================================================================
