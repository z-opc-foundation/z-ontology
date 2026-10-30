# REST API 契约

Base path：`/api/ontology`（bootstrap 默认 `http://localhost:8890`）。
所有运行时接口都接受可选查询参数 `workspace`，缺省取 `zont.default-workspace`（默认 `default`），
入参 workspace 会 trim。

## 1. Schema 管理

### GET `/api/ontology/schemas`

返回 `OntologySchema[]`。磁盘目录里的每个 JSON 文件对应一个本体（损坏文件会被跳过并打日志）。

### GET `/api/ontology/schemas/{ontologyId}`

* 200：`OntologySchema`
* 404：`OntologyNotFoundException` → `{"error":"ONTOLOGY_NOT_FOUND","message":"Ontology not found: <id>"}`

### POST `/api/ontology/schemas`

请求体即 `OntologySchema`：

```json
{
  "id": "people",
  "name": "People",
  "version": "1.0.0",
  "description": "人 / 组织示例",
  "prefixes": {"ex": "http://example.com/"},
  "classes": [
    {"name": "Entity"},
    {"name": "Person", "superClasses": ["Entity"], "disjointWith": ["Organization"]},
    {"name": "Organization", "superClasses": ["Entity"], "disjointWith": ["Person"]}
  ],
  "properties": [
    {"name": "worksFor", "kind": "OBJECT", "domain": "Person", "range": "Organization"},
    {"name": "age", "kind": "DATA", "range": "INT"}
  ]
}
```

* 201：落盘后的 `OntologySchema`（`createdAt` / `updatedAt` 自动补）
* 400：`{"error":"INVALID_ONTOLOGY_SCHEMA","message":"...","validation":{...}}`

Schema 校验码（`OntologySchemaValidator`）：

| 严重度 | code |
|--------|------|
| ERROR | `SCHEMA_REQUIRED`、`SCHEMA_ID_REQUIRED`、`CLASS_NAME_REQUIRED`、`DUPLICATE_CLASS`、`INVALID_CLASS_NAME`、`PROPERTY_NAME_REQUIRED`、`DUPLICATE_PROPERTY`、`INVALID_PROPERTY_NAME`、`RESERVED_PROPERTY_NAME`、`INVALID_SUPER_CLASS`、`UNKNOWN_SUPER_CLASS`、`INVALID_DISJOINT`、`UNKNOWN_DISJOINT_CLASS`、`UNKNOWN_DOMAIN`、`UNKNOWN_RANGE`、`UNKNOWN_DATATYPE`、`UNKNOWN_SUPER_PROPERTY`、`PROPERTY_KIND_MISMATCH`、`UNKNOWN_INVERSE`、`INVERSE_NOT_OBJECT`、`INVERSE_SELF`、`TRANSITIVE_DATA_PROPERTY`、`SYMMETRIC_DATA_PROPERTY`、`CLASS_CYCLE`、`PROPERTY_CYCLE` |
| WARNING | `SCHEMA_NAME_MISSING` |

`SUB_CLASS_OF` / `INSTANCE_OF` 是物化用的保留谓词名，不能声明为属性。

### DELETE `/api/ontology/schemas/{ontologyId}`

204（不存在也返回 204）。

## 2. 运行时

请求/响应模型：

```jsonc
// OntologySnapshot —— infer / validate / materialize-inline 的请求体
{
  "workspace": "team",                 // 仅 materialize-inline 在未传 ?workspace 时使用
  "individuals": [
    {"key": "alice", "name": "Alice", "label": "爱丽丝", "types": ["Person"],
     "properties": {"dept": "R&D"}, "sources": ["doc-1", "chunk-9"]}
  ],
  "statements": [
    {"subject": "alice", "predicate": "worksFor", "object": "acme",
     "literal": false, "weight": 1.0, "sources": ["chunk-9"]},
    {"subject": "alice", "predicate": "age", "object": "35", "literal": true}
  ]
}
```

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/sources` | `[{"id":"kb","description":"z-kb 知识图谱（实体 + 关系）"}]` |
| POST | `/{id}/infer` | 只推理，不落图。响应 `InferenceResult{individuals, statements, explicitStatements, inferredStatements, explicitTypes, inferredTypes}` |
| POST | `/{id}/validate` | 推理 + 校验。响应 `ValidationReport{issues, valid, errorCount, warningCount, infoCount}` |
| GET | `/{id}/validate-source?source=` | 先 `OntologySource.load` 再校验 |
| POST | `/{id}/materialize?source=` | 取来源数据 → 推理 → 校验 → 物化 |
| POST | `/{id}/materialize-inline[?workspace=]` | 内联 `OntologySnapshot` → 同上 |
| GET | `/{id}/classes` | `GraphNode[]`（label 含 `OntologyClass`） |
| GET | `/{id}/individuals` | `GraphNode[]`（label 含 `OntologyIndividual`） |
| GET | `/{id}/instances?className=` | 按个体属性 `allTypes` 过滤（含推断类型） |
| GET | `/{id}/neighbors?key=&depth=1&edgeType=` | `Neighbor[]{node, edge, direction(OUT/IN), depth}`；depth 上限 10 |
| GET | `/{id}/subgraph?keys=a&keys=b&depth=1` | `Subgraph{nodes, edges}`；无向 BFS，只保留两端都在子图里的边 |
| GET | `/{id}/stats` | `OntologyStats`（见下） |

物化响应 `MaterializationReport`：

```json
{
  "ontologyId": "people",
  "workspace": "team",
  "sourceId": "inline",
  "classNodes": 4,
  "individualNodes": 2,
  "edges": 5,
  "explicitStatements": 1,
  "inferredStatements": 0,
  "durationMs": 3,
  "materializedAt": "2026-10-30T15:34:14.350569Z",
  "validation": {"valid": true, "errorCount": 0, "warningCount": 0, "infoCount": 0, "issues": []}
}
```

`OntologyStats`：`{ontologyId, workspace, classNodes, individualNodes, nodeCount, edgeCount,
explicitStatements, inferredStatements, lastMaterializedAt}` —— 前六项实时取自图端口，
后三项取自本进程最近一次物化的报告（重启后为空）。

## 3. 数据校验码（`OntologyValidator`）

| 严重度 | code | 触发条件 |
|--------|------|----------|
| ERROR | `UNKNOWN_CLASS` | 个体类型不在 Schema 中 |
| ERROR | `UNKNOWN_PROPERTY` | 断言的谓词不在 Schema 中 |
| ERROR | `DOMAIN_VIOLATION` | 主语**显式声明**的类型都不是 domain 的子类 |
| ERROR | `RANGE_VIOLATION` | 宾语**显式声明**的类型都不是 range 的子类 |
| ERROR | `DISJOINT_VIOLATION` | 个体同时属于互斥类（含推断类型） |
| ERROR | `FUNCTIONAL_CONFLICT` | 函数性属性出现多个不同取值（只看显式断言） |
| ERROR | `LITERAL_MISMATCH` | 对象属性给了字面量，或数据属性给了实体 |
| ERROR | `INVALID_LITERAL` | 字面量与声明的数据类型不符 |
| WARNING | `UNKNOWN_SUBJECT` | 显式断言的主语不是已声明个体 |
| WARNING | `UNKNOWN_OBJECT` | 对象属性断言的宾语不是已声明个体 |
| WARNING | `UNTYPED_INDIVIDUAL` | 个体没有任何类型 |

只有 WARNING 时 `valid = true`。

## 4. 错误体

```json
{"error": "ONTOLOGY_NOT_FOUND", "message": "Ontology not found: ghost"}
{"error": "INVALID_ONTOLOGY_SCHEMA", "message": "Invalid ontology schema: bad", "validation": {"issues": [...]}}
```

| HTTP | error | 场景 |
|------|-------|------|
| 404 | `ONTOLOGY_NOT_FOUND` | 本体不存在 |
| 400 | `INVALID_ONTOLOGY_SCHEMA` | Schema 自检有 ERROR |
| 400 | `ONTOLOGY_ERROR` | `OntologyException`（含推理不收敛） |
| 400 | `INVALID_ARGUMENT` | `IllegalArgumentException` |
