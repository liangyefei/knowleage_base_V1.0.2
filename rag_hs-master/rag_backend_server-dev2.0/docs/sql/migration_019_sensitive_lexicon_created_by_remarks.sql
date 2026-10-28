-- ----------------------------
-- 敏感词词库增加 创建人 / 备注 列
-- created_by：创建人账号，取 JWT sub（OperatorIdHolder），仅新增/导入时写入，编辑不变更
-- remarks：备注，可空，编辑接口全量替换（传 null 清空）；规格对齐 kb_sensitive_tags.remarks
-- ----------------------------
ALTER TABLE "kb_sensitive_lexicons" ADD COLUMN IF NOT EXISTS "created_by" VARCHAR(128);
ALTER TABLE "kb_sensitive_lexicons" ADD COLUMN IF NOT EXISTS "remarks"    VARCHAR(512);

COMMENT ON COLUMN "kb_sensitive_lexicons"."created_by" IS '创建人账号，取 JWT sub（OperatorIdHolder），前端不传；存量行为 NULL';
COMMENT ON COLUMN "kb_sensitive_lexicons"."remarks"    IS '备注，可空；编辑接口全量替换';
