[English](README.md) | 简体中文

# AE2 VM 1.12

![CI](https://github.com/shinyashen/AE2-VM-1.12/actions/workflows/ci.yml/badge.svg)
![Release](https://img.shields.io/github/v/release/shinyashen/AE2-VM-1.12)
![Minecraft](https://img.shields.io/badge/Minecraft-1.12.2-blue)
![License](https://img.shields.io/github/license/shinyashen/AE2-VM-1.12)

**AE2 VM 1.12** 将应用能源2(AE2)原本的递归合成树计算替换为**"样板预编译字节码 + 栈式虚拟机 + JIT Bundle 缓存"**。本项目移植自 Tao 的 [AE2-VM](https://github.com/TaoLe-si/AE2-VM)(NeoForge 1.21.1),目标平台为 **Minecraft 1.12.2 / Cleanroom / AE2 Unofficial Extended Life v0.56.x**。

让原版规划器卡顿数分钟的深层嵌套、指数递归合成请求,现在**毫秒级**完成(上游基准:约 90 秒 → 约 38 毫秒),且从构造上杜绝栈溢出。

## 为什么又快又对

- **O(patterns + edges) 需求传播**:在样板关系图上传播需求而非逐节点展开;JIT bundle 对每条样板只捕获一次,任意数量 O(1) 回放
- **BigInteger 无限精度栈**:10^9 量级深链、数量 1 边界,无溢出、无递归
- **库存感知规划**:库存先用、缺口补合成;催化剂反馈环、耐久工具、换算环全部精确语义
- **守卫体系**:死环预剪、复合键 JIT 缓存防过期、计划记忆化对活库存复核、求解确认门(永远不可能让计划变差)
- **多样板分配求解器**:同一产物多条样板时,代数求解混合配比(5 次合成 = 4×A + 1×B)并编码为虚拟样板——上游自 v1.9.6 起唯一遗留的误报缺口由此关闭
- **兼容性**:AE2FC 流体样板、AE2CT 显示树、第三方缩放样板包装即插即兼容;VM 无法处理时自动回退原生树

详见:[功能明细](https://github.com/shinyashen/AE2-VM-1.12/wiki/Features) · [架构与设计](https://github.com/shinyashen/AE2-VM-1.12/wiki/Architecture) · [多样板分配求解器](https://github.com/shinyashen/AE2-VM-1.12/wiki/MultiPatternSolver)

## 性能

参考套件 39 场景 × 3 库存模式全部 **SUPPORTED**,多次全新 JVM 运行零波动;斐波那契 32 级 × 10^9 深链微基准热回放中位 **约 0.6 ms**。每个版本发布时 CI 自动推送最新基准到 wiki:

**[性能与基准数据 →](https://github.com/shinyashen/AE2-VM-1.12/wiki/Performance)**

## 安装

| 依赖 | 版本 |
|---|---|
| Minecraft | 1.12.2 |
| 模组加载器 | [Cleanroom](https://github.com/CleanroomMC/Cleanroom) |
| 应用能源 | [AE2 Unofficial Extended Life](https://www.curseforge.com/minecraft/mc-mods/ae2-extended-life) v0.56.7+ |

从 [Releases](https://github.com/shinyashen/AE2-VM-1.12/releases) 下载 `ae2_vm_112-x.y.z.jar`,放进 `mods/` 目录即可。VM 在合成计算时自动接管,无需任何手动操作;配置项 `proxyEnabled` 可整体关闭。

## 文档

[Wiki](https://github.com/shinyashen/AE2-VM-1.12/wiki) 承载完整文档(主中文,每页提供英文):

| 页面 | 内容 |
|---|---|
| [主页](https://github.com/shinyashen/AE2-VM-1.12/wiki) | 概述与快速开始 |
| [功能明细](https://github.com/shinyashen/AE2-VM-1.12/wiki/Features) | 全部已移植功能的机制说明 |
| [架构与设计](https://github.com/shinyashen/AE2-VM-1.12/wiki/Architecture) | 根节点替换、字节码、JIT 缓存、守卫体系 |
| [多样板分配求解器](https://github.com/shinyashen/AE2-VM-1.12/wiki/MultiPatternSolver) | 枚举 + 局部搜索 + 虚拟样板 |
| [性能](https://github.com/shinyashen/AE2-VM-1.12/wiki/Performance) | 方法学、历轮优化、发版自动基准 |
| [测试](https://github.com/shinyashen/AE2-VM-1.12/wiki/Testing) | 193 项测试与对拍方法论 |
| [差异与路线](https://github.com/shinyashen/AE2-VM-1.12/wiki/Differences-and-Roadmap) | 与原版的刻意差异、语义缺口状态 |

## 构建

```bash
./gradlew build      # 编译并运行全部测试(193 项);产物在 build/libs/
```

构建 JVM 需要 JDK 25(RetroFuturaGradle 2.x 要求;JDK 17 编译工具链自动供应)。运行环境为 Cleanroom(JDK 21+)。依赖经 CurseMaven 解析(AE2UEL、AE2FC-Rework、AE2CT、Baubles)。

## 致谢与许可

LGPL-3.0,与原版一致。致谢:

- **Tao** —— 原版 [AE2-VM](https://github.com/TaoLe-si/AE2-VM)
- **NNYYOONNIIOO 及贡献者** —— [AE2-Quick-Calculation](https://github.com/NNYYOONNIIOO/AE2-Quick-Calculation)(集成外壳参考)
- **Circulate233** —— [RandomComplement](https://github.com/Circulate233/RandomComplement)(构建体系参考)
- **moakiee** —— [TB-ThirdParty](https://github.com/TaoLe-si/TB-ThirdParty) 规划器(vendor 作测试参考)
