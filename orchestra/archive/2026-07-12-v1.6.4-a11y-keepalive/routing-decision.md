# Routing Decision — v1.6.4 a11y keepalive Layer 3 + 失效可见化

| 项 | 内容 |
|---|---|
| 日期 | 2026-07-12 |
| 需求 | ChatGPT 划到工具栏发送失效 → 根因 = ColorOS 清除 a11y 授权，做 root 层保活 + 失效可见化 |
| 类型 | 代码改动 → 完整流程 |
| 档位 | 默认（成本优先，`gpt-5.4`）|
| **判档理由** | 改动 = ~60 行 POSIX sh 守护脚本 + module.prop（本机有已真机验收的 adb_keepalive v2.0 同构参考）+ hook 里 ~20 行 settings 读取 + Toast + 版本 bump。无架构变更、无核心逆向逻辑改动、验收全部可机检。不满足质量档（核心/架构/重构）与速度档（非 hotfix）触发词 |
| **研究外包** | **不派**。根因已由主会话真机取证闭环（`enabled_accessibility_services` 为空 + `accessibility_enabled=0` + `stopped=true` + 07-11 实测 OK→07-12 重启后失效的时间线，三点实锤）；KSU 模块写法复用本地既有资产 `/Users/jin/Desktop/adbkeepalive-module-review/source/current/`，不涉及 GitHub 检索或 web 调研 |
| Executor model | `codex -m gpt-5.4` |
| Verifier | 独立子 Agent，Sonnet，`sh -n` + build + grep 取证 |
| Task file | `orchestra/task-current.md` |
| 项目目录 | `/Users/jin/Desktop/doubao-letter-longpress-voice` |
| Sandbox | `-s workspace-write -C <项目>`（强制）|

## 真机取证摘要（主会话完成，运行/分析迭代，不占执行器）

- `enabled_accessibility_services`=空、`accessibility_enabled`=0 → a11y 服务死，ChatGPT 发送必失效
- `stopped=true` → Layer 1 BOOT_COMPLETED 永远收不到，无法自愈
- AOSP hibernation 排除（`cmd app_hibernation get-state`=false）→ ColorOS 自有清理
- 已临时 root 恢复授权，服务确认拉起（logcat `receiver registered` + `startForeground ok`）

## 后续人工验证门（收口后，orchestrator 自己做 + 用户复测）

1. 打包模块 zip → `ksud module install` 装真机 → root 删授权模拟 ColorOS 清理
   → ≤120s 内自动恢复 = PASS
2. 用户装 v1.6.4 APK 复测 ChatGPT 多轮 + 长输入。若长输入仍失效，抓
   `logcat -s DoubaoVoiceSend` 判别次要假说（2s timeout 提前点击），另开任务
