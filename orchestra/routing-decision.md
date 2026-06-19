# Routing Decision — v1.5.x 无障碍保活 + 豆包逆向勘探

| 项 | Task B（代码）| Task A（分析）|
|---|---|---|
| 日期 | 2026-06-20 | 2026-06-20 |
| 需求 | 无障碍保活 Layer 1（BOOT_COMPLETED 自恢复）| 逆向豆包空格长按 ASR 完成机制 |
| 类型 | 代码改动 → 完整流程 | 运行/分析迭代 → 轻流程 |
| 档位 | 默认（`gpt-5.4`）| 质量优先（`gpt-5.5`）|
| 选档理由 | 简单新增：一个 Receiver 类 + manifest 两行 | 需要深度推理混淆代码，找权威 hook 点 |
| Executor model | `codex -m gpt-5.4` | `codex -m gpt-5.5` |
| Verifier | 独立子 Agent，Sonnet，build + grep 取证 | 不走 Verifier（分析任务）|
| Task file | `orchestra/HANDOFF-a11y-keepalive-layer1.md` | `orchestra/HANDOFF-doubao-internals.md` |
| 项目目录 | `/Users/jin/Documents/oss/doubao-letter-longpress-voice` | 同左 |
| 执行顺序 | 先（串行锁限制）| B 完成后 |
| Sandbox | `-s workspace-write -C <项目>`（强制）| 同左（只写 orchestra/DOUBAO-INTERNALS.md）|
| 撞车检测 | 串行，无撞车风险 | 同左 |
| Repair 预算 | 最多 2 轮 | N/A |

## 已知风险

| 情况 | 路由动作 |
|---|---|
| `EXEC_QUOTA` | 兜底 Reasonix |
| `EXEC_AUTH` | 停，提示用户重登 Codex |
| Task A jadx 超时 | 记录已找到内容，不空手而归 |
| Task A 类名全混淆找不到 | 记录"未找到"，给出搜索路径，回 Planner 评估是否换工具 |
