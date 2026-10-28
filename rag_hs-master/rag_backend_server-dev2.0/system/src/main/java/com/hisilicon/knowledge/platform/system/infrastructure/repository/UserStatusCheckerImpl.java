package com.hisilicon.knowledge.platform.system.infrastructure.repository;

import com.hisilicon.knowledge.platform.commons.web.security.UserStatusChecker;
import com.hisilicon.knowledge.platform.system.infrastructure.po.UserPO;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * 用户状态查询端口实现（{@link UserStatusChecker}）：给 commons-web 的鉴权过滤器回查 {@code users.status}。
 *
 * <p><b>为什么这里是同步阻塞的</b>：过滤器
 * {@code AuthenticationFilter.filter()} 是同步方法（JAX-RS {@code ContainerRequestFilter}），
 * 端口签名 {@code int statusOf(String)} 也必须是同步的，因此本实现是"过滤器同步边界上的受控阻塞"。</p>
 *
 * <p>阻塞被严格限制在调用线程、且有 2 秒上限：Hibernate Reactive 查询的<b>订阅</b>被
 * {@link Infrastructure#getDefaultWorkerPool() worker 线程池}接管，调用线程只等待结果
 * （{@code await().atMost(...)}），不会把数据库 IO 跑在等待线程上。超时 / 查库失败会抛异常，
 * 由 {@code UserStatusValidator} 兜底放行并记录 warn（向后兼容语义，见其 javadoc）。</p>
 *
 * <p>过滤器自身对结果做了 Redis 缓存（{@code user:status:{username}}，TTL 600 秒），
 * 所以正常流量下每用户每 10 分钟才回查一次，同步阻塞的实际频率很低。</p>
 */
@ApplicationScoped
public class UserStatusCheckerImpl implements UserStatusChecker {

    private static final Logger logger = LoggerFactory.getLogger(UserStatusCheckerImpl.class);

    /** 用户不存在 / 状态为空时返回的值：非 1 即视为无效，过滤器按 401 拒绝 */
    private static final int STATUS_UNKNOWN = 0;

    /** 同步等待上限：与过滤器"失败放行"的兜底语义配合，避免卡住请求线程 */
    private static final Duration STATUS_LOOKUP_TIMEOUT = Duration.ofSeconds(2);

    private final UserPanacheRepository userPanacheRepository;

    public UserStatusCheckerImpl(UserPanacheRepository userPanacheRepository) {
        this.userPanacheRepository = userPanacheRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>查询失败不吞异常：由 {@code UserStatusValidator} 统一兜底（放行 + warn）。
     * 用户不存在返回 {@link #STATUS_UNKNOWN}（0），等价于"已删除"，过滤器拒绝该令牌。</p>
     */
    @Override
    public int statusOf(String username) {
        logger.debug("User status lookup on authentication filter boundary: username={}", username);
        Integer status = userPanacheRepository.find("username", username).firstResult()
                // 订阅切到 worker 线程池：Hibernate Reactive 的查询不在等待线程上执行
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .map((UserPO user) -> user == null || user.getStatus() == null
                        ? STATUS_UNKNOWN
                        : user.getStatus().intValue())
                .await().atMost(STATUS_LOOKUP_TIMEOUT);
        return status == null ? STATUS_UNKNOWN : status;
    }
}
