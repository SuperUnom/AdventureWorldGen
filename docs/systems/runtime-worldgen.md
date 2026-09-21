# 运行时与 Minecraft worldgen

这里回答规划期、运行时和区块期如何分工。核心边界是：冻结的结构锚点可供查询，但不进入 Minecraft 结构生成。

## 三个时间边界

| 时间 | 可执行的工作 | 交接 |
|---|---|---|
| planning-time | 求解地形、环境、群系、结构宏观锚点和出生；校验；原子发布 | 完成的计划 future |
| runtime | 恢复并组装只读查询对象、索引和缓存 | `AdventurePlanView` |
| chunk-time | 生成列与表层，执行适用的 Minecraft 原生流程 | Minecraft 区块 |

区块请求不得触发群系或结构重新规划，也不得把 `PlannedStructurePlacement` 变成结构起点。

<a id="lifecycle"></a>
## 生命周期与屏障

[AdventureEvents](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/AdventureEvents.java)
在服务器即将启动时完成内容预检并启动主世界规划。`levelLoaded` 等待规划，出生事件使用计划脚底位置，
服务器停止时清理运行时注册表和进度。

[RuntimePlanRegistry](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/runtime/RuntimePlanRegistry.java)
以 profile ID 共享 `CompletableFuture<AdventurePlanView>`；重复 start 复用 future，await 等待同一结果。
资源 reload 不会替换已启动的 future。已有区块与配置变更的关系见
[READY 契约](../reference/plan-v2.md#compatibility)。

<a id="biomes"></a>
## 群系查询

[AdventureBiomeSource](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/worldgen/AdventureBiomeSource.java)
把 quart 坐标转换为方块坐标，等待计划，再把 `ContentId` 解析为活动注册表中的 biome holder。
查询顺序是海洋 → 陆地 patch/filler 与局部过渡 → 河湖覆盖；当前不做纵向群系分层。

河湖群系由 [VanillaWaterBiomes](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/compat/vanilla/VanillaWaterBiomes.java)
按明确的原版 ID 映射，不能推广为对第三方群系温度或 tag 的自动推断。

<a id="chunk"></a>
## 区块列、海洋与原生结构地基

[AdventureChunkGenerator](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/worldgen/AdventureChunkGenerator.java)
用冻结高度和水面合成基岩、石头、水与空气列，并让 base-height/base-column 使用同一计划基准。
含海洋区块会委托海洋噪声以保留封闭顶盖以下的原版空腔；可见海床仍来自共享计划。

生成器不覆盖 `createStructures`，所以 Minecraft 原生和数据包结构按其正常管线生成。
它不读取 `plannedStructures()`，不清除原生候选，也不注入结构起点。
`StructureTerrain` 只读取 Minecraft 已经建立的原生结构起点，为适用的表面结构组合 beard 类地基；
这种区块期地基适配不反馈到宏观规划，也不意味着计划锚点被执行。

carver 在原生结构地基或周围湿列处避让；其他干燥区域才委托原版 carver。
原版地物继续执行，`SpringFeatureMixin` 只过滤本生成器特定山地的露天泉口。

<a id="surface"></a>
## 表层材料

`buildPlannedSurface` 使用活动 overworld noise settings 的 surface rules，
通过 `PlannedSurfaceNoiseChunk` 提供已合成列的地表信息，执行后刷新 heightmaps。
表层、河床材料、植被和其他地物属于 Minecraft 管线；`BiomeAdapter` 不提供材料绘制入口。

<a id="structures"></a>
## 结构边界

`AdventurePlanView.plannedStructures()` 返回纯宏观元数据：实例 ID、结构 ID、锚点 X/Z。
运行时允许查询或展示这些信息，但 worldgen 不消费它们。计划也不保存 Y、旋转、footprint、入口或 pieces。

[StructureAdapterManager](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/worldgen/structure/StructureAdapterManager.java)
提供另一条显式调用路径。它接收独立的 `StructurePlacement`（资源 ID、最终 `BlockPos`、旋转），先让
`StructureResolver` 查询 Minecraft 模板管理器，再查询活动 `STRUCTURE` 注册表；因此类型优先级为
NBT 模板、`JigsawStructure`、其他 `Structure`、未知。检测不依赖资源名称。

`TemplateAdapter` 使用模板的 `placeInWorld` 在给定原点放置内容并应用旋转，随机源由世界种子、
位置与资源 ID 稳定派生。Jigsaw 和普通 Java Structure adapter 当前只验证取到的注册表对象类型，随后抛出
明确的未支持操作异常；代码没有猜测 1.21.1 的定点原生结构起点语义。
管理器没有被服务器生命周期或区块生成器自动调用，也不会把计划锚点转换为这个输入。

因此必须区分：

- “结构需求已规划”只表示数量、承载区域、锚点和间距通过 planner 校验；
- “Minecraft 中自然出现某个结构”只由 Minecraft 原生/数据包结构系统决定；
- “显式放置模板”表示某个外部调用方已经提供最终三维位置，与 planner 没有自动绑定；
- 两者可能使用相同内容 ID，但位置和数量没有绑定关系。

## 验证

纯 Java 测试覆盖计划查询、codec、READY、确定性和规划结构元数据。
`AdventureWorldGameTests`、`SurfaceGameTests`、`ChunkQueryGameTests`、`StructureExecutionGameTests` 与
`WorldgenFixGameTests` 覆盖实际列、表层、缓存、模板识别与旋转放置、原生结构地基及其他生成修正。
worldgen 变更必须运行完整 GameTest；
命令见 [测试指南](../development/testing.md#gametest)。
