[English](README.md) | 简体中文

# AE2 VM 1.12

![CI](https://github.com/shinyashen/AE2-VM-1.12/actions/workflows/ci.yml/badge.svg)
![Release](https://img.shields.io/github/v/release/shinyashen/AE2-VM-1.12)
![Minecraft](https://img.shields.io/badge/Minecraft-1.12.2-blue)
![License](https://img.shields.io/github/license/shinyashen/AE2-VM-1.12)

**AE2 VM 1.12** 把应用能源 2(AE2)原本的**递归合成树遍历**整个换成**"样板预编译字节码 + 栈式虚拟机 + JIT 缓存"**。移植自 Tao 的 [AE2-VM](https://github.com/TaoLe-si/AE2-VM)(NeoForge 1.21.1),目标平台 **Minecraft 1.12.2 / Cleanroom / AE2 Unofficial Extended Life v0.56.x**。

原版规划器要卡上几分钟的深层嵌套、指数递归配方,现在毫秒级出结果——上游实测约 90 秒 → 约 38 毫秒(约 2400 倍)。执行过程完全不存在递归,栈溢出从根上就不会发生。

## 为什么又快又对

- **需求一次算清**:不逐个展开合成树节点,而是沿样板关系图把全部需求一次传播到位(耗时只与样板数、依赖边数线性相关);每条样板只真实执行一次并记下来,之后任意数量都按比例直接回放
- **无限精度运算**:数量计算全走 BigInteger 大整数,10^9 量级的深链、数量 1 的边界,都不存在溢出问题
- **库存感知**:先用手头库存,差多少补合成多少;催化剂反馈环、耐久工具、换算环这类特殊配方都给出精确结果
- **处处设防**:死环提前剪除;样板一改,缓存立即失效;缓存的计算结果先用当前库存复核再放行;求解器的方案必须实测确认更优才采纳——提速从不以牺牲正确性为代价
- **多样板求解**:同一个产物有多条样板时,自动算出最优搭配(比如 5 次合成拆成 4 次走样板 A、1 次走样板 B)——上游自 v1.9.6 起唯一悬而未决的误报场景就此关掉
- **开箱即用的兼容**:AE2FC 流体样板、AE2CT 合成预览、第三方的翻倍/缩放样板包装都能直接配合;遇到 VM 处理不了的请求,自动退回原版逻辑

详见:[功能明细](https://github.com/shinyashen/AE2-VM-1.12/wiki/Features) · [架构与设计](https://github.com/shinyashen/AE2-VM-1.12/wiki/Architecture) · [多样板分配求解器](https://github.com/shinyashen/AE2-VM-1.12/wiki/MultiPatternSolver)

## 性能

参考套件 39 个场景 × 3 种库存模式全部支持,反复全新 JVM 运行结果零波动;斐波那契 32 级、10^9 量级的深链,热回放中位耗时约 0.6 毫秒。每个版本发布时,CI 会自动把最新基准数据推到 wiki:

**[性能与基准数据 →](https://github.com/shinyashen/AE2-VM-1.12/wiki/Performance)**

## 安装

| 依赖 | 版本 |
|---|---|
| Minecraft | 1.12.2 |
| 模组加载器 | [Cleanroom](https://github.com/CleanroomMC/Cleanroom) |
| 应用能源 | [AE2 Unofficial Extended Life](https://www.curseforge.com/minecraft/mc-mods/ae2-extended-life) v0.56.7+ |

从 [Releases](https://github.com/shinyashen/AE2-VM-1.12/releases) 下载 jar,丢进 `mods/` 文件夹就完事。合成计算时 VM 自动接管,什么都不用配;想切回原版逻辑,把配置项 `proxyEnabled` 关掉即可。

## 文档

[Wiki](https://github.com/shinyashen/AE2-VM-1.12/wiki) 承载完整文档(主中文,每页附英文):

| 页面 | 内容 |
|---|---|
| [主页](https://github.com/shinyashen/AE2-VM-1.12/wiki) | 概述与快速上手 |
| [功能明细](https://github.com/shinyashen/AE2-VM-1.12/wiki/Features) | 全部已移植功能的来龙去脉 |
| [架构与设计](https://github.com/shinyashen/AE2-VM-1.12/wiki/Architecture) | 根节点替换、字节码、JIT 缓存、守卫体系 |
| [多样板分配求解器](https://github.com/shinyashen/AE2-VM-1.12/wiki/MultiPatternSolver) | 枚举 + 局部搜索 + 虚拟样板 |
| [性能](https://github.com/shinyashen/AE2-VM-1.12/wiki/Performance) | 测量方法、历轮优化、发版自动基准 |
| [测试](https://github.com/shinyashen/AE2-VM-1.12/wiki/Testing) | 193 项测试与对拍方法论 |
| [差异与路线](https://github.com/shinyashen/AE2-VM-1.12/wiki/Differences-and-Roadmap) | 与原版的刻意差异、语义缺口状态 |

## 构建

```bash
./gradlew build      # 编译并跑完全部测试(193 项);产物在 build/libs/
```

构建用的 JVM 需要 JDK 25(RetroFuturaGradle 2.x 的硬性要求;实际编译用的 JDK 17 工具链 Gradle 会自动下载)。运行环境为 Cleanroom(JDK 21+)。依赖由 CurseMaven 自动拉取(AE2UEL、AE2FC-Rework、AE2CT、Baubles)。

## 致谢与许可

LGPL-3.0,与原版一致。致谢:

- **Tao** —— 原版 [AE2-VM](https://github.com/TaoLe-si/AE2-VM)
- **NNYYOONNIIOO 及贡献者** —— [AE2-Quick-Calculation](https://github.com/NNYYOONNIIOO/AE2-Quick-Calculation)(集成外壳参考)
- **Circulate233** —— [RandomComplement](https://github.com/Circulate233/RandomComplement)(构建体系参考)
- **moakiee** —— [TB-ThirdParty](https://github.com/TaoLe-si/TB-ThirdParty) 规划器(vendor 作测试参考)
