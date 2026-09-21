# 运行时与 Minecraft worldgen

这里回答规划期、运行时和区块期如何分工。结构执行以冻结锚点为输入，结构方块的分区块放置和存档由 Minecraft 管线负责。

## 三个时间边界

| 时间 | 可执行的工作 | 交接 |
|---|---|---|
| planning-time | 求解地形、环境、群系、结构宏观锚点和出生；校验；原子发布 | 完成的计划 future |
| runtime | 恢复并组装只读查询对象、索引和缓存 | `AdventurePlanView` |
| chunk-time | 生成列与表层，执行适用的 Minecraft 原生流程 | Minecraft 区块 |

区块请求不得触发群系或结构重新规划；只有 worldgen 桥接层将冻结锚点转成执行输入。

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

`createStructures` 先过滤已接管 ID 的随机候选，再通过 `PlannedStructureBridge` 在归属区块构造起点。
STRUCTURE_REFERENCES 复用原生流程；FEATURES 由原生 `applyBiomeDecoration` 调用起点及 pieces，按当前区块裁剪放置。
一次构造完整 pieces 是确定结构布局，不是一次放置全部方块。

`StructureTerrain` 在 NOISE 合成当前区块的地基。显式 fill/flatten 消费随起点保存的二维支撑区域；
计划结构的 native 模式使用原生 Beardifier 核函数及 Jigsaw junction，叠加本生成器的高度场密度。
这保留 BURY、ENCAPSULATE 等适配类别，不等于复现原版整套噪声密度地形。
未接管原生结构继续使用原有的 beard 地表地基逻辑。
地形适配不反馈到宏观规划；基础高度和基础列查询不包含结构，以免结构定高递归依赖自身地基。

carver 在原生结构地基或周围湿列处避让；其他干燥区域才委托原版 carver。
原版地物继续执行，`SpringFeatureMixin` 只过滤本生成器特定山地的露天泉口。

<a id="surface"></a>
## 表层材料

`buildPlannedSurface` 使用活动 overworld noise settings 的 surface rules，
通过 `PlannedSurfaceNoiseChunk` 提供已合成列的地表信息，执行后刷新 heightmaps。
表层、河床材料、植被和其他地物属于 Minecraft 管线；`BiomeAdapter` 不提供材料绘制入口。

<a id="structures"></a>
## 结构执行契约

`AdventurePlanView.plannedStructures()` 仍只有实例 ID、结构 ID、锚点 X/Z。
`PlannedStructureBridge` 按 `floorDiv(anchor,16)` 建立起点区块索引；实例 ID 唯一，
同 ID 的两个实例不能共享起点区块。执行器只接收 Minecraft 上下文和坐标，不读取计划或作者配置。

| 执行方式 | 定位与组装 | 地形支持 |
|---|---|---|
| Template | 明确模板定义；按锚点、高度基准及旋转构造一个可跨区块的模板 piece | 模板声明的局部支撑区域变换到世界坐标 |
| Jigsaw | 使用注册定义的起始池、连接点、深度、距离、高度、别名、投影和液体参数；直接在锚点组装 | fill/flatten 按 rigid 部件地面基准；terrain-matching 部件保留原生投影；native 使用原生核与 junction |
| Java | 调用原生 Structure.generate，保留原点、Y、pieces、afterPlace 与原生加载逻辑；不通用平移 | 默认 native；显式 fill/flatten 要求实现 TerrainSupportProvider 并提供已确定支撑区域 |

READY 前的原生实例验证、自然查询视图与有界候选修正见 [道路实例契约](roads.md#原生实例预检与重放)。
区块期接管结构沿用自然查询视图，避免道路覆盖改变拼装高度或 pieces。

分类按生成能力进行，不按具体结构 ID 分支。内部使用模板的 Java 结构仍走 Java 路径，保留其特殊回调。
生成定义与数据包字段见 [结构生成定义](../reference/adapters.md#execution)。

### 区块与失败边界

- 作者 profile 中列出的结构 ID 全部由本生成器接管，包括规划数量为零的 ID；其他 ID 保留原生候选。
- 起点建立在 STRUCTURE_STARTS，邻区块引用它；边缘区块先请求时也能建立必要起点。
- 结构本体和地基过渡带的引用包围盒必须处于起点周围的原生 8 区块扫描范围内；超出范围或世界高度明确失败。
- Template/Jigsaw 仍检查最终生成点群系，Java 保留原生生成条件。规划只有 ID 元信息，不能提前证明任意 Java 生成条件都成立。
- 缺资源、同 ID 起点冲突、无有效生成点或超限会失败并报告实例、结构和锚点；区块期不会跳过实例、随机补抽或移动冻结锚点。
- 普通加载不放置结构。已完成 FEATURES 的区块保留玩家改动，未完成区块继续使用保存的起点。
- 世界关闭结构生成选项时，尊重 Minecraft 的开关，不执行规划结构。

`findNearestMapStructure` 对已接管 ID 查询有限计划，不再搜索已经关闭的随机候选。
普通 locate 对比计划锚点和未接管原生结果的水平距离；计划查询扫描有限锚点集合，不使用原生随机分布的搜索环数，
因此 `radius` 只传给未接管结构的原生查询，并非计划锚点的区块距离上限。
计划候选先按目标结构 ID 过滤，再按锚点水平距离排序，等距时按实例 ID 排序；
从近到远请求起点区块达到 STRUCTURE_STARTS，找到首个有效且符合引用条件的起点后停止。
探索地图的 skipKnown 查询优先选择尚未引用的计划实例，没有可用实例才查询未接管结构；只增加返回起点的引用次数。

### 保存与资源身份

Minecraft 起点 NBT 保存 Children；`StructureStartMixin` 另外保存 `adventureworldgen_execution`，
包含执行数据版本、实例 ID、地形策略和已经确定的支撑区域。恢复失败明确报错，不能把损坏的计划起点当作不存在。
该信息随原生区块保存，全局计划不复制 pieces，也不保存“已加载区块集合”。

有接管结构或启用道路的世界通过 `StructureExecutionIdentity` 校验执行版本、计划输入身份、模组版本、活动结构/池/处理器定义、
模板内容（含 generated 模板覆盖）及地形策略。为覆盖间接池引用，首版保守纳入全部活动结构资源，
所以不相关的结构资源变更也可能使执行身份失效。
摘要保存于世界目录下的 `adventureworldgen/structure-generation.sha256`。
身份不匹配时拒绝继续，避免一半结构使用旧资源、一半使用新资源；已有区块但没有执行身份的旧世界也拒绝直接接管。
READY 失效不删除这个保护文件，不代表已有区块迁移。首版新增接管结构应使用新世界。

## 道路执行

冻结道路以区块索引接入地形列和表层，基础高度/基础列也消费相同施工几何；桥下保留水体。
生成期道路保护同时覆盖邻区块地物写入，普通加载不重铺。完整边界见 [道路系统](roads.md)。

## 验证

structure GameTest 使用真实 ServerLevel、区块生成状态和磁盘区块保存，覆盖三种执行器、不同访问顺序、
边缘先请求、模板生成一半后重载、玩家修改、原生 Java 回调、NBT 往返、候选抑制与 locate。
另外检查填充、平整、禁用和 native 埋入/包裹策略、资源身份、缺模板与起点冲突。
这不等于证明所有第三方 Java 结构、处理器、实体或超大结构兼容。
既有 performance 组检查地形列、表层、原生地基和 carver/spring 回归；命令见 [测试指南](../development/testing.md#gametest)。
