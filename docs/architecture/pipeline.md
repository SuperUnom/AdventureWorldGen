# 世界生命周期

这里回答 profile 如何变成冻结计划、已有计划如何恢复，以及何时允许区块生成。
生产编排入口是 [RuntimePlanner](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/runtime/RuntimePlanner.java)。

<a id="reload"></a>
## Profile reload

`ProfileReloadListener` 读取固定 profile 资源栈，调用 `AdventureWorldConfigParser` 完成严格解析和字段间校验，
再由 `CanonicalConfigJson` 写出规范化 JSON，发布不可变 `LoadedProfile`。
资源选择、重复覆盖及配置规则见 [profile 参考](../reference/profile.md#loading)。

注册内容的验证发生在 `AdventureEvents.serverAboutToStart`：
活动群系与结构注册表提供查询，`MinecraftAdapters` 冻结群系适配器集合，`ContentPreflight` 检查所需内容。
结构执行目录在此时读取并冻结，验证模板和地形能力；执行资源身份保护已有区块兼容性，见 [执行契约](../systems/runtime-worldgen.md#structures)。

服务器在 `ServerLevel` 创建前启动后台规划，因为世界生成构造过程可能提前查询群系。
加载事件、出生事件及 worldgen 查询通过 `RuntimePlanRegistry` 等待同一个 future。
profile 资源重载只更新加载结果，不能使已有 future 自动失效；会话边界见 [运行时](../systems/runtime-worldgen.md#lifecycle)。

<a id="initial"></a>
## Initial planning

先用 `PlanIdentity` 计算输入身份并尝试读取 READY。未命中才进入以下首次规划流程。
进度枚举定义在 [PlanningStage](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/plan/PlanningStage.java)，
它是 UI 阶段，不是每个内部求解步骤的独立成功状态。

| 阶段 | 输入与动作 | 交付给下一步的结果 |
|---|---|---|
| 海岸与容量 | 种子、作者需求、出生预留；`CoastGenerator` 后接 `TerrainCapacitySolver` | 海岸折线、岸带、容量承诺与山脉走廊 |
| 地形基础 | `PlanTerrain.foundation` 组装区域与岛屿高度 | 连续宏观采样器 |
| 侵蚀 | `ErosionGenerator` 在世界对齐网格模拟，再包装为侵蚀地形 | 冻结增量场和侵蚀后高度 |
| 水文 | `HydrologyGenerator` 在侵蚀后地形构造河网；组合水文和地貌 | 河湖几何、最终规划高度与水面 |
| 成本 | `CostPlanner` 从中心参考点构图 | 全局成本与冒险偏好信号 |
| 群系与结构 | `JointPlanner` 展开需求、建立索引与气候、竞争分配群系，再放必需和可选结构锚点 | 地块、宏观结构锚点、实际出生点 |
| 计划组装 | `GeneratedAdventurePlan.fromPlanning` 复用地形和气候；`PlanAssembly` 构造剩余填充与过渡 | 可查询计划及冻结布局 |
| 原生实例预检 | worldgen 通过自然查询验证候选并导出纯范围，丢弃临时 pieces | 经验证的锚点与实例范围 |
| 道路 | `RoadPlanner` 消费纯实例信息，构造并验证完整施工列 | 冻结路网 |
| 终检 | 有效面积、锚点承载、数量与间距检查 | 可发布快照 |
| 保存 | 编码、原子发布，随后输出性能诊断 | READY 和查询对象 |

成本图参考点固定在中心；具体影响见 [成本系统](../systems/planning.md#cost)。
温度需求统计不反馈调整温度场；湿度存在供给修正，见 [环境系统](../systems/terrain.md#climate)。
任务耗时只用于日志与性能记录，不能作为规划搜索终止输入。

结构数据的单向关系是：

```text
作者配置 -> StructureDemand -----------\
                                           -> JointPlanner -> PlannedStructurePlacement -> PlanSnapshot
planning catalog -> StructurePlanningInfo /
```

READY 恢复后，worldgen 组装结构锚点到起点区块的只读索引，并消费地形、群系和出生查询。

<a id="publish"></a>
## Freeze 与 publish

`GeneratedAdventurePlan` 是可执行查询对象；`PlanAssembly` 负责环境规则、填充和出生数据组装。
`PlanSnapshot` 是存储层接收的冻结数据，包含几何、环境、归属及结构锚点元数据。
`PlanV2Codec` 只编码和解码快照；`AtomicPlanRepository` 只负责磁盘包和完整性。
发布完成后，规划 future 返回计划，等待者才能继续。

冻结对象关系、输入身份、READY 目录及损坏处理的唯一契约见 [计划 v5](../reference/plan-v2.md)。

<a id="ready"></a>
## READY reload

仓库命中有效磁盘包后，codec 校验内部格式和身份，`GeneratedAdventurePlan.restore` 组装查询对象。
恢复使用保存的海岸、侵蚀增量、河网、容量、气候高度网格、湿度状态、填充标签、结构锚点和出生点。
不会重跑海岸求解、侵蚀模拟、水文规划、容量求解、成本图、群系竞争或结构落位。

恢复仍会创建函数、索引与局部缓存，按冻结输入重新推导区域配方清单并和保存清单比较。
因此 READY 不等于逐方块高度全量存储，也不等于零计算。
恢复后的结构条目仍只是宏观元数据，不检查或加载 Minecraft structure pieces。

<a id="chunk"></a>
## Chunk generation

`AdventureBiomeSource` 等待计划后将四分格查询映射为计划群系。
`AdventureChunkGenerator` 消费计划高度和水面，写入方块列并执行原版表层和适用的地物流程。
生成器在 STRUCTURE_STARTS 执行规划锚点；引用、方块放置和 pieces 存档复用 Minecraft 管线。
材料、海洋洞穴、结构地基和 carver 的具体边界见 [worldgen 执行](../systems/runtime-worldgen.md#chunk)。

服务器停止清理运行时计划注册表和进度；磁盘上的 READY 保留用于下次启动。
