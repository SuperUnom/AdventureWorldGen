# 审计与预览程序（tools/）

[工程说明](../README.md) · [实施与验收指南](../../docs/实施与验收指南.md) · [代码全景图](../../docs/代码全景图.md)

本目录的 25 个 Java 程序是独立的诊断工具：地形/气候/水文预览、计划审计与基准测试。它们**不进入生产 JAR**，也不是测试套件的一部分——`build.gradle` 没有任何 source set 指向这里。此前它们没有任何统一入口，只能靠回忆拼类路径；本文与 `run-audit-tool.sh` 就是那套入口。

## 1．怎么运行

```sh
cd neoforge

./tools/run-audit-tool.sh --list                          # 列出 25 个程序
./tools/run-audit-tool.sh --compile-all                   # 逐个编译，报告通过/失败
./tools/run-audit-tool.sh TerrainPreview                  # 编译并运行一个程序
./tools/run-audit-tool.sh PlanningBenchmark <profile.json> <新目录> <seed>
```

脚本做三件事：向 Gradle 取一次生产类路径（`./gradlew -I tools/planning-benchmark.gradle writePlanningBenchmarkClasspath`，写成 `build/planning-benchmark-classpath.txt`）、把选定的程序编译到 `build/audit-tools/classes`、再用该目录 + 生产类路径启动 JVM。被引用的其它工具会通过 `-sourcepath tools` 一并编译，但**仍然只输出到 `build/audit-tools/classes`**：

```sh
./gradlew clean build && unzip -l build/libs/adventureworldgen-0.1.0.jar | grep -c 'Audit\|Preview'   # 期望 0
```

环境变量：`AWG_TOOL_MEM`（默认 `2g`）、`AWG_TOOL_JAVA_OPTS`、`AWG_GRADLE_ARGS`（默认 `--offline`）。

## 2．共同约定

| 项 | 值 |
|---|---|
| JDK | 21（与工程 toolchain 相同） |
| 类路径 | `sourceSets.main.output` + `sourceSets.main.compileClasspath`，由上面的 Gradle 任务生成 |
| 工作目录 | **`neoforge/`**：所有硬编码相对路径都按此解析 |
| 默认档配置 | `src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json`（相对 `neoforge/`，存在） |
| 游戏运行时 | **不需要**。25 个程序都只走领域/规划代码，没有一个 import `net.minecraft.*`，也不需要启动游戏引导 |
| 内存 | 未特别说明时为 `-Xmx2g`；单表列出各程序的建议值 |

所有程序都在**默认包**中（无 `package` 声明），入口为 `public static void main`。

## 3．清单

“输入”列只写**必需的**输入；“历史版本”列指程序自身写死或假定的版本字面量——它们记录该程序当年针对哪一版本文档/输出，不代表当前实现。

| 程序 | 用途 | 必需参数 | 主要输出 | 预计成本 | 历史版本 |
|---|---|---|---|---|---|
| AllocationReplayAudit | 在已保存计划上重放第一阶段群系分配 | 计划目录、输出 TSV | TSV | 秒级～2 分钟 | 无（用 `input_sha256` 作身份） |
| BiomeDetailPreview | 必需掩膜与地表逐格选择的对照图 | 计划目录、输出 PNG | PNG | 秒级～1 分钟 | 图题 `r14` |
| BiomeFragmentationAudit | 隔离填充与地形过渡对碎片化的影响 | 无（全写死） | stdout | 秒级～1 分钟 | 地形版本字面量 `"audit"` |
| BoundaryPreview | 计划边界查渲染（无植被） | 计划目录、输出目录 | `.rgb`×2 + `coordinates.txt` | 秒级～1 分钟 | 无 |
| ChunkQueryBenchmark | 256 个新区块的水平查询基准 | 计划目录、profile JSON | stdout | 秒级～数分钟 | 无 |
| CoastDetailPreview | 三个嵌套尺度的冻结海岸线 | 种子、输出 PNG | PNG | 秒级 | 面板标签 `r9` |
| DefaultDiversityAudit | 实测干地归属与配方面积 | 配置 JSON、计划 JSON、输出 JSON | JSON | 秒级 | 输入哈希占位 `"audit"` |
| DemandPlannerAudit | 完整冻结环境与群系流水线 | 输出目录（+种子或种子列表） | PNG/TSV/TXT/JSON | 数分钟/种子 | `terrain-r21`、`erosion-v1`、哈希 `"audit"` |
| EcotonePreview | 真实计划的群系查询渲染 | 计划目录、PNG、相机文件、标题 | PNG + 相机文件 | 秒级～1 分钟 | 无（标题由参数给） |
| FrozenTerrainMetrics | 最终高度场的局部起伏与坡度 | 计划目录（+种子列表） | TSV | 秒级～1 分钟/种子 | 哈希 `"audit"` |
| HeightReservationAudit | 裁剪 vs 拟合包络的高度预留对比 | 版本标签 | stdout | 秒级 | 版本由参数给 |
| HumidityAudit | 冻结计划的湿度/环境一致性 | 计划 JSON | stdout | 1–3 分钟 | 哈希 `"audit"` |
| HydrologyAudit | 方块级水陆邻接审计 | 种子 | stdout | 数分钟 | `"audit"`、`erosion-v1` |
| OceanShelfPreview | 海底深度场剖面 | 输出 PNG | PNG | 秒级 | 图题 `r33` |
| PlannerMapPreview | 冻结计划查询渲染 | 计划目录、输出 PNG | PNG | 秒级～1 分钟 | 默认标签 `r12` |
| PlanningBenchmark | 全新默认档规划（含校验与持久化） | profile JSON、新目录、种子 | 计划目录 | 数分钟 | 标签 `benchmark` |
| PlanPatchTopologyAudit | 板块真实面积与位置 | 计划目录、输出 TSV | TSV | 秒级～1 分钟 | 无 |
| PlanReloadBenchmark | READY 重载基准 | 计划目录、profile JSON、次数 | stdout | 秒级/轮 | 无 |
| PlanTerrainAudit | 板块面积 + 地形/归属合法性 | 计划目录、输出 TSV | TSV | 秒级～数分钟 | 无 |
| RiverPreview | 河道几何与深度着色预览 | 种子、输出 PNG | PNG | 秒级～1 分钟 | 岛屿版本 `"preview"` |
| TemperatureFieldPreview | 冻结地形上的双字段气候试验 | 计划目录、输出目录 | `fields.bin` + PNG + JSON | 秒级～数分钟 | `height_reference: 76` |
| TerrainFragmentationAudit | 方块级分阶段消融 | 输出目录 | `.bin`×45 | 秒级 | 版本字面量 `"probe"` |
| TerrainPreview | 侵蚀前连续地形预览 | 无（种子写死 7331） | `build/reports/terrain-r6/*` | 数分钟 | `"r5"` |
| TerrainRecipeAudit | 固定种子配方与布局统计 | 输出目录（+种子列表） | TSV×2 | 1–3 分钟/种子 | 无 |
| TerrainRecipePreview | 真实配方函数高度/阴影图集 | 种子、输出 PNG | PNG | 秒级～1 分钟 | 图题 `r21` |

按输入形态分两类：

- **纯计算（11 个）**：BiomeFragmentationAudit、CoastDetailPreview、HeightReservationAudit、HydrologyAudit、OceanShelfPreview、PlanningBenchmark、RiverPreview、TerrainFragmentationAudit、TerrainPreview、TerrainRecipePreview，以及 TerrainRecipeAudit 的配方部分。
- **需要已生成的计划（14 个）**：AllocationReplayAudit、BiomeDetailPreview、BoundaryPreview、ChunkQueryBenchmark、DefaultDiversityAudit、DemandPlannerAudit（仅 `--preview`/`--render`）、EcotonePreview、FrozenTerrainMetrics、HumidityAudit、PlannerMapPreview、PlanPatchTopologyAudit、PlanReloadBenchmark、PlanTerrainAudit、TemperatureFieldPreview。计划由 `PlanningBenchmark` 或一次真实世界规划产出；`run/world` 等旧目录里的计划可能因 `input_sha256` 门禁或气候尺寸校验而拒绝载入，这是设计行为，不是工具缺陷。

## 4．逐项明细

### AllocationReplayAudit

- 用途：在已保存的默认地形与锚点上重放第一阶段的群系空间分配；只用内置配置做合法性检查，生产适配器另由 Minecraft GameTest 验证。
- 主类：`tools/AllocationReplayAudit.java`（默认包）
- 参数：`args[0]`=输入计划目录（含 `manifest.json`、`plan.json.gz`）；`args[1]`=输出 TSV。无默认值。
- 输入资源：`args[0]/manifest.json`、`args[0]/plan.json.gz`、默认档配置（相对 `neoforge/`）。
- 输出路径：`args[1]`（自动创建父目录）。
- 内存建议：`-Xmx2g`。需完整解码冻结计划，并为每个锚点建 `HashSet<Long>` 装箱单元格集合做连通分量。
- 预计成本：秒级～2 分钟。每个 patch 的候选网格 `(2*extent/4+1)²`，随后每 patch 一次单元格 BFS。
- 历史版本：无版本字面量，以 `manifest.json` 的 `input_sha256` 作为计划身份。

### BiomeDetailPreview

- 用途：把已保存的必需群系掩膜与地表逐格选择画成对照图。
- 主类：`tools/BiomeDetailPreview.java`
- 参数：`args[0]`=计划目录；`args[1]`=输出 PNG。
- 输入资源：`args[0]/manifest.json`、`args[0]/plan.json.gz`、默认档配置。
- 输出路径：`args[1]`（1100×688 PNG）。
- 内存建议：`-Xmx2g`。位图约 3 MB，解码主导。
- 预计成本：秒级～1 分钟。左右各 520×520 像素，右侧每像素一次 `surfaceBiomeAt`。
- 历史版本：图题写死 `r14 | Required biome body and block-scale edge mixing`。

### BiomeFragmentationAudit

- 用途：隔离填充器与地形过渡的影响，把必需掩膜与侵蚀排除在外；同一探针可分别对旧 JAR 与新类运行。
- 主类：`tools/BiomeFragmentationAudit.java`
- 参数：不读任何参数，种子与中心点写死在代码里（种子 `345705185492107788`、`4126649097427443736`、`1`；中心 `(0,0)`、`(1144,72)`、`(-1200,1200)`）。
- 输入资源：默认档配置。
- 输出路径：无文件输出，只打印 stdout。
- 内存建议：`-Xmx1g`。只有 256×256 的数组与计划自身缓存。
- 预计成本：秒级～1 分钟。3 种子 × 3 中心 × 65536 次查询，另加 9 次连通分量扫描。
- 历史版本：用 `"audit"` 作为河流与地形版本串（非真实版本号）。

### BoundaryPreview

- 用途：渲染真实存档计划的群系与海岸线查询结果，不含植被。
- 主类：`tools/BoundaryPreview.java`
- 参数：`args[0]`=计划目录；`args[1]`=输出目录。
- 输入资源：`args[0]/manifest.json`、`args[0]/plan.json.gz`、默认档配置。
- 输出路径：`args[1]/biomes.rgb`、`args[1]/coast.rgb`（各 512×512×3 裸 RGB）、`args[1]/coordinates.txt`。
- 内存建议：`-Xmx2g`。流式写出，不建整幅位图。
- 预计成本：秒级～1 分钟。两次 26 万次 `biomeAt`，外加约 1.3 万次边界搜索。
- 历史版本：无。

### ChunkQueryBenchmark

- 用途：用 256 个新区块位置对已有冻结计划重放水平查询；只测查询开销，不含写块、洞穴、结构与地物。
- 主类：`tools/ChunkQueryBenchmark.java`
- 参数：恰好 2 个——`args[0]`=计划目录，`args[1]`=匹配的 profile JSON；数量不符时主动抛 `IllegalArgumentException`。
- 输入资源：`args[0]/manifest.json`、`args[0]/plan.json.gz`、`args[1]`。
- 输出路径：无文件输出，打印每轮秒数与校验和。
- 内存建议：`-Xmx2g`。同时持有压缩字节、快照与还原对象（含两个 16384 列缓存）。
- 预计成本：秒级～数分钟。4 轮 × 64 区块 ×（96×4×4 次 `biomeAt` + 4×16×16 次 `terrainAt`）。
- 历史版本：无，全部来自数据。

### CoastDetailPreview

- 用途：在三个嵌套尺度上展示冻结海岸线的精确形状。
- 主类：`tools/CoastDetailPreview.java`
- 参数：`args[0]`=种子，`args[1]`=输出 PNG。
- 输入资源：无（`CoastGenerator(PlannerProfile.V2).generate(seed,3000,288)` 纯计算）。
- 输出路径：`args[1]`（1536×564 PNG）。
- 内存建议：`-Xmx1g`。位图约 3.5 MB。
- 预计成本：秒级。3 个面板 × 26 万次 `signedDistance`。
- 历史版本：面板标签写死 `r9`。

### DefaultDiversityAudit

- 用途：用实测的干地归属与配方面积衡量多样性，而不是把权重当作面积比例。
- 主类：`tools/DefaultDiversityAudit.java`
- 参数：`args[0]`=配置 JSON，`args[1]`=计划 JSON（`<seed>-plan.json`），`args[2]`=输出 JSON。
- 输入资源：`args[0]`、`args[1]`；无硬编码资源路径。
- 输出路径：`args[2]`（Gson 美化 JSON）。
- 内存建议：`-Xmx2g`。
- 预计成本：秒级。`radius=3000` 时约 14 万次干地采样。
- 历史版本：解码时写死输入哈希 `"audit"`（占位）。

### DemandPlannerAudit

- 用途：跑完整的冻结环境与群系流水线；结构使用有界测试几何体而不是 Minecraft NBT。
- 主类：`tools/DemandPlannerAudit.java`
- 参数：`args[0]`=输出目录；`args[1]` 为 `--preview`/`--render` 时 `args[2]`=种子并直接读 `args[0]/<seed>-plan.json`；否则 `args[1..]` 都是待规划的种子。
- 输入资源：默认档配置；预览模式读 `args[0]/<seed>-plan.json`。
- 输出路径：`args[0]/` 下的 `<seed>-humidity.png`、`<seed>-temperature-classes.png`、`<seed>-temperature.png`、`<seed>-terrain.png`、`<seed>-areas.tsv`、`<seed>-summary.txt`、`<seed>-plan.json`、`<seed>-biomes.png`、`<seed>-legend.txt`。
- 内存建议：`-Xmx2g`。侵蚀网格 815²、搜索自身约 100 MB 上限、4 张 780² 渲染图。
- 预计成本：数分钟/种子（完整规划 + 4 幅图约 243 万次采样）。
- 历史版本：`terrain-r21`、`erosion-v1`、输入哈希 `"audit"`。

### EcotonePreview

- 用途：渲染真实计划的群系查询结果；跨版本比较时要分别用各版本的类运行。
- 主类：`tools/EcotonePreview.java`
- 参数：4 个都要给——`args[0]`=计划目录，`args[1]`=输出 PNG，`args[2]`=相机坐标文件（不存在时会搜索并写入），`args[3]`=标题文案。
- 输入资源：计划目录、相机文件（可不存在）、默认档配置。
- 输出路径：`args[1]`（640×708 PNG）；必要时写 `args[2]`。
- 内存建议：`-Xmx2g`。
- 预计成本：秒级～1 分钟。41 万像素各一次 `biomeAt`，外加边界搜索。
- 历史版本：无（文案由参数给）。

### FrozenTerrainMetrics

- 用途：从最终“侵蚀 + 水文”后的精确高度场测量局部起伏与坡度。
- 主类：`tools/FrozenTerrainMetrics.java`
- 参数：`args[0]`=计划目录；`args[1..]`=种子列表（不给种子时只写表头）。
- 输入资源：`args[0]/<seed>-plan.json`（**未压缩**的单个计划文件）、默认档配置。
- 输出路径：`args[0]/frozen-local-metrics.tsv`（不创建目录，需已存在）。
- 内存建议：`-Xmx2g`。每种子约 3.5 万个装箱 `Double`。
- 预计成本：每种子秒级～1 分钟（188² 网格 × 9 次邻域采样）。
- 历史版本：输入哈希 `"audit"`。

### HeightReservationAudit

- 用途：用同一保留规划类别与种子，对比旧的“裁剪”与新的“拟合包络”。
- 主类：`tools/HeightReservationAudit.java`
- 参数：`args[0]`=打印在 `version` 列上的标签（自由文本）；种子写死 `9、7331、8844`。
- 输入资源：无（`RegionTerrain` + 单条 `TerrainCapacityPlan.Reservation` 纯计算）。
- 输出路径：无，打印 TSV 到 stdout。
- 内存建议：`-Xmx512m`。
- 预计成本：秒级（3 种子 × 401² ≈ 16 万次采样）。
- 历史版本：无内置版本号，`version` 列完全由参数决定。

### HumidityAudit

- 用途：检查 `DemandPlannerAudit` 冻结计划在最终四分格上的查询是否与环境规则一致。
- 主类：`tools/HumidityAudit.java`
- 参数：`args[0]`=计划 JSON 文件（不是目录）。
- 输入资源：`args[0]`、默认档配置。
- 输出路径：无，打印一行 PASS 统计。
- 内存建议：`-Xmx2g`（还原完整查询对象）。
- 预计成本：1–3 分钟（751² ≈ 56 万次迭代）。
- 历史版本：输入哈希 `"audit"`。

### HydrologyAudit

- 用途：可复现的方块级水陆邻接审计，含规划期侵蚀；发现漏水直接抛 `AssertionError`。
- 主类：`tools/HydrologyAudit.java`
- 参数：`args[0]`=种子。
- 输入资源：无（按种子重算，使用 `HydrologyProfile.FINITE_CONTINENT`）。
- 输出路径：无，打印统计。
- 内存建议：`-Xmx2g`（815² 侵蚀网格 + 河网点列）。
- 预计成本：数分钟（每河道每点 × 约 `2*maxBedRadius+1` 个横断面 × 4 个邻接方向）。
- 历史版本：岛屿版本 `"audit"`、侵蚀版本 `"erosion-v1"`。

### OceanShelfPreview

- 用途：直接采样海底深度场画剖面图。
- 主类：`tools/OceanShelfPreview.java`
- 参数：`args[0]`=输出 PNG；种子写死 7993，seaBand 写死 256。
- 输入资源：无（`new OceanBathymetry(7993)` 纯计算）。
- 输出路径：`args[0]`（1120×560 PNG）。
- 内存建议：`-Xmx512m`。
- 预计成本：秒级（3 条剖面 × 1001 次深度查询）。
- 历史版本：图题写死 `r33 | Shallows, shelves and deep seafloor relief`。

### PlannerMapPreview

- 用途：对真实冻结计划做查询渲染。
- 主类：`tools/PlannerMapPreview.java`
- 参数：`args[0]`=计划目录，`args[1]`=输出 PNG，`args[2]` 可选版本标签（缺省 `r12`）。
- 输入资源：计划目录、默认档配置。
- 输出路径：`args[1]`（1260×750 PNG）。
- 内存建议：`-Xmx2g`。
- 预计成本：秒级～1 分钟（36 万像素 × 一次 `biomeAt` + 一次温度查询）。
- 历史版本：默认标签 `r12`；画像区写死 6200×6200 方块。

### PlanningBenchmark

- 用途：用固定测试结构跑一次全新的默认档规划，含校验与持久化；不需要游戏引导。
- 主类：`tools/PlanningBenchmark.java`
- 参数：恰好 3 个——`args[0]`=profile JSON，`args[1]`=**必须不存在**的新目录，`args[2]`=种子。
- 输入资源：`args[0]`。
- 输出路径：`args[1]/adventureworldgen/plans/<profile-id>/`（`plan.json.gz`、`manifest.json`、`READY`）。
- 内存建议：`-Xmx2g`（搜索工作内存上限约 100 MB + 装配与编码缓冲）。
- 预计成本：数分钟（完整生产规划）。
- 历史版本：计划与来源包标签写死 `"benchmark"`，结构适配器版本 `"benchmark-fixed-piece-v1"`。

### PlanPatchTopologyAudit

- 用途：导出默认档计划中每个群系板块的真实面积与位置。
- 主类：`tools/PlanPatchTopologyAudit.java`
- 参数：`args[0]`=计划目录，`args[1]`=输出 TSV。
- 输入资源：计划目录、默认档配置。
- 输出路径：`args[1]`。
- 内存建议：`-Xmx2g`（每板块一个装箱单元格集合）。
- 预计成本：秒级～1 分钟。
- 历史版本：无。

### PlanReloadBenchmark

- 用途：对已有冻结计划做 READY 重载（读取、解码、重建查询对象），同一 JVM 内重复。
- 主类：`tools/PlanReloadBenchmark.java`
- 参数：恰好 3 个——计划目录、profile JSON、重复次数。
- 输入资源：计划目录、`args[1]`。
- 输出路径：无，打印每轮 `reload_seconds`。
- 内存建议：`-Xmx2g`。
- 预计成本：秒级/轮，随重复次数线性增长。
- 历史版本：无。

### PlanTerrainAudit

- 用途：导出板块面积与位置，并校验地形与归属合法性（比 `PlanPatchTopologyAudit` 多一层断言）。
- 主类：`tools/PlanTerrainAudit.java`
- 参数：`args[0]`=计划目录，`args[1]`=输出 TSV。
- 输入资源：计划目录、默认档配置。
- 输出路径：`args[1]`。
- 内存建议：`-Xmx2g`。
- 预计成本：秒级～数分钟（每板块按包围盒步长 4 全遍历，每格 2 次查询 + 2 次断言）。
- 历史版本：无。

### RiverPreview

- 用途：河道几何总览与方块级水域预览；`args[2]=before` 画标称河宽而非形态学调整后的河宽。
- 主类：`tools/RiverPreview.java`
- 参数：`args[0]`=种子，`args[1]`=输出 PNG，`args[2]` 可选 `before`。
- 输入资源：无（按种子重算，使用 `HydrologyProfile.FINITE_CONTINENT`）。
- 输出路径：`args[1]`（1440×820 PNG）。
- 内存建议：`-Xmx2g`。
- 预计成本：秒级～1 分钟（39.6 万次 `terrain.sample`）。
- 历史版本：岛屿版本 `"preview"`。
- **注意**：该程序原先还用 `BiomeAdapter.surface(...)` 比较河床**材料**。该接口已在 `1f1c60b feat(P5.2)!` 中按设计删除（材料改由原版地表管线决定，需要游戏注册表与方块状态），预览程序无法计算，因此该面板改为按水深着色，并在类注释里写明原因——在这里另发明一套材料来源只会变成第二条会漂移的规则。

### TemperatureFieldPreview

- 用途：在真实冻结地形上试验双字段气候；不修改已保存的计划。
- 主类：`tools/TemperatureFieldPreview.java`
- 参数：`args[0]`=计划目录，`args[1]`=输出目录（少于 2 个时打印完整参数说明）；可选 `args[2]` angle=32、`args[3]` spacing=900、`args[4]` lapse=0.025、`args[5]` 温度偏移=0、`args[6]` mountain height=110、`args[7]` mountain lapse=lapse、`args[8]` latitude contrast=1、`args[9]` mode=ordered、`args[10]` bend=spacing*0.16、`args[11]` warp scale=spacing*2.4。
- 输入资源：计划目录、默认档配置。
- 输出路径：`args[1]/fields.bin`（行优先大端：height、latitude、cooling、temperature 四个 float + 1 字节 land）、`temperature.png`、`temperature-bands.png`、`comparison.png`、`parameters.json`。
- 内存建议：`-Xmx2g`（两张 900² 位图 + 132 万像素级对照图，约 15 MB；解码主导）。
- 预计成本：秒级～数分钟（81 万次 `terrainAt` + `effectiveHeightAt`，写出约 13.8 MB）。
- 历史版本：`parameters.json` 写死 `"height_reference": 76`。

### TerrainFragmentationAudit

- 用途：方块级分阶段消融：原始配方、配方细节、冻结侵蚀 + 平滑。
- 主类：`tools/TerrainFragmentationAudit.java`
- 参数：`args[0]`=输出目录；`args[1]` 可选采样偏移（缺省 `0.5`）；种子与模板写死。
- 输入资源：无（按种子重算）。
- 输出路径：`args[0]/<seed>-<template>-<shape|detail|eroded>.bin`（45 个 256×256 float32）。
- 内存建议：`-Xmx1g`。
- 预计成本：秒级。
- 历史版本：版本字面量 `"probe"`。

### TerrainPreview

- 用途：侵蚀前连续地形的诊断预览。
- 主类：`tools/TerrainPreview.java`
- 参数：不读参数；种子写死 7331。
- 输入资源：默认档配置。
- 输出路径：`build/reports/terrain-r6/map.bin`、`build/reports/terrain-r6/coast.csv`（目录自动创建）。
- 内存建议：`-Xmx2g`。
- 预计成本：数分钟（800² 次 `terrainAt` + `biomeAt`，随后逐河道 7×7 邻域采样）。
- 历史版本：地形版本写死 `"r5"`；输出目录名沿用旧版 `terrain-r6`。

### TerrainRecipeAudit

- 用途：固定种子的配方与布局统计；`flat fraction` 统计两轴上 ≤0.01 格/格的格点比例。
- 主类：`tools/TerrainRecipeAudit.java`
- 参数：`args[0]`=输出目录；`args[1..]`=种子列表（不给种子时只写表头）。
- 输入资源：默认档配置。
- 输出路径：`args[0]/recipe-metrics.tsv`、`args[0]/template-distribution.tsv`（目录自动创建）。
- 内存建议：`-Xmx1g`。
- 预计成本：1–3 分钟/种子（12 模板 × 301² 格 × 3 次求值，加容量求解）。
- 历史版本：无。

### TerrainRecipePreview

- 用途：用统一比例与光照绘制真实配方函数的高度/阴影图集。
- 主类：`tools/TerrainRecipePreview.java`
- 参数：`args[0]`=种子，`args[1]`=输出 PNG。
- 输入资源：无（`TerrainRecipes(seed)` 纯计算）。
- 输出路径：`args[1]`（1160×1792 PNG）。
- 内存建议：`-Xmx512m`。
- 预计成本：秒级～1 分钟（12 模板 × 12.96 万像素 × 3 次求值）。
- 历史版本：图题写死 `Terrain r21 | ...`。

## 5．本轮核对结论与已知限制

1. **编译**：25/25 通过（`./tools/run-audit-tool.sh --compile-all`）。核对前 19 个无法编译，原因是重构期间类在包间移动（`ContentId`/`PlannerProfile` → `plan`，`PlannedBiomePatch`/`PlanDiagnostics` 提为顶层，`CellMask` → `spatial`，`ValueNoise` → `noise`）与若干调用形态变化（`TerrainCapacitySolver.reserve`、`GeneratedAdventurePlan.restore`、`ClimatePlan` 诊断参数等）。修复只改这些调用点，没有改动任何种子、参数、常量或输出路径。
2. **路径**：13 个程序原先写 `neoforge/src/main/resources/...`，而脚本以 `neoforge/` 为工作目录，实际会解析成 `neoforge/neoforge/...` 而失败；现已统一为相对 `neoforge/` 的 `src/main/resources/...`。仓库内**没有**任何绝对本机路径。
3. **冒烟验证**：本轮对 9 个程序做了最小参数实跑（`CoastDetailPreview`、`TerrainRecipePreview`、`OceanShelfPreview`、`RiverPreview`、`HeightReservationAudit`、`TerrainFragmentationAudit`、`TerrainPreview`、`HydrologyAudit`、`TerrainRecipeAudit`），均产出预期文件或统计。**昂贵全尺寸审计（`DemandPlannerAudit`、`HumidityAudit`、`ChunkQueryBenchmark`、`PlanReloadBenchmark` 等）本轮未跑**，它们需要一份由当前实现生成的计划，且单次数分钟到数十分钟。
4. **需要计划输入**：见第 3 节分类。旧目录（例如 `run-gametest/testcompanion-plan`）里的计划可能被 `input_sha256` 门禁或冻结气候尺寸校验拒绝，先跑一次 `PlanningBenchmark` 或一次真实世界规划再使用。
5. **仍然写死的版本字面量**：见“历史版本”列。它们出现在图像标题、输出目录名和 `RiverNetwork`/地形版本串里，用于标明该程序当年针对的版本；`BiomeDetailPreview`（`r14`）、`CoastDetailPreview`（`r9`）、`OceanShelfPreview`（`r33`）、`TerrainRecipePreview`（`r21`）、`PlanTerrain`（`r12`）、`TerrainPreview`（`r5`）等标题已经落后于当前实现，读取时请以程序实际调用的代码路径为准。
6. **不进入生产 JAR**：审计程序不加入任何 source set，编译产物只在 `build/audit-tools/classes`。
