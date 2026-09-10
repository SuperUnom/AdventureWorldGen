# AdventureWorldGen NeoForge 工程

[项目文档](../README.md) · [实施与验收指南](../docs/实施与验收指南.md)

Minecraft 1.21.1 / NeoForge 21.1.249 的可游玩 v1 模组工程。实现包括严格 profile、`planner-v2` 联合规划、`ftf-hydrology-adapted-v2`、原子 `plan-v2`、普通世界自动启用、自定义 ChunkGenerator、原生地表材料与表面构造、受控结构抑制与冻结注入，以及隔离的测试伴生模组。

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

[r32 修复说明](../docs/变更记录/曲线边界河网与结构地基-r32.md)：连续群系竞争边界、较长较宽且稀疏的河网、雪山露天泉口过滤和村庄地基适配。155 项单元测试与 5 项隔离 GameTest 通过；请新建世界测试。

[r33 更新说明](../docs/变更记录/分层海底与默认地形多样性-r33.md)：浅海至深海分层下降、海底起伏、减少风袭和平原群系、增加台地与高原模板并放宽群系地形准入。156 项单元测试及 6 项隔离 GameTest 通过。
