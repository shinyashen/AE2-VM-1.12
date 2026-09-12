# Unreleased

## Ring solver: net-amplifying crafting rings

- **Amplifying rings (the gaia-loop family)** — cycles that net-grow per round (4 spirits → ingot → 12 spirits) are detected, solved algebraically to the material-minimal counts (least fixed point, solved from below) and folded into a single gross-flow bundle whose emissions are inserted before its extractions; the startup working capital is disclosed separately and exactly
- **Whole family coverage** — byproduct intermediates (a ring routed through a key that only exists as a byproduct), external-fuel rings (fuel keys are ordinary demand: shortfalls reported honestly), byproduct-rooted orders (ordering the byproduct directly drives the whole ring), and coupled ring chains (consumers-first solve order writes each ring's solved net draw into its supplier's floor)
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
