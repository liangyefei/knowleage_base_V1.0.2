#!/bin/bash

# ============================================
# PostgreSQL 自动备份脚本
# 建议配合 crontab 定时执行
# ============================================

# 配置
CONTAINER_NAME="postgresql-db"
BACKUP_DIR="/backup/postgresql"
DB_NAME="faraidb"
DB_USER="farai"
RETENTION_DAYS=7
DATE=$(date +%Y%m%d_%H%M%S)

# 创建备份目录
mkdir -p $BACKUP_DIR

# 执行备份（自定义压缩格式，便于 pg_restore 恢复）
echo "开始备份数据库 $DB_NAME ..."
sudo docker exec $CONTAINER_NAME pg_dump -U $DB_USER -F c -b -v $DB_NAME > $BACKUP_DIR/backup_$DATE.dump

if [ $? -eq 0 ]; then
    echo "✓ 备份成功: $BACKUP_DIR/backup_$DATE.dump"
    echo "  文件大小: $(du -h $BACKUP_DIR/backup_$DATE.dump | cut -f1)"

    # 删除过期备份
    find $BACKUP_DIR -name "backup_*.dump" -mtime +$RETENTION_DAYS -delete
    echo "  已清理 $RETENTION_DAYS 天前的过期备份"
else
    echo "✗ 备份失败"
    exit 1
fi

# ============================================
# 定时任务配置说明（crontab -e）：
#   每天凌晨 2 点执行备份
#   0 2 * * * /path/to/scripts/backup.sh >> /var/log/postgresql_backup.log 2>&1
#
# 恢复命令：
#   sudo docker exec -i $CONTAINER_NAME pg_restore -U $DB_USER -d $DB_NAME -v < backup_xxx.dump
# ============================================
