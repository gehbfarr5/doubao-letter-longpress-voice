# HANDOFF Batch 3 — Cancel 精确化诊断 + 初步优化 v1.5.3

## 任务类型
代码改动，走完整流程。只改 `DoubaoLetterLongPressHook.java`。

## 背景

当前 `cancelVoice()` 的 cancel 抑制窗口为固定 1200ms：
```java
private static final long CANCEL_WINDOW_MS = 1200L;

private static void cancelVoice(ClassLoader cl) {
    sCancelUntilElapsed = SystemClock.elapsedRealtime() + CANCEL_WINDOW_MS;
    // finishPreedit + p0(true, cancel)
}
```

问题：cancel 通常在 200-400ms 内完成，但窗口固定 1200ms，导致 cancel 后键盘无响应时间过长。

**本批次目标**：
1. 插入诊断探针（仅 log，不改行为）—— 确认 `p0(true,"cancel")` 后是否触发 `L.a` all-back
2. 把 `CANCEL_WINDOW_MS` 从 1200ms 降到 500ms（保守、安全的初步优化）

---

## 改动清单（2 项）

### 1. 诊断探针：cancelVoice 后注册只 log 的 all-back 监听器

在 `cancelVoice(ClassLoader cl)` 末尾（`p0(true, "cancel")` 调用之后），追加：

```java
// Diagnostic probe: log whether L.a all-back fires on cancel (does NOT change behavior).
subscribeAsrAllBackThen(cl, 2000L, null);
```

解释：
- `subscribeAsrAllBackThen(cl, 2000L, null)` 会注册一个 all-back 监听器，等待最多 2000ms
- 若 all-back 触发（`s.g()==true`）→ log 会出现 `asr-allback: s.g()=true`
- 若超时无触发 → log 会出现 `asr-allback: TIMEOUT`
- `terminal` 为 `null` 时，`subscribeAsrAllBackThen` 在收到回调后只需安全地 no-op

**检查 `subscribeAsrAllBackThen` 如何处理 null terminal**：
找到该方法的实现，确认在 terminal 为 null 时有 null 检查（`if (terminal != null) terminal.run()`）。
如果**没有** null 检查，则在调用 `terminal.run()` 之前加上 null 守卫：
```java
if (terminal != null) {
    sMainHandler.post(terminal);
}
```
（或类似，保持与原来 terminal 调用风格一致即可）

### 2. 把 CANCEL_WINDOW_MS 从 1200ms 降到 500ms

找到常量声明行（约 line 127）：
```java
private static final long CANCEL_WINDOW_MS = 1200L;
```

改为：
```java
private static final long CANCEL_WINDOW_MS = 500L;
```

---

## 验收条件

1. `grep -c "subscribeAsrAllBackThen" DoubaoLetterLongPressHook.java` → ≥ 4
   （cancelVoice 里的诊断探针是新增的一处）

2. `grep -c "CANCEL_WINDOW_MS = 500" DoubaoLetterLongPressHook.java` → 1
   （常量值已改）

3. `grep -c "CANCEL_WINDOW_MS = 1200" DoubaoLetterLongPressHook.java` → 0
   （旧值已删）

4. `subscribeAsrAllBackThen` 在 `terminal == null` 时安全（不 NPE）
   Read `subscribeAsrAllBackThen` 方法体，找到所有 `terminal.run()` 或 `terminal.xxx` 的调用点，
   确认有 `if (terminal != null)` 守卫或等价保护。

5. `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL

---

## 约束

- 只改 `DoubaoLetterLongPressHook.java`
- 诊断探针仅 log，不改任何功能逻辑
- 不 git add / git commit / 自测
