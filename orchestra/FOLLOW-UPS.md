# FOLLOW-UPS — Post-v1.3.0 Backlog

> Items deferred from v1.3.0. Each is sized loosely; reorder by user priority.

---

## #1 — Claude App 适配（v1.4.0 主线）✅ 已发布 (2026-06-17, 7293b9e)

**实现**：`DoubaoVoiceSendA11yService` + hook 端 `A11Y_SEND_PACKAGES` 分支。已过独立 Verifier（build+grep）。
**待真机**：选择器是 best-guess（desc/text 含 "send"）。首测大概率走自探针 dump → 按 logcat 回填真值（见下方「#0 真机收尾」）。

---

### #0 — v1.4.0 真机收尾 ✅ 已完成 (2026-06-17, 722daa7)

真机实测结论：
- **Claude**（WebView）：一开始就命中（按钮真 `isClickable`），发送 OK。
- **ChatGPT**（Jetpack Compose）：首测 miss 且 dump 空 → 根因 = Compose 发送按钮 `isClickable()==false`、只在 action list 暴露语义 `ACTION_CLICK`；旧 dump 只打印 `isClickable()` 节点所以一行没有。
- 修复：匹配/点击改看 `getActionList()` 含 `ACTION_CLICK`，关键词加中文「发送/提交」，dump 改打印"可操作或带文本/desc"的节点。
- 标签修复：a11y 包走 `resolveEffectiveEnterOrdinal` 的 SEND 覆盖 → 工具栏显示「发送」而非「换行」。
- 复测：Claude + ChatGPT 均正常发送，标签均为「发送」，换行路径无回归。

### 后续可做（post-v1.4.0 backlog）

1. **引导 Activity**：一键跳「设置→无障碍」+ 服务启用状态提示（当前需用户手动进设置；reinstall 后无障碍可能被系统解绑需重开）。
2. **扩展更多应用**：Gemini / Grok / 其它聊天类 App，复用同一 a11y 服务 + 补选择器。靠 logcat dump 勘探节点。
3. **选择器健壮化**：当前是启发式（含 send/发送 + 可点击）。可加 per-package resource-id 精确匹配作为首选、启发式兜底。
4. **`DOUBAO-INTERNALS.md`**：沉淀 Claude/ChatGPT（及豆包自身）节点结构与 hook 点，作为后续 feature reference（呼应 #3 勘探精神）。

---

### #1 原始分析（保留作参考）

**状态**：v1.3.0 A 实验已确认 IME 层路径全部失效：
- `performEditorAction(IME_ACTION_SEND)`（v1.2.0 force-send 路径）→ Claude WebView 桥接到 commit，**不触发 send**
- `KEYCODE_ENTER` → Claude WebView 只插换行符到 textarea，**不触发 JS sendMessage**

**机制**：Claude 的发送动作挂在 React 组件的发送按钮 onClick 里，输入框对 IME 命令完全没回调。

**v1.4.0 方案**：Accessibility Service 找发送按钮并模拟点击。

**实现要点**：
- 新建 `DoubaoVoiceSendA11yService`（声明 `android.accessibilityservice` permission）
- 用户授权 a11y → 服务监听 IME 调度 "send" 信号 → 找当前 App 的发送按钮 → 模拟 `ACTION_CLICK`
- IME 端在 force-send 路径或新路径里 broadcast "send" 信号给 a11y service
- 按 package 配置节点选择器（content-desc / text / class / id 组合）

**初始适配包名**：
- `com.anthropic.claude`（按钮 content-desc 可能是 "Send" / "Send message"，需用 uiautomator 实测）

---

## #2 — ChatGPT App 适配（同 v1.4.0，跟 #1 共用 a11y 基础设施）✅ 已发布 (2026-06-17)

已随 v1.4.0 一起：`A11Y_SEND_PACKAGES` 含 `com.openai.chatgpt`，复用同一 a11y 服务。选择器同样待真机收尾（见 #0）。

**复用 #1 的 Accessibility Service**，只加 ChatGPT 的节点选择器配置。

**包名**：`com.openai.chatgpt`

**节点选择器实测**：用 `uiautomatorviewer` / Android Studio Layout Inspector 看按钮 resource-id / content-desc。

---

## #3 — 深 Hook 豆包空格长按 → ASR-finalize 权威信号（v1.4.x）

**背景**：v1.3.0 的 ASR-settle poll 是基于 callback 静默推断的 workaround。理论上更稳的方案 = 找豆包**自己**在空格长按 → 滑发送流程里使用的 "ASR 完成" 内部信号，直接订阅。

**用户态度（2026-06-16）**：
> "希望在 Hook 的过程中 还能找到其他有价值的东西"

也就是这个 hook 不仅是修这个 bug 的优化，也是**勘探**——逆向时可能发现其他可用的入口点 / API，对后续 feature 有用。

**研究方向**：
1. Hook `AsrLongPressView` 的空格长按 → 滑发送 release dispatch 逻辑
2. 找到豆包内部 "ASR 收尾确认完成" 的方法 / callback / state field
3. 用它替换 v1.3.0 的 `pollAsrSettleAndEnter` —— 直接订阅而不是 poll
4. 顺手记录沿路看到的其他 hook 点，写到一个新的 `DOUBAO-INTERNALS.md` 作为后续 feature 的 reference

**ROI**：低对 v1.3.0 这条 bug 来说；中-高对长期项目演化来说。

---

## 注意事项 — 这次 v1.3.0 学到的工程教训

1. **"调延迟参数找 sweet spot"** 类型的 bug 修法在实际开发中比看上去耗时——每一轮 build + 装 + 实测 + 抓 log + 分析要 5-15 分钟，迭代 5 轮就 1 小时。
2. **从 callback / hook 走事件驱动**通常更稳，但 upfront 成本高，看起来不划算。
3. 本次 v1.3.0 在迭代 5+ 轮（mode 0/3/4/5/6/7/8/8v2）后才到 callback 方案。**下次遇到类似 trade-off，倾向跳过磁性常数环节，直接走 hook**。
4. 反思已经沉淀到 user memory，未来类似 bug 默认 hook 路线。
