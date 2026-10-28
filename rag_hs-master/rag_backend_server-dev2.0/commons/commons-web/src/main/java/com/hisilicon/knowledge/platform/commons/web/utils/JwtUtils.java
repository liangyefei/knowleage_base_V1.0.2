package com.hisilicon.knowledge.platform.commons.web.utils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * JWT 工具类 — 无状态工具方法，用于从 JWT 字符串中提取 payload 字段。
 * <p>
 * 不依赖任何 Quarkus / SmallRye JWT 运行时，仅做字符串级解析，
 * 适用于需要从 <em>已签发</em> 的 token 中快速读取 sub/jti 等字段的场景。
 * </p>
 */
public final class JwtUtils {

    private JwtUtils() {
        throw new IllegalStateException("工具类不允许创建实例");
    }

    /**
     * 从 JWT 中提取 subject（sub 字段）。
     *
     * @param jwt JWT 字符串（三段式）
     * @return Optional 包装的 sub 值，解析失败或字段不存在时为空
     */
    public static Optional<String> extractSubject(String jwt) {
        return parsePayload(jwt)
                .map(payload -> extractJsonValue(payload, "sub"))
                .filter(s -> !s.isBlank());
    }

    /**
     * 从 JWT 中提取任意字符串字段。
     *
     * @param jwt JWT 字符串
     * @param key JSON key
     * @return Optional 包装的字段值
     */
    public static Optional<String> extractClaim(String jwt, String key) {
        return parsePayload(jwt)
                .map(payload -> extractJsonValue(payload, key))
                .filter(s -> !s.isBlank());
    }

    /**
     * 提取 JWT 的过期时间（exp 字段，秒级时间戳）。
     *
     * @param jwt JWT 字符串
     * @return OptionalLong 包装的 exp 值，解析失败或字段缺失时为空
     */
    public static OptionalLong extractExpiry(String jwt) {
        return extractNumericClaim(jwt, "exp");
    }

    /**
     * 提取 JWT 中的数字型字段（如 exp、iat 等秒级时间戳）。
     *
     * @param jwt JWT 字符串
     * @param key JSON key
     * @return OptionalLong 包装的数字值，解析失败或字段缺失时为空
     */
    public static OptionalLong extractNumericClaim(String jwt, String key) {
        Optional<String> payload = parsePayload(jwt);
        return payload.isPresent()
                ? extractNumericJsonValue(payload.get(), key)
                : OptionalLong.empty();
    }

    /**
     * 解析 JWT payload 为 JSON 字符串。
     */
    public static Optional<String> parsePayload(String jwt) {
        if (jwt == null || jwt.isBlank()) {
            return Optional.empty();
        }
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return Optional.empty();
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            return Optional.of(new String(decoded, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 从 JSON 字符串中提取指定 key 的字符串值（仅支持字符串类型值）。
     */
    private static String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return "";
        start += search.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : "";
    }

    /**
     * 从 JSON 字符串中提取指定 key 的数字值（仅支持整型数字）。
     */
    private static OptionalLong extractNumericJsonValue(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return OptionalLong.empty();
        start += search.length();
        // 跳过空白
        while (start < json.length()
                && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) {
            start++;
        }
        int end = start;
        while (end < json.length()
                && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end == start) return OptionalLong.empty();
        try {
            return OptionalLong.of(Long.parseLong(json.substring(start, end)));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
