/**
 * OIDC / 华为账号（OneAccess）对接的通用组件。
 *
 * <p>定位：<b>两侧共用</b>的鉴权构件，不承载业务。</p>
 * <ul>
 *   <li><b>资源服务器侧</b>（{@code hisilicon-knowledge-core-service} 等）：
 *       {@link com.hisilicon.knowledge.platform.commons.oidc.PlatformJwtVerifier} 以配置的 RSA 公钥
 *       （{@code rsa.public-key}）本地验签平台 JWT（RS256 + exp + 自定义 claim {@code type}），
 *       取代 commons-web AuthenticationFilter 当前"只解析不验签"的过渡行为。</li>
 *   <li><b>身份提供方对接侧</b>（{@code hisilicon-knowledge-iam-service}）：
 *       {@link com.hisilicon.knowledge.platform.commons.oidc.ExternalAccountValidator} 端口与
 *       {@link com.hisilicon.knowledge.platform.commons.oidc.OneAccessAccountValidator} 实现
 *       （调 OneAccess/华为账号 userinfo 校验 accessToken，fail-closed），多 IdP 可扩展；
 *       身份映射与首次登录建号归 system / iam-service，不在本模块。</li>
 * </ul>
 *
 * <p>claim 名以 {@link com.hisilicon.knowledge.platform.commons.oidc.OidcClaims} 常量为准，
 * 与 OneAccess 门户「映射配置」字段一一对应；外部唯一绑定键为 {@code sub}。</p>
 *
 * <p>纯库模块：不应用 io.quarkus 插件，靠 {@code META-INF/beans.xml} 声明为 bean archive
 * 被依赖方服务索引；HTTP 拦截器仍归 {@code commons-web}，本模块只提供可被其调用的校验能力。</p>
 *
 * @since 拆分后新增
 */
package com.hisilicon.knowledge.platform.commons.oidc;
