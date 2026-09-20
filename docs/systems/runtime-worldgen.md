# 运行时与 Minecraft worldgen

这里回答规划期、运行时和区块期如何分工，以及冻结计划怎样进入真实方块、群系、结构与出生流程。

## 三个时间边界

| 时间 | 可执行的工作 | 交接 |
|---|---|---|
| planning-time | 求解环境、归属、结构和出生；校验；原子发布 | 完成的计划 future |
| runtime | 恢复与组装只读查询对象，创建索引和缓存 | `AdventurePlanView` |
| chunk-time | 消费计划，生成列和表层，恢复结构起点，执行适用的原版流程 | Minecraft 区块 |

首次规划与 READY 的构建关系见 [生命周期](../architecture/pipeline.md)。
区块请求不得成为群系或结构重新规划的入口。

<a id="lifecycle"></a>
## 生命周期与屏障

[AdventureEvents](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/AdventureEvents.java)
在服务器即将启动时，对使用自定义生成器的主世界完成内容预检并启动规划。
`levelLoaded` 等待规划，出生事件使用计划脚底位置，服务器启动事件设置默认出生点。
`PlanningLoadingOverlay` 消费进度，不能参与随机选择或预算判断。

[RuntimePlanRegistry](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/runtime/RuntimePlanRegistry.java)
以 `ContentId` 为键共享 `CompletableFuture<AdventurePlanView>`：
重复 start 复用 future，await 等待完成，尚未启动则报错。
服务器停止清理注册表与进度。

当前资源加载器固定加载 default，生命周期只主动接入主世界。
注册表键不包含世界或维度；不能仅配置任意 profile ID 就期待多个世界并行规划。
资源 reload 不清理已启动 future，也不替换已发布的会话计划。
已有区块与配置变更的关系见 [READY 契约](../reference/plan-v2.md#compatibility)。

<a id="biomes"></a>
## 群系查询

[AdventureBiomeSource](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/worldgen/AdventureBiomeSource.java)
将 Minecraft quart 坐标转为方块坐标，等待计划，再把 `ContentId` 解析成活动注册表中的 biome holder。

`GeneratedAdventurePlan.biomeAt` 统一取未扰动的四分格中心，当前忽略纵向分层。
查询顺序是海洋 → 陆地原始归属与局部过渡 → 河湖覆盖。
陆地查询先找 patch，再查 filler；水体覆盖不会修改原始陆地掩膜。
`surfaceBiomeAt` 使用同一个最终群系查询，不建立独立材料群系。

河流与湖泊的水体群系由
[VanillaWaterBiomes](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/compat/vanilla/VanillaWaterBiomes.java)
按陆地 ID 映射普通或冰冻河流。
它使用明确的原版 ID 集合，不按温度场、tag 或第三方群系名称推断结冰；湿地不套用河湖覆盖。

<a id="chunk"></a>
## 区块列、海洋与地基

[AdventureChunkGenerator](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/worldgen/AdventureChunkGenerator.java)
序列化 profile 标识，注册表 lookup 由 codec 环境提供。
`fillFromNoise` 根据冻结高度和水面合成基岩、石头、水与空气列。
地面整数化使用 `solidSurfaceAt`，`getBaseHeight` 和 `getBaseColumn` 消费同一计划基准。

含海洋的区块先调用海洋噪声委托，再保留计划海床封闭顶盖以下的原版空腔。
可见海床始终来自共享计划；相关资源为
[海洋 noise settings](../../neoforge/src/main/resources/data/adventureworldgen/worldgen/noise_settings/ocean.json)
与 [海洋 density functions](../../neoforge/src/main/resources/data/adventureworldgen/worldgen/density_function/ocean/overworld/)。

`StructureTerrain` 按已知结构起点的地形适配和 footprint 组合地基。
它处理表面结构的 beard 类适配，不把地下 bury/encapsulate 当成台阶。
地基只调整实际列；base-height 查询保持与结构无关以供结构起点组装。
这是宏观 plan 高度与含地基实际列之间的显式差别，不反馈到规划成本。

carver 遇到结构地基或周围湿列时跳过；干燥且无地基区域才委托原版 carver。
海洋洞穴来自密度生成；不能宣称所有列均完整执行原版洞穴流程。
原版地物继续执行；`SpringFeatureMixin` 通过 `MountainSpringFilter` 对本生成器的特定山地露天泉口过滤。

<a id="surface"></a>
## 表层材料

`buildPlannedSurface` 使用活动 overworld noise settings 的 surface rules，
用 `PlannedSurfaceNoiseChunk` 提供已合成列的地表信息，避免每次材料判断重新生成整列。
噪声使用世界种子与项目海面基准，执行后刷新 heightmaps。

表层、河床材料和数据包表层扩展属于原版 surface 管线；
`BiomeAdapter` 不提供材料 palette 或额外地表绘制入口。
植物等地物由实际群系配置与原版装饰阶段产生，不从宏观规划器直接写块。

<a id="structures"></a>
## 结构恢复、抑制与注入

先由原版创建普通结构候选，再清除受控 ID 的原生起点，包括配置数量上界为零的 ID。
非受控结构仍走普通生成流程。

计划查询返回与区块相交的结构。只有实例原点所在区块注入其 `StructureStart`，
`FrozenPieceRestore` 按 NBT 的部件类型 ID 在 `BuiltInRegistries.STRUCTURE_PIECE` 中恢复全部 pieces。
跨区块覆盖依靠 Minecraft 的结构引用与分块物化，不增加规划实例或数量名额。
适配器不维护另一份逐区块提交记录。

发布和 READY 恢复前会检查部件类型注册；实际 loader 错误仍可能发生在区块期，
此时抛出带实例/部件信息的异常，不静默丢弃部件。
公开扩展边界与恢复上下文限制见 [适配器](../reference/adapters.md)。

## 验证

`PlanningBaselineTest`、`PlanTerrainTest`、`BiomeBoundaryTest`、
`DeterminismAcceptanceTest` 和 codec 测试覆盖完整纯 Java 规划及查询。
`AdventureWorldGameTests` 覆盖冻结部件恢复、起点注入、第三方适配、READY 与海洋列。
`SurfaceGameTests`、`ChunkQueryGameTests`、`WorldgenFixGameTests` 检查材料、缓存、地基、泉口和实际列。
修改这层需要 JUnit 与对应 GameTest；普通世界 cold/ready 对照命令见 [测试指南](../development/testing.md#ready)。
