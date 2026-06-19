# HANDOFF: 替换 pollAsrSettleThen → L.a all-back listener（Task C）

## 背景

基于逆向结果（`orchestra/DOUBAO-INTERNALS.md`），豆包空格长按"滑发送"使用的是一个
**`L.a` all-back listener** 来判断 ASR 全部回调完成，而不是轮询 callback 静默时间。

本任务只改 `DoubaoLetterLongPressHook.java` 一个文件，把当前两个发送路径的
"静默轮询" 替换为"注册 listener → 收 `s.g()==true` 回调 → 执行动作"，
并保留原 poll 作为 listener 注册失败时的 fallback。

## 涉及的已知符号（来自逆向）

| 符号 | 值 |
|---|---|
| AsrProcess 类 | `com.bytedance.android.input.speech.z` |
| AsrManager.b 字段 | `static z b`（AsrProcess 单例，在 `com.bytedance.android.input.speech.AsrManager`） |
| all-back listener 接口 | `com.bytedance.android.input.speech.L$a`（内部接口，JVM 名用 `$`） |
| listener 方法 | `void a(com.bytedance.android.input.speech.s)` |
| ASR callback info 类 | `com.bytedance.android.input.speech.s` |
| 完成判断 | `s.g() == true`（isAllBack） |
| listener 注册方法 | `z.w(L$a listener)`（传 null 注销） |

## 要做的改动（只改 DoubaoLetterLongPressHook.java）

### 1. 新增常量（在已有 ASR_MANAGER 常量附近）

```java
private static final String ASR_PROCESS_CLS =
        "com.bytedance.android.input.speech.z";
private static final String ASR_ALL_BACK_LISTENER_CLS =
        "com.bytedance.android.input.speech.L$a";
```

### 2. 新增 volatile 字段（在 sAsrResolveAttempted 之后）

```java
private static volatile Object sAsrProcess;
private static volatile boolean sAsrProcessResolveAttempted;
private static volatile Class<?> sListenerCls;
private static volatile boolean sListenerClsResolveAttempted;
```

### 3. 新增两个 lazy resolver（在 ensureAsrManager 之后）

**ensureAsrProcess** — 读 `AsrManager.b`（lazy，同 ensureAsrManager 的防御姿势）：

```java
private static Object ensureAsrProcess(ClassLoader cl) {
    if (sAsrProcess != null) return sAsrProcess;
    if (sAsrProcessResolveAttempted) return null;
    sAsrProcessResolveAttempted = true;
    try {
        Class<?> cls = XposedHelpers.findClass(ASR_MANAGER, cl);
        sAsrProcess = XposedHelpers.getStaticObjectField(cls, "b");
        log("AsrProcess resolved: " + (sAsrProcess != null));
    } catch (Throwable t) {
        log("ERR ensureAsrProcess: " + t.getClass().getSimpleName());
    }
    return sAsrProcess;
}
```

**ensureListenerCls** — 找 `L$a` 接口类：

```java
private static Class<?> ensureListenerCls(ClassLoader cl) {
    if (sListenerCls != null) return sListenerCls;
    if (sListenerClsResolveAttempted) return null;
    sListenerClsResolveAttempted = true;
    try {
        sListenerCls = XposedHelpers.findClass(ASR_ALL_BACK_LISTENER_CLS, cl);
        log("L$a listener class resolved: " + (sListenerCls != null));
    } catch (Throwable t) {
        log("ERR ensureListenerCls: " + t.getClass().getSimpleName());
    }
    return sListenerCls;
}
```

### 4. 新增 safeW helper（私有，内部用）

```java
private static void safeW(Object proc, Class<?> lcls, Object listener) {
    try {
        XposedHelpers.callMethod(proc, "w",
                new Class<?>[]{lcls}, new Object[]{listener});
    } catch (Throwable ignore) {}
}
```

### 5. 新增 subscribeAsrAllBackThen（核心，替换 pollAsrSettleThen 的调用点）

```java
/**
 * Replaces {@link #pollAsrSettleThen}: registers a one-shot {@code L$a}
 * all-back listener on {@code AsrManager.b} (AsrProcess). When
 * {@code s.g()==true} fires the terminal action runs on the main thread.
 * Falls back to {@link #pollAsrSettleThen} if listener or AsrProcess
 * cannot be resolved, or if {@code z.w()} throws.
 *
 * <p>Register BEFORE calling {@code p0()} so the all-back event is not missed.
 */
private static void subscribeAsrAllBackThen(
        final ClassLoader cl, final long maxWaitMs, final Runnable terminal) {
    Object proc = ensureAsrProcess(cl);
    Class<?> lcls = ensureListenerCls(cl);
    if (proc == null || lcls == null) {
        log("subscribeAsrAllBack: fallback poll (proc=" + (proc != null)
                + " lcls=" + (lcls != null) + ")");
        pollAsrSettleThen(cl, NEWLINE_ASR_SETTLE_MS, maxWaitMs,
                SystemClock.elapsedRealtime(), terminal);
        return;
    }
    final boolean[] done = {false};
    final Runnable[] timeoutRef = {null};
    Object listener;
    try {
        listener = java.lang.reflect.Proxy.newProxyInstance(
                cl,
                new Class<?>[]{lcls},
                (proxy, method, args) -> {
                    if (!"a".equals(method.getName())
                            || args == null || args.length != 1) {
                        return null;
                    }
                    try {
                        boolean allBack =
                                (Boolean) XposedHelpers.callMethod(args[0], "g");
                        if (!allBack || done[0]) return null;
                        done[0] = true;
                        if (timeoutRef[0] != null) {
                            sMainHandler.removeCallbacks(timeoutRef[0]);
                        }
                        safeW(proc, lcls, null);
                        sMainHandler.post(terminal);
                        log("asr-allback: s.g()=true -> terminal");
                    } catch (Throwable t) {
                        log("ERR allBack listener: " + t.getClass().getSimpleName());
                    }
                    return null;
                });
    } catch (Throwable t) {
        log("ERR Proxy L$a: " + t.getClass().getSimpleName() + " -> poll fallback");
        pollAsrSettleThen(cl, NEWLINE_ASR_SETTLE_MS, maxWaitMs,
                SystemClock.elapsedRealtime(), terminal);
        return;
    }
    Runnable timeout = () -> {
        if (done[0]) return;
        done[0] = true;
        safeW(proc, lcls, null);
        log("asr-allback: TIMEOUT " + maxWaitMs + "ms -> terminal");
        terminal.run();
    };
    timeoutRef[0] = timeout;
    try {
        safeW(proc, lcls, listener);
        sMainHandler.postDelayed(timeout, maxWaitMs);
        log("asr-allback: listener registered timeout=" + maxWaitMs + "ms");
    } catch (Throwable t) {
        log("ERR asrProcess.w(): " + t.getClass().getSimpleName() + " -> poll fallback");
        sMainHandler.removeCallbacks(timeout);
        pollAsrSettleThen(cl, NEWLINE_ASR_SETTLE_MS, maxWaitMs,
                SystemClock.elapsedRealtime(), terminal);
    }
}
```

### 6. 修改 dispatchNewlineFast

**关键**：listener 注册要在 `p0()` **之前**，避免错过 all-back 事件。

原来：
```java
private static void dispatchNewlineFast(final ClassLoader cl) {
    Object mgr = ensureAsrManager(cl);
    if (mgr != null) {
        try {
            XposedHelpers.callMethod(mgr, "p0", false, "");
            log("p0(false,\"\") fired (newline path)");
        } catch (Throwable e) {
            log("ERR p0(): " + e.getClass().getSimpleName());
        }
    }
    pollAsrSettleAndEnter(cl,
            NEWLINE_ASR_SETTLE_MS,
            NEWLINE_ASR_MAX_WAIT_MS,
            SystemClock.elapsedRealtime());
}
```

改成：
```java
private static void dispatchNewlineFast(final ClassLoader cl) {
    // Register listener BEFORE p0() to avoid missing the all-back callback.
    subscribeAsrAllBackThen(cl, NEWLINE_ASR_MAX_WAIT_MS, () -> sendEnterKey(cl));
    Object mgr = ensureAsrManager(cl);
    if (mgr != null) {
        try {
            XposedHelpers.callMethod(mgr, "p0", false, "");
            log("p0(false,\"\") fired (newline path)");
        } catch (Throwable e) {
            log("ERR p0(): " + e.getClass().getSimpleName());
        }
    }
}
```

### 7. 修改 dispatchViaA11ySend

同样，listener 注册在 `p0()` **之前**。

原来：
```java
private static void dispatchViaA11ySend(final ClassLoader cl, final String pkg) {
    Object mgr = ensureAsrManager(cl);
    if (mgr != null) {
        try {
            XposedHelpers.callMethod(mgr, "p0", false, "");
            log("p0(false,\"\") fired (a11y send path pkg=" + pkg + ")");
        } catch (Throwable e) {
            log("ERR p0() a11y: " + e.getClass().getSimpleName());
        }
    }
    pollAsrSettleThen(cl,
            NEWLINE_ASR_SETTLE_MS,
            NEWLINE_ASR_MAX_WAIT_MS,
            SystemClock.elapsedRealtime(),
            () -> broadcastA11ySend(cl, pkg));
}
```

改成：
```java
private static void dispatchViaA11ySend(final ClassLoader cl, final String pkg) {
    // Register listener BEFORE p0() to avoid missing the all-back callback.
    subscribeAsrAllBackThen(cl, NEWLINE_ASR_MAX_WAIT_MS, () -> broadcastA11ySend(cl, pkg));
    Object mgr = ensureAsrManager(cl);
    if (mgr != null) {
        try {
            XposedHelpers.callMethod(mgr, "p0", false, "");
            log("p0(false,\"\") fired (a11y send path pkg=" + pkg + ")");
        } catch (Throwable e) {
            log("ERR p0() a11y: " + e.getClass().getSimpleName());
        }
    }
}
```

### 8. 保留不改的内容（重要）

以下方法**保留不动**（作为 fallback 被 subscribeAsrAllBackThen 内部调用）：
- `pollAsrSettleThen(...)` — 保留原样
- `pollAsrSettleAndEnter(...)` — 保留原样（内部仍调 pollAsrSettleThen）
- `sLastAsrCallbackTs` 及其在 `onAsrSetPreedit` / `onAsrCommitPreeditText` hook 里的更新 — 保留
- `NEWLINE_ASR_POLL_MS`、`NEWLINE_ASR_SETTLE_MS`、`NEWLINE_ASR_MAX_WAIT_MS` — 全部保留

以下 volatile 状态在 `resetVolatileState` 里**不需要清理**（listener 有自己的 `done[]` flag + timeout 清理）。

## 验收条件

### grep 检查（Verifier 会跑）

1. 新常量存在：
   `grep -c "ASR_PROCESS_CLS" app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java` → `1`

2. 新方法存在：
   `grep -c "subscribeAsrAllBackThen" app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java` → ≥ 3（定义 + 两处调用 + 内部 fallback 引用）

3. listener 注册在 p0 之前（newline path）：
   在 `dispatchNewlineFast` 方法体内，`subscribeAsrAllBackThen` 在 `p0` 之前（先出现）

4. listener 注册在 p0 之前（a11y path）：
   在 `dispatchViaA11ySend` 方法体内，`subscribeAsrAllBackThen` 在 `p0` 之前

5. poll fallback 保留：
   `grep -c "pollAsrSettleThen" app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java` → ≥ 1

6. 回归——不得出现 pollAsrSettleAndEnter / pollAsrSettleThen 的外部调用（只能在 subscribeAsrAllBackThen 内作 fallback 或被原方法自调）：
   `dispatchNewlineFast` 方法体内**不**含 `pollAsrSettleAndEnter` 或 `pollAsrSettleThen`
   `dispatchViaA11ySend` 方法体内**不**含 `pollAsrSettleThen`

### 编译

`./gradlew :app:assembleDebug` → BUILD SUCCESSFUL（exit 0）

## 约束

- 只改 `DoubaoLetterLongPressHook.java`，其它文件不动
- `sAsrProcess` / `sListenerCls` 必须 lazy resolve（不在 `handleLoadPackage` 时触碰）
- `pollAsrSettleThen` / `pollAsrSettleAndEnter` 保留原样，不删不改
- 不 git add / git commit / 自测
