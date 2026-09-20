# 测试与审计

这里说明修改某部分后运行什么、输出在哪里，以及证据能证明到哪一层。
命令除特别说明外都从仓库的 `neoforge/` 执行，使用 Java 21 和仓库 Gradle wrapper。
首次构建需下载依赖；`--offline` 只适用于缓存已完整的环境。

<a id="selection"></a>
## 按修改选择验证

| 修改 | 必须覆盖的层 |
|---|---|
| 纯算法、配置或包依赖 | JUnit，包括 PackageBoundaryTest；算法变化加相关数值/预览检查 |
| planner、容量、成本或气候准入 | JUnit + planning GameTest；容量规则加 capacity 组 |
| codec、身份与冻结状态 | persistence/runtime JUnit + READY 对照 |
| 结构需求、规划信息与锚点 | planner/codec JUnit + planning GameTest |
| worldgen、surface、海洋资源、mixin | JUnit + 完整 GameTest；必要时实际客户端观察 |
| 性能 | 对应 performance 组和基准；固定 seed、配置、JVM 参数及 cold/READY 条件 |
| 仅文档 | 文档检查、命令存在性与示例验证；不据此声称重新验证了游戏生成 |

<a id="unit"></a>
## JUnit 与构建

```bash
./gradlew test
./gradlew build
./gradlew compileTestmodJava
```

`build` 包含 JUnit 和生产 JAR 构建，但不执行 GameTest。
`compileTestmodJava` 只证明伴随模组可编译。
JUnit 报告生成在 `build/reports/tests/test/index.html`，机器可读结果在 `build/test-results/test/`。

定向排查可使用测试过滤器：

```bash
./gradlew test --tests '*PackageBoundaryTest'
./gradlew test --tests 'io.github.luoyan.adventureworldgen.planner.*' --tests 'io.github.luoyan.adventureworldgen.cost.*'
./gradlew test --tests 'io.github.luoyan.adventureworldgen.persistence.*' --tests '*PlanIdentityTest' --tests '*DeterminismAcceptanceTest'
```

边界测试的源码根由 Gradle 从 projectDirectory 传入，从其他目录调用时也应使用
`./neoforge/gradlew -p neoforge test` 这种显式工程选择。
缺源码路径应失败，不能跳过检查并视为通过。

<a id="gametest"></a>
## GameTest：默认组与完整集合

[build.gradle](../../neoforge/build.gradle) 定义生产与 testmod source set、run 配置和默认命名空间。
默认入口是：

```bash
./gradlew runGameTestServer
```

该任务只运行默认命名空间，不等于完整 GameTest 集合。
它会复用 `run-gametest/`；首次规划行为必须使用全新目录。
[gametest-group.gradle](../../neoforge/tools/gametest-group.gradle) 统一提供分组和独立工作目录：

```bash
./gradlew runGameTestServer -I tools/gametest-group.gradle -PgametestGroup=default -PterrainAuditWorld=run-doc-check-default
```

此示例目录应在首次运行前不存在；重复使用会混入已生成状态。
不同测试命名空间不能在这里简单拼成一个服务器启动替代完整分组验收。

| 组 | 主要覆盖 |
|---|---|
| default | 内容注册、出生、表层、气候、区块与生成修正 |
| performance | 完整规划或查询的耗时与运行约束 |
| capacity | 容量回归和受限配置 |
| planning | 新世界生产规划、冻结结果和完整约束 |

[run-full-gametest.sh](../../neoforge/tools/run-full-gametest.sh) 顺序运行全部组，使用独立目录并核对发现、执行和通过清单：

```bash
./tools/run-full-gametest.sh
./tools/run-full-gametest.sh --group planning
./tools/run-full-gametest.sh --group capacity
./tools/run-full-gametest.sh --group performance
```

运行器需要 Bash 和名为 `timeout` 的命令；macOS 环境应先确认该命令在 PATH 中。
脚本默认传 `--offline`；需要联网解析时可用
`GRADLE_ARGS='--no-daemon' ./tools/run-full-gametest.sh`。
`RUN_ID` 可指定唯一运行标识，`GROUP_TIMEOUT` 控制每组墙钟保护时限；
报告生成在 `build/gametest-full-<RUN_ID>/summary.txt` 和同目录的组日志。

新增 GameTest 时同步检查
[GameTestInventoryTest](../../neoforge/src/test/java/io/github/luoyan/adventureworldgen/GameTestInventoryTest.java)
与运行器中的预期清单。不要仅凭 Gradle 成功断言全部测试被发现。
脚本允许测试已全通过后在服务器关闭阶段触及超时；
这类“通过”不是规划速度或关闭速度达到性能预算的证明。

<a id="ready"></a>
## READY 对照

纯数据往返运行 persistence 和 runtime 测试；`PlanningBaselineTest` 检查规范字节、恢复后重编码与采样字段。
GameTest 中需要 fresh planning 的组必须使用全新目录，恢复对照必须复用同一目录、seed 与配置。
结构计划只保存宏观锚点，因此 READY 验证不再包含 piece/NBT 重放或结构起点注入。
不要删除用户存档来制造 cold 条件；使用运行器生成的独立目录。

<a id="tools"></a>
## Java audit / preview tools

[run-audit-tool.sh](../../neoforge/tools/run-audit-tool.sh) 将工具编译到生成目录
`build/audit-tools/classes/`，不加入生产 source set 或 JAR：

```bash
./gradlew -I tools/planning-benchmark.gradle writePlanningBenchmarkClasspath
./tools/run-audit-tool.sh --list
./tools/run-audit-tool.sh --compile-all
AWG_TOOL_JAVA_OPTS=-Djava.awt.headless=true ./tools/run-audit-tool.sh CoastDetailPreview 12345 build/reports/coast.png
AWG_TOOL_JAVA_OPTS=-Djava.awt.headless=true ./tools/run-audit-tool.sh OceanShelfPreview build/reports/ocean.png
```

改变依赖、编译环境或生产源码后先重新运行 classpath 任务；工具脚本发现已有 classpath 文件会复用它。
`AWG_TOOL_MEM` 控制堆上限，`AWG_TOOL_JAVA_OPTS` 传额外 JVM 参数；
`AWG_GRADLE_ARGS` 默认 --offline，仅用于缺失 classpath 时的 Gradle 调用。
上例以 headless 模式渲染 PNG，适用于没有可用图形会话的终端或受限运行环境。

以下参数是位置参数；`<plan-dir>` 指直接包含 manifest 和 plan.json.gz 的叶目录，
不是服务器目录或世界根目录。工具输出路径需要由使用者选定。

| 目的 | 工具与参数 |
|---|---|
| 独立首次规划基准 | `PlanningBenchmark <profile.json> <new-output-dir> <seed>` |
| 解码并重建查询对象 | `PlanReloadBenchmark <plan-dir> <profile.json> <repetitions>` |
| 热点查询基准 | `ChunkQueryBenchmark <plan-dir> <profile.json>`；轮数与采样量由工具源码定义 |
| 地形与归属图 | `PlannerMapPreview <plan-dir> <output.png> [title]`、`BiomeDetailPreview <plan-dir> <output.png>` |
| 边界切片 | `BoundaryPreview <plan-dir> <output-dir>` |
| 冻结计划地形/拓扑数值 | `PlanTerrainAudit <plan-dir> <output.tsv>`、`PlanPatchTopologyAudit <plan-dir> <output.tsv>` |
| 温度场实验图 | `TemperatureFieldPreview <plan-dir> <output-dir> [实验参数…]` |
| 单种子河流/配方 | `RiverPreview <seed> <output.png>`、`TerrainRecipePreview <seed> <output.png>` |
| 多种子配方统计 | `TerrainRecipeAudit <output-dir> <seed> [seed…]` |
| 独立需求规划审计 | `DemandPlannerAudit <output-dir> <seed> [seed…]` |

`PlanningBenchmark` 拒绝已存在的输出目录，并按 profile 构造纯结构规划目录；
它生成的是规划审计包，不能据此声称 Minecraft 已生成对应结构。
`PlanReloadBenchmark` 从 manifest 读取已有输入摘要，没有重新执行生产身份和完整包校验；
它测量重建，不证明整个 READY 包有效。
目录预览工具多使用当前内置 default 配置，读取其他配置的计划前必须核对工具源码。

`DemandPlannerAudit` 产生自己的审计 plan JSON，不是服务器计划目录；
`HumidityAudit`、`DefaultDiversityAudit`、`FrozenTerrainMetrics` 消费相应审计数据。
这些格式不能与 PlanReloadBenchmark 的输入互换。
TemperatureFieldPreview 的可选参数是实验场参数，不应把参数扫描图当成实际生产气候结果。

工具全集及个别固定路径以 [tools](../../neoforge/tools/) 的源码入口为准；
编译成功不证明工具读取的文件存在或选用的 profile 匹配。
Python 绘图脚本需按 import 准备 NumPy、Matplotlib、Pillow 等实际依赖。
`generate_ocean_settings.py` 会写入资源并依赖本地游戏构建产物，不是只读验证入口。

<a id="docs-check"></a>
## 文档检查与证据

从仓库根目录运行：

```bash
python3 neoforge/tools/check-docs.py
```

该工具检查开发文档的本地链接、锚点、路径、项目 Java 类型引用、命令脚本与历史措辞。
有 Gradle 任务清单时还可传 `--gradle-tasks <tasks-log>` 检查任务存在性；
任务清单通过 `./gradlew tasks --all -I tools/planning-benchmark.gradle` 获取。

文档检查不替代 JSON parser、运行环境或游戏验证。
提交说明区分静态检查、编译、JUnit、GameTest、普通世界重放与视觉观察，
并写明未执行的相关层。
