# 图改写：A 本体 → B 本体（DSL / fork / pipeline）

> 状态：**设计稿（未实现）**，2026-10-31。
> 依据：`z-graph 1.0.8` 真实引擎实测（探针与读数见 §4）+ 公开资料调研（§2）。
> 本文只回答「怎么做」，不改现有 M1 语义。

## 1. 结论先行

1. **现状缺口**：M1 已经能把「源数据 + Schema」物化成一张图（`OntologySource → 推理 → 校验 → 物化`），
   但**没有「读 A 图 → 写 B 图」的能力**。这正是「本体清洗」缺的那一环。
2. **引擎够用，但必须两阶段**：z-graph 1.0.8 不支持 `MATCH ... CREATE` / `MATCH ... MERGE`
   （静默不写），也不支持边上的 MERGE。改写执行器必须走
   **MATCH（只读）→ 驱动侧按 key upsert（GraphStore API）→ 同一写事务提交**。
   实测 1000 类 / 999 条子类边全量清洗 **211ms**，二次幂等重跑 **0 写入 / 297ms**。
3. **fork 与 pipeline 是同一套底座的两个用法**：z-graph 的 `beginWrite / commit / createBranch / merge`
   （git-for-data 模型）直接给出「一次性 fork」与「持续 pipeline」；
   fork = 分支一次跑完不 merge；pipeline = 同一规则反复跑 + 幂等 upsert + 回写。
4. **DSL 自研、编译成计划、不落原生 Cypher**：语义借 SPARQL `CONSTRUCT` 与 SHACL-AF `sh:construct`，
   版本与隔离借 `branch/commit`，血缘借 PROV-O。**用户不写原生 Cypher**，杜绝注入与误写。
5. **需要给 z-graph 报三个缺陷**（只报不改，见 §6）。

## 2. 业界怎么做的（调研结论）

| 主题 | 代表做法 | 对我们的取舍 |
|---|---|---|
| 通用 Schema 分层 | 上层本体（BFO / schema.org）+ 领域模块 + `imports`；词表用 SKOS（`broader/narrower`、`altLabel`） | 采用：`upper / common / domain` 三层 + `imports`；别名归 z-cognition 的 Term/Concept，本仓只存引用 |
| 源 → 目标映射语言 | W3C **R2RML**，扩展版 **RML**（CSV/JSON/流）；规则 = 模板（Subject / Predicate / Object） | 借「规则即模板」的心智模型；不引入 RDF 栈 |
| 查询时映射（不落盘） | **OBDA / Ontop**：R2RML 映射 + SPARQL 查询重写为 SQL | 不做。我们的图已经是物化的；但保留「映射与执行分离」 |
| 图 → 图变换 | **SPARQL `CONSTRUCT`**（查询即构造）；**SHACL-AF `sh:construct`**（规则反复迭代到不动点） | 采用 CONSTRUCT 语义：`MATCH ... WHERE ... CONSTRUCT`，**默认单轮，显式声明才迭代** |
| 约束与推理的世界观 | OWL = 开放世界推理，SHACL = 封闭世界校验；工程上「**先推理、再校验**」 | 与 M1 现状一致（`RdfsReasoner` → `OntologyValidator`），改写规则同样先匹配后校验目标形状 |
| 增量维护 | **IVM / DBSP / Materialize**：`(data, time, diff)` 三元组，递归需二维时间 | M2 先「全量重算 + 幂等 upsert」（实测 211ms/千类）；M3 再谈差分 |
| 数据分支 | Dolt / TerminusDB / lakeFS 的 branch-commit-merge | 直接复用 z-graph 的 MVCC 分支，不自研 |
| 血缘 | W3C **PROV-O**（Entity / Activity / Agent） | 生成物带 `ruleId / runId / srcOntology / srcKey`，等价最小 PROV |

## 3. 现状盘点

| 能力 | 现状 | 位置 |
|---|---|---|
| Schema 自检 / 注册 / 落盘 | ✅ | `OntologySchemaValidator`、`JsonFileOntologyStore` |
| ABox + RDFS 子集推理 + 数据校验 | ✅ | `RdfsReasoner`、`OntologyValidator` |
| 物化到图（幂等 key、scope 隔离） | ✅ | `OntologyMaterializer`、`ZGraphOntologyPort` |
| z-kb 抽取映射（含证据 `sourceChunkIds`） | ✅ | `KbSchemaFactory`、`KbOntologyMapper`、`KbOntologySource` |
| **A → B 图改写** | ❌ | — |
| **fork / pipeline** | ❌ | — |
| **改写 DSL** | ❌ | — |
| 图查询面（按标签/属性检索） | ⚠️ 端口只有 `findNode / listNodes / neighbors / subgraph`，且 scope 索引在**进程内存** | `OntologyGraphPort`、`ZGraphOntologyPort` |
| Schema `imports` / 多 Schema 合成 | ❌ | `OntologySchema` 无 imports |
| 引擎级 `MATCH..CREATE` / 边 MERGE | ❌（见 §6） | `CypherEngine` |

## 4. 实测：z-graph 1.0.8 的真实边界

探针源码与运行日志（NAS，只读证据）：
`/Volumes/personal_folder/temp/zont-rewrite-probe-2026-10-31/`（`OntoRewriteProbe2.java` + `probe-run.log`）。

### 4.1 能力矩阵（实测，不是 README 声称）

| 写法 | 结果 | 证据 |
|---|---|---|
| `CREATE (a:Class {..}), (b:Class {..}), (a)-[:T]->(b)` | ✅ 节点+边同时建 | edges=1 |
| `MATCH ... RETURN` / `WHERE` / 变长路径 `*1..5` | ✅ | Employee 祖先 3 条；1000 类闭包 4969 条边 |
| `MATCH ... SET` / `DELETE` / `DETACH DELETE` | ✅（带 `WHERE` 才安全） | 见 4.2 |
| 独立 `MERGE (n:Label {...})` | ✅ 单节点 upsert | 二次运行零新增 |
| `MATCH ... CREATE` | ❌ **静默不写**，不抛错 | 建边数 0 |
| `MATCH ... MERGE` | ❌ **有行无写**，不抛错 | 返回 2 行、建节点 0 |
| 边 MERGE `(a)-[r:T]->(b)` | ❌ 不支持 | — |
| 单节点 `MATCH (n:Class {key:'k2'})` | ❌ **内联属性过滤被忽略**，返回同标签全部节点 | 3 行（应为 1） |
| `MATCH (a:Person {key:'p1'})-[:T]->(b)` | ❌ **抛** `Invalid edge pattern` | 边模式里带内联属性的节点不被解析 |
| 关系内联属性 `[r:KNOWS {w:2}]` | ❌ **过滤被忽略**，返回同类型全部边 | 2 条（应为 1） |
| 变长路径内联属性 | ✅ | 3 行（正确） |
| `WHERE` 过滤（节点 / 关系） | ✅ | 均命中 1 行 |
| `MATCH ... SET`（带 `WHERE`） | ✅ | `p1.city=HZ` |
| `MATCH ... SET`（内联属性） | ⚠️ **改全部同标签节点** | 只想改 p2，结果 p1、p2 都改 |
| 参数 `$k` | ✅（执行前展开为字面量） | `WHERE c.key = $k` 命中 1 行 |

### 4.2 两阶段执行器实测（A→B）

源图：5 类 + 2 个体 + 4 条子类边 + 1 条对象属性边。规则：类复制、闭包展平、关系重命名。

| 用例 | 读数 |
|---|---|
| 首次清洗 | 写 nodes=7 / edges=14；BClass=5、BIndividual=2、闭包边=8、重命名边=1、来源边=5 |
| 幂等重跑 | **0 写入**（nodes=0 edges=0） |
| 源新增 1 类后重跑 | **只增 1 节点 + 3 边**（契约内差量） |
| 全量重算 | `DETACH DELETE` 清目标标签后重建成功 |
| 规模（1000 类 / 999 边） | 首次 211ms（1000 节点 + 5969 边，其中闭包 4969）；二次 297ms / 0 写入 |

fork 实测（`GraphVersionStore`）：

| 动作 | 读数 |
|---|---|
| main 提交种子 | nodes=7 edges=7 |
| `createBranch("wash", headCommit)` | branches=2 |
| wash 分支跑规则并提交 | nodes=19 edges=21（7 A + 12 B） |
| main 分支读 | BClass=0（**未污染**） |
| main 继续演进 | nodes=8；wash 仍 19（**一次性 fork 语义成立**） |
| `merge("main", "wash")` | merged=true conflicts=[]；main BClass=5 |

## 5. 设计

### 5.1 整体形状

```
 A 本体图（source scope）                          B 本体图（target scope / 分支）
        │                                                    ▲
        │ ① MATCH（只读：标签/属性/变长路径）                  │ ③ upsert 节点与边（按 key 幂等）
        ▼                                                    │
   RewriteExecutor ──② 求值表达式 + 生成确定性 key ───────────┘
        │
        └─ ④ Run Ledger：runId / 源基底 commit / 目标 commit / 计数 / 耗时
```

- 改写是**同引擎内的 scope→scope 搬运**，不去外部系统取数；取数仍归 `OntologySource`。
- 规则集（ruleset）声明 `from ontology` 与 `into ontology`，执行器**只写 into**；
  写源 scope 需显式 `allowWriteSource true`，默认拒绝。
- 执行在**一个写事务**里完成：`beginWrite(targetBranch)` → 逐条 upsert → `commit`。
  中途失败 `rollback`，不产生半成品。

### 5.2 抽取与挂载（z-kb → A 本体）

**现状已通**：`KbOntologySource`（`listAllEntities` + `getOutRelations`，默认上限 5000）→
`KbOntologyMapper`（canonicalName → key，type → 类型，sourceChunkIds → sources）→
`OntologyMaterializer` → 图。也就是说「从智库抽取并挂载到本体」在 M1 已经是一条可跑的链路，
缺的是**规模化与稳定性**：

| 缺口 | 影响 | 建议 |
|---|---|---|
| 抽取类型是固定枚举（12 EntityType / 17 RelationType，各含 `OTHER`） | 新领域概念只能落 `OTHER`，语义坍缩 | 允许 Schema 侧声明「动态子类映射」：`Entity.type=OTHER` 时用 `properties.subType` 生成影子类 |
| 上限 5000、无水位 | 全量拉取不可持续 | 按 `workspace + 抓取时间` 记录水位，做增量拉取 |
| 无抽取置信度 / 无跨文档别名合并 | 同名不同实体或同实体多写法 | 置信度进个体属性；别名合并留给 z-cognition 的 Term/Concept（那是它的职责） |
| 证据只到 chunkId | 无法回查原文变更 | `sources` 已有；后续接快照 hash（等 z-kb 的 chunk 稳定性验证过） |

**挂载约定**：A 本体每轮物化都是「重建（rebuild）」而不是「追加」，因为 `OntologyMaterializer`
先 `reset` 再写。所以 **A 的稳定性 = Schema 版本 + 源水位**；下游改写必须记录它消费的是哪个
Schema 版本与哪一轮物化，否则无法复现。

### 5.3 Schema 分层与多 Schema 并存

```jsonc
{
  "id": "hr",
  "version": "0.1.0",
  "imports": ["upper@1.0.0", "common@2.1.0"],
  "classes": [ ... ],
  "properties": [ ... ]
}
```

| 层 | 例子 | 规则 |
|---|---|---|
| upper | `Thing / Agent / Event / Place` | 全组织唯一，极少改；只放最抽象的公理 |
| common | `Person / Organization / Document / Metric` | 跨域共用；`Person` 只能在 common 定义一次 |
| domain | `Employee / Department / Contract` | 域内自洽，`superClasses` 指回 common/upper |

- **同名不同义**用 `prefix` 区分（`hr:Person` ≠ `kb:Person`），**不合并同类**；
  真要合并走显式 `equivalentClass`（M3）。
- 一个 workspace 可同时挂多套本体（现状 `ontologyId + workspace` 已隔离），
  改写规则一次读一个 source scope、写一个 target scope。

### 5.4 改写 DSL

**设计原则**：语义像 `CONSTRUCT`，语法尽量小；**不暴露原生 Cypher**；每条规则自带证据与幂等键。

```
# employee-view.zrw
rewrite employee_view
  version 1.0.0
  from    ontology "kb" version "1.0.0"
  into    ontology "hr" version "0.1.0"
  mode    incremental          # incremental | rebuild

rule org_as_department
  match   (e:Employee)-[:worksFor]->(o:Organization)
  where   e.status = "active" and o.name is not null
  create  (d:Department { key: concat("dept:", o.key), name: o.name })
  create  (e)-[:MEMBER_OF { since: e.joinDate }]->(d)
```

规则块（IR，JSON 同构；文本 DSL 编译到它）：

| 字段 | 含义 | 约束 |
|---|---|---|
| `match` | 一个节点模式或一条边模式（可含变长 `*min..max`） | **只允许一个模式**；多模式拆分多条规则 |
| `where` | 布尔表达式 | 只允许：属性访问、`= != > >= < <=`、`and/or/not`、`is [not] null`、`in`、`contains` |
| `create`（0..n） | 节点模板 / 边模板 | 节点必须有 `key`；边必须由模式变量构成 |
| `set`（0..n） | 对目标节点/边的属性赋值 | 只能写 `create` 出来的目标变量 |
| `iterate` | 迭代到不动点（默认 false） | 对应 SHACL-AF 语义，需显式开启并给 `maxRounds` |
| `evidence` | 证据来源字段（默认 `sources`） | 透传到目标节点/边 |

**表达式白名单**（不实现通用脚本）：

`concat(a,b,...)`、`lower/upper/trim`、`coalesce(a,b,...)`、`substring`、`split`（限 2 段）、
四则运算、属性访问 `x.p`。类型只有 string / number / boolean / list。

**幂等键**（写入目标图时的唯一性）：

| 对象 | key 规则 | 例子 |
|---|---|---|
| 规则生成的节点 | `rw:<rulesetId>:<ruleId>:<模板 key 求值>` | `rw:employee_view:org_as_department:dept:acme` |
| 规则生成的边 | `rw:<rulesetId>:<ruleId>:<fromKey>-><type>-><toKey>` | 同上 |
| 来源边 | `WASHED_FROM`（`srcOntology / srcKey / ruleId / runId`） | 可追溯 |

**安全边界**：

1. 写入目标必须在 `into ontology` 声明的 scope 内，键前缀强制 `rw:`；
2. 不拼原生 Cypher：匹配走端口/查询 API，写入走 `upsertNode/upsertEdge`；
3. 字面量不拼字符串：键与属性直接以 Map 传给端口（规避 z-graph 参数展开的转义风险）；
4. `where` 表达式走自己的求值器，不交给引擎；
5. 每轮运行产出 `RunReport`，计数为 0 也算一次运行（可观测）。

### 5.5 两种玩法：fork 与 pipeline

| | **fork（洗出去就不管）** | **pipeline（持续洗）** |
|---|---|---|
| 语义 | 从源基底 commit 拉一条分支，跑一次，冻结 | 每次源变更重跑同一 ruleset，幂等合并 |
| 实现 | `createBranch(target, sourceHead)` + `beginWrite(target)` + 一次执行 + `commit` | 同上，但**不新建分支**；固定写 `target` 分支，逐轮 upsert |
| 源演进 | 分支不变（实测：main 7→8 节点，wash 仍 19） | 源每次提交后触发；未变则 0 写入（实测） |
| 下游读 | 按 branch / commit 读快照（`checkout(commitId)`） | 读 `target` 分支 HEAD |
| 回滚 | 丢弃分支即可 | 回退 `target` ref 到上一 commit（`merge`/ref 移动） |
| 消费方冲突 | 无（只读快照） | 多 pipeline 写同一 target 需串行化（按 branch 加锁） |

**回写主图**（可选）：`merge("main", "wash")`。实测 `merged=true conflicts=[]`；
字段级冲突时 `GraphMergeResult.hasConflicts()` 非空且不移动 target head——**冲突即失败，不自动覆盖**。

### 5.6 增量与性能

| 阶段 | 策略 | 实测/预期 |
|---|---|---|
| M2 | 全量重算 + 幂等 upsert（差值由 upsert 天然收敛） | 1000 类 211ms；二次 0 写入 297ms |
| M3 | 源变更驱动：只对「变更节点 + 其邻域」重跑规则 | 待做 |
| M4 | 引擎内 IVM（`(key, ruleId, ±1)` 差分）或把计划下推为单条 Cypher | 依赖 z-graph 支持 `MATCH..MERGE` |

**膨胀风险**：1000 类的子类闭包产生 4969 条边（≈5×节点）。目标 Schema 若含深层 is-a，
必须在规则里限制深度（`*1..2`）或只在目标保留直接父类；否则小图也会爆。

### 5.7 模块与端口

```
z-ontology-api       + RewriteRule / RewriteRuleset / RunReport（纯模型）
                     + RewriteGraphPort（匹配 + upsert，目标侧）
                     + GraphVersionPort（fork / merge / checkout 抽象）
z-ontology-core      + RewriteDslParser（文本 → IR）
                     + ExpressionEvaluator（白名单）
                     + RewriteExecutor（两阶段；不依赖 z-graph）
z-ontology-graph     + ZGraphRewritePort / ZGraphVersionPort 适配
z-ontology-web       + /api/ontology/rewrites/**（规则集 CRUD、试跑、fork、pipeline 运行、run 历史）
z-ontology-bootstrap + 装配
```

- **核心不依赖引擎**：`RewriteExecutor` 只依赖 api 里的两个端口，单测用 `InMemoryGraphStore` 兜底实现。
- **端口必须补匹配面**（现状 `OntologyGraphPort` 只有 `findNode/listNodes/neighbors/subgraph`）：
  `matchNodes(labels, props)`、`matchEdges(type, fromKey)`；
  变长路径在**执行器**里用 BFS 组合 `matchEdges` 完成（可移植，不绑定 Cypher）。
- **持久化前置**：`ZGraphOntologyPort` 的 scope 索引在进程内存，重启后丢失。
  改写要落库前，必须先做「索引重建或改成 store 直查」，否则 pipeline 重启即失忆。

### 5.8 对外 API（草案）

| 端点 | 用途 |
|---|---|
| `GET/POST/PUT /api/ontology/rewrites` | 规则集登记与版本 |
| `POST /api/ontology/rewrites/{id}/dry-run` | 只匹配不写，返回将生成/命中的计数与样例 |
| `POST /api/ontology/rewrites/{id}/run?mode=incremental\|rebuild` | 执行（pipeline） |
| `POST /api/ontology/rewrites/{id}/fork` | 一次性 fork（返回 branch / commit） |
| `GET /api/ontology/rewrites/{id}/runs` | Run Ledger |
| `POST /api/ontology/rewrites/{id}/promote` | 分支 merge 回主图 |

## 6. 对 z-graph 的诉求（只报不改）

实测已确认三个**静默失效**，任何一个都会让上层写出「看起来成功、实际没写」的数据：

| # | 缺陷 | 复现 | 影响 |
|---|---|---|---|
| G1 | 单节点 / 关系模式的内联属性过滤被忽略；边模式里的带属性节点直接抛异常 | 3 个 Class，`MATCH (n:Class {key:'k2'})` 返回 3 行；`MATCH (p:Person {key:'p2'}) SET p.city='GZ'` 把 p1 也改了 | 任何「按属性定位」都会误伤/误删，且 SET 会静默批量改 |
| G2 | `MATCH ... CREATE` 被忽略且不报错 | 建边数 0 | 图改写无法单语句完成 |
| G3 | `MATCH ... MERGE` 有行无写 | 返回 2 行、建节点 0 | 增量清洗会静默丢数据 |

建议（按优先级）：**G1 修属性过滤**（数据安全问题）→ **G3 支持 `MATCH..MERGE` 单节点** →
**G2 支持 `MATCH..CREATE`** → 边 MERGE / 多模式 JOIN。修完后改写计划可下推为单条 Cypher，
M4 的增量才有意义。

## 7. 落地切片（M2，建议）

| 切片 | 内容 | 验收读数 |
|---|---|---|
| S1 | `RewriteRule/Ruleset/RunReport` 模型 + `RewriteGraphPort` + 内存实现 | 单测：模型校验、端口契约 |
| S2 | `RewriteDslParser` + `ExpressionEvaluator` | 解析错误定位到「行:列 + 期望」；表达式负向用例 |
| S3 | `RewriteExecutor`（两阶段、幂等键、来源边、RunReport） | 复跑零写入；新增源只增差量；计数可读 |
| S4 | `GraphVersionPort` + z-graph 适配（fork / merge / run ledger） | main 不污染；merge 无冲突；冲突时 head 不动 |
| S5 | REST + 装配 + 一份真实规则集（`kb → hr`） | `dry-run` 命中数 = 实际写入数；线上 curl 读数 |

前置依赖：**scope 索引持久化**（§5.7）必须先做，否则 S4 的 pipeline 重启即失效。

## 8. 未决问题

1. 迭代到不动点（`iterate`）是否 M2 就要？默认关，规则集显式开启。
2. 目标 Schema 与规则集的一致性校验：是「规则只能写目标 Schema 已声明的类/属性」强制，还是告警？
   （倾向强制，和「缺证据写入 400」同一个口径。）
3. 删除语义：源删了，目标要不要跟着删？M2 只支持 `rebuild` 模式表达删除；incremental 只增改不删。
4. 多 pipeline 写同一 target 的串行化：用分支锁还是单写队列？
5. 是否需要给规则集做「影响面」：改一条规则前列出会被重写的目标 key 集合。

## 9. 参考

- SPARQL 1.1 Query Language（`CONSTRUCT`）：https://www.w3.org/TR/sparql11-query/
- SHACL Advanced Features（`sh:construct` 规则）：https://www.w3.org/TR/shacl-af/
- R2RML: RDB to RDF Mapping Language：https://www.w3.org/TR/r2rml/
- RML（R2RML 的多格式扩展）：https://rml.io/specs/rml/
- PROV-O：https://www.w3.org/TR/prov-o/
- DBSP / 增量视图维护：https://www.vldb.org/pvldb/vol16/p1604-budiu.pdf
- Dolt / TerminusDB 的 branch-commit-merge 数据版本模型（git-for-data）
