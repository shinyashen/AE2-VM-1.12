# CurseForge 项目 Description(英文全文,直接粘贴)

> 使用说明:CF 描述编辑器支持 BBCode 与受限 Markdown;以下按 CF 常见的
> 标题/列表结构写成纯文本+标记,粘贴后逐一确认渲染。图片占位(截图)
> 过审后补传。`ae2vm_logo.png` 上传为项目 Logo,不放在正文。

---

AE2 VM 1.12 is an **unofficial fork** of Tao's [AE2-VM](https://github.com/TaoLe-si/AE2-VM) for modern Minecraft (NeoForge 1.21.1), ported to **Minecraft 1.12.2** with Cleanroom or Forge + MixinBooter, running on **AE2 Unofficial Extended Life**. Not affiliated with or endorsed by the upstream author — all credit for the original architecture goes to Tao; see Credits below.

## What it does

AE2 VM replaces Applied Energistics 2's recursive crafting-tree traversal with a **pre-compiled pattern bytecode + stack-based virtual machine + JIT bundle cache**. Crafting requests that stall the vanilla planner for minutes — deeply nested, exponentially recursive patterns — are planned in **milliseconds** (upstream benchmark: ~90 s → ~38 ms, ~2,400×). Stack overflows are impossible by construction: there is no recursion anywhere in the pipeline.

## Why it's fast AND correct

- **O(patterns + edges) demand propagation** over the pattern graph instead of node-by-node expansion; JIT bundles capture each pattern once and replay it scaled in O(1)
- **BigInteger arithmetic everywhere** — 10^9-scale chains, amount-1 boundaries, no overflow
- **Stock-aware planning** — network inventory is consumed first, shortfalls are crafted; catalyst feedback loops, durability tools and conversion rings all get exact semantics
- **Amplifying crafting rings** — circular recipes that net-grow per round (a self-feeding loop whose outputs outweigh its inputs) are detected, solved algebraically to the material-minimal counts and folded into a single plan; byproduct intermediates, external inputs, byproduct-rooted orders and coupled ring chains are all covered, and the startup working capital is reported exactly
- **Multi-pattern assignment solver** — when one output has several patterns, mixed splits (5 crafts = 4×A + 1×B) are solved algebraically and encoded as a virtual pattern; this closes upstream's only remaining false-positive (carried since v1.9.6)
- **Guards everywhere** — dead-cycle pre-pruning, stale-pattern-proof JIT cache keys, plan memoization re-verified against live stock, and a material-closure adoption gate for the ring solver: a solver can only ever be declined, never make a plan worse
- **Compatibility** — AE2FC fluid patterns, AE2CT display trees, third-party scaled-pattern wrappers, with automatic fallback to the vanilla planning tree if the VM cannot handle a request

## Performance

All 39 reference-suite scenarios × 3 stock modes are **SUPPORTED** with zero flakiness, plus 37/37 boundary checks; the deep-chain micro-benchmark plans a Fibonacci-32 chain at 10^9 scale in ~0.6 ms (hot median). Fresh benchmarks are published to the wiki automatically on every release.

## Getting started

1. Install Minecraft 1.12.2 with the Cleanroom loader (recommended), or Forge + MixinBooter
2. Install AE2 Unofficial Extended Life (this mod is compatible with every 1.12.2 build of AE2UEL)
3. Put this mod's jar into mods/ — done.

The VM takes over crafting calculations automatically; no setup, no config needed. Encode AE2 patterns as usual, request a craft from the terminal, and the VM accelerates the calculation transparently. To return to vanilla planning, set proxyEnabled=false in config/ae2_vm_112-common.toml.

Supported recipes: crafting patterns (molecular assemblers) and processing patterns of any depth, including the mega-recipes common in AE2 addon packs. Calculation runs on a background thread — no server main-thread lag.

> On the Forge route: the mod uses no Cleanroom-specific APIs and ships Java 8 bytecode, so it is expected to work out of the box. This route is not covered by our automated tests — feedback is welcome.

## Requirements

| Requirement | Version |
|---|---|
| Minecraft | 1.12.2 |
| Loader | Cleanroom (recommended) or Forge + MixinBooter |
| Applied Energistics 2 | AE2 Unofficial Extended Life (any 1.12.2 build) |

## Links

- Source code & issue tracker: https://github.com/shinyashen/AE2-VM-1.12
- Full documentation (bilingual wiki): https://github.com/shinyashen/AE2-VM-1.12/wiki
- Changelog: see the Files tab of each release

## Credits & license

This is a fork of [AE2-VM](https://github.com/TaoLe-si/AE2-VM) by **Tao** — the crafting-VM architecture, instruction semantics and test baselines all originate there (LGPL-3.0). Thanks also to NNYYOONNIIOO & contributors (AE2-Quick-Calculation, integration shell reference), Circulate233 (RandomComplement, build system reference), moakiee (TB-ThirdParty, test-only reference planner), AlgorithmX2 et al. for Applied Energistics 2, and the AE2UEL team for the 1.12 platform.

This port is released under **LGPL-3.0**, same as upstream.
