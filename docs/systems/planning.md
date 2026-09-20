# 规划系统

这里回答作者需求如何转化为群系归属和结构实例，以及硬条件、偏好、恢复尝试与失败如何区分。

<a id="constraints"></a>
## 约束分层

| 层级 | 当前语义 |
|---|---|
| 硬条件 | 内容与规划信息可用、温湿度集合、地形分类及配方、硬高度、合法地貌、结构数量范围、锚点间距、承载区域、出生安全、面积上限 |
| 位置偏好 | adventure level、合法类型的权重、偏好高度、filler 权重、拥挤度和确定性扰动 |
| 目标与优化 | 最低面积优先争取，target 连续调节扩张压力；可选结构在已有合法方案上尝试增加 |
| 有界恢复 | 候选网格细化、多区域补种、尝试其他结构位置；不得扩大环境合法集合 |
| 失败 | 配置冲突、不支持内容、资源或操作预算耗尽、当前搜索域无合法落点、执行或终检错误 |

`area.max` 是每个需求的硬上限，`area.target` 不是达到后立即停止的上限。
群系分配中 `area.min` 可在合法供给不足时放宽；普通需求甚至可缺席并报告零面积。
出生与必需结构仍须有可用落点。更早的 `TerrainCapacitySolver` 仍要求最低地形容量，
因此最低面积放宽不保证任意输入都能通过首次规划。
这些失败应按阶段诊断，不能统一称为全空间无解。

<a id="demands"></a>
## 需求与归属模型

[RequirementExpander](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/planner/RequirementExpander.java)
把配置变成稳定的 `PatchDemand` 和 `StructureDemand`，不作空间选择。

- 每个 `biomes.required` 条目拥有独立需求身份；重复群系不合并，数组顺序参与身份。
- 出生群系若没有同 ID 的等级零显式需求，会补建隐含需求。
- 必需结构从 `count.min` 展开，每个实例创建独立 carrier 需求。
- 允许群系列表为空时，从 filler 池选择。
- `PlannedBiomePatch` 保存归属掩膜和锚点；`CellMask` / `AreaGrid` 定义最终世界对齐四分格。

一个需求可包含多个不相连的区域，不能仅凭包围盒计算面积。
有效面积只统计该 patch 内最终仍返回该群系的干地单元，河湖等水体不计入。
相邻同 ID filler 不抵扣独立需求。配置类型、缺省值和量化见 [profile](../reference/profile.md#area)。

<a id="cost"></a>
## 到达成本与 adventure level

[CostPlanner](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/cost/CostPlanner.java)
构建完整粗网格有向图；`CostDistanceMap` 使用最短路，
`EdgeCostCalculator` 对沿途高度、水深、危险与边界分段直接采样。
正反向费用分别计算，海洋、岩浆、危险或过陡路段阻断；不是仅比较端点净高差。
节点和估算工作内存在分配前检查。

`AdventureLevels` 用可达海岸等弧长样本的成本中位数归一化。
生产 `RuntimePlanner` 用 `normalizedPreferenceAt` 和 `AdventurePreference.penalty` 排序，
通过 `JointPlanner.preferenceOnly` 保持位置准入恒真。
不可达位置有较大的偏好惩罚，不因 adventure level 本身变成非法位置。
`CostRefinement` 的细网格查询用于精细成本诊断，并非当前生产等级硬门槛。

成本图参考点固定为中心 `(0.5, 0.5)`，建立在结构内实际出生点确定之前。
结构内出生不会触发重算。filler 的等级排序还使用径向代理；
因此不能声称所有位置评分均为“从最终玩家出生点出发的精确通行成本”。

<a id="capacity"></a>
## 地形容量

[TerrainCapacitySolver](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/planner/TerrainCapacitySolver.java)
先创建山脉和自然区域，估算远离岸带与区域边界的内部容量。
展开的需求按紧张程度稳定排序，先承诺最低容量，再使用余量争取 target 和环境损耗余量。
备选承载群系需有足够可用模板与高度包络容量，提交后输出 `TerrainCapacityPlan`。

容量是地形生成前的粗估承诺，尚未包含最终侵蚀、水文和气候合法域；
它不能替代最终群系分配，也不保证结构锚点一定有合法位置。
terrain 只消费结果；高度拟合与地形管线见 [地形系统](terrain.md#regions)。

<a id="allocation"></a>
## 群系分配

[JointPlanner](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/planner/JointPlanner.java)
建立 `PlacementIndex`，组合作者规则与适配器布尔准入，再创建气候场及环境规则。
[BiomeAllocationPlanner](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/planner/BiomeAllocationPlanner.java)
执行稳定选种、合法域竞争生长、多区域补种和目标面积扩张。

`ConnectedCapacityProbe` 复用同一归属快照下的连通供给检查；
归属变化后不能复用已失效的可行性结果。
`OrganicGrowth` 决定平滑的生长形状评分，四邻接 frontier 维护归属扩张。
跨河到达对岸的干地可以继续生长，但水体不计入干地配额。

最低轮后引入 filler 竞争，target 改变持续扩张压力，显式 max 才停止增长。
操作预算统计实际群系/单元合法性计算；预算不足报告搜索耗尽，不静默放宽准入。
`QuotaAssignment` 保留为可独立测试的分配算法，当前生产 `JointPlanner` 没有调用它；
不能把它的穷举验证性质移植为整个生产 planner 的保证。

<a id="structures"></a>
## 结构落位与可选实例

必需群系布局先完成，必需结构再在各自 carrier 内搜索安全、较平坦的宏观锚点。
实例按等级及实例 ID 稳定排序；候选从较粗网格逐步细化。
`StructurePlanningCatalog` 提供纯 `StructurePlanningInfo`；缺少信息时在结构搜索前失败。

当前实现是有界顺序规划，不是遍历所有数量向量与承载关系的全局联合回溯。
必需结构失败不会自动搬迁整套已分配群系。
可选实例在已有方案上尝试，单次失败不提交新 patch 或实例；达到 max 不是保证。
`scattered` 表示当前支持的落位模式，不承诺全大陆均匀覆盖。

同 ID 的间距是**锚点之间两两水平欧氏距离**；max 也约束每一对，不是连通图边长。
输出 `PlannedStructurePlacement` 只含实例 ID、结构 ID 和 X/Z 锚点。
它不含 Y、旋转、footprint、入口或 pieces，也不会被 worldgen 转为 Minecraft 结构。

<a id="filler"></a>
## 剩余填充、过渡与终检

[FillerLayout](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/planner/FillerLayout.java)
在较粗标签网格上继续已有 filler frontier，并为孤立未归属区域播种，冻结 `FillerState`。
查询时对真实地形与环境重新检查候选，不存在合法 filler 就明确失败。
它不保证池内每个 ID 都会出现，也没有公开 adapter 回调参数。

`LocalBiomeBlend` 只引入附近真实归属且当前位置合法的群系，出生保护区域限制混合。
`MinimumAreaPolicy` 决定哪些 patch 需要保护，并要求最终有效面积不低于
“请求最小值与已取得 patch 面积二者的较小值”；不足请求的合法结果通过日志报告。
`PlanDiagnostics` 保存操作计数，不是逐需求放宽事件表。

## 验证

主要测试为 `RequirementExpanderTest`、`CostPlannerTest`、`CostPlannerBudgetTest`、
`AdventurePreferenceTest`、`TerrainCapacitySolverTest`、`MultiRegionAreaTest`、
`StructureBiomePlanningTest`、`JointPlannerTest`、`MinimumAreaPolicyTest` 和 `DeterminismAcceptanceTest`。
production planning 和容量 GameTest 验证实际注册环境中的完整流程；
具体命令统一见 [测试指南](../development/testing.md)。
