# 内容适配器

这里回答第三方内容能扩展什么、何时注册，以及如何验证冻结和区块恢复。
第三方应依赖 [api](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/api/) 的契约，
不应调用内部 planner 来另建一套布局或状态。

## 三个边界

| 边界 | 职责 | 示例 |
|---|---|---|
| 公开扩展契约 | 内容 ID、兼容性、结构准备、冻结数据、能力检查 | BiomeAdapter、StructureAdapter、AdapterRegistry、FrozenPieceSupport |
| 原版兼容实现 | 具体原版内容语义和纯数据构造 | compat.vanilla 中的沙漠神殿与水体群系策略 |
| 游戏接入 | 注册 Minecraft 类型、加载 pieces、注入起点、执行方块生成 | MinecraftAdapters、RegisteredPieceSupport、FrozenPieceRestore |

## 注册时机

[AdapterRegistrations](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/api/AdapterRegistrations.java)
接受外部适配器，应在模组构造等早期阶段完成注册。
[AdapterRegistry](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/api/AdapterRegistry.java)
排序保存显式 biome/structure adapters，并提供 generic biome fallback。
重复同类 ID 会报告 CONFIG_CONFLICT / adapter-registration。

[MinecraftAdapters](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/worldgen/MinecraftAdapters.java)
合并内置和外部注册并冻结；首次冻结后拒绝新注册。
该集合是进程静态状态，服务器停止不重新开放注册窗口。
adapterVersion 进入 [计划身份](plan-v2.md#identity)，改变输出或合法域时必须评估版本更新。

数据包可提供实际 biome/structure 内容和默认 profile 覆盖；
JSON 本身不能实现 Java adapter 或注册 StructurePieceType。
内容还必须在活动注册表中存在，通过 [预检](profile.md#loading)。

## 群系

[BiomeAdapter](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/api/BiomeAdapter.java)
提供 biomeId、adapterVersion 和 compatibility(MacroSample)。
返回的 Compatibility 表示 allowed、[0,1] 的 softPreference 和原因。

当前规划桥接使用 allowed 参与显式需求准入；没有消费 softPreference 作为统一评分项。
FillerLayout 没有公开 adapter 回调，不能宣称一个 adapter 的限制自动覆盖所有填充与最终查询路径。
若外部群系需要严格限制，必须验证 required、filler、局部过渡和运行时查询，并配置对应作者环境规则。

这个 API 不负责表面 palette、植被或材料。
Minecraft surface rules 和群系地物接入见 [表层执行](../systems/runtime-worldgen.md#surface)。

## 结构

[StructureAdapter](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/api/StructureAdapter.java)
把一个候选变为完整且可恢复的准备结果：

| 接口/数据 | 扩展者需要保证 |
|---|---|
| describe | 可用旋转、最大 footprint 半径及能否冻结全部 pieces |
| Candidate | 以提供的 instanceId、原点和旋转为候选身份 |
| prepare(candidate, structureSeed) | 仅使用确定性输入，完成布局与随机选择，返回稳定顺序的完整 pieces |
| Prepared | 含候选、水平占地、群系保护框及实际入口世界坐标 |
| validatePrepared | 用提供的 MacroTerrain 检查真实占地与放置条件；失败返回原因 |

水平框使用包含端点的方块坐标。
每个 PlannedPiece 必须保存可被已注册 loader 恢复的 NBT 和部件类型 ID；
布局、变体、容器/战利品种子等影响重放的随机状态应在发布前冻结。
只保存结构 seed 并在区块期重抽布局不符合契约。

适配器不直接放方块，也不维护逐区块提交记录。
`StructureAdapterBridge` 完成准备与纯规划数据间的转换；
Minecraft 端恢复 StructureStart 后执行普通分块物化。

## 冻结部件能力与示例

[FrozenPieceSupport](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/api/FrozenPieceSupport.java)
是游戏能力的纯接口，生产实现为 RegisteredPieceSupport。
首次发布前和 READY 恢复后检查 NBT 部件类型是否注册；
这不是实际运行全部 loader 的替代品。

可运行示例集中在测试伴随模组：

- [TestCompanion](../../neoforge/src/testmod/java/io/github/luoyan/adventureworldgen/testcompanion/TestCompanion.java)：注册游戏内容与适配器。
- [TestCompanionAdapters](../../neoforge/src/testmod/java/io/github/luoyan/adventureworldgen/testcompanion/TestCompanionAdapters.java)：第三方群系和结构准备。
- [WaystationPiece](../../neoforge/src/testmod/java/io/github/luoyan/adventureworldgen/testcompanion/WaystationPiece.java)：跨区块部件、容器与 NBT。
- [WaystationAudit](../../neoforge/src/testmod/java/io/github/luoyan/adventureworldgen/testcompanion/WaystationAudit.java)：普通世界 cold/READY 对照。

testmod 是独立 source set，不随生产 JAR 发布。
`VanillaDesertPyramidAdapter` 是具体内置兼容实现，不是第三方必须继承的基类。

<a id="limits"></a>
## 当前消费边界

- 作者 `structures[].entrance` 被解析并进入 StructureInstanceDemand.relativeEntrance，
  但 StructureAdapterBridge 不读取该字段；规划使用 Prepared 返回的实际入口。
  不能把修改该 JSON 字段描述为能改变实际入口的位置旋钮。
- Descriptor.requiresSupportPatch 不代表存在自动反馈到宏观地形的修补流程；
  实际区块地基适配见 [区块列](../systems/runtime-worldgen.md#chunk)。
- [FrozenPieceRestore](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/worldgen/FrozenPieceRestore.java)
  创建的序列化上下文使用 null resourceManager，并提供注册表与模板管理器。
  需要 resourceManager 的第三方 loader 不能假定直接可用，必须做真实加载验证。
- 注册预检通过仍可能在区块期因 NBT、loader 或模板条件失败。
  当前恢复异常可为 IllegalStateException，并非所有失败都归一为 PlanningFailure。

## 验证要求

变更适配器要验证注册冲突、版本身份、prepare 确定性、完整 NBT、footprint 与保护区、
codec 往返、READY、跨区块引用以及受控原生候选抑制。
`StructureAdapterBridgeTest` 验证纯桥接；GameTest 与普通世界审计验证 Minecraft 物化。
具体命令见 [测试指南](../development/testing.md#ready)。
