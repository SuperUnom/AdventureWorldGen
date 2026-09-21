# 道路系统

道路在群系、结构宏观锚点和出生确定后规划，READY 发布前完成施工几何验证。
`RoadPlanner` 不改变群系归属、结构位置或用于冒险等级的自然地形到达成本。

## 目的地与连接

profile 在必须群系和结构条目下选择道路连接，字段见 [Profile 道路配置](../reference/profile.md#roads)。
出生自动作为路网根节点；群系节点从该 ID 已有 patch 中选择靠近出生的合法位置。
不再接受自定义方块坐标目的地，也不在 filler 中补选缺失的目的地。
结构节点只连接外围接入点，不代表门口或内部通道。

条目的 `road.required` 为真且道路启用时，目的地缺失或不能接通会阻止 READY 发布。可选目的地可以缺席，原因保存在
`RoadPlan.skipped` 并输出日志；无法连回出生的独立道路分量不会发布。
重合目的地共享已有节点，并记录重合诊断。

先评估每个节点最近的少量连接，在找到的候选图中按建设成本稳定排序，使用 Kruskal 选择骨架，
再有界补充不同连通分量间的候选。可选环路受额外长度比例约束，并要求显著减少已有路网绕行。
这不是连续地形上的全局最优路网证明；后续施工冲突也可能拒绝候选边。

## 平面形状与高程

`RoadSearch` 使用包含来向的 A*，惩罚急转弯；先粗后细，并有界扩大搜索范围。
沿路采样高度、横向宽度和水体，禁止海洋、岩浆、危险地形和结构保留区域。
长度、高差、转弯和过河成本参与候选比较，不直接复用自然地形行走成本的通行规则。

`RoadShape` 在合法干地走廊内简化折点、圆滑转角，并对较长直段加入长跨度、小幅度缓弯。
偏移使用世界 seed 和稳定端点身份，端部偏移与切向偏折收敛到零；桥段保留直线。
`maximum_bend_detour` 限制装饰性缓弯相对对应直段的增量，不限制绕山寻路的总绕行。
曲线不能通过全宽验证时退回可行路线，不能为了弯曲放宽地形限制。

`RoadConstruction` 把中心线变成完整道路列，陆路另有窄路肩，桥梁保持核心通行宽度。
高程求解同时约束自然地面附近的挖填范围、纵横坡度、出生高度与已有路口高程。
最终整数路面检查四邻接连通和相邻高度差，保存路面、支撑底部和净空顶部。
原始地形查询不受道路改变，施工覆盖由冻结列提供；道路地基不反馈到原始成本图。

河流只有在跨度、两岸接坡和施工范围允许时才能形成短桥。桥面和梁体位于水面之上，
不把桥下水体填成路堤。湖泊、湿地、海洋、隧道和大型高架不在当前施工范围内。
同高路口通过共享道路列汇合；当前不提供立交，也不保证每个路口都呈现特定的 Y/T 造型。

## 结构接入契约

参与接入的结构需要纯 `StructurePlanningInfo.RoadAccess`，资源加载层从
`data/<namespace>/adventureworldgen/structure_planning/<path>.json` 读取，ID 对应结构 ID：

```json
{
  "road_access": {
    "exclusion_radius": 32,
    "approach_distance": 64
  }
}
```

`exclusion_radius` 声明相对规划锚点的保守水平正方形范围，包含实际起点包围盒和地基影响。
`approach_distance` 给出外围节点搜索距离；节点仍须通过真实地形与道路全宽检查。
字段的有效范围由 `StructurePlanningInfo.RoadAccess` 校验。
资源不包含 pieces、NBT 或入口推断；缺少声明的结构不承诺接入。

执行桥接层检查实际结构是否处于声明范围内，以及是否与冻结道路冲突。必需规划结构违反声明
或发生冲突会明确失败，不能静默移动或覆盖道路。未接管的原生随机结构会拒绝与道路相交的候选。
没有道路声明的规划结构仍执行道路冲突检查，因此不能据此声称任意第三方结构都兼容。
规划元信息规范值进入输入身份，不能只提升版本常量替代输入摘要。

## 区块执行与存档

`AdventurePlanView` 提供道路点查询和区块范围查询；`RoadIndex` 从冻结列重建空间索引。
区块列与基础高度/基础列查询消费同一覆盖，桥下保留原始空腔和水。
原生 surface rules 完成后，`RoadWorldgen` 恢复路面、支撑、台阶和通行净空。
陆路路肩使用砂土，陆路/桥梁台阶分别使用圆石/橡木台阶。
普通生成材料配置见 [Profile 的道路字段](../reference/profile.md#roads)。

carver 在道路区块及邻近区块避让。`RoadDecorationMixin` 在 `WorldGenRegion.setBlock` 按写入目标
检查冻结保护范围，因此也能阻止相邻区块伸来的树干、树叶或地物堵路。
绕过该写入入口的第三方地物不在这个保护承诺内。
路面只在区块生成阶段执行，不在普通加载时重铺；玩家建造和拆除不受该生成期保护限制。

长距离道路不包装成一个原生结构起点，不借助区块加载事件放置，不建立第二份已生成区块清单。
全局计划保存完整施工列和路网元数据，区块保存已生成方块。恢复只重建索引，不重新寻路。
启用道路会启用世界级生成输入保护，沿用现有生成身份文件，详见
[执行身份](runtime-worldgen.md#structures) 与 [冻结格式](../reference/plan-v2.md)。已有世界不迁移道路。

## 预算和失败

节点数、冻结列数和地形采样次数都有预算；它们的作者设置进入规范输入。
每条候选连接另有固定搜索状态、扩展次数和采样上限；全局预留部分采样预算给最终施工。
缓存命中也计入相同采样次数，缓存不能改变何时结束搜索。调整固定搜索常量须更新实现身份。

可选连接耗尽自身预算会记录诊断；全局资源耗尽仍会明确失败，不发布未经验证的结果。
搜索域不足、采样预算耗尽、非法目的地、结构范围冲突及坡度/挖填失败分别诊断，
不能把任一有界失败解释成全大陆无解。

## 验证与预览

`RoadPlannerTest` 检查缓弯、绕行、缓坡接入、短桥、结构范围、稳定顺序和预算；
`RoadConfigTest`、`RoadPersistenceTest` 检查输入身份、严格字段与非空路网 READY 往返。
roads GameTest 检查实际区块顺序、基础列一致性、邻区块写入保护、桥下水体与玩家修改保留。

`RoadPreview` 使用同一个生产 planner 渲染合成地形示例和纵坡，也可读取真实冻结计划：

```bash
./gradlew -I tools/planning-benchmark.gradle writePlanningBenchmarkClasspath
AWG_TOOL_JAVA_OPTS=-Djava.awt.headless=true ./tools/run-audit-tool.sh RoadPreview build/reports/roads/road-shapes.png
AWG_TOOL_JAVA_OPTS=-Djava.awt.headless=true ./tools/run-audit-tool.sh RoadPreview <profile.json> <plan-dir> <output.png>
```

`<plan-dir>` 是包含 manifest 与压缩 payload 的叶目录；配置须与计划匹配。
预览显示冻结施工列，不是 Minecraft 客户端截图，也不等于对第三方地物或玩家实际行走的全面验收。
分组测试命令见 [测试指南](../development/testing.md#gametest)。
