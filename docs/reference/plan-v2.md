# 冻结计划与 READY

这里定义冻结计划的逻辑内容、身份、发布和恢复边界。
plan-v2 是项目内部持久化格式，不是第三方可自行写入的稳定公开交换协议。
编码事实来源是 [PlanV2Codec](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/persistence/PlanV2Codec.java)，
目录事务来源是 [AtomicPlanRepository](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/persistence/AtomicPlanRepository.java)。

## 为什么冻结

首次规划决定大陆、侵蚀、水文、气候、群系归属、结构和出生。
保存这些决策，使重启和区块访问只恢复确定性查询对象，不按访问顺序重新求解。
`GeneratedAdventurePlan` 持有运行时视图，`PlanAssembly` 负责组装，`PlanSnapshot` 表达纯数据快照；
三者交接见 [发布流程](../architecture/pipeline.md#publish)。

## 逻辑内容

[PlanSnapshot](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/persistence/PlanSnapshot.java)
保存领域快照；codec 另附 profile、版本和输入摘要元数据。完整 payload 的逻辑内容如下，具体字段类型由源码定义：

| 数据 | 用途 |
|---|---|
| seed、profile、版本、输入摘要与诊断计数 | 校验身份和定位规划行为 |
| 海岸、海面和岸带、河网 | 固定海陆与水体几何 |
| 地形版本、配方设置、区域清单、容量结果 | 重建确定性区域及连续地形 |
| 侵蚀增量场 | 查询保存的侵蚀结果，不重新运行水滴模拟 |
| 群系 patch、气候状态、filler 状态、保护掩膜 | 恢复群系归属、环境判断与过渡 |
| 结构 ID、原点、旋转、入口、范围及 pieces NBT | 恢复完整布局与区块起点 |
| 出生坐标 | 生命周期使用相同出生结果 |

快照不是每个方块或每次查询的枚举。
气候保存粗高度场、分类参数和湿度状态，细粒度查询缓存按需建立；
恢复会重建函数、索引和区域对象，并校验区域清单一致性。

codec 采用规范化 JSON、UTF-8 和 gzip；掩膜与 NBT 的内部编码不应成为适配器依赖。
`random_keys` 是随机域说明元数据，目前 decoder 不校验它。
随机域实际定义见 [RandomDomains](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/plan/RandomDomains.java)。

<a id="identity"></a>
## 计划身份

[PlanIdentity.hash](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/runtime/PlanIdentity.java)
对固定顺序拼接的以下输入计算 SHA-256：

- 规范化作者配置。
- 世界 seed。
- 传入 `PlannerProfile` 的 algorithmVersion 和 hydrologyVersion。
- `PlanIdentity.IMPLEMENTATION_REVISION`。
- `PlanVersions.TERRAIN`。
- registry 排序后的全部适配器版本键，包括 generic biome adapter。
- 函数内显式列出的成本与侵蚀算法标记。

profile ID 另在包和 payload 中检查；资源包显示名称不参与身份。
`PlanVersions` 是版本标识的主要来源，`PlannerProfile.V2` 组合活动算法和格式版本。
不要在文档、工具或测试辅助代码重新维护一套“当前版本”字符串。

<a id="compatibility"></a>
## 兼容性和失效条件

| 修改 | 当前检测方式/必须作出的决定 |
|---|---|
| canonical 配置、seed、已列入 hash 的版本或 adapter version | 输入摘要改变，旧目录不命中 |
| plan 格式版本 | manifest 和 codec 检查；不等于自动修改 input_sha256 |
| 地形设置、区域生成规则 | 恢复检查设置和区域清单；算法变化仍需明确提升参与身份的版本 |
| 预算、网格或其他 PlannerProfile 参数 | 不会整体自动进入 hash；改变输出时必须同步更新版本身份 |
| 侵蚀、随机域、piece loader、注册资源、表层或地物实现 | 不保证自动失效；逐项核对 hash、codec 和 Minecraft 已有区块兼容性 |

当前 `PlanIdentity` 没有直接读取 `PlanVersions.EROSION` 或地形随机域标识，
而是包含函数内的侵蚀字面标记；仅修改一个看似相关的版本常量可能不足以失效缓存。
部分恢复与预算路径固定使用 `PlannerProfile.V2`；
不能把可注入 profile 理解为端到端的任意格式兼容支持。

更换配置后，新会话可能重新规划并替换该 profile 的计划目录。
这不迁移已经生成的 Minecraft 区块，不能用 READY 失效代替旧世界迁移方案。
会话内 reload 的边界见 [运行时生命周期](../systems/runtime-worldgen.md#lifecycle)。

<a id="repository"></a>
## 目录与原子发布

运行时生成路径如下；`<world>` 是服务器实际世界目录，不是仓库中的文件：

```text
<world>/adventureworldgen/plans/<normalized-profile>/
  plan.json.gz
  manifest.json
  READY
```

profile 中冒号和斜杠替换为下划线；默认目录名为 `adventureworldgen_default`。
manifest 固定记录格式、profile、输入 SHA-256 与压缩 payload 的 SHA-256。

发布步骤是：

1. 已存在同身份且完整的包时，比较规范化 payload；相同则复用，不同则报错。
2. 不可复用的目标目录先删除；创建同父目录的临时目录。
3. 写入并强制落盘 payload 和 manifest，最后写 READY，强制临时目录。
4. 使用目录原子移动发布，再强制父目录；异常清理未发布临时目录。

文件系统必须支持原子移动，当前没有降级为普通移动的路径。
READY 防止把半写包当作有效结果，但替换前会删除旧目标目录，
不保证发布失败后仍保有上一份不同身份的包。

<a id="reload"></a>
## READY 恢复与失败分界

`loadReady` 检查 READY、manifest、预期身份、压缩字节摘要与 gzip 可读性。
缺失、包级损坏或不匹配返回缓存未命中，由首次规划路径重新创建。
只有 READY 文件存在不代表整个包可用。

包检查通过后，codec 再验证内容、字段、活动版本、profile 和输入摘要。
语义解码、快照恢复或 frozen piece 支持检查失败会中止，
不承诺捕获所有错误后自动重规划。
因此“包被忽略”和“READY 语义恢复失败”必须分别诊断。

`GeneratedAdventurePlan.restore` 不重跑海岸、水文、侵蚀、成本求解或群系竞争。
按冻结设置重建区域地形并比对清单，不意味着再次分配区域。
必须以 fresh / READY 的输出比较验证，而不能仅比较加载耗时。

## 修改与验证

格式修改要同时检查 snapshot、codec、manifest、identity、版本和 READY。
最低验证入口是 `PlanV2CodecTest`、`AtomicPlanRepositoryTest`、`PlanIdentityTest`、
`DeterminismAcceptanceTest` 和实际结构恢复测试。
命令及普通世界 cold/ready 对照见 [测试指南](../development/testing.md#ready)。
