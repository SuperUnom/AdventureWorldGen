# 冻结计划 v3 与 READY

这里定义当前 `plan-v3` 的逻辑内容、身份、发布和恢复边界。
文件路径和 Java 类型 `PlanV2Codec` 暂时保留历史名称，但 codec 只接受活动格式版本；它们不是 v2 兼容承诺。
格式是项目内部持久化协议，不是第三方稳定交换协议。

## 为什么冻结

首次规划决定大陆、侵蚀、水文、气候、群系归属、结构宏观锚点和出生。
保存这些决策，使重启与区块访问恢复同一查询对象，而不按访问顺序重新求解。
结构锚点只是一项规划结果，不是待恢复或待执行的 Minecraft 结构。

## 逻辑内容

[PlanSnapshot](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/persistence/PlanSnapshot.java)
保存领域快照；codec 另附 profile、版本和输入摘要：

| 数据 | 用途 |
|---|---|
| seed、profile、版本、输入摘要与诊断计数 | 校验身份和定位规划行为 |
| 海岸、海面、岸带和河网 | 固定海陆与水体几何 |
| 地形设置、区域清单、容量与侵蚀增量 | 重建连续地形而不重跑侵蚀模拟 |
| 群系 patch、气候、filler 与保护掩膜 | 恢复群系归属和过渡 |
| `instance_id`、`structure_id`、`anchor_x`、`anchor_z` | 恢复结构宏观规划元数据 |
| 出生坐标 | 生命周期使用同一出生结果 |

结构条目不含 Y、旋转、入口、范围、piece 类型或 NBT，也不能据此创建 Minecraft 结构起点。
codec 使用规范化 JSON、UTF-8 和 gzip；`random_keys` 是说明元数据，不是随机完整性证明。

<a id="identity"></a>
## 计划身份

[PlanIdentity.hash](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/runtime/PlanIdentity.java)
对固定顺序的规范化配置、世界 seed、算法/水文版本、实现修订、地形版本、排序后的 biome adapter 版本键，
以及显式成本/侵蚀标记计算 SHA-256。profile ID 还会在 manifest 与 payload 中独立检查。

`PlannerProfile.V2` 是保留的 Java 常量名，其活动 `planFormatVersion` 为 `plan-v3`。
不要在文档或工具中另维护“当前格式”字符串。

<a id="compatibility"></a>
## 兼容性和失效条件

| 修改 | 当前处理 |
|---|---|
| canonical 配置、seed、已纳入 hash 的版本或 biome adapter version | 输入摘要变化，旧目录不命中 |
| plan 格式版本 | manifest 与 codec 严格检查 |
| 地形设置、区域规则 | 恢复时核对设置与区域清单；算法变化仍须提升身份版本 |
| 预算、网格或其他 profile 参数 | 不会整体自动入 hash；改变输出时必须审查版本身份 |

v3 删除了结构执行数据，因此旧 `plan-v2` READY 明确不兼容并会被忽略/拒绝；系统不会尝试迁移其 pieces。
新规划不会迁移已经生成的 Minecraft 区块，READY 失效也不是世界迁移机制。

<a id="repository"></a>
## 目录与原子发布

```text
<world>/adventureworldgen/plans/<normalized-profile>/
  plan.json.gz
  manifest.json
  READY
```

manifest 记录格式、profile、输入 SHA-256 和压缩 payload SHA-256。
发布在同一父目录写临时包，先落盘 payload/manifest，最后写 READY，再以原子目录移动发布。
缺少 READY、摘要不符、gzip 损坏或格式不匹配均不能作为有效缓存使用。

<a id="reload"></a>
## READY 恢复与失败分界

包级缺失、损坏或身份不匹配返回缓存未命中；codec 语义错误或快照恢复错误会中止，
不能假设所有语义失败都会自动重新规划。
`GeneratedAdventurePlan.restore` 不重跑海岸、水文、侵蚀、成本、群系竞争或结构锚点落位，
但会重建确定性函数、索引与缓存，并核对区域清单。

## 修改与验证

格式修改必须同时检查 snapshot、codec、manifest、identity、版本与 READY。
最低验证入口是 `PlanV2CodecTest`、`AtomicPlanRepositoryTest`、`PlanIdentityTest`、
`PlanningBaselineTest` 和 `DeterminismAcceptanceTest`；游戏生成边界另见 [测试指南](../development/testing.md)。
