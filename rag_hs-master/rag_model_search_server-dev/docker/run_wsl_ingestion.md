# ingestion 容器开发环境（写路径，v2.0）

说明：本文件为**双镜像拆分**模式下的 ingestion 容器（写路径）本地验证。ingestion 容器仅承载入库路由（`create_knowledge_base`/`upload_docs`/`upload_temp_docs`/`delete_docs`）+ Kafka 消费者，通过 `HAISI_RUN_MODE=ingest` 控制，由 `Dockerfile-ingestion` 的 `ENTRYPOINT ["python3", "startup_ingestion.py"]` 自动完成启动。

> 全功能单容器模式见 [run_wsl_all.md](run_wsl_all.md)。生产部署命令见 [run_local_all.md](run_local_all.md)。

## 1. 构建镜像

```bash
# 进入 WSL 环境
wsl -d Ubuntu
cd /mnt/d/Code/haisi/haisi_rag_models_server

# ingestion 镜像（完全沿用原版 Dockerfile 环境，仅改 ENTRYPOINT + ENV HAISI_RUN_MODE=ingest）
sudo docker build -f docker/Dockerfile-ingestion -t haisi-rag-ingestion:v2.0 .
```

## 2. 创建网络并启动 ingestion 容器

```bash
# 1. 创建专用 docker 网络，供 qa 容器通过容器名访问 ingestion API
sudo docker network create haisi-rag-net

# 2. 启动 ingestion 容器：实际 API on 47861:7861（必须映射 7861，否则 qa 无法访问入库 API）
sudo docker run -itd --name haisi-rag-ingestion-test --ipc=host \
  --network haisi-rag-net \
  --restart always \
  -e OPENAI_API_KEY=123456789 -e TOKENIZERS_PARALLELISM=false \
  -p 47861:7861 \
  haisi-rag-ingestion:v2.0
```

端口说明：
| 宿主端口 | 容器端口 | 用途 |
|---|---|---|
| 47861 | 7861 | 入库 API Server（qa 通过 INGESTION_API_URL 调用） |

> ingestion 容器仅起 API（入库路由）+ Kafka 消费者，**不起 WebUI，不开 8501 端口**。
> 容器启动后由 `startup_ingestion.py` 自动起服务，无需手动 `docker exec`。

## 3. 验证路由分离（ingestion 侧）

```bash
# 验证 ingestion 容器：入库路由存在（405），QA 专属路由不存在（404）
sudo docker run --rm --network container:haisi-rag-ingestion-test \
  -v /mnt/d/Code/haisi/haisi_rag_models_server/docker/verify-routes-ingestion.py:/tmp/verify.py \
  --entrypoint python3 haisi-rag-ingestion:v2.0 /tmp/verify.py
```

预期结果：
- `/kw/knowledge_base/create_knowledge_base`、`upload_docs`、`upload_temp_docs`、`delete_docs` 返回 **405**（路由存在，方法需 POST）
- `/kw/chat/file_chat`、`feedback` 返回 **404**（QA 专属路由，ingestion 不挂载）

## 4. 日志验证

```bash
# 应出现：Kafka Comm Server Success (mode=ingest) + API server 4 workers uvloop + Application startup complete
docker logs -f haisi-rag-ingestion-test --tail 300
```

## 5. 清理

```bash
# 仅清理 ingestion 容器（qa 容器请见 run_wsl_qa.md 清理）
docker rm -f haisi-rag-ingestion-test

# 若 qa 容器也已清理，删除共享网络
docker network rm haisi-rag-net
```

## 6. 相关文档

- [run_wsl_qa.md](run_wsl_qa.md)：qa 容器（读路径 + WebUI）本地验证，需在本容器启动后运行
- [run_wsl_all.md](run_wsl_all.md)：全功能单容器模式
- [run_local_all.md](run_local_all.md)：生产环境双镜像部署命令
