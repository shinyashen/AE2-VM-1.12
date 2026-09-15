[English](README.md) | 简体中文

# AE2 VM 1.12

![CI](https://github.com/shinyashen/AE2-VM-1.12/actions/workflows/ci.yml/badge.svg)
![Release](https://img.shields.io/github/v/release/shinyashen/AE2-VM-1.12)
[![CurseForge](https://img.shields.io/curseforge/v/1692278)](https://www.curseforge.com/minecraft/mc-mods/ae2-vm-legacy)
[![CurseForge downloads](https://img.shields.io/curseforge/dt/1692278)](https://www.curseforge.com/minecraft/mc-mods/ae2-vm-legacy)
![Minecraft](https://img.shields.io/badge/Minecraft-1.12.2-blue)
![License](https://img.shields.io/github/license/shinyashen/AE2-VM-1.12)

**AE2 VM 1.12** 把应用能源 2(AE2)原本的**递归合成树遍历**整个换成"**样板预编译字节码 + 栈式虚拟机 + JIT 缓存**"。它是 Tao 的 [AE2-VM](https://github.com/TaoLe-si/AE2-VM)(NeoForge 1.21.1)的**非官方 fork**,移植到 **Minecraft 1.12.2 / Cleanroom / AE2 Unofficial Extended Life v0.56.x** 平台。

动辄让原版规划器卡顿数分钟的深层嵌套、指数递归请求,现在毫秒级即可完成——上游实测约 90 秒 → 约 38 毫秒(约 2400 倍);全程无递归,不存在栈溢出。

## 核心特性

- **需求传播聚合**:不逐个展开合成树节点,而是沿样板关系图把全部需求一次传播到位,耗时只与样板数和依赖边数线性相关;每条样板只真实执行一次并记录,之后任意数量按比例直接回放
- **无限精度运算**:数量计算全部使用 BigInteger,10^9 量级的深链与数量 1 的边界均无溢出风险
- **库存感知**:优先消耗网络库存,缺口自动排程合成;催化剂反馈环、换算环等特殊配方均给出精确语义
- **净放大合成环**:互为投入产出、每轮净增产出的环形配方自动检测、代数求解至物料最小轮数并折叠为单一计划;副产物中间键、环外原料、副产物直接下单、跨环耦合等形态全部覆盖,启动垫资单独精确披露
  **当前版本已禁用**——部分整合包中,合成路径包含环产物的物品下单可能导致合成 CPU 卡死,1.1.1 起环家族默认关闭;问题定位修复后将以配置开关的形式回归,其余规划能力(库存感知、副产物路由、催化剂环)不受影响。
- **守卫体系**:死环提前剪除;样板变更后缓存立即失效;缓存的计划先与当前库存复核再放行;求解方案须实测确认更优方可采纳——性能优化不以牺牲正确性为代价
- **内置诊断**:按需录制订单轨迹(`/ae2vm trace`),自动伪名化脱敏,轨迹带哈希链防篡改;内置忠实复刻 AE2UEL 合成 CPU 的离线模拟器,可对停摆分类(S1/S2/S4)并给出逐输入证据。详见 wiki([诊断追踪与重放](https://github.com/shinyashen/AE2-VM-1.12/wiki/Diagnostics))
- **多样板分配求解**:同一产物存在多条样板时自动求解最优配比(如 5 次合成 = 4×样板A + 1×样板B)——上游自 v1.9.6 起唯一遗留的误报场景就此关闭
- **兼容性**:AE2FC 流体样板、AE2CT 合成预览、第三方缩放样板包装即插即用;VM 无法处理的请求自动回退原版逻辑

详见:[功能明细](https://github.com/shinyashen/AE2-VM-1.12/wiki/Features) · [架构与设计](https://github.com/shinyashen/AE2-VM-1.12/wiki/Architecture) · [多样板分配求解器](https://github.com/shinyashen/AE2-VM-1.12/wiki/MultiPatternSolver)

## 性能

参考套件 39 个场景 × 3 种库存模式全部支持,反复全新 JVM 运行结果零波动;斐波那契 32 级、10^9 量级的深链,热回放中位耗时约 0.6 毫秒。每个版本发布时,CI 会自动把最新基准数据推送到 wiki:

**[性能与基准数据 →](https://github.com/shinyashen/AE2-VM-1.12/wiki/Performance)**

## 安装

| 依赖 | 版本 |
|---|---|
| Minecraft | 1.12.2 |
| 模组加载器 | [Cleanroom](https://github.com/CleanroomMC/Cleanroom)(推荐);或 Forge + [MixinBooter](https://www.curseforge.com/minecraft/mc-mods/mixin-booter) |
| 应用能源 | [AE2 Unofficial Extended Life](https://www.curseforge.com/minecraft/mc-mods/ae2-extended-life)(兼容 AE2UEL 1.12.2 的全部历史构建) |

从 [Releases](https://github.com/shinyashen/AE2-VM-1.12/releases) 或 [CurseForge](https://www.curseforge.com/minecraft/mc-mods/ae2-vm-legacy) 下载 jar 放入 `mods/` 目录即可。VM 在合成计算时自动接管,无需任何配置;如需切回原版逻辑,关闭配置项 `proxyEnabled` 即可。

> 关于 Forge 路线:本模组未使用任何 Cleanroom 专属 API,产物为 Java 8 字节码,mixin 支持由 MixinBooter 提供,理论上开箱即用;该路线不在自动化测试覆盖范围内,遇到问题欢迎反馈。

## 文档

[Wiki](https://github.com/shinyashen/AE2-VM-1.12/wiki) 承载完整文档(主中文,每页附英文):

| 页面 | 内容 |
|---|---|
| [主页](https://github.com/shinyashen/AE2-VM-1.12/wiki) | 概述与快速上手 |
| [功能明细](https://github.com/shinyashen/AE2-VM-1.12/wiki/Features) | 全部已移植功能的机制说明 |
| [架构与设计](https://github.com/shinyashen/AE2-VM-1.12/wiki/Architecture) | 根节点替换、字节码、JIT 缓存、守卫体系 |
| [多样板分配求解器](https://github.com/shinyashen/AE2-VM-1.12/wiki/MultiPatternSolver) | 枚举 + 局部搜索 + 虚拟样板 |
| [诊断追踪与重放](https://github.com/shinyashen/AE2-VM-1.12/wiki/Diagnostics) | 轨迹录制、取证报障、CPU 模拟判定 |
| [性能](https://github.com/shinyashen/AE2-VM-1.12/wiki/Performance) | 测量方法、历轮优化、发版自动基准 |
| [测试](https://github.com/shinyashen/AE2-VM-1.12/wiki/Testing) | 209 项测试与对拍方法论 |
| [差异与路线](https://github.com/shinyashen/AE2-VM-1.12/wiki/Differences-and-Roadmap) | 与原版的刻意差异、语义缺口状态 |

## 构建

```bash
./gradlew build      # 编译并跑完全部测试(262 项);产物在 build/libs/
```

构建用的 JVM 需要 JDK 25(RetroFuturaGradle 2.x 的硬性要求;实际编译用的 JDK 17 工具链由 Gradle 自动下载)。运行环境为 Cleanroom(JDK 21+)或 Forge(Java 8 + MixinBooter)。依赖由 CurseMaven 自动拉取(AE2UEL、AE2FC-Rework、AE2CT、Baubles)。

## 离线轨迹推演(诊断)

合成订单可录制成自包含的伪名化轨迹(`/ae2vm trace record` 后下单;`trace list`
行内自带可点击的上传/下载按钮)。轨迹可完全离线重放——从 Release 页下载
两个文件即可:

```
java -cp ae2_vm_112-x.y.z.jar:ae2_vm_112-x.y.z-replay-shim.jar ^
     com.ae2vm.replay.ReplayMain trace-xxx.aevmtrace.json.gz [--no-diff] [--no-simulate]
```

- **replay-shim jar**(随每个 Release 附带)内置重放所需的少数 Minecraft/AE2
  表面的手写桩与 JSON 解析器——无需真实 Minecraft jar、无需任何 mod jar、
  无需寻找依赖(Windows 下 `-cp` 分隔符用 `;`)。
- 退出码 0 = 当前引擎逐 token 复现了记录的计划;1 = 打印 diff(差异是
  证据,不是判决);2 = 失败;3 = 内置合成 CPU 模拟判定停摆(S1/S2/S4,
  附逐输入证据)。同一轨迹 + 同一组 jar 输出恒相同。

## 许可与致谢

本项目是 [AE2-VM](https://github.com/TaoLe-si/AE2-VM) 的 fork——合成虚拟机的架构、指令语义与测试基线均承自上游。

| 组成部分 | 许可 | 归属 |
|---|---|---|
| 本移植(代码) | LGPL-3.0 | shinyashen |
| 上游 AE2-VM(NeoForge 1.21.1) | LGPL-3.0 | Tao 及[贡献者](https://github.com/TaoLe-si/AE2-VM/graphs/contributors) |
| 图标 | 基于 AE2-VM 图标修改 | Tao |
| Thunderbolt planner(仅 vendor 进测试源集,不随 jar 发布) | LGPL-3.0 | moakiee([TB-ThirdParty](https://github.com/TaoLe-si/TB-ThirdParty)) |

特别感谢:

- **Tao** —— 原版 AE2-VM
- **NNYYOONNIIOO 及贡献者** —— [AE2-Quick-Calculation](https://github.com/NNYYOONNIIOO/AE2-Quick-Calculation)(集成外壳参考)
- **Circulate233** —— [RandomComplement](https://github.com/Circulate233/RandomComplement)(构建体系参考)
- **AlgorithmX2 等人** 的 Applied Energistics 2,以及 **AE2UEL** 团队维护的 1.12 平台([AE2-UEL/Applied-Energistics-2](https://github.com/AE2-UEL/Applied-Energistics-2))
