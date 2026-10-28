package com.hisilicon.knowledge.platform.system.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.web.security.OperatorIdHolder;
import com.hisilicon.knowledge.platform.commons.web.utils.PemRsaUtils;
import com.hisilicon.knowledge.platform.system.domain.entity.McpToken;
import com.hisilicon.knowledge.platform.system.domain.entity.UserEntity;
import com.hisilicon.knowledge.platform.system.domain.repository.McpTokenRepository;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRoleQueryRepository;
import com.hisilicon.knowledge.platform.system.dto.McpQuotaDTO;
import com.hisilicon.knowledge.platform.system.dto.McpTokenIssuedDTO;
import com.hisilicon.knowledge.platform.system.domain.repository.McpGatewayStore;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.jwt.build.Jwt;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * MCP 令牌签发与注销。
 *
 * <p><b>为什么另发一种令牌</b>：MCP 客户端（Trae / Cursor 等）把 token 贴在配置文件里，
 * 不像浏览器能刷新。平台 access JWT 只有 1 小时，贴上去每分钟都要换，不可用。
 * 因此签发 {@code type=mcp} 的专用 JWT：有效期默认 30 天（{@code platform.mcp-token.expire-days}）、
 * 落库可审计、可即时吊销（Redis 吊销位，网关 fail-closed 校验）。</p>
 *
 * <p><b>签名密钥与浏览器登录同一把 RSA 私钥</b>（{@code PemRsaUtils.getPrivateKey()}），
 * 网关用同一把公钥验签，所以两边不需要同步密钥；轮换公钥时同时失效所有 MCP 令牌，
 * 这是可接受的运维语义（换密钥 = 全部重新签发）。</p>
 *
 * <p>令牌明文不落库，只存 SHA-256 摘要用于排障；明文只在 {@link #issue} 的响应里出现一次。</p>
 *
 * @author hisilicon
 */
@ApplicationScoped
public class McpTokenCommandService {

    private static final Logger logger = LoggerFactory.getLogger(McpTokenCommandService.class);

    /** 与浏览器令牌相同的签发者，网关按同一 issuer 语义处理 */
    private static final String ISSUER = "hisilicon-knowledge-platform";

    /** JWT 的 type claim 名（与 access/refresh 区分） */
    private static final String CLAIM_TYPE = "type";

    /** JWT 的 jti claim 名；网关要求它是非空字符串，否则拒绝（防吊销/计量旁路） */
    private static final String CLAIM_JTI = "jti";

    private static final String CLAIM_SOURCE = "source";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final long DEFAULT_EXPIRE_DAYS = 30L;

    private final McpTokenRepository mcpTokenRepository;
    private final McpGatewayStore gatewayRedisRepository;
    private final UserRoleQueryRepository userRoleQueryRepository;
    private final OperatorIdHolder operatorIdHolder;
    private final long expireDays;
    private final String gatewayUrl;
    private final String serverName;

    @Inject
    public McpTokenCommandService(McpTokenRepository mcpTokenRepository,
                                  McpGatewayStore gatewayRedisRepository,
                                  UserRoleQueryRepository userRoleQueryRepository,
                                  OperatorIdHolder operatorIdHolder,
                                  @ConfigProperty(name = "platform.mcp-token.expire-days",
                                          defaultValue = "30") long expireDays,
                                  @ConfigProperty(name = "platform.mcp-token.gateway-url",
                                          defaultValue = "http://localhost:8080/hisilicon-knowledge-mcp/mcp")
                                  String gatewayUrl,
                                  @ConfigProperty(name = "platform.mcp-token.server-name",
                                          defaultValue = "hisilicon-knowledge") String serverName) {
        this.mcpTokenRepository = mcpTokenRepository;
        this.gatewayRedisRepository = gatewayRedisRepository;
        this.userRoleQueryRepository = userRoleQueryRepository;
        this.operatorIdHolder = operatorIdHolder;
        this.expireDays = expireDays > 0 ? expireDays : DEFAULT_EXPIRE_DAYS;
        this.gatewayUrl = gatewayUrl;
        this.serverName = serverName;
    }

    /**
     * 为当前登录用户签发一枚 MCP 令牌。
     *
     * <p>roles claim 取库里的实时角色：这样 admin 接口在网关侧的 sys_admin 判定与平台一致，
     * 且吊销后重新签发即可反映角色变化。</p>
     *
     * <p><b>每账号同时只有一枚有效令牌</b>（产品口径）：签发即轮换 ——
     * 先吊销该账号所有仍生效的旧令牌（Redis 吊销位 → 库内 revoked_at），再落新令牌。
     * 这样"重新生成"就是唯一的心智模型，不会出现用户手里存活多枚令牌、
     * 分不清哪枚才是当前配置的那个。旧 jti 通过 {@code replacedJti} 回传，供前端提示。</p>
     *
     * @param command 签发命令（label 可空）
     * @return 一次性返回令牌明文与客户端配置片段
     */
    @WithTransaction
    public Uni<McpTokenIssuedDTO> issue(IssueMcpTokenCommand command) {
        String username = requireOperator();
        Instant issuedAt = Instant.now();
        IssueContext context = new IssueContext(username, UUID.randomUUID().toString(),
                labelOf(command), issuedAt);
        logger.info("Issue MCP token for username={}, jti={}, expireDays={}",
                username, context.jti(), expireDays);
        return userRoleQueryRepository.findRoleKeysByUsername(username)
                .chain(roles -> rotateActive(username)
                        .chain(replacedJti -> persist(context, roles, replacedJti)));
    }

    /**
     * 注销当前用户自己的一枚令牌。
     *
     * @param jti 令牌 jti
     * @return 完成信号
     */
    @WithTransaction
    public Uni<Void> revokeOwn(String jti) {
        return revoke(requireOperator(), jti);
    }

    /**
     * 管理员注销指定账号的一枚令牌。
     *
     * @param username 账号
     * @param jti      令牌 jti
     * @return 完成信号
     */
    @WithTransaction
    public Uni<Void> revokeForUser(String username, String jti) {
        return revoke(username, jti);
    }

    /**
     * 注销某账号的全部有效令牌（登出联动、管理员一键收权）。
     *
     * <p>失败不中断主流程：登出本身必须成功，令牌收不掉最多留到过期。</p>
     *
     * @param username 账号
     * @return 完成信号（永不失败）
     */
    @WithTransaction
    public Uni<Void> revokeAllForUser(String username) {
        if (username == null || username.isBlank()) {
            return Uni.createFrom().voidItem();
        }
        return mcpTokenRepository.findByUsername(username)
                .chain(tokens -> revokeEach(username, tokens))
                .onFailure().invoke(t -> logger.error(
                        "Revoke all MCP tokens failed for username={}: {}", username, t.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * 设置账号级日限额覆盖（管理员）。
     *
     * @param username 账号
     * @param command  限额（null 维度不覆盖；两维都 null 等于清除覆盖）
     * @return 写入后的覆盖状态
     */
    public Uni<McpQuotaDTO> setQuota(String username, SetMcpQuotaCommand command) {
        requireUsername(username);
        Long dailyCalls = command == null ? null : command.dailyCalls();
        Long dailyChars = command == null ? null : command.dailyChars();
        logger.info("Set MCP quota username={}, dailyCalls={}, dailyChars={}",
                username, dailyCalls, dailyChars);
        return gatewayRedisRepository.replaceQuota(username, dailyCalls, dailyChars)
                .replaceWith(() -> new McpQuotaDTO(username, dailyCalls, dailyChars));
    }

    /**
     * 轮换：吊销该账号所有仍生效（未注销且未过期）的令牌，返回被顶掉的 jti。
     *
     * <p>已过期/已注销的令牌不重复处理（网关按 exp 本来就会拒绝，再写一次只是噪音）。
     * 多枚并存只可能来自历史数据或并发签发，这里一律收掉，返回其中最新的一枚做提示。</p>
     *
     * @param username 账号
     * @return 被顶掉的令牌 jti（没有旧令牌时为 null）
     */
    private Uni<String> rotateActive(String username) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        return mcpTokenRepository.findByUsername(username)
                .chain(tokens -> {
                    List<McpToken> active = tokens.stream()
                            .filter(token -> token.isUsableAt(now))
                            .toList();
                    if (active.isEmpty()) {
                        return Uni.createFrom().<String>nullItem();
                    }
                    String replacedJti = active.get(0).getJti();
                    logger.info("Rotate MCP token for username={}, revoking {} old token(s), replacedJti={}",
                            username, active.size(), replacedJti);
                    Uni<Void> pipeline = Uni.createFrom().voidItem();
                    for (McpToken token : active) {
                        pipeline = pipeline.chain(() -> revokeToken(username, token));
                    }
                    return pipeline.replaceWith(replacedJti);
                });
    }

    /**
     * 吊销一枚已在手的令牌实体（不再回库读取，顺序与 {@link #revoke} 一致：
     * 先 Redis 让网关立即拒绝，再写库留痕）。
     */
    private Uni<Void> revokeToken(String username, McpToken token) {
        logger.info("Revoke MCP token username={}, jti={}", username, token.getJti());
        return gatewayRedisRepository.revoke(token.getJti(), remainingOf(token))
                .chain(() -> mcpTokenRepository.markRevoked(token.getJti(), LocalDateTime.now(ZONE)))
                .replaceWithVoid();
    }

    private Uni<Void> revokeEach(String username, List<McpToken> tokens) {
        List<McpToken> active = tokens.stream().filter(token -> token.getRevokedAt() == null).toList();
        if (active.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        Uni<Void> pipeline = Uni.createFrom().voidItem();
        for (McpToken token : active) {
            pipeline = pipeline.chain(() -> revokeToken(username, token));
        }
        return pipeline;
    }

    /**
     * 吊销顺序：先写 Redis（网关立即拒绝），再写库留痕。
     *
     * <p>这个顺序是刻意的：Redis 成功而库写失败时，令牌已经被拒（安全侧偏保守）；
     * 反过来则会出现「库里标了注销但网关还放行」的危险窗口。</p>
     */
    private Uni<Void> revoke(String owner, String jti) {
        requireUsername(owner);
        if (jti == null || jti.isBlank()) {
            return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.USER_ERROR_A0410));
        }
        return mcpTokenRepository.findByJti(jti)
                .chain(found -> {
                    if (found.isEmpty() || !owner.equals(found.get().getUsername())) {
                        // 越权与不存在同样返回「不存在」，不泄露他人令牌是否存在
                        logger.warn("Revoke MCP token rejected, owner={} jti={} not found", owner, jti);
                        return Uni.createFrom().<Void>failure(
                                new BusinessException(ErrorCodeEnum.USER_ERROR_A0430));
                    }
                    McpToken token = found.get();
                    Duration remaining = remainingOf(token);
                    return gatewayRedisRepository.revoke(jti, remaining)
                            .chain(() -> mcpTokenRepository.markRevoked(jti, LocalDateTime.now(ZONE)))
                            .replaceWithVoid();
                });
    }

    private Uni<McpTokenIssuedDTO> persist(IssueContext context, List<String> roles, String replacedJti) {
        String token = signJwt(context, roles);
        LocalDateTime expiresAt = LocalDateTime.ofInstant(expiresAtOf(context), ZONE);
        McpToken entity = McpToken.builder()
                .jti(context.jti())
                .username(context.username())
                .tokenHash(sha256(token))
                .label(context.label())
                .createdAt(LocalDateTime.ofInstant(context.issuedAt(), ZONE))
                .expiresAt(expiresAt)
                .build();
        return mcpTokenRepository.save(entity)
                .replaceWith(() -> new McpTokenIssuedDTO(context.jti(), context.label(), token,
                        expiresAt, gatewayUrl, mcpServersJson(token), replacedJti));
    }

    private String signJwt(IssueContext context, List<String> roles) {
        return Jwt.claims()
                .subject(context.username())
                .issuer(ISSUER)
                .issuedAt(context.issuedAt())
                .expiresIn(Duration.between(context.issuedAt(), expiresAtOf(context)))
                .claim(CLAIM_TYPE, McpToken.TYPE_MCP)
                .claim(CLAIM_JTI, context.jti())
                .claim(UserEntity.CLAIM_ROLES, roles)
                .claim(CLAIM_SOURCE, UserEntity.SOURCE_LOCAL)
                .sign(PemRsaUtils.getPrivateKey());
    }

    private String mcpServersJson(String token) {
        // 手拼而非 ObjectMapper：结构固定、字段极少，且必须严格按客户端要求的 JSON 形态输出
        return "{\"mcpServers\":{\"" + serverName + "\":{"
                + "\"type\":\"streamable-http\","
                + "\"url\":\"" + gatewayUrl + "\","
                + "\"headers\":{\"Authorization\":\"Bearer " + token + "\"}}}}";
    }

    private Instant expiresAtOf(IssueContext context) {
        return context.issuedAt().plus(Duration.ofDays(expireDays));
    }

    private static Duration remainingOf(McpToken token) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (token.getExpiresAt() == null || !token.getExpiresAt().isAfter(now)) {
            // 已过期的令牌：给 1 秒即可，网关本来就会因 exp 拒绝
            return Duration.ofSeconds(1);
        }
        return Duration.between(now, token.getExpiresAt());
    }

    private String requireOperator() {
        String username = operatorIdHolder.get();
        requireUsername(username);
        return username;
    }

    private static void requireUsername(String username) {
        if (username == null || username.isBlank()) {
            logger.warn("MCP token operation rejected, no operator in context");
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0301);
        }
    }

    private static String labelOf(IssueMcpTokenCommand command) {
        if (command == null || command.label() == null || command.label().isBlank()) {
            return "default";
        }
        return command.label().trim();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须实现的算法，走到这里说明运行环境异常
            throw new BusinessException(ErrorCodeEnum.SYSTEM_ERROR_B0600);
        }
    }

    /**
     * 一次签发的上下文（收敛参数，避免方法签名过长）。
     *
     * @param username 账号
     * @param jti      令牌唯一标识
     * @param label    备注
     * @param issuedAt 签发时刻
     */
    private record IssueContext(String username, String jti, String label, Instant issuedAt) {
    }
}