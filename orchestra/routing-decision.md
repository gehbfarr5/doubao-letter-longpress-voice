# Routing Decision — v1.4.0 Claude/ChatGPT a11y send

| 项 | 值 |
|---|---|
| 日期 | 2026-06-17 |
| 需求 | 推进 v1.4.0 = Claude + ChatGPT 发送适配（Accessibility Service） |
| 类型 | 代码改动 → 完整 plan→执行→验收→commit |
| 档位 | 质量优先 |
| 选档理由 | 新增跨进程 a11y 组件 + 改 manifest + 在核心 dispatch 入口 (`commitAndDispatchToolbarAction`) 插新路径并重构 settle-poll。错改会回归已稳定的 Nekogram/换行路径。 |
| Executor model | `codex -m gpt-5.5`（传 `gpt-5.5` 给 wrapper，不 hardcode id） |
| Verifier | 独立子 Agent，Opus，fresh context，机器证据（build + grep）判成败 |
| Task file | `orchestra/task-current.md`（设计在 `orchestra/PLAN-v1.4.0.md`） |
| 项目目录 | `/Users/jin/Documents/oss/doubao-letter-longpress-voice` |
| 分支 | `feat/claude-chatgpt-a11y-send` |
| 撞车检测 | codex(gpt-5.x) 与 reasonix(deepseek-v4-pro) 不撞；本会话独占 orchestra/.lock |
| 用量预算 | 单 round Codex；研究已由 Planner 完成（源码挖掘 + 用户决策） |
| Repair 预算 | 最多 2 轮 |
| Sandbox | `-s workspace-write -C <项目>`（强制） |
| 用户决策(2026-06-17) | 范围 = Claude+ChatGPT 都做；选择器 = best-guess + 自探针 dump |

## 已知风险与 Repair 触发条件

| 情况 | 路由动作 |
|---|---|
| `EXEC_QUOTA` | 兜底 Reasonix (`orch-reasonix.sh`)，patch 法 |
| `EXEC_AUTH` | 停，提示用户重登 Codex |
| `EXEC_ERR` | 同档收窄重试 1 次 |
| Verifier FAIL：编译失败 | 回 Executor + repair-task.md（指出编译报错末 30 行 / grep 缺项） |
| Verifier FAIL：grep 断言缺项 | 同上 |
| 2 轮 repair 仍败 | 回 Planner，人工评估 |
| 选择器真机不命中 | 预期内 → 走自探针 dump，按 log 回填真值 v1.4.1（非 repair） |
