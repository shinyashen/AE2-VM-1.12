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

## 测试(151/151 全绿)

`gradlew build` 内置 JUnit5 语义测试(测试源集以 JDK 17 工具链编译运行,不进发布 jar)。
原仓库的可移植测试族已全部落地,断言与源语义逐条对拍:

- **语义基础**(VmSemanticsTest / VmSemantics2Test,15 例):标志物种子、递归放大器净增修正
  (含无种子恰报缺 1)、催化剂反馈环 working-capital、耐久工具闭式(含无工具不可行)、
  耐久链、子项库存感知、换算环守恒、原料短缺报量、斐波那契 11 级链(O(patterns))、
  JIT 跨请求复用、自增长剪枝(有库存/无库存)、数量 1 边界、替代槽(FUZZY_SLOT)、
  处理配方默认模糊。
- **CrossRequestCacheTest**(11 例):同 VM 跨请求确定性矩阵(相同请求/缺额分支/多步库存
  消耗/数量变化/深链/菱形/双 VM 隔离/冷热 VM 等价/空库存重捕/空库存斐波那契/24 级 10^9 深链
  可合成项绝不报缺)。
- **RecursionReferenceTest**(6 例):放大器与 A-A 精华催化剂 × 最小可行/无界库存/无种子恰报缺 1。
- **CatalystFeedbackLoopTest**(6 例):raw 平衡环(A→2B→E+D→A)与 lossy 递减环
  (3A→2B→D+2A)× 三种库存模式,lossy 恰报缺 2 启动态。
- **DurabilityToolTest**(3 例):100 用工具 ×10000 点火闭式边界(100 把而非 10000/1,少 1 把恰报缺 1)。
- **JitReuseTest**(4 例):复用 VM 的正确性(常规复用/后续短缺/realStockCache 跨请求刷新/
  可合成子项库存重读)。
- **模糊族**(VideoFuzzyReplacementRepro 5 + FuzzyGroupRegistration 3 + FuzzyDiag 3 +
  ProcessingDefaultFuzzy 2):v1.10.5 精确槽位 vs 替代槽需求分离(替代品库存只满足
  FUZZY_SLOT 需求,精确槽绝不吞替代品)、替代组注册/未注册语义、可合成子项部分库存
  仍排程子合成、处理配方同物品 NBT 变体(实际变体记入 usedItems)。
- **边界族**(StockAwareSubCraftRepro 2 例 42 参数化 + CraftableFluidStockRepro 1 例 35
  参数化 + FluidBucketBoundary 2 + QuantityOneBoundary 2 + VmBridgeSpike 1):
  库存感知子合成 off-by-one 矩阵、可合成流体最后一份送达、x1/x2 边界、子项/流体部分库存。
- **VMTest**(6 例):字节码 Builder / CALL_BY_KEY / 请求包裹 / DIV_ROUNDUP 单元测试。

- **能力总闸套件**(Ae2VmReferenceCapabilitySuite 39 例 + Ae2VmBoundaryCapabilitySuite 37 例 +
  FalsePositiveDiagnostic 1 例):经 1.12 翻译层(`Ae2VmReferencePlanner`)驱动 VM 走完
  Thunderbolt 参考套件全部 13 族 × 3 库存模式。参考图的 1.21 概念按真实 1.12 样板形状编码:
  催化剂(`returned`)→ 等量同键副产物(严格输出相等 → CATALYST_SEED,标志物/精华形状);
  耐久(`finiteUse`)→ maxDamage=uses 的可损伤工具 + 损伤 +1 副产物(损伤差探测);
  宿主复用库存(`returnedFrom`)→ 槽位替代变体 + 宿主池并入网络库存。
  **能力面结论:39 例中 38 例 SUPPORTED**;唯一 FALSE_POSITIVE 为
  `multi-dag/fibonacci/minimum`(同键双样板无回退搜索,参考最小前沿假设最优选样)——
  **与原版 AE2-VM 的分类一致**,属原项目已知能力面限制,非移植引入。
  Boundary 套件断言全部 37 例的 expectedFeasible:数量边界(x1/x2/x100)、
  模糊主产物 + 白库存/灰部分库存/无变体库存、10/20 级深链中段库存、
  可合成流体部分库存(x1/2/100)。

测试 harness(`com.ae2vm.bench`):无 bootstrap 的 `IAEItemStack` fake、
配方 fake(槽位级替代)、沙盒 fake(非破坏性网络视图 + 类型精确插入缓存,
与 `NetworkCraftingSandbox` 同语义),可在纯 JVM 下验证全部规划语义。
[TB-ThirdParty](https://github.com/TaoLe-si/TB-ThirdParty) 的纯 Java 规划器
(`com.moakiee.thunderbolt.core.planner`,23 文件)已 vendor 进测试源集,
作为参考对拍的基线设施。

移植期间由对拍测试暴露并修复的引擎语义缺口(主代码):

- 自返回催化剂种子可被模式自身副产物"自满足" → 种子提取移至本 bundle 副产物插入之前
  (启动资金必须来自网络或更早应用的其它模式);
- 精确槽位的处理默认模糊误吞跨物品替代组 → 拆分 `nbtFamilyOf`(同物品变体,任意处理槽)
  与 `fuzzyFamilyOf`(替代组,仅 FUZZY_SLOT);
- 无 IGrid 句柄时启动库存快照缺替代组/NBT 变体 → `snapshotExecuteStartStock` 扩展枚举。

## 尚未完成 / 未移植

### 测试(剩余缺口)

| 缺口 | 用例数 | 说明 |
|---|---|---|
| 流体测试的 AE2FC 注册表面 | — | 数值语义已按"数量型键"移植;真实假物品键的注册表依赖列入实机验证 |

原仓库其余测试(含能力总闸套件 x2、FalsePositiveDiagnosticTest)已全部移植完毕。
`multi-dag/fibonacci/minimum` 的 FALSE_POSITIVE 为原项目已知限制(同键多样板无回退
搜索),与原版分类一致,不计入移植缺口。

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
