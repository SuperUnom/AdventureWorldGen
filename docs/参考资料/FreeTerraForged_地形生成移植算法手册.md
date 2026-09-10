# FreeTerraForged 地形生成移植算法手册

[文档总览](../../README.md) · [本项目地形候选边界](../生成流程候选.md#terrain-candidate)

> 固定源码参考。AdventureWorldGen v1 已采用本文河网、湖泊、湿地、海床思想及水滴侵蚀/平滑的明确子集，版本名为 `ftf-hydrology-adapted-v1`；稳定随机键、星形海岸交点、区域高度和水体查询由本项目替换。其余章节仍是参考，不自动进入实现契约，也不声称与上游世界逐点一致。

版本：1.0 · 编写日期：2026-09-07

基准仓库：ETcodehome/FreeTerraForged；标签 `1.21.1_v0.0.6003R2`；提交 `43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a`。本文来自用户指定本地仓库的静态源码核对，未进行 Minecraft 世界生成测试。原仓库未作修改。

目标：让其他项目能够移植实际被 `Heightmap.make → TerrainProvider / Populators` 调用的地貌算法。覆盖全部普通高度模板、火山、复合模板、区域衔接、独立山脉、默认 UPLIFT 大陆基准、河网、河谷、湖状展宽、湿地和必要后处理。群系外观、树木、装饰不在范围内；气候和三维密度只说明依赖边界。

本文的“原实现”描述当前提交的行为；“移植建议”明确表示可选改造。正文公式用于理解与跨语言实现，关键函数保留精确代码和固定提交链接；代码片段是算法参照，不是可直接独立编译的完整程序。要实现逐点、逐种子一致，还须移植底层向量表、哈希、浮点与随机数语义。

## 阅读导航

| 章节 | 内容 |
|---|---|
| [1](#ref-1) | 总流程、坐标、单位、参数与种子 |
| [2](#ref-2) | 基础噪声、组合算子、台阶与侵蚀噪声 |
| [3](#ref-3) | 全部地形模板的逐步算法和精确配方 |
| [4](#ref-4) | 区域划分、加权选择、区域与海陆衔接 |
| [5](#ref-5) | 山脉分布场、山体与缩放行为 |
| [6](#ref-6) | 大陆抬升和分级水位 |
| [7](#ref-7) | 主河、支流、扰动、河谷四区、湖与湿地 |
| [8](#ref-8) | Tile 侵蚀、平滑与三维接入 |
| [9](#ref-9) | 移植接口、原实现注意点与验证清单 |
| [10](#ref-10) | 默认参数和关键实现补充 |
| [源码索引](#source-index) | 固定提交的源文件入口 |

<a id="ref-1"></a>

## 1．总流程与移植约定

常规生成路径按一个 Tile 批量采样，每个 `(x,z)` 对应一份 Cell，然后对整片数据做后处理：

```text
世界种子、预设
  → 大陆归属 / 海陆边缘值 / waterTable
  → 地形区域 ID / 边缘混合权重
  → 选择地形模板，与共享边界地形混合
  → 与独立山脉混合，加大陆抬升，再与海洋混合
  → 查询大陆河网，切削河床、河谷及湿地
  → 气候与兼容参数修正
  → Tile 水滴侵蚀、平滑、坡度与标签修正
  → 输出高度场，或经 CellSampler / NoiseRouter 生成三维方块
  → 高地河流填水与落差处理
```

单点 `Heightmap.apply` 不包括 Tile 邻域侵蚀和平滑。移植时应区分 `sampleRaw` 与 `sampleFinal`，否则预览和实际地形可能使用不同阶段的数据。[Heightmap:38](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/heightmap/Heightmap.java#L38) [TileGenerator:50](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/densityfunction/tile/generation/TileGenerator.java#L50)

<a id="ref-1-1"></a>

### 1.1 坐标与高度单位

- 水平坐标以方块为单位。大陆、区域和河流入口使用原始 `(x,z)`；地形模板及独立山脉实际查询坐标为 `(x/Gh,z/Gh)`，其中 `Gh=globalHorizontalScale`。
- 大陆/区域的空间尺度不能直接乘上 Gh 后当成原版等价行为。河流也没有套用这次模板坐标缩放。
- 设 `Q=properties.terrainScaler()=min(worldHeight,256)`，默认 Q=256。`Levels.worldHeight` 实际接收到 Q，不是整个世界可用总高度。
- `unit=1/Q`；`ground=min(seaLevel,Q)/Q`；`water=min(seaLevel−1,Q)/Q`。正常默认海平面 63 时，ground=63/256，water=62/256。
- `scale(float h)` 返回 `(int)(h×Q)`；`scale(int y)` 返回 `y/Q`。同名重载方向相反，跨语言移植请改名为 `heightToBlock` 与 `blockToHeight`。
- `water(n)=(waterY+n)/Q`；`ground(n)=(groundY+n)/Q`。这些值和 `seaLevel` 的一格差异会影响河床深度、填水和岸线。
- 模板高度可以超过 1，原版 `scale(float)` 没有把它限制到 1。不能把 0～1 当作所有组合后高度的硬范围。

[Levels:16](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/heightmap/Levels.java#L16) [GeneratorContext:25](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/GeneratorContext.java#L25) [WorldSettings:141](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java#L141)

<a id="ref-1-2"></a>

### 1.2 普通模板的统一输出

下文每份普通配方生成 height 噪声 F。最终写入：

```text
H = max(base × B + F × V, 0)
```

`B=settings.baseScale`，`V=settings.verticalScale`。基础 `ground` 噪声在默认预设中是 `seaLevel/Q` 常数。`Gv=globalVerticalScale` 仅在某些配方内部出现，不能统一再乘所有模板一次。

参数与执行顺序依据：[TerrainPopulator:16](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/populator/TerrainPopulator.java#L16)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
    public void apply(Cell cell, float x, float z) {
        float base = this.base.compute(x, z, 0) * this.baseScale;
        float height = this.height.compute(x, z, 0) * this.heightScale;

        cell.terrain = this.type;
        cell.height = Math.max(base + height, 0.0F);
        cell.erosion = this.erosion.compute(x, z, 0);
        cell.weirdness = this.weirdness.compute(x, z, 0);
    }
```

模板同时输出 `terrain`、`erosion`、`weirdness`。后两者是 Minecraft 生成参数，不是水滴模拟的侵蚀量；实际侵蚀量另存 `heightErosion`、`sediment`。即使不移植群系，也可能需要地形类别和河流掩码来控制后处理。

<a id="ref-1-3"></a>

### 1.3 种子与范围元数据

`Seed.next()` 是返回当前整数后加一；`offset(k)` 总是从 root+k 建立新流，不基于当前已消费位置。模板创建顺序、过滤顺序、复合组合顺序及 shuffle 都会影响逐点复现。`warpPerlin(...,seed.next(),...)` 内部使用种子 s、s+1，但外部只消费一次 next。

Perlin / Perlin2 保存构造种子，compute 的 seed 参数不改变它们的单点种子；Simplex、Ridge、Billow、Worley、Cubic 通常通过 `ShiftSeed` 在 compute 时把 shift 与传入 seed 相加。河流某些 Simplex 构造和采样都传同一个常数，因此有效种子为两者之和，见第 7 节。

为逐点复现保留 32 位有符号整数溢出、Java Random、`NoiseUtil` 的 floor/round、正弦查表与 float 运算。其 floor 对负整数也会减一，例如 floor(-1.0f)=-2；这与数学 floor 不同。修正它会改变边界处采样。

[Seed:12](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/util/Seed.java#L12) [ShiftSeed:14](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/ShiftSeed.java#L14) [NoiseUtil:63](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/NoiseUtil.java#L63)

<a id="ref-2"></a>

## 2．基础算子必须先一致

本章定位：[2.1 基础噪声及默认参数](#ref-2-1) · [2.2 数值变换与函数混合](#ref-2-2) · [2.3 坐标扰动](#ref-2-3) · [2.4 三种台阶函数](#ref-2-4) · [2.5 fancyMountains 的侵蚀噪声](#ref-2-5)。

所有配方都是“函数图”：把一个噪声传给另一个算子，意味着新函数在采样时递归求值。特别是 `warp(F,...)` 会在偏移坐标上重新求 F 的所有内部节点，不能先把 F 在原坐标算成一个数，再只移动这个数。

定义 `C(v,a,b)=clamp(v,a,b)`，`L(a,b,t)=a+t(b−a)`，`S3(t)=t²(3−2t)`，`S5(t)=t³(t(6t−15)+10)`。

<a id="ref-2-1"></a>

### 2.1 基础噪声及默认参数

| 构造函数 | 尺度与默认值 | 实际构造 |
|---|---|---|
| perlin(s,L,o,λ,g) | 频率 1/L；λ=2，g=0.5 | 四角梯度噪声，CURVE3 插值，多层叠加 |
| perlin2 | 同上 | 使用另一套梯度向量选择表 |
| simplex / simplex2 | λ=2，g=0.5 | 三角晶格梯度噪声，二者单层振幅常数不同 |
| perlinRidge | λ=2，g=0.975 | 山脊变换＋跨层反馈权重 |
| billow | λ=2，g=0.5 | 本版本为同结构 ridge 结果的反相 |
| cubic | λ=2，g=0.5 | 4×4 随机值的二维三次插值 |
| worley / worleyEdge | 频率 1/L，种子点偏移系数 1 | 邻域最近/次近种子点及相应函数 |

配方省略 λ/g 时使用此表；例如 `perlin(...,3,3.75)` 的 3.75 是 lacunarity，不是 gain。所有坐标扰动的尺度也先作用于当前输入坐标，再由模板外部的 Gh 影响实际方块尺度。[Noises:100](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Noises.java#L100)

Perlin 单层：取当前格子四角哈希梯度，计算梯度与局部位移点积，再在 X、Z 两轴用 S3 插值。叠加幅值依次为 `g,g²,...,g^o`，单层 seed 为构造 seed+i。归一化界限并非简单振幅和：采用 `SIGNALS[min(o,6)]×Σg^(i+1)`，SIGNALS 为 `[1,.9,.83,.75,.64,.62,.61]`，最后对称映射并截断到 0～1。

Simplex 用 skew 常数 0.36602542、unskew 常数 0.21132487，选三角形三个顶点。每点贡献为 `max(0,.5−dx²−dz²)^4 × gradientDot`；单层和乘 79.869484，Simplex2 则乘 99.83685。多层权重从 1 开始，界限系数表为 `[1,.989,.81,.781,.708,.702,.696]`。

Cubic 单层：在 4×4 哈希值上先沿 X 做 cubicLerp，再沿 Z 做 cubicLerp，乘 0.44444445。`cubicLerp(a,b,c,d,t)=p t³+((a−b)−p)t²+(c−a)t+b`，`p=(d−c)−(a−b)`。多层权重 `1,g,...`，按 `±.75×Σg^i` 截断映射。注意该类的 minValue/maxValue 仍是这组有符号界限，而 compute 已输出 0～1；后接 map 等算子时，这是原实现的可观察行为，不能擅自把元数据改为 0～1。

Ridge 的单层反馈与归一化如下；Billow 在同算法结果上取 `1−value`，并采用自己的默认 gain：

```text
weight=1; amp=2; value=0
for i=0..o−1:
    signal = (1−abs(Perlin.sample(p×λ^i,seed+i)))² × weight
    weight = clamp(signal×amp,0,1)
    value += signal / λ^i
    amp *= g
将 value 按构造时的最大反馈界限归一化到 [0,1]
```

[Perlin:29](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Perlin.java#L29) [Simplex:66](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Simplex.java#L66) [Cubic:22](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Cubic.java#L22) [PerlinRidge:32](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/PerlinRidge.java#L32) [Billow:32](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Billow.java#L32)

Worley：在缩放坐标周围 3×3 格子查询 `cell(seed,cx,cz)` 随机向量，种子点为 `(cx,cz)+vector×jitter`，记录最小 d1、次小 d2。EUCLIDEAN 在本实现中返回平方距离；NATURAL 返回 `|dx|+|dz|+dx²+dz²`。

| EdgeFunction | 原始表达式 | 声明范围，之后归一化 |
|---|---|---|
| DISTANCE_2 | d2−1 | [−1,1] |
| DISTANCE_2_ADD | d1+d2−1 | [−1,1.6] |
| DISTANCE_2_DIV | d1/d2−1 | [−1,0] |

`worley(NOISE_LOOKUP)` 在最近种子点坐标采样另一个噪声；火山高度使用这条路径。完整哈希为：s 异或 1619×cx、31337×cz，再做三次乘法×60493 和异或右移 13；`CELL_2D[hash&255]` 查固定表。不要用每次查询时重新抽随机向量替代。[WorleyEdge:47](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/WorleyEdge.java#L47) [NoiseUtil:164](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/NoiseUtil.java#L164)

<a id="ref-2-2"></a>

### 2.2 数值变换与函数混合

| 算子 | 精确语义/注意事项 |
|---|---|
| add / mul | 逐点相加/相乘；元数据跟随原类实现 |
| alpha(F,a) | `F×a+(1−a)`；不是 F×a。例如 a=.075 将 [0,1] 压至 [.925,1] |
| clamp(F,a,b) | 限制数值，声明范围改为 [a,b] |
| map(F,a,b) | `a+(F−F.min)/(F.max−F.min)×(b−a)`；本算子不自动截断 |
| NoiseUtil.map(v,min,max,range) | 先夹紧，再按 range 归一化；与上一个 map 不同 |
| invert(F) | `F.max−clamp(F,F.min,F.max)`；常见 [0,1] 情形为 1−F |
| pow(F,p) | F^p；原声明范围仍透传输入 |
| powCurve(F,p) | 围绕输入范围中点做带符号幂变换，再按变换后界限归一化；不是 F^p |
| boost(F) | 先按声明范围映射 0～1，再做 `v=v^(1−v)`，默认一次 |
| threshold(F,A,B,t) | 不大于 t 取 A，大于 t 取 B；不平滑 |
| curve(F,CURVE3) | S3(F) |

`alpha` 的声明范围依然透传输入；`Multiply` 的 min/max 仅分别相乘，没有计算四种端点组合。跨语言正确化这些范围会改变后续 map 的结果。可实现“兼容范围元数据”和“数学正确范围”两种策略，但必须显式选择。

`blend(S,A,B,mid,range)`：设 S 声明范围 [s0,s1]，`m=s0+(s1−s0)×mid`，`lo=max(s0,m−range/2)`，`hi=min(s1,m+range/2)`。S<lo 取 A，S>hi 取 B，中间以 `(S−lo)/(hi−lo)` 线性插值。range 是控制值域中的宽度，不是方块距离，也不是左右各一份的半宽。

参数与执行顺序依据：[Blend:31](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Blend.java#L31)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public float compute(float x, float z, int seed) {
        float mid = this.alpha.minValue() + (this.alpha.maxValue() - this.alpha.minValue()) * this.mid;
        float blendLower = Math.max(this.alpha.minValue(), mid - this.range / 2.0F);
        float blendUpper = Math.min(this.alpha.maxValue(), mid + this.range / 2.0F);
        float blendRange = blendUpper - blendLower;
		float alpha = this.alpha.compute(x, z, seed);
        if (alpha < blendLower) {
            return this.lower.compute(x, z, seed);
        }
        if (alpha > blendUpper) {
            return this.upper.compute(x, z, seed);
        }
        return NoiseUtil.lerp(this.lower.compute(x, z, seed), this.upper.compute(x, z, seed), this.interpolation.apply((alpha - blendLower) / blendRange));
	}
```

<a id="ref-2-3"></a>

### 2.3 坐标扰动

`warp(F,X,Z,D)` 先把 X、Z 各自声明范围映射到 `[−.5,.5]`，然后返回 `F(x+Xmapped×D,z+Zmapped×D)`。所以 D=200 的每轴名义偏移范围是 ±100，而不是 ±200。若输入的声明范围与实际输出不一致，实际偏移也随之偏离。

`warpPerlin(F,s,L,o,D)` 使用 `perlin(s,L,o)` 和 `perlin(s+1,L,o)` 作为两个坐标场。多个 warp 是嵌套函数；后加的外层 warp 会移动内层整个函数图的采样坐标。

`Domains.direction(dir,D)` 则用 `θ=dir×2π`，位移为 `(sinθ×D,cosθ×D)`；与二维独立扰动的 ±D/2 规则不同。

[DomainWarp:18](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/domain/DomainWarp.java#L18) [DirectionWarp:18](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/domain/DirectionWarp.java#L18)

<a id="ref-2-4"></a>

### 2.4 三种台阶函数

Steps 先把 v 反相，按 stepCount 向零截断量化，再根据一步中的余数，在 slopeMin/max 指定区间内混回连续值，最后反相回来。slopeCurve 默认 LINEAR，恶地部分使用 CURVE3：

参数与执行顺序依据：[Steps:20](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Steps.java#L20)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public float compute(float x, float z, int seed) {
		float noiseValue = this.input.compute(x, z, seed);
        float min = this.slopeMin.compute(x, z, seed);
        float max = this.slopeMax.compute(x, z, seed);
        float stepCount = this.steps.compute(x, z, seed);
        float range = max - min;
        if (range <= 0.0F) {
            return (int) (noiseValue * stepCount) / stepCount;
        }
        noiseValue = 1.0F - noiseValue;
        float value = (int) (noiseValue * stepCount) / stepCount;
        float delta = noiseValue - value;
        float alpha = NoiseUtil.map(delta * stepCount, min, max, range);
        return 1.0F - NoiseUtil.lerp(value, noiseValue, this.slopeCurve.apply(alpha));
	}
```

Terrace 先以 `spacing=(F.max−F.min)/(steps−1)` 建阶梯，阶梯值 `i×spacing`，左右边界为 `value±spacing×(1−blendRange)/2`；采样输入先夹到 `[0,.999999]`，用 floor(input×steps) 选阶。平台、斜坡和崖面分别由 ramp、rampHeight、cliff 控制，不能替换成普通 round：

参数与执行顺序依据：[Terrace:24](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Terrace.java#L24)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public float compute(float x, float z, int seed) {
        float input = NoiseUtil.clamp(this.input.compute(x, z, seed), 0.0F, 0.999999F);
        int index = NoiseUtil.floor(input * this.steps.length);
        Step step = this.steps[index];
        if (index == this.steps.length - 1) {
            return step.value;
        }
        if (input < step.lowerBound) {
            return step.value;
        }
        if (input > step.upperBound) {
            Step next = this.steps[index + 1];
            return next.value;
        }
        float ramp = 1.0F - this.ramp.compute(x, z, seed) * 0.5F;
        float cliff = 1.0F - this.cliff.compute(x, z, seed) * 0.5F;
        float alpha = (input - step.lowerBound) / (step.upperBound - step.lowerBound);
        float value = step.value;
        if (alpha > ramp) {
            Step next2 = this.steps[index + 1];
            float rampSize = 1.0F - ramp;
            float rampAlpha = (alpha - ramp) / rampSize;
            float rampHeight = this.rampHeight.compute(x, z, seed);
            value += (next2.value - value) * rampAlpha * rampHeight;
        }
        if (alpha > cliff) {
            Step next2 = this.steps[index + 1];
            float cliffAlpha = (alpha - cliff) / (1.0F - cliff);
            value = NoiseUtil.lerp(value, next2.value, cliffAlpha);
        }
        return value;
	}
```

AdvancedTerrace：当原值 v>blendMin 且 mask 非零，逐轮执行 `r=round(r×steps×i)/(steps×i)`，再 `r=r+(v−r)×slope`；结束后做 `(r+modulation)/(source.max+modulation.max)`，最后按 `clamp((v−blendMin)/(blendMax−blendMin),0,1)×mask` 与原值混合。这一变换使用原 v 作为每轮 slope 的参照。

[AdvancedTerrace:22](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/AdvancedTerrace.java#L22)

<a id="ref-2-5"></a>

### 2.5 fancyMountains 的侵蚀噪声

这是随高度函数采样的沟槽构造算法，区别于 Tile 上移动水滴的模拟：在查询点周围 3×3 抖动网格点 A 中，对每个 A 再查其 3×3 邻点，选输入高度最低的 B（包含 A 本身）。计算查询点到 A→B 线段的最短平方距离，取所有线段中的最小值，开方除以网格尺寸，夹到 [0,1]。因此低值沿“朝邻域低点连接”的线段分布。

多层时，网格尺寸乘 distanceFallOff，坐标乘 lacunarity，幅度乘 amplitude，加权平均。CONSTANT 模式输出 `strength×erosionField+(1−strength)×inputHeight`。山地默认调用参数：octaves=2、strength=.65、gridSize=128、amplitude=.15、lacunarity=3.1、distanceFallOff=.8；最后再用尺度 10 的 Perlin 方向场、长度 2 做方向扰动。

参数与执行顺序依据：[Erosion:80](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Erosion.java#L80)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	private float getSingleErosionValue(float x, float y, float gridSize, float[] cache) {
		Arrays.fill(cache, -1.0F);
		int pix = NoiseUtil.floor(x / gridSize);
		int piy = NoiseUtil.floor(y / gridSize);
		float minHeight2 = Float.MAX_VALUE;
		for (int dy1 = -1; dy1 <= 1; ++dy1) {
			for (int dx1 = -1; dx1 <= 1; ++dx1) {
				int pax = pix + dx1;
				int pay = piy + dy1;
				Vec2f vec1 = NoiseUtil.cell(this.seed, pax, pay);
				float ax = (pax + vec1.x()) * gridSize;
				float ay = (pay + vec1.y()) * gridSize;
				float bx = ax;
				float by = ay;
				float lowestNeighbour = Float.MAX_VALUE;
				for (int dy2 = -1; dy2 <= 1; ++dy2) {
					for (int dx2 = -1; dx2 <= 1; ++dx2) {
						int pbx = pax + dx2;
						int pby = pay + dy2;
						Vec2f vec2 = (pbx == pax && pby == pay) ? vec1 : NoiseUtil.cell(this.seed, pbx, pby);
						float candidateX = (pbx + vec2.x()) * gridSize;
						float candidateY = (pby + vec2.y()) * gridSize;
						float height = getNoiseValue(dx1 + dx2, dy1 + dy2, candidateX, candidateY, this.input, cache);
						if (height < lowestNeighbour) {
							lowestNeighbour = height;
							bx = candidateX;
							by = candidateY;
						}
					}
				}
				float height2 = sd(x, y, ax, ay, bx, by);
				if (height2 < minHeight2) {
					minHeight2 = height2;
				}
			}
		}
		return NoiseUtil.clamp(sqrt(minHeight2) / gridSize, 0.0F, 1.0F);
	}
```

<a id="ref-3"></a>

## 3．全部活动地形模板

本章定位：[3.1 Steppe：低起伏平地](#ref-3-1) · [3.2 Plains：平原](#ref-3-2) · [3.3 Hills 1：Perlin 与 Billow 调制丘陵](#ref-3-3) · [3.4 Hills 2：Cubic 与山脊调制丘陵](#ref-3-4) · [3.5 Dales：两组起伏混合的谷丘地形](#ref-3-5) · [3.6 Plateau：台地](#ref-3-6) · [3.7 Badlands：多层阶梯恶地](#ref-3-7) · [3.8 Torridonian：台阶化丘陵](#ref-3-8) · [3.9 Mountains 1：山脊山地及山脉共用核心](#ref-3-9) · [3.10 Mountains 2：Worley 峰体与山脊细节](#ref-3-10) · [3.11 Mountains 3：阶地山地](#ref-3-11) · [3.12 Volcano：锥体与火山口](#ref-3-12) · [3.13 深海、浅海、海岸](#ref-3-13) · [3.14 共享区域边界模板](#ref-3-14) · [3.15 两两复合模板](#ref-3-15)。

以下配方保留完整构造函数与返回字段，便于核对种子消费顺序。`height` 是尚未经过 TerrainPopulator 的 B/V 缩放的 F；`ground` 为共享基础噪声。中文名称只解释地貌，不表示对应的最终 Minecraft 群系。

<a id="ref-3-1"></a>

### 3.1 Steppe：低起伏平地

取 L=round(250×horizontalScale)。用尺度 2L 的三层 Perlin 经过 alpha(.45) 调制尺度 L 的单层 Perlin，再分别以 L/4 与 256 的坐标场扭曲。F 最后乘 .08、减 .02。其 Gv 不参与；V、B 在最终普通模板中生效。

参数与执行顺序依据：[Populators:61](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java#L61)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
    public static TerrainPopulator makeSteppe(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings) {
        int scaleH = Math.round(250.0F * settings.horizontalScale);

        Noise erosion = Noises.perlin(seed.next(), scaleH * 2, 3, 3.75F);
        erosion = Noises.alpha(erosion, 0.45F);
        
        Noise warpX = Noises.perlin(seed.next(), scaleH / 4, 3, 3.0F);
        Noise warpZ = Noises.perlin(seed.next(), scaleH / 4, 3, 3.0F);
        
        Noise height = Noises.perlin(seed.next(), scaleH, 1);
        height = Noises.mul(height, erosion);
        height = Noises.warp(height, warpX, warpZ, scaleH / 4.0F);
        height = Noises.warpPerlin(height, seed.next(), 256, 1, 200.0F);
        height = Noises.mul(height, 0.08F);
        height = Noises.add(height, -0.02F);
		return TerrainPopulator.make(TerrainType.FLATS, ground, height, DEFAULT_EROSION, DEFAULT_WEIRDNESS, settings);
    }
```

<a id="ref-3-2"></a>

### 3.2 Plains：平原

与 Steppe 同结构，但第一层扰动 lacunarity=3.5，第二层强度为 256，最终 F 乘 `.15×Gv` 再减 .02。内部实现接受两份配置：noiseSettings 控制 L，scalingSettings 控制 B、V、weight；普通平原二者相同，共享边界模板则不同。

参数与执行顺序依据：[Populators:79](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java#L79)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
    private static TerrainPopulator makePlains(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain noiseSettings, TerrainSettings.Terrain scalingSettings, float verticalScale) {
    	int scaleH = Math.round(250.0F * noiseSettings.horizontalScale);
      	
		Noise erosion = Noises.perlin(seed.next(), scaleH * 2, 3, 3.75F);
      	erosion = Noises.alpha(erosion, 0.45F);
      	
      	Noise warpX = Noises.perlin(seed.next(), scaleH / 4, 3, 3.5F);
      	Noise warpZ = Noises.perlin(seed.next(), scaleH / 4, 3, 3.5F);
      	
      	Noise height = Noises.perlin(seed.next(), scaleH, 1);
      	height = Noises.mul(height, erosion);
      	height = Noises.warp(height, warpX, warpZ, scaleH / 4.0F);
      	height = Noises.warpPerlin(height, seed.next(), 256, 1, 256.0F);
      	height = Noises.mul(height, 0.15F * verticalScale);
      	height = Noises.add(height, -0.02F);
      	return TerrainPopulator.make(TerrainType.FLATS, ground, height, DEFAULT_EROSION, DEFAULT_WEIRDNESS, scalingSettings);
    }
```

<a id="ref-3-3"></a>

### 3.3 Hills 1：Perlin 与 Billow 调制丘陵

尺度 200 三层 Perlin 乘 `alpha(Billow(400,3),.5)`，增加 30/400 两尺度扭曲，最终 `.6×Gv`。这里的 Billow 是第 2 节定义的原版算法。此函数未读取 settings.horizontalScale，只有外部 Gh 会统一改变水平采样。

参数与执行顺序依据：[Populators:138](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java#L138)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public static TerrainPopulator makeHills1(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float verticalScale) {
		Noise height = Noises.perlin(seed.next(), 200, 3);
		
		Noise scaler = Noises.billow(seed.next(), 400, 3);
		scaler = Noises.alpha(scaler, 0.5F);
		
		height = Noises.mul(height, scaler);
		height = Noises.warpPerlin(height, seed.next(), 30, 3, 20.0F);
		height = Noises.warpPerlin(height, seed.next(), 400, 3, 200.0F);
		height = Noises.mul(height, 0.6F * verticalScale);
		return TerrainPopulator.make(TerrainType.HILLS, ground, height, DEFAULT_EROSION, DEFAULT_WEIRDNESS, settings);
	}
```

<a id="ref-3-4"></a>

### 3.4 Hills 2：Cubic 与山脊调制丘陵

Cubic(128,2) 乘尺度 32 的弱起伏调制，先做两次 warp，再乘尺度 512 的山脊调制。顺序不能换：最后一份 scaler2 在当前外部查询坐标采样，不会自动受到前面的两次 warp 影响。最终 `.55×Gv`。

参数与执行顺序依据：[Populators:151](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java#L151)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public static TerrainPopulator makeHills2(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float verticalScale) {
		Noise height = Noises.cubic(seed.next(), 128, 2);

		Noise scaler1 = Noises.perlin(seed.next(), 32, 4);
		scaler1 = Noises.alpha(scaler1, 0.075F);
		height = Noises.mul(height, scaler1);
		
		height = Noises.warpPerlin(height, seed.next(), 30, 3, 20.0F);
		height = Noises.warpPerlin(height, seed.next(), 400, 3, 200.0F);

		Noise scaler2 = Noises.perlinRidge(seed.next(), 512, 2);
		scaler2 = Noises.alpha(scaler2, 0.8F);
		height = Noises.mul(height, scaler2);
		
		height = Noises.mul(height, 0.55F * verticalScale);
		return TerrainPopulator.make(TerrainType.HILLS, ground, height, DEFAULT_EROSION, DEFAULT_WEIRDNESS, settings);
	}
```

<a id="ref-3-5"></a>

### 3.5 Dales：两组起伏混合的谷丘地形

第一组 Billow 用 powCurve(.5) 改造并乘 .75，第二组 Billow 做普通幂 1.25；用被截断归一化的尺度 400 Perlin 混合，整体幂 1.125 后做尺度 300、强度 100 的 warp，最终乘 .4。Gv 不参与。

参数与执行顺序依据：[Populators:169](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java#L169)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public static TerrainPopulator makeDales(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings) {
		Noise hills1 = Noises.billow(seed.next(), 300, 4, 4.0F, 0.8F);
		hills1 = Noises.powCurve(hills1, 0.5F);
		hills1 = Noises.mul(hills1, 0.75F);
		
		Noise hills2 = Noises.billow(seed.next(), 350, 3, 4.0F, 0.8F);
		hills2 = Noises.pow(hills2, 1.25F);
		
		Noise selector = Noises.perlin(seed.next(), 400, 1);
		selector = Noises.clamp(selector, 0.3F, 0.6F);
		selector = Noises.map(selector, 0.0F, 1.0F);
		
		int warpSeed = seed.next();
		
		Noise hillsBlend = Noises.blend(selector, hills1, hills2, 0.4F, 0.75F);
		
		Noise height = hillsBlend;
		height = Noises.pow(height, 1.125F);
		height = Noises.warpPerlin(height, warpSeed, 300, 1, 100.0F);
		return TerrainPopulator.make(TerrainType.HILLS, ground, Noises.mul(height, 0.4F), Noises.threshold(selector, Erosion.LEVEL_2.mid(), Erosion.LEVEL_4.mid(), 0.5F), Noises.min(Noises.mul(height, -1.0F), Noises.constant(-0.06F)), settings);
	}
```

<a id="ref-3-6"></a>

### 3.6 Plateau：台地

反相大尺度山脊形成谷地控制；另一组山脊形成台顶起伏，由谷地强度限制；加入 Cubic 控制的大尺度高程后，通过 4 级 Terrace 形成平台与崖面；细节 surface 在台阶函数之后加入，避免所有细节都被量化。

参数与执行顺序依据：[Populators:101](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java#L101)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public static TerrainPopulator makePlateau(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float verticalScale) {
		Noise valley = Noises.perlinRidge(seed.next(), 500, 1);
		valley = Noises.invert(valley);
		valley = Noises.warpPerlin(valley, seed.next(), 100, 1, 150.0F);
		valley = Noises.warpPerlin(valley, seed.next(), 20, 1, 15.0F);
		
		Noise top = Noises.perlinRidge(seed.next(), 150, 3, 2.45F);
		top = Noises.warpPerlin(top, seed.next(), 300, 1, 150.0F);
		top = Noises.warpPerlin(top, seed.next(), 40, 2, 20.0F);
		top = Noises.mul(top, 0.15F);
		
		Noise valleyScaler = Noises.clamp(valley, 0.02F, 0.1F);
		valleyScaler = Noises.map(valleyScaler, 0.0F, 1.0F);
		
		top = Noises.mul(top, valleyScaler);
		
		Noise surface = Noises.perlin(seed.next(), 20, 3);
		surface = Noises.mul(surface, 0.05F);
		surface = Noises.warpPerlin(surface, seed.next(), 40, 2, 20.0F);
		
		Noise cubic = Noises.cubic(seed.next(), 500, 1);
		cubic = Noises.mul(cubic, 0.6F);
		cubic = Noises.add(cubic, 0.3F);
		
		Noise valleyBase = Noises.mul(valley, cubic);
		valleyBase = Noises.add(valleyBase, top);
		
		Noise height = Noises.terrace(valleyBase, 0.9F, 0.15F, 0.35F, 0.4F, 4);
		height = Noises.add(height, surface);
		height = Noises.mul(height, 0.475F * verticalScale);
		
		Noise weirdness = Noises.clamp(valleyBase, 0.0F, 0.415F);
		weirdness = Noises.map(weirdness, 0.0F, 1.0F);
		weirdness = Noises.map(weirdness, Weirdness.LOW_SLICE_NORMAL_DESCENDING.mid(), -0.42F);
		return TerrainPopulator.make(TerrainType.PLATEAU, ground, height, Erosion.LEVEL_3.source(), weirdness, settings);
	}
```

<a id="ref-3-7"></a>

### 3.7 Badlands：多层阶梯恶地

被区域掩码限制的山脊作为底图。4 阶与 10 阶两个 Steps 分支合成细节，另一个 4 阶 CURVE3 分支形成主体，再把主体与细节相乘。`modulation=.4`、`alpha=.6` 是这里的混合系数；与 `Noises.alpha` 不是同一个操作。

参数与执行顺序依据：[Populators:191](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java#L191)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public static TerrainPopulator makeBadlands(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings) {
		Noise mask = Noises.perlin(seed.next(), 270, 3);
		mask = Noises.clamp(mask, 0.35F, 0.65F);
		mask = Noises.map(mask, 0.0F, 1.0F);
		
		Noise hills = Noises.perlinRidge(seed.next(), 275, 4);
		hills = Noises.warpPerlin(hills, seed.next(), 400, 2, 100.0F);
		hills = Noises.warpPerlin(hills, seed.next(), 18, 1, 20.0F);
		hills = Noises.mul(hills, mask);
		
		float modulation = 0.4F;
		float alpha = 1.0F - modulation;
		
		Noise mod1 = Noises.warpPerlin(hills, seed.next(), 100, 1, 50.0F);
		mod1 = Noises.mul(mod1, modulation);
		
		Noise lowFreq = Noises.steps(hills, 4, 0.6F, 0.7F);
		lowFreq = Noises.mul(lowFreq, alpha);
		lowFreq = Noises.add(lowFreq, mod1);
		
		Noise highFreq = Noises.steps(hills, 10, 0.6F, 0.7F);
		highFreq = Noises.mul(highFreq, alpha);
		highFreq = Noises.add(highFreq, mod1);
		
		Noise detail = Noises.add(lowFreq, highFreq);
		detail = Noises.alpha(detail, 0.5F);
		
		Noise scaler = Noises.perlin(seed.next(), 200, 3);
		scaler = Noises.mul(scaler, modulation);
		
		Noise mod2 = Noises.mul(hills, scaler);
		
		Noise shape = Noises.steps(hills, 4, 0.65F, 0.75F, Interpolation.CURVE3);
		shape = Noises.mul(shape, alpha);
		shape = Noises.add(shape, mod2);
		shape = Noises.mul(shape, alpha);
		
		Noise height = Noises.mul(shape, detail);
		height = Noises.mul(height, 0.55F);
		height = Noises.add(height, 0.025F);
		return TerrainPopulator.make(TerrainType.BADLANDS, ground, height, DEFAULT_EROSION, DEFAULT_WEIRDNESS, settings);
	}
```

<a id="ref-3-8"></a>

### 3.8 Torridonian：台阶化丘陵

分别生成低幅平原和 boost 后的丘陵，用 Perlin 混合，再做 6 阶 AdvancedTerrace；台阶影响受 modulation、mask、slope 控制，最后 boost 并乘 .5。terrain 标签仍为 HILLS。

参数与执行顺序依据：[Populators:235](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java#L235)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public static TerrainPopulator makeTorridonian(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings) {
		Noise plains = Noises.perlin(seed.next(), 100, 3);
		plains = Noises.warpPerlin(plains, seed.next(), 300, 1, 150.0F);
		plains = Noises.warpPerlin(plains, seed.next(), 20, 1, 40.0F);
		plains = Noises.mul(plains, 0.15F);
		
		Noise hills = Noises.perlin(seed.next(), 150, 4);
		hills = Noises.warpPerlin(hills, seed.next(), 300, 1, 200.0F);
		hills = Noises.warpPerlin(hills, seed.next(), 20, 2, 20.0F);
		hills = Noises.boost(hills);
		
		Noise selector = Noises.perlin(seed.next(), 200, 3);
		
		Noise modulation = Noises.perlin(seed.next(), 120, 1);
		modulation = Noises.mul(modulation, 0.25F);
		
		Noise mask = Noises.perlin(seed.next(), 200, 1);
		mask = Noises.mul(mask, 0.5F);
		mask = Noises.add(mask, 0.5F);
		
		Noise slope = Noises.constant(0.5F);
		
		Noise blend = Noises.blend(selector, plains, hills, 0.6F, 0.6F);
		blend = Noises.advancedTerrace(blend, modulation, mask, slope, 0.0F, 0.3F, 6, 1);
		Noise height = Noises.boost(blend);
		height = Noises.mul(height, 0.5F);

		Noise weirdness = Noises.negative(blend);
		weirdness = Noises.min(weirdness, Noises.constant(Weirdness.LOW_SLICE_NORMAL_DESCENDING.max() - 0.01F));
		
		return TerrainPopulator.make(TerrainType.HILLS, ground, height, Erosion.LEVEL_5.source(), weirdness, settings);
	}
```

<a id="ref-3-9"></a>

### 3.9 Mountains 1：山脊山地及山脉共用核心

新缩放模式 L=round(610×settings.horizontalScale)，旧模式 L=round(410×settings.horizontalScale)。四层 Ridge 使用 λ=2.35、gain=1.15，乘尺度 24 的弱调制，warp 后可选 fancy。内部最终系数新模式 `1.3×verticalScale`，旧模式 `.7×verticalScale`；还会再经过外部 V。

参数与执行顺序依据：[Populators:272](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java#L272)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	private static TerrainPopulator makeMountains(Terrain terrainType, @Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float horizontalScale, float verticalScale, boolean makeFancy, boolean legacyScaling) {
		int scaleH = legacyScaling ? Math.round(410.0F * settings.horizontalScale) : Math.round(MOUNTAINS_H * settings.horizontalScale);

		Noise height = Noises.perlinRidge(seed.next(), scaleH, 4, 2.35F, 1.15F);

		Noise scaler = Noises.perlin(seed.next(), 24, 4);
		scaler = Noises.alpha(scaler, 0.075F);
		
		height = Noises.mul(height, scaler);
		height = Noises.warpPerlin(height, seed.next(), 350, 1, 150.0F);
		if(makeFancy) {
			height = makeFancy(seed, height);
		}
		height = Noises.cache2d(height);
		return TerrainPopulator.make(terrainType, ground, Noises.mul(height, (legacyScaling ? 0.7F : MOUNTAINS_V) * verticalScale), Erosion.LEVEL_1.source(), Noises.min(Noises.mul(height, Noises.constant(-1.0F)), Noises.constant(-0.08F)), settings);
	}
```

<a id="ref-3-10"></a>

### 3.10 Mountains 2：Worley 峰体与山脊细节

主形状取 WorleyEdge DISTANCE_2，放大 1.2 后截断并扭曲，再乘尺度 10 的弱调制与尺度 125 的 Ridge，幂 1.1 后可选 fancy，最终 `.645×Gv`。旧模式 L=360，新模式 L=round(360×settings.horizontalScale)。

参数与执行顺序依据：[Populators:297](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java#L297)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public static TerrainPopulator makeMountains2(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float verticalScale, boolean makeFancy, boolean legacyScaling) {
		Noise cell = Noises.worleyEdge(seed.next(), legacyScaling ? 360 : Math.round(360 * settings.horizontalScale), EdgeFunction.DISTANCE_2, DistanceFunction.EUCLIDEAN);
		cell = Noises.mul(cell, 1.2F);
		cell = Noises.clamp(cell, 0.0F, 1.0F);
		cell = Noises.warpPerlin(cell, seed.next(), 200, 2, 100.0F);
		
		Noise blur = Noises.perlin(seed.next(), 10, 1);
		blur = Noises.alpha(blur, 0.025F);
		
		Noise surface = Noises.perlinRidge(seed.next(), 125, 4);
		surface = Noises.alpha(surface, 0.37F);
		
		Noise height = Noises.clamp(cell, 0.0F, 1.0F);
		height = Noises.mul(height, blur);
		height = Noises.mul(height, surface);
		height = Noises.pow(height, 1.1F);
		if(makeFancy) { 
			height = makeFancy(seed, height);
		}
		height = Noises.cache2d(height);
		return TerrainPopulator.make(TerrainType.MOUNTAINS_2, ground, Noises.mul(height, 0.645F * verticalScale), Erosion.LEVEL_2.source(), Noises.min(Noises.mul(height, Noises.constant(-1.0F)), Noises.constant(-0.08F)), settings);
	}
```

<a id="ref-3-11"></a>

### 3.11 Mountains 3：阶地山地

前半段类似 Mountains 2，主形状旧模式 L=400，新模式 L=round(600×settings.horizontalScale)。以 24 阶 AdvancedTerrace 增加阶地，影响区间约 [.2,.45]、slope=.45，最后可选 fancy；新模式最终系数 1.185×Gv，旧模式 .645×Gv。

参数与执行顺序依据：[Populators:320](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java#L320)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
    public static TerrainPopulator makeMountains3(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float verticalScale, boolean makeFancy, boolean legacyScaling) {
    	Noise cell = Noises.worleyEdge(seed.next(), legacyScaling ? 400 : Math.round(MOUNTAINS3_H * settings.horizontalScale), EdgeFunction.DISTANCE_2, DistanceFunction.EUCLIDEAN);
    	cell = Noises.mul(cell, 1.2F);
    	cell = Noises.clamp(cell, 0.0F, 1.0F);
    	cell = Noises.warpPerlin(cell, seed.next(), 200, 2, 100.0F);

    	Noise blur = Noises.perlin(seed.next(), 10, 1);
    	blur = Noises.alpha(blur, 0.025F);
    	
    	Noise surface = Noises.perlinRidge(seed.next(), 125, 4);
    	surface = Noises.alpha(surface, 0.37F);
    	
    	Noise mountains = Noises.clamp(cell, 0.0F, 1.0F);
    	mountains = Noises.mul(mountains, blur);
    	mountains = Noises.mul(mountains, surface);
    	mountains = Noises.pow(mountains, 1.1F);
    	
    	Noise modulation = Noises.perlin(seed.next(), 50, 1);
    	modulation = Noises.mul(modulation, 0.5F);
    	
    	Noise mask = Noises.perlin(seed.next(), 100, 1);
    	mask = Noises.clamp(mask, 0.5F, 0.95F);
    	mask = Noises.map(mask, 0.0F, 1.0F);
    	
    	Noise slope = Noises.constant(0.45F);
    	
    	Noise height = Noises.advancedTerrace(mountains, modulation, mask, slope, 0.20000000298023224F, 0.44999998807907104F, 24, 1);
    	if(makeFancy) {
        	height = makeFancy(seed, height);
    	}
		height = Noises.cache2d(height);
		return TerrainPopulator.make(TerrainType.MOUNTAINS_3, ground, Noises.mul(height, (legacyScaling ? 0.645F : MOUNTAINS3_V) * verticalScale), Erosion.LEVEL_1.source(), Noises.min(Noises.mul(height, Noises.constant(-1.0F)), Noises.constant(-0.08F)), settings);
    }
```

<a id="ref-3-12"></a>

### 3.12 Volcano：锥体与火山口

高度限值场：用火山区域种子及区域尺度执行 Worley NOISE_LOOKUP，lookup 是尺度 2 的 Perlin 映射至 [.45,.65]，随后套区域 warp。

锥体场：Worley DISTANCE_2_DIV → invert → 区域 warp → powCurve(11) → clamp(.475,1) → map(0,1) → gradient(0,.5,.5) → 小尺度 warp(15,2,10) → 乘高度限值。

gradient 的 v<lower 段返回 `v^(1−strength)`；lower≤v≤upper 段用 `v^[1−strength×(1−(v−lower)/(upper−lower))]`；v>upper 保持原值。

山顶超过限值的 .94 后反折成火山口：令 `a=(cone−.94×limit)/(.06×limit)`，输出 `.94×limit×(1−a/5)`；a>.925 标为火山管。锥体低处混入低幅 Ridge，混合值域 [.15,.45]；最终加 levels.ground。火山没有走普通 TerrainPopulator 的 B/V 缩放，只使用配置 weight。

参数与执行顺序依据：[VolcanoPopulator:33](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/populator/VolcanoPopulator.java#L33)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
    public VolcanoPopulator(Seed seed, RegionConfig region, Levels levels, float weight) {
        float midpoint = 0.3F;
        float range = 0.3F;
        Noise heightLookup = Noises.perlin(seed.next(), 2, 1);
        heightLookup = Noises.map(heightLookup, 0.45F, 0.65F);
        
        Noise heightNoise = Noises.worley(region.seed(), region.scale(), CellFunction.NOISE_LOOKUP, DistanceFunction.EUCLIDEAN, heightLookup);
        heightNoise = Noises.warp(heightNoise, region.warpX(), region.warpZ(), region.warpStrength());
        this.height = heightNoise;
        
        Noise cone = Noises.worleyEdge(region.seed(), region.scale(), EdgeFunction.DISTANCE_2_DIV, DistanceFunction.EUCLIDEAN);
        cone = Noises.invert(cone);
        cone = Noises.warp(cone, region.warpX(), region.warpZ(), region.warpStrength());
        cone = Noises.powCurve(cone, 11.0F);
        cone = Noises.clamp(cone, 0.475F, 1.0F);
        cone = Noises.map(cone, 0.0F, 1.0F);
        cone = Noises.gradient(cone, 0.0F, 0.5F, 0.5F);
        cone = Noises.warpPerlin(cone, seed.next(), 15, 2, 10.0F);
        cone = Noises.mul(cone, this.height);
        
        this.cone = cone;
        
        Noise lowlands = Noises.perlinRidge(seed.next(), 150, 3);
        lowlands = Noises.warpPerlin(lowlands, seed.next(), 30, 1, 30.0F);
        lowlands = Noises.mul(lowlands, 0.1F);
        
        this.lowlands = lowlands;
        this.inversionPoint = 0.94F;
        this.blendLower = midpoint - range / 2.0F;
        this.blendUpper = this.blendLower + range;
        this.blendRange = this.blendUpper - this.blendLower;
        this.outer = TerrainType.VOLCANO;
        this.inner = TerrainType.VOLCANO_PIPE;
        this.bias = levels.ground;
        
        this.weight = weight;
    }
```

参数与执行顺序依据：[VolcanoPopulator:76](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/populator/VolcanoPopulator.java#L76)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
    public void apply(Cell cell, float x, float z) {
        float value = this.cone.compute(x, z, 0);
        float limit = this.height.compute(x, z, 0);
        float maxHeight = limit * this.inversionPoint;
        cell.weirdness = Weirdness.LOW_SLICE_NORMAL_DESCENDING.mid();
        cell.erosion = Erosion.LEVEL_4.mid();
        if (value > maxHeight) {
            float steepnessModifier = 1.0F;
            float delta = (value - maxHeight) * steepnessModifier;
            float range = limit - maxHeight;
            float alpha = delta / range;
            if (alpha > 0.925F) {
                cell.terrain = this.inner;
            }
            value = maxHeight - maxHeight / 5.0F * alpha;
        } else if (value < this.blendLower) {
            value += this.lowlands.compute(x, z, 0);
            cell.terrain = this.outer;
        } else if (value < this.blendUpper) {
            float alpha2 = 1.0F - (value - this.blendLower) / this.blendRange;
            value += this.lowlands.compute(x, z, 0) * alpha2;
            cell.terrain = this.outer;
        }
        cell.height = this.bias + value;
    }
```

源码对某些高度分支没有显式写 terrain 标签，可能保留调用前的标签；若重写为纯返回对象，应明确给每条分支赋类型。河流阶段结束后，若火山管低于海面或 riverMask<.85，会改回普通火山标签。这是标签修正，不是重新修改锥体高度。

<a id="ref-3-13"></a>

### 3.13 深海、浅海、海岸

深海混合两种海底形状：三层 Perlin 丘陵＋低频偏置；四层 Perlin 经 powCurve(.2)、反相形成沟谷＋偏置。两者按尺度 500 的 selector 混合，最后 warp。输出直接为海底高度，不套 B/V。

参数与执行顺序依据：[Populators:27](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java#L27)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public static CellPopulator makeDeepOcean(@Deprecated int seed, float seaLevel) {
		Noise hills = Noises.perlin(++seed, 150, 3);
		hills = Noises.mul(hills, seaLevel * 0.7F);

		Noise hillBias = Noises.perlin(++seed, 200, 1);
		hillBias = Noises.mul(hillBias, seaLevel * 0.2F);
		
		hills = Noises.add(hills, hillBias);
		
		Noise canyons = Noises.perlin(++seed, 150, 4);
		canyons = Noises.powCurve(canyons, 0.2F);
		canyons = Noises.invert(canyons);
		canyons = Noises.mul(canyons, seaLevel * 0.7F);
		
		Noise canyonBias = Noises.perlin(++seed, 170, 1);
		canyonBias = Noises.mul(canyonBias, seaLevel * 0.15F);
		
		canyons = Noises.add(canyons, canyonBias);
		
		Noise selector = Noises.perlin(++seed, 500, 1);
		
		Noise height = Noises.blend(selector, hills, canyons, 0.6F, 0.65F);
		height = Noises.warpPerlin(height, ++seed, 50, 2, 50.0F);
		return new OceanPopulator(TerrainType.DEEP_OCEAN, height);
	}
```

参数与执行顺序依据：[Populators:53](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java#L53)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public static CellPopulator makeShallowOcean(Levels levels) {
		 return new OceanPopulator(TerrainType.SHALLOW_OCEAN, Noises.constant(levels.water(-7)));
	}
```

参数与执行顺序依据：[Populators:57](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java#L57)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public static CellPopulator makeCoast(Levels levels) {
		return new OceanPopulator(TerrainType.COAST, Noises.constant(levels.water));
	}
```

浅海为 `levels.water(-7)`，海岸为 `levels.water`；这里的海岸高度模板与后续 BEACH 标签检测是两件事。

<a id="ref-3-14"></a>

### 3.14 共享区域边界模板

使用 Plains 的噪声构造，但 L 取 plainsSettings.horizontalScale，B、V、weight 取 steppeSettings。若把这里误写成普通平原配置，区域接缝的基准高度可能改变。

参数与执行顺序依据：[Populators:364](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java#L364)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public static TerrainPopulator makeBorder(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain plainsSettings, TerrainSettings.Terrain steppeSettings, float verticalScale) {
		return makePlains(seed, ground, plainsSettings, steppeSettings, verticalScale);
	}
```

<a id="ref-3-15"></a>

### 3.15 两两复合模板

可混合集合为 Steppe、Plains、Dales、Hills1、Hills2、Torridonian、Plateau、Badlands，共 8 项（先过滤 weight≤0）。保留这些单项，并为每一对生成复合，共 `n+n(n−1)/2` 项；全部启用时为 36 项。

另加入单独构造的 Badlands、Mountains1/2/3、Volcano，随后 shuffle。Badlands 在两个集合都出现，不能擅自去重。复合 selector 的尺度是区域尺寸的一半，并继续 warp，混合 mid=.5、range=.25。

参数与执行顺序依据：[TerrainProvider:67](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/provider/TerrainProvider.java#L67)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
    private static TerrainPopulator combine(TerrainPopulator tp1, TerrainPopulator tp2, Seed seed, Levels levels, int scale) {
        Terrain type = TerrainType.registerComposite(tp1.type(), tp2.type());
        Noise selector = Noises.perlin(seed.next(), scale, 1);
        selector = Noises.warpPerlin(selector, seed.next(), scale / 2, 2, scale / 2.0F);

        Noise height = Noises.blend(selector, tp1.height(), tp2.height(), 0.5F, 0.25F);
        height = Noises.max(height, Noises.zero());
        
        Noise erosion = Noises.blend(selector, tp1.erosion(), tp2.erosion(), 0.5F, 0.25F);
        Noise weirdness = Noises.threshold(selector, tp1.weirdness(), tp2.weirdness(), 0.5F);

        float weight = (tp1.weight() + tp2.weight()) / 2.0F;
        return new TerrainPopulator(type, Noises.constant(levels.ground), height, erosion, weirdness, weight);
    }
```

重要行为：复合取的是 `tp.height()` 原始函数，不是单模板 `apply` 后的完整高度；base 改为 levels.ground，且新模板 baseScale、heightScale 都为 1。这使单模板自身 B/V 不会原样继承到复合；已写进函数图的 Gv 则仍然存在。weirdness 是阈值切换，height 和 erosion 才是混合。移植若改成混合最终高度，应记为算法改造。

<a id="ref-4"></a>

## 4．区域划分与衔接算法

<a id="ref-4-1"></a>

### 4.1 区域划分

默认 RegionConfig：seed=worldSeed+789124；warp 种子流 root+8934 的前两项，Simplex 尺度 400、1 层，强度 200；区域尺度 R 由 terrainRegionSize 给出，默认 1200。RegionModule 内实际哈希 seed 再加 7。

```text
p = (worldXZ + domainWarp(worldXZ)) / R
遍历 floor(p) 周围 3×3 格点
种子点 q = integerCell + .7 × cellVector(seed,integerCell)
d(q,p) = |dx|+|dz|+dx²+dz²
取最近、次近距离 d1,d2，保留最近格点坐标 c
ID = clampNormalize(valCoord2D(seed,c),−1,1)
e = (1−clamp(d1/d2,0,1))^1.5
区域内部权重 a = clamp(e/.5,0,1)
```

接近边界时 d1≈d2，a→0；区域深处 a→1。a 是基于相对距离的权重，不是固定多少方块宽的边带。分区类别可以离散改变，数值高度通过后续混合处理。[RegionModule:32](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/region/RegionModule.java#L32)

<a id="ref-4-2"></a>

### 4.2 按权重选择模板

在候选模板中找最小非零权重 wmin；每个模板复制 round(weight/wmin) 次形成列表，以 `round(ID×(N−1))` 选项。权重为零的项通常跳过；若全部权重为零，代码回退原列表，不能将其理解为“完全不生成地形”。由于整数复制和 round，这也不是严格的连续权重概率抽样。

[RegionSelector:30](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/region/RegionSelector.java#L30)

<a id="ref-4-3"></a>

### 4.3 邻接区域共享边界混合

```text
H = lerp(Hborder, Hselected, a)
erosion = lerp(Eborder, Eselected, a)
weirdness = lerp(Wborder, Wselected, a)
```

a=0 只算 border，a=1 只算 selected。中间区域先算 border 并保存字段，再算 selected 并插值。两侧即使选中不同模板，都在边界趋向相同的 Hborder(x,z)，而不是直接采样邻居模板。地形标签没有随数值插值，可能发生离散切换；连续高度也不保证所有位置的一阶导数连续。

参数与执行顺序依据：[RegionLerper:17](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/region/RegionLerper.java#L17)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
    public void apply(Cell cell, float x, float y) {
        float alpha = cell.terrainRegionEdge;
        if (alpha == 0.0F) {
            this.lower.apply(cell, x, y);
            return;
        }
        if (alpha == 1.0F) {
            this.upper.apply(cell, x, y);
            return;
        }
        
        this.lower.apply(cell, x, y);
        float lowerHeight = cell.height;
        float lowerErosion = cell.erosion;
        float lowerWeirdness = cell.weirdness;
        
        this.upper.apply(cell, x, y);
        float upperHeight = cell.height;
        float upperErosion = cell.erosion;
        float upperWeirdness = cell.weirdness;
        
        cell.height = NoiseUtil.lerp(lowerHeight, upperHeight, alpha);
        cell.erosion = NoiseUtil.lerp(lowerErosion, upperErosion, alpha);
        cell.weirdness = NoiseUtil.lerp(lowerWeirdness, upperWeirdness, alpha);
    }
```

<a id="ref-4-4"></a>

### 4.4 海陆混合

使用大陆场 `c=continentEdge`，不是直接使用到海岸的方块距离：

- c<deepOcean：深海模板。
- deepOcean～shallowOcean：以 S3 权重混合深海与浅海。
- shallowOcean～coast：以 S3 权重混合浅海与海岸。
- c>coast：海岸模板。

以上构成 Hocean；外层在 shallowOcean～inland 范围，以线性权重将 Hocean 与已叠加大陆抬升的 Hland 混合。也就是说这里是嵌套混合，不能把两个层级扁平化为互不重叠的区间。

[ContinentLerper3:36](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/continent/ContinentLerper3.java#L36) [ContinentLerper2:30](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/continent/ContinentLerper2.java#L30)

<a id="ref-5"></a>

## 5．独立山脉算法

山脉是独立于普通区域模板的一层。设 Vmount 为 mountains.verticalScale、Hmount 为 mountains.horizontalScale：

```text
Lm = 旧缩放 ? 1000 : round(1000×Hmount×2.25)
M0 = WorleyEdge(seed.next(),Lm,DISTANCE_2_ADD,EUCLIDEAN)
M1 = warpPerlin(M0,seed.next(),333,2,250)
M  = map(clamp(S3(M1),0,.9),0,1)
```

这定义隐式山脉范围；M>.3 为影响区，M≥.8 为完全山脉区。它没有保存每条山脉的中心线或边界多边形，也不保证每个连通部分都形成可命名的一条山脉。

山脉高度使用 Mountains1 的共用核心，标签为 MOUNTAIN_CHAIN；入口如下：

参数与执行顺序依据：[Populators:293](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java#L293)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public static TerrainPopulator makeMountainChain(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float horizontalScale, float verticalScale, boolean makeFancy, boolean legacyScaling) { 
		return makeMountains(TerrainType.MOUNTAIN_CHAIN, seed, ground, settings, legacyScaling ? horizontalScale : horizontalScale * 2.25F, verticalScale, makeFancy, legacyScaling);
	}
```

混合参数为 `.3,.8,.575`：高度权重 `a=clamp((M−.3)/.5,0,1)`，H=lerp(Hregion,Hmountain,a)。`.575` 用来决定地形标签切换点：`.3+.5×.575=.5875`，不是高度混合中点。erosion/weirdness 也混合。

参数与执行顺序依据：[Blender:29](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Blender.java#L29)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public void apply(Cell cell, float x, float y) {
		float select = this.control.compute(x, y, 0);
		if (select < this.blendLower) {
			this.lower.apply(cell, x, y);
			return;
		}
		if (select > this.blendUpper) {
			this.upper.apply(cell, x, y);
			return;
		}
		float alpha = Interpolation.LINEAR.apply((select - this.blendLower) / this.blendRange);
		this.lower.apply(cell, x, y);
		float lowerHeight = cell.height;
		float lowerErosion = cell.erosion;
		float lowerWeirdness = cell.weirdness;
		Terrain lowerType = cell.terrain;
		this.upper.apply(cell, x, y);
		float upperHeight = cell.height;
		float upperErosion = cell.erosion;
		float upperWeirdness = cell.weirdness;
		cell.height = NoiseUtil.lerp(lowerHeight, upperHeight, alpha);
		cell.erosion = NoiseUtil.lerp(lowerErosion, upperErosion, alpha);
		cell.weirdness = NoiseUtil.lerp(lowerWeirdness, upperWeirdness, alpha);
		if (select < this.midpoint) {
			cell.terrain = lowerType;
		}
	}
```

**缩放必须按实参追踪：**非 legacy 模式下 Heightmap 把 `Gv×Vmount` 传给山脉核心，TerrainPopulator 又乘一次 Vmount，所以山脉起伏系数是 `1.3×Gv×Vmount²`；普通 Mountains1 区域为 `1.3×Gv×Vmount`。legacy 山脉为 `.7×Gv×Vmount`。

`makeMountainChain` 虽传递额外 horizontalScale 实参，但当前私有 makeMountains 并未使用该形参，L 实际仅取 `settings.horizontalScale×610/410`。因此不能依据调用中的 2.25 倍数推导山体内部尺度。2.25 确实作用于上面的山脉分布尺度 Lm。

纯山脉范围之外仍能通过区域模板生成 Mountains1/2/3。针对规划型项目，可以将 M 的生成替换为显式山脉范围/距离场，保留山体 F 和混合器；这属于空间布局适配，不能宣称与原噪声布局逐点相同。

<a id="ref-6"></a>

## 6．大陆抬升和河流水位基准

默认 `UPLIFT` 提供 waterTable。其他大陆模式同样可供模板采样，但不应套用“所有模式都有这份抬升”的假设。移植到有限大陆时，至少给形状层提供 `continentEdge`、稳定大陆 ID/中心、waterTable 三组数据。

<a id="ref-6-1"></a>

### 6.1 UPLIFT 海陆场

设 T=continentScale×4，频率 1/T。两个 Perlin2 场构造 domainWarp，尺度 round(.225T)、强度 round(.33T)，层数/lacunarity/gain 取大陆设置。扰动坐标缩放后，先找最近 Voronoi 种子点，再到它与周围种子之间的垂直平分线求最小距离。

该距离平方经过大小差异调整、开方和 [.05,.25] 归一化。海岸区间还用 cliffNoise 与 bayNoise 调整：cliff 为尺度 continentScale/2 的 Simplex2 经 [.1,.25] 截断重映射；bay 为尺度 100 的 Simplex×.1+.9。海岸区间的修正可能重复执行两次，精确顺序见原实现。

[UpliftContinentGenerator:66](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/continent/uplift/UpliftContinentGenerator.java#L66) [UpliftContinentGenerator:336](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/continent/uplift/UpliftContinentGenerator.java#L336)

<a id="ref-6-2"></a>

### 6.2 背景抬升梯度

在未做上述大陆 warp 的坐标上重新取最近 Voronoi 点 s0，收集 8 邻点。对邻点两两组合求垂直平分线交点，排除存在其他邻点更近的交点；取剩余顶点坐标的平均作为 c。它是顶点平均，不是面积加权多边形质心。

对于每个邻点 si，令 `d=si−s0`、`b=(|si|²−|s0|²)/2`，计算 `g_i(p)=(b−p·d)/(b−c·d)`，有效分母为正时取最小值，再夹到 [0,1]。所得 g 在参考中心较高，在边缘趋向零。将归一化中心 c 乘 T，并按原代码取整还原为方块坐标，作为大陆河网键。

随后两次执行 `remap(v,t)=v≤t?0:clamp((v−t)/(1−t),0,1)`，阈值先 levels.water 后 .15；再按大陆大小修正，最后用海岸边缘场约束靠海的抬升，输出 waterTable。精确顶点去重、候选判断与临界行为见第 10 节片段。

<a id="ref-6-3"></a>

### 6.3 15 级水位阶梯

Java Random(42) 交替生成 15 组 `dx=.5+nextDouble()`、`dy=.5+nextDouble()`；分别累加并除各自总和，得到阶梯终点 `(Xi,Yi)`。这是全局固定曲线，不随世界种子改变。每级坡段为 `[Xi−.02,Xi]`：

```text
t=(w−(Xi−.02))/.02
step(w)=lerp(Yprev,Yi,S3(t))           坡段
step(w)=Yi                            平台
U(w)=.5×step(clamp(w,0,1))
```

首个坡段前为 0。`getStepId` 在坡段返回 −2、初始平台返回 −1、之后平台返回 0～14；边界点分配遵循其二分查找实现。平台平坦度 F 取平台中心最大、两端为零的三角函数后套 S3，坡段为 0。

陆地分支加 U(w)；河流目标水位为 `W=levels.water+U(w)`。这让大陆抬升和河流共享基准，但不等于流量、集水区、地下水或水面压力的物理求解。

[ContinentalHydrology:96](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/ContinentalHydrology.java#L96) [ContinentalHydrology:135](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/ContinentalHydrology.java#L135)

<a id="ref-7"></a>

## 7．河流生成与切削

本章定位：[7.1 数据与缓存](#ref-7-1) · [7.2 主河骨架](#ref-7-2) · [7.3 支流骨架及约束](#ref-7-3) · [7.4 河道弯曲：两层坐标变形](#ref-7-4) · [7.5 河谷剖面的公共量](#ref-7-5) · [7.6 四区高度公式](#ref-7-6) · [7.7 湖状展宽](#ref-7-7) · [7.8 湿地](#ref-7-8) · [7.9 实际填水及包围盒](#ref-7-9)。

<a id="ref-7-1"></a>

### 7.1 数据与缓存

River 存起终点、方向、法线、长度与包围盒；Network 存一条 riverCarver、若干 wetlands 和子 Network。Rivermap 是一个大陆的根 Network 数组；RiverCache 用大陆中心坐标打包为 long 键，按需 computeIfAbsent。

一条 River 的 t=0 为源端，t=1 为出口端，`t=dot(p−A,B−A)/|B−A|²`。求点到河段距离时，t<0、t>1 分别改取端点。构造器直接除长度，故迁移实现应检测零长度候选；该检测属于改进。

原版 `new Random(id+riverSeed)` 负责一个大陆的构形随机序列；主河先生成，再 shuffle，然后依次生成支流与湿地。先后顺序影响结果。[BaseRiverGenerator:48](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/BaseRiverGenerator.java#L48) [RiverCache:18](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/RiverCache.java#L18)

<a id="ref-7-2"></a>

### 7.2 主河骨架

```text
for 每条主河:
    θ = randomFloat × 2π
    dir = (sinθ,cosθ)
    L = 从大陆中心沿 dir 查询到海洋的距离
    sourceDistance = max(400,(.05+.45×randomFloat)×L)
    source = center+dir×sourceDistance
    outlet = center+dir×L
    两端坐标转 int（向零截断）再转 float
    valleySize = 275×(.8+.7×randomFloat)
    创建 River、随机谷地曲线和 RiverWarp
```

入海距离依赖 AbstractContinent：先扩张搜索找到中心归属变化的界限，再二分逼近大陆边界（误差阈值约 50）；在中心至该界限二分找 continentalEdge 跨 shallowOcean 阈值的位置（误差阈值约 10）。它没有扫描沿途每一个最终地形低点；复杂海岸/非单调海陆场下不应把二分结果当严格的第一入海点。

默认主河数参数 8；主河彼此不会先统一通过完整的避免相交求解。长度参数 main=5000、fork=4500 存进配置，但上述实际线段长度取几何距离，不能把配置值当主河硬长度。

参数与执行顺序依据：[SimpleRiverGenerator:21](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/continent/simple/SimpleRiverGenerator.java#L21)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public List<Network.Builder> generateRoots(int x, int z, Random random, GenWarp warp) {
		List<Network.Builder> roots = new ArrayList<>(this.count);
		for (int i = 0; i < this.count; ++i) {
			float angle = random.nextFloat() * 6.2831855F;
			float dx = NoiseUtil.sin(angle);
			float dz = NoiseUtil.cos(angle);
			float startMod = 0.05F + random.nextFloat() * 0.45F;
			float length = this.continent.getDistanceToOcean(x, z, dx, dz);
			float startDist = Math.max(400.0F, startMod * length);
			float x2 = x + dx * startDist;
			float z2 = z + dz * startDist;
			float x3 = x + dx * length;
			float z3 = z + dz * length;
			float valleyWidth = 275.0F * River.MAIN_VALLEY.next(random);
			River river = new River((float) (int) x2, (float) (int) z2, (float) (int) x3, (float) (int) z3);
			RiverCarverSettings settings = new RiverCarverSettings(random);
			settings.fadeIn = this.main.fade;
			settings.valleySize = valleyWidth;
			RiverWarp riverWarp = RiverWarp.create(0.1F, 0.85F, random);
			RTFRiverCarver carver = new UpliftRiverCarver(river, riverWarp, this.main, settings, this.levels, this.lake, this.continent instanceof UpliftContinentGenerator);
			Network.Builder branch = Network.builder(carver);
			roots.add(branch);
		}
		return roots;
	}
```

<a id="ref-7-3"></a>

### 7.3 支流骨架及约束

父河长度乘 .44 为候选支流长度；小于 300 停止；递归入口 depth>2 停止，即 depth=0、1、2 各可尝试生成子河。

沿父河 t 从 .25 到小于 .9；首层步长为 `.1+random×.25`，后续为 `.25+random×.25`。每个位置交替两侧，角偏移为 `±2π×(.075+random×.115)`；从汇入口沿候选方向反推源点。源点和汇入口都须满足 continentEdge≥inland，并与已有网络做线段相交检查。

`Variance.of(a,b)` 的 b 是范围宽度，不是最大值。主河 valleySize 范围为 220～412.5，支流为 `275×(.4+.75u)`；不能误写为 275×uniform(.4,.75)。

重叠检查是延长约 250 方块后的线段相交，并对直属父河保留汇入口例外；不是任意两条河保持 250 方块的最小距离。

支流 bedWidth、bankWidth 从父河在汇入 t 的尺寸派生，取平方尺寸的平方根再乘 .75；至少 bedWidth=1，bankWidth≥bedWidth+1。bank 高度逐级降低但不低于 groundLevel。支流预设的 fade 用于新 carver，宽深等并非全部直接照搬 branchRivers 预设。

参数与执行顺序依据：[BaseRiverGenerator:67](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/BaseRiverGenerator.java#L67)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
    public void generateForks(Network.Builder parent, Variance spacing, RiverConfig config, Random random, GenWarp warp, List<Network.Builder> rivers, int depth) {
        if (depth > 2) {
            return;
        }
        float length = 0.44F * parent.carver.getRiver().length;
        if (length < 300.0f) {
            return;
        }
        int direction = random.nextBoolean() ? 1 : -1;
        for (float offset = 0.25F; offset < 0.9f; offset += spacing.next(random)) {
            for (boolean attempt = true; attempt; attempt = false) {
                direction = -direction;
                float parentAngle = parent.carver.getRiver().getAngle();
                float forkAngle = direction * 6.2831855F * River.FORK_ANGLE.next(random);
                float angle = parentAngle + forkAngle;
                float dx = NoiseUtil.sin(angle);
                float dz = NoiseUtil.cos(angle);
                long v1 = parent.carver.getRiver().pos(offset);
                float x1 = PosUtil.unpackLeftf(v1);
                float z1 = PosUtil.unpackRightf(v1);
                if (this.continent.getEdgeValue(x1, z1) >= this.minEdgeValue) {
                    float x2 = x1 - dx * length;
                    float z2 = z1 - dz * length;
                    if (this.continent.getEdgeValue(x2, z2) >= this.minEdgeValue) {
                        RiverConfig forkConfig = parent.carver.createForkConfig(offset, this.levels);
                        River river = new River(x2, z2, x1, z1);
                        if (!this.riverOverlaps(river, parent, rivers)) {
                            float valleyWidth = 275.0f * River.FORK_VALLEY.next(random);
                            RiverCarverSettings settings = new RiverCarverSettings(random);
                            settings.connecting = true;
                            settings.fadeIn = config.fade;
                            settings.valleySize = valleyWidth;
                            RiverWarp forkWarp = parent.carver.getWarp().createChild(0.15f, 0.75f, 0.65f, random);
                            RTFRiverCarver fork = new UpliftRiverCarver(river, forkWarp, forkConfig, settings, this.levels, this.lake, this.continent instanceof UpliftContinentGenerator);
                            Network.Builder builder = Network.builder(fork);
                            parent.children.add(builder);
                            this.generateForks(builder, River.FORK_SPACING, config, random, warp, rivers, depth + 1);
                        }
                    }
                }
            }
        }
    }
```

<a id="ref-7-4"></a>

### 7.4 河道弯曲：两层坐标变形

Rivermap 首先使用 GenWarp.river：尺度 95、1 层、强度 25 的 Perlin domain，加尺度 16、1 层、强度 5 的 domain。湖/湿地额外偏移来自尺度 200/50、强度 300/50 的两个 domain。相加域是在同一坐标计算偏移相加，不是顺序嵌套的 warp。

每条 RiverWarp 再按河流法线扭曲。主河 strength=125+nextInt(50)，frequency=.0005+.0005×u；沿 t 的主扰动包络在前 .1 渐入，过 .85 后渐出；支流采用 .15/.75 包络，继承父 frequency 和 strength 各乘 .65。

细摆动另按河长 L 计算：factor=L×.0004，wiggleFreq=8×factor，幅度 `clamp(map(t,0,.075)×25×factor,2,45)`；与 Simplex2 单层噪声及 t 的周期项共同作用。注意调用的是 `Simplex2.sample`，不是归一化多层 `Noises.simplex2`。

参数与执行顺序依据：[RiverWarp:37](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/RiverWarp.java#L37)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
    public long getOffset(float x, float z, float t, River river) {
        float alpha1 = this.getWarpAlpha(t);
        float px = x * this.frequency;
        float pz = z * this.frequency;
        float distance = alpha1 * this.scale;
        float noise = Simplex2.sample(px, pz, this.seed);
        float dx = river.normX * noise * distance;
        float dz = river.normZ * noise * distance;
        float alpha2 = this.getWiggleAlpha(t);
        float factor = river.length * 4.0E-4F;
        float wiggleFreq = 8.0f * factor;
        float wiggleDist = NoiseUtil.clamp(alpha2 * 25.0F * factor, 2.0F, 45.0F);
        float rads = noise + t * 6.2831855F * wiggleFreq;
        dx += NoiseUtil.cos(rads) * river.normX * wiggleDist;
        dz += NoiseUtil.sin(rads) * river.normZ * wiggleDist;
        return PosUtil.packf(dx, dz);
    }
```

这里把查询点 p 映射到扭曲坐标后对直线求距离，相当于定义曲线的隐式距离近似；不能直接把同样的偏移正向加到骨架顶点，就宣称两者等价。Network 递归时，子河承接父河已经扰动过的查询坐标，并再次施加自身扰动。

<a id="ref-7-5"></a>

### 7.5 河谷剖面的公共量

令 h 为当前归一化地面高度，w=waterTable，t 为扭曲后的河段进度，d 为到河段的实际方块距离。

| 字段 | 算法 |
|---|---|
| W，目标水面 | water+U(w) |
| Δbed，基本下切 | `(water−config.bedHeight)×(1+.25×depthNoise)` |
| V，谷底基准 | `W+(config.maxBankHeight−config.minBankHeight)` |
| F，平台平坦度 | UPLIFT 使用 flatness(w)，其他模式使用 flatness(t) |
| A，平台展宽平方因子 | `(1+.75F)²` |
| Bw，宽度乘子 | `1+.35×widthNoise` |
| Bs，附加宽度乘子 | `1+.4×asymmetryNoise` |
| D，沟槽调制 | `.7×(1−abs(gully))²+.3×(1−abs(rivulet))³` |

asymmetryNoise 虽这样命名，但代码没有根据河流法线左右符号分支；它只是位置相关乘子，不能据此宣称实现了明确的左右岸非对称剖面。

| 噪声 | 构造种子 | 尺度/层数 | compute 又传入 | 有效种子 |
|---|---:|---|---:|---:|
| width | 8241 | 150 / 2 | 8241 | 16482 |
| depth | 3912 | 100 / 2 | 3912 | 7824 |
| terrace | 5510 | 200 / 1 | 5510 | 11020 |
| asymmetry | 1193 | 250 / 1 | 1193 | 2386 |
| gully | 9876 | 65 / 2 | 9876 | 19752 |
| rivulet | 5432 | 20 / 2 | 5432 | 10864 |
| lake shore | 7439 | 55 / 3 | 7439 | 14878 |

这些都是 Noises.simplex，默认为 λ=2、g=.5，输出为 [0,1]。它们用固定种子，不随世界种子直接更换；世界河网位置会改变采样位置。

定义沿河尺寸函数：`size(t,lo,hi)=lerp(lo,hi,clamp(t/fade,0,1))`，其中同值范围恒定。河床平方尺寸 B(t)=size(t,.25,bedWidth²)。令 K 为湖展宽倍数（普通河段 K=1），每条河独立谷宽乘子 R=.70+.60u，由源点 float 位模式确定。

```text
Qb = A × Bw × Bs
r1 = sqrt(B(t)×Qb) × K
r2 = r1 + (maxBankHeight−minBankHeight)/unit × Qb
r3 = r2 + bankWidth×Bw×R
discrepancy = 1 + int((h−W)×Q)/100
r4 = r3 + (r3−r2)×(4+discrepancy)
```

r1～r4 分别是河床、河岸、谷底与外侧过渡终点。源码中的 valleySize×3.5 主要用于较宽的 riverMask 范围，并不是 r4 的直接值。

<a id="ref-7-6"></a>

### 7.6 四区高度公式

河床（d<r1）：`b=clamp(1−d²/(B(t)×A×K²),0,1)`，套 S3 后，下切 `Δbed×[1+(K−1)×(.35+lakeConfig.depth/50)]×S3(b)`，目标高度为 W 减去该项。

注意分区 r1 使用 Bw、Bs，但 b 的分母没有使用二者，因此实际水下剖面的影响半径可能小于“河床分区”半径。忠实复现应保留；改造版可以统一两种半径。

河岸（r1≤d<r2）：先 p=(d−r1)/(r2−r1)。将 p 与 round(3p)/3 混合，混合权重为 `.65×clamp(1.5×max(0,terrace−1.5D),0,1)`。在处理后的 p 上再扣 `.3D×4p(1−p)`，夹下界 0，套 S3，最后在 W 与 V 间插值。

谷底（r2≤d<r3）：`V+unit×(.4×terrace−.6×D)`。

外侧过渡（r3≤d<r4）：p=(d−r3)/(r4−r3)，按 5 阶处理后扣 `.25D×4p(1−p)`；这里沟槽包络使用原 p，而阶梯化结果单独存储。套 S3 后从 V 插值回当前 h。

最终写回 `h=min(h,target)`，只在目标更低时切削。标签与掩码可能仍会更新，不完全以“高度是否变低”为条件。四区并非所有边界都严格连续：谷底凹凸与相邻区基准存在小幅差异，round 阶地也会引入小台阶。精确公式如下：

参数与执行顺序依据：[UpliftRiverCarver:223](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/UpliftRiverCarver.java#L223)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
    private float carveZone1Riverbed(Cell cell, float currT, float distSqToCurr, float bedDepthOffset, float oceanHeightOffset, float sqScaleFactor, float targetWaterLevel, float widenMultiplier) {
        float effectiveScaleFactor = sqScaleFactor * (widenMultiplier * widenMultiplier);
        float bedInfluence = this.getDistanceAlpha(currT, distSqToCurr, this.bedWidth, effectiveScaleFactor);
        bedInfluence = bedInfluence * bedInfluence * (3.0F - 2.0F * bedInfluence);

        float lakeDepthMulti = 0.35F + (lakeConfig.depth / 50.0F);
        float dynamicDepthOffset = bedDepthOffset * (1.0F + (widenMultiplier - 1.0F) * lakeDepthMulti);

        float bedHeight = ContinentalHydrology.getWeightedWaterHeight(cell.waterTable) - (dynamicDepthOffset * bedInfluence) + oceanHeightOffset;

        cell.moisture = 1.0F;
        this.tag(cell, targetWaterLevel);
        return bedHeight;
    }
```

参数与执行顺序依据：[UpliftRiverCarver:238](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/UpliftRiverCarver.java#L238)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
    private float carveZone2BankStep(float distance, float zone1Radius, float zone2Radius, float targetWaterLevel, float targetValleyFloor, float terraceMask, float drainageMask) {
        float progress = (distance - zone1Radius) / (zone2Radius - zone1Radius);
        progress = NoiseUtil.clamp(progress, 0.0F, 1.0F);

        progress = applyTerracing(progress, terraceMask, drainageMask, 3.0F);

        float arc = progress * (1.0F - progress) * 4.0F;
        progress = Math.max(0.0F, progress - (drainageMask * 0.3F * arc));

        float smoothProgress = progress * progress * (3.0F - 2.0F * progress);
        return NoiseUtil.lerp(targetWaterLevel, targetValleyFloor, smoothProgress);
    }
```

参数与执行顺序依据：[UpliftRiverCarver:251](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/UpliftRiverCarver.java#L251)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
    private float carveZone3ValleyFloor(float targetValleyFloor, float terraceMask, float drainageMask) {
        float bumpiness = (terraceMask * 0.4F) - (drainageMask * 0.6F);
        return targetValleyFloor + (bumpiness * this.levels.unit);
    }
```

参数与执行顺序依据：[UpliftRiverCarver:256](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/UpliftRiverCarver.java#L256)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
    private float carveZone4Fadeout(float originalTerrainHeight, float distance, float zone3Radius, float zone4Radius, float targetValleyFloor, float terraceMask, float drainageMask) {
        float progress = (distance - zone3Radius) / (zone4Radius - zone3Radius);
        progress = NoiseUtil.clamp(progress, 0.0F, 1.0F);

        float modifiedProgress = applyTerracing(progress, terraceMask, drainageMask, 5.0F);

        float slopeMask = progress * (1.0F - progress) * 4.0F;
        modifiedProgress = Math.max(0.0F, modifiedProgress - (drainageMask * 0.25F * slopeMask));

        float smoothProgress = modifiedProgress * modifiedProgress * (3.0F - 2.0F * modifiedProgress);
        return NoiseUtil.lerp(targetValleyFloor, originalTerrainHeight, smoothProgress);
    }
```

河床标签设置 erosionMask=true、terrain=RIVER，riverWaterLevel 取已有值与 W 的最大值。riverMask 另由前后扭曲距离中的较小者与 `(valleySize×3.5)²×A` 得影响值 v；再套随机谷地曲线 `v^(lower+upper×v)`，最后 `riverMask=min(old,1−curve(v))`。

谷地曲线概率：5% 用 (.4,1)，25% 用 (4,5)，20% 用 (3,.25)，50% 用 (2,−.5)。这主要影响掩码及下游作用，不应误说成四区高度整体都使用这条曲线。

<a id="ref-7-7"></a>

### 7.7 湖状展宽

平台索引采用 UPLIFT 的 w，其他模式用 t；坡段返回 −2，禁止展宽。河段 t 必须落在 `[distanceMin−.04,distanceMax+.04]`。由源点 float 位模式与平台 ID 派生随机数，以 lake.chance 判定该平台是否展宽。

选中后 baseScale 在 `[sizeMin/100,sizeMax/100]` 内随机，岸线因子为 `baseScale×(1+.45×shoreNoise)`。沿河距离在设定区间内为 1，两侧各 .04 渐变并套 S3。最后：

```text
K = 1 + F × baseScale × (1+.45×shoreNoise) × distanceFade
```

K 同时影响 r1 和河床加深。`LakeConfig.of` 将 depth 转成 `levels.water(-settings.depth)`，但 carver 又将这个归一化绝对标高放入 `.35+depth/50`。原语义并非“湖深的方块数除以 50”；移植建议将深度单独保存为方块差或归一化差，并明确改造系数。

这条默认路径的湖状范围属于河床展宽，标签通常仍是 RIVER；其他 lake 类存在于仓库中，不代表都在当前生成链上调用。

[UpliftRiverCarver:291](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/UpliftRiverCarver.java#L291) [LakeConfig:31](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/lake/LakeConfig.java#L31)

<a id="ref-7-8"></a>

### 7.8 湿地

每条主/支河用 `skipSize=max(1,round((1−chance)×10))`、`nextInt(skipSize)==0` 判定湿地；所以真实概率为 1/skipSize。默认 chance=.6 对应 1/4，并非直接 60%。宽度从 `50+150u` 取；长度用 `Variance.of(sizeMin,sizeMax)`，即 sizeMin+sizeMax×u；默认 175/225 实际为 [175,400)，不是 [175,225]。

湿地沿河取起点 t<.75，结束点在其后随机延伸，形成一条带状影响区。局部水面为 `seaLevel/Q+U(w)`，比河流使用的 water+U(w) 基准通常高一格；bed 在局部水面下 3.5 格。

计算扰动后的河段距离 d²，以 v=1−d²/radius² 控制影响。总权重为 S5(clamp(v/.7))，内部权重为 clamp((v−.4)/.3)。先把原地面向 bed 压低，内部权重>.1 标 WETLAND，>.8 防止后续侵蚀。

小土丘噪声：Perlin(10,1) 经 [.3,.6] 截断重映射为 moundShape；Simplex(20,1) 经 [0,.3] 截断重映射为 moundHeight。对接近床面的位置，将高度以 `.8×moundShape×总权重` 混向水面上 1～2 格的小丘。

局部偏移取尺度 25、2 层 Perlin×8，X/Z 分别传 compute seed=0/1；由于 Perlin 忽略 compute seed，两轴实际使用同一个噪声值。这不等于两份独立坐标噪声。

参数与执行顺序依据：[Wetland:47](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/wetland/Wetland.java#L47)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
    public void apply(Cell cell, float rx, float rz, float x, float z) {

        // calculate the globally consistent water level at this cell
        float upliftOffset = (ContinentalHydrology.getWeightedWaterHeight(cell.waterTable));
        float oceanHeightOffset = levels.scale(levels.waterLevel);
        float localWaterSurface = oceanHeightOffset + upliftOffset;

        // generate a single block height factor so we can offset heights by single layers easily
        float singleBlock = levels.ground(1) - levels.ground(0);

        // Define the wetland bed. Mound builders will build up above the water level
        // this value roughly equates to 2 block depressions below water level for pools
        float bed = localWaterSurface - (3.5F * singleBlock);

        // early exit guard to prevent filling already carved surfaces
        if (cell.height < bed) return;

        // calculate warp meandering
        float warpStrength = 8.0F;
        float wx = rx + this.warpNoise.compute(x, z, 0) * warpStrength;
        float wz = rz + this.warpNoise.compute(x, z, 1) * warpStrength;
        float t = Line.distanceOnLine(wx, wz, this.a.x(), this.a.y(), this.b.x(), this.b.y());
        float d2 = getDistance2(wx, wz, this.a.x(), this.a.y(), this.b.x(), this.b.y(), t);

        // exit if we're outside the influence of the swamp
        if (d2 > this.radius2) return;

        // We use a fixed range for thresholds, only varying the intensity by noise
        float dist = 1.0F - d2 / this.radius2;
        float banks = cell.height;
        float tStart = 0.4F;
        float tEnd = 0.7F;
        float totalAlpha = NoiseUtil.map(dist, 0.0F, tEnd, tEnd);
        totalAlpha = Math.max(0, Math.min(1, totalAlpha));
        totalAlpha = NoiseUtil.interpQuintic(totalAlpha);

        float internalAlpha = NoiseUtil.map(dist, tStart, tEnd, tEnd - tStart);
        internalAlpha = Math.max(0, Math.min(1, internalAlpha));

        float targetHeight = NoiseUtil.lerp(banks, bed, internalAlpha);
        if (cell.height > targetHeight) {
            cell.height = NoiseUtil.lerp(cell.height, targetHeight, totalAlpha);
        }

        if (internalAlpha > 0.1F) {
            cell.terrain = TerrainType.WETLAND;
            cell.riverWaterLevel = upliftOffset;
            if (internalAlpha > 0.8F) cell.erosionMask = true;
        }

        // Dynamic island ranges relative to water level
        float localMoundMin = localWaterSurface + (1.0F * singleBlock);
        float localMoundMax = localWaterSurface + (2.0F * singleBlock);
        float localMoundVariance = localMoundMax - localMoundMin;

        // Hummocks with smooth slope encroachment
        if (cell.height >= bed && cell.height < localMoundMax) {
            float shapeAlpha = this.moundShape.compute(x, z, 0) * totalAlpha;
            float moundHeightNoise = this.moundHeight.compute(x, z, 0);
            float mounds = localMoundMin + (moundHeightNoise * localMoundVariance);

            cell.height = NoiseUtil.lerp(cell.height, mounds, shapeAlpha * 0.8F);
        }

        cell.riverMask = Math.min(cell.riverMask, 1.0F - totalAlpha);
    }
```

<a id="ref-7-9"></a>

### 7.9 实际填水及包围盒

高度切削完成不等于已经生成水方块。原版在 StrataRule.tryApply 中，对 RIVER/LAKE/WETLAND 且 riverWaterLevel>0 的柱，根据 `heightToBlock(water+U(w))` 填水；检查四邻水位，单格落差放流动水，多格落差还构造石壁/落水段。规则由 strataDecorator 控制是否加入；只去掉岩层外观时应迁出填水代码。湿地写 riverWaterLevel=U(w)，在 U=0 时不会满足该条件，须纳入水体复现检查。

River 的基础包围盒按线段扩张 550 方块，Network 再聚合主支河与湿地。实际 carver 有动态谷宽和湖展宽，不能假定固定包围盒总能覆盖最大影响区。移植建议用最大可能 r4、掩码半径及所有 warp 偏移计算安全边界，再进行空间索引裁剪。

[StrataRule:135](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/surface/rule/StrataRule.java#L135) [PresetSurfaceRuleData:20](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/PresetSurfaceRuleData.java#L20) [Network:18](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/Network.java#L18)

<a id="ref-8"></a>

## 8．后处理及三维接入

<a id="ref-8-1"></a>

### 8.1 水滴侵蚀

在带边框的 Tile 上，按每块区块和每轮种子生成水滴。每步从相邻四点双线性取高度/梯度，方向为 `.05×旧方向−.95×梯度` 后归一化，移动约一格。令 Δh=newHeight−oldHeight，携沙容量 `max(−Δh×speed×water×4,.01)`。

- 逆坡或携沙过多：沉积，逆坡沉积量 min(Δh,sediment)，否则 `(sediment−capacity)×depositSpeed`，双线性分配到四点。
- 其余情形：侵蚀量 `min((capacity−sediment)×erodeSpeed,−Δh)`，按半径 4 的笔刷权重削低邻点。
- 原速度更新为 `sqrt(speed²+Δh×3)`，NaN 后设 0；水量每步乘 .99。这里的 Δh 符号必须按原实现保留，若改物理模型需单独说明。
- erosionMask=true 的单元不被削低/沉积，河流掩码、模板 erosionModifier 与高度范围还会调制改变量。

细节见第 10 节原始 applyDrop。该模拟修改 Cell.height，与第 2.5 节的沟槽噪声不同。

<a id="ref-8-2"></a>

### 8.2 平滑与坡度

平滑在半径内取 `weight=1−distance²/radius²` 的邻域高度均值，以 smoothingRate 向均值靠近并经过 Modifier；erosionMask 的位置跳过。原实现就地更新数组，遍历顺序影响结果；双缓冲重写将改变输出，但可使并行行为更清晰。

坡度字段并非几何坡角：Steepness 遍历偏移 −1..2 的邻域，累加中心与 `max(neighborHeight,water)` 的绝对高度差/半径，再乘 scaler（默认 10）。给冒险通行成本使用时，建议另算可解释的坡度，而不是直接把该值当度数。

[Smoothing:17](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/densityfunction/tile/filter/Smoothing.java#L17) [Steepness:15](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/densityfunction/tile/filter/Steepness.java#L15)

<a id="ref-8-3"></a>

### 8.3 输出边界

一般高度场项目输出 H(x,z)、水位和地形标签即可；在 Minecraft 中，CellSampler 将高度接入 OFFSET/DEPTH，与洞穴函数组成三维密度。原 BASE_3D_NOISE_OVERWORLD 被设零，但洞穴仍能改变实体形状。要保留三维效果，需要接入目标项目的密度/体素层。

`applyClimate` 还调整 erosion/weirdness、海岸标签和部分 macroBiomeId 驱动的 weirdness 符号。它们输出给 NoiseRouter，因此不能把整个方法删掉就断言三维结果不变。移植建议把地形兼容参数放到独立步骤，温湿度留在环境系统。

[PresetNoiseRouterData:35](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/PresetNoiseRouterData.java#L35) [Heightmap:57](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/heightmap/Heightmap.java#L57)

<a id="ref-9"></a>

## 9．给其他项目的接口、取舍与验证

<a id="ref-9-1"></a>

### 9.1 建议的最小模块契约（新项目设计建议）

| 模块 | 输入 | 输出 |
|---|---|---|
| NoiseGraph | seed、坐标、节点配置 | 值及兼容 min/max 元数据 |
| ContinentField | seed、坐标 | ID、中心、海陆权重、waterTable |
| RegionLayout | seed、坐标或已保存规划 | 区域 ID、模板 ID、边界权重 |
| TerrainTemplate | 模板参数、坐标 | 基础高度、起伏、地形参数 |
| TerrainBlend | 区域权重、山脉场、海陆场 | 连续原始高度 |
| RiverPlanner | 大陆边界/出口或显式规划 | 主支河拓扑、尺寸、稳定随机标识 |
| RiverCarver | 河网、距离、水位、原高度 | 切削后高度、掩码、区域标签 |
| TileFilters | 带边框的高度片 | 最终高度、侵蚀/沉积、坡度 |
| WorldAdapter | 高度、水位、标签 | 目标引擎网格、密度或方块 |

不要让缓存对象本身成为规划权威；持久化布局和参数，缓存只是其可重新生成的结果。需要固定冒险布局时，可替换大陆/区域/山脉/河网的布局输入，保留本文高度函数与剖面，但应增加出生地、结构地面和通行区的约束与细化后复查。

<a id="ref-9-2"></a>

### 9.2 已核实的行为及改造选择

| 原实现行为 | 忠实复现 | 改造建议 |
|---|---|---|
| 模板横/纵缩放应用不一致 | 按每条配方原样处理 | 建统一缩放规则并重新标定 |
| 非 legacy 山脉 Vmount² | 保留平方效应 | 改成单次高度缩放 |
| 复合模板不继承父 B/V | 混合 raw height | 改混合完整最终高度 |
| 固定边界地形桥接 | 保留公共平原式边带 | 需要特定邻接时改成可配置过渡 |
| 河网先构形再切削 | 保留几何骨架和大陆基准 | 若要求水文合理，另加流域/纵剖面约束 |
| 河床分区与剖面半径不一致 | 保留两套因子 | 统一距离影响半径 |
| 湖深使用绝对归一化水位 | 保留原 LakeConfig 语义 | 改为明确的深度差 |
| 填水依赖 strataDecorator | 保留规则调用关系 | 独立 WaterBuilder |
| floor、范围元数据等特殊行为 | 保留以匹配种子 | 修正并更新基准样本 |

Cell.copyFrom/reset 也需要注意：此提交没有复制 waterTable、riverWaterLevel、riverZone，reset 又依赖 copyFrom。迁移时必须完整初始化与复制这三项，否则在缓存回退或对象复用路径中可能携带旧水体数据。是否影响原版某个实际世界需运行验证；字段缺失本身已从源码确认。[Cell:66](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/Cell.java#L66)

<a id="ref-9-3"></a>

### 9.3 建议的落地顺序

先实现基础噪声与算子，再逐个复现单模板；完成共享边界与海陆混合后接入山脉；然后实现大陆/水位基准、主河、支流和四区剖面；最后加入湖、湿地、Tile 后处理和实际填水。每步固定种子与采样坐标，保存中间字段，避免只比较最终截图。

<a id="ref-9-4"></a>

### 9.4 验证清单（待目标项目实际实施，本文未宣称已运行）

- 基础噪声：正负整数边界、大坐标、同种子重复采样、各算子的值与 min/max 元数据。
- 全部模板：单模板、复合模板及零权重场景；检查 Gh/Gv/B/V/Hmount 的实际影响。
- 衔接：跨区域边界、山脉 M=.3/.8、海陆各控制点扫描高度；将预期台阶与异常接缝区分。
- 河道：源端、汇流点、四区边界、湖展开区间、多个 Network 重叠、wetland 低抬升区。
- 水位：海岸归零、上游/下游关系、台阶水面、瀑布和填水开关。
- 缓存与并行：不同区块顺序、缓存驱逐再生成、Cell 复用、Tile 边框与邻区接缝。
- 忠实复现版比较中间浮点值；适配版验证明确目标，例如无意外断河、出生与结构地面不被噪声破坏、到达成本不因细化失效。

<a id="ref-10"></a>

## 10．默认参数与关键实现补充

本章定位：[10.1 默认参数读法](#ref-10-1) · [10.2 总组装函数](#ref-10-2) · [10.3 fancy 增强的精确入口](#ref-10-3) · [10.4 大陆内部抬升梯度](#ref-10-4) · [10.5 水滴更新原实现](#ref-10-5) · [10.6 关键函数核对表](#ref-10-6)。

<a id="ref-10-1"></a>

### 10.1 默认参数读法

默认 terrainRegionSize=1200、Gh=Gv=1、fancyMountains=true、legacyMountainScaling=false。单模板配置顺序为 `(weight,baseScale,verticalScale,horizontalScale)`；Steppe/Plains 的 baseScale=2，其余多数为 1。山地与河流的所有精确默认数值以下面默认预设为准：

参数与执行顺序依据：[Presets:20](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/Presets.java#L20)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public static Preset makeRTFDefault() {
		return new Preset(
			new WorldSettings(
				new Continent(ContinentType.UPLIFT, DistanceFunction.EUCLIDEAN, 3000, 0.7F, 0.25F, 0.25F, 5, 0.26F, 4.33F),
				new ControlPoints(0.0F, 0.0F, 0.1F, 0.25F, 0.327F, 0.448F, 0.502F),
				new Properties(SpawnType.CONTINENT_CENTER, 384, 64, 63, -54,0,0)
			), 
			new SurfaceSettings(new SurfaceSettings.Erosion(30, 256, 40, 95, 0.65F, 0.475F, 0.4F)),
			new CaveSettings(0.0F, 1.5625F, 1.0F, 1.0F, 1.0F, 0.05F, 0.07F, 0.0075F, true, false),
			new ClimateSettings(
				new RangeValue(0, 6, 2, 0.0F, 0.98F, 0.05F), 
				new RangeValue(0, 6, 1, 0.0F, 1.0F, 0.0F), 
				new BiomeShape(225, 8, 150, 80),
				new BiomeNoise(ClimateSettings.BiomeNoise.EdgeType.SIMPLEX, 24, 2, 0.5F, 2.65F, 14)
			), 
			new TerrainSettings(
				new General(0, 1200, 1.0F, 1.0F, true, false),
				new Terrain(1.0F, 2.0F, 1.0F, 1.0F),
				new Terrain(2.0F, 2.0F, 1.0F, 1.0F),
				new Terrain(2.0F, 1.0F, 1.0F, 1.0F),
				new Terrain(1.5F, 1.0F, 1.0F, 1.0F),
				new Terrain(1.5F, 1.0F, 1.0F, 1.0F), 
				new Terrain(1.0F, 1.0F, 1.0F, 1.0F), 
				new Terrain(2.0F, 1.0F, 1.0F, 1.0F), 
				new Terrain(2.5F, 1.0F, 1.0F, 1.0F),
				new Terrain(5.0F, 1.0F, 1.0F, 1.0F)
			), 
			new RiverSettings(
				0, 8, 
				new River(5, 2, 6, 20, 8, 0.75F),
				new River(4, 1, 4, 14, 5, 0.975F), 
				new Lake(0.3F, 0.0F, 0.03F, 10, 75, 150, 2, 10),
				new Wetland(0.6F, 175, 225)
			), 
			new FilterSettings(
				new Erosion(135, 12, 0.7F, 0.7F, 0.5F, 0.5F),
				new Smoothing(1, 1.8F, 0.9F)
			), 
			new StructureSettings(),
			new MiscellaneousSettings(true, 600, true, true, true, false, true, true, false, true, false, 0.4F, 0.4F)
		); 
	}
```

River 设置构造字段顺序不要凭位置猜测，按 RiverSettings 的声明核对。默认主河配置为 bedDepth=5、minBankHeight=2、maxBankHeight=6、bankWidth=20、bedWidth=8、fade=.75；支河为 4、1、4、14、5、.975。

[RiverSettings:53](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/RiverSettings.java#L53)

<a id="ref-10-2"></a>

### 10.2 总组装函数

这是模板种子消费、山脉实参和混合嵌套顺序的最终依据：

参数与执行顺序依据：[Heightmap:85](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/heightmap/Heightmap.java#L85)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public static Heightmap make(GeneratorContext ctx) {
    	HolderGetter<Noise> noiseLookup = ctx.noiseLookup;
    	
        Preset preset = ctx.preset;
        WorldSettings world = ctx.preset.world();
        ControlPoints controlPoints = world.controlPoints;

        TerrainSettings terrainSettings = preset.terrain();
        TerrainSettings.General general = terrainSettings.general;
        float globalVerticalScale = general.globalVerticalScale;
        
        Seed regionWarp = ctx.seed.offset(8934);
        int regionWarpScale = 400;
        int regionWarpStrength = 200;
        
        RegionConfig regionConfig = new RegionConfig(
        	ctx.seed.root() + 789124, 
        	general.terrainRegionSize, 
        	Noises.simplex(regionWarp.next(), regionWarpScale, 1),
        	Noises.simplex(regionWarp.next(), regionWarpScale, 1), 
        	regionWarpStrength
        );
        Levels levels = ctx.levels;
        float terrainFrequency = 1.0F / terrainSettings.general.globalHorizontalScale;
        CellPopulator region = new RegionModule(regionConfig);

        Seed mountainSeed = ctx.seed.offset(general.terrainSeedOffset);
        Noise mountainShape = Noises.worleyEdge(mountainSeed.next(), general.legacyMountainScaling ? 1000 : Math.round(1000 * terrainSettings.mountains.horizontalScale * 2.25F), EdgeFunction.DISTANCE_2_ADD, DistanceFunction.EUCLIDEAN);
        mountainShape = Noises.warpPerlin(mountainShape, mountainSeed.next(), 333, 2, 250.0F);
        mountainShape = Noises.curve(mountainShape, Interpolation.CURVE3);
        mountainShape = Noises.clamp(mountainShape, 0.0F, 0.9F);
        mountainShape = Noises.map(mountainShape, 0.0F, 1.0F);

        Noise ground = PresetNoiseData.getNoise(noiseLookup, PresetTerrainTypeNoise.GROUND);
        
        CellPopulator terrainRegions = new RegionSelector(TerrainProvider.generateTerrain(ctx.seed, terrainSettings, regionConfig, levels, noiseLookup));
        CellPopulator terrainRegionBorders = Populators.makeBorder(ctx.seed, ground, terrainSettings.plains, terrainSettings.steppe, globalVerticalScale);
        CellPopulator terrainBlend = new RegionLerper(terrainRegionBorders, terrainRegions);
        CellPopulator mountains = Populators.makeMountainChain(mountainSeed, ground, terrainSettings.mountains, terrainSettings.general.legacyMountainScaling ? 1.0F : terrainSettings.mountains.horizontalScale * 2.25F, terrainSettings.general.legacyMountainScaling ? globalVerticalScale : globalVerticalScale * terrainSettings.mountains.verticalScale, general.fancyMountains, general.legacyMountainScaling);
        Continent continent = world.continent.continentType.create(ctx.seed, ctx);
        Climate climate = Climate.make(continent, ctx);
        CellPopulator land = new Blender(mountainShape, terrainBlend, mountains, 0.3F, 0.8F, 0.575F);
        
        CellPopulator deepOcean = Populators.makeDeepOcean(ctx.seed.next(), levels.water);
        CellPopulator shallowOcean = Populators.makeShallowOcean(ctx.levels);
        CellPopulator coast = Populators.makeCoast(ctx.levels);

        CellPopulator oceans = new ContinentLerper3(deepOcean, shallowOcean, coast, controlPoints.deepOcean, controlPoints.shallowOcean, controlPoints.coast);
        CellPopulator terrain = new ContinentLerper2(oceans, (cell, x, z) -> {
            land.apply(cell, x, z);
            cell.height += (ContinentalHydrology.getWeightedWaterHeight(cell.waterTable));
        }, controlPoints.shallowOcean, controlPoints.inland);

        Noise beachNoise = Noises.perlin2(ctx.seed.next(), 20, 1);
        beachNoise = Noises.mul(beachNoise, ctx.levels.scale(5));
        return new Heightmap(terrain, region, continent, climate, levels, controlPoints, terrainFrequency, beachNoise);
	}
```

<a id="ref-10-3"></a>

### 10.3 fancy 增强的精确入口

参数与执行顺序依据：[Populators:354](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java#L354)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
	public static Noise makeFancy(@Deprecated Seed seed, Noise input) {
		Domain domain = Domains.direction(
			Noises.perlin(seed.next(), 10, 1),
			Noises.constant(2.0F)
		);
		Noise erosion = Noises.erosion(input, seed.next(), 2, 0.65F, 128.0F, 0.15F, 3.1F, 0.8F, BlendMode.CONSTANT);
		erosion = Noises.warp(erosion, domain);
		return erosion;
	}
```

<a id="ref-10-4"></a>

### 10.4 大陆内部抬升梯度

参数与执行顺序依据：[UpliftContinentGenerator:165](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/continent/uplift/UpliftContinentGenerator.java#L165)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
    public float getSmoothVoronoiGradient(Cell cell, float rawX, float rawZ) {

        // if we're at the ocean we're always at continent edge.
        if (cell.terrain.isShallowOcean() || cell.terrain.isDeepOcean()){
            return 0.0F;
        }

        float x = rawX * this.frequency;
        float y = rawZ * this.frequency;

        int xi = NoiseUtil.floor(x);
        int yi = NoiseUtil.floor(y);

        int cellX = xi;
        int cellY = yi;
        float cellPointX = x;
        float cellPointY = y;
        float nearestSq = Float.MAX_VALUE;

        // Find the closest Voronoi cell seed
        for (int cy = yi - 1; cy <= yi + 1; ++cy) {
            for (int cx = xi - 1; cx <= xi + 1; ++cx) {
                Vec2f vec = NoiseUtil.cell(this.seed, cx, cy);
                float px = cx + vec.x() * this.jitter;
                float py = cy + vec.y() * this.jitter;
                float dist2 = Line.distSq(x, y, px, py);

                if (dist2 < nearestSq) {
                    nearestSq = dist2;
                    cellPointX = px;
                    cellPointY = py;
                    cellX = cx;
                    cellY = cy;
                }
            }
        }

        // Collect the 8 immediate neighboring seeds
        float[] neighborX = new float[8];
        float[] neighborY = new float[8];
        int nIndex = 0;
        for (int cy2 = cellY - 1; cy2 <= cellY + 1; ++cy2) {
            for (int cx2 = cellX - 1; cx2 <= cellX + 1; ++cx2) {
                if (cx2 != cellX || cy2 != cellY) {
                    Vec2f vec2 = NoiseUtil.cell(this.seed, cx2, cy2);
                    neighborX[nIndex] = cx2 + vec2.x() * this.jitter;
                    neighborY[nIndex] = cy2 + vec2.y() * this.jitter;
                    nIndex++;
                }
            }
        }

        // Find the true polygon vertices
        float vertexSumX = 0.0F;
        float vertexSumY = 0.0F;
        int vertexCount = 0;
        float s0Sq = cellPointX * cellPointX + cellPointY * cellPointY;

        for (int i = 0; i < 8; i++) {
            float x1 = neighborX[i];
            float y1 = neighborY[i];
            float dx1 = x1 - cellPointX;
            float dy1 = y1 - cellPointY;
            float b1 = 0.5F * ((x1 * x1 + y1 * y1) - s0Sq);

            for (int j = i + 1; j < 8; j++) {
                float x2 = neighborX[j];
                float y2 = neighborY[j];
                float dx2 = x2 - cellPointX;
                float dy2 = y2 - cellPointY;
                float b2 = 0.5F * ((x2 * x2 + y2 * y2) - s0Sq);

                float det = dx1 * dy2 - dy1 * dx2;
                if (Math.abs(det) < 0.00001F) continue;

                float vx = (b1 * dy2 - b2 * dy1) / det;
                float vy = (dx1 * b2 - dx2 * b1) / det;

                float d0Sq = (vx - cellPointX) * (vx - cellPointX) + (vy - cellPointY) * (vy - cellPointY);
                boolean isValidVertex = true;

                for (int k = 0; k < 8; k++) {
                    if (k == i || k == j) continue;
                    float xk = neighborX[k];
                    float yk = neighborY[k];
                    float dkSq = (vx - xk) * (vx - xk) + (vy - yk) * (vy - yk);
                    if (dkSq < d0Sq - 0.0001F) { isValidVertex = false; break; }
                }

                if (isValidVertex) {
                    vertexSumX += vx;
                    vertexSumY += vy;
                    vertexCount++;
                }
            }
        }

        float centerX = (vertexCount > 0) ? (vertexSumX / vertexCount) : cellPointX;
        float centerY = (vertexCount > 0) ? (vertexSumY / vertexCount) : cellPointY;

        // Assign the centroid coordinates to the Cell object in world space
        // We reverse the frequency scaling to get back to raw coordinate space
        cell.continentX = Math.round(centerX / this.frequency);
        cell.continentZ = Math.round(centerY / this.frequency);

        // Render clean linear planes using unwarped logic
        float minGradient = 1.0F;
        for (int i = 0; i < 8; i++) {
            float px2 = neighborX[i];
            float py2 = neighborY[i];
            float dx = px2 - cellPointX;
            float dy = py2 - cellPointY;
            float lenSq = dx * dx + dy * dy;

            if (lenSq > 0.00001F) {
                float siSq = px2 * px2 + py2 * py2;
                float baseHalfDiff = 0.5F * (siSq - s0Sq);
                float h_x = baseHalfDiff - (x * dx + y * dy);
                float h_c = baseHalfDiff - (centerX * dx + centerY * dy);
                if (h_c > 0.00001F) {
                    float planeValue = h_x / h_c;
                    if (planeValue < minGradient) minGradient = planeValue;
                }
            }
        }
        return NoiseUtil.clamp(minGradient, 0.0F, 1.0F);
    }
```

<a id="ref-10-5"></a>

### 10.5 水滴更新原实现

参数与执行顺序依据：[Erosion:76](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/densityfunction/tile/filter/Erosion.java#L76)。以下为该版本原始算法片段；依赖的算子见第 2 节。

```java
    private void applyDrop(float posX, float posY, final Cell[] cells, final int mapSize, final TerrainPos gradient1, final TerrainPos gradient2) {
        float dirX = 0.0f;
        float dirY = 0.0f;
        float sediment = 0.0f;
        float speed = this.initialSpeed;
        float water = this.initialWaterVolume;
        gradient1.reset();
        gradient2.reset();
        for (int lifetime = 0; lifetime < this.maxDropletLifetime; ++lifetime) {
            final int nodeX = (int)posX;
            final int nodeY = (int)posY;
            final int dropletIndex = nodeY * mapSize + nodeX;
            final float cellOffsetX = posX - nodeX;
            final float cellOffsetY = posY - nodeY;
            gradient1.at(cells, mapSize, posX, posY);
            dirX = dirX * 0.05f - gradient1.gradientX * 0.95f;
            dirY = dirY * 0.05f - gradient1.gradientY * 0.95f;
            float len = (float)Math.sqrt(dirX * dirX + dirY * dirY);
            if (Float.isNaN(len)) {
                len = 0.0f;
            }
            if (len != 0.0f) {
                dirX /= len;
                dirY /= len;
            }
            posX += dirX;
            posY += dirY;
            if ((dirX == 0.0f && dirY == 0.0f) || posX < 0.0f || posX >= mapSize - 1 || posY < 0.0f || posY >= mapSize - 1) {
                return;
            }
            final float newHeight = gradient2.at(cells, mapSize, posX, posY).height;
            final float deltaHeight = newHeight - gradient1.height;
            final float sedimentCapacity = Math.max(-deltaHeight * speed * water * 4.0f, 0.01f);
            if (sediment > sedimentCapacity || deltaHeight > 0.0f) {
                final float amountToDeposit = (deltaHeight > 0.0f) ? Math.min(deltaHeight, sediment) : ((sediment - sedimentCapacity) * this.depositSpeed);
                sediment -= amountToDeposit;
                this.deposit(cells[dropletIndex], amountToDeposit * (1.0f - cellOffsetX) * (1.0f - cellOffsetY));
                this.deposit(cells[dropletIndex + 1], amountToDeposit * cellOffsetX * (1.0f - cellOffsetY));
                this.deposit(cells[dropletIndex + mapSize], amountToDeposit * (1.0f - cellOffsetX) * cellOffsetY);
                this.deposit(cells[dropletIndex + mapSize + 1], amountToDeposit * cellOffsetX * cellOffsetY);
            }
            else {
                final float amountToErode = Math.min((sedimentCapacity - sediment) * this.erodeSpeed, -deltaHeight);
                for (int brushPointIndex = 0; brushPointIndex < this.erosionBrushIndices[dropletIndex].length; ++brushPointIndex) {
                    final int nodeIndex = this.erosionBrushIndices[dropletIndex][brushPointIndex];
                    final Cell cell = cells[nodeIndex];
                    final float brushWeight = this.erosionBrushWeights[dropletIndex][brushPointIndex];
                    final float weighedErodeAmount = amountToErode * brushWeight;
                    final float deltaSediment = Math.min(cell.height, weighedErodeAmount);
                    this.erode(cell, deltaSediment);
                    sediment += deltaSediment;
                }
            }
            speed = (float)Math.sqrt(speed * speed + deltaHeight * 3.0f);
            water *= 0.99f;
            if (Float.isNaN(speed)) {
                speed = 0.0f;
            }
        }
    }
```

<a id="ref-10-6"></a>

### 10.6 关键函数核对表

| 需求 | 文档覆盖位置 |
|---|---|
| 所有普通模板、3 种山地及火山 | 3.1～3.12 |
| 海洋、海岸、共享边界及复合模板 | 3.13～3.15 |
| 算子内部算法与变形意义 | 第 2 节 |
| 区域权重、选型与衔接 | 第 4 节 |
| 山脉范围、山体、缩放 | 第 5 节 |
| 水位基准及大陆抬升 | 第 6 节 |
| 主支流骨架、扰动、切削、湖、湿地 | 第 7 节 |
| 后处理、引擎接入、移植验证 | 第 8～9 节 |

本手册给出的是固定版本的移植依据；完整 Java 工程仍依赖其注册表、Codec、NoiseUtil 常量表及 Minecraft 接入层。只移植地貌时，可以用目标项目的配置与数据结构替换这些外围依赖，但应保留所选兼容策略的数值和采样语义。


<a id="source-index"></a>

## 源码定位索引

以下链接均固定到本文提交，不跟随仓库后续更新。路径相对于仓库根目录；本地阅读时也可据此定位。

| 源文件 | 固定版本链接 |
|---|---|
| `data/worldgen/preset/PresetNoiseRouterData.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/PresetNoiseRouterData.java#L35) |
| `data/worldgen/preset/PresetSurfaceRuleData.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/PresetSurfaceRuleData.java#L20) |
| `data/worldgen/preset/settings/Presets.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/Presets.java#L20) |
| `data/worldgen/preset/settings/RiverSettings.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/RiverSettings.java#L53) |
| `data/worldgen/preset/settings/WorldSettings.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java#L141) |
| `world/worldgen/GeneratorContext.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/GeneratorContext.java#L25) |
| `world/worldgen/cell/Cell.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/Cell.java#L66) |
| `world/worldgen/cell/continent/ContinentLerper2.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/continent/ContinentLerper2.java#L30) |
| `world/worldgen/cell/continent/ContinentLerper3.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/continent/ContinentLerper3.java#L36) |
| `world/worldgen/cell/continent/simple/SimpleRiverGenerator.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/continent/simple/SimpleRiverGenerator.java#L21) |
| `world/worldgen/cell/continent/uplift/UpliftContinentGenerator.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/continent/uplift/UpliftContinentGenerator.java#L165) |
| `world/worldgen/cell/heightmap/Heightmap.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/heightmap/Heightmap.java#L85) |
| `world/worldgen/cell/heightmap/Levels.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/heightmap/Levels.java#L16) |
| `world/worldgen/cell/rivermap/ContinentalHydrology.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/ContinentalHydrology.java#L135) |
| `world/worldgen/cell/rivermap/RiverCache.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/RiverCache.java#L18) |
| `world/worldgen/cell/rivermap/lake/LakeConfig.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/lake/LakeConfig.java#L31) |
| `world/worldgen/cell/rivermap/river/BaseRiverGenerator.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/BaseRiverGenerator.java#L67) |
| `world/worldgen/cell/rivermap/river/Network.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/Network.java#L18) |
| `world/worldgen/cell/rivermap/river/RiverWarp.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/RiverWarp.java#L37) |
| `world/worldgen/cell/rivermap/river/UpliftRiverCarver.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/UpliftRiverCarver.java#L291) |
| `world/worldgen/cell/rivermap/wetland/Wetland.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/wetland/Wetland.java#L47) |
| `world/worldgen/cell/terrain/Blender.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Blender.java#L29) |
| `world/worldgen/cell/terrain/Populators.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java#L354) |
| `world/worldgen/cell/terrain/populator/TerrainPopulator.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/populator/TerrainPopulator.java#L16) |
| `world/worldgen/cell/terrain/populator/VolcanoPopulator.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/populator/VolcanoPopulator.java#L76) |
| `world/worldgen/cell/terrain/provider/TerrainProvider.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/provider/TerrainProvider.java#L67) |
| `world/worldgen/cell/terrain/region/RegionLerper.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/region/RegionLerper.java#L17) |
| `world/worldgen/cell/terrain/region/RegionModule.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/region/RegionModule.java#L32) |
| `world/worldgen/cell/terrain/region/RegionSelector.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/region/RegionSelector.java#L30) |
| `world/worldgen/densityfunction/tile/filter/Erosion.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/densityfunction/tile/filter/Erosion.java#L76) |
| `world/worldgen/densityfunction/tile/filter/Smoothing.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/densityfunction/tile/filter/Smoothing.java#L17) |
| `world/worldgen/densityfunction/tile/filter/Steepness.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/densityfunction/tile/filter/Steepness.java#L15) |
| `world/worldgen/densityfunction/tile/generation/TileGenerator.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/densityfunction/tile/generation/TileGenerator.java#L50) |
| `world/worldgen/noise/NoiseUtil.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/NoiseUtil.java#L164) |
| `world/worldgen/noise/domain/DirectionWarp.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/domain/DirectionWarp.java#L18) |
| `world/worldgen/noise/domain/DomainWarp.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/domain/DomainWarp.java#L18) |
| `world/worldgen/noise/module/AdvancedTerrace.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/AdvancedTerrace.java#L22) |
| `world/worldgen/noise/module/Billow.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Billow.java#L32) |
| `world/worldgen/noise/module/Blend.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Blend.java#L31) |
| `world/worldgen/noise/module/Cubic.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Cubic.java#L22) |
| `world/worldgen/noise/module/Erosion.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Erosion.java#L80) |
| `world/worldgen/noise/module/Noises.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Noises.java#L100) |
| `world/worldgen/noise/module/Perlin.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Perlin.java#L29) |
| `world/worldgen/noise/module/PerlinRidge.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/PerlinRidge.java#L32) |
| `world/worldgen/noise/module/ShiftSeed.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/ShiftSeed.java#L14) |
| `world/worldgen/noise/module/Simplex.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Simplex.java#L66) |
| `world/worldgen/noise/module/Steps.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Steps.java#L20) |
| `world/worldgen/noise/module/Terrace.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Terrace.java#L24) |
| `world/worldgen/noise/module/WorleyEdge.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/WorleyEdge.java#L47) |
| `world/worldgen/surface/rule/StrataRule.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/surface/rule/StrataRule.java#L135) |
| `world/worldgen/util/Seed.java` | [查看源码](https://github.com/ETcodehome/FreeTerraForged/blob/43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a/common/src/main/java/raccoonman/reterraforged/world/worldgen/util/Seed.java#L12) |
