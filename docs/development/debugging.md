# 故障定位

这里回答失败应该在哪一层定位、收集哪些输入和使用哪个审计入口。
先保存 seed、有效 profile、资源包来源、adapter 版本、失败阶段和完整异常链，再判断是否需要重跑。

## 证据在哪里

- 服务器游戏目录生成的 `logs/latest.log`：reload、预检、规划阶段、异常与 Minecraft 执行。
- `PlanningProgress`：当前阶段、已完成工作和前台进度；进度不是计划内容。
- `PlanDiagnostics`：冻结的确定性操作计数，不是完整事件流水或耗时记录。
- 世界目录生成的 `adventureworldgen/planning-metrics.json`：阶段耗时、检查点堆占用、容量/供给与偏好统计。
- [测试与工具](testing.md)：JUnit 报告、分组 GameTest 日志、数值审计及地图。

[PlanningMetrics](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/runtime/PlanningMetrics.java)
的 observed_heap_bytes 是检查点观测的 JVM 堆值，不是 OS 峰值内存。
指标写入失败会记录警告；不能因没有指标文件就断定 READY 未发布。

## 配置和内容

| 症状 | 定位与处理 |
|---|---|
| CONFIG_ERROR，带 JSON path | 检查拼写、未知键、重复键、类型、null、有限数值和必填字段；按 [profile](../reference/profile.md) 对照 parser |
| CONFIG_CONFLICT | 查跨字段关系、有效配方/filler 覆盖、面积范围、出生引用或资源栈；不要只改报错位置而忽略关联字段 |
| UNSUPPORTED_CONTENT | 检查活动注册表、adapter、结构冻结能力和 NBT 类型；JSON 文件存在不等于注册成功 |
| 注册失败 | 检查早期 AdapterRegistrations 与重复 ID；不能在集合冻结后热注册 |
| 改 profile 未生效 | 确认覆盖路径及实际资源栈；会话 future 不会被普通 reload 替换，见 [生命周期](../systems/runtime-worldgen.md#lifecycle) |

内容预检还检查海洋、普通/冰冻河流等运行时水体群系。
第三方降雪、材料或植被变化应检查游戏层资源，不能只检查 BiomeAdapter。

## 规划失败与资源限制

[PlanningFailure](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/plan/PlanningFailure.java)
携带 code、stage 与诊断字段；
[FailureStage](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/plan/FailureStage.java)
集中定义阶段标签。保留原始阶段，不要把所有失败重命名为“无法生成世界”。

| 症状 | 优先检查 |
|---|---|
| RESOURCE_LIMIT | 网格节点估算、工作内存、配方/河网或 profile 预算；区分规划预算与 JVM OutOfMemoryError |
| 搜索耗尽 | 群系候选与实际合法性评估次数、细化预算；有界搜索失败不证明全空间无解 |
| 无合法承载/落点 | 地形容量、温湿度硬集合、连通干地、完整结构 footprint、保护范围和间距 |
| minimum area 警告 | 对照 [约束分层](../systems/planning.md#constraints)，区分上游容量承诺、分配结果和终检有效面积 |
| filler 查询失败 | parser 的模板覆盖不等于每个温湿度点都有候选；检查该坐标的最终 MacroSample 与环境规则 |
| EXECUTION_FAILED | 沿异常链找真实阶段：plan-load、生成组装、存储或终检；不能默认都是作者 JSON 问题 |

先使用最小可复现 profile 和相同 seed 定位硬条件，再改变搜索预算。
避免同时调大半径、结构数量、堆内存与操作上限，使原因无法辨认。
预算变化可能改变输出，应按 [身份兼容性](../reference/plan-v2.md#compatibility) 处理。

## READY、摘要和恢复

[READY 契约](../reference/plan-v2.md#reload) 区分两层：

- 缺 READY、manifest 不匹配、输入摘要不同或包损坏：repository 返回未命中，走首次规划。
- 包通过但 codec/快照/piece 支持失败：恢复可能直接中止，需要修复语义或明确兼容性策略。

不要单凭日志出现 CACHE 阶段就认定成功复用。
记录实际输入摘要、manifest、payload 校验和以及是否继续进入规划阶段。
`PlanReloadBenchmark` 可以隔离解码与查询重建，但它读取 manifest 中的 hash，不能诊断生产输入身份是否正确。

相同输入 hash 却试图发布不同规范化内容说明确定性或版本身份有问题；
不要删除 READY 来掩盖。
保存故障包再在新实验目录复现，已有世界和区块不能作为可随意清空的缓存。
原子移动或落盘失败按文件系统与权限定位，发布事务的实际替换边界见 [存储](../reference/plan-v2.md#repository)。

## 结构恢复与注入

从实例 ID、piece 索引、NBT id、已注册 StructurePieceType 查起。
注册能力检查不运行实际 loader；模板缺失或 null resourceManager 的使用可能到区块期才暴露。
具体限制见 [适配器](../reference/adapters.md#limits)。

起点只在原点区块注入；相交区块依靠 Minecraft 引用完成分块物化。
排查“计划里有、世界里没有”时同时看冻结范围、原点区块、引用、恢复异常和实际 FULL 区块。
受控 ID 的普通原生起点会被抑制，不能把原生候选数等同于计划实例数。
用普通世界 cold/ready 审计验证 NBT、方块与容器。

## 地形与群系视觉排查

先导出冻结 plan 的地形/群系图，再对争议坐标比较 MacroSample、最终 biome、实际列和表层。
规划海床、结构地基、原版海洋空腔和 surface rules 属于不同数据来源；
见 [区块执行](../systems/runtime-worldgen.md#chunk)。

用 CoastDetailPreview / OceanShelfPreview / RiverPreview 隔离几何，
用 PlanTerrainAudit / PlanPatchTopologyAudit 检查保存结果，
用 PlannerMapPreview / BoundaryPreview 观察归属和过渡。
参数与输入格式统一见 [工具表](testing.md#tools)。

气候异常需比较两个全新对象的不同首次查询顺序，以及相同坐标不同 sample，
而不只是重复读取同一热缓存。已知限制见 [查询边界](../systems/terrain.md#query-limits)。
预览图不能证明实际漏水、方块材料或完整 Minecraft 洞穴流程正确；
这些需要 GameTest 或真实世界观察。
