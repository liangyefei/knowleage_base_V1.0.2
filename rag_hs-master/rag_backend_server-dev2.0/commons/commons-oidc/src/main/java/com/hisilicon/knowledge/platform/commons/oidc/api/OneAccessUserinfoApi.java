package com.hisilicon.knowledge.platform.commons.oidc.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/**
 * OneAccess / 华为账号用户信息接口 REST Client（OIDC userinfo）。
 *
 * <p>baseUri 为完整的 userinfo 端点（配置键 {@code oidc.provider.huawei.userinfo-uri}），
 * 由 {@code OneAccessAccountValidator} 经 {@code RestClientBuilder} 在运行期按配置构建，
 * 因此本接口不标注 {@code @RegisterRestClient}（无静态 baseUri 可绑定）。</p>
 */
@Consumes(MediaType.APPLICATION_JSON)
public interface OneAccessUserinfoApi {

    /**
     * 拉取当前令牌对应用户的身份信息。
     *
     * @param authorization {@code Bearer <accessToken>} 形式的鉴权头
     * @return userinfo JSON（claim 名见 {@code OidcClaims}）
     */
    @GET
    Map<String, Object> userinfo(@HeaderParam("Authorization") String authorization);
}
