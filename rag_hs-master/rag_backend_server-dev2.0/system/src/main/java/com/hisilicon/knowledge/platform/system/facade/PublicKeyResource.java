package com.hisilicon.knowledge.platform.system.facade;

import com.hisilicon.knowledge.platform.commons.web.utils.PemRsaUtils;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * 公钥获取接口。
 * <p>返回 RSA 公钥 PEM 内容，供前端加密敏感信息（如密码）。
 * 不包装 {@code ResponseResult}，直接返回纯文本。</p>
 *
 * <p>本接口优先从 Quarkus 配置键 {@code rsa.public-key} 读取公钥（生产环境通过 K8s ConfigMap 注入），
 * 未配置时自动回退到 classpath 根目录的 {@code publicKey.pem}（本地开发）。</p>
 */
@Path("/publicKey")
public class PublicKeyResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getPublicKey() {
        return PemRsaUtils.getPublicKeyContent();
    }
}
