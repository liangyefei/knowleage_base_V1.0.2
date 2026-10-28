# 服务启动模块（旧聚合服务，保留待下线）

> ⚠️ **这是拆分前的单一部署单元，命不久矣。**
> 拆分已落地为两个新服务：`hisilicon-knowledge-core-service`（业务 8080 / 探针 9000，`/hisilicon-knowledge`，knowledge+llm）
> 与 `hisilicon-knowledge-iam-service`（业务 8080 / 探针 9000，`/hisilicon-knowledge-iam`，system）。
> 本模块**刻意保持原样**（一个字节都没改），纯粹为了在新服务于真实环境验证通过前继续兼容旧前端。
>
> **不要再往这里加功能。** 新功能加在新服务或对应的业务库里。
> 新服务在真实 DB/Redis/Kafka 上跑通、旧前端切换完毕后，本模块整体删除即可
> （它不含任何业务逻辑，删除不丢代码）。

## 内容

`build.gradle.kts` 引 `system` + `knowledge` + `llm`；`src/main/resources/application.yml` 是拆分前的完整配置，
两个新服务的 yml 都是从它切分出来的——**改配置时注意两边要同步**，直到本模块被删除。

⚠️ **本模块的 Kafka 段仍是"带点键"写法，实测不生效**（`group.id` / `dead-letter-queue.topic` /
`auto.offset.reset` 等 10 个键被静默忽略）：实际消费组回落成**应用名** `hisilicon-knowledge-platform-service`，
死信主题回落成 smallrye 默认名，位点策略回落 `latest`。
新服务已改为嵌套 map 写法并验证生效（评估文档 §10.8.1），因此**新老服务组名不同**：
两者同时运行时**各自都会收到全量回调消息**（不同组 = 独立位点，会重复处理）。
切流时要么停掉本模块的消费，要么把新服务临时指向旧组名。
本模块按"冻结待下线"处理，**不要为了对齐去改它**——要改的是各环境 CCE 挂载的那份配置（见 §10.8.1 的补丁形状）。

`src/main/docker/*` 与 `7zz`、`src/main/resources/META-INF/native-image/kb-pdfbox/*` 已复制到
`hisilicon-knowledge-core-service`（那份是本模块的完整继承），`hisilicon-knowledge-iam-service` 用的是精简版。
