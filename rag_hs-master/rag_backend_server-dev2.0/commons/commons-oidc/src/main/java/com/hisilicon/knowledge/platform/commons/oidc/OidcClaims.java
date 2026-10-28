package com.hisilicon.knowledge.platform.commons.oidc;

/**
 * OneAccess / OIDC 标准 claim 名常量。
 *
 * <p>这些字段名与 OneAccess 门户「映射配置」中可配置的应用字段一一对应：
 * 在门户侧为应用配置映射时，请保持目标字段名与本类常量一致，
 * 用户信息接口（userinfo）返回的 JSON 与平台签发的 JWT（若是 OIDC 标准令牌）均按此命名。</p>
 *
 * <p>注意：{@code preferred_username} 是 OIDC 标准字段，OneAccess 门户映射配置中
 * 对应「登录名/工号」；{@code sub} 为全局唯一主体标识，作为 {@link
 * ExternalAccount#externalId()} 与平台用户绑定的唯一依据，禁止使用可变字段
 * （如邮箱、手机号）做绑定键。</p>
 *
 * <p>不可实例化的纯常量类。</p>
 */
public final class OidcClaims {

    /** OIDC 标准主体标识：全局唯一、不可变，外部账号与平台用户绑定的唯一键 */
    public static final String SUB = "sub";

    /** 登录名（OneAccess 门户映射配置中对应「登录名/工号」） */
    public static final String PREFERRED_USERNAME = "preferred_username";

    /** 显示名（门户映射配置中对应「姓名」） */
    public static final String NAME = "name";

    /** 邮箱 */
    public static final String EMAIL = "email";

    /** 手机号 */
    public static final String PHONE = "phone";

    /** 头像 URL */
    public static final String PICTURE = "picture";

    // ======================== OneAccess userinfo 专有字段名 ========================
    //
    // ⚠️ OneAccess 的「获取用户信息」接口（GET /api/v1/oauth2/userinfo）**不是**标准 OIDC 命名：
    // 官方文档（api-oneaccess / ShowUser_0）给出的返回字段是 id / userName / name / email / mobile。
    // 因此解析 userinfo 响应时不能只看上面的标准 claim，必须兼容这组名字——
    // 否则取不到唯一标识，外部账号复核会误判"账号已失效"（fail-closed 拒绝续期）。
    //
    // 注意区分：quarkus-oidc 验签后的 **ID Token** 仍是标准 OIDC claim（sub 有效），
    // 登录/建号走的是 ID Token，不受这里影响。

    /** OneAccess userinfo 的用户 ID（对应标准 OIDC 的 {@link #SUB}） */
    public static final String ONEACCESS_USER_ID = "id";

    /** OneAccess userinfo 的登录名（对应标准 OIDC 的 {@link #PREFERRED_USERNAME}） */
    public static final String ONEACCESS_USER_NAME = "userName";

    /** OneAccess userinfo 的手机号（对应标准 OIDC 的 {@link #PHONE}） */
    public static final String ONEACCESS_MOBILE = "mobile";

    private OidcClaims() {
    }
}
