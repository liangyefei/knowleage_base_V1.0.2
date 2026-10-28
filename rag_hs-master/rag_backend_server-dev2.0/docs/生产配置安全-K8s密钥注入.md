# 海思知识库后端 — 生产配置安全（CCE K8s）

> 2026-08-13 定稿。组合方案：**ConfigMap 管普通配置 + Secret 管密钥**，应用内用
> `${ENV_VAR:默认值}` 占位（默认值仅供本地/测试兜底），生产经 **envFrom secretRef**
> 注入环境变量覆盖（Quarkus 自动把 `QUARKUS_DATASOURCE_PASSWORD` 映射为
> `quarkus.datasource.password`——点转下划线、全大写，官方标准映射）。
> 2026-08-31 增补：图片签名密钥 `KB_ASSET_SIGNATURE_SECRET`（见 一/二.4/三.6）。

## 一、应用侧配置（已实施，hisilicon-knowledge-platform-service/src/main/resources/application.yml）

| 配置键 | 占位符 | 默认值（本地兜底） | 生产注入源 |
|--------|--------|-------------------|-----------|
| `quarkus.datasource.password` | `${QUARKUS_DATASOURCE_PASSWORD:123456}` | 123456 | Secret |
| `quarkus.redis.hosts` | `${QUARKUS_REDIS_HOSTS:redis://localhost:6379/0}` | 本地无密码 | Secret（如生产 Redis 有密码） |
| `rsa.public-key` | `${RSA_PUBLIC_KEY:}` | 空 → 回退 classpath `publicKey.pem` | ConfigMap（公钥非敏感） |
| `rsa.private-key` | `${RSA_PRIVATE_KEY:}` | 空 → 回退 classpath `privateKey.pem` | **Secret（私钥严禁入 ConfigMap/镜像）** |
| `kb.asset.signature-secret` | **无（键刻意不落盘）** | 未配置 → 图片功能降级（md 图片引用保持原样） | **Secret（markdown 图片 URL 的 HMAC 签名密钥）** |

**native 镜像**：`quarkus.native.resources.includes` 已移除 `privateKey.pem`（保留
`publicKey.pem`）——生产镜像不含私钥。私钥读取链路：`PemRsaUtils.getPrivateKeyContent()`
优先 `rsa.private-key` 配置键（K8s 注入），未配置回退 classpath（本地 JVM dev 用）。

**代码零改动**：`PemRsaUtils` 双通道读取（配置键优先 / classpath pem 兜底）本已支持注入。

> **`kb.asset.signature-secret` 为何没有占位符**：application.yml 中刻意**不建这个键**——
> SmallRye 对环境变量 `KB_ASSET_SIGNATURE_SECRET` 的标准映射（点转下划线、全大写）无需
> yml 占位即可被 `AssetUrlSigner` 的 `@ConfigProperty` 读取，密钥因此物理上不进应用仓库。
> 未注入时功能自动降级（下载 md 图片引用保持原样）并打启动 WARN，不会导致启动失败。
> 有效期 `kb.asset.url-expiry-seconds`（默认 3600）与跨域前缀 `kb.asset.url-prefix`（默认空）
> 均有代码默认值，无需注入。

## 二、CCE 资源模板（生产侧执行）

### 1. Secret `hisilicon-app-secret`（命名空间：默认）

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: hisilicon-app-secret
type: Opaque
stringData:
  QUARKUS_DATASOURCE_PASSWORD: "<生产数据库密码>"
  RSA_PRIVATE_KEY: |
    -----BEGIN PRIVATE KEY-----
    <生产 RSA 私钥>
    -----END PRIVATE KEY-----
  KB_ASSET_SIGNATURE_SECRET: "<图片签名密钥：openssl rand -hex 32 生成 64 位 hex>"
  # 如生产 Redis 有密码：
  # QUARKUS_REDIS_HOSTS: "redis://:<密码>@<host>:6379/0"
```

> ⚠️ `stringData` 明文写 yaml 仅限首次创建；落库后立即 `kubectl get secret ... -o jsonpath='{.data}'` 确认已 base64 编码。生产私钥/密码严禁提交到 git。

### 2. ConfigMap 追加公钥（现有 ConfigMap `hisilicon-app-config`）

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: hisilicon-app-config
data:
  RSA_PUBLIC_KEY: |
    -----BEGIN PUBLIC KEY-----
    <生产 RSA 公钥>
    -----END PUBLIC KEY-----
```

> 公钥 = 私钥对应公钥。前端登录加密用 `GET /publicKey` 返回的内容，生产注入后该端点自动返回此公钥。

### 3. Deployment 追加 envFrom

```yaml
spec:
  template:
    spec:
      containers:
        - name: hisilicon-knowledge-platform-service
          envFrom:
            - secretRef:
                name: hisilicon-app-secret
          # ConfigMap 的普通配置如已有 envFrom 则保留，两段 envFrom 并列
          # envFrom:
          #   - configMapRef:
          #       name: hisilicon-app-config
```

> 同一容器可同时挂多个 envFrom（configMapRef + secretRef），键冲突时后列出的优先——把 secretRef 放后面确保密码覆盖。

### 4. 图片签名密钥：Deployment 明文 env → Secret 注入（现网迁移步骤）

现网 Secret 实际为 `backend-secert`（名称含历史拼写笔误；重命名属可选清理，不影响本流程），
注入风格为**逐键 `secretKeyRef`**（与上面模板的 envFrom 等价，现网保持逐键即可）。
图片签名密钥当前以明文 `value:` 写在 Deployment spec 中——明文 env 对任何有 deployment 读权限的
RBAC 主体可见（`kubectl get deploy -o yaml`），YAML 入 git/归档即泄露，应迁到 Secret。
建议与下次换 tag 重新部署合并为**同一次 rollout**：

```bash
# 1) Secret 补 key（值可直接沿用现网值，或趁机轮换新随机值）
kubectl patch secret backend-secert \
  -p '{"stringData":{"kb-asset-signature-secret":"<新值，openssl rand -hex 32>"}}'

# 2) 编辑 Deployment：env 中该项由明文 value 改为 valueFrom，删除明文行
kubectl edit deployment hisilicon-knowledge-platform-service
#   迁移前（删除）：
#     - name: KB_ASSET_SIGNATURE_SECRET
#       value: "<明文值>"
#   迁移后：
#     - name: KB_ASSET_SIGNATURE_SECRET
#       valueFrom:
#         secretKeyRef:
#           name: backend-secert
#           key: kb-asset-signature-secret

# 3) 等待滚动完成（2/2 ready）
kubectl rollout status deployment/hisilicon-knowledge-platform-service
```

> 无需改镜像、无需在 application.yml 加占位（见第一节说明）。应用侧对"明文 value"与
> "secretKeyRef"两种来源无感知，读到的都是同名环境变量。

## 三、验证清单（CCE 部署后）

1. `GET https://<ingress>/hisilicon-knowledge/publicKey` → 返回**生产公钥**（非本地 dev 公钥）
2. 前端登录（RSA 加密密码）→ 成功（私钥解密链路 = Secret 注入值）
3. 登录返回 JWT → 后续请求 200（JWT 签名 = Secret 私钥）
4. 文档上传/查询正常（DB 连接 = Secret 密码）
5. `kubectl get secret hisilicon-app-secret -o jsonpath='{.data.QUARKUS_DATASOURCE_PASSWORD}'` 非明文
6. 图片签名密钥已走 Secret：Deployment env 中 `KB_ASSET_SIGNATURE_SECRET` 为
   `valueFrom.secretKeyRef`（无明文 `value:` 行）；打开一篇含相对路径图片的 md 在线查看，
   **图片正常显示**即双重验证通过——密钥未加载时后端拒绝重写、图片必挂；密钥已加载但 URL
   缺 root-path 前缀时请求会 404（图片同样挂）。两个历史缺陷的表现不同：前者 F12 无图片请求，
   后者有请求但 404。

## 四、运维注意事项

- **改密码/换密钥**：改 Secret → `kubectl rollout restart deployment`，**不重建镜像**
- **密钥轮换**：换 RSA 私钥后所有已签发 JWT 失效（用户重新登录）——建议私钥固定、仅轮换 DB 密码
- **图片签名密钥轮换**：只影响未过期的已签发图片 URL（默认 1 小时有效期，自行失效），改 Secret 值 →
  rollout restart 即完成，无会话失效、无需用户操作
- **私钥格式**：PEM 多行，yaml 用 `|` 块保留换行；`PemRsaUtils` 解析 `-----BEGIN PRIVATE KEY-----`（PKCS8）与 `BEGIN RSA PRIVATE KEY`（PKCS1）均支持
- **多副本**：Secret 集群内共享，多 Pod 同一私钥签名——JWT 跨 Pod 可验证，符合设计
- **回滚**：Secret 独立于镜像，回滚镜像版本不影响密钥；删 Secret 前确认无依赖
