-- =====================================================================
-- migration_024_roles_builtin
-- 用途：角色管理与用户授权落地所需的内置数据。
--   1) 内置角色 sys_admin（系统管理员）：is_preset=TRUE，应用层禁止改状态/删除。
--      角色管理写操作（角色增删改、用户授权）以该角色作为最小管理员判定依据。
--   2) 把既有 admin 用户绑定到 sys_admin，保证首个管理员开箱可用。
--
-- 幂等性：全部语句使用 NOT EXISTS，重复执行无副作用。
-- 目标库：GaussDB(DWS)，与 docs/sql/init.sql 同一套 DDL 惯例。
-- 依赖：migration_023（community 内置角色）先执行即可，但不强依赖。
-- =====================================================================

-- 1) 内置角色 sys_admin ---------------------------------------------------------
-- ⚠️ roles.is_preset 为 bool 列（见 init.sql L836），这里写 TRUE 而非 1。
-- NOT EXISTS 保证幂等：角色已存在（无论内容是否被改动）时不再插入。
INSERT INTO "roles" ("id", "created_at", "description", "is_preset", "role_key", "role_name", "status")
SELECT gen_random_uuid()::text,
       CURRENT_TIMESTAMP,
       '内置角色：系统管理员（角色管理与用户授权）。内置角色不可删除、不可禁用',
       TRUE,
       'sys_admin',
       '系统管理员',
       1
WHERE NOT EXISTS (
  SELECT 1 FROM "roles" WHERE "role_key" = 'sys_admin'
);

-- 2) admin 用户绑定 sys_admin ----------------------------------------------------
-- user_roles 主键为 (user_id, role_id)：按该组合做 NOT EXISTS 去重。
-- 用户或角色任一不存在时不插入（避免脏关联）。
INSERT INTO "user_roles" ("user_id", "role_id")
SELECT u."id", r."id"
FROM "users" u, "roles" r
WHERE u."username" = 'admin'
  AND r."role_key" = 'sys_admin'
  AND NOT EXISTS (
    SELECT 1
    FROM "user_roles" ur
    WHERE ur."user_id" = u."id"
      AND ur."role_id" = r."id"
  );
-- =====================================================================
