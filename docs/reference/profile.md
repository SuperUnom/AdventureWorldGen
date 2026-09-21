# 作者配置

这里定义作者 JSON 的字段、缺省行为和交互条件；规划结果的含义见 [规划系统](../systems/planning.md)。
字段事实来源是 [AdventureWorldConfigParser](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/config/AdventureWorldConfigParser.java)
与 [AdventureWorldConfig](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/config/AdventureWorldConfig.java)。
发布配置以 [内置 default.json](../../neoforge/src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json) 为准，
不等于所有字段省略时的 parser 默认值。

<a id="loading"></a>
## 加载与最小示例

[ProfileReloadListener](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/worldgen/ProfileReloadListener.java)
固定加载 `adventureworldgen:default`，数据包内路径为
`data/adventureworldgen/adventureworldgen/profiles/default.json`。
资源栈最多允许两份：内置资源和一份覆盖；取栈中最后一份，超过上限报告配置冲突。
当前不提供任意 profile 文件的扫描和选择机制。

下面是可被 parser 接受的最小配置形态，不是推荐的完整整合包配置，也不保证每个种子都能完成规划：

```json
{
  "world": {"radius": 1536},
  "spawn": {"biome": "minecraft:plains"},
  "biomes": {"filler": ["minecraft:plains"]}
}
```

对象不接受未知字段、重复键或以 null 代替省略；数值必须有限，整数必须能精确表示为有符号 64 位值。
内容 ID 使用带命名空间的字符串。语法和字段验证在 reload 进行；
注册表内容与群系适配器可用性在服务器启动前由
[ContentPreflight](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/config/ContentPreflight.java) 检查。
parse 成功不等于内容预检或规划成功。

## 顶层与 world

| 字段 | 类型与必填性 | 语义与缺省 |
|---|---|---|
| `world` | 必填对象 | 有限大陆与地形设置 |
| `spawn` | 必填对象 | 出生群系 |
| `biomes` | 必填对象 | 显式需求、填充池和环境规则 |
| `structures` | 可选数组 | 缺省为空；结构 ID 不可重复 |
| `world.radius` | 必填数值，> 0 | 方块单位的大陆包络半径；不是内存或搜索预算 |
| `world.terrain` | 可选对象 | 缺省使用 `TerrainSettings.defaults()` |

### terrain

`composite` 与 `mountain_ranges` 都是可选布尔值，缺省 true。
`templates` 是按配方 ID 索引的可选对象，可用名称为：
`steppe`、`plains`、`hills_1`、`hills_2`、`dales`、`torridonian`、
`plateau`、`badlands`、`mountains_1`、`mountains_2`、`mountains_3`、`volcano`。

每份配方设置都是局部覆盖，未列出的配方仍保留默认权重，并不会被禁用。
每个设置对象支持以下可选字段，其缺省数值统一取
[TerrainTemplate](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/terrain/TerrainTemplate.java)：

| 字段 | 校验 | 作用 |
|---|---|---|
| `weight` | [0, 10000] | 选择权重；零禁用该配方 |
| `horizontal_scale` | [0.25, 8] | 水平尺度倍率 |
| `vertical_amplitude` | (0, 220] | 方块单位的垂直幅度 |
| `detail_strength` | [0, 2] | 配方细节强度 |

至少启用一个配方。配方、粗分类和复合规则的关系见 [区域地形](../systems/terrain.md#regions)。

## spawn

| 字段 | 类型与必填性 | 语义 |
|---|---|---|
| `biome` | 必填 ID | 约束出生群系；会参与隐含需求展开 |

`spawn.structure` 已删除并按未知字段拒绝。出生点由出生群系与安全地形决定，不绑定结构实例。

## biomes

| 字段 | 类型与必填性 | 缺省与语义 |
|---|---|---|
| `required` | 可选数组 | 缺省空；每项拥有来源身份，可按[规划合并规则](../systems/planning.md#demands)共享地块 |
| `required[].id` | 必填 ID | 需求群系；相同 ID 可重复 |
| `required[].adventure_level` | 必填整数 [0,10] | 位置软偏好 |
| `required[].area` | 可选面积对象 | 缺省取 AreaRange.DEFAULT |
| `required[].road` | 可选对象 | 此群系的[道路连接](#roads)；缺省不连接 |
| `filler` | 必填非空 ID 数组 | 剩余区域候选；排序去重，不保证每项都出现 |
| `terrain_rules` | 可选 ID → 规则对象 | 缺省空；也会预检未被其他字段引用的规则 ID |
| `blend_radius` | 可选整数 [0,32] | 方块单位的局部群系过渡半径，缺省 4 |

<a id="rules"></a>
## 环境规则

以下字段均可省略。规则中的集合决定合法域，正权重只在合法集合内影响评分。
`allowed_terrain` 是粗分类，`allowed_templates` 是具体配方，不可互相替代。

| 字段 | 类型/合法值 | 缺省与交互 |
|---|---|---|
| `allowed_terrain` | 非空字符串数组：plains、hills、plateau、mountains | 全部粗分类 |
| `allowed_templates` | 非空配方 ID 数组 | 全部配方；与粗分类求交，再检查启用状态 |
| `landforms` | 非空数组：lowland、foothill、slope、peak | 不限制地貌部位；显式空数组非法 |
| `min_height`、`max_height` | 有限数值，min ≤ max | 不限制；检查 groundSurface，属于硬条件 |
| `temperatures` | 非空对象：very_cold、cold、medium、hot → 正数 | 未指定 temperature_level 时允许全部；权重为偏好 |
| `temperature_level` | 整数 [0,10] | 存储缺省 5；仅在显式提供且未给 temperatures 时限定单档 |
| `humidities` | 非空对象：dry、medium、wet → 正数 | 不限制湿度；权重为偏好 |
| `preferred_min_height`、`preferred_max_height` | 有限数值，min ≤ max | 不限制偏好高度；不是硬高度边界 |
| `height_penalty` | 数值 ≥ 0 | 缺省 1，控制偏好高度惩罚 |
| `filler_weight` | 数值 > 0 | 缺省 1，控制填充竞争偏好 |
| `adventure_level` | 整数 [0,10] | 可覆盖 filler 的等级偏好；缺省可从同 ID required 需求推导 |
| `shore_only` | 布尔值 | 缺省 false；仅海岸候选，并参与局部优先选择 |

temperature_level 的单档映射为 0–1 very_cold、2–3 cold、4–6 medium、7–10 hot。
同时给 temperatures 时以后者为准，但前者仍会被校验并进入规范化配置。
未给两者不等于只接受温度等级 5。

每条规则的有效配方交集必须非空且至少有一项启用。
每个启用配方必须有至少一个接受它的 filler，且该 filler 没有硬高度、地貌部位或 shore-only 限制。
此项仅是基础覆盖检查，不证明每种温湿度组合都存在合法 filler；
完整供给及查询限制见 [气候交互](../systems/terrain.md#climate)。

<a id="area"></a>
## 面积

required 条目与 allowed_biomes 承载需求共用面积对象。

| 字段 | 必填性与缺省 | 校验 |
|---|---|---|
| `min` | 对象存在时必填 | 正整数 |
| `max` | 与 target 至少提供一个；缺省 Long.MAX_VALUE | ≥ min |
| `target` | 缺省等于 max | 位于 [min,max] |

未显式提供 target 时还要求 max − min ≥ 256；显式 target 的路径没有此额外间隔条件。
整个 area 省略时使用模型中的 `AreaRange.DEFAULT`，其参数与“省略某个子字段”不同。

面积单位是水平干地的方块数；最终分配基于 4×4 单元。
最小单元需求向上取整，最大允许单元数向下取整；小于一个单元的范围未必可实现。
min、target、max 在容量阶段和最终分配中的不同强度，统一定义在
[约束分层](../systems/planning.md#constraints)。不要把 min 当成无条件成功承诺。

## structures

| 字段 | 类型与必填性 | 语义/缺省 |
|---|---|---|
| `id` | 必填 ID | 受控结构 ID；全数组唯一 |
| `adventure_level` | 必填整数 [0,10] | 位置软偏好 |
| `count` | 必填对象 | min、max 均为必填非负整数，max ≥ min |
| `allowed_biomes` | 必填对象 | 必须含 id 数组；可含 area 对象 |
| `allowed_biomes.id` | ID 数组，可为空 | 排序去重；空列表从 filler 池展开候选，不代表任意注册群系 |
| `allowed_biomes.area` | 可选面积对象 | 每个原始承载需求的面积设置；合并规则见[需求池](../systems/planning.md#demands)，省略取 AreaRange.DEFAULT |
| `placement_mode` | 可选字符串 | 缺省且仅支持 scattered |
| `spacing` | 可选对象 | min 缺省 0，max 缺省不限制 |
| `road` | 可选对象 | 该条目所有实例的[道路连接](#roads)；缺省不连接 |
| `spacing.min` | 有限数值 ≥ 0 | 方块距离 |
| `spacing.max` | 有限数值 > 0，且 ≥ min | 方块距离 |

count 下界是必须实现的规划实例，max 是允许尝试的上界；它不控制 Minecraft 原生生成数量。
spacing 检查同 ID 规划锚点的两两水平距离；只有一个实例时没有成对约束。
`entrance` 已删除并按未知字段拒绝。

## 规范化与验证

[CanonicalConfigJson](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/config/CanonicalConfigJson.java)
展开缺省并稳定输出对象、集合和结构顺序；required 数组保留顺序及需求身份。
空白、对象键顺序和 filler 排列不改变规范化结果，required 重排可能改变规划身份。
保存身份及变更影响见 [plan 契约](plan-v2.md#identity)。

校验覆盖由 `AdventureWorldConfigParserTest` 和 `TerrainTemplateConfigTest` 锁定；
实际内容和复杂规则组合还需对应 planning GameTest。

<a id="roads"></a>
## 道路

顶层可选 `roads` 只描述总开关与全局施工参数，省略时关闭。
目的地配置与生成需求放在同一条目：`biomes.required[].road` 和 `structures[].road`。
下面是可解析的示例；结构接入还需对应资源声明：

```json
{
  "world": {"radius": 3000},
  "spawn": {"biome": "minecraft:plains"},
  "biomes": {
    "filler": ["minecraft:plains", "minecraft:forest"],
    "required": [
      {"id": "minecraft:forest", "adventure_level": 2,
       "road": {"enabled": true, "required": false}}
    ]
  },
  "structures": [
    {"id": "minecraft:village_plains", "adventure_level": 1,
     "count": {"min": 1, "max": 1}, "allowed_biomes": {"id": ["minecraft:plains"]},
     "road": {"enabled": true, "required": true}}
  ],
  "roads": {"enabled": true}
}
```

出生点自动作为路网根节点。每条目的 `road.enabled` 和 `road.required` 都缺省为假；
前者选择连接，后者要求必须接通，否则阻止 READY。`required: true` 必须同时设置 `enabled: true`。
顶层 `roads.enabled` 是总开关，关闭时不规划道路；群系和结构的生成需求仍然生效。
群系按 ID 合并为一个探索节点，多条同 ID 需求中任一启用且要求必连就按必连处理。
未选择的群系不会自动成为目的地，filler 不提供单独的道路选择。
结构的 `road` 适用于该条目所有已规划实例；`count.min` 决定必须生成的数量，
与道路必连含义独立。结构须提供纯规划接入声明，见 [道路结构契约](../systems/roads.md#结构接入契约)。
旧的 `roads.points`、`roads.biomes`、`roads.structures` 已删除，解析时按未知字段拒绝。
发布默认配置的具体群系、村庄类型和数量以资源文件为准。

以下字段均位于顶层 `roads`：

| 字段 | 作用 |
|---|---|
| `width`、`clearance` | 奇数核心路宽和路面上方净空；陆路另铺窄路肩 |
| `maximum_grade`、`maximum_earthwork` | 连续施工坡度和相对自然地面的挖填范围 |
| `maximum_bridge_length` | 河流短桥最大跨度；零表示不允许跨河 |
| `bend_spacing`、`bend_amplitude` | 平原长缓弯的特征间距和最大横向偏移 |
| `maximum_bend_detour` | 缓弯相对对应直段的长度倍率上限 |
| `loop_budget_fraction` | 相对骨架长度允许添加的环路长度比例 |
| `maximum_nodes`、`maximum_operations`、`maximum_columns` | 节点、地形采样次数及冻结施工列预算 |
| `surface`、`bridge`、`foundation` | 陆路路面、桥体、路基的方块 ID；须为无流体、无方块实体的实心方块 |

完整缺省值和数值范围由 `RoadSettings` 单独定义；parser 拒绝未知字段、错误类型和非整数预算。
规范配置保存全局道路设置及各需求条目的连接开关，因此连接选择、材料、几何和预算变化都会改变输入身份。
生成与恢复语义统一见 [道路系统](../systems/roads.md)。
