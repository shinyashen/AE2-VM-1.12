# AE2 VM 1.12

**AE2 VM 1.12** 是 [AE2-VM](https://github.com/TaoLe-si/AE2-VM)(Tao 著,NeoForge 1.21.1)向
**Minecraft 1.12.2 / Cleanroom / AE2 Unofficial Extended Life v0.56.4** 的功能移植:
把 AE2 原本的**递归合成树遍历**替换为**"样板预编译字节码 + 栈式虚拟机 + JIT Bundle 缓存"**,
为深度嵌套/指数递归的大型合成请求提供亚秒级计算(原版基准:90 秒 → 38 毫秒)。

- 原版项目:Tao 的 [AE2-VM](https://github.com/TaoLe-si/AE2-VM)(LGPL-3.0)
- 集成外壳参考:[AE2-Quick-Calculation](https://github.com/NNYYOONNIIOO/AE2-Quick-Calculation) 已验证的 CraftingJob 根节点替换模式
- 构建体系参考:[RandomComplement](https://github.com/Circulate233/RandomComplement)(RetroFuturaGradle + Jabel + MixinBooter)
- 测试参考规划器:[TB-ThirdParty](https://github.com/TaoLe-si/TB-ThirdParty)(Thunderbolt-Core 下游,planner 已 vendor 进测试源集)

## 已移植功能

| 模块 | 说明 |
|---|---|
| 栈式 VM 引擎 | 18 条指令、BigInteger 无限精度栈、0-1023 值预分配、2 的幂快路径、顺序执行零递归 |
| JIT Bundle 缓存 | 1-craft 捕获 → O(1) scale 回放;子树经 needs 引用不折叠,缩放不重复计数;per-grid 跨请求持久 |
| 需求传播聚合 | 斐波那契式指数链 O(patterns+edges) 需求传播,共享 DAG 节点只回放一次 |
| 递归 / 自引用 | `A+B→2A` 放大器按净增修正合次数;自键收敛为一次性种子;无种子恰报缺 1 |
| 催化剂反馈环 | 副产物闭环 working-capital 精确种子(前向模拟 + 死锁注入,Tarjan SCC) |
| 换算环守恒 | 纯换算环 BigInteger 分数环值校验,无种子恰报最小价值键缺失 |
| 耐久工具 | `amount x ceil(times/uses)` 闭式;容器物品 / 损伤差 / NBT 数值迁移三种探测 |
| 模糊替换 | 替换组 + FUZZY_SLOT 槽位标记;处理配方默认同物品模糊族;精确槽/模糊槽分离 |
| 库存感知聚合 | 子项库存先用、缺口补合成;stock-aware 精确/模糊槽位切分 |
| 流体样板 | AE2FC-Rework 假物品键(drop 规范化,数量承载于 AE 栈大小),深层流体子合成可解析 |
| AE2CT 兼容 | VM 计划直接构建 LiteCraftTreeNode 显示树(@Pseudo mixin) |
| 公共 API | `AE2VMCrafting.calculate`(同步)、pickBestPattern 最小输出守卫、ignore-fix、T1/T3 解析 |
| 第三方门控 | `AE2VMCraftingRegistry` 注册表;未注册机器源保持原生行为 |
| 样板预编译 | 网格重算时全量预编译(`CraftingGridCacheMixin`) |
| 回退保障 | VM 无法处理时自动回退原生递归树;`proxyEnabled` 配置开关 |

## 与原版的架构差异

原版在 `CraftingService.beginCraftingCalculation` 层拦截并返回自建 `ICraftingPlan`;
1.12 的 `CraftingCPUCluster.submitJob` 只接受内部 `CraftingJob` 类型(instanceof 检查),
因此本移植采用**根节点替换**:`CraftingJobMixin` 把作业根节点换成 `VMRootNode`,
其 `request()` 运行 VM、`setJob()/getPlan()/dive()` 把 VM 计划喂回 AE2 原生作业生命周期
(模拟通过、缺料展示、CPU 执行),失败时回退原生树。
键类型相应从 `AEKey` 换为 `IAEItemStack` 类型键(equals = isSameType)。

## 测试(15/15 全绿)

`gradlew build` 内置 JUnit5 语义测试(测试源集以 JDK 17 工具链编译运行,不进发布 jar):

标志物种子、递归放大器净增修正(含无种子恰报缺 1)、催化剂反馈环 working-capital、
耐久工具闭式、耐久链、子项库存感知、换算环守恒、原料短缺报量、斐波那契 11 级链
(O(patterns))、JIT 跨请求复用、自增长剪枝(有库存/无库存)、数量 1 边界、
替代槽(FUZZY_SLOT)、处理配方默认模糊。

测试 harness(`com.ae2vm.bench`):无 bootstrap 的 `IAEItemStack` fake、
配方 fake、沙盒 fake,可在纯 JVM 下验证全部规划语义。
[TB-ThirdParty](https://github.com/TaoLe-si/TB-ThirdParty) 的纯 Java 规划器
(`com.moakiee.thunderbolt.core.planner`,23 文件)已 vendor 进测试源集,
作为参考对拍的基线设施。

## 尚未完成 / 未移植

### 测试(原版 136 用例,已移植 15;下表为剩余缺口)

| 缺口 | 用例数 | 说明 |
|---|---|---|
| `CrossRequestCacheTest` | 11 | 跨请求缓存一致性矩阵;harness 已就绪,优先移植 |
| `RecursionReferenceTest` 余量 | ~4 | 无界库存、A-A 精华变体等场景 |
| `CatalystFeedbackLoopTest` 余量 | ~4 | raw/lossy/balanced x 无界库存矩阵 |
| `JitReuseTest` 余量 | ~3 | cts=1 记忆化 / 缩放回放的内部断言 |
| `DurabilityToolTest` 余量 | ~2 | 100 用 x10000 点火边界 |
| `FuzzyDiagTest` / `FalsePositiveDiagnosticTest` | 4 | 模糊诊断与假阳性回归 |
| `QuantityOneBoundaryTest` 余量 / `FuzzyGroupRegistrationTest` | 1+3 | |
| `VideoFuzzyReplacementReproTest` | 5 | 需先补 grid 桩(`FakeBenchGrid` 等价物) |
| `ProcessingDefaultFuzzyTest` | 2 | 同上 |
| `StockAwareSubCraftReproTest` 余量 | 2 | 同上(含流体计数) |
| 能力总闸套件 x2 | ~76 | 参考规划器翻译层(`Ae2VmReferencePlanner` 的 1.12 版)——planner 已 vendor,缺 `AEKey/GenericStack → IAEItemStack` 翻转层 |
| `FluidBucketBoundaryTest` / `CraftableFluidStockReproTest` | 3 | **不可移植**:AE2FC 假物品需 MC 注册表运行时;此类语义列入实机验证 |

### 其它未完成

- **实机验证**:Cleanroom + AE2UEL 0.56.4(+AE2FCR/AE2CT)全链路下单、CPU 执行、产物入网、AE2CT 显示树
- **异步 API 形态**:原版 `CompletableFuture<ICraftingPlan>`;1.12 作业模型为同步 Future,现为同步 `calculate`(功能等价,形态不同)
- **批量余数消费方**:`getBatchRemainder()` 已暴露(请求超过 Long.MAX_VALUE 的余量),但 1.12 无原版 Thunderbolt/ECO 式的后续追加消费方
- **文档**:`CHANGELOG.md`、README_en 未建

### 确认跳过(与原版的刻意差异)

- Thunderbolt-Core **运行时路由层**(mod 本体):仅存在于 NeoForge 1.21.1,1.12 无构建也无法运行;
  原版以"注册引擎"方式借它路由计算请求,本移植的根节点替换已原生完成同等接入,故无需路由层。
  注意区分:Thunderbolt 的 **planner 纯 Java 规划库不受此限**,已 vendor 进测试源集作参考对拍基线(见"测试"一节)
- Cloth Config UI:已用 Forge Config 替代
- blocked-mods 崩溃逻辑(原版作者针对特定 mod 的对抗功能,1.12 无对应物)
- `ae2lt-recipes/` 基准配方 JSON(1.21 语义资产,不可直接复用)

## 构建

需要网络与 JDK 17 工具链(Gradle 自动供应);运行环境为 Cleanroom(JDK 21+/25):

```bash
./gradlew build      # 含测试;产物在 build/libs/
```

依赖经 cursemaven 自动解析:AE2UEL v0.56.4、AE2FC-Rework-Unofficial、AE2CT、Baubles(测试)。
代理环境可经 `GRADLE_OPTS` 传入。

## License

LGPL-3.0(与原版 AE2-VM 一致)。版权归原版作者 Tao 及各参考项目
(AE2-Quick-Calculation、RandomComplement、Thunderbolt-Core)所有。
