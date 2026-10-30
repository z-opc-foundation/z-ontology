# 模块结构

## 1. 模块清单

| 模块 | packaging | 依赖 | 说明 |
|------|-----------|------|------|
| `z-ontology`（根） | pom | `z-boot-parent:1.2.0` | 版本号、DM、flatten(oss)、`central` profile |
| `z-ontology-api` | jar | 无第三方 | 模型、SPI、异常 |
| `z-ontology-core` | jar | api、`z-util-core`、`z-util-parser-json` | 引擎与进程内实现 |
| `z-ontology-graph` | jar | api、`z-graph-api`、`z-graph-core` | z-graph 适配 + 默认 `InMemoryGraphStore` |
| `z-ontology-kb` | jar | api、`z-kb-api`（test: core） | z-kb 适配；`core` 仅测试期可见 |
| `z-ontology-spring-boot-starter` | jar | core、spring-boot-autoconfigure | 自动装配 + `zont.*` |
| `z-ontology-web` | jar | starter、spring-boot-starter-web/validation、swagger 注解、springdoc（optional） | REST 层 |
| `z-ontology-bootstrap` | jar（repackage） | web、graph、kb、log4j2 | 独立宿主 |

`core` 对 `z-ontology-api` 之外的领域依赖为零：z-kb / z-graph 都不会被引擎层感知。

## 2. 包结构

```
com.zifang.z.ontology
├── api
│   ├── model      OntologySchema / OntologyClass / OntologyProperty / Individual / Statement
│   │              InferenceResult / ValidationReport / ValidationIssue / MaterializationReport
│   │              OntologySnapshot / OntologyStats / PropertyKind / IssueSeverity / DataTypes
│   ├── graph      GraphNode / GraphEdge / Neighbor / Subgraph
│   ├── service    OntologyService / OntologyStore / OntologySource / OntologyGraphPort
│   └── exception  OntologyException / OntologyNotFoundException / InvalidOntologySchemaException
├── core
│   ├── schema     OntologySchemaValidator / InMemoryOntologyStore / JsonFileOntologyStore
│   ├── reasoning  RdfsReasoner
│   ├── validation OntologyValidator
│   ├── materialize OntologyMaterializer
│   ├── service    DefaultOntologyService
│   ├── graph      SimpleOntologyGraphPort
│   └── support    ClassHierarchy / LiteralValues
├── graph
│   ├── adapter    ZGraphOntologyPort
│   └── autoconfigure OntologyGraphAutoConfiguration
├── kb             KbSchemaFactory / KbOntologyMapper / KbOntologySource
│   └── autoconfigure KbOntologyAutoConfiguration / KbOntologyInitializer
├── starter
│   ├── config     OntologyProperties
│   └── autoconfigure OntologyAutoConfiguration
└── web
    ├── controller OntologySchemaController / OntologyRuntimeController
    ├── dto        ApiError
    ├── support    OntologyExceptionHandler
    └── autoconfigure OntologyWebAutoConfiguration
```

## 3. 自动装配文件

| 模块 | `META-INF/spring/...AutoConfiguration.imports` 内容 |
|------|------------------------------------------------------|
| graph | `com.zifang.z.ontology.graph.autoconfigure.OntologyGraphAutoConfiguration` |
| kb | `com.zifang.z.ontology.kb.autoconfigure.KbOntologyAutoConfiguration` |
| starter | `com.zifang.z.ontology.starter.autoconfigure.OntologyAutoConfiguration` |
| web | `com.zifang.z.ontology.web.autoconfigure.OntologyWebAutoConfiguration` |

## 4. 装配条件速查

| Bean | 条件 | 备注 |
|------|------|------|
| `GraphStore` | 缺省时 | `InMemoryGraphStore` |
| `OntologyGraphPort` | 缺省时 | `ZGraphOntologyPort` |
| `OntologyService` | `zont.enabled=true`（默认）+ 有 core 类 | 图端口取 `ObjectProvider`，取不到回落 `SimpleOntologyGraphPort` |
| `KbOntologySource` | `zont.kb.enabled` + 宿主有 `KnowledgeGraphStore` | 拉取上限 `zont.kb.limit` |
| `KbOntologyInitializer` | `zont.kb.auto-schema` | `SmartInitializingSingleton`，同版本 Schema 已存在则跳过 |
| Controllers | Servlet Web + 有 `OntologyService` | `@AutoConfiguration(after = OntologyAutoConfiguration.class)` |

## 5. 端口实现对照

| 行为 | `SimpleOntologyGraphPort`（core） | `ZGraphOntologyPort`（graph） |
|------|-----------------------------------|-------------------------------|
| 隔离 | `Map<String(scope) → Scope>`，scope = `ontologyId\0workspace` | z-graph `GraphStore` + 每 scope 的 key 索引 |
| reset | 移除整个 Scope | 先删本 scope 的边，再删节点（不动宿主其它节点） |
| upsertNode | 已存在则合并 labels/props | 已存在则 `updateNode` 合并；新建时主 label 取第一个 |
| upsertEdge | 两端可缺（只记 key） | 端点缺失直接抛 `OntologyException` |
| neighbors | 有向 BFS，OUT/IN + edgeType 过滤 | 同语义，走 `getOutEdges` / `getInEdges` |
| subgraph | 无向 BFS，只保留两端都在子图内的边 | 同语义 |
