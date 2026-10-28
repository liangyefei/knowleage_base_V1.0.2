-- =====================================================================
-- migration_020_badcase_module_alter_v1
-- 把 badcase_tickets 的 3 个 jsonb 列改为 text。
-- 原因：Hibernate Reactive 对 jsonb 列反序列化 List<String>/Map 时
--       会把 JsonArray 强转 JsonObject 或 String，报 ClassCastException；
--       我们不做 JSON 内部查询/索引，用 text 列 + 应用层 ObjectMapper 序列化即可。
-- 幂等：可重复执行（USING 子句 + 类型转换）。
-- =====================================================================

ALTER TABLE "badcase_tickets"
    ALTER COLUMN "related_doc_ids" TYPE TEXT
    USING CASE WHEN "related_doc_ids" IS NULL THEN NULL ELSE "related_doc_ids"::text END;

ALTER TABLE "badcase_tickets"
    ALTER COLUMN "related_doc_names" TYPE TEXT
    USING CASE WHEN "related_doc_names" IS NULL THEN NULL ELSE "related_doc_names"::text END;

ALTER TABLE "badcase_tickets"
    ALTER COLUMN "source_payload" TYPE TEXT
    USING CASE WHEN "source_payload" IS NULL THEN NULL ELSE "source_payload"::text END;
