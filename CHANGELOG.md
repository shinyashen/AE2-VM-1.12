# 1.0.0

First public release of the 1.12.2 port of AE2-VM.

## Highlights

- **Stack-based VM crafting calculator** for Applied Energistics 2 on Minecraft 1.12.2 (Cleanroom / AE2 Unofficial Extended Life v0.56.x) — replaces the recursive crafting-tree traversal with precompiled pattern bytecode + a JIT bundle cache; exponentially recursive recipes plan in milliseconds
- **Full capability face**: all 39 reference-suite scenarios × 3 stock modes SUPPORTED — including the zero-missing closure of upstream's only remaining false-positive via the multi-pattern assignment solver — plus 37/37 boundary checks; 193 tests total
- **Correctness guards**: dead-cycle pre-pruning, composite-key JIT cache against stale patterns, plan memoization re-verified against live stock, solver confirmation gate, stock-shortfall retry
- **Compatibility**: AE2FC fluid patterns, AE2CT display trees, scaled-pattern unwrapping, automatic fallback to vanilla planning; runs on Cleanroom (JDK 21+) or Forge with MixinBooter
