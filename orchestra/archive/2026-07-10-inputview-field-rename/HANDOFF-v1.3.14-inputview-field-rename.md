# HANDOFF: fix getInputView() for Doubao 1.3.14 (`ImeService.x` → `y`)

## Symptom (user report, 2026-07-10)

After the Doubao IME update, sliding to the toolbar zone (or sliding out to
cancel) during voice recording no longer shows the zone-feedback badge
(发送/搜索/前往/换行 label, or the red 撤回 cancel icon). Both zones affected
— the badge simply never appears.

## Root cause (verified via static decompile, not guessed)

Pulled the live `com.bytedance.android.doubaoime` v1.3.14 base.apk off device
and ran `apktool d` (smali disassembly). `ImeService`'s static field holding
the `InputView` singleton is:

```smali
.field private static y:Lcom/bytedance/android/input/keyboard/InputView; = null
```

`getInputView(cl)` in `DoubaoLetterLongPressHook.java:1502-1508` hardcodes the
old field name `"x"`:

```java
return XposedHelpers.getStaticObjectField(imeServiceCls, "x");
```

`x` no longer exists on 1.3.14 → reflection throws → caught → returns `null`.
This breaks two call sites:

1. `ensureOverlay()` (`:1734`) — `inputView` is `null` → not a `FrameLayout` →
   bails before attaching the badge view. **This is the exact reported bug.**
2. `callInputViewCloseAsrUi()` (`:1512`) — silently no-ops (minor; the
   "tidy up ASR UI" step is skipped, doesn't block commit/send).

Everything else checked against the same decompile is correct as of v1.6.1
(commit `28ec41d`): `AsrManager.s0(Z,String)V` / `t0()V` signatures match
exactly; `KeyboardJni.getToolbarHeight()I` / `getCurrentKbdType()I` /
`getKeyboardJni()` / `mImeService` field are all unchanged. (Side note: the
`p0`/`q0` fallback branches in `callAsrStop`/`callAsrGracefulCommit` call
those methods with the wrong arg count for 1.3.14's actual signatures —
`p0(Z)V` and `q0(String)Z` — but since `s0`/`t0` succeed first, these
fallbacks are unreachable dead code on 1.3.14. Not urgent, but worth aligning
next time this file is touched.)

## Recommended fix

Don't just swap the literal to `"y"` — that reproduces the exact fragility
that caused this bug. The codebase already has the right pattern for this in
`extractKeyboardView()` (`:1282-1296`, scans declared fields by type instead
of by name). Apply the same idea to `getInputView()`:

```java
private static Object getInputView(ClassLoader cl) {
    try {
        Class<?> imeServiceCls = XposedHelpers.findClass(IME_SERVICE, cl);
        try {
            return XposedHelpers.getStaticObjectField(imeServiceCls, "y");
        } catch (Throwable ignore) {}
        for (Field f : imeServiceCls.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    && FrameLayout.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                Object v = f.get(null);
                if (v != null) return v;
            }
        }
    } catch (Throwable t) {
        log("ERR getInputView: " + t.getClass().getSimpleName());
    }
    return null;
}
```

`InputView` extends `FrameLayout` (already how the rest of the code treats
it), and `ImeService` currently has exactly one static field of that type —
type-scan is unambiguous today and self-heals on the next rename.

## Verification blocked (2026-07-10)

Real-device regression testing could not be completed this session: LSPosed
(`zygisk_vector` / Vector-SR) does not currently hook anything on this
freshly-rebuilt device (daemon fails to start — see
`references/oneplus-app-projects.md` "Known blocker" note in the OnePlus 15
skill for the full root cause). Static analysis (this handoff) was used as a
substitute. Once LSPosed is working again, re-run the badge test manually:
long-press a letter key, slide to the toolbar, release — the label should
appear — and slide out to cancel — the red trash-can badge should appear.
