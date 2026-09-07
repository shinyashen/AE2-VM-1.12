# AE2 VM 1.12

**AE2 VM 1.12** 是 [AE2-VM](https://github.com/TaoLe-si/AE2-VM)(NeoForge 1.21.1)向 **Minecraft 1.12.2 + Cleanroom + AE2 Unofficial Extended Life v0.56.4** 的功能移植:把 AE2 原本的**递归合成树遍历**替换为**栈式虚拟机执行编译后的字节码**,为深度嵌套/指数递归的大型合成请求提供亚秒级计算。

- 原版项目:Tao 的 [AE2-VM](https://github.com/TaoLe-si/AE2-VM)(LGPL-3.0)
- 集成外壳参考:[AE2-Quick-Calculation](https://github.com/NNYYOONNIIOO/AE2-Quick-Calculation) 已验证的 CraftingJob 根节点替换模式
- 构建体系参考:[RandomComplement](https://github.com/Circulate233/RandomComplement)(RetroFuturaGradle + Jabel + MixinBooter)

## 已移植的功能

| 模块 | 说明 |
|---|---|
| 栈式 VM 引擎 | 18 条指令、BigInteger 无限精度、指数链 O(patterns) 需求传播聚合 |
| JIT Bundle 缓存 | 1-craft 捕获 → O(1) scale 回放,跨请求持久(per-grid) |
| 递归/自引用修正 | A+B→2A 放大器、A+B→A+C 精华催化剂的种子与净增修正 |
| 催化剂反馈环 | 副产物闭环的 working-capital 精确种子(前向模拟 + 死锁注入) |
| 换算环守恒 | 纯换算环 BigInteger 分数环值校验(Tarjan SCC) |
| 耐久工具 | `amount × ceil(times/uses)` 闭式(同物品损伤差迁移检测) |
| 模糊替换 | canSubstitute/getSubstituteInputs 替换组 + FUZZY_SLOT 槽位标记;处理配方默认同物品 NBT 族匹配 |
| 流体样板 | AE2 Fluid Craft Rework 假物品键(drop 归一化)覆盖 |
| AE2CT 兼容 | VM 计划直接构建 LiteCraftTreeNode 显示树(@Pseudo mixin) |
| 回退保障 | VM 无法处理时自动回退原生递归树;`proxyEnabled` 配置开关 |

## 与原版的架构差异

1.21 版在 `CraftingService.beginCraftingCalculation` 层拦截并返回自己的 `ICraftingPlan`;而 1.12 的 `CraftingCPUCluster.submitJob` 只接受内部 `CraftingJob` 类型(`instanceof` 检查),因此本移植采用**根节点替换**:`CraftingJobMixin` 把作业根节点换成 `VMRootNode`,其 `request()` 运行 VM、`setJob()/getPlan()/dive()` 把 VM 计划喂回 AE2 原生作业生命周期(模拟通过、缺料展示、CPU 执行),失败时回退原生树。

## 构建

需要 JDK 17 工具链(Gradle 自动供应)与网络;运行环境为 Cleanroom(JDK 21+/25):

```bash
./gradlew build
```

产物在 `build/libs/`。开发运行:`./gradlew runClient`(依赖由 cursemaven 自动解析:AE2UEL v0.56.4、AE2FCR-Unofficial、AE2CT)。

## 待办

- [ ] 移植基准测试子集(JUnit,参考原版 bench 语义:催化剂反馈环/斐波那契链/耐久闭式)
- [ ] 公共 API 入口(等价 AE2VMCrafting.calculate)
- [ ] 第三方 requester 注册表(AE2VMCraftingRegistry 白名单)

## License

LGPL-3.0(与原版 AE2-VM 一致)。版权归原版作者 Tao 及各参考项目所有。
