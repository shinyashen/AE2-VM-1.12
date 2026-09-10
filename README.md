English | [简体中文](README_zh-CN.md)

# AE2 VM 1.12

![CI](https://github.com/shinyashen/AE2-VM-1.12/actions/workflows/ci.yml/badge.svg)
![Release](https://img.shields.io/github/v/release/shinyashen/AE2-VM-1.12)
![Minecraft](https://img.shields.io/badge/Minecraft-1.12.2-blue)
![License](https://img.shields.io/github/license/shinyashen/AE2-VM-1.12)

**AE2 VM 1.12** replaces Applied Energistics 2's recursive crafting-tree traversal with a **pre-compiled pattern bytecode + stack-based virtual machine + JIT bundle cache**. It is a port of Tao's [AE2-VM](https://github.com/TaoLe-si/AE2-VM) (NeoForge 1.21.1) to **Minecraft 1.12.2 / Cleanroom / AE2 Unofficial Extended Life v0.56.x**.

Crafting requests that stall the vanilla planner for minutes — deeply nested, exponentially recursive patterns — are planned in **milliseconds** (upstream benchmark: ~90 s → ~38 ms, ~2,400×), and stack overflows are impossible by construction.

## Why it's fast *and* correct

- **O(patterns + edges) demand propagation** over the pattern graph instead of node-by-node expansion; JIT bundles capture each pattern once and replay it scaled in O(1)
- **BigInteger stack** — 10^9-scale chains, amount-1 boundaries, no overflow, no recursion
- **Stock-aware planning** — inventory is consumed first, shortfalls crafted; catalyst feedback loops, durability tools and conversion rings get exact semantics
- **Guards everywhere** — dead-cycle pre-pruning, composite-key JIT cache against stale patterns, plan memoization re-verified against live stock, and a solver confirmation gate that can never make a plan worse
- **Multi-pattern assignment solver** — when one output has several patterns, mixed splits (5 crafts = 4×A + 1×B) are solved algebraically and encoded as a virtual pattern; this closed upstream's only remaining false-positive (carried since v1.9.6)
- **Compatibility** — AE2FC fluid patterns, AE2CT display trees, third-party scaled-pattern wrappers, with automatic fallback to the vanilla tree if the VM can't handle a request

Full details: [Features](https://github.com/shinyashen/AE2-VM-1.12/wiki/Features) · [Architecture](https://github.com/shinyashen/AE2-VM-1.12/wiki/Architecture) · [Multi-pattern solver](https://github.com/shinyashen/AE2-VM-1.12/wiki/MultiPatternSolver)

## Performance

All 39 reference-suite scenarios × 3 stock modes are **SUPPORTED** with zero flakiness; the deep-chain micro-benchmark plans a Fibonacci-32 chain at 10^9 scale in **~0.6 ms** (hot median). Every release automatically publishes fresh benchmarks to the wiki:

**[Performance & benchmarks →](https://github.com/shinyashen/AE2-VM-1.12/wiki/Performance)**

## Installation

| Requirement | Version |
|---|---|
| Minecraft | 1.12.2 |
| Mod loader | [Cleanroom](https://github.com/CleanroomMC/Cleanroom) (recommended); or Forge + [MixinBooter](https://www.curseforge.com/minecraft/mc-mods/mixin-booter) |
| Applied Energistics | [AE2 Unofficial Extended Life](https://www.curseforge.com/minecraft/mc-mods/ae2-extended-life) (compatible with every 1.12.2 build of AE2UEL) |

Download `ae2_vm_112-x.y.z.jar` from [Releases](https://github.com/shinyashen/AE2-VM-1.12/releases), drop it into `mods/` — done. The VM takes over crafting calculations automatically; `proxyEnabled` in the config turns it off.

> On the Forge route: the mod uses no Cleanroom-specific APIs and ships Java 8 bytecode, with mixins provided by MixinBooter, so it is expected to work out of the box. This route is not covered by our automated tests — feedback is welcome.

## Documentation

The [wiki](https://github.com/shinyashen/AE2-VM-1.12/wiki) carries the full documentation (Chinese-primary, English available on every page):

| Page | Contents |
|---|---|
| [Home](https://github.com/shinyashen/AE2-VM-1.12/wiki) | Overview & quick start |
| [Features](https://github.com/shinyashen/AE2-VM-1.12/wiki/Features) | Every ported feature and how it works |
| [Architecture](https://github.com/shinyashen/AE2-VM-1.12/wiki/Architecture) | Root-node replacement, bytecode, JIT cache, guard system |
| [Multi-pattern solver](https://github.com/shinyashen/AE2-VM-1.12/wiki/MultiPatternSolver) | Enumeration + local search + virtual patterns |
| [Performance](https://github.com/shinyashen/AE2-VM-1.12/wiki/Performance) | Methodology, optimization history, per-release benchmarks |
| [Testing](https://github.com/shinyashen/AE2-VM-1.12/wiki/Testing) | 193 tests and the differential-testing methodology |
| [Differences & roadmap](https://github.com/shinyashen/AE2-VM-1.12/wiki/Differences-and-Roadmap) | Deliberate deviations from upstream, gap status |

## Building

```bash
./gradlew build      # compiles and runs the full test suite (193 tests); artifacts in build/libs/
```

Requires a JDK 25 build JVM (RetroFuturaGradle 2.x requirement; the JDK 17 compile toolchain is provisioned automatically). Runtime is Cleanroom (JDK 21+) or Forge (Java 8 + MixinBooter). Dependencies resolve through CurseMaven (AE2UEL, AE2FC-Rework, AE2CT, Baubles).

## Credits & License

LGPL-3.0, same as the original. Credits to:

- **Tao** — original [AE2-VM](https://github.com/TaoLe-si/AE2-VM)
- **NNYYOONNIIOO & contributors** — [AE2-Quick-Calculation](https://github.com/NNYYOONNIIOO/AE2-Quick-Calculation) (integration shell reference)
- **Circulate233** — [RandomComplement](https://github.com/Circulate233/RandomComplement) (build system reference)
- **moakiee** — [TB-ThirdParty](https://github.com/TaoLe-si/TB-ThirdParty) planner (vendored for test reference)
