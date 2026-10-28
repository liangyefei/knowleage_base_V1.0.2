# 架构
- 编译工具：Gradle with Kotlin DSL，版本9.0+
- 项目组织：采用多模块开发，以service结尾的模块为quarkus的web应用主模块，该模块无业务逻辑，引入其他业务模块。
- 设计架构：采用DDD的设计架构

# 约定
- 遵从阿里的编码规范
- quarkus配置文件采用yaml格式
- 优先采用响应式编程
- 所有的日志打印内容都用英文，避免使用中文日志
- 异步编程统一使用 Mutiny（`io.smallrye.mutiny.Uni` 和 `Multi`）。
- 除非特殊情况，否则rest端点默认返回的类型为com.hisilicon.knowledge.platform.commons.web.ResponseResult，分页查询为com.hisilicon.knowledge.platform.commons.web.PageResult；若是响应式，则为Uni<com.hisilicon.knowledge.platform.commons.web.ResponseResult>或Uni<com.hisilicon.knowledge.platform.commons.web.PageResult>
- 依赖注入优先采用构造函数注入
- JSON 处理统一使用 Quarkus 托管的 `ObjectMapper`（`@Inject ObjectMapper`，定制见 `commons-web` 的 `LocalDateTimeJacksonCustomizer`）。**禁止 `new ObjectMapper()`**：手搓实例绕过 Quarkus 构建期反射分析，在 native 镜像下反序列化 record/POJO 会因缺少反射元数据而失败（报 `no delegate- or property-based Creator … appears to be a native image`）。确需限定特性（如 `ACCEPT_SINGLE_VALUE_AS_ARRAY`）时，用 `@Singleton` 生产 `objectMapper.copy()` 后开特性的限定 mapper，而非 `new`。凡被手动 `(de)serialize` 的 record/POJO，必须加 `@RegisterForReflection`。
- 数据库访问：只读查询方法选用 @WithSession 或直接使用注入的 Mutiny.Session；需要事务的方法使用 @WithTransaction（通常标注在应用服务或仓储实现的方法上）。禁止在领域层使用任何 Quarkus 事务/会话注解。
- 单元测试框架为 JUnit 5 + Mockito，领域层不依赖任何 Quarkus 容器。
- `@WithTransaction` 仅限于应用服务层，领域层绝不标注事务。
- 领域事件放在 `*.domain.event` 包下，命名为 `*Event`
- 领域服务放在 `*.domain.service` 包下（仅当业务逻辑涉及多个聚合，且不适合放在单个聚合根内时使用）
- 自定义异常继承com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException
- 如用到redis，优先使用ReactiveStreamCommands
- 日志内容使用英文

# 重要说明
- \*.facade包为对外接口层，即quarkus的http Resource层
- \*.domain包下为领域层，\*Entity.class类为实体类，\*AggregateRoot.class类为聚合根类
- 数据库采用quarkus-hibernate-reactive-panache，参数校验采用quarkus-hibernate-validator
- \*.infrastructure包为基础设施层，其下mapper包为mapstruct的转换器接口，repository包为仓储层，repository.po为DB的实体（贫血 JPA Entity）
- \*.command包为命令包，\*Command.class为对外接口层入参bean定义，类型为record。\*CommandService.java为应用服务类，上接接口层，下接领域层和基础设施层。入参是否为空，长度等判断放在接口层做，其他非业务逻辑判断在应用层做，业务逻辑判断放领域层做
- 查询业务直接在应用层做，无需放到领域层（CQRS 模式），在\*.query包下
- 领域对象（`*.domain.entity`）是纯 POJO，**禁止**使用任何 `jakarta.persistence` 注解。
- 仅在 `*.infrastructure.po` 中的 PO 类上使用 JPA/Hibernate 注解。
- po、dto、vo等之间的转换由 `*.infrastructure.mapper` 下的 MapStruct 接口完成，禁止手写 `get/set` 转换。
- 查询 DTO 放在 `*.dto` 包下（如 `UserDTO`）。
- 采用 **Repository 模式**，不使用 Active Record（即不继承 `PanacheEntity`）。
- 仓储接口在 `*.domain.repository` 包定义（返回 `Uni`），实现在 `*.infrastructure.repository` 完成。
- 仓储实现使用注入的 `PanacheRepositoryBase`
- ID 在领域层用值对象包装（如 `UserId.of(String)`），在 PO 层用普通 `String`
- 实体 ID 的生成由应用层或领域层统一处理，不在构造函数中自动生成。

# 分层与命名
## 包结构示例
- 接口层：com.example.project.user.facade.UserResource
- 应用层：com.example.project.user.command.UserCommandService
- 领域层：com.example.project.user.domain.entity.User (Entity、AggregateRoot)
                                        .event
                                        .service
- 基础设施层：com.example.project.user.infrastructure.repository.UserRepositoryImpl
                                                              .po.UserPO
                                                              .mapper.UserMapper
- 入参定义：com.example.project.user.command.LoginCommand
- 查询业务：com.example.project.user.query

# 分层调用示例
完整的分层代码调用链示例在`examples/user/` 目录下，包含 Command、Resource、Application Service、Domain、Repository、PO、Mapper、DTO等两个完整示例（登录、查询）。所有新功能都应按此模式实现。

# AI 编码行为准则

> 本准则仅适用于 AI 编程助手（如 DSH 等）。人类开发者可忽略此章节。

## 1. 技能获取优先级

AI 在生成 Quarkus 相关代码时，必须按以下优先级获取指导信息：

| 优先级        | 来源                              | 说明                                                         |
| :------------ | :-------------------------------- | :----------------------------------------------------------- |
| **1（最高）** | **MCP 工具 `quarkus_skills`**     | 通过 Quarkus Agent MCP 服务器加载当前项目依赖中扩展自带的 `quarkus-skill.md`（位于 `META-INF/`）。这是与项目依赖版本精确绑定的官方指导，具有最高权威。 |
| **2**         | **MCP 工具 `quarkus_searchDocs`** | 搜索 Quarkus 官方文档，获取框架级的最佳实践和 API 说明。     |
| **3**         | **本规范文档**                    | 本文件中的约定作为兜底规则，确保代码风格和架构与项目一致。   |
| **4（最低）** | AI 通用训练知识                   | 仅在以上渠道均无相关信息时才使用。                           |

## 2. 触发规则

### 2.1 必须调用 `quarkus_skills` 的场景

当用户问题或任务涉及以下 Quarkus 扩展时，**必须先调用 `quarkus_skills` 加载对应的 `quarkus-skill.md` 文件**，并严格按其指导编码：

- **Hibernate Reactive / Panache**（数据库操作）
- **RESTEasy Reactive**（REST 端点）
- **ArC**（依赖注入）
- **OIDC / Security**（认证授权）
- **SmallRye GraphQL**（GraphQL 端点）
- **Micrometer**（指标监控）
- **任何其他带有官方 `quarkus-skill.md` 的扩展**

> **调用方式**：使用 MCP 工具 `mcp__quarkus_skills`，技能文件路径为 `META-INF/quarkus-skill.md`。

### 2.2 建议调用 `quarkus_searchDocs` 的场景

当任务涉及以下内容且 `quarkus_skills` 未覆盖时，应调用 `quarkus_searchDocs`：

- 配置文件（`application.yml`）的特定属性
- 框架级 API 用法（非扩展特有）
- 版本升级差异
- 已知问题或限制

### 2.3 本规范文档的兜底作用

当 `quarkus_skills` 和 `quarkus_searchDocs` 均未覆盖时，遵循本规范文档中的约定。

## 3. 冲突处理规则

**核心原则：MCP 加载的官方技能优先于本规范文档，本规范文档优先于 AI 通用知识。**

当出现以下冲突时：

| 冲突场景                                              | 处理方式                                                     |
| :---------------------------------------------------- | :----------------------------------------------------------- |
| `quarkus-skill.md` 与本规范文档的约定不一致           | **以 `quarkus-skill.md` 为准**。例如：Hibernate Reactive 的官方 skill 推荐使用 `@Inject Mutiny.Session` + `@Transactional`，而本规范提及 `@WithTransaction`，则以官方 skill 为准。 |
| `quarkus-skill.md` 与 AI 通用知识不一致               | **以 `quarkus-skill.md` 为准**。                             |
| `quarkus_searchDocs` 返回的最新文档与本规范文档不一致 | **以官方文档为准**，并建议同步更新本规范文档。               |

## 4. 示例：正确的 AI 编码流程

假设用户要求：“用 Quarkus 写一个 Hibernate Reactive 的用户仓储类。”

**AI 应执行的步骤：**

1. **调用 `mcp__quarkus_skills`**，加载当前项目依赖中的 `META-INF/quarkus-skill.md`（来自 `quarkus-hibernate-reactive` 扩展）。
2. **读取官方 skill 内容**，获取 Hibernate Reactive 的最佳实践（如使用 `@Inject Mutiny.Session` + `@Transactional`，避免 `@WithTransaction`）。
3. **结合本规范文档**的分层架构要求（`*.domain.repository` 定义接口，`*.infrastructure.repository` 实现）。
4. **生成代码**，确保同时满足官方 skill 的技术指导和本规范的项目架构约束。
5. **如官方 skill 未覆盖**，再调用 `quarkus_searchDocs` 补充查询。

## 5. 禁止事项

- ❌ **禁止**在未调用 `quarkus_skills` 的情况下直接凭 AI 通用知识编写涉及核心扩展的代码。
- ❌ **禁止**无视 `quarkus-skill.md` 中的明确指导，自行“优化”或“简化”官方推荐写法。
- ❌ **禁止**在 `quarkus_skills` 可用时，优先使用本规范文档中的过时写法（如本规范中历史遗留的 `@WithTransaction` 描述，应以官方 skill 为准）。

# 代码扫描整改规范

> 本章由 2026-10 代码扫描整改实战沉淀（基线 1620 条，覆盖 knowledge/system/llm/commons/docs/sql），规则编号沿用扫描器（G.XX.NN）。正例均摘自本次真实修改。

## 1. 属性封装（G.OBJ.01）

PO/实体/复合主键类的实例字段一律 `private` + getter/setter，禁止 `public` 裸字段（`static final` 常量除外）。

❌ 反例：
```java
public class UserPO {
    @Id
    public String id;          // 扫描项 FieldModifier
    public Short status;
}
```
✅ 正例：
```java
public class UserPO {
    @Id
    private String id;
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}
```

封装 PO 字段时**三个坑必须逐项检查**：

1. **MapStruct `expression` 字符串里是源码不是属性访问**：`@Mapping(target="id", expression="java(XxxId.of(po.id))")` 不会因字段改 private 而在 mapper 接口上报错，要同步改成 `po.getId()`，否则错误延迟到注解处理器生成代码时才暴露。
2. **integrationTest 源集不在 compileJava 里**：改 PO 可见性后，`compileJava compileTestJava` 全绿不代表完事，必须追加 `:模块:compileIntegrationTestJava`（本次 IT fixture 源集漏网 90+ 处字段直读）。
3. **`@IdClass` 复合主键类字段同样要封装**：PK 内部类的 public 字段也是扫描项；Hibernate 7 的 ReflectHelper 支持非 public 属性，`implements Serializable` 的 PK 记得补 `serialVersionUID`（见 §7）。

另注意 Panache 仓储泛型以 PK 为 ID 类型不受影响；禁止借封装之机改动字段语义或类型。

## 2. 方法简短与参数收敛（G.MET.01）

- 方法原则上 ≤50 行、参数 ≤5 个（JAX-RS 资源方法的 HTTP 注解入参不计入参数数，facade 签名不动以保持 HTTP 契约）。
- 收敛手段按优先级：**领域值对象（record）> 查询对象（如 `ListDocumentsQuery`、`SensitiveQuestionQuery`）> 私有方法拆分**。禁止为凑参数数把强相关的散参堆成无语义 Object 可变参数。
- **事务红线**：同一 `@WithTransaction` 内的多个写操作不得拆到两个服务类（跨类即跨事务边界），宁可在原类内拆私有方法。
- 超长方法按「**校验 → 落库 → 事件**」三段拆；复杂查询拼装抽 QueryBuilder 小对象（query 字符串 + params 打包），不要散拆成十个小方法；二进制协议解析（如 RAR5）用游标类按 header 解析/条目校验分步。

❌ 反例：`persistDocument(String docId, String kbId, String path, String mime, long size, String hash, String operator, ...)` 10 参直落库。
✅ 正例：`persistDocument(DocumentImportContext ctx)`，ctx 为 record；事务方法内部的私有拆分保持在同一应用服务类内。

## 3. 异常处理（G.ERR.02 / G.ERR.05）

- **catch 收窄以 try 体实际异常面为准**：禁止 `catch (Exception e)` / `catch (RuntimeException e)` 兜底。编译器会拦截"主体抛不出"的受检异常，按 Tika/POI/IO 的真实异常面多 catch 具体类型；原有回退/保守兜底**语义必须保留**，只收窄类型。

❌ 反例：
```java
} catch (Exception e) {
    logger.warn("fallback", e);   // 兜底吞掉一切
}
```
✅ 正例：
```java
} catch (IOException | UncheckedIOException | DirectoryIteratorException e) {
    logger.warn("fallback", e);   // 类型收窄，兜底语义不变
}
```

- 私有辅助方法不要声明 `throws Exception`，写真实异常面（如 `GeneralSecurityException, IOException`）；测试 helper 同样收窄。
- 裸 `throw new RuntimeException(...)`（G.ERR.05）替换为继承 `BusinessException` 的语义化异常（如 `ConversationFileStorageException`、`FileStorageException`）。
- **边界兜底场景允许 catch 宽异常，但必须注释说明兜底语义**（本次实战确立的口径）。获准场景清单：SSE 流式解析逐帧透传、审计/统计消费（失败不影响主流程）、zip 解析回退到另一解析器、Redis 启动预载尽力而为、第三方解析库（未声明受检异常面）。

## 4. Optional 与 null 哨兵（G.MET.06）

- 公共 API "可能无值" 的返回一律 `Optional<T>`，调用点同步适配（`isEmpty()` 断言替代 `assertNull`）。

❌ 反例：`private String findExternalTool() { ...; return null; }`
✅ 正例：`private Optional<String> findExternalTool() { ...; return Optional.empty(); }`

- **"校验失败返回 null" 的私有方法应改为直接 `throw BusinessException`**（方法返回 void），调用方少一层 null 判断；reactive 上下文用 `Uni.failure(...)` 保持失败语义，不要 try/catch 包 reactive 链。
- DTO 组装等外部契约边界可用 `orElse(null)` 兜底，但要有注释。

## 5. 编码与 Locale（G.TYP.09 / G.TYP.08）

- 字节串转换一律显式 UTF-8：`"x".getBytes(StandardCharsets.UTF_8)`、`new String(bytes, StandardCharsets.UTF_8)`；无参 `getBytes()` 全仓禁止（含测试，缺 import 要补）。
- 大小写转换与数字格式化显式 `Locale.ROOT`（土耳其语环境 `toLowerCase()` 会踩 i/İ 坑）。

## 6. 其他速查

| 规则 | 要求 | 备注 |
| --- | --- | --- |
| G.OTH.03 | 只删确认 0 引用的 import | `// ==== xxx ====` 分节横幅注释不是注释代码，**禁止删**（本次 66 条全是此类误报，已记录申诉清单） |
| G.PRM.04 | 循环体内重建的 `Pattern` 提为 `static final` | `Pattern.compile` 很贵 |
| G.CTL.02 | `if / else if` 链必须有收尾 `else`（或抛异常） | 防漏分支静默吞掉 |
| G.SER.02 | `implements Serializable` 的类（含复合主键内部类）必须有 `serialVersionUID` | 非 Serializable 的外层实体类不要画蛇添足 |
| G.EXP.04 | 测试断言常量前置（`assertEquals(CONST, actual)`）；字符集等魔数提常量 | |
| G.CTL.01 / G.DCL.02 / G.TYP.13 等 | 魔法串用枚举/常量、一个声明一个用途、断言避免装箱歧义（`assertEquals(1L, x)` 对 `long` 返回值） | 按扫描 fragment 逐条对照，基线过期以当前代码内容定位 |

## 7. SQL/DDL 规范（GaussDB，docs/sql）

- **字符串类型用 `VARCHAR2(n)`**，不用 `CHAR`/`VARCHAR`（注释文本里的类型字样也要一致）；无长度列按语义补 32/128/255。
- **建表必带列存 + 压缩 + Hash 分布**：`WITH (ORIENTATION = COLUMN, COMPRESSION = LOW)` + `DISTRIBUTE BY HASH(主键列)`（无 id 主键的表取业务主键，如 `document_contents → document_id`）。
- 禁外键、禁非主键/唯一键的二级索引、禁数据库序列（主键用 `IDENTITY` 自增）。
- `INSERT` 必须写显式列清单。
- 宽泛授权（`GRANT ALL TABLES/SEQUENCES`）禁止；账号为 OWNER 时保留 `ALTER DEFAULT PRIVILEGES` 即可。
- **变更策略**：`init.sql` 直改；已发布环境走新增 migration（如 `migration_022_gaussdb_compliance.sql`），**不篡改历史 migration 语义**。
- 孤儿数据清理 SQL 的 NULL 判定用 `COALESCE(col,0)=0` + `UNION` 等价改写，不用 `OR col IS NULL OR ...`（走不了索引）。

## 8. 扫描整改流程（教训固化）

1. **基线会过期**：模块迁移后约 30% 条目指向已不存在的文件。整改一律**按当前代码内容定位**（fragment 里的类名/代码片段），不盲信行号与文件路径；基线过期条目归档说明，不硬改。
2. **共享组件原子化封装**：多模块共享的 PO（如 commons-dict）必须由**一个执行者一次性完成 PO 字段私有化 + 全部模块调用点适配**，禁止按模块并发分包处理——本次因此产生跨模块编译断裂。
3. **验证四件套**：`compileJava` + `compileTestJava` + `compileIntegrationTestJava` + 相关模块 `test` 全绿才算完成；IT 源集不跑 compile 就交付是本次最大返工源。
4. **不得以删功能/删测试换构建通过**；分节横幅注释、测试惯用通配 import 等误报项记录清单供申诉，不改动。

## 附录：已定案事项（下次扫描对照）

- **G.LOG.04 中文日志**：615 条已全量修复，项目约定日志内容使用英文；如再扫出中文日志，按本规范"约定"章节申诉口径处理。
- **llm 边界兜底宽 catch（7 处，获准保留）**：`SensitiveWordExcel`、`SensitiveWordCacheService`（RuntimeException 反序列化兜底）、`HitRateQueryService`、`HitRateStatsResource`、`ModelErrorFrameScanner`、`KbStatsCapture`、`SendMessageCommandService`——均为 SSE 透传/审计消费/Redis 尽力而为场景，已有兜底语义注释。复核时确认注释在位即可，不要求再收窄。
- **已知基线外同类问题（待下轮）**：`KbStatsPO` 19 个 public 字段；knowledge 4 个 dict 测试 8 处无参 `getBytes()`（Base64 编码测试 JWT 串）。