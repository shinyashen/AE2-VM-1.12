# Git 历史重建映射表(草案 v1 — 待审)

- 原历史:103 提交(含 6 个合并提交)→ 重建后:**70 个线性提交**,无合并提交
- 规范:Conventional Commits(feat/fix/perf/refactor/test/docs/chore/build/ci)
- 不变:作者、author date、committer date、每提交树内容(除 N01 注入终版 .github/)
- 签名:全部以同一密钥重签;签名时间戳用 gpg --faked-system-time 回填原提交时间(已实验验证可行)
- 特殊:N01 的树注入**终版 .github/**(工作流从第一个提交存在);中间提交的 .github 变更(d8f82f7/75cd066)被吸收
- 修订:末位合并提交 N70 已删(其树与 N69 完全一致,git rev-parse 实证为空差异);类型修正 N01/N04 chore(build)→build

| 新# | 动作 | 新消息(Conventional Commits) | 中文对照 | 原提交 |
|---|---|---|---|---|
| N01 | keep+注入.github | build: scaffold the 1.12.2 port (RetroFuturaGradle, MixinBooter; deps AE2UEL/AE2FCR/AE2CT) | 搭建 1.12.2 移植脚手架(RetroFuturaGradle、MixinBooter;依赖 AE2UEL/AE2FCR/AE2CT) | 0b35335 |
| N02 | keep | feat: port the AE2-VM core engine to 1.12.2 | 移植 AE2-VM 核心引擎到 1.12.2 | 3fe322d |
| N03 | keep | fix: round-1 review fixes (build blockers, catalyst exclusion, sandbox/input normalization) | 第一轮审查修复(构建阻断项、催化剂排除、沙盒与输入规范化) | ded796b |
| N04 | squash | build: add gradle wrapper scripts; drop AZUL pin; relax wrapper timeout | 补 gradle wrapper 启动脚本;取消 AZUL 厂商限定;放宽下载超时 | 6e777b3+7776213 |
| N05 | keep | chore(build): track gradle.properties (project config, not secrets) | 纳管 gradle.properties(项目构建配置,非用户机密) | f53158a |
| N06 | keep | feat: generate the Tags class into the @Mod package | Tags 类生成到 @Mod 同包 | 94ac693 |
| N07 | squash | fix: resolve release-8 (Jabel) incompatibilities from the first compile | 解决首次编译发现的 release-8(Jabel)不兼容项 | 67f38e4+c2e59f3 |
| N08 | keep | feat: fluid key amount retention + crafting container-item detection | 流体键数量保留 + 合成容器物品探测 | 4e6045b |
| N09 | squash | feat: port the public API, third-party registry, precompile mixin and benchmark tests | 移植公共 API、第三方注册表、预编译 mixin 与基准测试 | 84ab08e+ceaaf95+c8e49e3 |
| N10 | squash | test: implement the BenchAEItemStack test-double methods | 实现 BenchAEItemStack 测试替身方法 | 34a7fe9+93bfe88+2e2ce48+b6c2f27+c2b39d5 |
| N11 | keep | test: run Bootstrap.register() so Items initialize in a plain JVM | 运行 Bootstrap.register() 使 Items 在纯 JVM 中初始化 | bedb7fa |
| N12 | keep | build: put Baubles on the test classpath | 测试类路径加入 Baubles | 6756da5 |
| N13 | keep | fix: strict-equality catalyst detection; test fixture fixes | 严格相等催化剂判定;测试夹具修正 | f75313c |
| N14 | squash | fix: realStockOf falls back to the execute-start snapshot; test expectations | realStockOf 回退到执行起始快照;测试期望修正 | ce8cd8f+80dce44 |
| N15 | squash | test: stockAware asserts feasibility and craft count | stockAware 断言可行性与合成次数 | fa7ee70+387914a |
| N16 | keep | test: the feedback loop's p1 crafts exactly once | 反馈环 p1 恰好合成一次 | 7be04f5 |
| N17 | keep | docs(readme): 7/7 green; trim known issues | README:7/7 全绿;精简已知问题节 | ae002ad |
| N18 | squash+吸收3合并 | test: restore starved-seed assertions (missing exactly 1 seed) | 恢复饥饿种子断言(恰好缺 1 个种子) | e658b28+4e298f6+e2b0cc7+0f9650c |
| N19 | squash | test: round-2 benchmark families (fibonacci/JIT-reuse/self-growth/quantity-one/substitute/fuzzy/durability) | 第二轮基准测试族(斐波那契/JIT复用/自增长/数量1/替换槽/模糊/耐久) | 56c2483+b6f9a4d+38a2284+b261a04 |
| N20 | keep | docs(readme): 15/15 green; drop the completed todo | README:15/15 全绿;移除已完成 todo | 8bfeebf |
| N21 | keep | chore: ignore build/test logs | 忽略构建/测试日志 | fff7e60 |
| N22 | keep | test: vendor the Thunderbolt-Core planner as a test-only reference planner | vendor Thunderbolt-Core 规划器作为仅测试用参考规划器 | b3c6242 |
| N23 | squash | build: JDK 17 test toolchain for the vendored planner | 为 vendored planner 配 JDK 17 测试工具链 | fe1fee1+1f07b27+3d61daa |
| N24 | squash | docs(readme): full porting status + Thunderbolt clarification | README:完整移植状态 + Thunderbolt 澄清 | 46d4659+6821dc2 |
| N25 | keep | test: port all remaining benchmark families (72/72 green) | 移植全部剩余基准测试族(72/72 绿) | e8a9df2 |
| N26 | keep | test: port the Thunderbolt reference capability suites (151/151 green) | 移植 Thunderbolt 参考能力套件(151/151 绿) | 8a89474 |
| N27 | keep | docs: upstream README audit, API parity gaps, style cleanup | 对照上游 README 审查;补 API 对齐缺口;风格清理 | 36d9f27 |
| N28 | keep | fix: retarget AE2FC compat to the AE2-UEL build + real-class fluid tests | AE2FC 兼容重定向到 AE2-UEL 构建 + 真实类流体测试 | 61aad97 |
| N29 | squash | feat: support every published AE2UEL build (v49t..v0.56.7) | 支持全部已发布 AE2UEL 构建(v49t..v0.56.7) | 7904e86+99dbc23+8c0b15a |
| N30 | keep | docs(readme): full CurseForge range verified compatible | README:验证全 CurseForge 版本区间兼容 | b28f6cc |
| N31 | keep | feat(vm): pattern-qualified JIT bundle cache + transitive choice invalidation | 样板限定的 JIT bundle 缓存 + 传递性选择失效 | a944dd5 |
| N32 | keep | feat(solver): multi-pattern assignment solver — close the last FALSE_POSITIVE (fibonacci/minimum) | 多样板分配求解器——关闭最后一个误报(fibonacci/minimum) | f4fc33b |
| N33 | keep | feat(vm): dead-cycle pre-pruning (1.12 port of CYCLE-AWARE/SEEDED-RING) | 死环预剪(CYCLE-AWARE/SEEDED-RING 的 1.12 移植) | 81a7668 |
| N34 | keep | test: persistent performance benchmark suite (informational) | 常驻性能基准套件(仅信息展示) | 7226c92 |
| N35 | keep | chore: exclude the local work memo (AGENTS.md) | 排除本地工作备忘(AGENTS.md) | 19de5c7 |
| N36 | keep | test: pattern-lifecycle invalidation differentials + substitute-chain boundaries | 样板生命周期失效对拍 + 替换链边界 | 9cde043 |
| N37 | keep | fix(vm): shortfall retry — restocked re-plans no longer replay stale shortfalls | 缺口重试——补料后重算不再回放过期缺口 | 69717ee |
| N38 | keep | perf(solver): linear-cascade fast path (long[] key index + stock pre-read) | 求解器线性级联快路径(long[] 键索引 + 库存预读) | a265158 |
| N39 | keep | feat(vm): full-plan memoization + pattern-set version gating (fixes GAP-3) | 全计划记忆化 + 样板集版本门控(修复 GAP-3) | 239a1fa |
| N40 | keep | refactor(vm): dead-cycle guard shared across passes; defensive unwrap API | 死环守卫跨 pass 共享;防御性解包 API | c42432b |
| N41 | keep | perf(vm): single-candidate fast path + solver confirmation skip; README 190/190 | 单候选快路径 + 求解确认跳过;README 190/190 | 0b06429 |
| N42 | keep | perf(vm): two-layer fuzzy-family caching | 模糊族两层缓存 | db3a455 |
| N43 | keep | fix(resolve): T2.5 craftable-substitute resolution + backstop test | T2.5 可制造替换变体解析 + 兜底测试 | 21a92ee |
| N44 | keep | refactor(vm): merge passStockLookup/liveStockLookup | 合并 passStockLookup/liveStockLookup | 0beec0e |
| N45 | squash | docs(readme): bilingual restructure; upstream wording; fork identity; AE2UEL range | README 双语重构;对齐上游措辞;fork 身份;AE2UEL 版本范围 | 7057e90+ff5b687+a75a16d+600802e+56a9d3e+1ec9a50 |
| N46 | keep | chore(assets): finalize the mod icon (1.12 badge, 256×256) | 模组图标定稿(1.12 角标,256×256) | 5188897 |
| N47 | squash | ci: extract_perf.py + gradlew executable bit (workflows live from the first commit) | extract_perf.py + gradlew 可执行位(工作流自首提交存在) | d8f82f7+e5012dc+75cd066 |
| N48 | keep | chore(release): set the 1.0.0 version; mod_url; add CHANGELOG.md | 定版 1.0.0;mod_url 指向本仓库;新增 CHANGELOG.md | d7d0f39 |
| N49 | squash | fix(docs): CommonMark rendering; fold THUNDERBOLT-LICENSE into the license table | 修复 CommonMark 渲染;THUNDERBOLT-LICENSE 并入许可表 | 397a2c5+1ec9a50 |
| N50 | squash | test: pin the gaia-spirit amplifying-ring regression | 钉死盖亚之魂放大环回归 | 3cd6217+88f0d51 |
| N51 | keep | feat(vm): shadow material ledger (resident diagnostics) | 物料总账影子模式(常驻诊断) | 03a814d |
| N52 | keep | feat(solver): the ring solver lands the gaia amplifying-ring feature | 环求解器落地盖亚放大环特性 | 9d87355 |
| N53 | squash+吸收合并 | refactor(solver): retire debug instrumentation + the shadow ledger | 移除调试插桩 + 退役影子总账 | 38b3a53+421db00 |
| N54 | squash | test: ring variant coverage (byproduct gain proven; shared intermediate pinned) | 环变体覆盖(副产物增益实证;共享中间体钉死) | c51bb34+966ef39 |
| N55 | keep | feat(solver): generalize emitted/used to all keys | emitted/used 全键通用化 | 95adad1 |
| N56 | keep | test: remove the temporary GaiaDebug driver | 移除临时 GaiaDebug 驱动 | 3d6f4be |
| N57 | keep | fix(vm): strip ring keys from the aggregation total — the net bundle owns ring scheduling | 环键从聚合 total 移除——净 bundle 独占环排程 | 456bd0e |
| N58 | keep | feat(solver): shared-intermediate rings — external drains + ready-first seed probing | 共享中间体环——环外原料放行 + 就绪优先种子探序 | f311834 |
| N59 | keep | fix(compiler): byproduct-rooted requests compile on the byproduct's per-craft output | 副产物根请求按副产物产物槽产量编译 | 479a52b |
| N60 | keep | feat(solver): external-root driver — byproduct orders amplify the ring | 外部根驱动——副产物订单放大整环 | 3fb377b |
| N61 | keep | feat(solver): from-below minimization + external-demand floors + ready-first seed + deterministic driver pick | 从下方最小化 + 环外需求地板 + seed 就绪优先 + 驱动确定性择一 | 28fc94c |
| N62 | keep | feat(solver): material-closure adoption gate + record-order Tarjan | 物料闭包采纳门 + Tarjan 记录序 | 2e87445 |
| N63 | keep | feat(solver): byproduct-intermediate rings — T4 fallback + pattern-level variables + reservation release | 副产物中间键环——T4 回退 + pattern 级变量 + 预留释放 | dae8f55 |
| N64 | keep | feat(solver): coupled rings — consumers-first order + net write-back + SimulationState.restock | 跨环耦合——消费者优先求解序 + 净写回 + SimulationState.restock | 0f9ef78 |
| N65 | keep | fix(vm): account claim extracts in the delta — capture accounting symmetry | claim 提取计入 delta 账——捕获账目对称化 | 26f272e |
| N66 | keep | chore: drop completed stage labels from comments | 去除注释中已完结的阶段标签 | 28db884 |
| N67 | keep | docs: sync README/CHANGELOG with the ring-solver capability line | README/CHANGELOG 同步环求解器能力线 | 3943464 |
| N68 | keep | docs: unify terminology | 统一术语 | c2439e2 |
| N69 | keep | chore: remove shadow-ledger leftovers; solver fallback logs via LOGGER | 移除影子总账残留;回退日志改走 LOGGER | 9d5fa21 |
| N70 | keep | docs: mark the fork as unofficial; add the CurseForge project description | fork 措辞改为 unofficial;新增 CurseForge 项目描述全文 | 403a624 |

统计:102 → 70(消解 6 个合并提交,其中末位合并提交经树比对为空差异直接删除;26 行并入 20 个目标);终态树与原 master 完全一致;N01 树额外含终版 .github/。
