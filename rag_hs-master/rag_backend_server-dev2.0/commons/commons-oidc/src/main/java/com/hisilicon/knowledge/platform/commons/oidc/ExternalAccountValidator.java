package com.hisilicon.knowledge.platform.commons.oidc;

/**
 * 外部账号校验端口：校验外部身份提供方（OneAccess / 华为账号等）的访问令牌当前仍有效，
 * 并返回其身份信息。
 *
 * <p>语义为 <b>fail-closed</b>：</p>
 * <ul>
 *   <li>令牌有效 → 返回 {@link ExternalAccount}；</li>
 *   <li>令牌无效 / 账号已失效 → 抛 {@code BusinessException}
 *       （错误码 {@code A0224 外部账号已失效}）；</li>
 *   <li>提供方服务不可用 / 重试后仍失败 → 抛 {@code BusinessException}
 *       （错误码 {@code C0114 身份提供方服务调用失败}）。</li>
 * </ul>
 *
 * <p>多 IdP 可扩展：每个提供方一个实现，{@link #supports(String)} 按提供方标识路由。</p>
 */
public interface ExternalAccountValidator {

    /**
     * 是否支持指定提供方。
     *
     * @param provider 提供方标识（如 {@code "huawei"} / {@code "oneaccess"}）
     * @return 支持返回 {@code true}
     */
    boolean supports(String provider);

    /**
     * 校验外部账号当前仍有效并返回其身份信息（fail-closed，无效时抛业务异常）。
     *
     * @param provider              提供方标识
     * @param accessTokenOrIdToken  外部访问令牌（access token，或用户信息接口接受的 id token）
     * @return 外部账号身份信息
     */
    ExternalAccount validate(String provider, String accessTokenOrIdToken);
}
