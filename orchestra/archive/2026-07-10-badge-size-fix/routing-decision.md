# Routing Decision — v1.6.3 徽章尺寸修复

| 项 | 内容 |
|---|---|
| 日期 | 2026-07-10 |
| 需求 | 修复 zone 反馈徽章尺寸偏大（比例兜底 283px vs 真实 201px） |
| 类型 | 代码改动 → 完整流程 |
| 档位 | 默认（`gpt-5.4`）|
| **判档理由** | 改动范围明确（`effectiveToolbarHeight` + 两个级联签名改动，共 4 个调用点），根因和实现代码都已在规划阶段用完整反编译+真机诊断 build 验证过，不是架构级改动，无需质量档 |
| **研究外包** | **不派**。根因链已在 Planner 阶段查清：`apktool d`（完整，不加 `-r`）反编译真实设备 1.3.14 APK 找到 `native_candidate_bar` 布局定义，再用临时诊断 build（`DEBUG=true` + `findViewById` 打点）在真机上验证了实际测量值（201px vs 兜底算出的 283px）。修复代码已在 HANDOFF 写好，Executor 照抄落地 |
| Executor model | `codex -m gpt-5.4` |
| Verifier | 独立子 Agent，Sonnet，lint + build + grep 取证；**这轮 orchestrator 会自己补真机验证**（LSPosed 环境上一轮已确认是好的，不存在阻塞） |
| Task file | `orchestra/task-current.md` + `orchestra/HANDOFF-v1.6.3-badge-size.md` |
| 项目目录 | `/Users/jin/Desktop/doubao-letter-longpress-voice` |
| Sandbox | `-s workspace-write -C <项目>`（强制）|
| 撞车检测 | 单任务串行，无撞车风险 |
| Repair 预算 | 最多 2 轮 |

## 已知风险

| 情况 | 路由动作 |
|---|---|
| `EXEC_QUOTA` | 兜底 Reasonix（patch 法）|
| `EXEC_AUTH` | 停，提示用户重登 Codex |
| 级联签名改动漏改某个调用点导致编译失败 | HANDOFF 已列出全部 4 个调用点行号，Verifier 用 `./gradlew assembleDebug` 能直接抓到编译错误，走 repair 循环 |
| 真机验证徽章仍然偏大（可能 `native_candidate_bar` 在有真实翻译/AI工具栏的场景下高度不同） | 记录实测数据，评估是否需要额外场景验证，不算这轮任务失败（这轮目标是把兜底从纯猜测换成有真实信号支撑的实时测量，不是保证所有场景像素级精确）|
