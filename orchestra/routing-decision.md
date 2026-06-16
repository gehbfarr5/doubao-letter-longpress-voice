# Routing Decision — v1.2.0 Cross-App Send

| 项 | 值 |
|---|---|
| 档位 | 质量优先 |
| 选档理由 | 改动落在核心 dispatch 入口 (`commitAndDispatchToolbarAction`)，且改判定函数后被两个调用点共用。错改会污染已经稳定工作的 WeChat/QQ/钉钉路径，造成回归。 |
| Executor model | `codex -m gpt-5.5` |
| Verifier | 独立子 Agent，Opus，fresh context |
| Task file | `orchestra/PLAN-v1.2.0.md` |
| 项目目录 | `/Users/jin/Documents/oss/doubao-letter-longpress-voice` |
| 分支 | `feat/cross-app-send-routing` |
| 基线 tag | `pre-v1.2.0-baseline`（在 main 上） |
| 撞车检测 | 无并行任务，本会话独占 orchestra/.lock |
| 用量预算 | 单 round Codex 调用；不预先研究阶段（已由 Planner 完成源码挖掘） |
| Repair 预算 | 最多 2 轮（per Orchestra ROUTING.md §流程5） |
| Sandbox | `-s workspace-write -C <项目>`（强制） |

## 已知风险与 Repair 触发条件

| 情况 | 路由动作 |
|---|---|
| `EXEC_QUOTA` | 兜底 Reasonix (`orch-reasonix.sh`)，patch 法 |
| `EXEC_AUTH` | 停，提示用户重登 Codex |
| `EXEC_ERR` | 同档收窄重试 1 次 |
| Verifier FAIL：编译失败 | 回 Executor + repair-task.md（精确指出 grep 缺什么 / 编译报错末 30 行） |
| Verifier FAIL：grep 静态断言缺项 | 同上 |
| Verifier 2 轮 repair 仍败 | 回 Planner（主会话），人工评估 |
| 用户实测 P0 失败（`t(4)` 在 Nekogram 不工作） | 不走 repair 链；终止 v1.2.0，回 Planner 评估 v1.2.0-rc2 改用 KeyEvent 路径 |
