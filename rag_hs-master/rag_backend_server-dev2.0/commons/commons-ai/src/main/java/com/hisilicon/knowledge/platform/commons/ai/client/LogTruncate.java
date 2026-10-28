package com.hisilicon.knowledge.platform.commons.ai.client;

/**
 * 日志截断工具：防止超大请求/响应（如 get_doc_by_filename 切片全文、KG 子图、SSE 拼接结果）撑爆日志。
 *
 * <p>调用模型接口处统一用本类截断出参：参数与响应均经 {@link #truncate(Object, int)} 收口，
 * 超长时保留前缀并标注总长，便于排查又不刷屏。</p>
 */
final class LogTruncate {

    /** 默认截断上限（字符），覆盖请求参数与常规响应。 */
    static final int DEFAULT_MAX = 2000;

    private LogTruncate() {
    }

    /**
     * 截断为字符串，超 {@code max} 字符时保留前缀并标注总长。
     */
    static String truncate(Object value) {
        return truncate(value, DEFAULT_MAX);
    }

    /**
     * 截断为字符串，超 {@code max} 字符时保留前缀并标注总长。
     */
    static String truncate(Object value, int max) {
        String s = String.valueOf(value);
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...(截断，共 " + s.length() + " 字符)";
    }
}