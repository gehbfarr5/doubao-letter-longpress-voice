# HANDOFF Batch 2 — Hook 改进 v1.5.2

## 任务类型
代码改动，走完整流程。只改 `DoubaoLetterLongPressHook.java`。

## 背景

### #2 scheduleAsrStartVerification 当前问题

当前 `scheduleAsrStartVerification()` 做法：
```java
private static final long ASR_START_VERIFY_DELAY_MS = 80L;

private static void scheduleAsrStartVerification(final ClassLoader cl) {
    sMainHandler.postDelayed(new Runnable() {
        @Override public void run() {
            if (!sSuppressNextUp) return;
            Object mgr = ensureAsrManager(cl);
            if (mgr == null) return;
            try {
                Object running = XposedHelpers.callMethod(mgr, "E");
                if (Boolean.FALSE.equals(running)) {
                    log("ASR did NOT start (E()=false) -> rollback suppress");
                    sSuppressNextUp = false;
                    sMaxDisplacementSq = 0f;
                }
            } catch (Throwable t) {
                log("ERR ASR start verify: " + t.getClass().getSimpleName());
            }
        }
    }, ASR_START_VERIFY_DELAY_MS);
}
```

问题：80ms 是 magic number，低端机可能不够；`E()` 只是查询 bool，不是事件驱动。

### #11 sLastAsrCallbackTs 当前问题

当前 `sLastAsrCallbackTs` 由 `onAsrSetPreedit` / `onAsrCommitPreeditText` 更新，
但这两个是 UI 回调，可能比底层 ASR 引擎完成时间晚数百毫秒。

`AsrContext.T(int phase, boolean done)` 是更精确的信号：
- phase=1：流式结果结束
- phase=2：第二次结果结束（最终完成）

---

## 改动清单（2 项）

### 1. #2 scheduleAsrStartVerification → 改为 onAsrSetPreedit 首次触发确认

**Step A — 新增 volatile 字段**（在 `sSuppressNextUp` 附近的 volatile 状态区）：
```java
private static volatile boolean sAsrStartConfirmed = false;
```

**Step B — 修改 `onAsrSetPreedit` hook**（在 `installCommitSuppressionHooks` 里）：

当前代码（找到这段，约 line 566）：
```java
XposedHelpers.findAndHookMethod(KEYBOARD_JNI, cl, "onAsrSetPreedit",
        String.class,
        new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isInCancelWindow()) {
                    log("onAsrSetPreedit SWALLOWED text="
                            + safeText((String) param.args[0]));
                    param.setResult(Boolean.TRUE);
                    return;
                }
                // Update poll-fallback timestamp only when listener path unavailable.
                if (sAsrProcess == null) {
                    sLastAsrCallbackTs = SystemClock.elapsedRealtime();
                }
            }
        });
```

改为：
```java
XposedHelpers.findAndHookMethod(KEYBOARD_JNI, cl, "onAsrSetPreedit",
        String.class,
        new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isInCancelWindow()) {
                    log("onAsrSetPreedit SWALLOWED text="
                            + safeText((String) param.args[0]));
                    param.setResult(Boolean.TRUE);
                    return;
                }
                // Update poll-fallback timestamp only when listener path unavailable.
                if (sAsrProcess == null) {
                    sLastAsrCallbackTs = SystemClock.elapsedRealtime();
                }
                // Confirm ASR actually started on first preedit output.
                if (sSuppressNextUp && !sAsrStartConfirmed) {
                    sAsrStartConfirmed = true;
                    log("AsrStartConfirmed via onAsrSetPreedit");
                }
            }
        });
```

**Step C — 修改 `scheduleAsrStartVerification`**：

把 delay 从 80ms 改为 300ms，把条件改为检查 `sAsrStartConfirmed`：

```java
private static void scheduleAsrStartVerification(final ClassLoader cl) {
    sMainHandler.postDelayed(new Runnable() {
        @Override public void run() {
            if (!sSuppressNextUp) return;
            if (sAsrStartConfirmed) return;
            // 300ms 内无任何 onAsrSetPreedit 输出 → ASR 未启动
            log("ASR did NOT start (no preedit in 300ms) -> rollback suppress");
            sSuppressNextUp = false;
            sMaxDisplacementSq = 0f;
        }
    }, 300L);
}
```

注意：同时把常量 `ASR_START_VERIFY_DELAY_MS = 80L` 改为 `300L`（或直接把常量改值，保留常量名），
或直接在 postDelayed 里写 `300L` 并删掉常量。**推荐：删掉常量，直接写字面量 `300L`**（常量只用了一处）。

**Step D — 在 `resetVolatileState(String reason)` 里加**：
```java
sAsrStartConfirmed = false;
```
（放在 `sSuppressNextUp = false;` 附近）

---

### 2. #11 新增 AsrContext.T hook

在 `installCommitSuppressionHooks(final ClassLoader cl)` 方法末尾（`onAsrSetPreedit` hook 块之后），
新增一个 try-catch 块来 hook `AsrContext.T`：

```java
try {
    XposedHelpers.findAndHookMethod(
            "com.bytedance.android.input.speech.AsrContext", cl,
            "T", int.class, boolean.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    boolean isDone = Boolean.TRUE.equals(param.args[1]);
                    if (isDone) {
                        if (sAsrProcess == null) {
                            sLastAsrCallbackTs = SystemClock.elapsedRealtime();
                        }
                        log("AsrContext.T phase=" + param.args[0] + " done=true");
                    }
                }
            });
    log("hooked AsrContext#T");
} catch (Throwable t) {
    log("ERR hook AsrContext#T: " + t.getClass().getSimpleName());
}
```

注意：`AsrContext.T` 可能混淆成其他名字。如果 hook 失败（ERR），不影响功能（有兜底）。

---

## 验收条件

1. `grep -c "sAsrStartConfirmed" DoubaoLetterLongPressHook.java` → ≥ 4
   （声明 + resetVolatileState + scheduleAsrStart check + onAsrSetPreedit set）

2. `grep -c "ASR_START_VERIFY_DELAY_MS" DoubaoLetterLongPressHook.java` → 0
   （常量已删，改为字面量 300L）

3. `grep -c "AsrContext" DoubaoLetterLongPressHook.java` → ≥ 1

4. `grep -c "AsrStartConfirmed via onAsrSetPreedit" DoubaoLetterLongPressHook.java` → 1

5. `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL

---

## 约束

- 只改 `DoubaoLetterLongPressHook.java`
- 不改任何功能逻辑（行为影响：verify 从 80ms 延长到 300ms，更保守；AsrContext.T hook 只增加 log + fallback ts 更新）
- 不 git add / git commit / 自测
