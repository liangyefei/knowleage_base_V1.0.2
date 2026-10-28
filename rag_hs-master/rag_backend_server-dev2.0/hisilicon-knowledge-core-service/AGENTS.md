# 业务能力服务（hisilicon-knowledge-core-service）

前台与后台的**业务能力**部署单元：知识库管理 + 智能问答（含文档全局搜索、热门词、知识图谱），
不含身份与访问域。

## 组成

| 来源 | 内容 |
| --- | --- |
| `knowledge` | 知识库、文档、分类、版本、检索、**字典域**（`knowledge.dict.*`，原 system 的字典管理） |
| `llm` | 智能问答、会话、命中率、**敏感词域**（`llm.sensitive.*`，原 system 的敏感词管理） |
| `commons-*` | web/错误码/i18n/dict/sensitive/ai（模型服务适配层）。实测 runtimeClasspath 上就这 6 个，**不含 `commons-abac`**（它当前无任何使用者） |

**不包含** `system`（身份与访问：用户/角色/权限/登录/令牌）——那是 `hisilicon-knowledge-iam-service`。

## 端口与路由

- HTTP 业务端口 **8080**、探针端口 **9000**，在 yml 里显式声明为 `port: 8080` / `quarkus.management.port: 9000`；
  `quarkus.http.root-path = /hisilicon-knowledge`（与拆分前一致）。
  ⚠️ 业务端口与 iam **同值**是有意的（CCE 里各自独立容器，同值不冲突；统一值让 Service/探针配置一致）；
  本机同时起两个服务时覆盖端口：`java -Dquarkus.http.port=8085 -Dquarkus.management.port=9005 -jar ...`
  （`-D` 必须在 `-jar` 之前），或环境变量 `QUARKUS_HTTP_PORT` / `QUARKUS_MANAGEMENT_PORT`
  （Quarkus 自动把环境变量映射到配置键，yml 里写死 8080 也照样能覆盖，已实测）。
  ⚠️ 探针已从业务端口剥离到 9000：**CCE 的 liveness/readiness 必须指 `:9000/q/health`**，
  业务端口上的 `/q/health` 是 404（会 404 → Pod 反复重启）。Dockerfile 已 `EXPOSE 8080 9000`。
  好处之一：探针地址与各服务 `root-path` 无关，两个服务都是 `:9000/q/health`。
- 对外路径**逐字未变**（本服务保留拆分前的 `root-path: /hisilicon-knowledge`），
  包括历史遗留的 `/system/**` 前缀：`/system/dict/**`、`/system/sensitive/**` 在拆分中归属本服务
  （字典域在 knowledge、敏感词域在 llm）。
- 分流规则（APIG/nginx 按**服务前缀**转发，不做路径细粒度判断）：
  - `/hisilicon-knowledge/**` → 本服务（**含** `/system/dict/**`、`/system/sensitive/**`）
  - `/hisilicon-knowledge-iam/**` → `hisilicon-knowledge-iam-service`
  - 浏览器可见的 `/hisilicon-knowledge/user/**`、`/hisilicon-knowledge/publicKey` 由前端 nginx/APIG
    **rewrite** 到 `/hisilicon-knowledge-iam/...`（前端零改动）——本服务拿不到这几个路径，也不该有。
- ⚠️ 前缀必须与 iam 不同（APIG 按前缀转发，同前缀分不开两个服务），冒烟测试里有断言兜底。

## 配置

`src/main/resources/application.yml` 是唯一配置文件，由拆分前的聚合配置切分而来：

- 保留：日志、HTTP/CORS、datasource（schema `knowledge`）、hibernate-orm、redis、
  rest-client（model-service / kg-service）、Kafka（`mp.messaging.*` + `kb.messaging.kafka.enabled`）、
  `kb.*` 全部业务配置、native 构建参数（charset/时区/POI-PDFBox-Tika-AWT 初始化策略）。
- **不含** `rsa.*`：本服务没有任何 RSA 用途（无登录解密、无 JWT 签名），
  classpath 里也没有 `privateKey.pem`（它随 `system` 模块只进 iam）。
- ⚠️ **外挂配置的位置**：本服务默认打 native 镜像，`WORKDIR /work/`，运行时只读
  **`/work/config/application.yml`**（旧聚合服务的 JVM 镜像是 `/deployments/config/`）。
  挂错目录**不会报错**，应用会安静地用镜像内默认值（`127.0.0.1:5432`）——
  iam 在 CCE 上已踩过一次，详见评估文档 §10.4.1。注意 `subPath` 挂单文件时内容
  **不会随 ConfigMap 更新刷新**，改完必须重建 Pod；YAML 写坏则是启动即失败（`exit=1`），
  不会静默忽略——所以"启动成功却连了默认地址"只会是文件不在或内容不对。
- **Hibernate SQL 日志默认关闭**（`hibernate-orm.log.{sql,format-sql,bind-parameters}` 全 `false`），
  本地由 yml 末尾的 `%dev` 段打开。⚠️ 外挂 ConfigMap 里**删掉**这段不会关掉它（叠加语义只是回落镜像值），
  要关就显式写 `false`。生产别常开：LTS 按量计费、outbox relay 每 10s 一条 × 副本数、
  `bind-parameters` 会把参数明文写进日志平台。

### ⚠️ Kafka 段里的"带点键"会被静默忽略（P4，务必先读）

`mp.messaging.incoming/outgoing.*` 下写成**带点的键**（`group.id`、`dead-letter-queue.topic`、
`auto.offset.reset`、`partition.assignment.strategy`、`bootstrap.servers`、
`key/value.serializer|deserializer`）**会被静默忽略**，值不生效也不报错。
**本服务的 yml 已全部改为嵌套 map 写法**（此前 10 个键全部没生效过），实测证据与对照实验见评估文档 §10.8.1：

| 写法 | 是否生效 |
| --- | --- |
| 带点的键：`group.id: xxx` | ❌ 静默忽略 |
| **嵌套 map：`group: { id: xxx }`** | ✅（本服务现行写法） |
| `.properties`：`mp.messaging.incoming.<channel>.group.id=xxx` | ✅ |
| JVM 系统属性 `-Dmp.messaging.incoming.<channel>.group.id=xxx` | ✅（`-D` 必须在 `-jar` 之前） |
| 环境变量 `MP_MESSAGING_INCOMING_<CHANNEL>_GROUP_ID` | ❌ **启动直接失败**（通道名含连字符时会被还原成名为 `kb` 的通道，报 `SRMSG00071`） |

修好后的实际生效值（真实 Kafka 已验证）：消费组 `kb-document-processed-consumer`、死信主题
`kb-document-processed-dlq`、位点复位 `earliest`、assignor `cooperative-sticky`。
**发版/切流后仍要核对启动日志三行**：`SRMSG18229`（topic）/ `SRMSG18257`（消费组）/ `SRMSG18258`（死信主题）。

⚠️ 两条与切换强相关的后果（详见 §10.8.1）：

1. **旧服务与新服务组名不同 ⇒ 同时运行时各收全量消息。** 旧服务的这 10 个键**未修**，
   其实际组名仍是应用名 `hisilicon-knowledge-platform-service`。切换时要么停掉旧服务的消费，
   要么把新服务临时指向旧组名。
2. **各环境实际生效的是 CCE 挂载的那份配置文件**（本文件只是 dev 默认值/模板）。
   若那份也是带点键写法，同样不生效——2026-10-13 那次"三环境共组、探针全绿却永不消费"
   极可能就是栽在这里（组名缺省 ⇒ 回落应用名 ⇒ 三环境应用名相同 ⇒ 必然共组）。

## 构建

```bash
./gradlew :hisilicon-knowledge-core-service:quarkusDev '-Dquarkus.native.enabled=false'   # 本地开发
./gradlew :hisilicon-knowledge-core-service:test '-Dquarkus.native.enabled=false'         # 冒烟测试
./gradlew :hisilicon-knowledge-core-service:quarkusAppPartsBuild '-Dquarkus.native.enabled=false' '-Dquarkus.package.jar.enabled=true'   # 只验证装配
./gradlew :hisilicon-knowledge-core-service:build '-Dquarkus.native.enabled=true'         # native（默认容器构建）
```

⚠️ 本服务是 `package.jar.enabled: false` + `native.enabled: true`，所以只传
`'-Dquarkus.native.enabled=false'` 会让 `quarkusAppPartsBuild` 因"没有任何产物可产出"而失败
（报 `No artifact results were produced`，不是代码问题）。只想验证 CDI 装配就补上
`-Dquarkus.package.jar.enabled=true`。

⚠️ native 构建已由用户侧验证通过（core 与 IAM 均报无问题），但**不是我在本轮执行的步骤**；
若改动 native 参数或依赖（尤其是 POI/AWT/Tika/zstd 的初始化豁免），需重新验证。

native 镜像由 `src/main/docker/Dockerfile.native` 定义，需 `src/main/docker/7zz`
（RAR5 解压）与 quarkus-awt 产出的 `.so`。

## 测试

`src/test/java/.../CoreServiceSmokeTest.java` 是服务级独立启动冒烟：验证
"换成服务级 classpath 后 CDI 仍完整 + 路由归属"。模块自己的 `@QuarkusTest` 跑在模块 classpath 上，
验证不了这件事（典型漏网：某个 Bean 依赖只存在于 `system` 的实现），所以不是重复劳动。

⚠️ 测试请求路径**不要带 `/hisilicon-knowledge` 前缀**：Quarkus 测试框架会把 `quarkus.http.root-path`
设为 RestAssured 的 `basePath`，写全前缀会变成双层前缀、全部 404。
测试端口：业务 8087、管理 9007（主 yml 的 `management.enabled` 在测试期同样生效；默认 9001 会被 iam 的测试同时占用）。
测试期关 hibernate/scheduler/Dev Service，`kb.storage.local-path` 指向临时目录
（默认 `/data/kb-files` 在 Windows 上 AccessDenied）。

## 边界（不要越界）

- 不要在本模块写业务代码：只做依赖聚合与配置。业务代码放 `knowledge` / `llm` / `commons-*`。
- 不要引入 `project(":system")`：那会把身份域重新拉回本服务的运行时 classpath，
  `hisilicon-knowledge-iam-service` 的独立部署即失去意义（鉴权在网关/服务侧各自完成，见评估文档）。
