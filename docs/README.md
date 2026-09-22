# 开发文档目录

这里回答开始开发、修改某个系统或排查故障时应该阅读哪一份文档。
这是开发文档的唯一总目录；[项目入口](../README.md) 与 [代理指南](../AGENTS.md) 提供快速起点。

## 第一次了解项目

| 阅读顺序 | 回答的问题 |
|---|---|
| [架构概览](architecture/overview.md) | 系统如何分层，关键入口在哪里 |
| [世界生命周期](architecture/pipeline.md) | 从 profile 到首次规划、发布、恢复及区块生成如何流动 |
| [跨系统约束](architecture/invariants.md) | 修改时必须保持哪些稳定边界 |
| [修改指南](development/change-guide.md) | 具体任务对应哪些实现和验证 |

## 修改系统

已批准的整体改造设计见[多核分层规划与三维道路](architecture/planning-redesign.md)；当前行为仍以以下系统主题和测试为准。

| 我要修改 | 阅读 |
|---|---|
| 海岸、区域配方、山脉、水文、侵蚀、海床或气候 | [地形与环境](systems/terrain.md) |
| 到达成本、群系需求、容量、布局、结构落位或填充 | [规划系统](systems/planning.md) |
| 道路路网、缓弯、短桥与区块铺设 | [道路系统](systems/roads.md) |
| 服务器生命周期、计划查询、Minecraft 区块与原生结构边界 | [运行时与 worldgen](systems/runtime-worldgen.md) |
| 作者 JSON、缺省行为与验证 | [Profile 参考](reference/profile.md) |
| 冻结格式、身份或 READY 恢复 | [冻结计划 参考](reference/plan-v2.md) |
| 数据包覆盖、第三方群系与结构规划信息 | [适配器参考](reference/adapters.md) |
| 派生算法、上游归属和许可记录 | [FTF 来源说明](reference/ftf-provenance.md) |

## 验证与排障

- [测试指南](development/testing.md)：JUnit、GameTest、READY、基准、预览和文档检查命令。
- [故障定位](development/debugging.md)：错误类别、日志、诊断字段与排查顺序。

## 阅读约定

代码与测试是当前事实的依据；注释与文档冲突时检查实际调用和断言。
核实事实与修改前检查要求见 [AGENTS.md](../AGENTS.md#开始工作)。
主题文档解释语义，不抄录全部默认值、版本字面量、源码行号或测试数量。
有关来源和演化的问题通过 Git 查询；当前目录不维护实施时间线。
