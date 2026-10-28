# 身份与访问服务（hisilicon-knowledge-iam-service）

**身份与访问**部署单元：用户、角色、权限、登录/刷新/退出、令牌与 RSA 密钥对。
对应 DDD 的支撑域（通用子域），拆分的理由是它与业务能力域变更频率不同、
权限模型后续要扩展（RBAC/多 IdP/华为账号），且它是唯一需要 RSA 私钥与口令库的服务。

## 组成

只聚合 `system` 模块（`com.hisilicon.knowledge.platform.system.*`）+ `commons-*`。
**不含** `knowledge` / `llm`。

拆分后 `system` 已不含字典域与敏感词域（分别归 `knowledge.dict` 与 `llm.sensitive`），
因此本服务的依赖里不再需要 `commons-dict` / `commons-sensitive` / POI / commons-csv。

## 端口与路由

- HTTP 业务端口 **8080**、探针端口 **9000**，在 yml 里显式声明为 `port: 8080` / `quarkus.management.port: 9000`；
  `quarkus.http.root-path = /hisilicon-knowledge-iam`。
  ⚠️ 业务端口与 core **同值**是有意的（CCE 里各自独立容器，同值不冲突；统一值让 Service/探针配置一致），
  本机同时起两个服务时覆盖端口：`java -Dquarkus.http.port=8085 -Dquarkus.management.port=9006 -jar ...`
  （`-D` 必须在 `-jar` 之前），或环境变量 `QUARKUS_HTTP_PORT` / `QUARKUS_MANAGEMENT_PORT`
  （Quarkus 自动把环境变量映射到配置键，yml 里写死 8080 也照样能覆盖，已实测）。
  ⚠️ 探针已从业务端口剥离到 9000：**CCE 的 liveness/readiness 必须指 `:9000/q/health`**，
  业务端口上的 `/q/health` 是 404（会 404 → Pod 反复重启）。Dockerfile 已 `EXPOSE 8080 9000`。
- ⚠️ **`root-path` 必须与 `hisilicon-knowledge-core-service` 不同，这不是风格问题**：测试/生产经华为云 APIG 接入，
  APIG 按**前缀**转发到 CCE 内的 Service；两个服务共用 `/hisilicon-knowledge` 时 APIG 分不开它们
  （只能退化成对 `/user/**`、`/publicKey` 的细粒度规则，每加一个端点就要改网关）。
  MCP 网关预留 `/hisilicon-knowledge-mcp`。两个服务的冒烟测试都断言了各自的前缀，改回共用会立刻红。
- 对外接口（**服务侧**路径）：
  - `GET  /hisilicon-knowledge-iam/publicKey`（下发 RSA 公钥）
  - `POST /hisilicon-knowledge-iam/user/login`、`/user/refresh`、`/user/logout`
  - **OneAccess OIDC**：`GET /oidc/login/huawei`、`GET /oidc/callback/huawei`
    （与 `quarkus.oidc.authentication.redirect-path` 一致）、`POST /oidc/token`（一次性 code 换平台令牌）、
    `GET /oidc/logout/huawei`
  - **角色管理**：`/system/roles/**`、`/system/users/{username}/roles`（写操作要求 `sys_admin`，内置角色不可删/改状态）
- **浏览器可见路径不变，靠前端 nginx / APIG 内部 rewrite**（前端零改动）：
  `/hisilicon-knowledge/user/**` → `/hisilicon-knowledge-iam/user/**`，
  `/hisilicon-knowledge/publicKey` → `/hisilicon-knowledge-iam/publicKey`。
- 探针地址与 `root-path` **无关**（它是独立管理端口上的 `/q`）：两个服务的探针都是 `:9000/q/health`。

## 配置

`src/main/resources/application.yml` 由拆分前的聚合配置切分而来，只保留本服务真正读取的键：

- 保留：日志、HTTP/CORS、datasource（与 core 同库同 schema）、hibernate-orm、redis、native。
- **不含**：`rest-client`（不调模型）、`mp.messaging.*` / `kb.messaging.kafka.*`（不收发 Kafka）、
  所有 `kb.*` 业务配置（`system` 不读任何 `kb.*` 键）、POI/AWT/Tika 相关 native 参数。
- **独有**：`rsa.public-key` / `rsa.private-key`。私钥只应存在于本服务。
- ⚠️ **外挂配置的位置**：本服务默认打 native 镜像，`WORKDIR /work/`，运行时只读
  **`/work/config/application.yml`**（旧聚合服务的 JVM 镜像是 `/deployments/config/`）。
  挂错目录**不会报错**，应用会安静地用镜像内默认值 `127.0.0.1:5432` 去连库，
  表现为 `Connection refused (connect failed): /127.0.0.1:5432` 且健康检查 DOWN——已在 CCE 上踩过，
  详见评估文档 §10.4.1。等价的免挂载做法是环境变量（如 `QUARKUS_DATASOURCE_REACTIVE_URL`），
  但 `${VAR}` 不写默认值会在变量缺失时**启动即失败**，写 `${VAR:}`。
- **Hibernate SQL 日志默认关闭**（`hibernate-orm.log.{sql,format-sql,bind-parameters}` 全 `false`），
  本地由 yml 末尾的 `%dev` 段打开。⚠️ 外挂 ConfigMap 里**删掉**这段不会关掉它（叠加语义只是回落镜像值），
  要关就显式写 `false`。生产别常开：LTS 按量计费、`bind-parameters` 会把登录相关参数明文写进日志平台。

## 构建

```bash
./gradlew :hisilicon-knowledge-iam-service:quarkusDev '-Dquarkus.native.enabled=false'   # 本地开发
./gradlew :hisilicon-knowledge-iam-service:test '-Dquarkus.native.enabled=false'         # 冒烟测试
./gradlew :hisilicon-knowledge-iam-service:quarkusAppPartsBuild '-Dquarkus.native.enabled=false' '-Dquarkus.package.jar.enabled=true'   # 只验证装配
./gradlew :hisilicon-knowledge-iam-service:build '-Dquarkus.native.enabled=true'         # native（默认容器构建）
```

⚠️ 本服务是 `package.jar.enabled: false` + `native.enabled: true`，所以只传
`'-Dquarkus.native.enabled=false'` 会让 `quarkusAppPartsBuild` 因"没有任何产物可产出"而失败
（报 `No artifact results were produced`，不是代码问题）。

⚠️ native 构建已由用户侧验证通过（**不是我在本轮执行的步骤**）；本服务的 `quarkus.native`
参数是**故意精简**的（照抄 core 的那套豁免会引入无关依赖假设），改动依赖或参数后需重新验证。
`privateKey.pem` 的镜像排除逻辑在 `system/build.gradle.kts`（native 构建时 processResources 排除）——
⚠️ 它的判断条件（系统属性 `quarkus.native.enabled`）与 yml 里的 `quarkus.native.enabled: true`
**口径不一致**，构建前先确认私钥没被打进镜像（评估文档 P1）。

## 测试

`src/test/java/.../IamServiceSmokeTest.java`：服务级独立启动冒烟 + **反向隔离防线**——
若谁把 `knowledge` / `llm` 拉进本服务，测试里的 404 断言会立刻失败；
同时断言 `root-path` 是 `/hisilicon-knowledge-iam`（不能与 core 相同）与 `/q/health` 不在业务端口上。
测试端口：业务 8088、管理 9008（默认 9001 会被 core 的测试同时占用，必须错开），测试期关 hibernate 与 Dev Service。
⚠️ 测试请求路径**不要带 `/hisilicon-knowledge-iam` 前缀**（框架会把它设为 RestAssured 的 `basePath`，写全会双层前缀、全 404）。

### 真实环境验证已做过什么（评估文档 §10.8）

启动**生产模式** runner jar（`java -jar build/quarkus-app/quarkus-run.jar`，非 dev、非测试 profile）连着真实
PostgreSQL / Redis 跑通过：

- 启动日志同时打印 `Listening on: 0.0.0.0:8080` 与 `Management interface listening on 0.0.0.0:9000`；
- **探针 `:9000/q/health` = `UP`（PostgreSQL `UP` + Redis `PONG`）**，而 `:8080/q/health` = 404；
- 登录全链路：`GET /publicKey` → 用 `PemRsaUtils.encryptBase64WithKey` 加密账号口令 → `POST /user/login`
  → `code:"0"` + 真实 RS256 令牌 → **Redis 写入 `user_token:*` / `refresh_token:*`** → `last_login` 落库。

复现这个验证需要造一条账号：用 `BcryptUtil.bcryptHash(明文)` 生成散列后直接插 `users` 表
（`id`/`username`/`password`/`status=1` 即可），**测完记得删账号和 Redis 键**。
注意 `users` 表**没有主键/唯一约束**，`INSERT ... ON CONFLICT (id)` 会直接报错。

## 后续（本轮不做）

- 后台本地账号与华为账号打通：对接后管理员为新账号设置口令与权限，两种方式都能登录后台。
- 权限判定引擎：当前角色管理只有最小 `sys_admin` 守卫；`commons-abac` 仍是空壳。
- `commons-web` 过滤器不验签的 P0 收口（`commons-oidc` 已提供 `PlatformJwtVerifier`，尚未接线）。
