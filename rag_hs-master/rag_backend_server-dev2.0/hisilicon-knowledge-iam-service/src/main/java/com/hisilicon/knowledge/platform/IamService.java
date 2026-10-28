package com.hisilicon.knowledge.platform;

/**
 * hisilicon-knowledge-iam-service 启动标记类。
 *
 * <p>自身不含业务逻辑，仅作为 Quarkus 应用归档的锚点。本模块只聚合 {@code system}
 * （身份与访问域：用户/角色/权限/登录/令牌），并提供 RSA 密钥对（{@code privateKey.pem}
 * 只应存在于本服务）。</p>
 *
 * <p>后续华为账号（OIDC RP）对接也落在此服务：它需要 {@code rsa.private-key} 做令牌签名，
 * 且是本平台用户表的唯一写入方。共享的 OIDC 资源服务器组件放 {@code commons-oidc}。</p>
 */
public class IamService {
}
