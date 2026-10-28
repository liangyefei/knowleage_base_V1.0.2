package com.hisilicon.knowledge.platform.commons.oidc;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.oidc.api.OneAccessUserinfoApi;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * OneAccess / 华为账号（{@code provider = "huawei"} / {@code "oneaccess"}）外部账号校验实现。
 *
 * <p>用 quarkus REST Client 调用用户信息接口（userinfo）校验 accessToken：
 * 端点从 MP Config 键 {@code oidc.provider.huawei.userinfo-uri} 读取（无默认值，未配置即不可用），
 * 连接/读超时各 5s；网络失败或 5xx 重试 1 次，仍失败 fail-closed 抛业务异常（C0114）。
 * HTTP 401/403 或响应缺少 {@code sub} 视为账号已失效，抛 A0224。</p>
 *
 * <p>RestClient 在构造期经 MP {@code RestClientBuilder} 构建一次；测试可走包私有构造
 * 注入替身，不打真实 HTTP。</p>
 */
@ApplicationScoped
@Unremovable
public class OneAccessAccountValidator implements ExternalAccountValidator {

    private static final Logger log = LoggerFactory.getLogger(OneAccessAccountValidator.class);

    /** 支持的提供方标识（OneAccess 与华为账号共用同一 userinfo 端点） */
    public static final Set<String> SUPPORTED_PROVIDERS = Set.of("huawei", "oneaccess");

    /** 失败重试 1 次（含首次共 2 次尝试） */
    private static final int MAX_ATTEMPTS = 2;

    private static final long TIMEOUT_SECONDS = 5L;

    /** userinfo 端点，未配置（空白）时校验请求直接 fail-closed */
    private final String userinfoUri;
    private final OneAccessUserinfoApi client;

    /**
     * CDI 构造函数：按配置的 userinfo 端点构建 REST Client。
     *
     * <p>类内还有一个包私有构造器（测试注入替身用），因此必须显式 {@code @Inject}
     * 告诉 ArC 用哪个构造器——否则 ArC 找不到无参构造器会静默跳过本 bean。</p>
     *
     * @param userinfoUri 完整 userinfo 端点（MP Config 键 {@code oidc.provider.huawei.userinfo-uri}）
     */
    @Inject
    public OneAccessAccountValidator(
            @ConfigProperty(name = "oidc.provider.huawei.userinfo-uri") Optional<String> userinfoUri) {
        this(userinfoUri.orElse(""), buildClient(userinfoUri.orElse("")));
    }

    /** 包私有：测试注入替身 client */
    OneAccessAccountValidator(String userinfoUri, OneAccessUserinfoApi client) {
        this.userinfoUri = userinfoUri;
        this.client = client;
    }

    static OneAccessUserinfoApi buildClient(String userinfoUri) {
        if (userinfoUri == null || userinfoUri.isBlank()) {
            // 延迟到真正调用时才报配置缺失，避免未启用 OIDC 的服务因缺配置而启动失败
            return null;
        }
        return RestClientBuilderFactory.create(URI.create(userinfoUri.trim()));
    }

    @Override
    public boolean supports(String provider) {
        return provider != null && SUPPORTED_PROVIDERS.contains(provider);
    }

    @Override
    public ExternalAccount validate(String provider, String accessTokenOrIdToken) {
        if (!supports(provider)) {
            throw new BusinessException(ErrorCodeEnum.SERVICE_ERROR_C0114,
                    "Unsupported external identity provider: " + provider);
        }
        if (accessTokenOrIdToken == null || accessTokenOrIdToken.isBlank()) {
            throw new BusinessException(ErrorCodeEnum.EXTERNAL_ACCOUNT_INVALID);
        }
        if (client == null) {
            log.error("Userinfo endpoint is not configured (oidc.provider.huawei.userinfo-uri), fail-closed");
            throw new BusinessException(ErrorCodeEnum.SERVICE_ERROR_C0114,
                    "Userinfo endpoint is not configured: oidc.provider.huawei.userinfo-uri");
        }
        Map<String, Object> claims = callUserinfoWithRetry(accessTokenOrIdToken.trim());
        return toExternalAccount(claims);
    }

    private Map<String, Object> callUserinfoWithRetry(String accessToken) {
        String bearer = "Bearer " + accessToken;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return client.userinfo(bearer);
            } catch (WebApplicationException e) {
                int status = e.getResponse() != null ? e.getResponse().getStatus() : 0;
                if (status == 401 || status == 403) {
                    // 令牌被明确拒绝：不重试，账号已失效
                    log.info("Userinfo endpoint rejected the token, url={}, status={}", userinfoUri, status);
                    throw new BusinessException(ErrorCodeEnum.EXTERNAL_ACCOUNT_INVALID);
                }
                if (attempt == MAX_ATTEMPTS) {
                    log.error("Userinfo endpoint returned error after retry, url={}, status={}", userinfoUri, status, e);
                    throw new BusinessException(ErrorCodeEnum.SERVICE_ERROR_C0114,
                            "Userinfo endpoint failed, http status " + status, e);
                }
                log.warn("Userinfo endpoint returned error, retrying, url={}, status={}, attempt={}",
                        userinfoUri, status, attempt);
            } catch (ProcessingException e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.error("Userinfo endpoint call failed after retry, url={}", userinfoUri, e);
                    throw new BusinessException(ErrorCodeEnum.SERVICE_ERROR_C0114,
                            "Userinfo endpoint call failed: " + e.getMessage(), e);
                }
                log.warn("Userinfo endpoint call failed, retrying, url={}, attempt={}", userinfoUri, attempt, e);
            }
        }
        // 循环在 attempt == MAX_ATTEMPTS 分支内必然抛出；收尾兜底满足 G.CTL.02
        throw new BusinessException(ErrorCodeEnum.SERVICE_ERROR_C0114);
    }

    private ExternalAccount toExternalAccount(Map<String, Object> claims) {
        // OneAccess 的 userinfo 用非标准命名（id / userName / mobile），标准 OIDC 用 sub / preferred_username / phone。
        // 两种都接受：先试标准名，再回退 OneAccess 名——否则真实 OneAccess 实例上取不到唯一标识，
        // 会把"账号有效"误判成"账号已失效"（fail-closed 拒绝续期）。
        String externalId = firstNonBlank(claims,
                OidcClaims.SUB, OidcClaims.ONEACCESS_USER_ID);
        if (externalId == null) {
            log.warn("Userinfo response is missing identity claim (tried '{}' and '{}'), url={}",
                    OidcClaims.SUB, OidcClaims.ONEACCESS_USER_ID, userinfoUri);
            throw new BusinessException(ErrorCodeEnum.EXTERNAL_ACCOUNT_INVALID,
                    "External account is invalid: userinfo response has neither '"
                            + OidcClaims.SUB + "' nor '" + OidcClaims.ONEACCESS_USER_ID + "'");
        }
        return new ExternalAccount(
                externalId,
                firstNonBlank(claims, OidcClaims.PREFERRED_USERNAME, OidcClaims.ONEACCESS_USER_NAME),
                str(claims.get(OidcClaims.NAME)),
                str(claims.get(OidcClaims.EMAIL)),
                firstNonBlank(claims, OidcClaims.PHONE, OidcClaims.ONEACCESS_MOBILE),
                str(claims.get(OidcClaims.PICTURE)),
                claims);
    }

    /**
     * 按给定字段名顺序取第一个非空白值（用于兼容标准 OIDC 与 OneAccess 两套字段命名）。
     *
     * @param claims userinfo 响应
     * @param names  候选字段名，按优先级排列
     * @return 第一个非空白值；都不存在时返回 {@code null}
     */
    private static String firstNonBlank(Map<String, Object> claims, String... names) {
        if (claims == null) {
            return null;
        }
        for (String name : names) {
            String value = str(claims.get(name));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }

    /** 静态内部类隔离 RestClientBuilder 依赖，避免测试类加载期强依赖 REST 实现细节 */
    private static final class RestClientBuilderFactory {

        private static OneAccessUserinfoApi create(URI baseUri) {
            return org.eclipse.microprofile.rest.client.RestClientBuilder.newBuilder()
                    .baseUri(baseUri)
                    .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build(OneAccessUserinfoApi.class);
        }
    }
}
