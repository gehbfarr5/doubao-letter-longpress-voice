# TASK — v1.4.0: Claude/ChatGPT 发送适配（Accessibility Service）

完整设计见 `orchestra/PLAN-v1.4.0.md`（同目录，先读它）。下面是可机检验收清单与精确实现要求。

## 约束
- 只改/建文件，不 git commit、不自测、完成即停。
- 不破坏 v1.2.0（Nekogram force-send）/ v1.3.0（ASR-settle newline）现有行为。
- 风格对齐周围代码：javadoc、log 前缀、try/catch 包裹反射与跨进程调用。

## 要做的事（5 处文件）

### 1. 新建 `app/src/main/java/com/jin/doubaolongpressvoice/DoubaoVoiceSendA11yService.java`
- `package com.jin.doubaolongpressvoice;`
- `public class DoubaoVoiceSendA11yService extends android.accessibilityservice.AccessibilityService`
- `public static final String ACTION_A11Y_SEND = "com.jin.doubaolongpressvoice.ACTION_A11Y_SEND";`
- `public static final String EXTRA_TARGET_PKG = "target_pkg";`
- 动态注册 `BroadcastReceiver`（`onServiceConnected` 注册，`onDestroy`/`onUnbind` 注销）。
  API ≥ 33 用 `registerReceiver(r, filter, Context.RECEIVER_EXPORTED)`；低版本用旧两参重载。
- receiver `onReceive` → 读 `EXTRA_TARGET_PKG` → `performSend(pkg)`。
- `performSend(String targetPkg)`：
  - `AccessibilityNodeInfo root = getRootInActiveWindow()`；null → log + return。
  - `findSendNode(root, targetPkg)`：递归找 **clickable 且 (contentDescription 或 text 含 "send"（忽略大小写))** 的节点；命中不可点则上溯最近可点击祖先。
  - 命中 → `node.performAction(AccessibilityNodeInfo.ACTION_CLICK)`，log 成功（含 viewId/desc）。
  - 未命中 → 调 `dumpClickables(root)` 把每个 clickable 节点的
    `viewIdResourceName | text | contentDescription | className | bounds` 打到 logcat（限深度与数量），并 log 失败。
- `onAccessibilityEvent`、`onInterrupt` 空实现。
- 统一 TAG（如 `"DoubaoVoiceSend"`），全程 try/catch。

### 2. 改 `app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java`
- 在 `FORCE_SEND_PACKAGES` 附近新增：
  `private static final java.util.Set<String> A11Y_SEND_PACKAGES = new java.util.HashSet<>(java.util.Arrays.asList("com.anthropic.claude", "com.openai.chatgpt"));`
- `commitAndDispatchToolbarAction(cl)` 内，在 `boolean specific = ...` 分流前插入 A11Y 优先分支：
  取 `currentEditorPackageName(cl)`，若 ∈ `A11Y_SEND_PACKAGES` → `dispatchViaA11ySend(cl, pkg); return;`
- 新增 `dispatchViaA11ySend(ClassLoader cl, String pkg)`：调 `p0(false,"")`（同 `dispatchNewlineFast` 的 commit），
  然后 `pollAsrSettleThen(cl, NEWLINE_ASR_SETTLE_MS, NEWLINE_ASR_MAX_WAIT_MS, SystemClock.elapsedRealtime(), () -> broadcastA11ySend(cl, pkg));`
- 重构：把现有 `pollAsrSettleAndEnter` 的循环体抽成
  `pollAsrSettleThen(ClassLoader cl, long settleMs, long maxWaitMs, long startTs, Runnable terminal)`，
  原 `pollAsrSettleAndEnter` 改为 `pollAsrSettleThen(cl, settleMs, maxWaitMs, startTs, () -> sendEnterKey(cl))` 的 delegate
  （所有现有 ENTER 行为不变；terminal 替换 `sendEnterKey(cl)` 调用点）。
- 新增 `broadcastA11ySend(ClassLoader cl, String pkg)`：
  从 `KeyboardJni.mImeService` 取对象当 `android.content.Context`，
  `ctx.sendBroadcast(new android.content.Intent(DoubaoVoiceSendA11yService.ACTION_A11Y_SEND).setPackage("com.jin.doubaolongpressvoice").putExtra(DoubaoVoiceSendA11yService.EXTRA_TARGET_PKG, pkg));`
  try/catch + log。

### 3. 改 `app/src/main/AndroidManifest.xml`
在 `<application>` 内加 `<service>`（见 PLAN，name `.DoubaoVoiceSendA11yService`，
permission `android.permission.BIND_ACCESSIBILITY_SERVICE`，intent-filter
`android.accessibilityservice.AccessibilityService`，meta-data 指向 `@xml/accessibility_service_config`，
`android:label="@string/a11y_service_label"`，`android:exported="true"`）。

### 4. 新建 `app/src/main/res/xml/accessibility_service_config.xml`
内容见 PLAN（`canRetrieveWindowContent="true"`，`packageNames="com.anthropic.claude,com.openai.chatgpt"`，
`description="@string/a11y_service_description"`）。

### 5. 改 `app/src/main/res/values/strings.xml`
加 `a11y_service_label` 与 `a11y_service_description`（文案见 PLAN）。

### 6. 改 `app/build.gradle`
`versionCode 5`，`versionName "1.4.0"`。

## 可机检验收（Verifier 会跑）

### 构建
- `./gradlew :app:assembleDebug` 退出 0，产出 `app/build/outputs/apk/debug/app-debug.apk`。

### 必须存在（grep）
- `DoubaoVoiceSendA11yService.java` 存在且 `extends ... AccessibilityService`
- `ACTION_A11Y_SEND = "com.jin.doubaolongpressvoice.ACTION_A11Y_SEND"`
- service 内 `getRootInActiveWindow()`、`ACTION_CLICK`、dump 逻辑（`viewIdResourceName` 与 `isClickable`）
- hook 内 `A11Y_SEND_PACKAGES` 含 `com.anthropic.claude` 与 `com.openai.chatgpt`
- hook 内 `dispatchViaA11ySend`、`broadcastA11ySend`、`pollAsrSettleThen`
- `commitAndDispatchToolbarAction` 内对 `A11Y_SEND_PACKAGES` 的判断在 `dispatchViaAsrManagerT`/`dispatchNewlineFast` 之前
- Manifest 含 `android.accessibilityservice.AccessibilityService` 与 `BIND_ACCESSIBILITY_SERVICE`
- `res/xml/accessibility_service_config.xml` 含 `canRetrieveWindowContent="true"`
- strings 含 `a11y_service_label`、`a11y_service_description`
- build.gradle：`versionCode 5`、`versionName "1.4.0"`

### 回归（不得破坏）
- `FORCE_SEND_PACKAGES` 仍 = `singleton("tw.nekomimi.nekogram")`
- `sendEnterKey(cl)` 仍被 newline 终止路径调用（经 `pollAsrSettleThen` 的 ENTER terminal）
- `NEWLINE_ASR_SETTLE_MS` / `NEWLINE_ASR_MAX_WAIT_MS` / `sLastAsrCallbackTs` 保留
- `dispatchViaAsrManagerT`、`commitVoice`、`maybeUpdateZone` 仍在
