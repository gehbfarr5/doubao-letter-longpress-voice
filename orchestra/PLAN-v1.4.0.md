# PLAN — v1.4.0: Claude / ChatGPT 发送适配（Accessibility Service）

> 主线来自 `FOLLOW-UPS.md` #1 + #2。范围：Claude + ChatGPT 一起做；
> 节点选择器用 best-guess + 自探针（找不到就 dump 候选节点到 logcat）。

## 背景 / 为什么需要 a11y

v1.3.0 A 实验已确认：在 Claude WebView 里，IME 层所有"发送"路径都失效——
- `performEditorAction(IME_ACTION_SEND)`（v1.2.0 force-send 路径）→ 桥接成 commit，不触发 send
- `KEYCODE_ENTER` → 只在 textarea 插换行符，不触发 JS sendMessage

发送动作挂在 React 组件发送按钮的 onClick 上，输入框对 IME 命令无回调。
→ 唯一可行路径：**Accessibility Service 找发送按钮 → 模拟 ACTION_CLICK**。

## 架构（跨进程）

```
[Doubao IME 进程]  Xposed hook                         [模块自身进程]  A11y Service
   用户上滑到 toolbar(send) 区松手
   → commitAndDispatchToolbarAction(cl)
   → pkg ∈ A11Y_SEND_PACKAGES?
        → commit ASR 文本 (p0, 文本进 textarea, 不发 ENTER)
        → poll ASR settle
        → sendBroadcast(ACTION_A11Y_SEND, target_pkg) ───────►  BroadcastReceiver.onReceive
                                                                 → getRootInActiveWindow()
                                                                 → findSendNode(root, pkg)
                                                                 → performAction(ACTION_CLICK)
                                                                 → 找不到则 dump clickable 节点到 log
```

关键点：hook 跑在 Doubao UID，a11y service 跑在模块 UID → 广播跨 UID。
用 `setPackage(自己包名)` 定向 + 接收方动态注册 `RECEIVER_EXPORTED`。

## 语义边界（重要）

- **字母区松手**（默认 commit）：文本上屏，**不发送**。沿用现状，不动。
- **toolbar/send 区松手**（上滑到发送）：Claude/ChatGPT 走新 a11y 路径 = commit 文本 + 点发送。
- Nekogram（FORCE_SEND）和普通换行包路径**完全不变**。

## 变更清单

### 新增 `app/src/main/java/com/jin/doubaolongpressvoice/DoubaoVoiceSendA11yService.java`
- `extends AccessibilityService`
- 常量 `ACTION_A11Y_SEND = "com.jin.doubaolongpressvoice.ACTION_A11Y_SEND"`，extra key `target_pkg`
- `onServiceConnected()`：动态注册 BroadcastReceiver（API ≥ 33 用 `Context.RECEIVER_EXPORTED`）
- `onAccessibilityEvent()` / `onInterrupt()`：空实现
- `onUnbind()` / `onDestroy()`：注销 receiver
- `performSend(String targetPkg)`：
  1. `root = getRootInActiveWindow()`；null → log warn + return
  2. （可选）校验 `root.getPackageName()` 与 targetPkg，不一致只 warn 仍尝试
  3. `node = findSendNode(root, targetPkg)`
  4. 命中 → 取自身或最近可点击祖先 → `performAction(ACTION_CLICK)` → log 成功
  5. 未命中 → `dumpClickables(root)` 到 logcat + log 失败
- `SELECTORS`：`Map<String, ...>` per-package best-guess
  - Claude `com.anthropic.claude`：contentDescription / text 含 "send"（忽略大小写）
  - ChatGPT `com.openai.chatgpt`：同上 "send"（含 "send message"）
- `findSendNode`：递归遍历，优先 clickable 且 desc/text 含 "send" 的节点
- `dumpClickables`：递归，对每个 `isClickable()` 节点打印 `viewIdResourceName | text | contentDescription | className | bounds`（限深度/数量，避免刷屏）
- 全程 try/catch，TAG 用与 hook 一致的前缀便于 `logcat | grep`

### 改 `DoubaoLetterLongPressHook.java`
1. 新增 `private static final java.util.Set<String> A11Y_SEND_PACKAGES =`
   `new java.util.HashSet<>(java.util.Arrays.asList("com.anthropic.claude", "com.openai.chatgpt"));`
2. `commitAndDispatchToolbarAction(cl)`：在 specific/newline 分流**之前**插入：
   ```
   String pkg = currentEditorPackageName(cl);
   if (pkg != null && A11Y_SEND_PACKAGES.contains(pkg)) {
       dispatchViaA11ySend(cl, pkg);
       return;
   }
   ```
3. 新增 `dispatchViaA11ySend(cl, pkg)`：commit `p0(false,"")`（文本上屏，同 newline 路径），
   然后调用 settle-poll，**终止动作 = 广播而非 ENTER**。
4. 重构 `pollAsrSettleAndEnter`：抽出 `pollAsrSettleThen(cl, settleMs, maxWaitMs, startTs, Runnable terminal)`；
   原 `pollAsrSettleAndEnter` 改为 delegate（terminal = `() -> sendEnterKey(cl)`），现有调用点行为不变。
   a11y 路径 terminal = `() -> broadcastA11ySend(cl, pkg)`。
5. 新增 `broadcastA11ySend(cl, pkg)`：
   ```
   Object ime = getStaticObjectField(KeyboardJni, "mImeService"); // 是 Context
   Intent i = new Intent(DoubaoVoiceSendA11yService.ACTION_A11Y_SEND)
       .setPackage("com.jin.doubaolongpressvoice")
       .putExtra("target_pkg", pkg);
   ((Context) ime).sendBroadcast(i);
   ```
   try/catch + log。

### 改 `AndroidManifest.xml`
加 `<service>`：
```xml
<service
    android:name=".DoubaoVoiceSendA11yService"
    android:exported="true"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:label="@string/a11y_service_label">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

### 新增 `app/src/main/res/xml/accessibility_service_config.xml`
```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="100"
    android:description="@string/a11y_service_description"
    android:packageNames="com.anthropic.claude,com.openai.chatgpt" />
```
（`packageNames` 限定两个包 = 隐私/性能友好；service 只在这两个 App 里工作。）

### 改 `app/src/main/res/values/strings.xml`
加：
- `a11y_service_label` = "豆包语音发送助手"
- `a11y_service_description` = "在 Claude / ChatGPT 等应用中，长按字母键语音输入上滑后，自动点击发送按钮。"

### 改 `app/build.gradle`
- `versionCode 5`
- `versionName "1.4.0"`

## 验收（无真机 → 编译 + 静态 grep）

构建必过（a11y service 纯 Android SDK，不依赖 Xposed），grep 断言见 `task-current.md`。
真机点击行为留用户手动验证（需手动授权 a11y）。

## 已知限制 / 后续

- 选择器是 best-guess；真机首测大概率走自探针 dump，按 log 回填真值 → v1.4.1。
- 无引导 Activity：用户需手动到 设置→无障碍 启用服务。引导页留 follow-up。
- 自探针 dump 也是"勘探"：顺手记录 Claude/ChatGPT 节点结构，呼应 FOLLOW-UPS #3 的探索精神。
