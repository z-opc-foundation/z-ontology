# 测试报告

> 实测时间：2026-10-30 · 命令：`mvn -o clean verify`（全 reactor）· 结论：**BUILD SUCCESS，46 例全绿**

## 1. 汇总

| 模块 | 测试类 | 用例数 | 结果 |
|------|--------|--------|------|
| `z-ontology-api` | —（纯模型 / SPI） | 0 | — |
| `z-ontology-core` | `RdfsReasonerTest` / `OntologyValidatorTest` / `OntologyMaterializerTest` / `JsonFileOntologyStoreTest` / `DefaultOntologyServiceTest` | 28 | ✅ |
| `z-ontology-graph` | `ZGraphOntologyPortTest` | 6 | ✅ |
| `z-ontology-kb` | `KbSchemaFactoryTest` / `KbOntologyMapperTest` / `KbOntologySourceTest` | 7 | ✅ |
| `z-ontology-spring-boot-starter` | —（装配由 web / bootstrap 覆盖） | 0 | — |
| `z-ontology-web` | `OntologyRestApiTest`（`@SpringBootTest` RANDOM_PORT + 真 HTTP） | 3 | ✅ |
| `z-ontology-bootstrap` | `ZOntologyBootstrapApplicationTest`（真启动上下文） | 2 | ✅ |
| **合计** | | **46** | ✅ |

## 2. 用例清单

### core（28）

| 类 | 用例 | 覆盖点 |
|----|------|--------|
| `RdfsReasonerTest`（6） | `inheritsSuperClassTypes` / `computesTransitiveClosure` / `addsInverseAndSymmetricStatements` / `expandsSubPropertyOfStatements` / `infersTypesFromDomainAndRange` / `keepsExplicitStatementsExplicit` | R1–R6 规则、显式/推断标记不互相污染 |
| `OntologyValidatorTest`（7） | `acceptsConsistentData` / `reportsUnknownClassAndProperty` / `reportsDisjointViolation` / `reportsDomainAndRangeViolations` / `reportsFunctionalConflict` / `reportsLiteralMismatchAndInvalidLiteral` / `warnsUntypedIndividualsAndUnknownEndpoints` | 11 个数据校验码逐项命中，WARNING 不影响 `valid` |
| `OntologyMaterializerTest`（4） | `materializesClassesInstancesAndPropertyEdges` / `writesExplicitAndInferredInstanceEdges` / `createsPlaceholderForUndeclaredObject` / `rematerializationDoesNotDuplicateNodes` | 节点/边形状、`data:age` 转 Integer、`inferred` 标记、占位个体、幂等 |
| `JsonFileOntologyStoreTest`（5） | 存取/列举/删除、损坏文件跳过、id 路径穿越不越界、`~` 展开、时间戳 | 文件存储边界 |
| `DefaultOntologyServiceTest`（6） | Schema 注册/查询/删除、非法 Schema 抛报告、内联物化 + 四类查询 + stats、来源校验、未知本体/来源、只推理不物化 | 服务门面全方法 |

### graph（6）

`ZGraphOntologyPortTest`：节点/边 roundtrip；按 ontology/workspace 隔离且 `reset` 只清自己；
属性 merge；子图 depth=1/2 的节点与边数并验证不跨端点的边被排除；边端点缺失抛 `OntologyException`；
`reset` 不动宿主 `GraphStore` 里的其它节点。

### kb（7）

| 类 | 用例 | 覆盖点 |
|----|------|--------|
| `KbSchemaFactoryTest`（2） | Schema 形状（根类 + 每 EntityType 一类 + 每 RelationType 一属性，domain/range = Entity）、`classNames()` |
| `KbOntologyMapperTest`（3） | Entity→Individual（key/name/types/属性/sources）、Relation→Statement（原样谓词 / 权重 / 证据）、缺省类型回落 `CONCEPT` |
| `KbOntologySourceTest`（2） | 来源 id/描述与实体-关系去重；**端到端**：Fake `KnowledgeGraphStore` → 生成 Schema 注册 → `materialize` → `instances(PERSON) = alice, bob` |

### web（3）

`OntologyRestApiTest` 走真实 HTTP：注册 Schema（201）→ 内联物化（2 个个体、校验有效）→
`instances` / `stats` / `neighbors` 三个查询形状；未知本体 404；非法 Schema 400 且
`validation.issues[0].code = UNKNOWN_SUPER_CLASS`。

### bootstrap（2）

`ZOntologyBootstrapApplicationTest`：`kb` Schema 启动即登记；图端口是 `ZGraphOntologyPort`；
对 kb Schema 物化 PERSON 实例可用。

## 3. 本轮修掉的问题（真跑才暴露）

| 问题 | 现象 | 根因 | 修法 |
|------|------|------|------|
| `SimpleOntologyGraphPort` 读不到数据 | 写入后 `findNode` 恒 null、`nodeCount` 恒 0 | 读路径把 `scopeOf(...)` 返回的 `Scope` **对象**当 map key 去 `get`，写路径用的却是 `ontologyId\0workspace` 字符串 | 抽出 `scopeKey(...)`，所有读路径 `scopes.get(scopeKey(...))` |
| 推断语句被降级成显式 | `alice worksFor acme` 的 `inferred` 变回 false | 派生语句构造时 `inferred` 默认 false，`merge` 按“显式优先”把已有推断结果改回显式 | `addStatement` 里先 `derived.setInferred(true)` 再合并 |
| 物化从不写 `INSTANCE_OF` 边 | 图的实例查询全空 | materializer 注释写了但代码没实现 | 补类型/推断类型两条循环 + `instanceEdge` 辅助方法 |
| domain/range 违反永远报不出 | `acme(Organization) worksFor alice` 校验通过 | 推理先把 acme 补成 Person、alice 补成 Organization，再用 `allTypes` 比对自然“满足” | 改为与**显式 `types`** 比对 |
| `UNKNOWN_OBJECT` 漏报 | 无 range 的对象属性指向未声明个体时不告警 | 该告警写在 `range != null` 分支里 | 提到 branch 外，与 range 解耦 |
| 单模块跑测试拿到旧产物 | `-pl z-ontology-web` / `-rf` 跑出的失败与全 reactor 不一致 | 跳过 core 构建时 Maven 从 `~/.m2` 解析旧 `z-ontology-core` | 验收一律 `mvn -o clean verify`（全 reactor） |

另：web 无效 Schema 用例原先请求体缺 `name`，命中的是 `SCHEMA_NAME_MISSING`（按 code 排序在首），
已补 `name` 让用例聚焦 `UNKNOWN_SUPER_CLASS`。

## 4. 冒烟（独立进程，非测试用例）

`java -jar z-ontology-bootstrap-1.0.0.jar --server.port=18890`（8890 被本机 Docker 占用）：

* `/actuator/health` → `UP`；
* 启动即自动登记 `kb` Schema 并落盘 `~/.zont/ontologies/a2I.json`；
* `/api/ontology/sources` → `[]`（bootstrap 宿主里没有 z-kb 的 `KnowledgeGraphStore`，符合预期）；
* 对 `kb` Schema 内联物化：13 个类节点（`Entity` + 12 个 `EntityType`）、2 个个体、13 条边，`validation.valid=true`；
* `instances` / `neighbors` / `subgraph` / `stats` 形状正确；
* 非法数据校验返回 `UNKNOWN_CLASS` + `UNKNOWN_PROPERTY`；非法 Schema POST 返回 400 + 校验报告。

> 注：bootstrap 里 log4j2 是显式加的（父链会把 `spring-boot-starter-logging` 的传递依赖清空），
> 不加会出现 “No SLF4J providers were found” 且看不到启动日志。

> 全链路（REST 全接口 / 图改写与 fork / 重启持久化）验收见 [02-fullstack-acceptance-2026-10-31.md](02-fullstack-acceptance-2026-10-31.md)。
