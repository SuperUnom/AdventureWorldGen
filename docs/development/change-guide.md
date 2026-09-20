# 修改指南

这里按开发任务定位应读文档、主要实现和验证入口。
源码路径链接用于进入职责边界，不是逐文件清单；完整包职责见 [AGENTS](../../AGENTS.md#包职责)。

## 按任务定位

| 我要修改 | 先读 | 主要代码 | 验证重点 |
|---|---|---|---|
| 海岸轮廓 | [地形：海岸](../systems/terrain.md#coast) | [CoastGenerator](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/terrain/CoastGenerator.java) | CoastGeneratorTest、CoastlineSearchTest、CoastDetailPreview |
| 配方与区域接缝 | [地形：区域](../systems/terrain.md#regions) | [TerrainRecipes](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/terrain/TerrainRecipes.java)、[RegionTerrain](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/terrain/RegionTerrain.java) | TerrainRecipesTest、RegionTerrainTest、缓存/负坐标与配方预览 |
| 山脉包络 | [地形：山脉](../systems/terrain.md#mountains) | [MountainRangePlan](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/terrain/MountainRangePlan.java)、[TerrainCapacitySolver](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/planner/TerrainCapacitySolver.java) | RegionTerrainTest、TerrainCapacitySolverTest、地形回归与预览 |
| 河网、河床与湖湿地 | [地形：水文](../systems/terrain.md#hydrology) | [hydrology](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/hydrology/) | HydrologyGeneratorTest、RiverMorphologyTest、HydrologyAudit、漏水 GameTest |
| 侵蚀及平滑 | [地形：侵蚀](../systems/terrain.md#erosion) | [erosion](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/erosion/) | ErosionGeneratorTest、TerrainRegressionTest、确定性与 READY |
| 海床与洞穴空腔 | [地形：海洋](../systems/terrain.md#ocean)、[区块列](../systems/runtime-worldgen.md#chunk) | [OceanBathymetry](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/terrain/OceanBathymetry.java)、[海洋资源](../../neoforge/src/main/resources/data/adventureworldgen/worldgen/) | OceanBathymetryTest、OceanShelfPreview、实际列 GameTest |
| 温度与湿度 | [气候交互及限制](../systems/terrain.md#climate) | [climate](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/climate/) | 气候 JUnit、不同首次 sample/顺序、规划与气候 GameTest |
| 群系环境准入与过渡 | [规划：filler](../systems/planning.md#filler)、[环境规则](../reference/profile.md#rules) | [biome](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/biome/) | BiomeEnvironmentRulesTest、LocalBiomeBlendTest、边界与有效面积 |
| 需求展开与群系分配 | [规划系统](../systems/planning.md) | [planner](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/planner/) | RequirementExpanderTest、MultiRegionAreaTest、MinimumAreaPolicyTest、planning 组 |
| 地形容量 | [规划：容量](../systems/planning.md#capacity) | [TerrainCapacitySolver](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/planner/TerrainCapacitySolver.java) | TerrainCapacitySolverTest、capacity 与 planning 组 |
| 到达成本与冒险偏好 | [规划：成本](../systems/planning.md#cost) | [cost](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/cost/) | CostPlannerTest、EdgeCostCalculatorTest、AdventurePreferenceTest、预算测试 |
| 结构数量、承载与落位 | [规划：结构](../systems/planning.md#structures) | [JointPlanner](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/planner/JointPlanner.java)、[StructureAdapterBridge](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/runtime/StructureAdapterBridge.java) | 结构规划 JUnit、planning/default 组、cold/ready |
| 配置字段与缺省 | [profile](../reference/profile.md) | [config](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/config/)、[发布默认值](../../neoforge/src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json) | parser 与 canonical 断言、冲突输入、planning 组 |
| 计划格式与身份 | [plan-v2](../reference/plan-v2.md) | [plan](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/plan/)、[persistence](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/persistence/)、[PlanIdentity](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/runtime/PlanIdentity.java) | codec、原子发布、身份、确定性与 READY |
| 首次规划或会话加载 | [生命周期](../architecture/pipeline.md) | [runtime](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/runtime/)、[AdventureEvents](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/AdventureEvents.java) | planning 组、异常屏障、普通世界 cold/ready |
| Minecraft 区块与材料 | [runtime/worldgen](../systems/runtime-worldgen.md) | [worldgen](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/worldgen/)、[mixin](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/mixin/) | JUnit + 完整 GameTest，按需客户端观察 |
| 第三方 adapter | [适配器契约](../reference/adapters.md) | [api](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/api/)、[compat](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/compat/)、[testcompanion](../../neoforge/src/testmod/java/io/github/luoyan/adventureworldgen/testcompanion/) | 注册/冻结/重放与跨区块完整路径 |
| 进度界面 | [生命周期](../architecture/pipeline.md) | [client](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/client/)、[PlanningProgress](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/runtime/PlanningProgress.java) | PlanningProgressTest、客户端；进度不得改变生成 |
| 文档入口与契约 | [文档目录](../README.md) | 当前对应主题 + 真实来源 | check-docs.py、示例与命令核对 |

运行方式统一见 [测试指南](testing.md#selection)，不在此表复制命令和测试数量。

## 修改前后要回答的问题

1. 这个事实由配置、计划还是区块执行层拥有？是否需要接口输入，而非新增跨层依赖？
2. 改的是合法域、偏好、预算还是最终几何？不能用软评分掩盖硬条件变化。
3. 是否改变同 seed 的输出、规范化结果或冻结状态？核对 [版本与身份](../reference/plan-v2.md#compatibility)。
4. 首次构建、READY、缓存冷/热和负坐标是否仍一致？现有测试是否真正覆盖了不同访问顺序？
5. 对实际 Minecraft 行为的结论来自源码、纯 Java 测试还是已运行的游戏证据？
6. 对应主题是否更新，其他文档是否只保留摘要和链接？

新增模块时以 [PackageBoundaryTest](../../neoforge/src/test/java/io/github/luoyan/adventureworldgen/PackageBoundaryTest.java)
作为可执行护栏，不把测试未禁止的方向视为默认合理。
算法来源变动还需核对 [FTF 归属](../reference/ftf-provenance.md) 与 NOTICE。

## 保持文档可维护

字段定义写入 profile，冻结兼容性写入 plan-v2，模块行为写入对应 systems，验证命令写入 testing。
源文件和类型用链接或明确代码标识，避免行号、当前测试总量和完整默认 JSON。
生成产物路径要注明为运行后产生，命令参数占位符要解释输入格式。

发生故障从 [debugging](debugging.md) 进入。
设计出处用 Git 查询；未实施的产品需求不写成开发 invariant。
