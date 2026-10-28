# qa 容器开发环境（读路径 + WebUI，v2.0）

说明：本文件为**双镜像拆分**模式下的 qa 容器（读路径）本地验证。qa 容器承载问答路由（`file_chat`/`feedback`/`list_knowledge_bases`）+ WebUI，通过 `HAISI_RUN_MODE=qa` 控制，由 `Dockerfile-qa` 的 `ENTRYPOINT ["python3", "startup_qa.py"]` 自动完成启动，跳过 Kafka 消费者。

> **前置条件**：ingestion 容器已按 [run_wsl_ingestion.md](run_wsl_ingestion.md) 启动（共享 `haisi-rag-net` 网络与 `haisi-rag-ingestion-test` 容器名），否则 `file_chat` 调 `upload_temp_docs` 会失败。

> 全功能单容器模式见 [run_wsl_all.md](run_wsl_all.md)。生产部署命令见 [run_local_all.md](run_local_all.md)。

## 1. 构建镜像

```bash
# 进入 WSL 环境
wsl -d Ubuntu
cd /mnt/d/Code/haisi/haisi_rag_models_server

# qa 镜像（用 requirements-qa.txt 瘦身版，去掉 unstructured/torch/kafka-python 等 22 个重包）
sudo docker build -f docker/Dockerfile-qa -t haisi-rag-qa:v2.0 .
```

## 2. 启动 qa 容器

```bash
# qa 容器：API on 37862:7861 + WebUI on 38502:8501
# INGESTION_API_URL 用容器名访问（同 docker network 内 DNS 解析，避免 127.0.0.1 跨容器不通）
sudo docker run -itd --name haisi-rag-qa-test --ipc=host \
  --network haisi-rag-net \
  --restart always \
  -e OPENAI_API_KEY=123456789 -e TOKENIZERS_PARALLELISM=false \
  -e INGESTION_API_URL=http://haisi-rag-ingestion-test:7861/kw \
  -p 38502:8501 -p 37862:7861 \
  haisi-rag-qa:v2.0
```

端口说明：
| 宿主端口 | 容器端口 | 用途 |
|---|---|---|
| 37862 | 7861 | 问答 API Server（对外） |
| 38502 | 8501 | WebUI (Streamlit，对外) |

> qa 容器起 API（问答路由）+ WebUI，跳过 Kafka 消费者。
> 容器启动后由 `startup_qa.py` 自动起服务，无需手动 `docker exec`。

## 3. 验证路由分离（qa 侧）

```bash
# 验证 qa 容器：问答路由存在（405/200），入库路由不存在（404）
sudo docker run --rm --network container:haisi-rag-qa-test \
  -v /mnt/d/Code/haisi/haisi_rag_models_server/docker/verify-routes-qa.py:/tmp/verify.py \
  --entrypoint python3 haisi-rag-qa:v2.0 /tmp/verify.py
```

预期结果：
- `/kw/chat/file_chat`、`feedback` 返回 **405**（路由存在，方法需 POST）
- `/kw/knowledge_base/list_knowledge_bases` 返回 **200**
- `create_knowledge_base`、`upload_docs`、`upload_temp_docs`、`delete_docs` 返回 **404**（入库路由，qa 不挂载）

## 4. 联调验证（跨容器）

qa 的 `file_chat` 内部会调 ingestion 的 `upload_temp_docs`，验证 `INGESTION_API_URL` 已配通：

```bash
# 在宿主调用 qa 的 file_chat（qa 内部路由到 ingestion 的 upload_temp_docs）
curl -s -X POST http://127.0.0.1:37862/kw/chat/file_chat \
  -H "Content-Type: application/json" \
  -d '{"question":"测试","local_docs":"[]"}' | python3 -m json.tool
```

若返回正常对话结果或结构化响应（而非 500/Connection Error），说明 qa → ingestion 跨容器路由已打通。

## 5. 日志验证

```bash
# 应出现：HAISI_RUN_MODE=qa，跳过 KafkaCommServer 启动 + Uvicorn running on 7861 + Streamlit on 8501
docker logs -f haisi-rag-qa-test --tail 300
```

## 6. 清理

```bash
# 仅清理 qa 容器（ingestion 容器与共享网络请见 run_wsl_ingestion.md 清理）
docker rm -f haisi-rag-qa-test
```

## 7. 相关文档

- [run_wsl_ingestion.md](run_wsl_ingestion.md)：ingestion 容器（写路径）本地验证，本容器的前置依赖
- [run_wsl_all.md](run_wsl_all.md)：全功能单容器模式
- [run_local_all.md](run_local_all.md)：生产环境双镜像部署命令
