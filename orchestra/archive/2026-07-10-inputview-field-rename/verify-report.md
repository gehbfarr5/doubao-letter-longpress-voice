# Verify Report — v1.6.2 (getInputView 字段改名修复 x→y)

**Baseline commit:** `d7e8e6f2777a78bf911dec2a0cdc5c265bb0b88c`
**Working tree state at verify time:** uncommitted (Executor did not commit)
**Verifier:** independent fresh-context agent, machine evidence only.

---

## Overall verdict: PASS

All 6 machine-checkable acceptance criteria from `orchestra/task-current.md` pass with real command output. Code review confirms the implementation matches the HANDOFF reference implementation byte-for-byte in logic (fast path `"y"` + `FrameLayout` type-scan fallback, not a naive `x`→`y` string swap). Diff scope is clean — only `getInputView()` + one import changed in the Java file, plus the version bump and README prose. No untouched-per-spec code (`AsrManager.s0/t0`, `effectiveToolbarHeight`, `KeyboardJni`) was touched. Real-device smoke test could not be run (documented LSPosed/zygisk blocker, unrelated to this bug) — this is a known, pre-declared limitation, not a verifier failure.

---

## 1. Diff scope

`git status --short`:
```
 M README.md
 M app/build.gradle
 M app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java
 M orchestra/cost-ledger.tsv
?? orchestra/PLAN-v1.6.1-godclass-split.md
```
`README.md`, `app/build.gradle`, the hook Java file match the task's declared scope exactly. `orchestra/cost-ledger.tsv` and the untracked `PLAN-v1.6.1-godclass-split.md` are orchestration bookkeeping, not app code — not flagged as a concern.

## 2. Machine-checkable acceptance criteria (task-current.md §"机器可检验收条件")

| # | Command | Expected | Actual | Verdict |
|---|---|---|---|---|
| 1 | `grep -c 'getStaticObjectField(imeServiceCls, "x")' app/src/main/java/.../DoubaoLetterLongPressHook.java` | `0` | `0` | PASS |
| 2 | `grep -c 'FrameLayout.class.isAssignableFrom' app/src/main/java/.../DoubaoLetterLongPressHook.java` | `>= 1` | `1` | PASS |
| 3 | `grep -c '"y"' app/src/main/java/.../DoubaoLetterLongPressHook.java` | `>= 1` | `1` | PASS |
| 4 | `./gradlew :app:lintDebug` | exit 0 | `BUILD SUCCESSFUL`, exit 0 | PASS |
| 5 | `./gradlew :app:assembleDebug` | exit 0 | `BUILD SUCCESSFUL`, exit 0 | PASS |
| 6 | `grep -c 'versionName "1.6.2"' app/build.gradle` | `1` | `1` | PASS |

Environment used: `JAVA_HOME=/opt/homebrew/opt/openjdk@17` (JDK 17.0.19), `sdk.dir=/Users/jin/Library/Android/sdk` from `local.properties`. Both were already correctly configured on this machine — no environment workaround was needed, unlike some prior verify rounds on other machines.

`assembleDebug` produced `app/build/outputs/apk/debug/app-debug.apk` (46271 bytes, timestamp 2026-07-10 11:57), confirming the build actually emitted an artifact, not just a cached UP-TO-DATE task graph.

## 3. Code review — `getInputView()` implementation

Current implementation (`app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java:1503-1524`):

```java
private static Object getInputView(ClassLoader cl) {
    try {
        Class<?> imeServiceCls = XposedHelpers.findClass(IME_SERVICE, cl);
        try {
            return XposedHelpers.getStaticObjectField(imeServiceCls, "y");
        } catch (Throwable ignore) {
        }
        for (Field f : imeServiceCls.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())
                    && FrameLayout.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                Object v = f.get(null);
                if (v != null) {
                    return v;
                }
            }
        }
    } catch (Throwable t) {
        log("ERR getInputView: " + t.getClass().getSimpleName());
    }
    return null;
}
```

Comparing against `orchestra/HANDOFF-v1.3.14-inputview-field-rename.md`'s recommended fix (lines 52-72): **the implementation is an exact match**, logic-for-logic:

- **Not a naive `x`→`y` swap** — confirmed. There's a fast-path `getStaticObjectField(imeServiceCls, "y")` wrapped in its own try/catch that swallows failure and falls through, followed by a type-scan loop over `imeServiceCls.getDeclaredFields()`. This is the self-healing pattern the task explicitly required, mirroring the existing `extractKeyboardView()` precedent (`SoftReference`-type scan) elsewhere in the file.
- **Type-scan fallback correctness**:
  - `Modifier.isStatic(f.getModifiers())` — present (line 1511), matches HANDOFF exactly.
  - `FrameLayout.class.isAssignableFrom(f.getType())` — present (line 1512), correctly matches `InputView` (a `FrameLayout` subclass) or any subclass, not just exact `FrameLayout`.
  - `f.setAccessible(true)` — present (line 1513), required since the field is `private static`.
  - Non-null check (`if (v != null) return v;`) — present (line 1514-1516), so an unset/null static field of the right type is correctly skipped rather than short-circuiting the scan.
  - `import java.lang.reflect.Modifier;` was added (diff line 32); `FrameLayout` was already imported (`android.widget.FrameLayout`, confirmed at line 24, pre-existing).
- **Error handling improved, not regressed**: baseline silently returned `null` on any `Throwable` with no log line; new code logs `"ERR getInputView: " + t.getClass().getSimpleName()` before returning `null`, consistent with the file's existing `log("ERR ...")` convention and matching the task's explicit requirement to "保留原有的 log("ERR getInputView: ...") 风格" (the log call didn't actually exist in the pre-fix baseline for this method — this is a net improvement, not a regression).

## 4. Call-site regression check

`ensureOverlay()` and `callInputViewCloseAsrUi()` were required to keep working. Confirmed via `grep -n "getInputView"`:
```
707:        Object inputView = getInputView(cl);
889:        Object inputView = getInputView(cl);
1503:    private static Object getInputView(ClassLoader cl) {
1521:            log("ERR getInputView: " + t.getClass().getSimpleName());
1757:            Object inputView = getInputView(cl);
1776:        Object inputView = getInputView(cl);
```
All four call sites (707, 889, 1757, 1776) are outside the diff hunk — `git diff` confirms the only hunk in the Java file is inside `getInputView()`'s own body plus the one new import line. The method's signature (`private static Object getInputView(ClassLoader cl)`) and return contract (`Object`, `null` on failure) are unchanged, so no caller-side changes were needed and none were made.

## 5. "Do not touch" scope check

Per HANDOFF and task-current.md, `AsrManager.s0/t0`, `effectiveToolbarHeight`, and `KeyboardJni`-related reflection were explicitly out of scope. Full diff of the Java file:

```
git diff d7e8e6f2777a78bf911dec2a0cdc5c265bb0b88c -- app/src/main/java/.../DoubaoLetterLongPressHook.java
```
shows exactly one hunk: the new `import java.lang.reflect.Modifier;` line and the rewritten body of `getInputView()` (comment + method). `grep -i "AsrManager\|effectiveToolbarHeight\|KeyboardJni"` piped through the diff output returns nothing — none of those terms appear anywhere in the diff. Confirmed untouched.

## 6. Other diffs (build.gradle, README.md)

`app/build.gradle`: `versionCode 9`→`10`, `versionName "1.6.1"`→`"1.6.2"`. Matches task requirement.

`README.md`: two lines updated — the compatibility table row and the "已知限制" bullet — both append "以及 `ImeService.InputView` 静态字段 `x→y` 改名" / "v1.3.14 已补 `ImeService.InputView` 静态字段 `x→y` 改名兼容" in the same style as the existing `AsrManager.s0/t0` note. Matches task requirement (same行文风格, additive, no unrelated rewrite).

## 7. Environment / scope limitations

- **Real-device smoke test**: not performed. This is a pre-declared, documented limitation (task-current.md §"说明：本轮无法做真机烟测" and HANDOFF §"Verification blocked") — LSPosed/zygisk daemon does not currently hook on this rebuilt device, unrelated to this bug. Code review is the substitute gate this round; badge-appears-on-slide behavior should be manually confirmed once LSPosed is working again.
- **Lint/build environment**: fully functional on this machine — JDK 17 and Android SDK were both already configured, no workaround needed. No environment blocker to report here.

---

## Summary

6/6 machine-checkable criteria PASS with real command output (not assumed). Code review confirms the fix is the intended self-healing fast-path + type-scan pattern, not the fragile string-swap anti-pattern the task explicitly warned against. Diff is scoped exactly to the declared files with no incursion into declared-off-limits code. The only gap is real-device confirmation, which was known-blocked before this round started and is explicitly deferred, not silently skipped.

**Safe to commit.** Real-device badge-appears verification remains outstanding and should happen once the LSPosed/zygisk environment is restored.

---

## Addendum (2026-07-10, later same day): real-device verification completed

The "LSPosed/zygisk daemon does not hook on this device" premise behind §7's
deferral was wrong — a misdiagnosis from checking the wrong evidence
(`ps`/`proc-maps` instead of `lsof .cli_sock` / the lspd verbose log; see the
correction in the OnePlus 15 Codex skill's `oneplus-app-projects.md`).
LSPosed/Vector-SR was working the whole time. v1.6.2 was installed on-device,
`com.bytedance.android.doubaoime` force-stopped to pick up the fresh hook, and
a real long-press-and-slide gesture was driven via `adb shell input
touchscreen motionevent`: sliding into the toolbar zone showed the blue
"换行" badge, sliding out showed the red "撤回输入" cancel badge — both
correctly attached and rendered. **Real-device confirmation: PASS.** No
outstanding verification gap remains for this fix.
