# AE2 VM 1.12

**AE2 VM 1.12** 是 [AE2-VM](https://github.com/TaoLe-si/AE2-VM)(Tao 著,NeoForge 1.21.1)向**Minecraft 1.12.2 / Cleanroom / AE2 Unofficial Extended Life v0.56.7** 的功能移植:把 AE2 原本的**递归合成树遍历**替换为**"样板预编译字节码 + 栈式虚拟机 + JIT Bundle 缓存"**,为深度嵌套/指数递归的大型合成请求提供亚秒级计算(原版基准:90 秒 → 38 毫秒)。

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

原版在 `CraftingService.beginCraftingCalculation` 层拦截并返回自建 `ICraftingPlan`; 1.12 的 `CraftingCPUCluster.submitJob` 只接受内部 `CraftingJob` 类型(instanceof 检查),因此本移植采用**根节点替换**:`CraftingJobMixin` 把作业根节点换成 `VMRootNode`,其 `request()` 运行 VM、`setJob()/getPlan()/dive()` 把 VM 计划喂回 AE2 原生作业生命周期(模拟通过、缺料展示、CPU 执行),失败时回退原生树。键类型相应从 `AEKey` 换为 `IAEItemStack` 类型键(equals = isSameType)。

## 测试(164/164 全绿)

`gradlew build` 内置 JUnit5 语义测试(测试源集以 JDK 17 工具链编译运行,不进发布 jar)。原仓库的可移植测试族已全部落地,断言与源语义逐条对拍:

- **语义基础**(VmSemanticsTest / VmSemantics2Test,15 例):标志物种子、递归放大器净增修正(含无种子恰报缺 1)、催化剂反馈环 working-capital、耐久工具闭式(含无工具不可行)、耐久链、子项库存感知、换算环守恒、原料短缺报量、斐波那契 11 级链(O(patterns))、JIT 跨请求复用、自增长剪枝(有库存/无库存)、数量 1 边界、替代槽(FUZZY_SLOT)、处理配方默认模糊。
- **CrossRequestCacheTest**(11 例):同 VM 跨请求确定性矩阵(相同请求/缺额分支/多步库存消耗/数量变化/深链/菱形/双 VM 隔离/冷热 VM 等价/空库存重捕/空库存斐波那契/24 级 10^9 深链可合成项绝不报缺)。
- **RecursionReferenceTest**(6 例):放大器与 A-A 精华催化剂 × 最小可行/无界库存/无种子恰报缺 1。
- **CatalystFeedbackLoopTest**(6 例):raw 平衡环(A→2B→E+D→A)与 lossy 递减环(3A→2B→D+2A)× 三种库存模式,lossy 恰报缺 2 启动态。
- **DurabilityToolTest**(3 例):100 用工具 ×10000 点火闭式边界(100 把而非 10000/1,少 1 把恰报缺 1)。
- **JitReuseTest**(4 例):复用 VM 的正确性(常规复用/后续短缺/realStockCache 跨请求刷新/可合成子项库存重读)。
- **模糊族**(VideoFuzzyReplacementRepro 5 + FuzzyGroupRegistration 3 + FuzzyDiag 3 + ProcessingDefaultFuzzy 2):v1.10.5 精确槽位 vs 替代槽需求分离(替代品库存只满足FUZZY_SLOT 需求,精确槽绝不吞替代品)、替代组注册/未注册语义、可合成子项部分库存仍排程子合成、处理配方同物品 NBT 变体(实际变体记入 usedItems)。
- **边界族**(StockAwareSubCraftRepro 2 例 42 参数化 + CraftableFluidStockRepro 1 例 35参数化 + FluidBucketBoundary 2 + QuantityOneBoundary 2 + VmBridgeSpike 1):库存感知子合成 off-by-one 矩阵、可合成流体最后一份送达、x1/x2 边界、子项/流体部分库存。
- **VMTest**(6 例):字节码 Builder / CALL_BY_KEY / 请求包裹 / DIV_ROUNDUP 单元测试。

- **流体兼容面**(AE2FCCompatTest 8 例):以**真实 AE2 Fluid Craft Rework 类**为测试对象(纯 JVM 下手工填充 ObjectHolder 字段并注册 fake-item handler):drop 的 NBT 身份与mB 承载(AE 栈大小)、long 数量解码(identity-only ItemStack 探测)、包键 NBT 编码与超量拒绝、编译期包键→drop 规范化,以及以真实 AE2 物品键驱动的"可合成流体部分库存" VM 全链路(库存感知切分 + ceil 缺口补合成)。
- **能力总闸套件**(Ae2VmReferenceCapabilitySuite 39 例 + Ae2VmBoundaryCapabilitySuite 37 例 + FalsePositiveDiagnostic 1 例):经 1.12 翻译层(`Ae2VmReferencePlanner`)驱动 VM 走完Thunderbolt 参考套件全部 13 族 × 3 库存模式。参考图的 1.21 概念按真实 1.12 样板形状编码:催化剂(`returned`)→ 等量同键副产物(严格输出相等 → CATALYST_SEED,标志物/精华形状);耐久(`finiteUse`)→ maxDamage=uses 的可损伤工具 + 损伤 +1 副产物(损伤差探测);宿主复用库存(`returnedFrom`)→ 槽位替代变体 + 宿主池并入网络库存。**能力面结论:39 例全部稳定 SUPPORTED(含曾经的 FALSE_POSITIVE `multi-dag/fibonacci/minimum`,由多样板分配求解器确认零缺口闭合)** (详见"多样板分配求解器"一节)。Boundary 套件断言全部 37 例的 expectedFeasible:数量边界(x1/x2/x100)、模糊主产物 + 白库存/灰部分库存/无变体库存、10/20 级深链中段库存、可合成流体部分库存(x1/2/100)。

- **多样板求解回归**(MultiPatternSolverTest 5 例):fibonacci 族与 greedy-trap 族三模式全部 SUPPORTED(即 39/39 的引擎级钉死),以及求解器机制三断言(采纳严格更优混合、可行贪心快路径零改动、等缺口不换配比)。

测试 harness(`com.ae2vm.bench`):无 bootstrap 的 `IAEItemStack` fake、配方 fake(槽位级替代)、沙盒 fake(非破坏性网络视图 + 类型精确插入缓存,与 `NetworkCraftingSandbox` 同语义),可在纯 JVM 下验证全部规划语义。[TB-ThirdParty](https://github.com/TaoLe-si/TB-ThirdParty) 的纯 Java 规划器(`com.moakiee.thunderbolt.core.planner`,23 文件)已 vendor 进测试源集,作为参考对拍的基线设施。

移植期间由对拍测试暴露并修复的引擎语义缺口(主代码):

- 自返回催化剂种子可被模式自身副产物"自满足" → 种子提取移至本 bundle 副产物插入之前(启动资金必须来自网络或更早应用的其它模式);
- 精确槽位的处理默认模糊误吞跨物品替代组 → 拆分 `nbtFamilyOf`(同物品变体,任意处理槽)与 `fuzzyFamilyOf`(替代组,仅 FUZZY_SLOT);
- 无 IGrid 句柄时启动库存快照缺替代组/NBT 变体 → `snapshotExecuteStartStock` 扩展枚举。

### 主线基建:JIT 缓存按样板限定(2026-09)

`CraftingVM` 的 bundle 缓存键从"键"升级为"(键, 样板编译字节码)",bundle 记录捕获时每个直接子调用解析到的样板(传递合并子 bundle 的记录),回放前校验当前解析与记录一致,不一致即强制重新捕获。这同时修复了一个独立的隐患:同一网络的 VM 实例跨请求持久,玩家增删/改样板后,旧缓存会把**已不存在或已更换的样板**的冻结子树回放进新计划。多条样板的 bundle 就此可并存,读取经 `activeBundles()` 取"当前解析样板"的条目;选择稳定的常规请求 JIT 命中率不受影响。这也是多样板分配求解器(见下节)的基建。

## 多样板分配求解器(PatternChoiceRepair)

**问题**(原项目唯一遗留的引擎级未解问题,即参考套件中自 v1.9.6 起唯一的FALSE_POSITIVE `multi-dag/fibonacci/minimum`):VM 的解析器对每个输出键只解析**一条**样板(单次产出最小者优先,平局按注册顺序)且从不再回看。当同一产物存在多条样板时,贪心选样可能对某叶子超量需求——报出本可避免的缺料,或得到更差的配比。原作将 Thunderbolt 预算化回溯规划器的整体移植评估为"高风险、暂缓",其试过的"局部最小叶子代价"启发式也因破坏 greedy-trap 场景而回退。

**思路:求解分配,再把拆分编码为数据。** 贪心首轮逐字节保持原行为;仅当其报缺时,求解器(`com.ae2vm.vm.PatternChoiceRepair`,生产入口 `AE2VMCrafting.calculate` 与参考套件 `Ae2VmReferencePlanner` 共用同一 `Pass` 抽象)分三步:

1. **单选择枚举**:候选样板的"每合成消耗输入"(排除 returned/催化剂种子)构成线性需求系统,从根按拓扑级联;对全部争用键(≤12)的 2^n 组合做纯代数求值(零引擎开销),并保留**全部并列最优**(上限 8 个)作为后续精修起点——哪个最优能长出混合拆分因图而异,单一起点可能每个单步移动都非改进。
2. **拆分权重局部搜索**:每个争用键带一个权重向量,其需求数按**最大余数法**分摊到各候选样板——混合拆分(如 X3 的 5 次合成 = 4×A + 1×B)由此可表达。局部搜索每次在两个候选间转移一个权重单位,或对单一候选加一单位(ADD,从单热点状态表达 4:1 这类配比);等值移动只推进搜索位置(有界),严格改进才进入解;拆分限定在等单次产出的候选集上。visited 键按行 **GCD 规约**——权重是配比,等比状态(如 [0,2] 与 [0,1])是同一分配,不规约会被 ADD 移动的无穷等比阶梯耗尽横向预算。
3. **虚拟样板合成 + 确认**:每个起点的搜索结果取全局最优后,混合权重被合成为一条**虚拟样板**(`VirtualPatternDetails`:输入 = Σ 份额×各成分输入,产出 = 合并批量)——拆分由此变成纯数据,执行循环、bundle 缓存、聚合与库存感知语义全部原样工作,引擎零改动。虚拟样板经普通偏好机制交给解析器,一次真实重放确认**严格更少**的总缺失才采纳;模型失真只会确认失败并保留原计划。

**效果**:参考套件 `multi-dag/fibonacci/minimum` 缺口 **4 → 0**,三库存模式**39/39 稳定 SUPPORTED**(连续多次全新 JVM 运行零波动)。该场景的最优库存{X0=1, X1=9, X2=11} 由独立穷举证明**不可被任何纯单选择分配精确实现**(纯最优缺口 1),只能由混合份额 X3 = 4×A + 1×B 精确匹配(X0 = 份额 B 数, X1 = 4+A+B, X2 = 7+A 恒等式)——这正是原作预期需要"预算化回溯规划器"才能关闭的FALSE_POSITIVE。逐场景决策链追踪工具 `TraceSimulationState` 保留在测试源集(接线点见 `Ae2VmReferencePlanner` 注释)。

**代价与确定性**:求解仅在首轮回放报缺时发生(成功请求零开销);枚举 + 局部搜索为纯代数(毫秒级),外加一次确认重放;全部遍历走插入序/记录序,visited 防振荡,同一网络状态下结果确定。虚拟样板按键缓存最近一份(`PatternCompiler` 会保留旧编译产物,长期高频场景存在少量驻留,已加注释说明)。

## 尚未完成 / 未移植

- **异步 API 形态**:原版 `CompletableFuture<ICraftingPlan>`;1.12 作业模型为同步 Future,现为同步 `calculate`(功能等价,形态不同)。原 README 文档化的第三方 API 面已对齐:`isLoaded()`、`calculateSync()`(即同步 `calculate` 的命名别名)与 `AE2VMCraftingRegistry` 均已提供
- **批量余数消费方**:`getBatchRemainder()` 已暴露(请求超过 Long.MAX_VALUE 的余量),但 1.12 无原版 Thunderbolt/ECO 式的后续追加消费方
- **文档**:`CHANGELOG.md`、README_en 未建

### 确认跳过(与原版的刻意差异)

- Thunderbolt-Core **运行时路由层**(mod 本体):仅存在于 NeoForge 1.21.1,1.12 无构建也无法运行;原版以"注册引擎"方式借它路由计算请求,本移植的根节点替换已原生完成同等接入,故无需路由层。注意区分:Thunderbolt 的 **planner 纯 Java 规划库不受此限**,已 vendor 进测试源集作参考对拍基线(见"测试"一节)
- Cloth Config UI:已用 Forge Config 替代
- blocked-mods 崩溃逻辑(原版作者针对特定 mod 的对抗功能,1.12 无对应物)
- `ae2lt-recipes/` 基准配方 JSON(1.21 语义资产,不可直接复用)

## 构建

需要网络与 JDK 17 工具链(Gradle 自动供应);运行环境为 Cleanroom(JDK 21+/25):

```bash
./gradlew build      # 含测试;产物在 build/libs/
```

依赖经 cursemaven 自动解析:AE2UEL v0.56.7(latest;CurseForge 1.12.2 线自首个构建 v49t 起的全部历史版本均兼容)、AE2 Fluid Crafting Rework(**AE2-UEL 版 2.6.6-r 为兼容基准**;Circulate233 unofficial fork 的 `FakeFluids`/`FakeItemRegister`/`FCItems` API 与其一致,运行期安装任一 fork 均受支持)、AE2CT、Baubles(测试)。代理环境可经 `GRADLE_OPTS` 传入。

## License

LGPL-3.0(与原版 AE2-VM 一致)。版权归原版作者 Tao 及各参考项目(AE2-Quick-Calculation、RandomComplement、Thunderbolt-Core)所有。
