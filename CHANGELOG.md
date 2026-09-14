# 1.1.1 (unreleased, pending live validation)

## Diagnostics: trace, out-of-game replay and CPU simulation

- **Diagnostic tracing** (`/ae2vm trace record|list|show|upload|download`, server-side commands usable from vanilla clients): opt-in job tracing with armed sessions, token-pseudonymized item identities (the vault never leaves the server), hash-chained+payload-sealed gzipped traces under `logs/aevm/traces/`, mclo.gs upload for vanilla-client players, client download channel for modded ones, retention caps and en_us/zh_cn messages
- **Out-of-game replay**: the mod jar doubles as the replay CLI (`java -cp ae2_vm_112-x.y.z.jar com.ae2vm.replay.ReplayLauncher trace.json --deps <jar>...`); sub-pattern bytecode is embedded in traces (schema v2) so CALLs replay offline with zero compilation; plan diff reports identical/diverged
- **Faithful CPU simulation** (`--simulate`, on by default): the offline VirtualCPUCluster mirrors AE2UEL's CraftingCPUCluster operation-by-operation (exact processing extraction, waitingFor-gated injection, final-output delivery) and classifies non-completing plans as S1/S2/S4 stalls
- **Amplifying ring family ships disabled** (`ringSolverEnabled=false`): the faithful simulator proved the net-form ring plans lack the startup inventory a real CPU requires (t=0 deadlock) — the solver returns once it charges ring-member startup seeds; all ring tests now pin the faithful stall verdicts
- **Durability-tool amortization removed**: the upstream `ceil(times/uses)` tool accounting assumed the CPU re-consumes worn returns, which AE2UEL's exact processing extraction does not do (it burns one fresh tool per firing). Degrading tools now compile as ordinary gross inputs — plans demand the full tool count and complete honestly instead of stalling mid-job. Deliberate deviation from the 1.21 upstream, whose modern-AE2 runtime supports the amortization

# 1.1.0

## Ring solver: net-amplifying crafting rings

- **Amplifying rings (the gaia-loop family)** — cycles that net-grow per round (4 spirits → ingot → 12 spirits) are detected, solved algebraically to the material-minimal counts (least fixed point, solved from below) and folded into a single gross-flow bundle whose emissions are inserted before its extractions; the startup working capital is disclosed separately and exactly
- **Whole family coverage** — byproduct intermediates (a ring routed through a key that only exists as a byproduct), external-input rings (inputs without an in-ring producer are ordinary demand: shortfalls reported honestly), byproduct-rooted orders (ordering the byproduct directly drives the whole ring), and coupled ring chains (consumers-first solve order writes each ring's solved net draw into its supplier's floor)
- **Net-losing and conversion cycles stay with their dedicated machinery** — a two-pass gate (a stock-free structural pass, then the material pass) plus a material-closure adoption check keep lossy, conversion and catalyst loops under their exact existing guards; the 39-scenario reference suite is preserved verbatim
- **Accounting symmetry** — captured claims are now restored on revert (SimulationState.restock), so reverted speculative captures can no longer permanently spend network stock
- 209 tests, including exact per-pattern scheduling pins for every ring shape

# 1.0.0

First public release of the 1.12.2 port of AE2-VM.

## Highlights

- **Stack-based VM crafting calculator** for Applied Energistics 2 on Minecraft 1.12.2 (Cleanroom / AE2 Unofficial Extended Life v0.56.x) — replaces the recursive crafting-tree traversal with precompiled pattern bytecode + a JIT bundle cache; exponentially recursive recipes plan in milliseconds
- **Full capability face**: all 39 reference-suite scenarios × 3 stock modes SUPPORTED — including the zero-missing closure of upstream's only remaining false-positive via the multi-pattern assignment solver — plus 37/37 boundary checks; 193 tests total
- **Correctness guards**: dead-cycle pre-pruning, composite-key JIT cache against stale patterns, plan memoization re-verified against live stock, solver confirmation gate, stock-shortfall retry
- **Compatibility**: AE2FC fluid patterns, AE2CT display trees, scaled-pattern unwrapping, automatic fallback to vanilla planning; runs on Cleanroom (JDK 21+) or Forge with MixinBooter
