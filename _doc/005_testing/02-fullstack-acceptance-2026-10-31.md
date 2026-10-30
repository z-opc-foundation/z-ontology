# 全方位验收报告（2026-10-31）

> 验收对象：`z-ontology` 1.0.0（M1 竖切）+ 改写/fork 设计所依赖的 `z-graph` 1.0.8 引擎行为。
> 结论：**四层共 157 项自动化检查全绿**（单测 46 / REST 66 / 改写探针 32 / 重启与端口 13），
> 另确认 **2 项会阻塞 M2 的持久化缺陷**、4 项契约/装配观察项。
> 本文只记录实测；设计与业界调研见 [08-rewrite-and-fork.md](../001_arch/08-rewrite-and-fork.md)。

## 1. 范围与环境

| 层 | 形式 | 检查数 | 结果 |
|----|------|--------|------|
| L1 构建与单测 | `mvn -B clean verify`（全 reactor） | 46 | ✅ 0 失败 |
| L2 REST 全接口 | 真进程 + 真 HTTP，Python 零依赖探针 | 66 | ✅ 0 失败 |
| L3 图改写 / fork | Java 探针直打 `z-graph` 1.0.8 | 32 | ✅ 0 失败 |
| L4 重启与端口 | REST 重启实验 + 端口级探针 | 13 + 4 步 | ✅ 0 失败 |

环境：macOS 26.6.2 / arm64 / Corretto 17.0.19 / Maven 3.9.9。
服务：`z-ontology-bootstrap` 打在 `127.0.0.1:18890`（8890 被本机 Docker `zopc-aio-ctc` 占用，未动它）。
数据目录：`/tmp/zont-rest/ontologies`（仅运行时暂存）；证据与探针源码全部落 NAS（见 §8）。

## 2. L1：构建与单测

`mvn -B clean verify` → **BUILD SUCCESS，13.334s**，7 个模块全 SUCCESS：

| 模块 | 用例 | 结果 |
|------|------|------|
| core | 28 | ✅ |
| graph | 6 | ✅ |
| kb | 7 | ✅ |
| web | 3 | ✅ |
| bootstrap | 2 | ✅ |
| api / starter | 0（模型与装配） | — |
| **合计** | **46** | ✅ |

用例清单与上轮修掉的 6 个缺陷见 [01-test-report.md](01-test-report.md)。

## 3. L2：REST 全接口（66）

探针遍历 OpenAPI 暴露的 14 条路径，含正向、边界与负向：

| 组 | 检查数 | 覆盖 |
|----|--------|------|
| G1 元信息 | 4 | health / info / OpenAPI 路径完整性 / 来源列表 |
| G2 Schema CRUD | 10 | 列表/单取/注册/覆盖/删/404、3 类非法 Schema、坏 JSON |
| G3 推理 | 4 | 父类继承、计数、未知本体 404、空快照 |
| G4 校验（kb） | 8 | 合法、未知类/属性/宾语、字面量错、来源校验、缺参 |
| G5 深度校验（自建 `hr`） | 11 | 互斥/函数性/值域/定义域/字面量五码同报；对称/传递/domain-range 推理 |
| G6 物化与图查询 | 24 | inline/来源物化、类/个体/实例/邻居/子图/stats、工作区隔离、reset 缩容、脏串、200KB、8 并发 |
| G7 错误面 | 5 | 404 / 405 / 415 / 未知类名 / 空白 workspace 回落 |

关键读数（详见 `rest-probe-rerun.log` 与逐请求的 `rest-probe.jsonl`）：

* `kb` Schema：**13 类**（`Entity` + 12 个 `EntityType`，含 `OTHER`）、**17 属性**（16 `RelationType` + `OTHER`）。
* 推理：`PERSON` → 推断 `Entity`；对称 `knows` 生成反向断言、传递 `locatedIn` 生成闭包断言、
  domain/range 反向补类型，均命中。
* 校验：`DISJOINT_VIOLATION` / `FUNCTIONAL_CONFLICT` / `RANGE_VIOLATION` / `DOMAIN_VIOLATION` /
  `INVALID_LITERAL` 五码在同一次请求内全部返回；**WARNING 不影响 `valid`**。
* 物化 alpha：13 类 + 3 个体 + 1 个未声明宾语占位节点，17 节点 / 21 边；非法 schema 注册返回 400 且
  `validation.issues` 带全部错误码。
* 工作区隔离：`alpha` / `beta` / `default` / `evil` / `big` / `conc` 互不串；`body.workspace` 优先生效；空白 workspace 回落 `default`。
* reset：同工作区 2 个体重物化为 1 个体后，节点 3→2、边 3→1，无残留。
* 并发：同一工作区 8 路 `materialize-inline` 全部 200，终态 13 类 + 2 个体、边 17，无重复/丢写。
* 脏数据：`alice'); MATCH (n) DETACH DELETE n; // "双引号" 😀 \n\tend` 作为 key/属性写入后原样查回，
  图未被破坏（13 类仍在）；200KB 属性值可物化。
* 确定性：整套 66 项连跑两次，逐条结果完全一致。

## 4. L3：图改写 / fork 探针（32）

两阶段执行器（MATCH 只读 + `GraphStore` API 确定性 upsert + 单事务提交）在 `z-graph` 1.0.8 上的实测：

| 组 | 检查数 | 读数 |
|----|--------|------|
| A 引擎与数据边界 | 11 | 环上 1..5 跳按路径枚举 = 15（需 upsert 去重 + 深度上限）；并行边收敛 1 条；重复源键收敛；特殊字符/10k 串原样；`null` 属性可写；upsert 覆盖保留旧属性；1000 节点 `*1..5` = 4985 行 |
| B 事务/并发/合并 | 16 | rollback 无半成品；中途异常源/目标都不脏；并发后提交者 `StaleHeadException` 且只留先提交者；同字段冲突 `merged=false` 且 head 不动；fast-forward `merged=true`；**不同字段自动合并**（`v=2` 与 `w=3` 都在） |
| C 注入 | 2 | 参数绑定下恶意串命中 0 行，图完整 |
| D 规模 | 3 | 1k 类：首次 195ms / 幂等重跑 0 写入 130ms；10k 类：14440ms、写入 59969、闭包边 49969、堆增长≈33MB |

规模结论：耗时随类数**超线性**（1k→10k 约 74×），主因是变长路径闭包按节点逐跳 BFS；
M2 的增量/按需闭包必须带上深度上限与去重（见 08 文档 §5.6）。
测试介质提醒：图数据目录放 SMB 挂载盘时，B 组每个小事务慢到 2–4.7s；换 `/tmp` 后恢复毫秒级，
性能读数一律以 `/tmp` 为准，NAS 只存日志。

## 5. L4：重启与持久化

### 5.1 REST 进程重启（真实 kill + 重启）

| 步骤 | 读数 |
|------|------|
| 重启前：注册 `persist` + 物化 workspace `p` | 1 类 + 2 个体，3 节点 / 3 边 |
| 重启后：`GET /schemas/persist` | **200**，版本/类数完整（JSON 文件持久化生效） |
| 重启后：`GET /stats?workspace=p` | **0/0**（物化图全部丢失） |
| 重启后重新物化 | 1 类 + 2 个体 / 3 边，**无旧残留、无重复** |
| 同工作区缩容物化（2→1 个体） | 节点 2 / 边 1，reset 正确 |

结论：**Schema 持久、物化图不持久**。默认装配是 `InMemoryGraphStore`，这是独立启动器的既定行为，
但对「持续洗数」的 pipeline 是硬阻塞。

### 5.2 端口级探针：持久 store + 新端口实例

假设宿主换用持久化 `GraphStore`，重启后新 `ZGraphOntologyPort` 的行为：

| 编号 | 检查 | 结果 |
|------|------|------|
| R1.1–R1.6 | Schema 跨实例读写/删除、损坏文件跳过、id 带 `../` 不逃逸（只落 base64 文件名） | ✅ |
| R2.1 | 端口 A：2 节点 / 1 边 | ✅ |
| R2.2 | 新端口看不到旧 scope（索引只在进程内） | ✅（缺陷确认） |
| R2.3 | 底层 store 里旧节点仍在（孤儿） | ✅（缺陷确认） |
| R2.4 | 新端口 `reset` 清不掉旧节点 | ✅（缺陷确认） |
| R2.5 | 同 key 重新 upsert 产生重复节点（无幂等） | ✅（缺陷确认） |
| R2.6 | 同一 key 在 store 中出现两条记录 | ✅（缺陷确认） |
| R2.7 | 同端口内 `reset` 只清本实例节点，旧孤儿仍在 | ✅（缺陷确认） |

根因：`ZGraphOntologyPort` 的 `Map<String, ScopeIndex> scopes` 是进程内 `ConcurrentHashMap`，
无任何重建/持久化路径（`ZGraphOntologyPort.java:42`）。这就是 08 文档 §5.7 标注的
「pipeline 前置依赖：scope 索引持久化」。

> `port-restart-probe.log` 里那段 `Rule 'json' failed to match` 堆栈是 R1.5 **故意**写入损坏 JSON 触发的
> 告警日志，随后 `list()` 正常跳过，不是失败。

## 6. 缺陷与观察

| 编号 | 级别 | 现象 | 影响 | 证据 |
|------|------|------|------|------|
| P1 | 阻塞 M2 | 物化图只在内存，重启归零 | pipeline 断点续洗、审计、fork 基线都无处落 | §5.1 |
| P2 | 阻塞 M2（换持久 store 时） | 端口 scope 索引不持久：孤儿、reset 失效、重复 upsert | 图越跑越脏，清洗结果不可重复 | §5.2 R2.2–R2.7 |
| O1 | 装配观察 | 独立启动器 `/sources` 恒 `[]`，`validate-source` / `materialize?source=` 不可用 | 只因 bootstrap 没有 z-kb 的 `KnowledgeGraphStore`；宿主带 z-kb 时可用 | G1.4 / G4.6 / G6.1 |
| O2 | 契约观察 | `DELETE /schemas/{id}` 对不存在 id 也回 204 | 幂等但无法区分「删了」与「本来没有」 | G2.10 |
| O3 | 契约观察 | `ValidationReport` JSON 字段名是 `valid`（Jackson 由 `isValid()` 推导） | 客户端按 `isValid` 读会拿到 null | G4.1 |
| O4 | 引擎已知缺陷 | z-graph 1.0.8：内联属性过滤被忽略、`MATCH..CREATE/MERGE` 静默不写、带内联属性的边模式报错 | 改写 DSL 必须避开，按两阶段执行器实现 | [08 文档 §6](../001_arch/08-rewrite-and-fork.md) |

处置建议（待裁定，未改动代码）：

1. **P1 + P2 一起收**：给 `ZGraphOntologyPort` 换成可持久化的 scope 索引（或让索引可从 store 重建：
   按 `ontologyId`/`workspace` 属性扫描 + 边 key 重建），并补重启后 `reset`/幂等的回归用例。
2. **O1**：要么在 bootstrap 加一个可选的 z-kb 存储 profile，要么在端口文档里明确「独立模式无来源」。
3. **O3**：若要对外发布 SDK，建议加 `@JsonProperty("isValid")` 或改 getter 名，避免契约歧义。
4. **O2**：维持 204 幂等可接受，但应在 API 文档显式写明。

## 7. M2 放行判断

* 引擎能力边界已量化：**可用**（两阶段执行器已覆盖样例与规模），**不可用**（原生 Cypher 一步改写）。
* 事务语义已具备：rollback、并发 `StaleHeadException`、字段级自动合并、fast-forward、冲突不动 head。
* 放行条件只剩持久化（P1/P2）；在这两项收口前，pipeline 模式只能做到「进程内正确」。

## 8. 证据索引

全部在 NAS：`/Volumes/personal_folder/temp/zont-full-test-2026-10-31/`

| 文件 | 内容 |
|------|------|
| `mvn-verify.log` | L1 全 reactor 构建与 46 例单测 |
| `rest_probe.py` / `rest-probe.log` / `rest-probe-rerun.log` / `rest-probe.jsonl` | L2 探针源码、两次运行、逐请求响应 |
| `OntoRewriteProbe3.java` / `probe3.clean.log` | L3 改写/fork 探针与 32 项读数（原始日志含 NUL，用 `tr -d '\000'` 清洗） |
| `OntologyPortRestartProbe.java` / `port-restart-probe.log` | L4 端口级 13 项 |
| `service.log` / `service-after-restart.log` | 服务启动与重启日志 |
| `cp-graph.txt` | L3 探针的 z-graph 1.0.8 classpath 快照 |

## 9. 复现

```bash
# L1
cd z-ontology && mvn -B clean verify

# L2：先起服务（8890 被占用时换端口）
java -jar z-ontology-bootstrap/target/z-ontology-bootstrap-1.0.0.jar \
  --server.port=18890 --zont.storage-path=/tmp/zont-rest/ontologies
python3 rest_probe.py

# L3 / L4：按证据目录里的 classpath 快照编译运行
javac -encoding UTF-8 -cp "$(cat cp-graph.txt)" -d out OntoRewriteProbe3.java
java  -cp "out:$(cat cp-graph.txt)" OntoRewriteProbe3
```
