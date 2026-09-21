# AdventureWorldGen

AdventureWorldGen 是面向 Minecraft 整合包作者的冒险世界宏观编排系统。
这里提供项目介绍、开发启动方式和文档入口。

作者通过 JSON 表达想安排的群系、结构、面积、数量与冒险进度。
系统先生成有限大陆的宏观地形、水文和气候，规划群系归属与结构宏观锚点，
再保存冻结计划，由 Minecraft worldgen 按需生成地形与结构所在的区块。

规划决定世界整体布局；玩家探索触发区块物化。
冻结结果使不同区块请求共享同一份世界安排。

## 系统包含什么

| 部分 | 承担的工作 |
|---|---|
| 有限大陆 | 单块大陆、海岸轮廓与外围海洋 |
| 地形 | 连续区域、地形配方、山脉与海床 |
| 水文与侵蚀 | 河流、湖湿地、水位、河床切削与侵蚀增量 |
| 气候与群系 | 温湿度场、环境准入、必需需求与剩余地表填充 |
| 冒险进度 | 基于到达成本的位置偏好 |
| 结构 | 数量、承载群系、间距与锚点规划；Template、Jigsaw、Java 分区块执行与地形适配 |
| 运行时 | 计划发布屏障、READY 恢复与只读查询 |
| Minecraft 接入 | 区块列、群系、表层、原生结构管线与出生位置 |

范围、依赖和关键入口见 [架构概览](docs/architecture/overview.md)。
字段语义见 [profile 参考](docs/reference/profile.md)。

## 技术环境

| 项目 | 配置值 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.249 |
| Java | 21 |

版本依据是 [gradle.properties](neoforge/gradle.properties) 与
[build.gradle](neoforge/build.gradle) 中的 Java toolchain。
工程使用仓库自带的 Gradle Wrapper。

## 快速开发

准备 JDK 21；首次构建需要下载 Wrapper、插件和游戏依赖。
从仓库根目录执行：

```sh
cd neoforge
./gradlew build
./gradlew runClient
```

Windows 使用 `gradlew.bat`。
生产 JAR 输出在 `neoforge/build/libs/`（构建时生成）。
客户端开发目录为 `neoforge/run-client/`（运行时生成）。

普通专用服务器入口：

```sh
./gradlew runServer
```

服务器使用 `neoforge/run-production-server/`，需要按服务器提示确认 EULA。
客户端和生产服务器只加载主模组；测试伴随模组有独立入口。

内置普通世界预设将主世界接到本生成器；下界与末地保留原版生成器。

## 文档入口

- [开发文档目录](docs/README.md)：按问题找到唯一主题文档。
- [AGENTS.md](AGENTS.md)：代码代理和新开发者的包职责、约束及事实来源。

首次阅读沿“架构概览 → 世界生命周期 → 修改指南”进入对应模块。
修改已有存档所用的配置前，阅读目录中的计划格式与 READY 契约。

## 测试入口

以下命令均在 `neoforge/` 运行；首次使用工具脚本的离线模式前先完成依赖准备。

| 目的 | 入口 |
|---|---|
| JUnit | `./gradlew test` |
| 默认 GameTest | `./gradlew runGameTestServer` |
| 全量 GameTest | `./tools/run-full-gametest.sh` |
| 审计与预览发现 | `./tools/run-audit-tool.sh --list` |

测试分组、READY 对照、工具参数、平台依赖和验证范围统一见
[测试指南](docs/development/testing.md)。
工具输出是算法诊断证据，不能代替实际区块和客户端验证。

## 内容接入

数据包覆盖与模组适配使用 [适配器参考](docs/reference/adapters.md)。
随包默认内容在资源文件中维护，文档不复制整份默认 profile。
测试伴随模组展示自定义群系接入和实际 worldgen 验证方式。

## 许可与来源

项目许可见 [工程元数据](neoforge/gradle.properties)。
FreeTerraForged 派生算法的固定来源、适配边界与随包许可见
[来源说明](docs/reference/ftf-provenance.md) 和 [NOTICE](neoforge/src/main/resources/META-INF/NOTICE)。
