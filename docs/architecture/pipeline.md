# 世界生命周期

这里回答 profile 如何变成冻结计划、已有计划如何恢复，以及何时允许区块生成。
生产编排入口是 [RuntimePlanner](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/runtime/RuntimePlanner.java)。

<a id="reload"></a>
## Profile reload

`ProfileReloadListener` 读取固定 profile 资源栈，调用 `AdventureWorldConfigParser` 完成严格解析和字段间校验，
再由 `CanonicalConfigJson` 写出规范化 JSON，发布不可变 `LoadedProfile`。
资源选择、重复覆盖及配置规则见 [profile 参考](../reference/profile.md#loading)。

注册内容的验证发生在 `AdventureEvents.serverAboutToStart`：
活动群系与结构注册表提供查询，`MinecraftAdapters` 冻结适配器集合，`ContentPreflight` 检查所需内容与能力。
预检成功只表示检查通过，不产生另一份“已解析内容”状态。

服务器在 `ServerLevel` 创建前启动后台规划，因为结构环等构造过程可能提前查询群系。
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
| 群系与结构 | `JointPlanner` 展开需求、建立索引与气候、竞争分配群系，再放必需和可选结构 | 地块、冻结部件、实际出生点 |
| 计划组装 | `GeneratedAdventurePlan.fromPlanning` 复用地形和气候；`PlanAssembly` 构造剩余填充与过渡 | 可查询计划及冻结布局 |
| 终检 | 有效面积检查、结构部件类型能力检查 | 可发布快照 |
| 保存 | 编码、原子发布，随后输出性能诊断 | READY 和查询对象 |

成本图先于结构内出生点求解，参考点固定在中心；具体影响见 [成本系统](../systems/planning.md#cost)。
温度需求统计不反馈调整温度场；湿度存在供给修正，见 [环境系统](../systems/terrain.md#climate)。
任务耗时只用于日志与性能记录，不能作为规划搜索终止输入。

<a id="publish"></a>
## Freeze 与 publish

`GeneratedAdventurePlan` 是可执行查询对象；`PlanAssembly` 负责环境规则、填充和出生数据组装。
`PlanSnapshot` 是存储层接收的冻结数据，包含几何、环境、归属及部件数据。
`PlanV2Codec` 只编码和解码快照；`AtomicPlanRepository` 只负责磁盘包和完整性。
发布完成后，规划 future 返回计划，等待者才能继续。

冻结对象关系、输入身份、READY 目录及损坏处理的唯一契约见 [plan-v2](../reference/plan-v2.md)。

<a id="ready"></a>
## READY reload

仓库命中有效磁盘包后，codec 校验内部格式和身份，`GeneratedAdventurePlan.restore` 组装查询对象。
恢复使用保存的海岸、侵蚀增量、河网、容量、气候高度网格、湿度状态、填充标签、保护集合、结构和出生点。
不会重跑海岸求解、侵蚀模拟、水文规划、容量求解、成本图、群系竞争或结构落位。

恢复仍会创建函数、索引与局部缓存，按冻结输入重新推导区域配方清单并和保存清单比较。
因此 READY 不等于逐方块高度全量存储，也不等于零计算。
恢复后再次检查部件类型是否可用；不支持的结构不能随计划静默进入游戏。

<a id="chunk"></a>
## Chunk generation

`AdventureBiomeSource` 等待计划后将四分格查询映射为计划群系。
`AdventureChunkGenerator` 消费计划高度、水面和结构部件，写入方块列并执行原版表层和适用的地物流程。
受控结构在原点区块注入起点，跨区块部分依赖原版结构引用。
材料、海洋洞穴、结构地基和 carver 的具体边界见 [worldgen 执行](../systems/runtime-worldgen.md#chunk)。

服务器停止清理运行时计划注册表和进度；磁盘上的 READY 保留用于下次启动。
