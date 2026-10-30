# 路线图

## M1（本次交付，已完成）

| 项 | 状态 |
|----|------|
| 本体模型（类 / 对象属性 / 数据属性 / 公理） | ✅ `z-ontology-api` |
| RDFS 子集推理（子类闭包、子属性、domain/range、逆/对称、传递） | ✅ `RdfsReasoner` |
| 一致性校验（11 个数据校验码 + 26 个 Schema 校验码） | ✅ `OntologyValidator` / `OntologySchemaValidator` |
| 物化到图（类节点 / 个体节点 / 子类边 / 实例化边 / 属性边 / 数据属性） | ✅ `OntologyMaterializer` |
| z-graph 适配 + 进程内兜底 | ✅ `ZGraphOntologyPort` / `SimpleOntologyGraphPort` |
| z-kb 适配（默认 Schema + 来源 + 映射） | ✅ `z-ontology-kb` |
| REST + starter + 独立 bootstrap | ✅ `z-ontology-web` / starter / bootstrap |
| 真实测试（46 例，全 reactor 绿） | ✅ 见 [`../005_testing/01-test-report.md`](../005_testing/01-test-report.md) |
| 发 Maven Central | ⏸ 未发；`-Pcentral` 已备好，属人工保留项 |

## 已知限制

| 限制 | 影响 | 规避 |
|------|------|------|
| 物化图默认在进程内存 | 重启丢图（Schema 仍在磁盘） | 宿主自行提供持久 `OntologyGraphPort`；或重启后重新物化 |
| 推理只在物化时全量跑 | 无增量推理；大图每次全量重算 | M1 数据量级（万级实体）可接受；后续可做增量 |
| 只覆盖 RDFS 子集 | 无 sameAs / 等价类 / 基数约束 / 否定 | 复杂约束在应用层校验 |
| functional 只报冲突，不做去重 | 冲突数据仍会被物化 | 调用方按 `validation.valid` 决定是否采用 |
| z-kb 来源默认上限 5000 实体 | 超限只告警不截断（会继续拉完当前批次） | 调大 `zont.kb.limit`，或分 workspace 物化 |
| Schema 无版本迁移 | 同 id 重复注册直接覆盖，旧图需重新物化 | 变更 Schema 后重新 `materialize` |
| 无鉴权 / 多租户配额 | 面向内网 | 前置网关（kapi）统一鉴权 |
| 未发布到 Central | 外部消费需自行构建 | 仓内 `mvn install`，或等发版 |

## 后续演进

1. **持久图端口**：接 z-graph 的持久存储，物化结果重启不丢（目前 z-graph 适配层已就绪，缺的是选型与验收）。
2. **增量物化**：按 workspace 记录上次 Schema 指纹与数据水位，只重算变化的个体。
3. **约束增强**：类不相交的传递闭包、属性 cardinality、必填数据属性（SHACL 子集）。
4. **多本体**：`imports` / `owl:equivalentClass` 级别的本体引用与合并冲突检测。
5. **查询面**：在 `/neighbors`、`/subgraph` 之上加路径查询 / 模式匹配。
6. **前端**：本体编辑与图可视化（沿用 z-frontend-common 的域前端规范，落 `_frontend/`）。
7. **发布**：`1.0.0` 走 `-Pcentral` 发到 Maven Central，并在 lead 的 `003_辅助能力/bootstrap/manifest.json` 登记版本。
8. **图改写（A 本体 → B 本体）**：规则 DSL + 两阶段执行器 + fork/pipeline 两种玩法，方案见
   [`08-rewrite-and-fork.md`](08-rewrite-and-fork.md)（含 z-graph 1.0.8 实测读数与三个引擎缺陷）。
