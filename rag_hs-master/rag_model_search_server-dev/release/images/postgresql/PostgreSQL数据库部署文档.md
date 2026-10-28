# PostgreSQL 15 数据库部署文档

## 📋 目录

* 1\. 概述
* 2\. 环境信息
* 3\. 部署方式
* 4\. 配置文件
* 5\. 部署步骤
* 6\. 连接数据库
* 7\. 远程访问配置
* 8\. 常用管理命令
* 9\. 备份与恢复
* 10\. 安全建议
* 11\. 故障排查
* 12\. 维护与监控

***

## 1. 概述

本项目使用 Docker Compose 部署 PostgreSQL 15 数据库服务，基于官方 SCL（Software Collections）镜像 `quay.io/sclorg/postgresql-15-c9s:latest`。

### 1.1 技术栈

| **组件**         | **版本**                    | **说明** |
| -------------- | ------------------------- | ------ |
| PostgreSQL     | 15.x                      | 关系型数据库 |
| Docker         | 20.10+                    | 容器运行时  |
| Docker Compose | 2.0+                      | 容器编排工具 |
| 宿主机系统          | Windows 11 / WSL2 / Linux | 部署环境   |

***

## 2. 环境信息

### 2.1 数据库连接信息

| **配置项** | **值**            | **说明**          |
| ------- | ---------------- | --------------- |
| 宿主机 IP  | `192.168.80.250` | 根据实际环境修改        |
| 容器端口    | `5432`           | PostgreSQL 默认端口 |
| 映射端口    | `16602`          | 对外暴露端口（避免端口冲突）  |
| 数据库名称   | `faraidb`        | 默认创建的数据库        |
| 普通用户名   | `farai`          | 应用程序使用的用户       |
| 普通用户密码  | `farai123`       | **建议生产环境修改**    |
| 管理员用户名  | `postgres`       | 超级管理员           |
| 管理员密码   | `farai123`       | **建议生产环境修改**    |

### 2.2 数据持久化

| **配置项** | **值**                 |
| ------- | --------------------- |
| 数据卷名称   | `postgresql_data`     |
| 容器内数据目录 | `/var/lib/pgsql/data` |
| 宿主机数据目录 | Docker Volume 管理      |

***

## 3. 部署方式

本项目支持以下三种部署方式，推荐使用 **Docker Compose**：

| **方式**               | **适用场景**   | **复杂度** |
| -------------------- | ---------- | ------- |
| Docker 命令            | 快速测试、开发环境  | ⭐       |
| Docker Compose       | 开发/测试/生产环境 | ⭐⭐      |
| Kubernetes/OpenShift | 大规模生产集群    | ⭐⭐⭐⭐    |

***

## 4. 配置文件

### 4.1 Docker Compose 配置文件

创建 `docker-compose.yml`：

yaml

```
services:
  postgresql:
    # 使用 SCL PostgreSQL 15 镜像
    image: quay.io/sclorg/postgresql-15-c9s:latest
    # 容器名称
    container_name: postgresql-db
    # 自动重启策略
    restart: unless-stopped
    # 环境变量配置
    environment:
      # 普通数据库用户名
      POSTGRESQL_USER: farai
      # 普通用户密码（生产环境请修改）
      POSTGRESQL_PASSWORD: farai123
      # 默认数据库名称
      POSTGRESQL_DATABASE: faraidb
      # 管理员 postgres 用户密码（生产环境请修改）
      POSTGRESQL_ADMIN_PASSWORD: farai123
    # 端口映射 [宿主机端口]:[容器端口]
    ports:
      - "16602:5432"
    # 数据持久化卷
    volumes:
      - postgresql_data:/var/lib/pgsql/data
    # 网络配置
    networks:
      - postgresql_network
    # 资源限制（可选）
    deploy:
      resources:
        limits:
          memory: 2G
          cpus: '2'
        reservations:
          memory: 512M
          cpus: '0.5'

# 数据卷定义
volumes:
  postgresql_data:
    driver: local

# 网络定义
networks:
  postgresql_network:
    driver: bridge
```

### 4.2 环境变量说明

| **变量名**                                | **必需** | **说明**            |
| -------------------------------------- | ------ | ----------------- |
| `POSTGRESQL_USER`                      | 可选     | 自定义数据库用户名         |
| `POSTGRESQL_PASSWORD`                  | 可选     | 自定义用户密码           |
| `POSTGRESQL_DATABASE`                  | 可选     | 默认创建的数据库名         |
| `POSTGRESQL_ADMIN_PASSWORD`            | **必需** | 管理员 postgres 用户密码 |
| `POSTGRESQL_MAX_CONNECTIONS`           | 可选     | 最大连接数，默认 100      |
| `POSTGRESQL_MAX_PREPARED_TRANSACTIONS` | 可选     | 最大预准备事务数          |
| `POSTGRESQL_SHARED_BUFFERS`            | 可选     | 共享缓冲区大小，默认 128MB  |
| `POSTGRESQL_EFFECTIVE_CACHE_SIZE`      | 可选     | 有效缓存大小            |
| `POSTGRESQL_MAINTENANCE_WORK_MEM`      | 可选     | 维护工作内存            |
| `POSTGRESQL_CHECKPOINT_SEGMENTS`       | 可选     | 检查点分段数            |

***

## 5. 部署步骤

### 5.1 前置条件

bash

```
# 检查 Docker 是否安装
docker --version

# 检查 Docker Compose 是否安装
docker compose version

# 如果未安装，参考以下命令安装
# Ubuntu/Debian
sudo apt update
sudo apt install docker.io docker-compose-plugin

# CentOS/RHEL
sudo yum install docker docker-compose-plugin

# 将用户加入 docker 组（避免 sudo）
sudo usermod -aG docker $USER
newgrp docker
```

### 5.2 创建项目目录

bash

```
# 创建部署目录
mkdir -p /data/Deploy/postgresql
cd /data/Deploy/postgresql
```

### 5.3 创建配置文件

将上述 `docker-compose.yml` 内容保存到当前目录。

### 5.4 启动服务

bash

```
# 启动 PostgreSQL 服务（后台运行）
docker compose up -d

# 查看启动日志
docker compose logs -f

# 等待日志显示 "accepting connections" 表示启动成功
# 按 Ctrl+C 退出日志查看
```

### 5.5 验证部署

bash

```
# 查看容器运行状态
docker compose ps

# 查看容器详细信息
docker ps | grep postgresql

# 测试数据库连接（使用容器内客户端）
docker exec -it postgresql-db psql -U farai -d faraidb -c "SELECT version();"

# 预期输出：
# PostgreSQL 15.x on x86_64-pc-linux-gnu, compiled by gcc ...
```

### 5.6 停止与删除服务

bash

```
# 停止容器（保留数据）
docker compose stop

# 启动已停止的容器
docker compose start

# 重启容器
docker compose restart

# 停止并删除容器（保留数据卷）
docker compose down

# 停止并删除容器及数据卷（⚠️ 会删除所有数据）
docker compose down -v
```

***

## 6. 连接数据库

### 6.1 本地连接（宿主机）

#### 方式一：使用容器内 psql 客户端

bash

```
# 以普通用户 farai 连接
docker exec -it postgresql-db psql -U farai -d faraidb

# 以管理员 postgres 连接
docker exec -it postgresql-db psql -U postgres

# 执行单条 SQL 并退出
docker exec -it postgresql-db psql -U farai -d faraidb -c "SELECT current_database();"
```

#### 方式二：从宿主机使用 psql 客户端

bash

```
# 需要宿主机安装 postgresql-client
psql -h localhost -p 16602 -U farai -d faraidb
# 密码: farai123
```

### 6.2 远程连接（其他电脑）

bash

```
# 基本连接命令
psql -h 192.168.80.250 -p 16602 -U farai -d faraidb
# 密码: farai123

# 使用环境变量指定密码（不推荐）
PGPASSWORD=farai123 psql -h 192.168.80.250 -p 16602 -U farai -d faraidb
```

### 6.3 连接字符串（应用程序使用）

yaml

```
# JDBC URL
jdbc:postgresql://192.168.80.250:16602/faraidb

# DSN (ODBC)
postgresql://farai:farai123@192.168.80.250:16602/faraidb

# Python SQLAlchemy
postgresql+psycopg2://farai:farai123@192.168.80.250:16602/faraidb

# Node.js (pg 模块)
postgresql://farai:farai123@192.168.80.250:16602/faraidb

# Go (lib/pq)
host=192.168.80.250 port=16602 user=farai password=farai123 dbname=faraidb sslmode=disable

# Django
DATABASES = {
    'default': {
        'ENGINE': 'django.db.backends.postgresql',
        'NAME': 'faraidb',
        'USER': 'farai',
        'PASSWORD': 'farai123',
        'HOST': '192.168.80.250',
        'PORT': '16602',
    }
}

# Spring Boot (application.yml)
spring:
  datasource:
    url: jdbc:postgresql://192.168.80.250:16602/faraidb
    username: farai
    password: farai123
```

### 6.4 图形化管理工具

| **工具**   | **下载地址**                                                                   | **特点**            |
| -------- | -------------------------------------------------------------------------- | ----------------- |
| DBeaver  | ​[https://dbeaver.io](https://dbeaver.io/)​                                | 免费、跨平台、功能全面       |
| pgAdmin  | ​[https://www.pgadmin.org](https://www.pgadmin.org/)​                      | PostgreSQL 官方工具   |
| HeidiSQL | ​[https://www.heidisql.com](https://www.heidisql.com/)​                    | Windows、轻量级       |
| DataGrip | ​[https://www.jetbrains.com/datagrip](https://www.jetbrains.com/datagrip)​ | JetBrains 出品、功能强大 |

***

## 7. 远程访问配置

默认情况下，PostgreSQL 只允许本地连接。如需允许其他电脑访问，需进行以下配置。

### 7.1 修改监听地址

bash

```
# 修改 postgresql.conf
docker exec -it postgresql-db sed -i "s/#listen_addresses = 'localhost'/listen_addresses = '*'/g" /var/lib/pgsql/data/userdata/postgresql.conf

# 验证修改
docker exec postgresql-db cat /var/lib/pgsql/data/userdata/postgresql.conf | grep listen_addresses
```

### 7.2 配置客户端认证

bash

```
# 允许所有 IP 访问（测试环境）
docker exec -it postgresql-db bash -c "echo 'host all all 0.0.0.0/0 md5' >> /var/lib/pgsql/data/userdata/pg_hba.conf"

# 限制特定网段（生产环境推荐）
docker exec -it postgresql-db bash -c "echo 'host all all 192.168.81.0/24 md5' >> /var/lib/pgsql/data/userdata/pg_hba.conf"

# 验证配置
docker exec postgresql-db cat /var/lib/pgsql/data/userdata/pg_hba.conf | tail -5
```

### 7.3 重启容器使配置生效

bash

```
docker compose restart
```

### 7.4 配置防火墙（宿主机）

#### Windows 防火墙

powershell

```
# 以管理员身份运行 PowerShell
New-NetFirewallRule -DisplayName "PostgreSQL 16602" -Direction Inbound -Protocol TCP -LocalPort 16602 -Action Allow
```

#### Linux 防火墙

bash

```
# Ubuntu/Debian (ufw)
sudo ufw allow 16602/tcp
sudo ufw reload

# CentOS/RHEL (firewalld)
sudo firewall-cmd --permanent --add-port=16602/tcp
sudo firewall-cmd --reload
```

### 7.5 一键配置脚本

创建 `scripts/setup_remote.sh`：

bash

```
#!/bin/bash
# PostgreSQL 远程访问配置脚本

echo "🔧 配置 PostgreSQL 远程访问..."

# 修改监听地址
echo "1. 配置监听地址..."
docker exec -it postgresql-db sed -i "s/#listen_addresses = 'localhost'/listen_addresses = '*'/g" /var/lib/pgsql/data/userdata/postgresql.conf

# 添加远程访问权限
echo "2. 配置访问权限..."
docker exec -it postgresql-db bash -c "echo 'host all all 192.168.81.0/24 md5' >> /var/lib/pgsql/data/userdata/pg_hba.conf"

# 重启容器
echo "3. 重启容器..."
docker compose restart

echo "⏳ 等待服务启动..."
sleep 5

echo "✅ 配置完成！"
echo ""
echo "📌 连接信息："
echo "   主机: 192.168.80.250"
echo "   端口: 16602"
echo "   数据库: faraidb"
echo "   用户: farai"
echo "   密码: farai123"
```

***

## 8. 常用管理命令

### 8.1 容器管理

bash

```
# 查看所有容器状态
docker compose ps

# 查看容器日志
docker compose logs          # 全部日志
docker compose logs -f       # 实时日志
docker compose logs -f postgresql  # 特定服务日志

# 进入容器终端
docker exec -it postgresql-db bash

# 查看容器资源使用
docker stats postgresql-db
```

### 8.2 数据库管理

bash

```
# 查看数据库列表
docker exec -it postgresql-db psql -U postgres -c "\l"

# 查看当前连接
docker exec -it postgresql-db psql -U postgres -c "SELECT * FROM pg_stat_activity;"

# 查看表空间
docker exec -it postgresql-db psql -U postgres -c "\db"

# 查看用户列表
docker exec -it postgresql-db psql -U postgres -c "\du"

# 切换数据库
docker exec -it postgresql-db psql -U farai -d faraidb
```

### 8.3 数据库维护

bash

```
# 分析数据库（更新统计信息）
docker exec -it postgresql-db psql -U postgres -c "ANALYZE;"

# 清理数据库（回收空间）
docker exec -it postgresql-db psql -U postgres -c "VACUUM;"

# 清理并分析
docker exec -it postgresql-db psql -U postgres -c "VACUUM ANALYZE;"

# 重建索引
docker exec -it postgresql-db psql -U postgres -c "REINDEX DATABASE faraidb;"
```

### 8.4 用户与权限管理

bash

```
# 创建新用户
docker exec -it postgresql-db psql -U postgres -c "CREATE USER newuser WITH PASSWORD 'newpassword';"

# 创建数据库并指定所有者
docker exec -it postgresql-db psql -U postgres -c "CREATE DATABASE newdb OWNER newuser;"

# 授予所有权限
docker exec -it postgresql-db psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE newdb TO newuser;"

# 修改用户密码
docker exec -it postgresql-db psql -U postgres -c "ALTER USER farai WITH PASSWORD 'newpassword';"
```

***

## 9. 备份与恢复

### 9.1 备份数据库

bash

```
# 备份整个数据库（自定义格式）
docker exec postgresql-db pg_dump -U farai -F c -b -v faraidb > backup_$(date +%Y%m%d_%H%M%S).dump

# 备份为 SQL 文本（可读）
docker exec postgresql-db pg_dump -U farai -F p -b -v faraidb > backup_$(date +%Y%m%d_%H%M%S).sql

# 备份所有数据库
docker exec postgresql-db pg_dumpall -U postgres > backup_all_$(date +%Y%m%d).sql

# 备份特定表
docker exec postgresql-db pg_dump -U farai -t table_name faraidb > backup_table.sql

# 压缩备份
docker exec postgresql-db pg_dump -U farai faraidb | gzip > backup_$(date +%Y%m%d).sql.gz
```

### 9.2 恢复数据库

bash

```
# 恢复 SQL 文件
cat backup.sql | docker exec -i postgresql-db psql -U farai faraidb

# 恢复自定义格式备份
docker exec -i postgresql-db pg_restore -U farai -d faraidb -v < backup.dump

# 恢复压缩文件
gunzip -c backup.sql.gz | docker exec -i postgresql-db psql -U farai faraidb

# 恢复并清理现有数据（⚠️ 危险操作）
docker exec -i postgresql-db psql -U farai -d faraidb -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" && \
cat backup.sql | docker exec -i postgresql-db psql -U farai faraidb
```

### 9.3 自动备份脚本

创建 `scripts/backup.sh`：

bash

```
#!/bin/bash
# PostgreSQL 自动备份脚本

# 配置
BACKUP_DIR="/backup/postgresql"
DB_NAME="faraidb"
DB_USER="farai"
RETENTION_DAYS=7
DATE=$(date +%Y%m%d_%H%M%S)

# 创建备份目录
mkdir -p $BACKUP_DIR

# 执行备份
echo "开始备份数据库 $DB_NAME ..."
docker exec postgresql-db pg_dump -U $DB_USER -F c -b -v $DB_NAME > $BACKUP_DIR/backup_$DATE.dump

if [ $? -eq 0 ]; then
    echo "✅ 备份成功: $BACKUP_DIR/backup_$DATE.dump"
    
    # 删除过期备份
    find $BACKUP_DIR -name "backup_*.dump" -mtime +$RETENTION_DAYS -delete
    echo "已删除 $RETENTION_DAYS 天前的备份"
else
    echo "❌ 备份失败"
    exit 1
fi
```

添加到 crontab（定时任务）：

bash

```
# 每天凌晨2点执行备份
crontab -e
# 添加以下行
0 2 * * * /path/to/scripts/backup.sh >> /var/log/postgresql_backup.log 2>&1
```

***

## 10. 安全建议

### 10.1 密码安全

bash

```
# 生产环境请使用强密码
docker exec -it postgresql-db psql -U postgres -c "
ALTER USER farai WITH PASSWORD 'YourStrongP@ssw0rd#2026!';
ALTER USER postgres WITH PASSWORD 'AdminStrongP@ssw0rd#2026!';
"
```

**强密码要求**：

* 长度 ≥ 12 字符
* 包含大小写字母
* 包含数字
* 包含特殊字符 (!@#\$%^&\*)

### 10.2 网络访问控制

bash

```
# 只允许特定 IP 访问（生产环境）
docker exec -it postgresql-db bash -c "echo 'host all all 192.168.81.100/32 md5' >> /var/lib/pgsql/data/userdata/pg_hba.conf"
docker exec -it postgresql-db bash -c "echo 'host all all 10.0.0.0/8 md5' >> /var/lib/pgsql/data/userdata/pg_hba.conf"

# 拒绝所有（默认）
# 不要使用 0.0.0.0/0，使用具体的 IP 范围
```

### 10.3 SSL/TLS 加密（生产环境必需）

bash

```
# 生成 SSL 证书（自签名）
docker exec -it postgresql-db openssl req -new -text -nodes -subj "/CN=postgresql-server" \
  -out /var/lib/pgsql/data/server.req

docker exec -it postgresql-db openssl rsa -in /var/lib/pgsql/data/privkey.pem -out /var/lib/pgsql/data/server.key

docker exec -it postgresql-db openssl req -x509 -in /var/lib/pgsql/data/server.req -text -key /var/lib/pgsql/data/server.key -out /var/lib/pgsql/data/server.crt

# 修改权限
docker exec -it postgresql-db chmod 600 /var/lib/pgsql/data/server.key
docker exec -it postgresql-db chmod 600 /var/lib/pgsql/data/server.crt

# 启用 SSL
docker exec -it postgresql-db sed -i "s/#ssl = off/ssl = on/g" /var/lib/pgsql/data/userdata/postgresql.conf
docker exec -it postgresql-db sed -i "s/#ssl_cert_file = 'server.crt'/ssl_cert_file = 'server.crt'/g" /var/lib/pgsql/data/userdata/postgresql.conf
docker exec -it postgresql-db sed -i "s/#ssl_key_file = 'server.key'/ssl_key_file = 'server.key'/g" /var/lib/pgsql/data/userdata/postgresql.conf

docker compose restart
```

### 10.4 资源限制

在 `docker-compose.yml` 中添加：

yaml

```
services:
  postgresql:
    # ... 其他配置
    deploy:
      resources:
        limits:
          memory: 2G
          cpus: '2'
        reservations:
          memory: 512M
          cpus: '0.5'
```

### 10.5 审计日志

bash

```
# 启用查询日志
docker exec -it postgresql-db sed -i "s/#log_statement = 'none'/log_statement = 'ddl'/g" /var/lib/pgsql/data/userdata/postgresql.conf

# 启用连接日志
docker exec -it postgresql-db sed -i "s/#log_connections = off/log_connections = on/g" /var/lib/pgsql/data/userdata/postgresql.conf

# 启用断开连接日志
docker exec -it postgresql-db sed -i "s/#log_disconnections = off/log_disconnections = on/g" /var/lib/pgsql/data/userdata/postgresql.conf

docker compose restart
```

***

## 11. 故障排查

### 11.1 容器启动失败

bash

```
# 查看详细日志
docker compose logs --tail=100

# 检查容器状态
docker ps -a | grep postgresql

# 检查端口占用
sudo netstat -tlnp | grep 16602
# 或 Windows PowerShell
netstat -ano | findstr 16602
```

### 11.2 连接失败

bash

```
# 检查容器是否运行
docker ps | grep postgresql

# 检查端口映射
docker port postgresql-db

# 测试端口连通性
telnet 192.168.80.250 16602
# 或
nc -zv 192.168.80.250 16602

# 检查防火墙
# Windows
netsh advfirewall show allprofiles

# Linux
sudo ufw status
sudo firewall-cmd --list-all
```

### 11.3 认证失败

bash

```
# 检查用户是否存在
docker exec postgresql-db psql -U postgres -c "\du"

# 重置用户密码
docker exec -it postgresql-db psql -U postgres -c "ALTER USER farai WITH PASSWORD 'farai123';"

# 检查 pg_hba.conf 配置
docker exec postgresql-db cat /var/lib/pgsql/data/userdata/pg_hba.conf
```

### 11.4 数据目录权限问题

bash

```
# 检查数据目录权限
docker exec postgresql-db ls -la /var/lib/pgsql/data/

# 修复权限（如有问题）
docker exec postgresql-db chown -R postgres:postgres /var/lib/pgsql/data/
docker exec postgresql-db chmod 700 /var/lib/pgsql/data/
```

### 11.5 磁盘空间不足

bash

```
# 查看磁盘使用
docker exec postgresql-db df -h

# 查看数据目录大小
docker exec postgresql-db du -sh /var/lib/pgsql/data/

# 清理日志
docker exec postgresql-db pg_ctl -D /var/lib/pgsql/data/ rotate

# 清理 WAL 日志
docker exec postgresql-db psql -U postgres -c "CHECKPOINT;"
docker exec postgresql-db psql -U postgres -c "SELECT pg_switch_wal();"
```

***

## 12. 维护与监控

### 12.1 性能监控

bash

```
# 查看数据库大小
docker exec postgresql-db psql -U postgres -c "
SELECT pg_database.datname, 
       pg_size_pretty(pg_database_size(pg_database.datname)) AS size 
FROM pg_database 
ORDER BY pg_database_size(pg_database.datname) DESC;
"

# 查看表大小
docker exec postgresql-db psql -U farai -d faraidb -c "
SELECT schemaname, tablename, 
       pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS total_size
FROM pg_tables 
WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC
LIMIT 20;
"

# 查看索引大小
docker exec postgresql-db psql -U farai -d faraidb -c "
SELECT schemaname, tablename, indexname,
       pg_size_pretty(pg_relation_size(indexrelid)) AS index_size
FROM pg_stat_user_indexes
ORDER BY pg_relation_size(indexrelid) DESC
LIMIT 20;
"

# 查看连接数
docker exec postgresql-db psql -U postgres -c "
SELECT count(*) AS total_connections,
       sum(CASE WHEN state = 'active' THEN 1 ELSE 0 END) AS active_connections
FROM pg_stat_activity;
"

# 查看锁等待
docker exec postgresql-db psql -U postgres -c "
SELECT pid, locktype, mode, granted, 
       now() - pg_stat_activity.query_start AS waiting_time,
       pg_stat_activity.query
FROM pg_locks 
JOIN pg_stat_activity ON pg_locks.pid = pg_stat_activity.pid
WHERE NOT granted;
"
```

### 12.2 健康检查

bash

```
# 检查 PostgreSQL 健康状态
docker exec postgresql-db pg_isready -U postgres

# 预期输出: /var/run/postgresql:5432 - accepting connections
```

### 12.3 监控指标（Prometheus + Grafana）

如需接入 Prometheus 监控，可部署 postgres\_exporter：

bash

```
# 部署 postgres_exporter
docker run -d \
  --name postgres_exporter \
  -p 9187:9187 \
  -e DATA_SOURCE_NAME="postgresql://farai:farai123@192.168.80.250:16602/faraidb?sslmode=disable" \
  prometheuscommunity/postgres-exporter
```

***

## 13. 版本升级

### 13.1 小版本升级（补丁版本）

bash

```
# 拉取最新镜像
docker pull quay.io/sclorg/postgresql-15-c9s:latest

# 停止并删除旧容器（保留数据卷）
docker compose down

# 启动新容器（数据卷自动挂载）
docker compose up -d
```

### 13.2 大版本升级（如 15 → 16）

bash

```
# 1. 备份当前数据库
docker exec postgresql-db pg_dumpall -U postgres > backup_all.sql

# 2. 停止旧容器
docker compose down

# 3. 修改 docker-compose.yml 中的镜像版本
# image: quay.io/sclorg/postgresql-16-c9s:latest

# 4. 启动新容器
docker compose up -d

# 5. 恢复数据
cat backup_all.sql | docker exec -i postgresql-db psql -U postgres
```

***

## 14. 参考资料

| **资源**            | **链接**                                                                                                                           |
| ----------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| PostgreSQL 官方文档   | ​[https://www.postgresql.org/docs/15/](https://www.postgresql.org/docs/15/)​                                                     |
| Docker 官方文档       | ​[https://docs.docker.com/](https://docs.docker.com/)​                                                                           |
| Docker Compose 文档 | ​[https://docs.docker.com/compose/](https://docs.docker.com/compose/)​                                                           |
| SCL PostgreSQL 镜像 | ​[https://github.com/sclorg/postgresql-container](https://github.com/sclorg/postgresql-container)​                               |
| PostgreSQL 镜像文档   | ​[https://github.com/sclorg/postgresql-container/tree/master/15](https://github.com/sclorg/postgresql-container/tree/master/15)​ |

***

## 15. 版本记录

| **版本** | **日期**     | **作者**          | **变更说明**    |
| ------ | ---------- | --------------- | ----------- |
| v1.0   | 2026-07-30 | Deployment Team | 初始版本，完成基础部署 |

***

## 附录 A：快速部署命令汇总

bash

```
# 1. 克隆/创建项目目录
mkdir -p /data/Deploy/postgresql
cd /data/Deploy/postgresql

# 2. 创建 docker-compose.yml（见第四节）

# 3. 启动服务
docker compose up -d

# 4. 验证
docker exec -it postgresql-db psql -U farai -d faraidb -c "SELECT version();"

# 5. 连接数据库
docker exec -it postgresql-db psql -U farai -d faraidb

# 6. 查看日志
docker compose logs -f

# 7. 停止服务
docker compose down
```

***

## 附录 B：环境变量完整列表

bash

```
# 必需变量（必须设置 POSTGRESQL_ADMIN_PASSWORD）
POSTGRESQL_ADMIN_PASSWORD=admin_password

# 用户与数据库配置
POSTGRESQL_USER=db_user
POSTGRESQL_PASSWORD=db_password
POSTGRESQL_DATABASE=db_name

# 性能调优
POSTGRESQL_MAX_CONNECTIONS=100
POSTGRESQL_MAX_PREPARED_TRANSACTIONS=0
POSTGRESQL_SHARED_BUFFERS=128MB
POSTGRESQL_EFFECTIVE_CACHE_SIZE=4GB
POSTGRESQL_MAINTENANCE_WORK_MEM=64MB
POSTGRESQL_CHECKPOINT_SEGMENTS=3

# 其他配置
POSTGRESQL_TIMEZONE=Etc/UTC
POSTGRESQL_PG_EXTENSIONS=uuid-ossp,postgis
```

***

**文档结束**

如有问题，请联系运维团队或提交 Issue。
