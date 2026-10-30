# 总体设计

## 1. 为什么需要本体层

z-kb 的产出是「实体 + 实体关系」：`Entity`（canonicalName / type / aliases / …）与
`Relation`（type / source / target / weight / 证据块）。这些数据是一张**同质图**：
节点只有名字，边只有关系类型字符串，没有类型层次、没有约束、没有推理。

同一批数据在不同消费方眼里需要不同粒度：

* 检索侧只关心「谁和谁有关系」；
* 分析侧关心「这是不是一家公司」「它属于哪个组织层级」；
* 治理侧关心「这条关系违反了建模约定吗」。

本体层把这三种诉求统一到一份可声明、可校验、可推出的 Schema 上：**类**决定节点可以
有哪些身份，**属性公理**决定边合法不合法，**推理**把隐含知识（子类实例、逆关系、
传递闭包）显式化，**物化**把结果写成一张下游可直接查的图。

## 2. 边界

| 组件 | 管什么 | 不管什么 |
|------|--------|----------|
| z-kb | 文档接入、分块、向量化、实体关系抽取、检索、问答 | 类型体系、约束、推理 |
| z-graph | 图存储引擎（节点/边、索引、遍历） | 节点语义、Schema |
| **z-ontology** | 本体 Schema（TBox）、个体/断言（ABox）、推理、校验、物化映射 | 知识抽取、向量检索、图存储实现 |

三条硬边界：

1. `z-ontology-core` 不依赖 z-kb / z-graph，只依赖 `z-util`（JSON）——保证引擎可单测、可复用；
2. 与外部世界的接触面只有两个 SPI：`OntologySource`（数据从哪来）与 `OntologyGraphPort`（图写到哪去）；
3. 图节点 key / 边 key / label 的命名由本体层定义，后端只负责存取，不反向影响语义。

## 3. 核心流程

```
                  ┌──────────────┐
 OntologySource → │  推理        │ → 校验 → 物化 → OntologyGraphPort
 (kb / 内联快照)  │ RdfsReasoner │  OntologyValidator  OntologyMaterializer
                  └──────────────┘
```

1. **Schema 注册**：`POST /api/ontology/schemas` → `OntologySchemaValidator` 自检
   （引用存在、成环、kind 一致等）→ 有 ERROR 则 400 并带报告，否则落盘 `OntologyStore`。
2. **取数**：`materialize(ontologyId, workspace, sourceId)` 走 `OntologySource.load`；
   `materialize-inline` 直接用请求体里的 `OntologySnapshot`。
3. **推理**：`RdfsReasoner.infer(schema, individuals, statements)` 拷贝输入后做前向链，
   直到不动点（上限 1000 轮，不收敛抛 `OntologyException`）。
4. **校验**：`OntologyValidator.validate(schema, inference)` 产出 `ValidationReport`；
   有 ERROR 时仍会物化（调用方通过响应里的 `validation.valid` 决定是否采用）。
5. **物化**：`OntologyMaterializer.materialize(...)` 先 `port.reset(ontologyId, workspace)`
   清空该 scope，再写类节点 → 子类边 → 个体节点 → 实例化边 → 对象属性边 / 数据属性。

## 4. 关键决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 推理范围 | RDFS 子集 + 少量实用公理（inverse / symmetric / transitive / functional 校验） | OWL 复杂公理（sameAs、基数约束、DL 推理）成本远高于 M1 收益；显式声明「不做」避免误期 |
| 推理策略 | 前向链 + 不动点 | 数据量级是知识库实体（万级），物化一次性完成，简单可测；不做增量维护 |
| 校验与推理的先后 | 先推理后校验；domain/range 违反按**显式声明类型**判定 | 推理会把违反 domain/range 的类型补上，若用含推断的类型比对就永远「合法」；只看显式类型才能报出 `DOMAIN_VIOLATION` / `RANGE_VIOLATION` |
| 图后端 | `OntologyGraphPort` 抽象 + `z-graph` 适配 + 进程内兜底 | 单测不需要图引擎；宿主没装 z-graph 时也能启动 |
| 兜底方式 | starter 里 `ObjectProvider.getIfAvailable(supplier)` | 用「兜底 Bean」会和 z-graph 适配器抢装配顺序，`@ConditionalOnMissingBean` 在自动装配排序下不可靠 |
| 隔离维度 | `ontologyId + workspace` | 同一份图引擎里并存多套本体、多工作区；`reset` 只清自己的 scope |
| Schema 存储 | 每本体一个 JSON 文件，文件名 = id 的 URL-safe Base64 无填充 | id 可能含 `:` `/` 等字符；Base64 文件名可逆且不会逃出目录 |
| 重复物化 | 幂等（先 reset，节点/边 key 确定） | 保证反复物化不产生重复节点；`INSTANCE_OF` 用 `instance:<个体>:<类>` 去重 |

## 5. 自动装配顺序

* `OntologyGraphAutoConfiguration`（graph 模块）：`@ConditionalOnClass({GraphStore, InMemoryGraphStore})`，
  缺 `GraphStore` 时提供 `InMemoryGraphStore`，缺 `OntologyGraphPort` 时提供 `ZGraphOntologyPort`。
* `OntologyAutoConfiguration`（starter）：`@ConditionalOnProperty(zont.enabled)`，产出
  `JsonFileOntologyStore` / `RdfsReasoner` / `OntologyValidator` / `OntologyMaterializer` / `OntologyService`。
* `KbOntologyAutoConfiguration`（kb 模块）：`afterName` z-kb 的图谱装配，`@ConditionalOnBean(KnowledgeGraphStore)`
  才产出 `kb` 来源；`KbOntologyInitializer` 用 `SmartInitializingSingleton` 在全部单例就绪后登记 Schema。
* `OntologyWebAutoConfiguration`（web 模块）：`@AutoConfiguration(after = OntologyAutoConfiguration.class)`，
  `@ConditionalOnBean(OntologyService.class)` 时导入两组 Controller 与异常处理器。

## 6. 运行形态

* **独立服务**：`z-ontology-bootstrap`，端口 8890，装配 web + graph + kb；
* **嵌入宿主**：只依赖 starter，宿主自己提供图后端（或接受进程内兜底）与数据来源；
* **库**：直接依赖 `z-ontology-core`，自己 new `DefaultOntologyService`（测试里就是这么用的）。
