# HANDOFF Batch 1 — 代码清理 v1.5.1

## 任务类型
代码改动，走完整流程。只改 `DoubaoLetterLongPressHook.java`。

## 改动清单（5 项，无行为变更）

### 1. #8 删除 COMMIT_TAIL_DELAY_MS 死常量

找到并删除以下行（及其注释）：
```java
private static final long COMMIT_TAIL_DELAY_MS = 600L;     // fallback path only
```
这个常量从未被任何代码引用，直接删除。

---

### 2. #4 EnterActionType 简化为 EditorViewInfo 单一主来源

**修改 `resolveEnterOrdinal(ClassLoader cl)`**：

原来读三来源、取最大值的逻辑改为优先级链：

```java
private static int resolveEnterOrdinal(ClassLoader cl) {
    // EditorViewInfo.e().d() is the authoritative source (used by AsrLongPressView).
    int fromEditorViewInfo = readEnterTypeFromEditorViewInfo(cl);
    if (fromEditorViewInfo >= 2 && fromEditorViewInfo <= 8) {
        return fromEditorViewInfo;
    }
    // Fallback: mCurrentEnterType (may be stale in some editors).
    int fromEnterType = readEnterTypeOrdinal(cl);
    if (fromEnterType >= 2 && fromEnterType <= 8) {
        return fromEnterType;
    }
    // Return best non-negative value for diagnostic clarity.
    return Math.max(0, Math.max(fromEditorViewInfo, fromEnterType));
}
```

注意：
- **完全移除** `readCurrentEditboxAction(cl)` 在 `resolveEnterOrdinal` 里的调用
- `readCurrentEditboxAction` 方法本身**保留不删**（仍在 zone log 里用）

**修改 `maybeUpdateZone()`**：

原来：
```java
int srcA = readEnterTypeFromEditorViewInfo(cl);
int srcB = readEnterTypeOrdinal(cl);
int rawAct = readCurrentEditboxAction(cl);
log("zone " + prev + " -> " + next + " coord=(" + x + "," + y + ") tbH=" + tbH
        + " EditorViewInfo.d=" + srcA
        + " mCurrentEnterType.ord=" + srcB
        + " mCurrentEditboxAction=0x" + Integer.toHexString(rawAct)
        + " resolved=" + enterOrdinal);
```

改为（精简为一行，保留诊断关键字段）：
```java
log("zone " + prev + " -> " + next
        + " coord=(" + x + "," + y + ")"
        + " tbH=" + tbH
        + " resolved=" + enterOrdinal);
```

---

### 3. #3 sLastAsrCallbackTs 只在 listener 路径不可用时更新

**修改 `installCommitSuppressionHooks` 里的 `onAsrCommitPreeditText` hook**：

原来：
```java
protected void beforeHookedMethod(MethodHookParam param) {
    if (isInCancelWindow()) {
        log("onAsrCommitPreeditText SWALLOWED");
        param.setResult(Boolean.TRUE);
        return;
    }
    sLastAsrCallbackTs = SystemClock.elapsedRealtime();
}
```

改为：
```java
protected void beforeHookedMethod(MethodHookParam param) {
    if (isInCancelWindow()) {
        log("onAsrCommitPreeditText SWALLOWED");
        param.setResult(Boolean.TRUE);
        return;
    }
    // Update poll-fallback timestamp only when listener path unavailable.
    if (sAsrProcess == null) {
        sLastAsrCallbackTs = SystemClock.elapsedRealtime();
    }
}
```

**修改 `onAsrSetPreedit` hook** 里同样的赋值：

原来：
```java
protected void beforeHookedMethod(MethodHookParam param) {
    if (isInCancelWindow()) {
        log("onAsrSetPreedit SWALLOWED text=" + safeText((String) param.args[0]));
        param.setResult(Boolean.TRUE);
        return;
    }
    sLastAsrCallbackTs = SystemClock.elapsedRealtime();
}
```

改为：
```java
protected void beforeHookedMethod(MethodHookParam param) {
    if (isInCancelWindow()) {
        log("onAsrSetPreedit SWALLOWED text=" + safeText((String) param.args[0]));
        param.setResult(Boolean.TRUE);
        return;
    }
    // Update poll-fallback timestamp only when listener path unavailable.
    if (sAsrProcess == null) {
        sLastAsrCallbackTs = SystemClock.elapsedRealtime();
    }
}
```

---

### 4. #9 Overlay 改为 GONE/VISIBLE 缓存

**新增 volatile 字段**（在 `sColorAnimator` 附近）：
```java
private static volatile ViewGroup sOverlayParent;
```

**修改 `resetVolatileState(String reason)`** — overlay 部分：

原来：
```java
LinearLayout ov = sOverlay;
if (ov != null) {
    try {
        ViewParent p = ov.getParent();
        if (p instanceof ViewGroup) {
            ((ViewGroup) p).removeView(ov);
        }
    } catch (Throwable ignore) {
    }
    sOverlay = null;
    sOverlayIcon = null;
    sOverlayLabel = null;
}
```

改为：
```java
LinearLayout ov = sOverlay;
if (ov != null) {
    try {
        ov.setVisibility(View.GONE);
    } catch (Throwable ignore) {
    }
    // Keep sOverlay, sOverlayIcon, sOverlayLabel references for reuse.
}
```

**修改 `ensureOverlay(ClassLoader cl, int toolbarHeight)`**：

在方法最开始（`if (toolbarHeight <= 0) return null;` 之后），加：

```java
// Reuse existing overlay if already attached to the correct parent.
LinearLayout existing = sOverlay;
if (existing != null) {
    ViewParent existingParent = existing.getParent();
    Object inputView = getInputView(cl);
    if (existingParent instanceof FrameLayout && existingParent == inputView) {
        if (existing.getVisibility() != View.VISIBLE) {
            existing.setVisibility(View.VISIBLE);
        }
        updateOverlayLayout(existing, toolbarHeight);
        return existing;
    }
    // Parent changed (rare) — detach old and recreate below.
    try {
        if (existingParent instanceof ViewGroup) {
            ((ViewGroup) existingParent).removeView(existing);
        }
    } catch (Throwable ignore) {}
    sOverlay = null;
    sOverlayIcon = null;
    sOverlayLabel = null;
}
```

删除原来方法里的这段（已被上面替代）：
```java
LinearLayout existing = sOverlay;
if (existing != null && existing.getParent() == parent) {
    updateOverlayLayout(existing, toolbarHeight);
    return existing;
}
if (existing != null) {
    try {
        ViewParent p = existing.getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).removeView(existing);
    } catch (Throwable ignore) {}
}
```

---

### 5. #10 Toolbar height 在 session 开始缓存

**新增 volatile 字段**（在 volatile 状态区）：
```java
private static volatile int sCachedToolbarHeight = -1;
```

**修改 `installImeLifecycleHook(ClassLoader cl)`**，在已有 `onFinishInput` / `onFinishInputView` hook 旁边新增 `onStartInputView` hook：

```java
try {
    XposedHelpers.findAndHookMethod(IME_SERVICE, cl, "onStartInputView",
            EditorInfo.class, boolean.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int h = readToolbarHeight(sClassLoader);
                    if (h > 0) {
                        sCachedToolbarHeight = h;
                    }
                }
            });
    log("hooked " + IME_SERVICE + "#onStartInputView");
} catch (Throwable t) {
    log("ERR hook onStartInputView: " + t.getClass().getSimpleName());
}
```

**修改 `resetVolatileState(String reason)`**，加一行：
```java
sCachedToolbarHeight = -1;
```

**修改 `readToolbarHeight(ClassLoader cl)` 的所有调用点**（`maybeUpdateZone`、`ensureOverlay`、touch hook 里的 `readToolbarHeight(cl)` 调用），改为：

```java
int tbH = (sCachedToolbarHeight > 0) ? sCachedToolbarHeight : readToolbarHeight(cl);
```

注意：`readToolbarHeight` 方法本身**保留不删**（仍用于初次填充缓存 + 非 session 场景兜底）。

---

## 验收条件

1. `grep -c "COMMIT_TAIL_DELAY_MS" app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java` → `0`

2. `grep -c "readCurrentEditboxAction" app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java` 仍 ≥ 1（方法存在），但 `resolveEnterOrdinal` 方法体内**不**调用它

3. `grep -c "sCachedToolbarHeight" app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java` → ≥ 3（声明 + 写 + 读）

4. `grep -c "onStartInputView" app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java` → ≥ 1

5. `grep -c "sAsrProcess == null" app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java` → 2（两个 hook 各一处）

6. `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL

## 约束

- 只改 `DoubaoLetterLongPressHook.java`
- 不改任何功能逻辑，只做结构/清理改动
- 不 git add / git commit / 自测
