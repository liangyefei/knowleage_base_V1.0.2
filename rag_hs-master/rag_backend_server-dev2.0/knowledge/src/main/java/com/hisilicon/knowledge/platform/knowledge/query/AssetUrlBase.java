package com.hisilicon.knowledge.platform.knowledge.query;

/**
 * 资产 URL 前缀组合（{@link MarkdownImageRewriter#buildAssetUrl} /
 * {@link MarkdownImageRewriter#buildChatAssetUrl} 的前缀入参打包）。
 *
 * @param urlPrefix 跨域/独立域名部署时可配置绝对前缀（如 {@code https://api.example.com}），默认空
 * @param rootPath  应用根路径（生产恒为 {@code /hisilicon-knowledge}），默认空 = 无前缀。
 *                  必须携带：否则浏览器请求 {@code /kb/documents/...} 会漏掉根路径一级而 404
 */
record AssetUrlBase(String urlPrefix, String rootPath) {
}
