# 跨系统约束

这里回答跨模块修改必须保持哪些性质，以及哪些性质由当前测试直接约束。
下列要求是开发边界，不能据此声称所有输入都已获得运行时证明。

<a id="determinism"></a>
## 确定性

同一完整生成输入必须得到同一规划结果：随机选择使用稳定键，遍历与并行归并保持稳定顺序，
搜索用操作预算而非墙钟时限终止。输入身份实际包含什么、哪些变化不会自动入 hash，
统一见 [计划身份](../reference/plan-v2.md#identity)。

缓存只允许复用可重算结果；命中、淘汰、线程或区块访问顺序不能成为新随机输入。
验证时同时检查冷缓存、热缓存、恢复和负坐标，不能仅重复相同查询顺序。
随机调用来源见 [RandomDomains](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/plan/RandomDomains.java)，
计划的 `random_keys` 字段不提供随机完整性证明。
当前气候缓存的验证缺口见 [环境查询边界](../systems/terrain.md#query-limits)。

<a id="frozen"></a>
## 冻结计划

发布前必须完成规划和验证；运行时查询不得重新分配群系、移动结构或补抽实例。
恢复可以重新组装确定性函数及索引，不得重新求解已冻结布局。
同一结构实例跨多个区块仍只计一次。

发布后的缓存可变不等于计划内容可变；缓存写入不得改变冻结归属和几何。
磁盘身份不匹配时的重新规划属于启动过程，不能称为已有区块迁移。

<a id="dependency"></a>
## 依赖边界

下表总结 [PackageBoundaryTest](../../neoforge/src/test/java/io/github/luoyan/adventureworldgen/PackageBoundaryTest.java)
实际检查的禁用引用前缀。测试扫描剔除注释后的源码，包括全限定引用；这是源码依赖护栏。

| 包 | 禁止引用 |
|---|---|
| `config` | Minecraft、NeoForge、`planner`、`runtime` |
| `plan` | `planner`、`runtime`、`config`、`hydrology` |
| `spatial` | 其他本项目包 |
| `noise` | `planner`、`runtime`、`terrain`、`config` |
| `api` | `planner`、`runtime`、`hydrology`、`terrain`、`surface`、`worldgen` |
| `planner` | `runtime` |
| `terrain` | `planner`、`runtime`、`config` |
| `biome` | `planner`、`runtime` |
| `climate` | `planner`、`runtime` |
| `hydrology`、`erosion` | Minecraft、NeoForge、`biome` |
| `compat` | `runtime`、`planner`、`worldgen`、`terrain`、`hydrology`、`config` |
| `runtime` | Minecraft、NeoForge |
| `persistence` | `runtime` |
| `surface`（出现时生效） | `hydrology`、`planner`、`runtime`、`worldgen` |

测试要求已声明的生产包存在且非空；`surface` 是唯一允许缺席的预留包。
它还检查 planner 不引用结构执行模型，并检查共享生成器没有计划结构注入入口。
没有列出的方向不等于鼓励添加依赖，仍应审查职责与环路。

<a id="ownership"></a>
## 地形和材料所有权

| 职责 | 所有者 | 不应越界做的事 |
|---|---|---|
| 区域配方、岸线、山脉与海床 | `terrain` | 读取整份作者配置并求解需求 |
| 需求驱动的地形容量承诺 | `planner` | 在最终生成列上按群系限制截平高度 |
| 侵蚀增量与高度滤波 | `erosion` | 查询时重新模拟水滴或选择材料 |
| 河网、水位、湖湿地、切削和封岸量化 | `hydrology` | 决定河床方块与 Minecraft 群系 |
| 气候准入与归属过渡 | `biome` | 重画地形或持有游戏生命周期 |
| 最终方块和 surface rules | `worldgen` 与原版执行管线 | 重新规划地块或执行规划结构 |

连续采样使用同一组冻结输入；网格是用途相关的采样与归属表示，不是另一套低精度地形公式。
原生结构地基是区块期显式适配，不能混同为已反馈到成本图的宏观高度。

<a id="constraints"></a>
## 约束层级

硬条件先决定合法域，软偏好只排序合法候选，优化不得破坏已有硬条件。
Adventure level 是位置软偏好，不是互斥空间区间。
允许的温湿度类型与类型权重是两件事：集合控制准入，权重控制合法候选评分。
面积最低值、目标、上限及不同阶段的失败边界见 [规划系统](../systems/planning.md#constraints)。

不能把有界搜索失败报告成全空间无解，也不能用评分放宽结构数量、间距或环境准入。
当前实现的能力限制必须在对应系统文档中保留，并用 [测试与诊断](../development/testing.md) 验证。

<a id="structure-boundary"></a>
## 结构规划边界

- `StructurePlanningInfo` 是结构类型向 planner 提供固有规划事实的唯一通道。
- `StructureDemand` 是作者需求，不是结构自身属性。
- `PlannedStructurePlacement` 是宏观锚点，不是 Minecraft 结构起点或最终原点。
- planner 只产生纯规划数据，不产生 Minecraft 对象，也不得按具体 structure ID 写特例。
- worldgen 当前不执行 planned structure placements；原生结构由 Minecraft 自身生成。
- AdventureWorldGen 当前没有结构生成系统，不保留半实现的结构 adapter API。
- 未来结构生成必须建立在 planner 输出之上，不能让 planner 调用生成实现。
