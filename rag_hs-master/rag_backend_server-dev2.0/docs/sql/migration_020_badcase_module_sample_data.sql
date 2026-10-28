-- =====================================================================
-- Badcase 模块示例数据（与 migration_020_badcase_module.sql 配套�?-- 场景�? 种来源各 1 条工单，覆盖不同状态（待处�?/ 处理�?/ 已处�?/ 不予处理），
--      其中 1 条有处理历史记录�?-- 执行前提：knowledge_bases / documents 已存在对�?id（下面的示例 id �?--   演示用占位，实际按环境替换）。可重复执行（先 DELETE 示例 id �?INSERT）�?-- =====================================================================

-- ---------------------------------------------------------------------
-- 0. 清理旧示例数据（按示�?id 精准删除，避免污染）
-- ---------------------------------------------------------------------
DELETE FROM "badcase_handlings"
 WHERE "ticket_id" IN ('BC-20261020-0001','BC-20261020-0002','BC-20261020-0003','BC-20261020-0004');

DELETE FROM "badcase_tickets"
 WHERE "id" IN ('bc-qa-page-001','bc-mcp-002','bc-conflict-003','bc-slice-004');

-- ---------------------------------------------------------------------
-- 1. 页面问答来源（PAGE_QA）：待处理状态，无处理记�?-- ---------------------------------------------------------------------
INSERT INTO "badcase_tickets" (
    "id", "ticket_no", "source", "problem_type", "description", "status",
    "kb_id", "kb_name_snapshot", "related_doc_ids", "related_doc_names",
    "source_payload",
    "latest_handling_remark", "latest_handled_by", "latest_handled_by_name", "latest_handled_at",
    "submitted_by", "submitted_by_name", "submitted_at", "created_at", "updated_at"
) VALUES (
    'bc-qa-page-001', 'BC-20261020-0001', 'PAGE_QA', 'WRONG_ANSWER',
    '回答�?UART 波特率上限参数错误，用户手册�?921600bps，回答却�?3Mbps�?,
    'PENDING',
    'kb-diting', '谛听知识�?,
    '["doc-user-manual-v21"]',
    '["谛听用户手册 v2.1.pdf"]',
    '{"question":"UART 最高波特率是多少？","answer":"UART 最高支�?3Mbps"}',
    NULL, NULL, NULL, NULL,
    'zhangsan', '张三', '2026-10-20 09:15:00', '2026-10-20 09:15:00', '2026-10-20 09:15:00'
);

-- ---------------------------------------------------------------------
-- 2. MCP 调用问答来源（MCP_QA）：处理中状态，无处理记�?-- ---------------------------------------------------------------------
INSERT INTO "badcase_tickets" (
    "id", "ticket_no", "source", "problem_type", "description", "status",
    "kb_id", "kb_name_snapshot", "related_doc_ids", "related_doc_names",
    "source_payload",
    "latest_handling_remark", "latest_handled_by", "latest_handled_by_name", "latest_handled_at",
    "submitted_by", "submitted_by_name", "submitted_at", "created_at", "updated_at"
) VALUES (
    'bc-mcp-002', 'BC-20261020-0002', 'MCP_QA', 'IRRELEVANT_ANSWER',
    '调用 MCP 工具返回的结果与用户提问完全无关，答非所问�?,
    'PROCESSING',
    'kb-diting', '谛听知识�?,
    NULL, NULL,
    '{"question":"QSPI 支持的最高时钟频率？","answer":"当前支持 I2C/SPI/UART 三种协议...","mcpTool":"doc_retrieve"}',
    '已定位为召回策略问题，正在调整权重�?, 'lisi', '李四', '2026-10-20 10:30:00',
    'wangwu', '王五', '2026-10-20 10:00:00', '2026-10-20 10:00:00', '2026-10-20 10:30:00'
);

-- 2.1 对应处理记录（MCP 那条：待处理 �?处理中）
INSERT INTO "badcase_handlings" (
    "id", "ticket_id", "action", "remark", "handled_by", "handled_by_name", "handled_at"
) VALUES (
    'bh-mcp-002-01', 'bc-mcp-002', 'PROCESSING',
    '已定位为召回策略问题，正在调整权重�?,
    'lisi', '李四', '2026-10-20 10:30:00'
);

-- ---------------------------------------------------------------------
-- 3. 冲突检测来源（CONFLICT_DETECTION）：已处理状态，有处理记�?-- ---------------------------------------------------------------------
INSERT INTO "badcase_tickets" (
    "id", "ticket_no", "source", "problem_type", "description", "status",
    "kb_id", "kb_name_snapshot", "related_doc_ids", "related_doc_names",
    "source_payload",
    "latest_handling_remark", "latest_handled_by", "latest_handled_by_name", "latest_handled_at",
    "submitted_by", "submitted_by_name", "submitted_at", "created_at", "updated_at"
) VALUES (
    'bc-conflict-003', 'BC-20261020-0003', 'CONFLICT_DETECTION', 'FACT_CONFLICT',
    '冲突判定与实际情况不符，3Mbps 为早期工程样片参数，非现售规格�?,
    'RESOLVED',
    'kb-diting', '谛听知识�?,
    '["doc-user-manual-v21","doc-datasheet-v13"]',
    '["谛听用户手册 v2.1.pdf","谛听数据手册 v1.3.pdf"]',
    '{
        "conflictTopic": "UART 波特率上限参数不一�?,
        "evidence": [
            {"documentId":"doc-user-manual-v21","excerpt":"支持波特�?9600 ~ 921600 bps","isBaseline":true},
            {"documentId":"doc-datasheet-v13","excerpt":"UART 最高支�?3Mbps","isBaseline":false}
        ]
    }',
    '以用户手册为准，数据手册�?3Mbps 为早期工程样片参数，已在数据手册中补充样片说明�?,
    'lisi', '李四', '2026-10-20 14:20:00',
    'zhangsan', '张三', '2026-10-20 11:00:00', '2026-10-20 11:00:00', '2026-10-20 14:20:00'
);

-- 3.1 对应处理记录（冲突那条：待处�?�?已处理）
INSERT INTO "badcase_handlings" (
    "id", "ticket_id", "action", "remark", "handled_by", "handled_by_name", "handled_at"
) VALUES (
    'bh-conflict-003-01', 'bc-conflict-003', 'RESOLVED',
    '以用户手册为准，数据手册�?3Mbps 为早期工程样片参数，已在数据手册中补充样片说明�?,
    'lisi', '李四', '2026-10-20 14:20:00'
);

-- ---------------------------------------------------------------------
-- 4. 知识切片来源（KNOWLEDGE_SLICE）：不予处理状态，有处理记�?-- ---------------------------------------------------------------------
INSERT INTO "badcase_tickets" (
    "id", "ticket_no", "source", "problem_type", "description", "status",
    "kb_id", "kb_name_snapshot", "related_doc_ids", "related_doc_names",
    "source_payload",
    "latest_handling_remark", "latest_handled_by", "latest_handled_by_name", "latest_handled_at",
    "submitted_by", "submitted_by_name", "submitted_at", "created_at", "updated_at"
) VALUES (
    'bc-slice-004', 'BC-20261020-0004', 'KNOWLEDGE_SLICE', 'INACCURATE_SLICE',
    '切片切分位置不合理，把一个完整的寄存器说明切成了两段，导致检索时上下文不完整�?,
    'REJECTED',
    'kb-diting', '谛听知识�?,
    '["doc-register-ref-v10"]',
    '["谛听寄存器参考手�?v1.0.pdf"]',
    '{
        "sliceContent": "UART_IER 寄存器：偏移 0x04，复位�?0x00... 该寄存器用于使能接收中断，当 RXNE 置位时触�?,
        "documentId": "doc-register-ref-v10",
        "chunkIndex": 45
    }',
    '经确认该切片为历史版本遗留，当前 v2.0 已重新切分，无需处理�?,
    'wangwu', '王五', '2026-10-20 16:00:00',
    'zhangsan', '张三', '2026-10-20 15:00:00', '2026-10-20 15:00:00', '2026-10-20 16:00:00'
);

-- 4.1 对应处理记录（切片那条：待处�?�?不予处理�?INSERT INTO "badcase_handlings" (
    "id", "ticket_id", "action", "remark", "handled_by", "handled_by_name", "handled_at"
) VALUES (
    'bh-slice-004-01', 'bc-slice-004', 'REJECTED',
    '经确认该切片为历史版本遗留，当前 v2.0 已重新切分，无需处理�?,
    'wangwu', '王五', '2026-10-20 16:00:00'
);
