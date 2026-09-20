# 内容适配器

这里定义第三方群系与规划器之间的公开扩展边界。结构不再拥有 adapter、冻结部件或 Minecraft 执行扩展点。
第三方应只依赖 [api](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/api/) 中的公开契约，
不应调用内部 planner 来建立第二套布局或状态。

## 注册边界

[AdapterRegistrations](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/api/AdapterRegistrations.java)
接受外部 `BiomeAdapter`，应在模组构造等早期阶段完成注册。
[AdapterRegistry](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/api/AdapterRegistry.java)
按 ID 保存显式群系 adapter，并提供 generic biome fallback；重复 ID 会报告注册冲突。

[MinecraftAdapters](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/worldgen/MinecraftAdapters.java)
合并内置和外部群系 adapter 后冻结注册表；首次冻结后拒绝新注册。
adapter version 进入 [计划身份](plan-v2.md#identity)，改变合法域或输出时必须审查版本。

数据包负责提供实际 biome、structure 注册内容与 profile 覆盖。
结构 ID 只要存在于活动 Minecraft 注册表即可通过内容预检；它不要求 AdventureWorldGen 结构 adapter。

## 群系契约

[BiomeAdapter](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/api/BiomeAdapter.java)
提供 `biomeId`、`adapterVersion` 和 `compatibility(MacroSample)`。
返回的 `Compatibility` 包含硬准入、[0,1] 软偏好和诊断原因。

当前规划桥接使用硬准入约束显式需求；并未把 soft preference 统一接入所有评分。
`FillerLayout` 也没有公开 adapter 回调。外部群系若需要严格环境限制，应同时配置作者环境规则，
并验证 required、filler、局部过渡和运行时查询。

Biome adapter 不负责表层 palette、植被或材料；这些仍由 Minecraft surface rules 与群系地物执行。

## 结构规划信息

结构侧只有三个纯规划模型，均不提供 Minecraft 物化能力：

| 模型 | 来源与方向 | 内容 |
|---|---|---|
| `StructurePlanningInfo` | 已验证结构目录 → planner | 结构 ID 等规划器需要的固有信息 |
| `StructureDemand` | 作者配置 → planner | 数量、承载群系、面积、等级和间距 |
| `PlannedStructurePlacement` | planner → 冻结计划 | 实例 ID、结构 ID、宏观锚点 X/Z |

当前 `StructurePlanningInfo` 只有结构 ID。以后新增尺寸、地形偏好或安全半径时，必须仍保持纯数据，
不能引入 Minecraft 结构起点、piece、NBT、旋转、模板管理器或区块回调。
`PlannedStructurePlacement` 是叙事与后续系统可查询的宏观位置，不是“待执行结构”。

## 明确不提供的能力

- 不注册或调用结构 adapter。
- 不冻结或恢复 structure pieces / NBT。
- 不抑制任何配置 ID 的原生结构候选。
- 不从计划向区块注入 Minecraft 结构起点。
- 不保证计划锚点与 Minecraft 自行生成的同 ID 结构重合。

原版及数据包结构由 Minecraft 的普通结构管线处理。AdventureWorldGen 的结构规划信息不会改变该管线。

## 验证

`StructurePlanningCatalogTest`、`RequirementExpanderTest`、`StructureBiomePlanningTest`、
`JointPlannerTest` 与 codec/READY 测试覆盖纯规划边界。
`PackageBoundaryTest` 阻止执行模型重新进入 planner，并检查生成器没有计划结构注入入口。
游戏侧仍需用完整 GameTest 验证原生结构、地基适配和其他 worldgen 行为没有回归。
