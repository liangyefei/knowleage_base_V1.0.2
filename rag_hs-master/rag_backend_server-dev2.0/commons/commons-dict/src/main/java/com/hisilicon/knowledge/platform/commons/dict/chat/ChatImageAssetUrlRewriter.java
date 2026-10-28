package com.hisilicon.knowledge.platform.commons.dict.chat;

import io.smallrye.mutiny.Uni;

/**
 * 对话图片引用网络地址化端口（跨模块解耦：knowledge 提供 impl，llm 注入）。
 *
 * <p>模型回答（流式 answer 分片与历史 ASSISTANT 消息）中的 markdown 图片引用形如
 * {@code ![alt](/kb-files/{documents.file_path})}——{@code /kb-files} 是模型服务对共享存储卷的
 * 挂载视图（对应平台 {@code documents.file_path}），平台没有任何 HTTP 路由对外提供该前缀，
 * 浏览器直接取图必然失败。本端口把文本中<b>完整</b>的此类引用改写为平台图片资产签名 URL
 * （{@code {前缀}/kb/documents/{图片所属文档id}/assets?path=…&expires=…&sig=…}，
 * 免登录、短时效，与 markdown 下载在线查看共用同一资产接口 {@code DocumentAssetResource}）。</p>
 *
 * <p>放于 commons-dict（同 {@link ChatDocsSourceResolver}，避免 llm 编译依赖 knowledge）。
 * 运行期由 service 聚合注入 knowledge 的实现。</p>
 *
 * <p>降级语义（整体不抛异常、不阻断对话）：</p>
 * <ul>
 *   <li>引用非 {@code /kb-files/} 前缀（相对引用/http 外链/锚点等）、扩展名不在图片白名单
 *       （png/jpg/jpeg/bmp）、按路径查不到所属文档、签名密钥未配置或改写内部失败 →
 *       该引用一律<b>原样保留</b>，文本其余部分不变；</li>
 *   <li>调用方须保证输入中的每个图片引用是<b>完整</b>的（含收尾右括号）——不完整的引用
 *       不构成匹配、自然原样保留；流式场景由 llm 侧的跨分片改写器保证只在"安全段"上调用。</li>
 * </ul>
 */
public interface ChatImageAssetUrlRewriter {

    /**
     * 改写文本中的 markdown 图片引用（{@code ![alt](/kb-files/…)）为签名资产 URL。
     *
     * <p>仅改写 {@code ![alt](ref)} 形态的 md 图片语法（与 markdown 下载重写器的 md 语法
     * 口径一致）；HTML {@code <img>} 标签不在范围。同一路径多次出现只查一次库、分别替换；
     * 替换值（签名 URL）仅含百分号编码 ASCII 字符，可安全嵌入 JSON 字符串值内
     * （模型 SSE 分片即为 JSON 文本）而无需转义。</p>
     *
     * @param text 任意文本（流式安全段或历史消息全文）；null 按空串处理
     * @return 改写后的文本；无可改写引用或整体降级时内容与输入一致
     */
    Uni<String> rewrite(String text);
}
