# Verify Report — v1.6.3 (徽章尺寸修复：改用真实 native_candidate_bar 高度)

**Baseline commit:** `c45d0ae01b31f090bc6f30dd1bddaa09372db6d2`
**Working tree state at verify time:** uncommitted (Executor did not commit, no lint/build run prior to this verify)
**Verifier:** independent fresh-context agent, machine evidence only.

---

## Overall verdict: PASS

All 5 machine-checkable acceptance criteria from `orchestra/task-current.md` pass with real command output re-run from scratch in this session. Full `git diff` code review confirms the cascading signature change (`effectiveToolbarHeight` → `isLetterZone`/`computeZone`) is threaded through every call site — 1 def + 1 call for `isLetterZone`, 1 def + 2 calls for `computeZone`, 1 def + 4 calls for `effectiveToolbarHeight`, all passing `cl` — matching the HANDOFF's call-graph enumeration exactly. `readNativeCandidateBarHeight()` is a new, uncached function inserted between the native API and the ratio fallback, with the fallback chain order preserved and nothing deleted. Off-limits code (`getInputView()` body, `AsrManager.s0/t0`, `KeyboardJni`) is untouched. `sCachedToolbarHeight` caching is untouched and not extended to the new function, per HANDOFF's explicit "do not cache" instruction.

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
`README.md`, `app/build.gradle`, the hook Java file match the task's declared scope exactly. `orchestra/cost-ledger.tsv` (new EXEC_DONE row) and the untracked `PLAN-v1.6.1-godclass-split.md` are orchestration bookkeeping, not app code — not flagged as a concern (same pattern as prior verify rounds).

## 2. Machine-checkable acceptance criteria (task-current.md §"机器可检验收条件")

| # | Command | Expected | Actual | Verdict |
|---|---|---|---|---|
| 1 | `grep -c 'native_candidate_bar' app/src/main/java/.../DoubaoLetterLongPressHook.java` | `>= 1` | `3` (constant decl `:189`, comment `:262`, comment `:1417`) | PASS |
| 2 | `grep -c 'effectiveToolbarHeight(ClassLoader' app/src/main/java/.../DoubaoLetterLongPressHook.java` | `>= 1` | `1` (`:1466`, actual signature change, not a dead overload) | PASS |
| 3 | `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:lintDebug` | exit 0 | `BUILD SUCCESSFUL in 1s`, real exit code `0` (re-verified without tee to rule out pipe masking) | PASS |
| 4 | `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug` | exit 0 | `BUILD SUCCESSFUL in 368ms`, real exit code `0` | PASS |
| 5 | `grep -c 'versionName "1.6.3"' app/build.gradle` | `1` | `1` (`:14`) | PASS |

Environment used: `JAVA_HOME=/opt/homebrew/opt/openjdk@17` (JDK 17.0.19), `sdk.dir` from `local.properties`, already correctly configured — no workaround needed. `assembleDebug` produced `app/build/outputs/apk/debug/app-debug.apk` (47K, timestamp 2026-07-10 12:54), confirming a real artifact was emitted, not just a cached UP-TO-DATE task graph (dexBuilderDebug, mergeProjectDexDebug, packageDebug, assembleDebug all executed, not UP-TO-DATE). `lintDebug` also actually executed `compileDebugJavaWithJavac` (only pre-existing deprecation warnings, no errors) and `lintReportDebug`, producing `app/build/reports/lint-results-debug.html`.

## 3. Code review — cascading signature change completeness

Full diff of `effectiveToolbarHeight`/`isLetterZone`/`computeZone` region against `git diff c45d0ae01b31f090bc6f30dd1bddaa09372db6d2`, confirmed line-by-line:

**Fallback order in `effectiveToolbarHeight(ClassLoader cl, int w, int h, int toolbarHeight)`** (`:1466-1479`):
```java
private static int effectiveToolbarHeight(ClassLoader cl, int w, int h, int toolbarHeight) {
    if (toolbarHeight > 0) {
        return toolbarHeight;                    // ① native getToolbarHeight() — unchanged, first
    }
    int live = readNativeCandidateBarHeight(cl);
    if (live > 0) {
        return live;                              // ② new: live native_candidate_bar measurement
    }
    if (w <= 0 || h <= 0) {
        return 0;
    }
    boolean tallToolbar = (h > w * TALL_KBD_H_OVER_W);
    float topRatio = tallToolbar ? TOOLBAR_TOP_TALL : TOOLBAR_TOP_NORMAL;
    return Math.max(1, Math.round(h * topRatio)); // ③ original ratio heuristic — preserved, last resort
}
```
Order matches HANDOFF exactly: ① unchanged native check first, ② new live measurement second, ③ original `TOOLBAR_TOP_NORMAL`/`TOOLBAR_TOP_TALL` ratio math kept intact as final fallback, nothing deleted, nothing reordered.

**`readNativeCandidateBarHeight(ClassLoader cl)`** (`:1447-1464`):
```java
private static int readNativeCandidateBarHeight(ClassLoader cl) {
    try {
        Object inputView = getInputView(cl);
        if (!(inputView instanceof ViewGroup)) {
            return -1;
        }
        ViewGroup vg = (ViewGroup) inputView;
        int id = vg.getResources().getIdentifier(
                RES_ID_NATIVE_CANDIDATE_BAR, "id", DOUBAO_PACKAGE);
        if (id == 0) {
            return -1;
        }
        View bar = vg.findViewById(id);
        return (bar != null && bar.getHeight() > 0) ? bar.getHeight() : -1;
    } catch (Throwable t) {
        return -1;
    }
}
```
Matches HANDOFF's reference implementation byte-for-byte. Single `try/catch (Throwable t)` wraps the entire body — `getInputView(cl)` call, the `instanceof` guard, `getIdentifier` (returns `0` on miss, handled explicitly), `findViewById` (null-checked), and `getHeight()` — so any failure at any step (including a `NullPointerException` from a null `Resources` or a `ClassCastException`) returns `-1` and cannot propagate out to crash the hook. `RES_ID_NATIVE_CANDIDATE_BAR = "native_candidate_bar"` constant declared at `:189`, `DOUBAO_PACKAGE` reused from existing constant (not redefined).

**Cascading signature threading** — full call-graph via `grep -n "effectiveToolbarHeight(\|isLetterZone(\|computeZone("`:
```
374:   if (!isLetterZone(cl, x, y, w, h, kbdType, toolbarHeight)) {
404:   ensureOverlay(cl, effectiveToolbarHeight(cl, w, h, toolbarHeight));
481:   releaseZone = computeZone(cl, ux, uy, vw, vh, tbH);
1411:  private static boolean isLetterZone(ClassLoader cl, int x, int y, int w, int h, int kbdType,
1418:  int topExclusion = effectiveToolbarHeight(cl, w, h, toolbarHeight);
1466:  private static int effectiveToolbarHeight(ClassLoader cl, int w, int h, int toolbarHeight) {
1482:  private static Zone computeZone(ClassLoader cl, float x, float y, int w, int h,
1487:  if (y < effectiveToolbarHeight(cl, w, h, toolbarHeight)) {
1501:  Zone next = computeZone(cl, x, y, w, h, tbH);
1513:  ensureOverlay(cl, effectiveToolbarHeight(cl, w, h, tbH));
```
Tally against HANDOFF's declared call graph:
- `isLetterZone`: 1 definition (`:1411`, `cl` added as first param) + 1 call site (`:374`, Hook 1's `handleMessage`, `cl` already in scope from `readToolbarHeight(cl)` two lines above) — matches "only call site" claim.
- `computeZone`: 1 definition (`:1482`, `cl` added as first param) + 2 call sites (`:481` Hook 2's UP handler, `cl` in scope from `readToolbarHeight(cl)` just above; `:1501` inside `maybeUpdateZone`, which already takes `cl` as its own first param) — matches "two call sites" claim.
- `effectiveToolbarHeight`: 1 definition (`:1466`, `cl` added as first param) + 4 call sites (`:1418` inside `isLetterZone`, `:1487` inside `computeZone`, `:404` Hook 1 direct call, `:1513` `maybeUpdateZone` direct call) — matches HANDOFF's enumeration of 2 internal + 2 direct sites.

Every call site threads the `cl` already in its enclosing scope (verified by reading the surrounding lines at 372-404, 476-481, 1408-1420, 1479-1489, 1498-1513) — none pass a wrong/unrelated `ClassLoader` variable. Since the build compiles cleanly (criterion 4), this is also mechanically confirmed: a missing or type-mismatched argument at any of these 10 sites would be a compile error.

## 4. "Do not touch" scope check

Per HANDOFF §"What NOT to touch": `getInputView()` body, `AsrManager.s0/t0`, `KeyboardJni` reflection.
```
git diff c45d0ae... -- app/src/main/java/.../DoubaoLetterLongPressHook.java | grep -n "getInputView\|AsrManager\|KeyboardJni"
```
Only one match with a `+` prefix: `+ Object inputView = getInputView(cl);` inside the *new* `readNativeCandidateBarHeight()` function — this is a **call** to the existing, unmodified `getInputView()`, not a change to its body. No `AsrManager` or `KeyboardJni` lines appear in the diff at all. Confirmed untouched.

## 5. `sCachedToolbarHeight` caching scope check

Per HANDOFF: "Do not cache `readNativeCandidateBarHeight()`'s result the way `sCachedToolbarHeight` caches the native API's value."
```
grep -n "sCachedToolbarHeight" app/src/main/java/.../DoubaoLetterLongPressHook.java
```
```
294: private static volatile int sCachedToolbarHeight = -1;
357: int toolbarHeight = (sCachedToolbarHeight > 0) ? sCachedToolbarHeight : readToolbarHeight(cl);
479: int tbH = (sCachedToolbarHeight > 0) ? sCachedToolbarHeight : readToolbarHeight(cl);
648: sCachedToolbarHeight = h;
668: sCachedToolbarHeight = -1;
1500: int tbH = (sCachedToolbarHeight > 0) ? sCachedToolbarHeight : readToolbarHeight(cl);
```
All six sites are pre-existing (unchanged by this diff — confirmed none carry a `+`/`-` diff marker). `readNativeCandidateBarHeight()` is called fresh inside `effectiveToolbarHeight()` on every invocation with no memoization variable of its own and no interaction with `sCachedToolbarHeight`. Confirmed the new function is not wired into the existing cache.

## 6. Other diffs (build.gradle, README.md)

`app/build.gradle`: `versionCode 10`→`11`, `versionName "1.6.2"`→`"1.6.3"`. Matches task requirement.

`README.md`: one compatibility-table cell updated, same style as the existing `AsrManager.s0/t0`/`x→y` entries — replaces the stale "`getToolbarHeight()==0` 的顶部工具栏判定" phrase with "`getToolbarHeight()==0` 时改读真实 `native_candidate_bar` 高度修正徽章尺寸", additive and consistent with prior entries, no unrelated rewrite.

## 7. Real-device verification

Not performed by this Verifier — per task-current.md §"真机验证", this step is explicitly deferred to the orchestrator after Verifier machine-check passes ("orchestrator 在 Verifier 机检通过后自己做，不算 Codex 的活"). All 5 machine-checkable gates pass; real-device confirmation of badge height (~201px vs. old ~283px) remains outstanding and is the orchestrator's next step, not a gap in this verify pass.

---

## Summary

5/5 machine-checkable criteria PASS with real command output re-run from a clean, fresh-context session (not trusted from Executor's self-report). Code review confirms the three-tier fallback order is correct and complete (native API → live `native_candidate_bar` measurement → ratio heuristic, nothing deleted or reordered), the cascading `ClassLoader cl` signature change is threaded through all 10 definition/call sites with no gaps (mechanically guaranteed by a clean compile, and manually spot-checked for correct-variable threading), the new function is exception-safe (single `try/catch (Throwable)` wrapping the entire body) and deliberately uncached per spec, and all declared off-limits code (`getInputView()` body, `AsrManager`, `KeyboardJni`) is untouched.

**Safe to commit.** Real-device badge-size confirmation (~201px target) remains outstanding and is the orchestrator's next step per the task's own division of labor, not a blocker discovered by this verify pass.
