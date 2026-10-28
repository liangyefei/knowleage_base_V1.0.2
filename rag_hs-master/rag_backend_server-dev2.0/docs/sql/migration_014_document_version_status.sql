-- migration_014: document_versions 增加版本行状态列（版本级归档/废弃）
-- 背景：版本覆盖问题终版方案——归档/废弃支持版本级操作（可选 version 参数），
--       文档生命周期保持 documents 单行状态机不变；下线版本（status != 'ACTIVE'）
--       在 download/detail 返回 A0508「文档已归档/已废弃」，问答解析兜底返回
--       versionStatus 供前端提示，模型按事件 payload 的 filePath 移除向量化数据。
-- 执行方式：psql 连接业务库后直接执行（幂等可重复执行）。

ALTER TABLE document_versions
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

COMMENT ON COLUMN document_versions.status IS '版本行状态：ACTIVE-在线；ARCHIVED-已归档（版本级下线）；DEPRECATED-已废弃（版本覆盖新归档恒为 ACTIVE）';
