# 风险评估：在长按语音流里引入「点选候选/纠错（LLM candidate）」

> 日期：2026-06-21 ｜ 类型：实现前风险评估（先评估、后决定）
> 前置：[REVIEW-2026-06-21](REVIEW-2026-06-21.md) · [EVAL-llm-rewrite-vs-current](EVAL-llm-rewrite-vs-current.md)

## 0. 评估对象（拟实现功能）
把豆包 `AsrEditorLayoutView` 的候选/纠错体系（`llm_candidate` 点选替换、`directCommitCandByEngine`、`addAsrModifyPairInfo`、同音字纠错，如 的→地）接进**当前长按语音流**。
注意：当前长按流复刻的是 `AsrLongPressView`（LLM 引用=0），是「按住→松手即上屏/发送」的最小 overlay，**本身没有任何候选 UI**。

## 1. 现有功能基线（会被影响的面）
| 现有能力 | 实现支点 |
|---|---|
| 长按触发 ASR | `handleMessage` hook + `DoFunctionKey(6)` |
| 松手即上屏 | `q0()/p0(false,"")` 自动 finalize（二段结果静默纠错） |
| 滑工具栏发送/换行 | `t(ord)` / `p0+ENTER`，zone 决策 |
| 滑出取消 | cancel 窗口 + 吞 `commitString/onAsrSetPreedit/onAsrCommitPreeditText` |
| 跨应用发送 | a11y 点发送按钮 / force-send `performEditorAction` |
| 会话状态隔离 | `onFinishInput*` 重置 volatile state |

## 2. 风险清单（按严重度）

### 🔴 高 — 交互模型冲突（核心体验）
长按流的灵魂是「松手即上屏/即发送」的秒级闭环。点选候选要求**松手后停留在一个可点选的编辑面**：
- 要么切到 `AsrEditorLayoutView` 完整面板 → 等于把"松手即走"改成"松手进编辑器再点"，**核心快感没了**；
- 要么自建候选 overlay → 新增大块 UI + 触摸分发，和现有 zone-overlay 抢工具栏空间、抢 touch 事件。

**影响**：直接动摇模块最核心、用户最依赖的行为。

### 🔴 高 — 跨应用场景直接失效
LLM 候选靠 **composition string 驱动 + 仅 full input mode**，且候选窗是**豆包自家 UI**。在 Claude/ChatGPT 等第三方输入框里**根本不弹**。
**影响**：模块最值钱的场景（跨应用发送）拿不到这个功能 → 体验割裂（A 应用有、B 应用没有），且对主用例零收益。

### 🟠 中 — 与 cancel 抑制窗口打架
候选提交走 `DoCommit(..., CommitSource.ASR_EDITOR)`，而 `ASR_EDITOR` **不在** 我们的 `USER_INPUT_SOURCES` 白名单：
- 取消窗口内点候选 → 被我们吞掉（功能失灵）；
- 若把 `ASR_EDITOR` 加进白名单 → 真·取消时 ASR 文本可能泄漏上屏（**取消功能回归**）。
**影响**：cancel 这条来之不易的路径（v1.5.3 才调稳）有回归风险。

### 🟠 中 — 双重提交 / commit 路径分叉
现状 commit = `q0()/p0()` 自动 finalize 上屏。候选 = `directCommitCandByEngine + DoCommit`。两条路并存易：
- 自动 finalize 先把文字上屏，候选点选再 `DoCommit` 一次 → **重复上屏**；
- finalize 与候选渲染时序竞争（候选还没出，文字已 commit）。

### 🟡 低-中 — 状态泄漏面扩大
新增面板可见性 / 候选列表 / pending LLM 请求等 state，必须同步进 `onFinishInput*` 重置链，否则**跨 input session 泄漏**——和历史上反复踩的同类 bug 同源。

### 🟡 低-中 — 逆向脆弱面扩大
新增对 `AsrEditorLayoutView` / `LLMCandidate` / `directCommitCandByEngine` / `performLLMRequest` 的 hook，**全是混淆符号**。豆包版本一变，breakage 点从现在的"少数关键反射"扩到"候选体系整片"，维护成本上升。

### 🟡 低 — LLM 异步延迟引入新时序
`performLLMRequest` 是协程+网络，松手到候选出现有 1–3s 空窗，需要新的 loading/超时态，且可能和现有 all-back/ASR-settle 时序逻辑互相干扰。

## 3. 风险×价值矩阵
| 方案 | 对现有功能风险 | 价值 |
|---|---|---|
| 接进**默认松手路径** | 🔴 高（动摇核心 + cancel 回归 + 双提交） | 低（核心场景跨应用还用不上） |
| 做成**独立可选 edit 模式**（仅豆包原生输入场景，不碰默认路径/cancel 白名单） | 🟠 中（隔离得当可控） | 低-中（仅豆包自家场景可用） |

## 4. 结论与建议
- **不要接进默认松手上屏/发送路径**。会同时冲击：核心秒级体验、cancel 抑制窗口、commit 唯一性——这三样都是项目里调最久、最值钱的资产。
- 若仍要做，**唯一可接受形态**：独立 opt-in 的"编辑/纠错模式"，明确只在豆包自家输入场景启用，**完全旁路** 现有 commit/cancel 逻辑（不改 `USER_INPUT_SOURCES`、不与 `q0/p0` 同路），并把所有新 state 纳入 `onFinishInput*` 重置。
- **优先级仍低于 A 类真机收尾**（Gemini/Grok/Kimi 验证、`PACKAGE_SEND_VIEW_ID` 回填）：那是已投入未闭环、ROI 明确；本功能是高风险、且对主场景（跨应用）天然失效。

**一句话**：这个功能和长按流的"快+跨应用"定位天然相斥，硬接进默认路径风险高回报低；要做就严格隔离成独立模式，且接受它只在豆包自家场景生效。
