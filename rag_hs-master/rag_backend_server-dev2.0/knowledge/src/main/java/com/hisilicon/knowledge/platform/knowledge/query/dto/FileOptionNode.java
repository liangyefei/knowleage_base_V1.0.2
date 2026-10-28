package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.util.List;

/**
 * 代码↔文档映射功能：按方向搜索文件的树形下拉节点
 * <p>GET /kb/documents/file-options 返回单根树：nodeType=KB 时根为知识库节点，
 * nodeType=CATEGORY 时根为该类别节点；类别节点 children 递归挂载（文档为叶子，
 * 空分支已裁剪）；keyword 非空时仅保留命中文档及其祖先类别链（命中按文档标题
 * 或存储文件名模糊、大小写不敏感）。</p>
 *
 * @param id             节点 ID（KB ID / 类别 ID / 文档 ID）
 * @param title          展示名（KB 名 / 类别名 / 文档标题）
 * @param nodeType       节点类型：KB / CATEGORY / DOCUMENT（DOCUMENT=可选文件叶子）
 * @param docType        文档类型（仅 DOCUMENT 叶子携带：direction=doc2code→DOCUMENT、
 *                       code2doc→CODE；KB/CATEGORY 节点为 null）
 * @param currentVersion 当前版本号（仅 DOCUMENT 叶子携带，供前端展示；其余为 null）
 * @param fileName       存储文件名（仅 DOCUMENT 叶子携带，取自 file_path 末段，
 *                       如 {@code test.txt}；其余为 null）
 * @param fileType       文件扩展名（仅 DOCUMENT 叶子携带，小写，如 {@code txt}；
 *                       无扩展名为 null；其余为 null）
 * @param children       子节点（叶子为空列表）
 */
public record FileOptionNode(String id, String title, String nodeType,
                             String docType, String currentVersion,
                             String fileName, String fileType,
                             List<FileOptionNode> children) {

    /** 根/类别节点类型（与 {@link NavTreeItem#TYPE_KB}/{@link NavTreeItem#TYPE_CATEGORY} 同值） */
    public static final String NODE_DOCUMENT = "DOCUMENT";
}
