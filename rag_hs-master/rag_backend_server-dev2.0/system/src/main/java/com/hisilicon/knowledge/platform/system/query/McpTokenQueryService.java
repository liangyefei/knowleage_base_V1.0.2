package com.hisilicon.knowledge.platform.system.query;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.web.security.OperatorIdHolder;
import com.hisilicon.knowledge.platform.system.domain.entity.McpToken;
import com.hisilicon.knowledge.platform.system.domain.repository.McpGatewayStore;
import com.hisilicon.knowledge.platform.system.domain.repository.McpTokenRepository;
import com.hisilicon.knowledge.platform.system.dto.McpQuotaDTO;
import com.hisilicon.knowledge.platform.system.dto.McpTokenDTO;
import com.hisilicon.knowledge.platform.system.dto.McpUsageDTO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MCP 令牌与用量查询（读侧，CQRS）。
 *
 * <p>用量数据全部来自 Redis 计数器（网关写、iam 读），库内 {@code mcp_tokens.last_used_at}
 * 是预留列、当前不写 —— 网关每请求回写数据库不划算，展示用 Redis 的
 * {@code mcp:token-lastused:{jti}} 就够。</p>
 *
 * <p>Redis 不可用时列表与用量降级：最近使用时间显示 null、计数显示 0，
 * 不让管理页面因为缓存故障整体 500。</p>
 *
 * @author hisilicon
 */
@ApplicationScoped
public class McpTokenQueryService {

    private static final Logger logger = LoggerFactory.getLogger(McpTokenQueryService.class);

    /** 粗估口径：4 字符 ≈ 1 token（网关只统计字符数，不精确分词） */
    private static final long CHARS_PER_TOKEN = 4L;

    private final McpTokenRepository mcpTokenRepository;
    private final McpGatewayStore gatewayRedisRepository;
    private final OperatorIdHolder operatorIdHolder;
    private final long defaultDailyCalls;
    private final long defaultDailyChars;

    @Inject
    public McpTokenQueryService(McpTokenRepository mcpTokenRepository,
                                McpGatewayStore gatewayRedisRepository,
                                OperatorIdHolder operatorIdHolder,
                                @ConfigProperty(name = "platform.mcp-token.quota-default-calls",
                                        defaultValue = "2000") long defaultDailyCalls,
                                @ConfigProperty(name = "platform.mcp-token.quota-default-chars",
                                        defaultValue = "20000000") long defaultDailyChars) {
        this.mcpTokenRepository = mcpTokenRepository;
        this.gatewayRedisRepository = gatewayRedisRepository;
        this.operatorIdHolder = operatorIdHolder;
        this.defaultDailyCalls = defaultDailyCalls;
        this.defaultDailyChars = defaultDailyChars;
    }

    /**
     * 当前登录用户的令牌列表。
     *
     * @return 令牌元数据列表（不含明文）
     */
    public Uni<List<McpTokenDTO>> listOwn() {
        String username = operatorIdHolder.get();
        if (username == null || username.isBlank()) {
            return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.USER_ERROR_A0301));
        }
        return listForUser(username);
    }

    /**
     * 指定账号的令牌列表（管理员）。
     *
     * @param username 账号
     * @return 令牌元数据列表（不含明文）
     */
    public Uni<List<McpTokenDTO>> listForUser(String username) {
        if (username == null || username.isBlank()) {
            return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.USER_ERROR_A0410));
        }
        return mcpTokenRepository.findByUsername(username)
                .chain(this::withLastUsed);
    }

    /**
     * 当前登录用户某日的用量。
     *
     * @param date 统计日（yyyy-MM-dd，空则取今天）
     * @return 用量快照
     */
    public Uni<McpUsageDTO> usageOwn(String date) {
        String username = operatorIdHolder.get();
        if (username == null || username.isBlank()) {
            return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.USER_ERROR_A0301));
        }
        return usageForUser(username, date);
    }

    /**
     * 指定账号某日的用量（管理员）。
     *
     * @param username 账号
     * @param date     统计日（yyyy-MM-dd，空则取今天）
     * @return 用量快照
     */
    public Uni<McpUsageDTO> usageForUser(String username, String date) {
        if (username == null || username.isBlank()) {
            return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.USER_ERROR_A0410));
        }
        String day = normalizeDate(date);
        return Uni.combine().all()
                .unis(gatewayRedisRepository.findUsage(username, day),
                        gatewayRedisRepository.findDailyCallsOverride(username),
                        gatewayRedisRepository.findDailyCharsOverride(username))
                .asTuple()
                .map(tuple -> {
                    McpGatewayStore.UsageCounters counters = tuple.getItem1();
                    Long limitCalls = tuple.getItem2().orElse(null);
                    Long limitChars = tuple.getItem3().orElse(null);
                    long effectiveCalls = limitCalls == null ? defaultDailyCalls : limitCalls;
                    long effectiveChars = limitChars == null ? defaultDailyChars : limitChars;
                    return new McpUsageDTO(username, day, counters.calls(), counters.chars(),
                            counters.chars() / CHARS_PER_TOKEN, limitCalls, limitChars,
                            counters.calls() >= effectiveCalls || counters.chars() >= effectiveChars);
                });
    }

    /**
     * 指定账号的限额覆盖（管理员）。
     *
     * @param username 账号
     * @return 覆盖值；两个维度都为 null 表示没有覆盖
     */
    public Uni<McpQuotaDTO> quota(String username) {
        if (username == null || username.isBlank()) {
            return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.USER_ERROR_A0410));
        }
        return Uni.combine().all()
                .unis(gatewayRedisRepository.findDailyCallsOverride(username),
                        gatewayRedisRepository.findDailyCharsOverride(username))
                .asTuple()
                .map(tuple -> new McpQuotaDTO(username, tuple.getItem1().orElse(null),
                        tuple.getItem2().orElse(null)));
    }

    /**
     * 统计日规整：空值取今天，格式非法直接拒绝（避免把坏参数当今天用，掩盖前端 bug）。
     */
    private String normalizeDate(String date) {
        if (date == null || date.isBlank()) {
            return gatewayRedisRepository.today();
        }
        try {
            return LocalDate.parse(date.trim()).toString();
        } catch (DateTimeParseException e) {
            logger.warn("Invalid MCP usage date '{}'", date);
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0421);
        }
    }

    /**
     * 给每个令牌补最近使用时间；Redis 故障时退化为 null，不影响列表可用性。
     */
    private Uni<List<McpTokenDTO>> withLastUsed(List<McpToken> tokens) {
        if (tokens.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        LocalDateTime now = LocalDateTime.now();
        List<Uni<McpTokenDTO>> unis = new ArrayList<>(tokens.size());
        for (McpToken token : tokens) {
            unis.add(gatewayRedisRepository.findLastUsed(token.getJti())
                    .onFailure().recoverWithItem(t -> {
                        logger.warn("Read MCP last-used failed for jti={}: {}",
                                token.getJti(), t.getMessage());
                        return Optional.empty();
                    })
                    .map(lastUsed -> toDto(token, now, lastUsed.orElse(null))));
        }
        return Uni.combine().all().unis(unis).with(list -> castDto(list));
    }

    private static McpTokenDTO toDto(McpToken token, LocalDateTime now, LocalDateTime lastUsedAt) {
        return new McpTokenDTO(token.getJti(), token.getLabel(), token.getCreatedAt(),
                token.getExpiresAt(), token.getRevokedAt(), token.isUsableAt(now), lastUsedAt);
    }

    private static List<McpTokenDTO> castDto(List<?> raw) {
        List<McpTokenDTO> result = new ArrayList<>(raw.size());
        for (Object item : raw) {
            result.add((McpTokenDTO) item);
        }
        return result;
    }
}