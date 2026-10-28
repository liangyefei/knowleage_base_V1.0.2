# 使用 SCL PostgreSQL 15 官方镜像（CentOS Stream 9 基础）
FROM quay.io/sclorg/postgresql-15-c9s:latest

# 设置时区相关环境变量
ENV TZ=Asia/Shanghai \
    POSTGRESQL_TIMEZONE=Asia/Shanghai \
    PGTZ=Asia/Shanghai

# 切换到 root 安装时区数据并设置时区
USER root
RUN set -e; \
    (microdnf -y install tzdata 2>/dev/null || yum -y install tzdata 2>/dev/null || true); \
    (cp -f /usr/share/zoneinfo/$TZ /etc/localtime 2>/dev/null || true); \
    (echo "$TZ" > /etc/timezone 2>/dev/null || true)

# 恢复 postgres 用户（UID 26）
USER 26

# 暴露端口
EXPOSE 5432

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=5 \
    CMD pg_isready -U postgres || exit 1

# 使用基础镜像的启动命令
CMD ["run-postgresql"]
