# 开发方向指导 v1.6.x — 性能 / 代码优化 / UI

> 日期：2026-06-21 ｜ 背景：功能侧已判定"足够完善"（LLM 候选方案因冲击核心操作逻辑已取消）。
> 接下来聚焦**非功能性质量**。前置：[REVIEW](REVIEW-2026-06-21.md) · [RISK](RISK-llm-candidate-feature.md)

## 0. 总判断
项目成熟、防御性强、文档完备。功能面无明显缺口，**核心资产是来之不易的稳定性**（cancel 抑制窗口、commit 唯一性、懒加载避免 `<clinit>` 炸进程）。
→ 本阶段所有改动的第一原则：**不回归**。优先选**无行为变更 / 可隔离 / 易回滚**的项；凡碰到 commit/cancel/触发判定的，一律高门槛、单独验收。

---

## 1. 性能（Performance）

### 🟢 P1 — 日志门控（最高 ROI，零行为风险）
**现状**：88 处 `log()`，每处无条件同时打 `Log.i` + `XposedBridge.log`。后者写 Xposed 日志管道，且分布在**触摸/zone/ASR 流式回调等热路径**上（如 `onTouchEvent` MOVE → `maybeUpdateZone` → `log("zone ...")`，`onAsrSetPreedit` 每帧 preedit）。
**问题**：热路径上每事件一次 IPC 级日志写 → 持续录音/滑动时的无谓 CPU + 电量 + 日志刷屏。
**改法**：加 `private static final boolean DEBUG`（或 gradle `buildConfigField`），`log()` 内 `if (!DEBUG) return;` 包住 `XposedBridge.log`（保留 `Log.i` 或一并门控）。Release 默认 `false`，排障时改一处即可。
**风险**：极低（纯日志）。**收益**：明确的热路径开销下降 + 日志卫生。

### 🟢 P2 — 缓存单次录音内不变的反射结果
**现状**：`maybeUpdateZone` 每次 zone 切换都 `resolveEffectiveEnterOrdinal()` → `readEnterTypeFromEditorViewInfo` + `currentEditorPackageName`（两次反射）。但 **enterOrdinal / package 在一次录音内不会变**。
**改法**：长按触发时解析一次，存进 per-session 字段，zone 切换直接读缓存；`onFinishInput*` 重置时清掉。
**风险**：低（只要纳入现有重置链）。**收益**：滑动时反射归零。

### 🟡 P3 — 集中化反射访问 + 缓存 Method 句柄
**现状**：13 处 `findClass(KEYBOARD_JNI)`、7 处 `mImeService` 取值，`callMethod`/`callStaticMethod` 反复按名解析。
**改法**：抽一个 `DoubaoRefs` 持有懒解析并缓存的 `Class`/`Method`/字段句柄（`imeService()`、`keyboardJni()` 等访问器）。`findClass` 本身 Xposed 有缓存，主要收益是**去重 + 可读性**，顺带省 method 解析。
**风险**：中（动到反射封装，需回归长按全流程）。**收益**：中（perf 小、可维护性大）——与 C2 合并做。

---

## 2. 代码优化（Code quality）

### 🟡 C1 — 拆分 1968 行 god class
**现状**：`DoubaoLetterLongPressHook` 一个类塞了：5 个 hook 安装 + 反射解析 + ASR 提交/取消/settle + zone 逻辑 + overlay UI 全部。
**改法**：按职责拆分（保持纯静态/无状态迁移）：
- `DoubaoRefs`（懒解析 + reader，吸收 P3）
- `AsrController`（trigger / commit / cancel / settle / all-back 订阅）
- `ZoneTracker`（zone 计算 + 防抖）
- `OverlayBadge`（overlay 创建/动画/着色）
- `DoubaoLetterLongPressHook` 只留 hook 安装 + 编排
**风险**：中-高（大重构碰核心路径）→ **必须分多个无行为变更小步 + 每步独立验收**，参照 v1.5.x 批次法。
**收益**：可维护性、可读性、未来适配新版豆包时定位更快。**注意**：这是"投资型"重构，若近期不打算频繁改这块，可降优先级。

### 🟢 C2 — 去重反射样板
合并进 C1/P3：`findClass(JNI)→getStaticObjectField("mImeService")→callMethod` 的三段式在 `readToolbarHeight / readKbdType / readInputClass / currentEditorPackageName / resolveDoubaoDimenPx / ...` 重复多遍，集中成一个 `imeService()` + 资源访问器。

### 🟢 C3 — 死代码二次扫描 + lint
v1.5.7 已清一批。重构后再过一遍 unused（IDE inspect / lint），确保没有新孤儿。
**风险**：极低。

---

## 3. UI

### 🟢 U1 — overlay 适配豆包深/浅色主题（最值得做的 UI 项）
**现状**：overlay 颜色硬编码 `COLOR_SEND=0xFF1A77FF` / `COLOR_CANCEL=0xFFFF4D4F`，文字/图标恒为白。豆包夜间/浅色主题切换时不跟随。
**改法**：发送色优先读豆包 `asr_long_press_navigation_press`（README 已提及该资源）等主题色，找不到再退当前硬编码；图标已走豆包资源，文字色也按主题取。
**风险**：低（找不到资源就回退现状）。**收益**：视觉一致性，跟"复用豆包视觉风格"的既有定位一致。

### 🟡 U2 — 录音中（LETTER zone）的反馈确认
**现状**：纯录音、未滑动时自定义 overlay 是隐藏的（alpha 0），反馈完全依赖豆包自身 ASR 面板。
**待确认**：真机上长按触发后豆包原生面板是否够清晰；若够，**不动**（避免和豆包面板抢 UI）。若不够，可在 overlay 加一个极轻的"录音中"态。
**风险**：中（碰录音态 UI 可能和豆包面板叠加）→ 先观察、非必要不做。

### 🟢 U3 — 文案集中化
**现状**：`TEXT_NEWLINE`/`TEXT_CANCEL` 等中文字面量散在 Java 常量里，与 `resolveDoubaoString` 资源回退混用。
**改法**：把模块自有兜底文案收进 `strings.xml`（已有该文件），Java 侧只留资源 id。
**风险**：极低。**收益**：i18n 友好 + 一致。

---

## 4. 建议执行顺序（按 ROI×风险）
1. **先批（低风险高 ROI，可立即做）**：P1 日志门控 → P2 反射缓存 → U1 主题色 → U3 文案集中 → C3 lint。一个 `v1.6.0` 小版本即可覆盖，全程无核心行为变更。
2. **后批（投资型，单独排期）**：C1 god-class 拆分（含 P3/C2），按无行为变更小步走 + 每步独立验收。视是否还会频繁改这块决定要不要现在投。
3. **观察项**：U2 录音反馈——先真机看豆包原生面板够不够，再决定。

> 与既有约束一致：所有碰 commit/cancel/触发判定的改动走完整 plan→执行→验收；纯日志/文案/主题色这类可直接小步提交。

**一句话**：功能已封顶，下一程是"把已经很好的东西打磨得更省、更整洁、更一致"——先做"日志门控 + 反射缓存 + 主题色 + 文案"这批零风险高收益的，god-class 拆分作为投资型重构单独排期。
