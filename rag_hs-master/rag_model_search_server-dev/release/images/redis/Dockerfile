# 使用华为云镜像加速的 Redis 7 Alpine 镜像
FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/redis:7-alpine

# 设置环境变量
ENV TZ=Asia/Shanghai \
    REDIS_PASSWORD=farai123 \
    REDIS_PORT=6379

# 安装时区数据并设置时区
RUN apk add --no-cache tzdata curl \
    && cp /usr/share/zoneinfo/$TZ /etc/localtime \
    && echo $TZ > /etc/timezone

# 创建数据目录
RUN mkdir -p /data

# 复制 Redis 配置文件
COPY redis.conf /etc/redis/redis.conf

# 暴露端口
EXPOSE 6379

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
    CMD redis-cli -a "$REDIS_PASSWORD" ping | grep -q PONG || exit 1

# 启动 Redis
CMD ["redis-server", "/etc/redis/redis.conf"]
