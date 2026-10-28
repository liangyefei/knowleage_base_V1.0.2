package com.hisilicon.knowledge.platform.system.facade;

import com.hisilicon.knowledge.platform.commons.web.utils.PemRsaUtils;
import com.hisilicon.knowledge.platform.system.domain.entity.UserEntity;
import com.hisilicon.knowledge.platform.system.domain.entity.UserId;
import com.hisilicon.knowledge.platform.system.dto.TokenDTO;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRepository;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRoleQueryRepository;
import com.hisilicon.knowledge.platform.system.infrastructure.repository.TokenRedisRepository;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * {@code @QuarkusTest} for the login endpoint.
 *
 * <p>Uses {@link QuarkusMock#installMockForInstance} to replace real CDI
 * beans with hand-written mocks, avoiding database and Redis dependencies.</p>
 *
 * <p>NOTE: en-US <em>validation</em> message tests are excluded because
 * Hibernate Validator's locale initialization for {@code en-US} requires
 * a Quarkus build-time configuration change outside the scope of this
 * module. English <em>business error</em> messages (via {@code AppMessages})
 * are tested and work correctly.</p>
 */
@QuarkusTest
class UserResourceLoginTest {

    private static final String VALID_USERNAME = "admin";
    private static final String VALID_PASSWORD = "admin123";
    private static final String PASSWORD_HASH = BcryptUtil.bcryptHash(VALID_PASSWORD);

    @Inject
    UserRepository userRepository;

    @Inject
    TokenRedisRepository tokenRedisRepository;

    @Inject
    UserRoleQueryRepository userRoleQueryRepository;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(
                new MockUserRepository(), userRepository);
        QuarkusMock.installMockForInstance(
                new MockTokenRedisRepository(), tokenRedisRepository);
        QuarkusMock.installMockForInstance(
                new MockUserRoleQueryRepository(), userRoleQueryRepository);
        // 重置 mock 行为
        MockTokenRedisRepository.setVerified(true);
        MockTokenRedisRepository.setCurrentUserToken("");
        MockUserRoleQueryRepository.reset();
    }

    // ======================== 成功场景 ========================

    @Test
    void shouldLoginSuccess() {
        MockUserRepository.setReturnUser(createActiveUser());

        String encUsername = PemRsaUtils.encryptBase64(VALID_USERNAME, null);
        String encPassword = PemRsaUtils.encryptBase64(VALID_PASSWORD, null);

        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"username\":\"" + encUsername + "\",\"password\":\"" + encPassword + "\"}")
                .when().post("/user/login")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("操作成功"))
                .body("data.accessToken", not(emptyOrNullString()))
                .body("data.refreshToken", not(emptyOrNullString()));
    }

    @Test
    void shouldLoginSuccess_En() {
        MockUserRepository.setReturnUser(createActiveUser());

        String encUsername = PemRsaUtils.encryptBase64(VALID_USERNAME, null);
        String encPassword = PemRsaUtils.encryptBase64(VALID_PASSWORD, null);

        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "en-US")
                .body("{\"username\":\"" + encUsername + "\",\"password\":\"" + encPassword + "\"}")
                .when().post("/user/login")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("Operation successful"))
                .body("data.accessToken", not(emptyOrNullString()))
                .body("data.refreshToken", not(emptyOrNullString()));
    }

    // ======================== 参数校验（中文） ========================

    @Test
    void shouldFailWhenUsernameBlank() {
        String encPassword = PemRsaUtils.encryptBase64(VALID_PASSWORD, null);

        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"username\":\"\",\"password\":\"" + encPassword + "\"}")
                .when().post("/user/login")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("用户名不能为空"));
    }

    @Test
    void shouldFailWhenPasswordBlank() {
        String encUsername = PemRsaUtils.encryptBase64(VALID_USERNAME, null);

        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"username\":\"" + encUsername + "\",\"password\":\"\"}")
                .when().post("/user/login")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("密码不能为空"));
    }

    @Test
    void shouldFailWhenBothBlank() {
        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"username\":\"\",\"password\":\"\"}")
                .when().post("/user/login")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"));
    }

    // ======================== 业务异常（中文） ========================

    @Test
    void shouldFailWhenCredentialsWrong() {
        MockUserRepository.setReturnUser(createActiveUser());

        String encUsername = PemRsaUtils.encryptBase64(VALID_USERNAME, null);
        String encPassword = PemRsaUtils.encryptBase64("wrong-password", null);

        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"username\":\"" + encUsername + "\",\"password\":\"" + encPassword + "\"}")
                .when().post("/user/login")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0210"))
                .body("message", containsString("用户名或密码错误"));
    }

    @Test
    void shouldFailWhenUserNotFound() {
        MockUserRepository.setReturnUser(null);

        String encUsername = PemRsaUtils.encryptBase64("nonexistent", null);
        String encPassword = PemRsaUtils.encryptBase64(VALID_PASSWORD, null);

        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"username\":\"" + encUsername + "\",\"password\":\"" + encPassword + "\"}")
                .when().post("/user/login")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0210"))
                .body("message", containsString("用户名或密码错误"));
    }

    @Test
    void shouldFailWhenUserDisabled() {
        MockUserRepository.setReturnUser(createDisabledUser());

        String encUsername = PemRsaUtils.encryptBase64(VALID_USERNAME, null);
        String encPassword = PemRsaUtils.encryptBase64(VALID_PASSWORD, null);

        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"username\":\"" + encUsername + "\",\"password\":\"" + encPassword + "\"}")
                .when().post("/user/login")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0202"))
                .body("message", containsString("用户账户被冻结"));
    }

    // ======================== 业务异常国际化（英文） ========================

    @Test
    void shouldFailWhenCredentialsWrong_En() {
        MockUserRepository.setReturnUser(createActiveUser());

        String encUsername = PemRsaUtils.encryptBase64(VALID_USERNAME, null);
        String encPassword = PemRsaUtils.encryptBase64("wrong-password", null);

        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "en-US")
                .body("{\"username\":\"" + encUsername + "\",\"password\":\"" + encPassword + "\"}")
                .when().post("/user/login")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0210"))
                .body("message", containsString("Invalid username or password"));
    }

    @Test
    void shouldFailWhenUserDisabled_En() {
        MockUserRepository.setReturnUser(createDisabledUser());

        String encUsername = PemRsaUtils.encryptBase64(VALID_USERNAME, null);
        String encPassword = PemRsaUtils.encryptBase64(VALID_PASSWORD, null);

        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "en-US")
                .body("{\"username\":\"" + encUsername + "\",\"password\":\"" + encPassword + "\"}")
                .when().post("/user/login")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0202"))
                .body("message", containsString("Account has been frozen"));
    }

    // ======================== 默认语言降级 ========================

    @Test
    void shouldDefaultToChineseWhenNoAcceptLanguage() {
        MockUserRepository.setReturnUser(createActiveUser());

        String encUsername = PemRsaUtils.encryptBase64(VALID_USERNAME, null);
        String encPassword = PemRsaUtils.encryptBase64("wrong-password", null);

        given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"" + encUsername + "\",\"password\":\"" + encPassword + "\"}")
                .when().post("/user/login")
                .then()
                .statusCode(200)
                .body("message", containsString("用户名或密码错误"));
    }

    // ======================== 刷新令牌（成功） ========================

    @Test
    void shouldRefreshSuccess() {
        // 生成有效的 token 对
        TokenDTO token = UserEntity.generateTokenWithKey(
                VALID_USERNAME, PemRsaUtils.getPrivateKey());
        // mock user_token 索引匹配
        String jti = extractJtiFromToken(token.refreshToken());
        MockTokenRedisRepository.setCurrentUserToken(jti + "|" + UUID.randomUUID().toString());

        given()
                .contentType(ContentType.JSON)
                .header("X-Access-Token", token.accessToken())
                .header("Refresh-Token", token.refreshToken())
                .header("Accept-Language", "zh-CN")
                .when().post("/user/refresh")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("操作成功"))
                .body("data.accessToken", not(emptyOrNullString()))
                .body("data.refreshToken", not(emptyOrNullString()));
    }

    @Test
    void shouldRefreshSuccess_En() {
        TokenDTO token = UserEntity.generateTokenWithKey(
                VALID_USERNAME, PemRsaUtils.getPrivateKey());
        String jti = extractJtiFromToken(token.refreshToken());
        MockTokenRedisRepository.setCurrentUserToken(jti + "|" + UUID.randomUUID().toString());

        given()
                .contentType(ContentType.JSON)
                .header("X-Access-Token", token.accessToken())
                .header("Refresh-Token", token.refreshToken())
                .header("Accept-Language", "en-US")
                .when().post("/user/refresh")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("Operation successful"))
                .body("data.accessToken", not(emptyOrNullString()))
                .body("data.refreshToken", not(emptyOrNullString()));
    }

    // ======================== 刷新令牌（异常） ========================

    @Test
    void shouldFailWhenRefreshTokenNotInRedis() {
        TokenDTO token = UserEntity.generateTokenWithKey(
                VALID_USERNAME, PemRsaUtils.getPrivateKey());
        MockTokenRedisRepository.setVerified(false);

        given()
                .contentType(ContentType.JSON)
                .header("X-Access-Token", token.accessToken())
                .header("Refresh-Token", token.refreshToken())
                .header("Accept-Language", "zh-CN")
                .when().post("/user/refresh")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0204"))
                .body("message", containsString("刷新令牌已过期"));
    }

    @Test
    void shouldFailWhenAccessTokenNotMatch() {
        TokenDTO token = UserEntity.generateTokenWithKey(
                VALID_USERNAME, PemRsaUtils.getPrivateKey());
        String jti = extractJtiFromToken(token.refreshToken());
        MockTokenRedisRepository.setCurrentUserToken(jti + "|" + UUID.randomUUID().toString());

        given()
                .contentType(ContentType.JSON)
                .header("X-Access-Token", "invalid-access-token")
                .header("Refresh-Token", token.refreshToken())
                .header("Accept-Language", "zh-CN")
                .when().post("/user/refresh")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0220"))
                .body("message", containsString("令牌对不匹配"));
    }

    @Test
    void shouldFailWhenRefreshTokenExpired_En() {
        TokenDTO token = UserEntity.generateTokenWithKey(
                VALID_USERNAME, PemRsaUtils.getPrivateKey());
        MockTokenRedisRepository.setVerified(false);

        given()
                .contentType(ContentType.JSON)
                .header("X-Access-Token", token.accessToken())
                .header("Refresh-Token", token.refreshToken())
                .header("Accept-Language", "en-US")
                .when().post("/user/refresh")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0204"))
                .body("message", containsString("The refresh token has expired"));
    }

    @Test
    void shouldFailWhenNoRefreshTokenHeader() {
        TokenDTO token = UserEntity.generateTokenWithKey(
                VALID_USERNAME, PemRsaUtils.getPrivateKey());

        given()
                .contentType(ContentType.JSON)
                .header("X-Access-Token", token.accessToken())
                .header("Accept-Language", "zh-CN")
                .when().post("/user/refresh")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0400"))
                .body("message", containsString("参数不正确"));
    }

    // ======================== 退出登录 ========================

    @Test
    void shouldLogoutSuccess() {
        TokenDTO token = UserEntity.generateTokenWithKey(
                VALID_USERNAME, PemRsaUtils.getPrivateKey());
        String jti = extractJtiFromToken(token.refreshToken());
        MockTokenRedisRepository.setCurrentUserToken(jti + "|" + UUID.randomUUID().toString());

        given()
                .contentType(ContentType.JSON)
                .header("X-Access-Token", token.accessToken())
                .header("Accept-Language", "zh-CN")
                .when().post("/user/logout")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("操作成功"));
    }

    @Test
    void shouldLogoutSuccess_En() {
        TokenDTO token = UserEntity.generateTokenWithKey(
                VALID_USERNAME, PemRsaUtils.getPrivateKey());

        given()
                .contentType(ContentType.JSON)
                .header("X-Access-Token", token.accessToken())
                .header("Accept-Language", "en-US")
                .when().post("/user/logout")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("Operation successful"));
    }

    @Test
    void shouldLogoutSuccessWhenInvalidToken() {
        given()
                .contentType(ContentType.JSON)
                .header("X-Access-Token", "invalid-jwt-token")
                .header("Accept-Language", "zh-CN")
                .when().post("/user/logout")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("操作成功"));
    }

    @Test
    void shouldLogoutSuccessWhenNoToken() {
        given()
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .when().post("/user/logout")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("操作成功"));
    }


    @Test
    void shouldLogoutSuccessWhenExpiredToken() throws Exception {
        // 生成一个已经过期的 access token
        java.security.PrivateKey key = com.hisilicon.knowledge.platform.commons.web.utils.PemRsaUtils.getPrivateKey();
        String expiredToken = io.smallrye.jwt.build.Jwt.claims()
                .subject(VALID_USERNAME)
                .issuer("hisilicon-knowledge-platform")
                .issuedAt(java.time.Instant.now().minusSeconds(7200))
                .expiresIn(java.time.Duration.ofHours(-1))
                .claim("type", "access")
                .sign(key);

        given()
                .contentType(ContentType.JSON)
                .header("X-Access-Token", expiredToken)
                .header("Accept-Language", "zh-CN")
                .when().post("/user/logout")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("message", equalTo("操作成功"));
    }


    // ======================== 辅助方法 ========================

    private static String extractJtiFromToken(String refreshToken) {
        try {
            String[] parts = refreshToken.split("\\.");
            byte[] decoded = java.util.Base64.getUrlDecoder().decode(parts[1]);
            String payload = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            String search = "\"jti\":\"";
            int start = payload.indexOf(search);
            if (start < 0) return "";
            start += search.length();
            int end = payload.indexOf("\"", start);
            return end > start ? payload.substring(start, end) : "";
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            return "";
        }
    }

    private static UserEntity createActiveUser() {
        return UserEntity.builder()
                .id(UserId.of(UUID.randomUUID().toString()))
                .username(VALID_USERNAME)
                .password(PASSWORD_HASH)
                .status((short) 1)
                .lastLogin(LocalDateTime.now())
                .build();
    }

    private static UserEntity createDisabledUser() {
        return UserEntity.builder()
                .id(UserId.of(UUID.randomUUID().toString()))
                .username(VALID_USERNAME)
                .password(PASSWORD_HASH)
                .status((short) 2)
                .lastLogin(LocalDateTime.now())
                .build();
    }
}
