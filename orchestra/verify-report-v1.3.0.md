# Verify Report — v1.3.0 ASR-settle newline fix

**Branch**: `feat/asr-settle-newline-fix` HEAD `2d66897`
**Verifier**: Claude Sonnet (independent context)
**Date**: 2026-06-17

---

## Overall Verdict: PASS

---

## Step 1 — Plan Read

`orchestra/PLAN-v1.3.0.md` §4 read. Used as source of truth for all assertions below.

---

## Step 2 — Diff Scope

```
git diff --stat v1.2.0..HEAD
```

Files touched:
- ✅ `app/build.gradle` (4 lines)
- ✅ `app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java` (94 lines)
- ✅ `orchestra/FOLLOW-UPS.md` (new file, 62 lines)
- ✅ `orchestra/PLAN-v1.3.0.md` (new file, 156 lines)

No unexpected files. **PASS**

---

## Step 3 — Build

```
./gradlew :app:assembleDebug
```

- ✅ Exit 0 — `BUILD SUCCESSFUL in 2s`
- ✅ APK exists: `app/build/outputs/apk/debug/app-debug.apk` (33K, 2026-06-17 02:27)

---

## Step 4 — Static Grep Assertions

### Must EXIST

| Assertion | Result | Evidence |
|---|---|---|
| `private static volatile long sLastAsrCallbackTs` field | ✅ | line 269 |
| `sLastAsrCallbackTs = SystemClock.elapsedRealtime()` in `onAsrCommitPreeditText` (non-cancel branch) | ✅ | line 540, after `isInCancelWindow()` return |
| `sLastAsrCallbackTs = SystemClock.elapsedRealtime()` in `onAsrSetPreedit` (non-cancel branch) | ✅ | line 559, after `isInCancelWindow()` return |
| `NEWLINE_ASR_POLL_MS` constant | ✅ | line 138: `= 50L` |
| `NEWLINE_ASR_SETTLE_MS = 100L` | ✅ | line 139 |
| `NEWLINE_ASR_MAX_WAIT_MS = 2000L` | ✅ | line 140 |
| `pollAsrSettleAndEnter(ClassLoader, long, long, long)` method | ✅ | line 741 |
| `asrRespondedAfterDispatch` variable inside `pollAsrSettleAndEnter` | ✅ | line 748 |
| `sLastAsrCallbackTs > startTs` comparison | ✅ | line 748 |
| TIMEOUT log branch | ✅ | line 751: `"asr-settle TIMEOUT waited="` |
| `dispatchNewlineFast` calls `pollAsrSettleAndEnter(cl, NEWLINE_ASR_SETTLE_MS, NEWLINE_ASR_MAX_WAIT_MS, SystemClock.elapsedRealtime())` | ✅ | lines 720-723 (multi-line call) |

### Must be DELETED (0 matches expected)

| Assertion | Result | Evidence |
|---|---|---|
| `NEWLINE_KEY_DELAY_MS` | ✅ | 0 matches |
| `getDiagMode` | ✅ | 0 matches |
| `diagInspect` | ✅ | 0 matches |
| `diagFinishComposing` | ✅ | 0 matches |
| `diagQuote` | ✅ | 0 matches |
| `debug.dispatch_diag` | ✅ | 0 matches |
| `DIAG[` | ✅ | 0 matches |
| `mode == ` | ✅ | 0 matches |

### `app/build.gradle`

| Assertion | Result | Evidence |
|---|---|---|
| `versionCode 4` | ✅ | line 13 |
| `versionName "1.3.0"` | ✅ | line 14 |

---

## Step 5 — Regression Grep (v1.2.0 preserved)

| Assertion | Result | Evidence |
|---|---|---|
| `FORCE_SEND_PACKAGES` = `Collections.singleton("tw.nekomimi.nekogram")` | ✅ | line 161 |
| No `com.anthropic.claude` in file | ✅ | 0 matches for `anthropic\|claude` |
| `isSpecificSendOrdinal` body: `ord == 2 \|\| ord == 3 \|\| ord == 4 \|\| ord == 8` | ✅ | lines 1379-1381 |
| `resolveEffectiveEnterOrdinal` exists | ✅ | line 1324 |
| `currentEditorPackageName` exists | ✅ | line 1087 |
| `commitAndDispatchToolbarAction` exists | ✅ | line 653 |
| `maybeUpdateZone` exists | ✅ | line 1210 |
| `dispatchViaAsrManagerT` exists | ✅ | line 678 |
| `commitVoice` exists | ✅ | line 796 |

---

## Summary

All 30 assertions passed. Build succeeds. APK produced. No diag residue. No scope creep. v1.2.0 regression surface intact.
