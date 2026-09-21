# 仓库开发指南

AdventureWorldGen 将冒险意图编排为有限大陆的冻结计划，再由 Minecraft worldgen 执行。

## 开始工作

- 先检查工作区差异，保留与任务无关的用户修改。
- 从 [文档目录](docs/README.md) 选择相关主题；修改前阅读 [架构概览](docs/architecture/overview.md) 和 [跨系统约束](docs/architecture/invariants.md)。
- 用 [修改指南](docs/development/change-guide.md) 定位实现与验证入口；包职责见 [架构概览](docs/architecture/overview.md#依赖方向)。
- 以实际实现、测试断言、资源和持久化格式核实事实，不仅凭注释、文档或历史记录推断。

## 核心约束

- 遵守 [依赖边界](docs/architecture/invariants.md#dependency) 与 `PackageBoundaryTest`；不通过反射或全限定名绕过检查，底层算法不得依赖游戏生命周期。
- 配置、版本和冻结状态保持单一权威来源；不复制出另一套状态或配置。
- 修改地形、随机键、遍历顺序或缓存时，保持同输入的确定性和查询一致性；缓存只能改变开销，不能改变结果。不得用更新黄金值掩盖未解释的变化。
- planner 区分硬约束、软评分和有界失败；冒险等级不是位置硬门槛，面积放宽不能扩大环境准入集合。详见 [规划系统](docs/systems/planning.md)。
- 结构规划只接收纯 `StructurePlanningInfo`、输出宏观锚点 `PlannedStructurePlacement`，不得按具体 structure ID 添加特例或依赖 pieces、NBT 与区块执行。
- `worldgen` 桥接层将规划锚点接入 Minecraft 结构起点，并在候选选择前排除已接管 ID；结构方块仍由原生区块管线放置。`worldgen.structure` 只接收执行上下文与定位参数，不读取 planner、冻结计划或作者配置；Java 结构保留原生定位，禁止通用整体搬移。
- pieces 与执行元数据随原生起点保存；地基影响范围必须进入区块引用包围盒，并处于原生引用半径内。不得用区块加载事件重放已完成结构，不得静默丢失失败实例。
- 修改生成输入、预算、冻结格式或结构规划字段时，审查 [输入身份、算法版本与 READY 兼容性](docs/reference/plan-v2.md)。影响 planner 输出的新字段必须纳入规范输入摘要，不能假定改版本常量就会改变 `input_sha256`。

## 最小充分验证

按实际行为和调用链选择检查，运行前简要说明范围；[测试指南](docs/development/testing.md) 提供覆盖点与命令，不是每次全部执行的清单。

| 改动 | 默认验证 |
|---|---|
| 仅文档 | 文档检查；仅注释或格式改动检查差异即可 |
| 局部代码 | 用 `--tests` 选择直接相关的 JUnit 类或方法；无适用测试时做必要编译或针对性检查并说明缺口 |
| 包依赖 | 补跑 `PackageBoundaryTest` |
| 确定性、冻结格式、输入身份或 READY | 补充对应契约的回归检查 |
| Minecraft 集成或 JUnit 无法覆盖的行为 | 补跑对应 GameTest 组 |

- 同轮相关修改完成后集中验证，通过即停止；仅因后续相关修改、失败或新的具体风险重跑或扩大，修复后优先重跑失败及受影响项。
- 全量 JUnit、完整 GameTest、全套审计仅用于用户明确要求、既有 CI 要求，或有具体证据证明跨系统影响无法由定向检查覆盖；扩大前说明理由。
- 性能基准、多种子审计、预览和客户端检查按需运行；新增测试应覆盖行为变化、缺陷回归或关键边界，不为凑数量镜像实现。
- 不默认 `clean` 或 `--rerun-tasks`，不重复执行已被其他任务覆盖的检查；需要 `build` 时不预先单独跑同范围 `test`，仅打包时选择产物所需任务。
- 首次规划验证使用新目录；READY 对照复用同目录、seed 和完全匹配的配置，不删除用户存档制造首次规划条件。

## Gradle 缓存

- 统一使用默认 `~/.gradle`；运行前检查 `GRADLE_USER_HOME`，取消非默认覆盖。
- 不通过环境变量、`--gradle-user-home` 或 `-g` 创建独立缓存；沙箱不能写默认目录时申请权限，不以临时缓存绕过。
- 仅用户明确授权或既有 CI 明确要求时可用非默认缓存，须复用稳定路径并说明磁盘占用与清理方式。
- `neoforge/.gradle` 是项目状态目录，不是 Gradle User Home，不向其中重定向 Minecraft / NeoForge 下载缓存。

## 文档与交付

- 文档用中文，代码标识保持英文；每个事实只在一个主题完整定义，其他页面摘要并链接。
- 不重复登记默认 profile、版本字符串或测试数量，不创建逐文件源码全景图、逐提交实施记录或 Markdown backlog；设计来源用 Git 历史查询。
- 文档修改从仓库根运行 `python3 neoforge/tools/check-docs.py`，交付前检查 `git diff --check`。
- 报告实际执行的命令、输入、结果和未验证范围；区分静态检查、编译、JUnit、游戏测试与客户端观察，不把历史通过记录当作本次结果。
