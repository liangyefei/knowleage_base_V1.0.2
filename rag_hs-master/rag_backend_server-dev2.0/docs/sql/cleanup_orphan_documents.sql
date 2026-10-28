-- =====================================================================
-- 孤儿文档排查与清理（历史存量数据一次性处置脚本）
-- =====================================================================
-- 背景：2026-08 前「删除知识库/类别」不级联处理文档，遗留 categoryId 指向已软删类别
--（或所属知识库已软删）的孤儿文档。这类文档在管理页树/默认列表中不可见，但
-- /kb/documents/search（整库或按已删 categoryId）仍可查到。
-- 代码修复后（删除时阻塞校验 + 级联物理删除 DRAFT/DEPRECATED），不会再产生新孤儿；
-- 本脚本用于清理测试/生产环境的历史存量。
--
-- 执行方式：psql 连接业务库后先 SET search_path TO knowledge;（或逐表加 schema 前缀）。
-- 建议先在事务中执行排查语句确认清单，再按步骤 2/3 处置。
-- =====================================================================

SET search_path TO knowledge;

-- ---------------------------------------------------------------------
-- 1) 排查：孤儿文档清单（所属类别已删，或所属知识库已删）
-- ---------------------------------------------------------------------
-- 注：扫描规则「查询语句禁止使用OR」——孤儿判定改写为两个条件分支 UNION，
-- 分支内用 COALESCE(c/k.status, 0) = 0 等价替换 (id IS NULL OR status = 0)。
SELECT d.id, d.kb_id, d.category_id, d.title, d.status, d.doc_type,
       c.status AS category_status, k.status AS kb_status
FROM documents d
LEFT JOIN categories c ON c.id = d.category_id
LEFT JOIN knowledge_bases k ON k.id = d.kb_id
WHERE d.category_id IS NOT NULL AND COALESCE(c.status, 0) = 0
UNION
SELECT d.id, d.kb_id, d.category_id, d.title, d.status, d.doc_type,
       NULL AS category_status, k.status AS kb_status
FROM documents d
LEFT JOIN knowledge_bases k ON k.id = d.kb_id
WHERE COALESCE(k.status, 0) = 0
ORDER BY 2, 5, 4;

-- ---------------------------------------------------------------------
-- 2) 清理：DRAFT / DEPRECATED 孤儿 → 物理删除（连同子表）
--    DRAFT 未经过模型处理（无向量）；DEPRECATED 的向量已在废弃时经
--    DOCUMENT_DELETED 事件清理。文件本体（documents.file_path 与
--    document_versions.file_path）按需从对象存储/SFS 移入回收站，脚本不处理。
--    注意：孤儿判定整体加括号后再 AND 状态过滤（AND 优先级高于 OR）。
-- ---------------------------------------------------------------------
WITH orphan_docs AS (
    SELECT d.id, d.status
    FROM documents d
    LEFT JOIN categories c ON c.id = d.category_id
    WHERE d.category_id IS NOT NULL AND COALESCE(c.status, 0) = 0
    UNION
    SELECT d.id, d.status
    FROM documents d
    LEFT JOIN knowledge_bases k ON k.id = d.kb_id
    WHERE COALESCE(k.status, 0) = 0
)
DELETE FROM document_metadata m
USING orphan_docs o
WHERE m.document_id = o.id AND o.status IN ('DRAFT', 'DEPRECATED');

WITH orphan_docs AS (
    SELECT d.id, d.status
    FROM documents d
    LEFT JOIN categories c ON c.id = d.category_id
    WHERE d.category_id IS NOT NULL AND COALESCE(c.status, 0) = 0
    UNION
    SELECT d.id, d.status
    FROM documents d
    LEFT JOIN knowledge_bases k ON k.id = d.kb_id
    WHERE COALESCE(k.status, 0) = 0
)
DELETE FROM document_versions v
USING orphan_docs o
WHERE v.document_id = o.id AND o.status IN ('DRAFT', 'DEPRECATED');

WITH orphan_docs AS (
    SELECT d.id, d.status
    FROM documents d
    LEFT JOIN categories c ON c.id = d.category_id
    WHERE d.category_id IS NOT NULL AND COALESCE(c.status, 0) = 0
    UNION
    SELECT d.id, d.status
    FROM documents d
    LEFT JOIN knowledge_bases k ON k.id = d.kb_id
    WHERE COALESCE(k.status, 0) = 0
)
DELETE FROM documents d
USING orphan_docs o
WHERE d.id = o.id AND o.status IN ('DRAFT', 'DEPRECATED');

-- 说明：review_history 表注释为「记录不可删除、不可修改」，作为审计保留不随删；
-- 如需彻底清理可参照上述 CTE 自行 DELETE。

-- ---------------------------------------------------------------------
-- 3) REVIEWING / PUBLISHED / ARCHIVED 孤儿：不自动删除，先人工决策
--    建议处置顺序：REVIEWING → 撤回（转 DRAFT）后按 2) 清理；
--                 PUBLISHED / ARCHIVED → 废弃（DEPRECATED）后按 2) 清理。
--    决策前先执行步骤 1) 的清单查询确认影响范围。
-- ---------------------------------------------------------------------
