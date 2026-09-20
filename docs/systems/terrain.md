# 地形与环境系统

这里回答海岸、区域配方、山脉、水文、侵蚀、海床与气候分别产生什么，以及修改后应验证什么。

## 地形管线

生产组合顺序由 [PlanTerrain](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/runtime/PlanTerrain.java) 维护：

```text
RegionTerrain → IslandMacroTerrain → ErodedTerrain
              → HydrologyTerrain → TerrainMorphology
```

区域与岛屿基础建立后才运行侵蚀模拟，侵蚀后地形是水文输入。
`TerrainMorphology` 测量最终高度的坡度、起伏和相对高度，不再平滑真实高度。
首次规划可带 `ExactGridTerrain` 缓存，READY 组装使用相同公式。
各层不得决定方块材料，材料边界见 [worldgen](runtime-worldgen.md#surface)。

<a id="coast"></a>
## 海岸

[CoastGenerator](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/terrain/CoastGenerator.java)
从扰动 Voronoi 边缘场提取包含原点的闭合大陆分量。
`FractalCoastWarp` 通过旋转剪切复合增加局部湾岸细节，整体等比缩放到作者半径。
半径控制大陆尺度，不以圆截断轮廓；支持凹湾，不要求每条原点射线只穿越一次海岸。

轮廓按逐级分辨率比较、简化并检查顶点预算和中心预留，冻结为 `Coastline`。
陆海判断、带符号距离和等弧长样本均消费这条折线；正距离为陆侧。
数值收敛比较不是解析误差证明。
预算、误差容限和岸带尺度来自 `PlannerProfile.Coast`。

验证：`CoastGeneratorTest`、`CoastlineSearchTest`、`ContinuousDomainWarpTest`，
配合 `CoastDetailPreview` 查看全岛及局部细节。

<a id="regions"></a>
## 区域与配方

[RegionTerrain](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/terrain/RegionTerrain.java)
以世界对齐的抖动 Voronoi 区域和连续坐标扰动构造高度。
区域高度跨边界混合；窄边缘的配方标签选择与宽范围高度混合是不同步骤。
`EcotoneSelector` 只在真实参与的邻近标签中选择，不凭空创造新配方。

[TerrainRecipes](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/terrain/TerrainRecipes.java)
提供具体函数图，`TerrainTemplate` 将其归入规划使用的粗分类。
当前配方包含平地、谷丘、阶地、恶地、山地与休眠火山；完整标识与参数范围见 [profile](../reference/profile.md#terrain)。
函数输出使用方块高度振幅，不复制上游归一化高度单位；火山不生成岩浆管。

容量求解产生 `TerrainCapacityPlan`，区域采样器只消费它。
预留可选择配方、基底和振幅以满足高度包络，不在生成完成后按群系高度阈值逐点截平。
主副配方分别保留尺度、振幅与细节参数；副配方实际参与时，群系须同时允许两者。
是否允许复合通过 `FillerTerrainPolicy` 注入，不让 terrain 反向读取作者模型。

区域清单、设置与容量在快照中冻结；恢复重新推导区域并核对清单。
验证：`RegionTerrainTest`、`RegionTerrainCacheTest`、`TerrainRecipesTest`、
`TerrainFragmentationTest`、`PlanningOptimizationGoldenTest`。

<a id="mountains"></a>
## 山脉

[MountainRangePlan](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/terrain/MountainRangePlan.java)
在容量预留时创建蜿蜒走廊，冻结脊线和宽度。
`RegionTerrain` 依据走廊选择区域并应用连续山脉影响包络；容量预留区域有自己的高度承诺。
山脉不能在区块生成时重新抽取，也不能单凭“标签属于 mountains”推断实际峰顶。

验证区域和配方测试，并用 `TerrainRecipeAudit`、`TerrainRecipePreview` 查看连续性和分布。
改变 mountain 参数还需检查容量供给及温度降温。

<a id="erosion"></a>
## 侵蚀

[ErosionGenerator](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/erosion/ErosionGenerator.java)
接收岛屿基础高度，在世界对齐网格运行确定性水滴模拟，产出 `ErosionDeltaField`。
固定色批与批次屏障约束并行写入；位移、坡度和泥沙量显式转换到方块单位。

`ErodedTerrain` 查询冻结增量并对完整高度执行方块尺度滤波。
不能把方块半径误解为保存网格的格子半径；运行时不再模拟水滴。
海岸附近的衰减维持海陆基准，水文随后切削。
验证 `ErosionGeneratorTest`、`TerrainRegressionTest` 和 `PlanTerrainTest`。

<a id="hydrology"></a>
## 水文

[HydrologyGenerator](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/hydrology/HydrologyGenerator.java)
根据海岸、侵蚀后高度、种子与 `HydrologyProfile.FINITE_CONTINENT` 选择集水起点、出口和支流。
`RiverPath` 负责地形引导路径；冻结的 `RiverNetwork` 保存主支流关系、折线、累计长度、水位、剖面、湖状展宽和湿地。

水位沿下游不升高，候选检查交叉和邻近河谷冲突；河口与海面衔接。
`RiverMorphology` 根据冻结网络推导连续宽深及不规则河岸。
`HydrologyTerrain` 合并切削与水面，按空间索引查询附近河段、湖和湿地。
封岸整数化由 `solidSurfaceAt` 处理；较大水位或河网缺陷应在几何层解决，不能靠随意补块掩盖。

生产参数和上游参考参数分开存放在
[HydrologyProfile](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/hydrology/HydrologyProfile.java)。
`LakeReference` 只记录来源，不能当作活动旋钮；平滑参数仅接受支持的组合。
验证 `HydrologyGeneratorTest`、`RiverMorphologyTest`、`HydrologyAudit`，
实际漏水、河口和流体更新还需 GameTest。

<a id="ocean"></a>
## 海岸高度与海底

`IslandMacroTerrain` 将陆侧区域高度平滑接到海面。
[OceanBathymetry](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/terrain/OceanBathymetry.java)
按离岸距离、岸带尺度和连续噪声构造浅海台阶、陆坡与深海盆地。
海床是计划与实际列共享的高度源；原版海洋噪声只提供封闭海床下的洞穴等空腔。

水面是物理上表面；Minecraft 顶层水方块在该表面以下。
量化和海底空腔的实际规则见 [区块执行](runtime-worldgen.md#chunk)。
验证 `OceanBathymetryTest`、`TerrainRegressionTest` 和海洋 GameTest；预览用 `OceanShelfPreview`。

<a id="climate"></a>
## 气候交互

[ClimatePlan](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/climate/ClimatePlan.java)
先采样最终地形并形成局部与区域高度场，再叠加
`OrganicTemperatureField` 的二维自然温度和多尺度高度降温。
新计划使用固定的四档温度阈值；需求统计由 `ClimateStatistics` 注入，生产实现为 `ClimateDiagnostics`。
统计不调整温度场、准入或搜索，改变需求不意味着温度会自动让出面积。

[HumidityPlan](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/climate/HumidityPlan.java)
准备水体距离状态；当前自然湿度公式使用天气、温度、海拔与海洋距离，不直接用淡水距离增强河岸湿度。
干地上的 `HumiditySupplyCorrection` 会把不可用湿度移向当前 filler 能接受的最近档位。
其供给掩膜按分类、主副配方和地貌部位缓存，排除硬高度限制与 shore-only 候选。
更换 filler 可能改变湿度，也可能改变允许复合的配方；不能把它理解为只换最终标签。
供给修正不修改群系准入集合，也不保证每个位置最终都有合法 filler。

`BiomeEnvironmentRules` 读取这些场决定气候准入和评分，并给合法的海岸群系局部优先权。
温度分类统一通过 `ClimateField.band` / `ClimatePlan.typeAt`，不能另按原版降雪条件重建分类。
冻结状态保存高度场、分类参数、统计和湿度距离数据；READY 不重新采集和竞争分配。

验证 `OrganicTemperatureFieldTest`、`StrictClimateTest`、`ClimateDiagnosticsTest`、
`HumidityPlanTest`、`HumiditySupplyCorrectionTest`、`BiomeEnvironmentRulesTest`。

<a id="query-limits"></a>
## 最终查询与当前限制

`MacroSample` 区分地面、水面、通行表面、危险性、区域分类、配方和地貌测量。
规划与区块采样必须保持坐标语义；任意连续坐标不应被粗网格插值替代。
`GeneratedAdventurePlan.terrainAt` 为精确方块中心查询设置缓存，其他位置交给连续函数。

气候缓存目前存在需要特别审查的实现边界：
`ClimatePlan.raw` 和 `HumidityPlan.valueAt` 的缓存计算闭包仍使用调用者传入的 sample，
而 `FrozenQuartField` 仅按坐标索引；同一坐标首次传入不同高度可能形成不同缓存结果。
其 `cacheable` 将坐标转为整数后判断中心，未先排除全部非整数坐标。
[ClimatePlanQuartCacheTest](../../neoforge/src/test/java/io/github/luoyan/adventureworldgen/climate/ClimatePlanQuartCacheTest.java)
主要复读同一缓存或使用相同首次顺序，不能据此证明任意 sample 和访问顺序都等价。
调用者应传入与查询位置一致的最终地形样本；修改这一契约需补反例验证并检查计划输出变化。
