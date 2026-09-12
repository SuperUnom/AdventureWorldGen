# AdventureWorldGen NeoForge 工程

| 信息 | 值 |
|---|---|
| 名称 | AdventureWorldGen |
| Mod ID | `adventureworldgen` |
| 模组版本 | `0.1.0` |
| Minecraft | `1.21.1` |
| NeoForge | `21.1.249` |
| Java | `21` |
| 作者 | 罗言 |
| 许可证 | All Rights Reserved |

基础信息在 `gradle.properties` 中配置；修改 Mod ID 时同步修改 Java 入口中的 `MOD_ID`。作者使用 Java properties 的 Unicode 转义表示，生成后的元数据为中文。

在此目录运行：

```sh
./gradlew clean build
./gradlew runGameTestServer
./gradlew runClient
```

Windows 使用 `gradlew.bat`。首次构建需要联网下载 Gradle 和 NeoForge 等依赖；本机需安装 JDK 21。构建产物为 `build/libs/adventureworldgen-0.1.0.jar`。

工程依据 [NeoForge 官方 1.21.1 ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle) 精简。`runGameTestServer` 会带上不进入生产 JAR 的 `testcompanion` source set；`runTestCompanionServer` 用于真实普通世界的第三方群系、跨区块结构和原生候选抑制验收。详细结果见[实现状态与验收报告](../docs/实现状态与验收报告.md)。

当前地形版本为 `terrain-r22`，延续 12 套具体配方、受约束的区域复合、连续山脉和生成前高度包络拟合。配方与作者字段见 [r21 说明](../docs/变更记录/地形配方与山脉-r21.md)，连续细节与平滑修复见 [r22 说明](../docs/变更记录/地形碎片修复-r22.md)。需新建测试世界，不能用旧区块检验新地形。

[r24 首次规划性能优化](../docs/变更记录/规划性能优化-r24.md) 延续 r22 地形和 r23 区块查询优化，提供完整规划基准及独立游戏回归入口；已有 READY 计划保持兼容。

[r32 修复说明](../docs/变更记录/曲线边界河网与结构地基-r32.md)：连续群系竞争边界、较长较宽且稀疏的河网、雪山露天泉口过滤和村庄地基适配。**历史验证记录（2026-09-09，地形 `terrain-r32`）：** 当时 155 项单元测试与 5 项隔离 GameTest 通过；该数字属于那个提交，不是当前源码的数量，也未随后续改动重跑。请新建世界测试。

[r33 更新说明](../docs/变更记录/分层海底与默认地形多样性-r33.md)：浅海至深海分层下降、海底起伏、减少风袭和平原群系、增加台地与高原模板并放宽群系地形准入。**历史验证记录（2026-09-10，地形 `terrain-r33`）：** 当时 156 项单元测试及 6 项隔离 GameTest 通过；同样只对那个提交有效。当前数量与入口见上面的“测试数量与验证入口”。

## 测试数量与验证入口

四种数字含义不同，不要互相替代：**源码静态方法数**（注解计数）、**实际发现数**（运行时报出的数量）、**执行数**与**通过/跳过数**。下面分开列出，并标明命令与日期。

| 套件 | 入口 | 规模 | 状态 |
|---|---|---|---|
| 单元/集成（JUnit 5） | `./gradlew test` | 源码 `@Test` 注解 232 处，65 个测试类；运行发现并执行 **233** 个 | 2026-09-11 在 `035395a` 上通过：233 通过 / 0 失败 / 0 跳过 |
| 隔离 GameTest（默认组） | `./gradlew runGameTestServer` | 源码 `@GameTest` 24 个；本组 16 个 | 与 `tools/run-full-gametest.sh --group default` 等价 |
| 隔离 GameTest（全量） | `RUN_ID=<标签> ./tools/run-full-gametest.sh` | 16 + 6 + 1 + 1 = 24 个方法 | 2026-09-11 `RUN_ID=boundaries-02`：四组各 16/6/1/1 发现、执行、通过，跳过 0，合计 24；报告在 `build/gametest-full-<RUN_ID>/summary.txt` |
| 审计/预览程序 | `./tools/run-audit-tool.sh --compile-all` | 25 个程序 | 25/25 编译通过；逐项说明见 [tools/README](tools/README.md) |

注解计数与运行发现数不一致，是因为计数只统计注解文本，既不区分 `@Test` 与 `@TestTemplate` 等前缀相同的注解，也不判断是否被条件禁用。**通过数量以本表的运行结果为准，不从注解数量推定。** 运行后可在 `build/test-results/test/*.xml` 核对发现、执行、失败与跳过数量；GameTest 的结果与计数由 `run-full-gametest.sh` 从各组服务器日志中提取并写入 `build/gametest-full-<RUN_ID>/summary.txt`。

四组 GameTest 的范围、命名空间与目录约定见[实施与验收指南 §4.1](../docs/实施与验收指南.md#gametest-groups)；方法标识清单固定在 `src/test/java/.../GameTestInventoryTest.java`，脚本用日志核对“发现数 = 清单数”。**性能组通过不等于性能达标**，耗时与峰值内存需另取自该组日志。

GameTest 服务器在最后一个方法报告之后还要静默刷新区块数分钟（全新目录下观察约 6 分钟），所以 `run-full-gametest.sh` 有按组墙钟上限 `GROUP_TIMEOUT`（默认 2700 秒）：上限只在日志已给出完整结果时记为“通过并附注”，否则记为失败。
