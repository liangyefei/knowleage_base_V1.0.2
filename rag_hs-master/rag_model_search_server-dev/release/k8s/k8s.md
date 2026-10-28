
## 部署说明

知识库入库 + 问答双服务部署，部署到 `default` namespace，复用 v1.0.6 生产已有资源。

### 前置条件

**集群与命名空间：**
- CCE 集群已开通，`default` namespace 默认存在

**复用 v1.0.6 生产已有资源（须预先存在，不在本清单管理）：**

| 资源类型 | 名称 | 用途 |
|---|---|---|
| Secret | `default-secret` | CCE SWR 镜像拉取密钥（default ns 内置） |
| ConfigMap | `rag-models-config` | params/\*.yaml 运行时覆盖（basic_config / kb_config / model_config / prompt_templates / server_config） |
| PVC | `pvc-kb-files-release` | /kb-files SFS 共享卷（入库写文件） |
| PVC | `pvc-efs-kw-release` | /kw 日志 + kb 数据持久卷 |

**镜像已推送至 SWR：**
- `swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-rag-ingestion:v2.0`（含完整解析栈）
- `swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-rag-qa:v2.0`（瘦身版，去 libreoffice/unstructured/tree-sitter/kafka-python）

### 资源清单

| 文件 | 资源 | 说明 |
|---|---|---|
| `configmap.yaml` | ConfigMap `haisi-rag-shared-config` | 共享环境变量（HAISI_KAFKA_\*、UVICORN_LOG_LEVEL、TOKENIZERS_PARALLELISM 等） |
| `ingestion.yaml` | Deployment + Service `haisi-rag-ingestion` | 入库写路径，Kafka 驱动，不接 HPA |
| `qa.yaml` | Deployment + Service + HPA `haisi-rag-qa` | 问答读路径 + WebUI，按 CPU 横向扩 3-10 副本 |

> 注：清单不含 `namespace.yaml` 和 `pvc.yaml`（复用 default ns + 生产已有 PVC）。

### 部署命令

```bash
# 方式一：kustomize 一键 apply
kubectl apply -k release/k8s/

# 方式二：先渲染核对再 apply（便于人工检查）
kubectl kustomize release/k8s/ | kubectl apply -f -
```

**更新配置后滚动重启（ConfigMap 变更不会自动触发 Pod 重建）：**

```bash
# 更新共享环境变量
kubectl apply -f release/k8s/configmap.yaml

# 触发 Deployment 滚动重启（让新 envFrom 生效）
kubectl rollout restart deploy/haisi-rag-ingestion -n default
kubectl rollout restart deploy/haisi-rag-qa -n default
```

### 端口映射

| Service | 端口名 | Service port | → targetPort (containerPort) | 说明 |
|---|---|---|---|---|
| `haisi-rag-qa` | api | 47861 | 7861 | 与生产 v1.0.6 一致，外部调用方无缝切换 |
| `haisi-rag-qa` | webui | 48501 | 8501 | 与生产 v1.0.6 一致 |
| `haisi-rag-ingestion` | api | 47860 | 7861 | 生产 47861 - 1，独立端口避免冲突 |

> containerPort（7861/8501）是 app 实际监听端口，与生产 targetPort 一致；Service port 是集群内访问端口。
> qa 调 ingestion 走 `http://haisi-rag-ingestion:47860/kw`（见 qa.yaml `INGESTION_API_URL`）。

### 验证部署

```bash
# 查看 Pod（default namespace）
kubectl get pods -n default -l app.kubernetes.io/part-of=haisi-rag

# 查看 Service 与端口
kubectl get svc -n default | grep haisi-rag

# 查看 HPA 状态（qa）
kubectl get hpa -n default haisi-rag-qa

# 查看日志
kubectl logs -n default <pod-name> -c rag-ingestion
kubectl logs -n default <pod-name> -c rag-qa

# 健康检查（Pod 内）
kubectl exec -n default <pod-name> -- curl -s http://localhost:7861/kw/health

# 滚动发布状态
kubectl rollout status deploy/haisi-rag-ingestion -n default
kubectl rollout status deploy/haisi-rag-qa -n default
```

### 注意事项

- **params 覆盖须预先存在 ConfigMap `rag-models-config`**：5 个 params 文件由 subPath 挂载到 `/workspace/haisi_rag_models_server/params/`，缺失会导致 Pod 启动失败。修改 params 后需重启 Pod 才能生效（subPath 挂载不感知 ConfigMap 更新）。
- **/kw 持久卷复用生产**：两个 Deployment 均挂 `pvc-efs-kw-release` 到 `/kw`，Pod 重启不丢日志与 kb 数据；多 Pod（qa 副本 ≥3）共享写需注意日志文件命名冲突（已按日期+进程分隔）。
- **qa 不挂 kb-files PVC**：Phase 2 A 方案，qa 不解析临时文件，file_chat 直查共享 Milvus；若需源文件预览走 ingestion 侧返回。
- **HPA 扩容依赖 metrics-server**：qa 的 HPA 按 CPU/Memory 利用率伸缩，集群须已部署 metrics-server，否则 HPA 无法获取指标。
- **镜像 tag 与 HAISI_RUN_MODE 对齐**：ingestion 镜像配 `HAISI_RUN_MODE=ingest`，qa 镜像配 `HAISI_RUN_MODE=qa`，切莫混用（qa 镜像无解析栈，跑 ingest 模式会缺依赖报错）。
- **ConfigMap 变更不自动重建 Pod**：envFrom 引用的 ConfigMap 更新后，旧 Pod 仍用旧 env，须 `kubectl rollout restart` 显式触发。

---

## 复制日志到本地

**命令范式：**

```bash
kubectl cp <namespace>/<pod-name>:<容器内源路径> <本地目标路径>
```

**参数含义：**

- `kubectl cp`：kubectl 子命令，用于在 Pod 与本地主机之间复制文件或目录（双向均可）
- `<namespace>/<pod-name>`：目标 Pod 所在命名空间与 Pod 名称，用 `/` 分隔
- `:`：分隔符，其右侧为容器内路径
- `<容器内源路径>`：Pod 容器内待复制的文件或目录绝对路径
- `<本地目标路径>`：本地保存路径，所在目录需预先存在

**示例**：将 `default` 命名空间下 `haisi-rag-models-server-679c99b7bc-46ntk` Pod 内的 `/kw/logs/kw_error_2026-08-29.log` 复制到本地 `/home/user/test/kw_error_2026-08-29.log`：

```bash
kubectl cp default/haisi-rag-models-server-679c99b7bc-46ntk:/kw/logs/kw_error_2026-08-29.log /home/user/test/kw_error_2026-08-29.log
```

**常用变体：**

- 复制目录：源与目标均为目录路径，`kubectl cp` 会递归复制
- 多容器 Pod 指定容器：`kubectl cp <namespace>/<pod>:<src> <dst> -c <container-name>`
- 从本地复制到 Pod（反向）：`kubectl cp <local-src> <namespace>/<pod>:<容器内目标路径>`

**注意事项：**

- Pod 内需存在 `tar` 工具（`kubectl cp` 依赖容器内 tar 打包/解包），否则会报错 `OCI runtime exec failed`
- 本地目标父目录需预先存在，`kubectl cp` 不会自动创建多层目录
- 跨命名空间无需额外参数，namespace 已在 Pod 标识中指定
