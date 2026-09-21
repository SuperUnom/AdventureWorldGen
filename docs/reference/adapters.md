# 内容适配器

这里定义第三方群系契约、结构规划信息与结构执行生成定义。
群系扩展依赖 [api](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/api/) 中的公开契约，
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
`PlannedStructurePlacement` 是后续执行层使用的宏观位置，不能当作最终几何。

<a id="execution"></a>
## 结构生成定义

结构本体仍是活动 `worldgen/structure` 注册内容。标准 Jigsaw 使用原生 `minecraft:jigsaw` 定义，
可配置起始池、起始连接点、深度、距离、高度、池别名和投影等原生字段；执行器读取同一份定义，不复制配置。
其他已有 Java Structure 保留自己的生成和存档实现。

独立模板使用 `adventureworldgen:template` 类型，例如放在数据包的
`data/example/worldgen/structure/hall.json`：

```json
{
  "type": "adventureworldgen:template",
  "biomes": ["minecraft:plains"],
  "spawn_overrides": {},
  "step": "surface_structures",
  "terrain_adaptation": "none",
  "template": "example:hall",
  "processors": "minecraft:empty",
  "support": {"min_x": 0, "min_z": 0, "max_x": 15, "max_z": 15, "surface": 0},
  "heightmap": "WORLD_SURFACE_WG",
  "height_offset": 0,
  "rotation": "none",
  "foundation": {"mode": "flatten", "margin": 12}
}
```

其中 example 只是作者命名空间示例，不是模组内置结构。对应模板资源是 `data/example/structure/hall.nbt`。
然后在 profile 的 structures 中声明 `example:hall` 的需求；[profile 参考](profile.md) 定义数量和承载群系字段。

| 字段 | 语义 |
|---|---|
| template | 必须存在且非空的模板资源 |
| processors | 原生 processor list holder，处理器仍由 Minecraft 放置阶段调用 |
| support | 模板局部坐标的闭区间二维支撑矩形；surface 是局部地基顶部第一个非地基方块的 Y |
| heightmap / height_offset | 从锚点基础高度查询取得支撑面，叠加偏移；模板原点 Y 为支撑面减 support.surface |
| rotation | 原生 rotation 名；省略时由结构生成随机源确定，随 piece 保存 |
| foundation | 模板缺省地形策略；仅用于接管后的执行 |

支撑区域必须位于模板尺寸内。独立模板不解释 Java 数据标记，也不组装 Jigsaw 连接：
包含结构方块或 Jigsaw 方块时预检失败，应使用相应 Java/Jigsaw 生成方式。
普通模板方块、方块实体和模板实体仍由原生模板放置处理；能移动方块位置的自定义处理器必须与声明的支撑区域和引用范围一致。

### 地形策略覆盖

可在 `data/<namespace>/adventureworldgen/structure_execution/<structure-path>.json` 覆盖同 ID 的地形策略：

```json
{"mode": "fill", "margin": 12}
```

只允许 mode 和 margin。margin 为 0–12 方块；mode 有以下取值：

| mode | 语义 |
|---|---|
| native | 使用结构原生适配类别与投影信息；Java/Jigsaw 的缺省值 |
| none | 不额外适配地形 |
| fill | 支撑区域只填高，不削低已有地表 |
| flatten | 支撑区域可填高或削低，边缘按 margin 过渡 |

Java 结构请求 fill/flatten 时必须实现
[TerrainSupportProvider](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/worldgen/structure/TerrainSupportProvider.java)，
返回 NOISE 之前已经确定的世界坐标支撑区域；不能在 postProcess 时移动这些区域。
这个 Minecraft 执行扩展点不属于纯 planner API。

没有按特定结构 ID 判断的执行逻辑。暂不支持的第三方能力应明确失败，不能通过猜测模板或整体搬移 pieces 伪装兼容。
区块引用、保存、失败及资源身份契约统一见 [结构执行](../systems/runtime-worldgen.md#structures)。

## 验证

纯规划与 codec/READY 测试继续证明锚点数据边界；PackageBoundaryTest 禁止执行模型进入 planner，
也禁止执行器读取整份计划或配置。实际结构、存档和地形适配由 structure GameTest 组验证。
