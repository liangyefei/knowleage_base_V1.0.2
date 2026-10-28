# 批量初始化知识库流程
### 1. 前端页面删除知识库

### 2.清空知识库中的3个表
file_info
knowledge
knowledge_file

### 3.清空知识库本地数据(如果第一步顺利执行则不需要)
ls /mnt/d/data/release/kw/knowledge_base/
rm -rf /mnt/d/data/release/kw/knowledge_base/*
cp /mnt/d/data/release/kw/info.db /mnt/d/data/release/kw/knowledge_base/

### 4.准备预处理数据，复制预处理数据原始数据到指定目录
rm -rf /mnt/d/data/release/juicefs/knowledge/prod
cp -r /mnt/d/data/release/knowledge/mobile-intelligent-platform-service/prod /mnt/d/data/release/juicefs/knowledge/
cp -r /mnt/d/data/release/knowledge/mobile-intelligent-platform-service/original_del_repeat /mnt/d/data/release/juicefs/knowledge/prod

### 4.执行初始化命令
curl -X 'POST' 'http://192.168.41.164:10086/mobile-intelligent/knowledge/initKnowledge'  -H 'accept: application/json' -H 'Authorization: 98e3980c-c727-41b7-a960-47e07bb6b90b'

### 5.看初始化结果
docker-compose -p haisi_kw_models_server logs -f

 
