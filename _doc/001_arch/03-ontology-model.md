# 本体模型与语义

## 1. TBox：Schema

```
OntologySchema
├── id / name / version / description / prefixes
├── classes: OntologyClass[]
│     name, label, description, superClasses[], disjointWith[]
└── properties: OntologyProperty[]
      name, label, description, kind(OBJECT|DATA), domain, range,
      superProperties[], inverseOf, symmetric, transitive, functional
```

* 类名、属性名不能含空白字符；`SUB_CLASS_OF` / `INSTANCE_OF` 是物化保留名，不能作为属性声明。
* `kind` 缺省按 `OBJECT` 处理。
* `range`：OBJECT 属性指向类名；DATA 属性取 `DataTypes.SUPPORTED`：
  `STRING / INT / LONG / DOUBLE / BOOLEAN / DATE / DATETIME / ANY`。
* `inverseOf` 必须是对象属性且不能指向自己；`superProperties` 的 kind 必须与本属性一致。
* `subClassOf` / `subPropertyOf` 允许声明多条，但成环视为 Schema 错误（`CLASS_CYCLE` / `PROPERTY_CYCLE`）。

### Schema 自检与数据校验的分工

| 阶段 | 校验器 | 关注点 |
|------|--------|--------|
| 注册 Schema | `OntologySchemaValidator` | Schema 自身是否自洽（引用存在、不成环、kind 一致、保留名） |
| 物化/校验数据 | `OntologyValidator` | 实例数据是否满足 Schema 公理 |

## 2. ABox：个体与断言

```java
Individual(key, name, label, types, inferredTypes, properties, sources)
Statement(subject, predicate, object, literal, weight, sources)
```

* `Individual.key` 是图中的稳定标识；`name` 是带进图的展示名。
* `properties` 是个体的**业务属性**（如 `dept`），物化时会以 `attributesJson` 存成 JSON 字符串，
  不参与推理。
* `Statement.object` 统一是字符串；`literal=true` 表示字面量，否则是另一个个体的 key。
* `weight` 用于 z-kb 关系强度透传；`sources` 记录证据（文档 / 块 id），会带进图边属性。
* 输入中的 `inferred` 字段会被推理器忽略（输入一律视为显式）。

## 3. 推理规则（`RdfsReasoner`）

前向链，每轮依次应用以下规则，任一轮无新增即停止：

| # | 规则 | 形式 | 结果 |
|---|------|------|------|
| R1 | subClassOf 闭包 | `x : C`, `C ⊑ D*` | `x : D`（写入 `inferredTypes`） |
| R2 | subPropertyOf 扩展 | `x p y`, `p ⊑ q` | `x q y`（推断语句） |
| R3 | domain 推断 | `x p y`, `domain(p)=C` | `x : C` |
| R4 | range 推断 | `x p y`（对象属性）, `range(p)=C` | `y : C` |
| R5 | 逆属性 / 对称 | `x p y`, `inverseOf(p)=q` 或 `symmetric(p)` | `y q x` |
| R6 | 传递闭包 | `x p y`, `y p z`, `transitive(p)` | `x p z` |

补充约定：

* 派生语句构造后立即标 `inferred=true`；与已有显式语句撞 key 时只合并权重与来源，**不会**把显式语句降级。
* 传递语句的权重取两端较小值；其余派生语句继承原语句权重。
* 不动点上限 `MAX_ROUNDS = 1000`，超限抛 `OntologyException`（防止公理组合导致图膨胀失控）。
* 不实现：sameAs / 等价类、基数约束、否定、DL 一致性判定。functional 只在校验阶段报冲突。

## 4. 一致性校验（`OntologyValidator`）

* 未知类 / 未知属性：按个体 `allTypes` 与断言谓词比对 Schema。
* 互斥类：`allTypes` 中任意两类在类层次上互斥即 `DISJOINT_VIOLATION`（含推断出的类型）。
* **domain / range 违反只看显式声明的类型**：推理阶段会把满足 domain/range 的类型补上，
  若拿 `allTypes` 比对，`x(Organization) worksFor y` 会被推理补成 `x : Person` 从而永远“合法”；
  只看 `types` 才能报出 `DOMAIN_VIOLATION` / `RANGE_VIOLATION`。
  若个体没有显式类型，则跳过 domain/range 判定（由 `UNTYPED_INDIVIDUAL` 提示）。
* `UNKNOWN_OBJECT` 与 range 无关：只要是对象属性断言的宾语没被声明成个体就告警。
* 函数性冲突只统计显式断言；推断出的同值语句不参与。
* 同一 (code, subject, predicate, message) 的 issue 去重；输出按 severity → code → subject 排序。

## 5. 物化映射（`OntologyMaterializer`）

物化前先 `port.reset(ontologyId, workspace)`，然后：

| 步骤 | 写入 |
|------|------|
| 1 | 每个类 → `class:<name>`，labels = `[OntologyClass, <name>]`，属性 `name/label/description/superClasses/disjointWith` |
| 2 | 每条 superClasses → `SUB_CLASS_OF` 边（key `subclass:<子>:<父>`），`inferred=false` |
| 3 | 每个个体 → `individual:<key>`，labels = `[OntologyIndividual, ...allTypes]`，属性含 `types/inferredTypes/allTypes/sources/attributesJson` |
| 4 | 每条显式类型 → `INSTANCE_OF` 边（key `instance:<个体>:<类>`，`inferred=false`）；每条推断类型 → 同 key 规则，`inferred=true` |
| 5 | 每条非字面量语句 → 以谓词为 type 的边（key `stmt:<主>:<谓>:<宾>`，属性 `predicate/weight/inferred/sources`）；主语/宾语未声明时补占位个体（`placeholder=true`） |
| 6 | 每条字面量语句 → 合并到主语节点的 `data:<谓词>` 属性，按 range 转换类型（INT→Integer、LONG→Long、DOUBLE→Double、BOOLEAN→Boolean，其余保留字符串）；同谓词多值合并为数组 |

* 若类型不在 Schema 中则跳过 `INSTANCE_OF` 边（避免 z-graph 端口因悬空端点抛错）。
* 节点/边 key 全部由本体层决定，重复物化幂等。

## 6. z-kb 映射（`z-ontology-kb`）

默认 Schema（`KbSchemaFactory`，id = `kb`，version = `1.0.0`）：

* 根类 `Entity`；每个 `z-kb.api.EntityType` 一个子类（共 12 个，含 `OTHER`）；
* 每个 `z-kb.api.RelationType` 一个对象属性（共 17 个，含 `OTHER`），`domain = range = Entity`。

数据映射（`KbOntologyMapper`）：

| 来源 | 目标 |
|------|------|
| `Entity.canonicalName` | `Individual.key` 与 `name`（**canonicalName 为空则整条实体跳过**，不退回 id） |
| `Entity.displayName` | `Individual.label`（为空时用 key） |
| `Entity.type`（缺省 `CONCEPT`） | `Individual.types` |
| `Entity.aliases / description / frequency / importance / workspace / id` | 个体属性（`aliases/description/frequency/importance/workspace/kbId`） |
| `Entity.properties` | 个体属性（扁平合并） |
| `Entity.sourceDocumentIds` → `sourceChunkIds` | `Individual.sources` |
| `Relation.type` | `Statement.predicate`（原样保留，未知谓词交给校验器报 `UNKNOWN_PROPERTY`） |
| `Relation.sourceEntityName → targetEntityName` | `Statement.subject → object`（任一端为空则该关系跳过） |
| `Relation.weight` | `Statement.weight` |
| `Relation.evidenceChunkIds` | `Statement.sources` |

`KbOntologySource`（id = `kb`）：`listAllEntities()` 后逐个 `getOutRelations()`，按关系 id 去重；
默认上限 5000，触顶打 WARN。`KbOntologyInitializer` 在应用启动末尾登记 Schema（同版本已存在则跳过）。
