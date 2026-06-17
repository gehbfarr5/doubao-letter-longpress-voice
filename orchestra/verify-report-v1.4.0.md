# Verify Report — v1.4.0 (Claude/ChatGPT a11y send)

Branch: feat/claude-chatgpt-a11y-send (uncommitted working tree)
Verifier: independent, fresh context, machine evidence only.

## Overall Verdict: PASS

---

## 1. Diff scope — PASS
`git status --short` / `git diff --stat`:
- M app/build.gradle
- M app/src/main/AndroidManifest.xml
- M app/src/main/java/.../DoubaoLetterLongPressHook.java
- M app/src/main/res/values/strings.xml
- ?? app/src/main/java/.../DoubaoVoiceSendA11yService.java (new)
- ?? app/src/main/res/xml/accessibility_service_config.xml (new)

Exactly the 6 expected files. No unexpected files.

## 2. Build — PASS
JAVA_HOME=/opt/homebrew/opt/openjdk@21 (system `java` was absent; brew JDK 21 used).
`./gradlew :app:assembleDebug` -> BUILD SUCCESSFUL in 2s, exit 0.
Only a deprecation `-Xlint` note (pre-existing). APK produced:
`app/build/outputs/apk/debug/app-debug.apk` — 39k, 2026-06-17 10:57.

## 3. Static grep assertions — ALL PASS
- DoubaoVoiceSendA11yService.java:18 `public class DoubaoVoiceSendA11yService extends AccessibilityService`
- :20-21 `ACTION_A11Y_SEND = "com.jin.doubaolongpressvoice.ACTION_A11Y_SEND"`
- :106 `getRootInActiveWindow()`; :128 `AccessibilityNodeInfo.ACTION_CLICK`; dump at :221-224 references `viewIdResourceName=` + uses `node.isClickable()` (:217)
- Hook:162-165 `A11Y_SEND_PACKAGES` HashSet with BOTH `com.anthropic.claude` AND `com.openai.chatgpt`
- Hook methods present: `dispatchViaA11ySend` (:688), `broadcastA11ySend` (:816), `pollAsrSettleThen` (:780)
- Ordering (read method body, :657-681): in `commitAndDispatchToolbarAction`, the `A11Y_SEND_PACKAGES.contains(pkg)` check + `return` (lines 663-666) occurs BEFORE the `dispatchViaAsrManagerT`/`dispatchNewlineFast` branch (lines 676-680). Confirmed.
- broadcast `setPackage("com.jin.doubaolongpressvoice")` at Hook:827
- Manifest: action `android.accessibilityservice.AccessibilityService` (:30); permission `BIND_ACCESSIBILITY_SERVICE` (service `android:permission`, :28); `@xml/accessibility_service_config` (:34)
- accessibility_service_config.xml:5 `android:canRetrieveWindowContent="true"` (also packageNames + description wired)
- strings.xml:5-6 `a11y_service_label`, `a11y_service_description`
- build.gradle:13-14 `versionCode 5`, `versionName "1.4.0"`

## 4. Regression assertions — ALL PASS
- FORCE_SEND_PACKAGES still `Collections.singleton("tw.nekomimi.nekogram")` (Hook:160-161)
- Newline ENTER path preserved: `pollAsrSettleAndEnter` (:773-778) is now a delegate to `pollAsrSettleThen(..., () -> sendEnterKey(cl))`; `dispatchNewlineFast` (:752) still calls `pollAsrSettleAndEnter`; `sendEnterKey(cl)` intact (:837, dispatches KEYCODE_ENTER 66). ENTER terminal preserved, not dropped.
- `NEWLINE_ASR_SETTLE_MS` (:139), `NEWLINE_ASR_MAX_WAIT_MS` (:140), `sLastAsrCallbackTs` (:273) all present
- `dispatchViaAsrManagerT` (:710), `commitVoice` (:857), `maybeUpdateZone` (:1271), `dispatchNewlineFast` (:742) all present
- pollAsrSettleThen refactor: two-phase poll body preserved verbatim; existing newline callers route through delegate with ENTER terminal. No behavior change for newline.

## 5. Receiver-registration sanity — PASS
DoubaoVoiceSendA11yService.java:79-83: API >= 33 -> `registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED)`; older -> 2-arg overload. Cross-UID broadcast (from Doubao process) will be received on API 33+. Registered in `onServiceConnected`, unregistered in both `onUnbind` and `onDestroy`, guarded by `mReceiverRegistered`. Correct.

## Notes (non-blocking)
- System `java` not on PATH on this machine; build required brew openjdk@21. Not a defect in the change.

---

## Real-device test (2026-06-17, post-static-verify) — PASS

Static verify above was the gate for the first commit (7293b9e). On-device run then surfaced two functional issues, both fixed in 722daa7:

| Item | First run | After fix (722daa7) |
|---|---|---|
| Claude send (WebView) | ✅ `ACTION_CLICK ok=true` | ✅ still sends |
| ChatGPT send (Compose) | ❌ "no send node", dump empty | ✅ sends |
| Toolbar label in Claude/ChatGPT | ❌ shows 换行 | ✅ shows 发送 |
| Newline path (other apps) | ✅ | ✅ no regression |

Root cause of ChatGPT miss: Jetpack Compose send button reports `isClickable()==false` but exposes `ACTION_CLICK` as a semantic action; old matcher + dump only considered `isClickable()`. Fix: match/click via `getActionList()` ACTION_CLICK, broaden keywords (zh 发送/提交), dump any actionable-or-labeled node. Label fix: a11y packages now use the SEND-label override.

**Verdict: v1.4.0 PASS on real device (Claude + ChatGPT).**
