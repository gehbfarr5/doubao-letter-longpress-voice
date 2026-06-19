# PLAN v1.5.x — 全量优化（14 项）

基于 code review 结论，按优先级分 6 个批次顺序执行。

---

## 批次划分

| 批次 | 版本 | 内容 | 文件 |
|---|---|---|---|
| Batch 1 | v1.5.1 | 代码清理（无行为变更） | Hook.java |
| Batch 2 | v1.5.2 | Hook 改进（scheduleVerify + AsrContext.T） | Hook.java |
| Batch 3 | v1.5.3 | Cancel 精确化（需先诊断实验） | Hook.java |
| Batch 4 | v1.5.4 | a11y 选择器健壮化 | A11yService.java + Hook.java |
| Batch 5 | v1.5.5 | Layer 2 保活 + BootReceiver 修复 | A11yService.java + BootReceiver.java |
| Batch 6 | v1.5.6 | 新 App 适配（Gemini/Grok/Kimi） | Hook.java + A11yService.java |

> Batch 7（LLMCandidate #13）需要先逆向 `performLLMRequest` 参数语义，独立排期。

---

## Batch 1 — 代码清理（v1.5.1）

**文件**：`DoubaoLetterLongPressHook.java`  
**性质**：无行为变更，纯代码质量改善  
**风险**：极低

### #8 删除 COMMIT_TAIL_DELAY_MS 死常量

```
- private static final long COMMIT_TAIL_DELAY_MS = 600L;  // 删除
```

从未被任何代码引用，历史迭代遗留。

### #4 EnterActionType 简化为 EditorViewInfo 单一来源

`resolveEnterOrdinal()` 当前读三来源（EditorViewInfo、mCurrentEnterType、mCurrentEditboxAction）。
逆向已确认 `EditorViewInfo.e().d()` 是 AsrLongPressView 的权威来源。

改动：
- `resolveEnterOrdinal()` 里先调 `readEnterTypeFromEditorViewInfo(cl)`，若结果 ∈ \{2..8\}（specific）直接返回
- 若结果 ≤ 1，再读 `readEnterTypeOrdinal(cl)` 作为第二来源
- `readCurrentEditboxAction` 降级为仅在 zone log 里打印，不参与 ordinal 决策
- `maybeUpdateZone` 里的三路 log 行精简为一行（已有 `resolved=` 字段够诊断）

### #3 从主路径 hook 里移除 sLastAsrCallbackTs 更新

`onAsrSetPreedit` 和 `onAsrCommitPreeditText` 的 hook 里有：
```java
sLastAsrCallbackTs = SystemClock.elapsedRealtime();
```

现在 `subscribeAsrAllBackThen` 是主路径，`sLastAsrCallbackTs` 只被 `pollAsrSettleThen`（fallback）用到。

改动：把两处赋值改为仅在 `sAsrProcessResolveAttempted && sAsrProcess == null` 时才更新
（即只有 listener 路径不可用时才走 poll fallback，poll fallback 才需要这个时间戳）。
实现方式：在两个 hook 里加判断：`if (sAsrProcess == null) sLastAsrCallbackTs = ...`

### #9 Overlay 改为 GONE/VISIBLE 缓存（不再每次 detach/recreate）

`resetVolatileState()` 里把：
```java
((ViewGroup) p).removeView(ov);
sOverlay = null; sOverlayIcon = null; sOverlayLabel = null;
```
改为：
```java
ov.setVisibility(View.GONE);
// sOverlay 保留引用不清空
```

`ensureOverlay()` 里：若 `sOverlay != null && sOverlay.getParent() == parent`，
检查 visibility，若是 GONE 则 `setVisibility(VISIBLE)` 后直接返回（不 recreate）。

### #10 Toolbar height 在 session 开始时缓存

新增 `private static volatile int sCachedToolbarHeight = -1;`

Hook `ImeService.onStartInputView(EditorInfo, boolean)`（已有 lifecycle hook 基础）：
在 `afterHookedMethod` 里调一次 `readToolbarHeight(cl)` 并存入 `sCachedToolbarHeight`。

`resetVolatileState()` 里把 `sCachedToolbarHeight = -1`（下次 session 重新读）。

`maybeUpdateZone()`、`ensureOverlay()` 里把 `readToolbarHeight(cl)` 改为先读缓存：
```java
int tbH = (sCachedToolbarHeight > 0) ? sCachedToolbarHeight : readToolbarHeight(cl);
```

---

### Batch 1 测试方法

| 测试项 | 方法 | 期望结果 |
|---|---|---|
| 编译通过 | `./gradlew :app:assembleDebug` | BUILD SUCCESSFUL |
| EnterActionType 回归 | 在微信聊天框录音→滑工具栏，查 log `resolved=` | 仍为 4（SEND），与之前一致 |
| EnterActionType 回归 | 在备忘录录音→滑工具栏，查 log `resolved=` | 仍为 0 或 1（换行） |
| overlay 复用 | 连续三次长按，查 log | `overlay attached` 只出现一次，后续两次无此 log |
| toolbar height 缓存 | 连续 MOVE 时查 log | `getToolbarHeight` 反射不出现在 MOVE 期间 |
| 无新崩溃 | `adb logcat -s DoubaoLongPress` 全程无 `ERR` | 无报错 |

---

## Batch 2 — Hook 改进（v1.5.2）

**文件**：`DoubaoLetterLongPressHook.java`  
**性质**：用更精确的事件替换 magic-number 延迟  
**风险**：低

### #2 scheduleAsrStartVerification → onAsrSetPreedit 首次触发

当前：`postDelayed(80ms)` 后查 `E()`，如果 ASR 没启动则回滚 `sSuppressNextUp`。

改动：
- 新增 `private static volatile boolean sAsrStartConfirmed = false;`
- `onAsrSetPreedit` 的 hook 里，若 `sSuppressNextUp && !sAsrStartConfirmed`：
  - 设 `sAsrStartConfirmed = true`（ASR 真正有输出，确认已启动）
  - 取消 pending 的 verify runnable
- `scheduleAsrStartVerification` 改为：投递 runnable 到 300ms（给低端机更多时间），检查 `sAsrStartConfirmed`：
  - 若 false（300ms 内没有任何 `onAsrSetPreedit`）→ 判定 ASR 未启动，回滚
  - 若 true → 不做任何事
- `resetVolatileState()` 里清空 `sAsrStartConfirmed = false`

### #11 Hook AsrContext.T(int, boolean) 作为 sLastAsrCallbackTs 的精确更新点

新增一个安装在 `com.bytedance.android.input.speech.AsrContext` 上的 hook：

```java
XposedHelpers.findAndHookMethod(
    "com.bytedance.android.input.speech.AsrContext", cl,
    "T", int.class, boolean.class,
    new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam param) {
            boolean isDone = Boolean.TRUE.equals(param.args[1]);
            if (isDone) {
                sLastAsrCallbackTs = SystemClock.elapsedRealtime();
                log("AsrContext.T phase=" + param.args[0] + " done=true");
            }
        }
    });
```

这让 poll fallback 的 settle 检测用上 ASR 引擎内部的真实完成时间点，
而不是 UI 回调 `onAsrSetPreedit` 的最后时间（两者可能差数百毫秒）。

---

### Batch 2 测试方法

| 测试项 | 方法 | 期望结果 |
|---|---|---|
| ASR 启动确认日志 | 长按字母键录音，查 log | 出现 `AsrStartConfirmed via onAsrSetPreedit`（或类似），不再有 `ASR did NOT start` 误报 |
| AsrContext.T hook | 任意录音，查 log | 出现 `AsrContext.T phase=1 done=true` 和 `phase=2 done=true` 两行 |
| poll fallback settle | 强制让 listener 路径失败（可临时把 ASR_ALL_BACK_LISTENER_CLS 改错），验证 poll 路径仍工作 | `asr-settle quiet=...ms` 日志出现，时间比之前更精准 |
| 回归：正常录音不受影响 | 完整录音→上屏→滑发送三条路径 | 行为与之前一致，无新 ERR |

---

## Batch 3 — Cancel 精确化（v1.5.3）

**文件**：`DoubaoLetterLongPressHook.java`  
**前置条件**：先做诊断实验确认 `p0(true,"cancel")` 是否触发 `L.a` all-back

### 诊断实验（先于代码改动）

临时在 `cancelVoice()` 里的 `p0(true, "cancel")` 调用之后，
向 `AsrManager.b` 注册一个只 log 不执行任何动作的 `L.a` listener（不影响功能），
装机后测试：录音 → 滑出键盘 → cancel → 查 log：

- **若出现** `asr-allback: s.g()=true` → 确认 cancel 也触发 all-back，可以精确化
- **若不出现**（超时后 `TIMEOUT` log）→ ASR cancel 不走 all-back，改用缩短固定窗口方案

### #1 Cancel 窗口精确化（实验确认后实施）

**方案 A（all-back 可用）**：
```java
private static void cancelVoice(ClassLoader cl) {
    // 1. 立即关掉 UI
    KeyboardJni.finishPreedit(false)
    // 2. 注册一次性 all-back listener（只关窗，不执行 action）
    subscribeAsrAllBackThen(cl, CANCEL_MAX_WAIT_MS, () -> {
        // all-back 到了，加 100ms safety tail 后关窗
        sCancelUntilElapsed = SystemClock.elapsedRealtime() + CANCEL_SAFETY_TAIL_MS;
    });
    // 3. 立刻打开窗口（不能等 all-back，要在 p0 之前保护）
    sCancelUntilElapsed = SystemClock.elapsedRealtime() + CANCEL_WINDOW_MS;
    // 4. 触发 cancel
    p0(true, "cancel")
}
```
新常量：`CANCEL_MAX_WAIT_MS = 800L`，`CANCEL_SAFETY_TAIL_MS = 150L`

效果：cancel 完成后窗口从 1200ms 动态缩短到 `elapsed + 150ms`，
低延迟 ASR 平均节省 700-1000ms 的无响应期。

**方案 B（all-back 不可用）**：
把 `CANCEL_WINDOW_MS` 从 1200ms 降到 500ms（cancel 通常在 200-400ms 内完成），
并在 `onAsrSetPreedit` hook 的 cancel 窗口分支里增加"连续 200ms 无新 preedit 则提前关窗"逻辑。

---

### Batch 3 测试方法

| 测试项 | 方法 | 期望结果 |
|---|---|---|
| 诊断实验 | 装机 → 长按字母键 → 滑出键盘取消 → 查 log | 确认 `s.g()=true` 是否出现 |
| Cancel 窗口缩短 | 同上，比较 cancel 到下一次可录音的间隔 | 方案 A：间隔 < 300ms；方案 B：间隔 < 600ms |
| Cancel 后 ASR 文字不上屏 | 录音一段话 → 快速滑出取消 → 检查输入框 | 输入框无任何文字 |
| Cancel 后立即再次录音 | Cancel 后立刻长按字母键 | 能正常触发新的语音（无 sSuppressNextUp 残留） |

---

## Batch 4 — a11y 选择器健壮化（v1.5.4）

**文件**：`DoubaoVoiceSendA11yService.java`（主改），`DoubaoLetterLongPressHook.java`（包名表）  
**风险**：中（改选择器逻辑，需真机验证 Claude + ChatGPT 两路）

### #5 per-package viewId 精确匹配表

在 `DoubaoVoiceSendA11yService.java` 里新增：

```java
private static final java.util.Map<String, String> PACKAGE_SEND_VIEW_ID;
static {
    PACKAGE_SEND_VIEW_ID = new java.util.HashMap<>();
    // 从真机 logcat dump 拿到的稳定 resource-id
    // 格式: "包名" -> "包名:id/xxx"（或 null = 无稳定 id，只能靠启发式）
    PACKAGE_SEND_VIEW_ID.put("com.anthropic.claude", null);   // WebView，无稳定 id，靠关键词
    PACKAGE_SEND_VIEW_ID.put("com.openai.chatgpt", null);     // Compose，无稳定 id
}
```

`findSendNode()` 里：先尝试 `root.findAccessibilityNodeInfosByViewId(viewId)`（若 viewId 非 null），
命中则直接返回，不走启发式。

### #7 findSendNode 收集全部命中节点 + 优先级排序 + 排除词黑名单

排除词：`{"图片", "文件", "image", "file", "attachment", "photo", "album", "表情", "emoji"}`

当全部命中节点 > 1 时，按以下优先级排序取第一个：
1. contentDescription/text 精确等于关键词（不含其他字符，如纯"发送"而非"发送图片"）
2. 节点在屏幕右侧（x > 屏幕宽度 60%，聊天框发送按钮通常在右侧）
3. 节点面积最大（通常主要动作按钮比附属按钮大）
4. 原深度优先顺序兜底

---

### Batch 4 测试方法

| 测试项 | 方法 | 期望结果 |
|---|---|---|
| Claude 发送 | 在 Claude 里录音→滑工具栏→松手 | 消息成功发送 |
| ChatGPT 发送 | 在 ChatGPT 里录音→滑工具栏→松手 | 消息成功发送 |
| 无误击 | 在含"发送验证码"按钮的页面（如登录页）触发，查 log | log 显示 "no send node"，不误击 |
| 排除词 | 在含"发送图片"按钮的场景触发 | 不击中附件按钮，或走启发式后命中正确发送按钮 |

---

## Batch 5 — Layer 2 保活 + BootReceiver 修复（v1.5.5）

**文件**：`DoubaoVoiceSendA11yService.java`，`BootRestoreReceiver.java`

### #6 a11y 服务 Layer 2：startForeground 保活

在 `onServiceConnected()` 里：

```java
// API 26+ 需要 startForeground，低版本不需要
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    NotificationChannel ch = new NotificationChannel(
        "doubao_voice_send", "豆包语音发送", NotificationManager.IMPORTANCE_MIN);
    ch.setShowBadge(false);
    getSystemService(NotificationManager.class).createNotificationChannel(ch);
    Notification n = new Notification.Builder(this, "doubao_voice_send")
        .setContentTitle("豆包语音发送助手运行中")
        .setSmallIcon(android.R.drawable.ic_menu_send)
        .setOngoing(true)
        .build();
    startForeground(1, n);
}
```

`onUnbind` / `onDestroy` 里调 `stopForeground(true)`。

AndroidManifest.xml 加 `<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />`

### #14 BootRestoreReceiver grep 正则误匹配修复

把 shell script 里的 `grep -q "$comp"` 改为 `grep -qF "$comp"`（fixed string，不把 `.` 当正则通配）。
同时加 `2>/dev/null` 抑制非 root 场景的权限报错。

---

### Batch 5 测试方法

| 测试项 | 方法 | 期望结果 |
|---|---|---|
| 前台服务存在 | `adb shell dumpsys activity services com.jin.doubaolongpressvoice` | 出现 `DoubaoVoiceSendA11yService`，状态 foreground |
| 通知栏条目 | 开启无障碍后查通知栏 | 有一条"豆包语音发送助手运行中"的持久通知（低优先级） |
| ColorOS 杀后台 | 从最近任务划掉豆包输入法（不是模块 App） | 服务仍存活（前台服务保护进程） |
| 重启后自动恢复 | 重启设备，查 `adb shell settings get secure enabled_accessibility_services` | 包含 `com.jin.doubaolongpressvoice/.DoubaoVoiceSendA11yService` |
| grep -qF | 设备组件名里含特殊字符时不误匹配（理论验证） | 无误匹配 |

---

## Batch 6 — 新 App 适配（v1.5.6）

**文件**：`DoubaoLetterLongPressHook.java`（A11Y_SEND_PACKAGES），`DoubaoVoiceSendA11yService.java`

### #12 新 App 分析与适配

按路径分类（需真机验证确认哪条路径有效）：

**路径 A（performEditorAction SEND）**— 先测再加 FORCE_SEND_PACKAGES：
- Telegram 官方（`org.telegram.messenger`）：和 Nekogram 同源，大概率有效
- 文心一言（`com.baidu.ernie`）：原生 View，需测

**路径 B（a11y 模拟点击）**— 加 A11Y_SEND_PACKAGES + PACKAGE_SEND_VIEW_ID：
- Gemini（`com.google.android.apps.bard`）：WebView/Compose 混合
- Grok（`ai.x.grok`）：移动端 LLM 客户端
- Kimi（`com.moonshot.kimichat`）：月之暗面

适配流程（复用 ADAPT-PLAYBOOK.md）：
1. 先试 `performEditorAction(IME_ACTION_SEND)` ── 如果 onEditorAction 被响应则走路径 A
2. 否则走路径 B：logcat dump 拿节点 id → 加 PACKAGE_SEND_VIEW_ID

---

### Batch 6 测试方法

| 测试项 | 方法 | 期望结果 |
|---|---|---|
| Telegram（路径 A） | Telegram 聊天框录音→滑工具栏 | 消息发送（不需要 a11y 授权） |
| Gemini（路径 B） | 需要 a11y 授权；Gemini 输入框录音→滑工具栏 | 消息发送 |
| 回归：Claude / ChatGPT | 适配新 App 后复测 Claude / ChatGPT | 行为与之前一致 |
| 回归：微信 / 换行路径 | 微信录音→滑工具栏（SEND）；备忘录录音→滑工具栏（换行） | 行为与之前一致 |

---

## 汇总验收矩阵（全量）

| 批次 | 核心 log 信号 | 编译 | 行为 |
|---|---|---|---|
| 1 | overlay attached 只出现一次；无冗余 readToolbarHeight | ✅ | EnterActionType 各路径回归 |
| 2 | `AsrContext.T phase=1 done=true`；ASR 启动改由 preedit 首次触发确认 | ✅ | 无 verify-80ms ERR 误报 |
| 3 | cancel 后 allback 触发或 TIMEOUT；下次录音不卡 | ✅ | 取消后无残留文字、立即可再录 |
| 4 | Claude/ChatGPT 发送正常；含"发送图片"场景无误击 | ✅ | 两路发送通过 |
| 5 | foreground service 在 dumpsys 可见；重启后授权保留 | ✅ | ColorOS 后台杀不掉 |
| 6 | 各新 App 发送通过；旧路径无回归 | ✅ | 全路径回归 |

---

## 执行顺序

```
Batch 1 → Verifier → commit
Batch 2 → Verifier → commit
Batch 3（诊断实验 → 代码改动）→ Verifier → commit
Batch 4 → Verifier → commit（真机 Claude + ChatGPT 验证）
Batch 5 → Verifier → commit（真机重启 + ColorOS 测试）
Batch 6 → Verifier → commit（各新 App 真机测试）
```
