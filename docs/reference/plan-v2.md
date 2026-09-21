# 冻结计划 v5 与 READY

这里定义当前 `plan-v5` 的逻辑内容、身份、发布和恢复边界。
文件路径和 Java 类型 `PlanV2Codec` 暂时保留历史名称，但 codec 只接受活动格式版本；它们不是 v2 兼容承诺。
格式是项目内部持久化协议，不是第三方稳定交换协议。

## 为什么冻结

首次规划决定大陆、侵蚀、水文、气候、群系归属、结构宏观锚点、出生和道路施工几何。
保存这些决策，使重启与区块访问恢复同一查询对象，而不按访问顺序重新求解。
结构锚点是执行层的输入，不是 Minecraft 对象或已经完成的结构几何。

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
| 道路节点、中心线、施工列、矩形结构避让范围和失败诊断 | 恢复道路几何与索引，不重新寻路 |
| 出生坐标 | 生命周期使用同一出生结果 |

结构条目不含 Y、旋转、入口、范围、piece 类型或 NBT；执行层结合活动生成定义构造起点。
已构造的 pieces 与地形适配元数据随 Minecraft 区块保存，不进入本格式。
codec 使用规范化 JSON、UTF-8 和 gzip。随机 domain 的真实清单由源码与测试维护，不重复写入 payload。

<a id="identity"></a>
## 计划身份

[PlanIdentity.hash](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/runtime/PlanIdentity.java)
对固定顺序的规范化配置、世界 seed、算法/水文版本、实现修订、地形版本、排序后的 biome adapter 版本键，
以及显式成本/侵蚀标记、结构预检输入身份计算 SHA-256。profile ID 还会在 manifest 与 payload 中独立检查。

`PlannerProfile.V2` 是保留的 Java 常量名，其活动 `planFormatVersion` 由该常量统一提供。
不要在文档或工具中另维护“当前格式”字符串。
`StructurePlanningInfo` 的结构 ID、显式禁入范围、接入 margin、局部连接长度、排序后的入口与朝向，
以及资源导出的 Template 几何/影响范围和旋转选项，通过结构规划目录的规范表示进入摘要。
生产结构预检输入身份包含活动执行资源摘要；它在查询 READY 前确定，动态实例范围是该输入下的派生结果。
纯算法工具使用显式的 declared 模式，与生产原生实例计划不共用身份。
资源导出发生在查询 READY 前；局部段与宏观道路共用冻结中心线和施工列，恢复不重新选择入口。
作者全局道路字段与必须群系/结构条目的 `road` 均由 canonical 配置覆盖；新增任何影响输出的字段仍须同步审查摘要、算法版本、格式与 READY。

<a id="compatibility"></a>
## 兼容性和失效条件

| 修改 | 当前处理 |
|---|---|
| canonical 配置、seed、已纳入 hash 的版本或 biome adapter version | 输入摘要变化，旧目录不命中 |
| plan 格式版本 | manifest 与 codec 严格检查 |
| 地形设置、区域规则 | 恢复时核对设置与区域清单；算法变化仍须提升身份版本 |
| 预算、网格或其他 profile 参数 | 不会整体自动入 hash；改变输出时必须审查版本身份 |

当前格式将道路结构避让声明改为世界坐标矩形，旧 `plan-v4` 及更早 READY 不兼容。
结构 pieces 继续保存在原生区块中；新格式不迁移已有结构或道路。
群系需求合并规则改变布局与容量承诺，通过 `PlanIdentity.IMPLEMENTATION_REVISION` 纳入输入身份。
道路目的地改由必须群系和结构条目声明，通过规范配置与实现修订使旧 READY 失效；冻结道路数据结构不变。
共享需求成员和承载关联是规划期数据；READY 直接恢复已冻结地块与锚点，无需更改 payload 格式。
新规划不会迁移已经生成的 Minecraft 区块，READY 失效也不是世界迁移机制。
结构执行另外使用世界级资源身份保护半生成结构；其字段、范围及拒绝条件统一见 [结构执行](../systems/runtime-worldgen.md#structures)。

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

所有平台都要求文件强制落盘与原子目录移动，失败时中止发布，不降级为逐文件复制。
Windows 不支持通过 Java `FileChannel` 打开目录，因此仅检查目录存在且类型正确，跳过目录强制同步；
其他平台在移动前同步临时目录、移动后同步父目录。Windows 下不承诺突然断电后目录改名仍被保留，
下次启动仍按 READY 与摘要检查决定缓存是否可用。这一平台适配不改变计划内容、输入身份或冻结格式。

<a id="reload"></a>
## READY 恢复与失败分界

包级缺失、损坏或身份不匹配返回缓存未命中；codec 语义错误或快照恢复错误会中止，
不能假设所有语义失败都会自动重新规划。
`GeneratedAdventurePlan.restore` 不重跑海岸、水文、侵蚀、成本、群系竞争或结构锚点落位，
也不重跑道路寻路；会重建确定性函数、索引与缓存，并核对区域清单。

## 修改与验证

格式修改必须同时检查 snapshot、codec、manifest、identity、版本与 READY。
最低验证入口是 `PlanV2CodecTest`、`AtomicPlanRepositoryTest`、`PlanIdentityTest`、
`PlanningBaselineTest` 和 `DeterminismAcceptanceTest`；游戏生成边界另见 [测试指南](../development/testing.md)。
