# Routing Decision — v1.6.2 getInputView 字段改名修复

| 项 | 内容 |
|---|---|
| 日期 | 2026-07-10 |
| 需求 | 修复 `getInputView()` 对豆包 1.3.14 `ImeService.x→y` 字段改名的兼容 |
| 类型 | 代码改动 → 完整流程 |
| 档位 | 默认（`gpt-5.4`）|
| **判档理由** | 单方法级修复，改动范围已在规划阶段通过反编译精确定位（`getInputView()` 一个方法），有现成同款模式（`extractKeyboardView()`）可抄，非架构/核心改动，无需质量档 |
| **研究外包** | **不派**。根因已在 Planner 阶段用 `apktool d` 反编译设备上的真实豆包 1.3.14 `base.apk` 验证完成（不是猜测），修复代码已经在 HANDOFF 里写好，Executor 照抄落地即可，没有需要外部检索的未知点 |
| Executor model | `codex -m gpt-5.4` |
| Verifier | 独立子 Agent，Sonnet，lint + build + grep 取证；**无法做真机烟测**（LSPosed 环境本身故障，另案，不阻塞本次修复落地） |
| Task file | `orchestra/task-current.md` + `orchestra/HANDOFF-v1.3.14-inputview-field-rename.md` |
| 项目目录 | `/Users/jin/Desktop/doubao-letter-longpress-voice`（注：`~/Documents/oss/...` 是旧记录，已在 Codex skill 里更正） |
| Sandbox | `-s workspace-write -C <项目>`（强制）|
| 撞车检测 | 单任务串行，无撞车风险 |
| Repair 预算 | 最多 2 轮 |

## 已知风险

| 情况 | 路由动作 |
|---|---|
| `EXEC_QUOTA` | 兜底 Reasonix（patch 法）|
| `EXEC_AUTH` | 停，提示用户重登 Codex |
| Verifier 只能机检，无真机烟测证据 | PASS 判定标「机检通过 + 代码审查，实机验证待补」，不算完全置信但可以先落库 |
| 构建环境（JDK/Gradle）不可用 | 记录具体报错，回 Planner 评估是否降级为纯代码审查验收 |
