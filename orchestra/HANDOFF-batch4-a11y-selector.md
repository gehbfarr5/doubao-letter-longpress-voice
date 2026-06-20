# HANDOFF Batch 4 — a11y 选择器健壮化 v1.5.4

## 任务类型
代码改动，走完整流程。只改 `DoubaoVoiceSendA11yService.java`。

## 背景

当前 `findSendNode()` 是深度优先、命中立即返回的朴素搜索：
- 不排除误击词（"发送图片"、"attachment" 等）
- 多个候选只取第一个（顺序不稳定）
- 无 per-package viewId 精确路径

需要改为：
1. 收集所有候选节点
2. 过滤排除词
3. 优先级排序后取第一个

---

## 改动清单（2 项，只改 `DoubaoVoiceSendA11yService.java`）

### 1. #5 per-package viewId 精确匹配表（结构预留，当前两个 App 都为 null）

在类顶部（`SEND_KEYWORDS` 常量附近）新增：

```java
/**
 * Known stable resource-id for the send button, keyed by package name.
 * null = no stable id available for this package (fall back to heuristic).
 * Populated from real-device logcat dumps; update when apps change.
 */
private static final java.util.Map<String, String> PACKAGE_SEND_VIEW_ID;
static {
    PACKAGE_SEND_VIEW_ID = new java.util.HashMap<>();
    PACKAGE_SEND_VIEW_ID.put("com.anthropic.claude", null);   // WebView, no stable id
    PACKAGE_SEND_VIEW_ID.put("com.openai.chatgpt", null);     // Compose, no stable id
}
```

在 `performSend(String targetPkg)` 里，**在** `findSendNode(root, targetPkg)` 调用之前，
先尝试 viewId 精确查找（只在 viewId 非 null 时）：

```java
// Fast path: per-package stable viewId (if known).
if (targetPkg != null && PACKAGE_SEND_VIEW_ID.containsKey(targetPkg)) {
    String viewId = PACKAGE_SEND_VIEW_ID.get(targetPkg);
    if (viewId != null) {
        java.util.List<AccessibilityNodeInfo> byId =
                root.findAccessibilityNodeInfosByViewId(viewId);
        if (byId != null && !byId.isEmpty()) {
            AccessibilityNodeInfo n = byId.get(0);
            AccessibilityNodeInfo clickable = nearestClickable(n);
            if (clickable == null) clickable = n;
            boolean ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.i(TAG, "performSend(viewId): ACTION_CLICK ok=" + ok + " viewId=" + viewId);
            return;
        }
    }
}
```

### 2. #7 findSendNode 收集全部候选 + 排除词过滤 + 优先级排序

**Step A — 新增排除词常量**（紧接 `SEND_KEYWORDS` 之后）：

```java
/** Keywords that disqualify a node from being the send button. */
private static final String[] EXCLUDE_KEYWORDS = {
        "图片", "文件", "image", "file", "attachment", "photo", "album", "表情", "emoji"
};
```

**Step B — 新增 `containsExclude` 方法**（紧接 `containsSend` 之后）：

```java
private boolean containsExclude(CharSequence value) {
    if (value == null) {
        return false;
    }
    String lower = value.toString().toLowerCase(java.util.Locale.US);
    for (String kw : EXCLUDE_KEYWORDS) {
        if (lower.contains(kw.toLowerCase(java.util.Locale.US))) {
            return true;
        }
    }
    return false;
}
```

**Step C — 新增 `isExcluded` 方法**：

```java
private boolean isExcluded(AccessibilityNodeInfo node) {
    try {
        return containsExclude(node.getContentDescription())
                || containsExclude(node.getText());
    } catch (Throwable t) {
        return false;
    }
}
```

**Step D — 新增辅助：`isExactSendMatch`**（contentDescription 或 text 精确等于关键词）：

```java
private boolean isExactSendMatch(AccessibilityNodeInfo node) {
    try {
        for (CharSequence value : new CharSequence[]{
                node.getContentDescription(), node.getText()}) {
            if (value == null) continue;
            String s = value.toString().trim().toLowerCase(java.util.Locale.US);
            for (String kw : SEND_KEYWORDS) {
                if (s.equals(kw.toLowerCase(java.util.Locale.US))) {
                    return true;
                }
            }
        }
    } catch (Throwable t) {
        // ignore
    }
    return false;
}
```

**Step E — 重写 `findSendNode` 为收集 + 排序模式**：

把当前的 `findSendNode(AccessibilityNodeInfo node, String targetPkg)` 替换为：

```java
private AccessibilityNodeInfo findSendNode(AccessibilityNodeInfo root, String targetPkg) {
    java.util.List<AccessibilityNodeInfo> candidates = new java.util.ArrayList<>();
    collectSendCandidates(root, candidates);
    if (candidates.isEmpty()) {
        return null;
    }
    // Get screen width for right-side check.
    final int screenWidth = getResources().getDisplayMetrics().widthPixels;
    // Sort: exact match first, then right-side, then larger area, then original order.
    java.util.Collections.sort(candidates, new java.util.Comparator<AccessibilityNodeInfo>() {
        @Override
        public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
            int scoreA = candidateScore(a, screenWidth);
            int scoreB = candidateScore(b, screenWidth);
            return Integer.compare(scoreB, scoreA); // higher score first
        }
    });
    return candidates.get(0);
}

/** DFS: collect ALL nodes that match send keywords, are not excluded, and are actionable. */
private void collectSendCandidates(AccessibilityNodeInfo node,
        java.util.List<AccessibilityNodeInfo> out) {
    try {
        if (node == null) return;
        if (matchesSendCandidate(node)) {
            AccessibilityNodeInfo clickable = nearestClickable(node);
            out.add(clickable != null ? clickable : node);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            try {
                AccessibilityNodeInfo child = node.getChild(i);
                collectSendCandidates(child, out);
            } catch (Throwable t) {
                // skip
            }
        }
    } catch (Throwable t) {
        Log.w(TAG, "ERR collectSendCandidates: " + t.getClass().getSimpleName());
    }
}

/**
 * A node is a send candidate if:
 * 1. Its text or description contains a send keyword
 * 2. It does NOT contain an exclude keyword
 * 3. It is actionable (or has an actionable ancestor)
 */
private boolean matchesSendCandidate(AccessibilityNodeInfo node) {
    try {
        if (!containsSend(node.getContentDescription()) && !containsSend(node.getText())) {
            return false;
        }
        if (isExcluded(node)) {
            return false;
        }
        return supportsClick(node) || nearestClickable(node) != null;
    } catch (Throwable t) {
        return false;
    }
}

private int candidateScore(AccessibilityNodeInfo node, int screenWidth) {
    int score = 0;
    try {
        // Exact keyword match is strongest signal.
        if (isExactSendMatch(node)) score += 100;
        // Right side of screen (send buttons usually on the right).
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (screenWidth > 0 && bounds.centerX() > screenWidth * 0.6f) score += 10;
        // Larger area (main action button usually bigger).
        score += Math.min(9, (bounds.width() * bounds.height()) / 10000);
    } catch (Throwable t) {
        // ignore
    }
    return score;
}
```

注意：`matchesSend()` 方法原来被 `findSendNode` 调用，现在改为 `matchesSendCandidate()` 取代（含排除词检查）。
原来的 `matchesSend()` 方法可以删除（或保留但不再调用）。如果删掉 `matchesSend()` 会编译失败（被别处引用），则保留。
**请先 grep 确认 `matchesSend` 的调用点，再决定删或保留。**

---

## 验收条件

1. `grep -c "PACKAGE_SEND_VIEW_ID" DoubaoVoiceSendA11yService.java` → ≥ 3（声明 + put * 2）

2. `grep -c "EXCLUDE_KEYWORDS" DoubaoVoiceSendA11yService.java` → ≥ 2（声明 + 用）

3. `grep -c "collectSendCandidates" DoubaoVoiceSendA11yService.java` → ≥ 2（定义 + 调用）

4. `grep -c "candidateScore" DoubaoVoiceSendA11yService.java` → ≥ 2（定义 + 调用）

5. `grep -c "isExcluded" DoubaoVoiceSendA11yService.java` → ≥ 2（定义 + 调用）

6. `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL

---

## 约束

- 只改 `DoubaoVoiceSendA11yService.java`
- 不改 `DoubaoLetterLongPressHook.java`
- 不 git add / git commit / 自测
