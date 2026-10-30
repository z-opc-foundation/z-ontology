# z-ontology

> 本体层 —— 给知识一个**有类型、有约束、可推理**的形状：TBox（类 / 属性 / 公理）+ ABox（个体 / 断言）→ RDFS 子集推理 → 一致性校验 → 物化成一棵可查询的图。

`z-kb` 负责「知识是什么」（文档 → 块 → 实体 / 关系 → 检索），`z-graph` 负责「图存在哪」，
`z-ontology` 负责中间的「知识应该长成什么形状」：声明类层次、属性 domain/range、互斥类、
函数性属性等公理，把 z-kb 抽出来的实体关系按这个 Schema 校验、补全（推断子类 / 逆属性 /
传递闭包），再物化成 z-graph 里一张带 `OntologyClass` / `OntologyIndividual` 标签的图。

本仓**不依赖 z-kb 也能独立跑**（`z-ontology-core` 零外部领域依赖），z-kb 只在
`z-ontology-kb` 适配模块里以 `z-kb-api` 的形式接进来；同理图后端通过 `OntologyGraphPort`
抽象，`z-ontology-graph` 是 z-graph 的适配器，没装 z-graph 时 starter 会回落到进程内实现。

---

## 📋 基本信息

| 字段 | 值 |
|------|-----|
| **仓库** | `z-ontology` |
| **Maven 坐标** | `io.github.yuku123:z-ontology:1.0.0`（字面版本号，非 `${revision}`；flatten-maven-plugin 1.5.0 `oss` 模式常开） |
| **父项目** | `io.github.yuku123:z-boot-parent:1.2.0`（`<relativePath/>` 留空） |
| **对外发布** | **尚未发到 Maven Central**；`-Pcentral` 已备好（签名 / sources / javadoc / central-publishing），发版属人工保留项 |
| **默认端口** | `8890`（`z-ontology-bootstrap/src/main/resources/application.yml`） |
| **运行口径** | Java 17 · Spring Boot 3.3.13（父链下发） |
| **接入面版本** | `z-kb-api` 1.1.0 · `z-graph-api/core` 1.0.8 · `z-util-core/parser-json/dsl-kernel` 1.2.2（根 pom `properties` 显式顶住父链） |
| **最近更新** | 2026-10-30 |

---

## 🧩 模块结构

| 模块 | 职责 | 关键类 |
|------|------|--------|
| `z-ontology-api` | 纯契约：模型 + SPI + 异常 | `OntologySchema` / `OntologyClass` / `OntologyProperty` / `Individual` / `Statement`、`OntologyService`、`OntologyGraphPort`、`OntologySource`、`OntologyStore` |
| `z-ontology-core` | 引擎：Schema 校验、RDFS 子集推理、一致性校验、物化、文件存储、进程内图端口 | `OntologySchemaValidator`、`RdfsReasoner`、`OntologyValidator`、`OntologyMaterializer`、`DefaultOntologyService`、`JsonFileOntologyStore`、`SimpleOntologyGraphPort`、`ClassHierarchy`、`LiteralValues` |
| `z-ontology-graph` | z-graph 适配器（`GraphStore` → `OntologyGraphPort`），并为其提供默认 `InMemoryGraphStore` Bean | `ZGraphOntologyPort`、`OntologyGraphAutoConfiguration` |
| `z-ontology-kb` | z-kb 适配：由 `EntityType` / `RelationType` 生成默认 Schema；把 `Entity` / `Relation` 映射成个体 / 断言；注册为 `OntologySource("kb")` | `KbSchemaFactory`、`KbOntologyMapper`、`KbOntologySource`、`KbOntologyInitializer` |
| `z-ontology-spring-boot-starter` | 自动装配：`zont.*` 配置、存储、引擎、`OntologyService`；没有图端口时回落进程内 | `OntologyProperties`、`OntologyAutoConfiguration` |
| `z-ontology-web` | REST：`/api/ontology/*`（Schema 管理、推理、校验、物化、图查询）+ springdoc | `OntologySchemaController`、`OntologyRuntimeController`、`OntologyExceptionHandler` |
| `z-ontology-bootstrap` | 可独立启动的宿主（web + graph + kb 三件套装配、log4j2） | `ZOntologyBootstrapApplication` |

依赖方向：

```
api ← core ← starter ← web ← bootstrap
        ↑        ↑
      graph      │
        ↑        │
       kb ───────┘
```

`core` 不依赖 `graph` / `kb`；`kb` 只依赖 `z-kb-api`（不依赖 z-kb 实现）；
`graph` 只依赖 `z-graph-api` / `z-graph-core`。

---

## 🎯 能力清单

| 能力 | 实现 |
|------|------|
| 本体建模（TBox） | 类（label / description / `superClasses` / `disjointWith`）；对象属性（domain / range / `superProperties` / `inverseOf` / symmetric / transitive）；数据属性（domain / 8 种字面量类型 / functional） |
| 数据建模（ABox） | `Individual`（key / name / label / types / properties / sources）、`Statement`（subject / predicate / object / literal / weight / sources） |
| Schema 自检 | `OntologySchemaValidator`：必填项、重名、空白字符、引用存在性、父属性 kind 一致、逆属性合法性、`subClassOf` / `subPropertyOf` 成环 |
| RDFS 子集推理 | `RdfsReasoner`：子类传递闭包 → 类型继承、`subPropertyOf` 断言扩展、domain/range 类型推断、`inverseOf` / symmetric 反向断言、transitive 传递闭包；不动点迭代（上限 1000 轮） |
| 一致性校验 | `OntologyValidator`：未知类/属性、domain/range 违反、互斥类冲突、函数性冲突、字面量类型不匹配/非法、未知主语/宾语、未类型个体 |
| 物化到图 | `OntologyMaterializer`：类节点、个体节点、`SUB_CLASS_OF` 边、`INSTANCE_OF` 边（`inferred` 标记显式/推断）、对象属性边、数据属性落到节点属性 |
| 图后端抽象 | `OntologyGraphPort`：按 `ontologyId + workspace` 隔离；`z-graph` 适配器与进程内兜底实现语义一致 |
| z-kb 接入 | `KbSchemaFactory`（12 个 `EntityType` 类 + 17 个 `RelationType` 对象属性（均含 `OTHER`））→ `KbOntologyMapper` → `KbOntologySource`，自动登记 `kb` Schema |
| REST + OpenAPI | 两组 Controller + `ApiError` 统一错误体；springdoc `/v3/api-docs`、`/swagger-ui.html` |

---

## 🗺️ 图模型约定（物化结果）

| 对象 | key | labels / type | 说明 |
|------|-----|---------------|------|
| 类节点 | `class:<类名>` | `OntologyClass` + 类名 | 属性含 `name` / `label` / `description` / `superClasses` / `disjointWith` |
| 个体节点 | `individual:<个体 key>` | `OntologyIndividual` + 全部类型 | 属性含 `types` / `inferredTypes` / `allTypes` / `attributesJson` 等；数据属性为 `data:<谓词>` |
| 子类边 | `subclass:<子>:<父>` | `SUB_CLASS_OF` | 类 → 父类 |
| 实例化边 | `instance:<个体>:<类>` | `INSTANCE_OF` | 个体 → 类，`inferred` 区分显式 / 推断 |
| 对象属性边 | `stmt:<主>:<谓>:<宾>` | 谓词名 | 属性含 `predicate` / `weight` / `inferred` / `sources` |

Schema 的 JSON 落盘为每个本体一个文件（文件名 = 本体 id 的 URL-safe Base64 无填充），
默认目录 `~/.zont/ontologies`。物化图默认在进程内存（与 z-kb 默认图谱口径一致），
重启不保留；要持久化请把 `OntologyGraphPort` 换成持久图后端。

---

## ⚙️ 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `zont.enabled` | `true` | 关闭后整个自动装配不生效 |
| `zont.storage-path` | `~/.zont/ontologies` | Schema JSON 目录，支持 `~` 展开 |
| `zont.default-workspace` | `default` | 查询/物化未指定 workspace 时的回退 |
| `zont.kb.enabled` | `true` | 是否注册 `kb` 来源（宿主须有 `KnowledgeGraphStore`） |
| `zont.kb.limit` | `5000` | 从 z-kb 拉实体上限（达到上限会告警） |
| `zont.kb.auto-schema` | `true` | 启动时登记 `kb` 默认 Schema（同版本已存在则跳过） |

---

## 🌐 REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/ontology/schemas` | 列出全部本体 |
| GET | `/api/ontology/schemas/{id}` | 取单个本体（不存在 404） |
| POST | `/api/ontology/schemas` | 注册本体（Schema 不合法 400 + 校验报告） |
| DELETE | `/api/ontology/schemas/{id}` | 删除本体（204） |
| GET | `/api/ontology/sources` | 已注册知识来源（id / description） |
| POST | `/api/ontology/{id}/infer` | 只推理，返回 `InferenceResult` |
| POST | `/api/ontology/{id}/validate` | 只校验，返回 `ValidationReport` |
| GET | `/api/ontology/{id}/validate-source?source=` | 拉取来源数据后校验 |
| POST | `/api/ontology/{id}/materialize?source=` | 拉取来源数据 → 推理 → 校验 → 物化 |
| POST | `/api/ontology/{id}/materialize-inline` | 内联 `OntologySnapshot` → 同上 |
| GET | `/api/ontology/{id}/classes` | 类节点 |
| GET | `/api/ontology/{id}/individuals` | 个体节点 |
| GET | `/api/ontology/{id}/instances?className=` | 某类的实例（按 `allTypes`） |
| GET | `/api/ontology/{id}/neighbors?key=&depth=&edgeType=` | 邻接查询（depth 上限 10） |
| GET | `/api/ontology/{id}/subgraph?keys=&depth=` | 子图（无向 BFS，只保留两端都在子图内的边） |
| GET | `/api/ontology/{id}/stats` | 节点/边数量与最近一次物化统计 |

所有运行时接口都接受可选 `workspace` 参数，缺省取 `zont.default-workspace`。
错误统一为 `{error, message, validation?}`。

---

## 🚀 快速开始

```bash
# 构建 + 全部测试
mvn clean verify

# 跑独立服务（默认 8890；本机若被占用可加 --server.port=18890）
java -jar z-ontology-bootstrap/target/z-ontology-bootstrap-1.0.0.jar
```

注册一个本体并物化内联数据：

```bash
curl -s -X POST localhost:8890/api/ontology/schemas -H 'Content-Type: application/json' -d '{
  "id": "people", "name": "People",
  "classes": [
    {"name": "Entity"},
    {"name": "Person", "superClasses": ["Entity"], "disjointWith": ["Organization"]},
    {"name": "Organization", "superClasses": ["Entity"], "disjointWith": ["Person"]}
  ],
  "properties": [
    {"name": "worksFor", "kind": "OBJECT", "domain": "Person", "range": "Organization"}
  ]
}'

curl -s -X POST 'localhost:8890/api/ontology/people/materialize-inline?workspace=team' \
  -H 'Content-Type: application/json' -d '{
    "individuals": [
      {"key": "alice", "name": "Alice", "types": ["Person"]},
      {"key": "acme", "name": "Acme", "types": ["Organization"]}
    ],
    "statements": [{"subject": "alice", "predicate": "worksFor", "object": "acme"}]
  }'

curl -s 'localhost:8890/api/ontology/people/instances?workspace=team&className=Person'
```

嵌入宿主应用：依赖 `z-ontology-spring-boot-starter`（+ 需要 z-graph 图后端时再依赖
`z-ontology-graph`，需要 z-kb 数据来源时再依赖 `z-ontology-kb`），注入 `OntologyService` 即可。
注意父链 `z-boot-parent` 会把 `spring-boot-starter-logging` 的传递依赖全清空，宿主需自带
日志实现（本仓 bootstrap 用的是 log4j2）。

---

## 文档目录

| 文档 | 内容 |
|------|------|
| [`_doc/001_arch/00-overview.md`](_doc/001_arch/00-overview.md) | 总体设计：定位、边界、核心流程、关键决策 |
| [`_doc/001_arch/01-module-structure.md`](_doc/001_arch/01-module-structure.md) | 模块划分、依赖方向、自动装配条件与顺序 |
| [`_doc/001_arch/02-api.md`](_doc/001_arch/02-api.md) | REST 契约、请求/响应模型、错误码 |
| [`_doc/001_arch/03-ontology-model.md`](_doc/001_arch/03-ontology-model.md) | 本体模型、推理规则、校验码、图映射规则 |
| [`_doc/001_arch/07-roadmap.md`](_doc/001_arch/07-roadmap.md) | M1 范围、已知限制、后续演进 |
| [`_doc/001_arch/08-rewrite-and-fork.md`](_doc/001_arch/08-rewrite-and-fork.md) | 图改写设计稿：A→B 本体 DSL / fork / pipeline（未实现） |
| [`_doc/005_testing/01-test-report.md`](_doc/005_testing/01-test-report.md) | 测试清单与实测结论 |

---

## 许可

[MIT](LICENSE) © z-opc-foundation
