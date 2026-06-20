# HANDOFF v1.6.0 — perf/code/UI（执行规格）

目标文件：`app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java`（除版本号在 `app/build.gradle`）。
**铁律**：不改 commit/cancel 逻辑、不改 `USER_INPUT_SOURCES`、不改触发门槛/几何判定、不改 dispatch 路由（`commitAndDispatchToolbarAction`/`dispatchViaAsrManagerT`/`dispatchNewlineFast`/`dispatchViaA11ySend`）。**只做下面 4 项。** 不要 commit、不要 push。改完必须 `./gradlew :app:assembleDebug` 通过。

## P1 — 日志门控
1. 在 `TAG` 常量附近加：`private static final boolean DEBUG = false;`
2. 改 `log(String)`：
```java
private static void log(String message) {
    if (!DEBUG) {
        return;
    }
    Log.i(TAG, message);
    XposedBridge.log(TAG + ": " + message);
}
```
不动任何 `log(...)` 调用点。

## P2 — 反射缓存（仅显示热路径）
1. 加字段（与其它 `sXxx` volatile 放一起）：`private static volatile int sRecordingEnterOrdinal = -1;`
2. `installHandlerHook` 的 `handleMessage` 命中分支里，在 `sCurrentZone = Zone.LETTER;` 之后、`param.setResult(null);` 之前加一行：
   `sRecordingEnterOrdinal = resolveEffectiveEnterOrdinal(cl);`
3. `maybeUpdateZone(...)` 里把：`int enterOrdinal = resolveEffectiveEnterOrdinal(cl);`
   改为：`int enterOrdinal = (sRecordingEnterOrdinal >= 0) ? sRecordingEnterOrdinal : resolveEffectiveEnterOrdinal(cl);`
4. `resetVolatileState(...)` 里加：`sRecordingEnterOrdinal = -1;`
5. **不要动** `commitAndDispatchToolbarAction`（它继续实时调 `resolveEffectiveEnterOrdinal`，保证 dispatch 正确）。

## U1 — overlay 发送色读豆包主题
1. 仿照 `resolveDoubaoString` / `resolveDoubaoDimenPx` 加一个：
```java
private static int resolveDoubaoColor(ClassLoader cl, String resName, int fallback) {
    try {
        Class<?> jni = XposedHelpers.findClass(KEYBOARD_JNI, cl);
        Object ime = XposedHelpers.getStaticObjectField(jni, "mImeService");
        if (ime == null) return fallback;
        android.content.Context ctx =
                (android.content.Context) XposedHelpers.callMethod(ime, "getApplicationContext");
        if (ctx == null) return fallback;
        android.content.res.Resources res = ctx.getResources();
        int id = res.getIdentifier(resName, "color", DOUBAO_PACKAGE);
        if (id == 0) return fallback;
        if (Build.VERSION.SDK_INT >= 23) {
            return res.getColor(id, null);
        }
        return res.getColor(id);
    } catch (Throwable t) {
        return fallback;
    }
}
```
2. 加常量：`private static final String COLOR_NAME_SEND = "asr_long_press_navigation_press";`
3. `applyOverlayState(...)` 里发送分支（`else { ... targetColor = COLOR_SEND; }`）把
   `targetColor = COLOR_SEND;` 改为 `targetColor = resolveDoubaoColor(cl, COLOR_NAME_SEND, COLOR_SEND);`
   取消分支（`COLOR_CANCEL`）**不动**。

## C3 — 收尾
- `./gradlew :app:assembleDebug` 必须通过（只允许 deprecation 提示）。
- 扫一眼有没有因改动产生的新 unused（应该没有）。

## 版本号
`app/build.gradle`：`versionCode 7→8`、`versionName "1.5.7"→"1.6.0"`。

## 交付
报告：改了哪些点、build 是否通过、APK 路径。**不要 git commit / push。**
