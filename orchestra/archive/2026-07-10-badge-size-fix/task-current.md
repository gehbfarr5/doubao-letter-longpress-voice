# 当前任务计划 (v1.6.3 — 徽章尺寸修复)

## Task — 徽章尺寸偏大：改用真实 native_candidate_bar 高度替代比例兜底

**档位**：默认（`gpt-5.4`）
**类型**：代码改动，走完整流程
**HANDOFF**：`orchestra/HANDOFF-v1.6.3-badge-size.md`（已含根因分析 + 完整实现代码 + 完整调用链梳理，直接照做）

### 背景

v1.6.2 修好了徽章不显示的问题，但用户实机反馈徽章显示出来后尺寸明显偏大，
盖到了第一排字母键上。已通过完整反编译（`apktool d`，这次没加 `-r`，把资源
也解出来了）+ 真机诊断 build 验证根因：`effectiveToolbarHeight()` 在原生
`getToolbarHeight()==0` 时用的兜底是 `TOOLBAR_TOP_NORMAL=0.30` 比例猜测，算出
283px；但真实的候选/工具栏行（`native_candidate_bar`，来自
`res/layout/ime_inputview_root.xml`，固定 `56dp` 高）真机实测只有 201px。
283 比 201 多 41%，就是这次视觉上"偏大"的根因。

### 涉及文件

- `app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java`

### 实现要求（HANDOFF 里已给出具体代码和完整调用链梳理，照抄即可）

新增 `readNativeCandidateBarHeight(cl)`：通过 `getInputView(cl)` 拿到
InputView，用 `getIdentifier("native_candidate_bar", "id", DOUBAO_PACKAGE)` +
`findViewById` 实时读取真实高度。`effectiveToolbarHeight()` 的兜底顺序改成：
①原生 `getToolbarHeight()`（不变）→ ②新增的实时测量 `native_candidate_bar`
→ ③现有的比例猜测（保留作最后一道保险，不删除）。

**这个改动会级联**：`effectiveToolbarHeight()` 要加 `ClassLoader cl` 首参，
连带调用它的 `isLetterZone()` 和 `computeZone()` 也要加 `cl` 参数并透传给各
自的调用点。HANDOFF 里已经把完整调用链（哪几行、谁调谁、cl 从哪来）列清楚
了，照着改，4 个调用点一个都不能漏，否则编译不过。

顺手把版本号从 `1.6.2` bump 到 `1.6.3`，README 里同风格补一句说明。

**不要动**：`getInputView()` 本体（v1.6.2 刚修的 x→y 自愈逻辑）、
`AsrManager.s0/t0`、`KeyboardJni` 相关反射——都已验证正确。

### 机器可检验收条件

1. `grep -c 'native_candidate_bar' app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java` → `>= 1`
2. `grep -c 'effectiveToolbarHeight(ClassLoader' app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java` → `>= 1`
3. `./gradlew :app:lintDebug` 通过（exit 0）
4. `./gradlew :app:assembleDebug` 编译通过（exit 0）
5. `grep -c 'versionName "1.6.3"' app/build.gradle` → `1`

### 真机验证（orchestrator 在 Verifier 机检通过后自己做，不算 Codex 的活）

装机、强杀豆包重新加载、长按滑到工具栏区，用截图量色块像素高度，确认新高度
接近 201px 而不是旧的 283px。这台设备的 LSPosed 环境本身是好的（上一轮已经
排查清楚，之前怀疑环境坏是排查方法用错了），不存在真机验证障碍。
