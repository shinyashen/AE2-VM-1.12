English | [简体中文](README_zh-CN.md)

# AE2 VM 1.12

![CI](https://github.com/shinyashen/AE2-VM-1.12/actions/workflows/ci.yml/badge.svg)
![Release](https://img.shields.io/github/v/release/shinyashen/AE2-VM-1.12)
[![CurseForge](https://img.shields.io/curseforge/v/1692278)](https://www.curseforge.com/minecraft/mc-mods/ae2-vm-legacy)
[![CurseForge downloads](https://img.shields.io/curseforge/dt/1692278)](https://www.curseforge.com/minecraft/mc-mods/ae2-vm-legacy)
![Minecraft](https://img.shields.io/badge/Minecraft-1.12.2-blue)
![License](https://img.shields.io/github/license/shinyashen/AE2-VM-1.12)

**AE2 VM 1.12** replaces Applied Energistics 2's recursive crafting-tree traversal with a **pre-compiled pattern bytecode + stack-based virtual machine + JIT bundle cache**. It is an **unofficial fork** of Tao's [AE2-VM](https://github.com/TaoLe-si/AE2-VM) (NeoForge 1.21.1), ported to **Minecraft 1.12.2 / Cleanroom / AE2 Unofficial Extended Life v0.56.x**.

Crafting requests that stall the vanilla planner for minutes — deeply nested, exponentially recursive patterns — are planned in **milliseconds** (upstream benchmark: ~90 s → ~38 ms, ~2,400×), and stack overflows are impossible by construction.

## Why it's fast *and* correct

- **O(patterns + edges) demand propagation** over the pattern graph instead of node-by-node expansion; JIT bundles capture each pattern once and replay it scaled in O(1)
- **BigInteger stack** — 10^9-scale chains, amount-1 boundaries, no overflow, no recursion
- **Stock-aware planning** — inventory is consumed first, shortfalls crafted; catalyst feedback loops and conversion rings get exact semantics
- **Amplifying crafting rings** — circular recipes that net-grow per round (a self-feeding loop whose outputs outweigh its inputs) are detected, solved to the material-minimal counts and folded into one bundle; byproduct intermediates, external inputs, byproduct-rooted orders and coupled ring chains are all covered, and startup working capital is reported exactly

  **Currently disabled** — the ring family ships switched off in 1.1.1: on some modpacks, ordering an item whose recipe path contains a ring product could stall the crafting CPU. It will return behind a config toggle once the cause is fixed; all other planning (stock-aware propagation, byproduct routing, catalyst loops) is unaffected.
- **Guards everywhere** — dead-cycle pre-pruning, composite-key JIT cache against stale patterns, plan memoization re-verified against live stock, and a solver confirmation gate that can never make a plan worse
- **Multi-pattern assignment solver** — when one output has several patterns, mixed splits (5 crafts = 4×A + 1×B) are solved algebraically and encoded as a virtual pattern; this closed upstream's only remaining false-positive (carried since v1.9.6)
- **Diagnostics built in** — opt-in job tracing (`/ae2vm trace`), automatic item pseudonymization, hash-chained trace files, and a faithful offline crafting-CPU simulator that classifies stalls (S1/S2/S4) with per-input evidence. See the wiki ([Diagnostics](https://github.com/shinyashen/AE2-VM-1.12/wiki/Diagnostics-en))
- **Compatibility** — AE2FC fluid patterns, AE2CT display trees, third-party scaled-pattern wrappers, with automatic fallback to the vanilla tree if the VM can't handle a request

Full details: [Features](https://github.com/shinyashen/AE2-VM-1.12/wiki/Features-en) · [Architecture](https://github.com/shinyashen/AE2-VM-1.12/wiki/Architecture-en) · [Multi-pattern solver](https://github.com/shinyashen/AE2-VM-1.12/wiki/MultiPatternSolver-en)

## Performance

All 39 reference-suite scenarios × 3 stock modes are **SUPPORTED** with zero flakiness; the deep-chain micro-benchmark plans a Fibonacci-32 chain at 10^9 scale in **~0.6 ms** (hot median). Every release automatically publishes fresh benchmarks to the wiki:

**[Performance & benchmarks →](https://github.com/shinyashen/AE2-VM-1.12/wiki/Performance-en)**

## Installation

| Requirement | Version |
|---|---|
| Minecraft | 1.12.2 |
| Mod loader | [Cleanroom](https://github.com/CleanroomMC/Cleanroom) (recommended); or Forge + [MixinBooter](https://www.curseforge.com/minecraft/mc-mods/mixin-booter) |
| Applied Energistics | [AE2 Unofficial Extended Life](https://www.curseforge.com/minecraft/mc-mods/ae2-extended-life) (compatible with every 1.12.2 build of AE2UEL) |

Download `ae2_vm_112-x.y.z.jar` from [Releases](https://github.com/shinyashen/AE2-VM-1.12/releases) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/ae2-vm-legacy), drop it into `mods/` — done. The VM takes over crafting calculations automatically; `proxyEnabled` in the config turns it off.

> On the Forge route: the mod uses no Cleanroom-specific APIs and ships Java 8 bytecode, with mixins provided by MixinBooter, so it is expected to work out of the box. This route is not covered by our automated tests — feedback is welcome.

## Documentation

The [wiki](https://github.com/shinyashen/AE2-VM-1.12/wiki/Home-en) carries the full documentation (Chinese-primary, English available on every page):

| Page | Contents |
|---|---|
| [Home](https://github.com/shinyashen/AE2-VM-1.12/wiki/Home-en) | Overview & quick start |
| [Features](https://github.com/shinyashen/AE2-VM-1.12/wiki/Features-en) | Every ported feature and how it works |
| [Architecture](https://github.com/shinyashen/AE2-VM-1.12/wiki/Architecture-en) | Root-node replacement, bytecode, JIT cache, guard system |
| [Multi-pattern solver](https://github.com/shinyashen/AE2-VM-1.12/wiki/MultiPatternSolver-en) | Enumeration + local search + virtual patterns |
| [Diagnostics](https://github.com/shinyashen/AE2-VM-1.12/wiki/Diagnostics-en) | Trace recording, evidence reporting, CPU simulation verdicts |
| [Performance](https://github.com/shinyashen/AE2-VM-1.12/wiki/Performance-en) | Methodology, optimization history, per-release benchmarks |
| [Testing](https://github.com/shinyashen/AE2-VM-1.12/wiki/Testing-en) | 209 tests and the differential-testing methodology |
| [Differences & roadmap](https://github.com/shinyashen/AE2-VM-1.12/wiki/Differences-and-Roadmap-en) | Deliberate deviations from upstream, gap status |

## Building

```bash
./gradlew build      # compiles and runs the full test suite (209 tests); artifacts in build/libs/
```

Requires a JDK 25 build JVM (RetroFuturaGradle 2.x requirement; the JDK 17 compile toolchain is provisioned automatically). Runtime is Cleanroom (JDK 21+) or Forge (Java 8 + MixinBooter). Dependencies resolve through CurseMaven (AE2UEL, AE2FC-Rework, AE2CT, Baubles).

## Offline trace replay (diagnostics)

Crafting orders can be recorded as self-contained, pseudonymous traces
(`/ae2vm trace record`, then place the order; `trace list` lines carry
clickable upload/download actions). A trace rebuilds the whole order
offline — grab two files from the release page and you are set:

```
java -cp ae2_vm_112-x.y.z.jar:ae2_vm_112-x.y.z-replay-shim.jar ^
     com.ae2vm.replay.ReplayMain trace-xxx.aevmtrace.json.gz [--no-diff] [--no-simulate]
```

- The **replay-shim jar** (attached to every release) bundles hand-written
  stubs of the few Minecraft/AE2 surfaces the replay touches plus a JSON
  parser — no real Minecraft jar, no mod jars, no dependency hunting
  (Windows: use `;` as the `-cp` separator).
- Exit code 0 = the current engine reproduces the recorded plan exactly;
  1 = a token-exact diff is printed (differences are evidence, not
  verdicts); 2 = failure; 3 = the built-in crafting-CPU simulation
  classifies the plan as a stall (S1/S2/S4, with per-input evidence). Same
  trace + same jars always produce identical output.

## License & Credits

This project is a fork of [AE2-VM](https://github.com/TaoLe-si/AE2-VM) — the crafting-VM architecture, instruction semantics and test baselines all originate there.

| Component | License | Attribution |
|---|---|---|
| This port (code) | LGPL-3.0 | shinyashen |
| Upstream AE2-VM (NeoForge 1.21.1) | LGPL-3.0 | Tao & [contributors](https://github.com/TaoLe-si/AE2-VM/graphs/contributors) |
| Logo | based on the AE2-VM icon | Tao |
| Thunderbolt planner (vendored into the test sourceset only, ships in no jar) | LGPL-3.0 | moakiee ([TB-ThirdParty](https://github.com/TaoLe-si/TB-ThirdParty)) |

Special thanks to:

- **Tao** for the original AE2-VM
- **NNYYOONNIIOO & contributors** for [AE2-Quick-Calculation](https://github.com/NNYYOONNIIOO/AE2-Quick-Calculation) (integration shell reference)
- **Circulate233** for [RandomComplement](https://github.com/Circulate233/RandomComplement) (build system reference)
- **AlgorithmX2 et al.** for Applied Energistics 2, and the **AE2UEL** team for the 1.12 platform ([AE2-UEL/Applied-Energistics-2](https://github.com/AE2-UEL/Applied-Energistics-2))
