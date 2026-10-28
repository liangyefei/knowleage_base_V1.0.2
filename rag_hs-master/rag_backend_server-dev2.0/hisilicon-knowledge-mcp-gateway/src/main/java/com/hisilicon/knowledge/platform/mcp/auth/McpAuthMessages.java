package com.hisilicon.knowledge.platform.mcp.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 网关的本地化提示消息（{@code messages/mcp_zh_CN.properties} / {@code mcp_en_US.properties}）。
 *
 * <p><b>为什么不引 commons-i18n</b>：那个模块的 {@code @MessageBundle} 基于 Qute，会传递
 * {@code quarkus-rest}、{@code rest-qute}、{@code hibernate-validator}、{@code mapstruct}，
 * 与网关「依赖最小化」口径直接冲突（同 {@code commons-web} 的理由，见模块 AGENTS.md）。
 * 网关只需要 9 条静态文案，用 JDK 的 {@link ResourceBundle} 足够，且不需要模板引擎。</p>
 *
 * <p><b>语言判定</b>：读 {@code Accept-Language}，取第一个 q 值大于 0 的语言标签，按主语言
 * 映射到平台支持的两种资源：{@code zh*} → {@code zh_CN}、{@code en*} → {@code en_US}；
 * 其他语言（MCP 客户端常常什么都不发）回落中文。语言标签不做细粒度匹配 ——
 * 网关的文案面向最终用户，{@code zh-Hans-CN} 与 {@code zh-TW} 用同一份即可。</p>
 *
 * <p><b>native 提示</b>：生产用 native 打包，{@code .properties} 需要显式登记，
 * 见 {@code application.yml} 的 {@code quarkus.native.resources.includes}。</p>
 *
 * @author hisilicon
 */
public final class McpAuthMessages {

    private static final Logger log = LoggerFactory.getLogger(McpAuthMessages.class);

    private static final String BUNDLE_BASE = "messages.mcp";
    private static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;
    private static final Map<Locale, ResourceBundle> CACHE = new ConcurrentHashMap<>();

    private McpAuthMessages() {
    }

    /**
     * 取本地化消息。
     *
     * @param key           资源键
     * @param acceptLanguage 请求头 {@code Accept-Language}（可为 null）
     * @param args           {@link MessageFormat} 占位参数，可空
     * @return 本地化后的文案；资源缺失时返回 key 本身，绝不抛异常到请求链路
     */
    public static String get(String key, String acceptLanguage, Object... args) {
        ResourceBundle bundle = bundle(resolveLocale(acceptLanguage));
        String pattern;
        try {
            pattern = bundle.getString(key);
        } catch (MissingResourceException e) {
            log.error("Missing MCP message key '{}' in bundle '{}'", key, BUNDLE_BASE);
            return key;
        }
        if (args == null || args.length == 0) {
            return pattern;
        }
        return MessageFormat.format(pattern, args);
    }

    /**
     * 解析 {@code Accept-Language} 为受支持的 Locale。
     *
     * @param acceptLanguage 请求头值（形如 {@code en-US,en;q=0.9}）
     * @return 受支持 Locale，无法识别时回落中文
     */
    static Locale resolveLocale(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return DEFAULT_LOCALE;
        }
        List<String> tags = List.of(acceptLanguage.split(","));
        for (String raw : tags) {
            String tag = raw.trim();
            if (tag.isEmpty()) {
                continue;
            }
            int qIndex = tag.indexOf(';');
            if (qIndex >= 0) {
                String quality = tag.substring(qIndex + 1).trim();
                if (quality.startsWith("q=") && isRejected(quality.substring(2))) {
                    continue;
                }
                tag = tag.substring(0, qIndex).trim();
            }
            Locale mapped = mapLanguage(tag);
            if (mapped != null) {
                return mapped;
            }
        }
        return DEFAULT_LOCALE;
    }

    private static boolean isRejected(String qValue) {
        try {
            return Double.parseDouble(qValue.trim()) <= 0d;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Locale mapLanguage(String tag) {
        int dash = tag.indexOf('-');
        String language = (dash > 0 ? tag.substring(0, dash) : tag).toLowerCase(Locale.ROOT);
        return switch (language) {
            case "zh" -> Locale.SIMPLIFIED_CHINESE;
            case "en" -> Locale.US;
            default -> null;
        };
    }

    private static ResourceBundle bundle(Locale locale) {
        return CACHE.computeIfAbsent(locale, key -> {
            try {
                ResourceBundle loaded = ResourceBundle.getBundle(BUNDLE_BASE, key);
                log.debug("Loaded MCP message bundle for locale {}", key);
                return loaded;
            } catch (MissingResourceException e) {
                log.error("MCP message bundle '{}' not found for locale {}", BUNDLE_BASE, key);
                return ResourceBundle.getBundle(BUNDLE_BASE, DEFAULT_LOCALE);
            }
        });
    }
}