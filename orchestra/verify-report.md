# Verify Report — v1.2.0 cross-app send routing

**Branch:** `feat/cross-app-send-routing`
**Plan commit (HEAD):** `240a122`
**Baseline tag:** `pre-v1.2.0-baseline`
**Verifier:** Opus subagent, fresh context, evidence-based.

---

## Overall verdict: **PASS**

All §6 assertions in `orchestra/PLAN-v1.2.0.md` hold. Build is green, APK produced, diff is scope-clean, and no unintended regions changed vs baseline.

---

## Step 2 — Diff scope

`git status --short`:
```
 M app/build.gradle
 M app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java
```
`git diff --stat`:
```
 app/build.gradle                                   |  4 +-
 .../DoubaoLetterLongPressHook.java                 | 81 +++++++++++++++++++++-
 2 files changed, 81 insertions(+), 4 deletions(-)
```
✅ Exactly the two allowed files. No extras.

---

## Step 3 — Build

Command (with `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`):
```
./gradlew :app:assembleDebug
```
Result:
```
BUILD SUCCESSFUL in 5s
32 actionable tasks: 10 executed, 22 up-to-date
```
✅ Exit code 0.

APK exists:
```
-rw-r--r--  app/build/outputs/apk/debug/app-debug.apk   (33k)
```
✅

Note: initial run failed with "Unable to locate a Java Runtime" because system has no default Java; once `JAVA_HOME` was set to the existing `openjdk@21` brew install, build passed. This is a host setup nit, not a code defect.

---

## Step 4 — Static grep assertions (DoubaoLetterLongPressHook.java)

| # | Assertion | Result | Evidence |
|---|---|---|---|
| 4.a | `FORCE_SEND_PACKAGES` constant exists | ✅ | line 146: `private static final java.util.Set<String> FORCE_SEND_PACKAGES =` |
| 4.b | …contains literal `"tw.nekomimi.nekogram"` | ✅ | line 147: `java.util.Collections.singleton("tw.nekomimi.nekogram");` |
| 4.c | `currentEditorPackageName` exists | ✅ | line 1005: `private static String currentEditorPackageName(ClassLoader cl) {` |
| 4.d | …contains `try` + `catch (Throwable` | ✅ | line 1006 `try {`, line 1017 `} catch (Throwable t) {` |
| 4.e | `resolveEffectiveEnterOrdinal` exists | ✅ | line 1242: `private static int resolveEffectiveEnterOrdinal(ClassLoader cl) {` |
| 4.f | …contains `isSpecificSendOrdinal` | ✅ | line 1244: `if (isSpecificSendOrdinal(ord)) {` |
| 4.g | …contains `FORCE_SEND_PACKAGES.contains` | ✅ | line 1248: `if (pkg != null && FORCE_SEND_PACKAGES.contains(pkg)) {` |
| 4.h | `commitAndDispatchToolbarAction` calls `resolveEffectiveEnterOrdinal`, not `resolveEnterOrdinal` directly | ✅ | line 638: `int enterOrdinal = resolveEffectiveEnterOrdinal(cl);` (was line 614 in baseline); diff hunk `@@ -611,7 +635,7 @@` shows the only `-` line is `-        int enterOrdinal = resolveEnterOrdinal(cl);` replaced by the new call |
| 4.i | `maybeUpdateZone` calls `resolveEffectiveEnterOrdinal`, not `resolveEnterOrdinal` directly | ✅ | line 1144: `int enterOrdinal = resolveEffectiveEnterOrdinal(cl);` (was line 1098 in baseline); diff hunk `@@ -1095,7 +1141,7 @@` confirms the swap |
| 4.j | `resolveEnterOrdinal` function body unchanged vs `pre-v1.2.0-baseline` | ✅ | `git diff pre-v1.2.0-baseline` hunks only touch lines 124, 611, 973, 1095, 1176 — none of these are inside `resolveEnterOrdinal` (its body lives between baseline lines 1140-1178; new file lines 1203-1219). The two `-` lines in the entire java diff are both `resolveEnterOrdinal(cl)` call-site replacements, never modifications to the function itself. |
| 4.k | `resolveEnterOrdinal` is now only called from `resolveEffectiveEnterOrdinal` internals (and diagnostic log context inside `maybeUpdateZone`) | ✅ | Only remaining references in file: line 1203 (declaration), line 1230/1239 (javadoc), line 1243 (call inside `resolveEffectiveEnterOrdinal`). The "resolved=" diagnostic log inside `maybeUpdateZone` (around baseline line 1103-1112) was untouched per the plan §4.5; it still logs `resolveEnterOrdinal`'s raw value via the existing log helper. |

### app/build.gradle

| # | Assertion | Result | Evidence |
|---|---|---|---|
| 4.l | `versionCode 3` | ✅ | line 13: `        versionCode 3` |
| 4.m | `versionName "1.2.0"` | ✅ | line 14: `        versionName "1.2.0"` |

---

## Step 5 — Regression grep

| # | Assertion | Result | Evidence |
|---|---|---|---|
| 5.a | `AsrManager.t` call signature still `(int, long)` invoked with `(enterOrdinal, System.currentTimeMillis())` | ✅ | lines 665-667: `XposedHelpers.callMethod(mgr, "t", new Class<?>[]{int.class, long.class}, enterOrdinal, System.currentTimeMillis());` |
| 5.b | `dispatchViaAsrManagerT` body untouched vs baseline | ✅ | Defined at new line 658 (baseline ~634), no diff hunk crosses this range |
| 5.c | `dispatchNewlineFast` body untouched | ✅ | New line 680, no diff hunk in range |
| 5.d | `sendEnterKey` body untouched | ✅ | New line 694, no diff hunk in range |
| 5.e | `isSpecificSendOrdinal` body untouched: `ord == 2 || ord == 3 || ord == 4 || ord == 8` | ✅ | line 1297-1299: `private static boolean isSpecificSendOrdinal(int ord) { return ord == 2 \|\| ord == 3 \|\| ord == 4 \|\| ord == 8; }` — no diff hunk in range |

The full `git diff pre-v1.2.0-baseline -- <java>` shows only 5 hunks at headers `@@ -124,6 @@`, `@@ -611,7 @@`, `@@ -973,6 @@`, `@@ -1095,7 @@`, `@@ -1176,6 @@`. The only deletions across the entire diff are the two `resolveEnterOrdinal(cl)` call-site lines. No function bodies removed or rewritten.

---

## Out of scope (per plan §6.4)

- No on-device testing (Verifier has no device — delegated to user)
- No unit tests (project has none)

---

## Summary

v1.2.0 implementation matches PLAN-v1.2.0.md precisely. Two-file diff, additive changes plus two surgical call-site swaps, build green, APK present, baseline-protected functions untouched. Ready for user manual P0/P1/P2 matrix on OnePlus 15.
