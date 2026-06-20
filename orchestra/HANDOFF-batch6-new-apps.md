# HANDOFF Batch 6 — 新 App 适配 v1.5.6

## 任务类型
代码改动，走完整流程。改动文件：
- `DoubaoLetterLongPressHook.java`（包名表）
- `DoubaoVoiceSendA11yService.java`（PACKAGE_SEND_VIEW_ID 表）

## 背景

当前已适配的 App：
- `FORCE_SEND_PACKAGES`（路径 A：performEditorAction SEND）：`tw.nekomimi.nekogram`
- `A11Y_SEND_PACKAGES`（路径 B：a11y 模拟点击）：`com.anthropic.claude`, `com.openai.chatgpt`

需要新增的 App（按路径分类）：

**路径 A — performEditorAction（原生 View 输入框，IME action 有效）**：
- `org.telegram.messenger`（Telegram 官方，与 Nekogram 同源）
- `com.baidu.ernie`（文心一言）

**路径 B — a11y 模拟点击（WebView/Compose，IME action 无效）**：
- `com.google.android.apps.bard`（Gemini）
- `ai.x.grok`（Grok）
- `com.moonshot.kimichat`（Kimi）

---

## 改动清单

### 1. DoubaoLetterLongPressHook.java — 扩展包名表

**找到这两行（约 line 162-167）**：
```java
private static final java.util.Set<String> FORCE_SEND_PACKAGES =
        java.util.Collections.singleton("tw.nekomimi.nekogram");
private static final java.util.Set<String> A11Y_SEND_PACKAGES =
        new java.util.HashSet<>(java.util.Arrays.asList(
                "com.anthropic.claude",
                "com.openai.chatgpt"));
```

**改为**：
```java
private static final java.util.Set<String> FORCE_SEND_PACKAGES =
        new java.util.HashSet<>(java.util.Arrays.asList(
                "tw.nekomimi.nekogram",
                "org.telegram.messenger",
                "com.baidu.ernie"));
private static final java.util.Set<String> A11Y_SEND_PACKAGES =
        new java.util.HashSet<>(java.util.Arrays.asList(
                "com.anthropic.claude",
                "com.openai.chatgpt",
                "com.google.android.apps.bard",
                "ai.x.grok",
                "com.moonshot.kimichat"));
```

### 2. DoubaoVoiceSendA11yService.java — 补充 PACKAGE_SEND_VIEW_ID 表

找到 `PACKAGE_SEND_VIEW_ID` static 初始化块（当前只有 claude 和 chatgpt 两条 null 记录），
在末尾追加三条新的 a11y 路径 App（均为 null，需真机 logcat dump 后填充）：

```java
PACKAGE_SEND_VIEW_ID.put("com.google.android.apps.bard", null);  // Gemini, WebView/Compose
PACKAGE_SEND_VIEW_ID.put("ai.x.grok", null);                     // Grok
PACKAGE_SEND_VIEW_ID.put("com.moonshot.kimichat", null);          // Kimi
```

---

## 验收条件

1. `grep -c "org.telegram.messenger" DoubaoLetterLongPressHook.java` → 1

2. `grep -c "com.baidu.ernie" DoubaoLetterLongPressHook.java` → 1

3. `grep -c "com.google.android.apps.bard" DoubaoLetterLongPressHook.java` → 1

4. `grep -c "ai.x.grok" DoubaoLetterLongPressHook.java` → 1

5. `grep -c "com.moonshot.kimichat" DoubaoLetterLongPressHook.java` → 1

6. `grep -c "com.google.android.apps.bard" DoubaoVoiceSendA11yService.java` → 1

7. `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL

---

## 约束

- 只改上述两个文件
- 不 git add / git commit / 自测
