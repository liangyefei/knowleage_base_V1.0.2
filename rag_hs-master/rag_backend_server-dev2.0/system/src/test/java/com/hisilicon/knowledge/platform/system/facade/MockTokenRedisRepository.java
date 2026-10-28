package com.hisilicon.knowledge.platform.system.facade;

import com.hisilicon.knowledge.platform.system.infrastructure.repository.TokenRedisRepository;
import io.smallrye.mutiny.Uni;

/**
 * 手动 Mock — 替换 {@link TokenRedisRepository}
 * <p>通过静态字段控制各方法的行为；同时记录 {@link #saveRefreshToken(String, String)}
 * 的调用次数，供"未签发新令牌"类断言使用（该替身不是 Mockito mock，不能用 verify）。</p>
 */
public class MockTokenRedisRepository extends TokenRedisRepository {

    /** refresh_token 校验结果 */
    private static volatile boolean verified = true;
    /** 当前 user_token 值（空串表示不存在） */
    private static volatile String currentUserToken = "";
    /** saveRefreshToken 被调用的次数 */
    private static final java.util.concurrent.atomic.AtomicInteger SAVE_REFRESH_COUNT =
            new java.util.concurrent.atomic.AtomicInteger();

    public static boolean isVerified() {
        return verified;
    }

    public static void setVerified(boolean verified) {
        MockTokenRedisRepository.verified = verified;
    }

    /** 重置调用计数（各用例 @BeforeEach 调用） */
    public static void resetSaveRefreshCount() {
        SAVE_REFRESH_COUNT.set(0);
    }

    /** saveRefreshToken 的累计调用次数 */
    public static int saveRefreshCount() {
        return SAVE_REFRESH_COUNT.get();
    }

    public static String getCurrentUserToken() {
        return currentUserToken;
    }

    public static void setCurrentUserToken(String token) {
        currentUserToken = token == null ? "" : token;
    }

    public MockTokenRedisRepository() {
        super(null); // 跳过 RedisAPI 依赖
    }

    @Override
    public Uni<Void> saveRefreshToken(String jti, String username) {
        SAVE_REFRESH_COUNT.incrementAndGet();
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<String> consumeRefreshToken(String jti) {
        return Uni.createFrom().nullItem();
    }

    @Override
    public Uni<Void> deleteRefreshToken(String jti) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Boolean> verifyAndDeleteRefreshToken(String jti, String username) {
        return Uni.createFrom().item(verified);
    }

    @Override
    public Uni<Void> setUserCurrentToken(String username, String refreshJti, String accessJti) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<String> getUserCurrentToken(String username) {
        return Uni.createFrom().item(currentUserToken);
    }

    @Override
    public Uni<Void> deleteUserCurrentToken(String username) {
        return Uni.createFrom().voidItem();
    }
}
