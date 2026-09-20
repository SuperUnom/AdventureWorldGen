# 仓库开发指南

这里回答修改 AdventureWorldGen 前应读什么、各包负责什么，以及如何维护开发契约。
AdventureWorldGen 将整合包作者的冒险意图编排为有限大陆的冻结计划，再由 Minecraft worldgen 执行。

## 开始工作

1. 从 [开发文档目录](docs/README.md) 选择与任务相关的文档。
2. 阅读 [架构概览](docs/architecture/overview.md) 和 [跨系统约束](docs/architecture/invariants.md)。
3. 用 [修改指南](docs/development/change-guide.md) 定位代码与验证入口。
4. 先检查工作区差异，保留与当前任务无关的用户修改。
5. 用实际实现、测试断言、资源和持久化格式核实事实，不仅凭注释或文档推断行为。

## 包职责

生产源码根为 [Java 源码目录](neoforge/src/main/java/io/github/luoyan/adventureworldgen/)。
下表包名均相对此目录。

| 包 | 职责 |
|---|---|
| `api` | 群系与结构适配契约、计划查询视图和存储接口；不放内置适配实现 |
| `config` | 作者模型、严格 JSON 解析、规范化与内容预检；注册表查询由外部注入 |
| `plan` | ID、版本、规划参数、冻结环境状态、进度与失败词汇 |
| `spatial` | 世界对齐网格、稀疏掩膜、坐标和查询缓存基础件 |
| `noise` | 确定性随机键、连续噪声与坐标扰动 |
| `climate` | 温度与湿度场、冻结场查询、湿度供给修正 |
| `biome` | 环境准入、偏好评分、局部归属过渡与生长形状策略 |
| `hydrology` | 河网、湖湿地、水位和切削几何 |
| `erosion` | 规划期侵蚀增量场与查询期高度滤波 |
| `terrain` | 海岸、区域配方、山脉包络、海床和最终地貌测量；消费容量结果 |
| `cost` | 有向通行图、到达成本、冒险位置偏好和局部成本细化 |
| `planner` | 需求展开、容量预留、群系竞争分配、结构落位与填充 |
| `persistence` | 冻结快照、内部计划编解码、校验和及原子发布 |
| `runtime` | 首次规划编排、READY 恢复、计划组装与会话查询屏障 |
| `compat` | 原版内容的具体适配器与水体群系规则 |
| `worldgen` | 数据包读取、Minecraft 注册表接入、区块执行和结构部件恢复 |
| `client` | 规划进度加载界面 |
| `mixin` | 原版功能的受限接入钩子，目前用于泉口过滤 |

根包的 `AdventureWorldGen` 注册模组，`AdventureEvents` 接入服务器与主世界生命周期。
`surface` 是测试预留的职责名，当前没有该生产包；材料由原版 surface rules 执行。

## 架构约束

可执行依据是 [PackageBoundaryTest](neoforge/src/test/java/io/github/luoyan/adventureworldgen/PackageBoundaryTest.java)。
完整禁用依赖表只维护在 [依赖边界](docs/architecture/invariants.md#dependency)。

- `config` 与 `runtime` 不依赖 Minecraft / NeoForge。
- `planner` 不依赖 `runtime`；回调使用共享观察接口。
- `plan` 不依赖规划器、运行时、作者配置或水文实现。
- `terrain` 不依赖 `planner`、`runtime` 或作者 `config`；配置只传入地形词汇或策略接口。
- `climate`、`biome` 不依赖规划器和运行时；需求统计通过接口注入。
- `hydrology` 与 `erosion` 不选择 Minecraft 方块或群系。
- `persistence` 只处理冻结数据，不依赖 `runtime` 查询对象。
- `api` 不引用内部求解与执行实现，`compat` 通过公开契约实现具体内容。
- 共享结构恢复按部件注册表分派，不在生成器中增加具体结构类型分支。

护栏按源码中的包引用检查，不是对所有运行时行为的证明。
增加依赖前同时核对实际数据流和测试约束，不通过反射或全限定名绕过检查。

## Source of truth

| 事实 | 真实来源 |
|---|---|
| 配置字段与校验 | [AdventureWorldConfigParser](neoforge/src/main/java/io/github/luoyan/adventureworldgen/config/AdventureWorldConfigParser.java)、[AdventureWorldConfig](neoforge/src/main/java/io/github/luoyan/adventureworldgen/config/AdventureWorldConfig.java) |
| 随包默认 profile | [default.json](neoforge/src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json) |
| 缺省地形参数 | [TerrainSettings](neoforge/src/main/java/io/github/luoyan/adventureworldgen/terrain/TerrainSettings.java)、[TerrainTemplate](neoforge/src/main/java/io/github/luoyan/adventureworldgen/terrain/TerrainTemplate.java) |
| 算法与格式身份 | [PlanVersions](neoforge/src/main/java/io/github/luoyan/adventureworldgen/plan/PlanVersions.java)、[PlannerProfile](neoforge/src/main/java/io/github/luoyan/adventureworldgen/plan/PlannerProfile.java)、[PlanIdentity](neoforge/src/main/java/io/github/luoyan/adventureworldgen/runtime/PlanIdentity.java) |
| 架构边界 | 上述 `PackageBoundaryTest` |
| 构建与运行任务 | [build.gradle](neoforge/build.gradle)、[gradle.properties](neoforge/gradle.properties) |
| GameTest 分组 | [GameTestInventoryTest](neoforge/src/test/java/io/github/luoyan/adventureworldgen/GameTestInventoryTest.java)、[分组脚本](neoforge/tools/gametest-group.gradle)、[全量入口](neoforge/tools/run-full-gametest.sh) |
| 冻结数据与磁盘协议 | [PlanSnapshot](neoforge/src/main/java/io/github/luoyan/adventureworldgen/persistence/PlanSnapshot.java)、[PlanV2Codec](neoforge/src/main/java/io/github/luoyan/adventureworldgen/persistence/PlanV2Codec.java)、[AtomicPlanRepository](neoforge/src/main/java/io/github/luoyan/adventureworldgen/persistence/AtomicPlanRepository.java) |

## 修改规则

不要让底层算法为获取方块、注册表或服务器状态而依赖游戏生命周期。
不要复制配置、版本或冻结状态构造第二份权威数据。

修改地形、随机键、集合遍历顺序或缓存时，检查同输入的确定性和查询一致性。
缓存命中与淘汰只能改变开销，不能改变结果；发现现有缺口应明确报告。
不要用修改黄金值的方式掩盖尚未解释的行为变化。

修改 planner 时，先区分硬约束、软评分和有界失败。
冒险等级不得变成位置硬门槛；面积放宽不能扩大环境准入集合。
具体分配语义以 [规划系统](docs/systems/planning.md) 为准。

修改冻结格式或影响生成的输入时，检查 [计划身份与 READY 兼容性](docs/reference/plan-v2.md)。
不要假定修改任意版本常量都会自动改变 `input_sha256`。
改变预算也可能改变规划结果，需要审查身份标记与持久化约束。

结构适配必须冻结完整部件，通过注册部件类型恢复。
使用 [公开适配契约](docs/reference/adapters.md)，不要让第三方接入依赖内部 planner。

## 验证与交付

按 [测试指南](docs/development/testing.md) 选择验证层级。
纯算法改动运行 JUnit；worldgen、结构恢复、注册与生命周期改动补跑对应 GameTest。
首次规划用新目录，READY 对照复用同一目录和完全匹配的配置。

报告实际执行的命令、输入、结果和未验证范围。
工具编译、纯 Java 查询、Minecraft 区块测试与客户端视觉检查是不同证据。
不要把已有日志或 Git 提交中的通过记录当作本次运行结果。

## 文档维护

文档使用中文，代码标识保持英文。
每个事实在一个主题中完整定义，其他页面使用摘要和链接。
默认 profile、版本字符串和测试数量不在长期文档中重复登记。
不要创建逐文件源码全景图、逐提交实施记录或 Markdown backlog。
设计来源通过 `git log`、`git blame`、`git show` 查询。
文档改动运行 [文档自动检查](docs/development/testing.md#docs-check)。
