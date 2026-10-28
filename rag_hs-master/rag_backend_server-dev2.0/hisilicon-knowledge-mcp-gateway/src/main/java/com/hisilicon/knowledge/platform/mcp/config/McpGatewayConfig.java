package com.hisilicon.knowledge.platform.mcp.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * MCP 网关上游配置（MP Config 前缀 {@code mcp-gateway}）。
 *
 * <p>上游清单**配置化**：增减上游不发版（对应《MCP 网关对接方案》§3）。
 * url 走环境变量注入（对齐 {@code KB_KG_PUBLIC_URL} 风格），便于阶段 3 把公网 EIP
 * 切到 CCE 内网地址而不改代码。</p>
 *
 * <p>网关**不缓存**上游工具清单：每次启动拉取一次，并提供刷新入口
 * （上游工具变更无通知，缓存会让 agent 侧 schema 漂移，见方案 §5 风险表）。</p>
 */
@ConfigMapping(prefix = "mcp-gateway")
public interface McpGatewayConfig {

    /** 上游 MCP 服务清单 */
    List<Upstream> upstreams();

    /**
     * 工具暴露名前缀分隔符：{@code 上游名<separator>工具名}。
     *
     * <p>默认双下划线。实测 dev 环境 {@code hisi_docs_search} 同时存在于 9802 与 9803，
     * 前缀是**必需**的消歧手段，不是可选装饰。</p>
     */
    @WithDefault("__")
    String nameSeparator();

    /** 单次上游请求的连接超时 */
    @WithDefault("5s")
    Duration connectTimeout();

    /** 单次上游请求的读取超时（上游检索类工具耗时较长，默认放宽到 60s） */
    @WithDefault("60s")
    Duration readTimeout();

    /** 启动时是否立即拉取上游工具清单 */
    @WithDefault("true")
    boolean loadOnStartup();

    /** 入站鉴权与吊销/限额存储（阶段 2） */
    Auth auth();

    /** 账号默认日限额（阶段 2） */
    Quota quota();

    /**
     * 入站鉴权配置。
     *
     * <p>鉴权由 {@code McpAuthFilter}（Vert.x 平台 filter）执行——MCP 的 {@code /mcp}
     * 不是 JAX-RS 端点，{@code ContainerRequestFilter} 拦不到它。</p>
     */
    interface Auth {

        /**
         * 是否开启入站鉴权。
         *
         * <p>生产默认开启；置 false 即回到阶段 1 的"内测直连"形态（不解析 token、不计费）。</p>
         *
         * @return true 表示要求 Bearer 平台 JWT
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * 管理面（{@code /gateway/status}、{@code /gateway/refresh}）是否要求 {@code sys_admin}。
         *
         * @return true 表示非管理员返回 403
         */
        @WithDefault("true")
        boolean adminGuard();

        /**
         * 吊销/限额存储选型：{@code redis}（默认）或 {@code memory}（仅测试）。
         *
         * <p>对应 {@code McpUsageStore} 的两个 {@code @IfBuildProperty} 实现。</p>
         *
         * @return 存储标识
         */
        @WithDefault("redis")
        String store();
    }

    /**
     * 账号默认日限额。
     *
     * <p>账号未在 Redis {@code mcp:quota:{username}} 单独覆盖时生效；用量统计口径见
     * {@code McpUsageStore}：chars/4 粗估 token，不求精确。</p>
     */
    interface Quota {

        /**
         * 默认每日工具调用次数上限。
         *
         * @return 次数上限
         */
        @WithDefault("2000")
        long dailyCalls();

        /**
         * 默认每日响应字符数上限（chars/4 粗估 token）。
         *
         * @return 字符数上限
         */
        @WithDefault("20000000")
        long dailyChars();
    }

    /**
     * 单个上游 MCP 服务。
     *
     * <p>⚠️ 必须是 {@code interface} 嵌套组，**不能用 record**：SmallRye Config 的集合元素
     * 只支持嵌套接口（官方 config-mappings 指南的 Collections 一节），
     * 用 record 会在启动时报 {@code SRCFG00013: No Converter registered for class ...$Upstream}
     * 并导致配置校验失败、应用起不来（实测踩过）。</p>
     */
    interface Upstream {

        /**
         * 上游名，同时用作工具暴露名前缀；必须唯一。
         *
         * @return 上游名
         */
        String name();

        /**
         * MCP Streamable HTTP 端点（形如 {@code http://host:port/mcp}）。
         *
         * @return 端点地址
         */
        String url();

        /**
         * 上游优先级：数字越小越优先，仅在**同名工具**分组内比较（无重名的工具用不上它）。
         *
         * <p>用于同名工具的**主备路由**（failover）：dev 环境实测 {@code hisi_docs_search}
         * 同时存在于 9802 与 9803，要求"以 9803 为主，9803 不通走 9802"——
         * 给 9803 配 {@code priority: 1}、9802 配 {@code priority: 2} 即可。
         * 未配置时默认 0；同 priority 的候选按配置文件里的声明顺序排。
         * ⚠️ 只在重名分组内比较意味着给不重名的上游随便配 priority 没有任何副作用。</p>
         *
         * @return 优先级，越小越优先
         */
        @WithDefault("0")
        int priority();
    }

    /**
     * 按上游名查找。
     *
     * @param name 上游名
     * @return 命中的上游，未配置时为空
     */
    default Optional<Upstream> findUpstream(String name) {
        return upstreams().stream().filter(u -> u.name().equals(name)).findFirst();
    }
}