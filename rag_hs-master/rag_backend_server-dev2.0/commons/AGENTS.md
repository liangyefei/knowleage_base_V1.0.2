# 通用组件模块

所有通用组件均放于此模块下。纯库模块：**不应用 `io.quarkus` 插件**，只应用 `java-common-conventions`。

## 成为 bean archive 的约定（重要）

每个含 CDI Bean 的 commons 模块必须在 `src/main/resources/META-INF/beans.xml` 里声明
（`bean-discovery-mode="annotated"`）。**全仓库没有任何 `quarkus.index-dependency` 配置**，
所以这个文件就是唯一的发现开关：漏了它，依赖它的服务会以 `UnsatisfiedDependency` 或
"注入静默缺失"的形式在启动期炸掉，而不是编译期报错。

新建 commons 模块时不用改 `application.yml`——没有索引配置要同步。

## 现有模块

| 模块 | 内容 | 备注 |
|------|------|------|
| commons-errorcode | 统一系统错误码 | |
| commons-i18n | 国际化、校验消息字段名解析 | `app.i18n.fieldName-list` 取 `Optional`，未配置不报错 |
| commons-web | 统一 web 处理、异常映射、`@Authenticated` 过滤器、413 处理 | 仓库里的 `@Authenticated` 是**自定义注解**，不是 `jakarta.annotation.security.Authenticated` |
| commons-ai | 模型服务访问适配层（rest-client、DTO、异常） | 解环时从 `llm` 下沉；模型侧 MCP Client 的落点 |
| commons-dict | 字典 PO/仓储/共享服务 + "问答↔知识"接缝端口 | 端口实现归 `knowledge`；`chat/` 下的接口是跨域唯一通道 |
| commons-sensitive | 敏感词端口 + 字典树匹配器 + DTO | 实现归 `llm.sensitive`；`WordTreeMatcher` 为纯逻辑，自带单测 |
| commons-abac | **空壳**：3 个 PO，无引擎 | 当前**无任何模块依赖它**（拆分前 `system` 曾依赖，实为未使用的声明） |
| commons-oidc | OIDC/华为账号（OneAccess）组件：平台 JWT 验签 `PlatformJwtVerifier`、外部账号校验端口 `ExternalAccountValidator` + `OneAccessAccountValidator`、claim 常量 | 平台 JWT 验签的落点；**尚未**替换 commons-web 过滤器的不验签路径 |

## 鉴权现状（P0，尚未修）

`commons-web` 的 `AuthenticationFilter` 与 `JwtUtils` 只做 Base64 解析取 `sub`/`exp`，
**不校验签名**，全仓库也没有 `mp.jwt.verify.*`。即：任何自签 JWT 都能通过全部 `@Authenticated` 资源。
真实验签计划落在 `commons-oidc`，详见《海思知识库架构设计-服务拆分评估.md》。
