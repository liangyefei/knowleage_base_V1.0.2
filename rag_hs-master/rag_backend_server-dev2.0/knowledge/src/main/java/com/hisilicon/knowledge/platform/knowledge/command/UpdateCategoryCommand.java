package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.i18n.validation.SizeWithName;
import jakarta.validation.constraints.NotBlank;

/**
 * 编辑类别基本信息命令（全量替换语义）。
 * <p>不涉及知识库 / 父类别变更（类别移动由专门接口处理）。</p>
 *
 * <h3>空值处理（全量替换规则）</h3>
 * <table>
 *   <tr><th>字段</th><th>传了有效值</th><th>为 {@code null}</th></tr>
 *   <tr><td>{@code categoryName}</td><td>替换为传入值</td><td>校验拦截（必填）</td></tr>
 *   <tr><td>{@code sortOrder}</td><td>替换为传入值</td><td><b>重置为 0</b></td></tr>
 *   <tr><td>{@code inheritParent}</td><td>替换为传入值</td><td><b>重置为 {@code true}</b></td></tr>
 * </table>
 *
 * <p>即：不想改排序号就不要传 {@code editCategory}，前端需把当前值回填；本命令只负责全量接收并替换。</p>
 *
 * @param categoryId    类别 ID（必填）
 * @param categoryName  类别名称（必填，1–50 字符）
 * @param sortOrder     排序号（可选；{@code null} 时重置为 0）
 * @param inheritParent 是否继承父级 ACL（可选；{@code null} 时重置为 {@code true}）
 */
public record UpdateCategoryCommand(
        @NotBlank(message = "{app.category.id.notblank}")
        String categoryId,

        @NotBlank(message = "{app.category.name.notblank}")
        @SizeWithName(fieldName = "categoryName", min = 1, max = 50)
        String categoryName,

        /** 排序号；{@code null} 时重置为 0 */
        Integer sortOrder,

        /** 是否继承父级 ACL；{@code null} 时重置为 {@code true} */
        Boolean inheritParent) {
}
