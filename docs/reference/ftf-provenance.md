# FreeTerraForged 来源与差异

这里记录本仓库采用的地形算法来源、改造范围和对应实现，不提供另一份生成器操作手册。
许可和归属事实以随 JAR 发布的 [META-INF/NOTICE](../../neoforge/src/main/resources/META-INF/NOTICE) 为准。

## 上游与许可

参考上游为 [ETcodehome/FreeTerraForged](https://github.com/ETcodehome/FreeTerraForged)，
固定参考提交为
[43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a](https://github.com/ETcodehome/FreeTerraForged/tree/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a)。
NOTICE 包含 FreeTerraForged / ReTerraForged 的 MIT 许可全文及 ReTerraForged 版权声明。
本项目自身的发行声明与上游 MIT 归属分别保留，不能把其中一个推广为所有代码的许可。

## 采用与改造

| 来源/思想 | 当前实现 | 本项目的适配 |
|---|---|---|
| Populators / VolcanoPopulator 的十二种配方图 | [TerrainRecipes](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/terrain/TerrainRecipes.java) | 由区域与作者配方参数选择；使用方块单位幅度，连续台阶过渡；休眠火山不生成熔岩管 |
| Perlin 梯度散列、CURVE3 插值，Ridge/Billow、Cubic、Worley、Steps 与嵌套域扭曲 | [TerraForgedNoise](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/terrain/TerraForgedNoise.java) | 确定性图种子和本地采样组合，不依赖上游运行时图对象 |
| 水滴侵蚀及高度滤波 | [ErosionGenerator](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/erosion/ErosionGenerator.java)、[ErodedTerrain](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/erosion/ErodedTerrain.java) | 位移和泥沙转换为方块单位；持久化增量场，查询期只采样冻结结果 |
| 自然河网和河形参考 | [HydrologyProfile](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/hydrology/HydrologyProfile.java)、[RiverMorphology](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/hydrology/RiverMorphology.java) | 活动有限大陆参数与来源参数分开；出口匹配本项目岸线，冻结网络后连续查询 |

海岸、区域容量、冒险成本、气候供给、群系竞争、结构承载与 READY 由本项目系统组合。
具体职责和冻结时机见 [地形系统](../systems/terrain.md)，不要以一个上游类名推断当前完整调用链。

## 没有整体移植的部分

本仓库没有把上游完整世界生成器、配置界面、预设系统或 tile/cache 生命周期作为运行时依赖。
群系归属由本项目 planner 决定，表面材料与地物使用当前 Minecraft 接入；
上游完整的群系、材料和装饰体系不是本项目的直接执行管线。
这些配方和算法的采用不意味着同种子能逐块复现上游世界。

需要追溯算法出处时，结合固定上游提交、对应源码注释和本仓库 Git：
`git log -- <path>`、`git blame <path>`、`git show <commit>:<path>`。
修改派生实现时同步审查 NOTICE；不要再复制一份大规模源码索引到文档。
