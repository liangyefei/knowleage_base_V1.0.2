package com.hisilicon.knowledge.platform.commons.oidc;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.oidc.api.OneAccessUserinfoApi;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link OneAccessAccountValidator} 错误路径与重试语义测试（注入替身 client，不打真实 HTTP）。 */
class OneAccessAccountValidatorTest {

    private static final String PROVIDER = "huawei";
    private static final String USERINFO_URI = "https://oneaccess.example.com/oauth2/userinfo";
    private static final String ACCESS_TOKEN = "at-123";
    private static final String BEARER = "Bearer " + ACCESS_TOKEN;
    private static final String EXTERNAL_ID = "hcp-0001";
    private static final int UNAUTHORIZED = 401;
    private static final int SERVER_ERROR = 500;

    @Test
    void supportsOnlyConfiguredProviders() {
        OneAccessAccountValidator validator = newValidator(bearer -> Map.of("sub", EXTERNAL_ID));
        assertTrue(validator.supports(PROVIDER));
        assertTrue(validator.supports("oneaccess"));
        assertFalse(validator.supports("github"));
        assertFalse(validator.supports(null));
    }

    @Test
    void validateReturnsAccountOnValidUserinfo() {
        Map<String, Object> claims = new HashMap<>();
        claims.put(OidcClaims.SUB, EXTERNAL_ID);
        claims.put(OidcClaims.PREFERRED_USERNAME, "zhangsan");
        claims.put(OidcClaims.NAME, "张三");
        claims.put(OidcClaims.EMAIL, "zhangsan@example.com");
        claims.put(OidcClaims.PHONE, "13800000000");
        claims.put(OidcClaims.PICTURE, "https://example.com/a.png");
        OneAccessAccountValidator validator = newValidator(bearer -> {
            assertEquals(BEARER, bearer);
            return claims;
        });

        ExternalAccount account = validator.validate(PROVIDER, ACCESS_TOKEN);
        assertEquals(EXTERNAL_ID, account.externalId());
        assertEquals("zhangsan", account.username());
        assertEquals("张三", account.displayName());
        assertEquals("zhangsan@example.com", account.email());
        assertEquals("13800000000", account.phone());
        assertEquals("https://example.com/a.png", account.picture());
        assertEquals(EXTERNAL_ID, String.valueOf(account.rawClaims().get(OidcClaims.SUB)));
    }

    @Test
    void validateNullifiesBlankOptionalFields() {
        Map<String, Object> claims = Map.of(
                OidcClaims.SUB, EXTERNAL_ID,
                OidcClaims.EMAIL, "  ");
        ExternalAccount account = newValidator(bearer -> claims).validate(PROVIDER, ACCESS_TOKEN);
        assertNull(account.email());
        assertNull(account.username());
    }

    @Test
    void validateFailsClosedOnUnauthorized() {
        AtomicInteger calls = new AtomicInteger();
        OneAccessAccountValidator validator = newValidator(bearer -> {
            calls.incrementAndGet();
            throw unauthorized();
        });
        BusinessException e = assertThrows(BusinessException.class,
                () -> validator.validate(PROVIDER, ACCESS_TOKEN));
        assertEquals(ErrorCodeEnum.EXTERNAL_ACCOUNT_INVALID, e.getErrorCodeEnum());
        // 401 是明确拒绝：不重试
        assertEquals(1, calls.get());
    }

    @Test
    void validateRetriesOnceThenFailsClosedOnServerError() {
        AtomicInteger calls = new AtomicInteger();
        OneAccessAccountValidator validator = newValidator(bearer -> {
            calls.incrementAndGet();
            throw serverError();
        });
        BusinessException e = assertThrows(BusinessException.class,
                () -> validator.validate(PROVIDER, ACCESS_TOKEN));
        assertEquals(ErrorCodeEnum.SERVICE_ERROR_C0114, e.getErrorCodeEnum());
        // 失败重试 1 次：共 2 次尝试
        assertEquals(2, calls.get());
    }

    @Test
    void validateRetriesOnceOnTransportFailure() {
        AtomicInteger calls = new AtomicInteger();
        OneAccessAccountValidator validator = newValidator(bearer -> {
            calls.incrementAndGet();
            throw new ProcessingException("connection reset");
        });
        BusinessException e = assertThrows(BusinessException.class,
                () -> validator.validate(PROVIDER, ACCESS_TOKEN));
        assertEquals(ErrorCodeEnum.SERVICE_ERROR_C0114, e.getErrorCodeEnum());
        assertEquals(2, calls.get());
    }

    @Test
    void validateFailsClosedWhenSubMissing() {
        OneAccessAccountValidator validator = newValidator(bearer -> Map.of(OidcClaims.EMAIL, "x@example.com"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> validator.validate(PROVIDER, ACCESS_TOKEN));
        assertEquals(ErrorCodeEnum.EXTERNAL_ACCOUNT_INVALID, e.getErrorCodeEnum());
    }

    // ======================== OneAccess 非标准字段名兼容 ========================
    // 官方文档（api-oneaccess ShowUser_0）：userinfo 返回 id / userName / name / email / mobile。
    // 若只认标准 OIDC 的 sub / preferred_username / phone，会把有效账号误判为失效。

    @Test
    void validateAcceptsOneAccessFieldNames() {
        Map<String, Object> claims = Map.of(
                OidcClaims.ONEACCESS_USER_ID, EXTERNAL_ID,
                OidcClaims.ONEACCESS_USER_NAME, "zhangsan",
                OidcClaims.NAME, "张三",
                OidcClaims.EMAIL, "zhangsan@example.com",
                OidcClaims.ONEACCESS_MOBILE, "13800000000");
        OneAccessAccountValidator validator = newValidator(bearer -> claims);

        ExternalAccount account = validator.validate(PROVIDER, ACCESS_TOKEN);

        assertEquals(EXTERNAL_ID, account.externalId());
        assertEquals("zhangsan", account.username());
        assertEquals("张三", account.displayName());
        assertEquals("zhangsan@example.com", account.email());
        assertEquals("13800000000", account.phone());
    }

    @Test
    void validatePrefersStandardClaimsWhenBothPresent() {
        Map<String, Object> claims = Map.of(
                OidcClaims.SUB, EXTERNAL_ID,
                OidcClaims.ONEACCESS_USER_ID, "oneaccess-id",
                OidcClaims.PHONE, "111",
                OidcClaims.ONEACCESS_MOBILE, "222");
        OneAccessAccountValidator validator = newValidator(bearer -> claims);

        ExternalAccount account = validator.validate(PROVIDER, ACCESS_TOKEN);

        assertEquals(EXTERNAL_ID, account.externalId());
        assertEquals("111", account.phone());
    }

    @Test
    void validateFailsClosedWithoutConfiguredEndpoint() {
        OneAccessAccountValidator validator = new OneAccessAccountValidator("   ", null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> validator.validate(PROVIDER, ACCESS_TOKEN));
        assertEquals(ErrorCodeEnum.SERVICE_ERROR_C0114, e.getErrorCodeEnum());
    }

    @Test
    void validateRejectsUnknownProviderAndBlankToken() {
        OneAccessAccountValidator validator = newValidator(bearer -> Map.of("sub", EXTERNAL_ID));
        BusinessException unknown = assertThrows(BusinessException.class,
                () -> validator.validate("github", ACCESS_TOKEN));
        assertEquals(ErrorCodeEnum.SERVICE_ERROR_C0114, unknown.getErrorCodeEnum());
        BusinessException blank = assertThrows(BusinessException.class,
                () -> validator.validate(PROVIDER, " "));
        assertEquals(ErrorCodeEnum.EXTERNAL_ACCOUNT_INVALID, blank.getErrorCodeEnum());
    }

    private static OneAccessAccountValidator newValidator(OneAccessUserinfoApi api) {
        return new OneAccessAccountValidator(USERINFO_URI, api);
    }

    private static WebApplicationException unauthorized() {
        return new WebApplicationException(
                Response.status(UNAUTHORIZED).type(MediaType.APPLICATION_JSON).build());
    }

    private static WebApplicationException serverError() {
        return new WebApplicationException(
                Response.status(SERVER_ERROR).type(MediaType.APPLICATION_JSON).build());
    }
}
