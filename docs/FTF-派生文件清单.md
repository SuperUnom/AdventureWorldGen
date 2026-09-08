# FreeTerraForged 派生文件清单

固定上游提交：`43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a`  
上游仓库：<https://github.com/ETcodehome/FreeTerraForged>  
上游许可：MIT；完整文本随 JAR 放在 `META-INF/NOTICE`。

本项目采用适配式移植，版本名为 `ftf-hydrology-adapted-v1`。不会声称输出与上游逐点一致。

| 本项目文件 | 上游参考文件 | 状态与适配边界 |
|---|---|---|
| `terrain/CoastGenerator.java`、`terrain/Coastline.java` | `continent/uplift/UpliftContinentGenerator.java` | r9：保留二维扰动 Voronoi 宏观场，使用项目自有 `FractalCoastWarp` 细化至 8 方块尺度；单大陆等值线提取、缩放和一般多边形查询为项目适配 |
| `terrain/IslandMacroTerrain.java` | `cell/terrain/populator/OceanPopulator.java` | 仅作为规划期海床近似；r6 实际外海改用 Minecraft 原版密度、含水层与表层规则 |
| `terrain/MountainTerrain.java`、`terrain/GradientNoise.java` | `cell/terrain/Populators.java:makeMountains`、`noise/module/PerlinRidge.java` | r6 采用四层反馈山脊、2.35 倍频、1.15 增益和两轴扰动；项目自有梯度哈希，不逐点等价 |
| `hydrology/HydrologyProfile.java` | `RiverSettings.java`、`Presets.java` | 固定上游参数保留用于校验；r7 生产使用有限大陆参数，缩小并减少湖湿地 |
| `hydrology/HydrologyGenerator.java`、`HydrologyTerrain.java`、`RiverNetwork.java` | `cell/rivermap/**` | r6 适配随机方向、源头距离、分散集水起点、随机支流间隔、相交约束、按河长缩放的 RiverWarp、连续河床/岸坡、湖与湿地；r6 约束沿途两岸水位并延伸河口切削；采用 Wetland 的浅池/土丘混合，替换大陆场、缓存和随机源 |
| `erosion/ErosionGenerator.java`、`ErosionDeltaField.java`、`ErodedTerrain.java` | `densityfunction/tile/filter/Erosion.java`、`WorldErosion.java` | 已实现规划期世界对齐确定性增量场、固定色批并行和平滑；运行时仅查询持久化结果 |

每次新增或修改派生文件时必须同步更新本表，并增加对应的固定向量或适配约束测试。

r7 对 `HydrologyGenerator` 和 `HydrologyTerrain` 增加最近河段查询、相邻河谷水位冲突检查、平缓蓄水盆地筛选、水位与挖掘独立合并，以及岸边方块封闭处理。这些是项目自身的修正，验证方法见 [r7 修复说明](河岸与水墙修复-r7.md)。

r8 为 `CoastGenerator` 增加项目自有 `ContinuousDomainWarp`，通过可逆小位移复合细化海岸，并将局部细节纳入轮廓精度比较。`ErosionGenerator` 仅增加观察进度的回调，不改变侵蚀计算与随机序列。详见 [r8 说明](进度与群系过渡-r8.md)。

r9 以自有旋转剪切分形细化替换 r8 的海岸细节位移。新增嵌套尺度周长增长测试、局部方向保持测试和真实种子三级放大图；不修改上游引用范围。
