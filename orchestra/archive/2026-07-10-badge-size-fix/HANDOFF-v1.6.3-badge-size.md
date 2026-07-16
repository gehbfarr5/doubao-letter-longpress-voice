# HANDOFF: fix oversized zone-feedback badge (use real `native_candidate_bar` height)

## Symptom (user report, 2026-07-10, after v1.6.2 shipped)

v1.6.2 fixed the badge not appearing at all (`ImeService.x→y` field rename).
But once visible, both badges (blue toolbar "换行" label, red "撤回输入"
cancel label) render **too big** — they visibly bleed into the first row of
letter keys instead of sitting neatly in Doubao's own candidate/shortcut row.

## Root cause (verified via full `apktool d` decompile + live on-device measurement, not guessed)

`KeyboardJni.getToolbarHeight()` (the native API `effectiveToolbarHeight()`
prefers) returns `0` in this UI context on 1.3.14 — confirmed via a temporary
debug build's log: `HIT letter long-press ... toolbarH=0`. This is not new
information (already known from the v1.6.2 round), but this round dug into
*why* and *what the correct value actually is*, by fully decompiling the live
device's `com.bytedance.android.doubaoime` v1.3.14 APK (`apktool d` without
`-r`, to get resource IDs too) instead of just smali.

`res/layout/ime_inputview_root.xml` (the layout backing the `InputViewRoot`
class our overlay attaches to) contains:

```xml
<FrameLayout android:id="@id/native_candidate_bar"
    android:background="@color/navigation_bar_normal"
    android:visibility="visible"
    android:layout_width="match_parent"
    android:layout_height="56.0dp"
    android:layout_alignParentTop="true" />
```

This is the real "toolbar row" — a `FrameLayout` with a **fixed 56dp height**
(not a percentage of keyboard height). Confirmed live on-device via a
diagnostic build that resolved it with
`getIdentifier("native_candidate_bar", "id", "com.bytedance.android.doubaoime")`
+ `findViewById`:

```
DIAG native_candidate_bar id=2131362946 view=android.widget.FrameLayout
     top=0 bottom=201 h=201 vis=4
```

**Real measured height: 201px** (≈56dp × 3.5 density = 196px, close enough —
the small gap is likely padding/insets). Our current fallback in
`effectiveToolbarHeight()` (`DoubaoLetterLongPressHook.java:1444-1454`) computes
**283px** (`TOOLBAR_TOP_NORMAL = 0.30f` × `h=943`) — a **41% overshoot**,
which is exactly why the badge bleeds into the first letter row. The
`TOOLBAR_TOP_NORMAL`/`TOOLBAR_TOP_TALL` ratio constants added in commit
`28ec41d` were a reasonable-looking heuristic at the time but were never
measured against ground truth — this round supplies that ground truth.

Note: `vis=4` (`View.INVISIBLE`) — Doubao hides this view's *content* while
ASR is active but keeps its layout space reserved, which is why reading its
height still works even mid-recording.

## Recommended fix

Don't hardcode `56dp` as a new magic constant either (same fragility as the
ratio it replaces — a future Doubao redesign could change it silently). Use
the same resilience pattern as the `getInputView()` fix from v1.6.2: resolve
`native_candidate_bar` by id at runtime and read its **live** height, only
falling back to the existing ratio heuristic if the id lookup or view isn't
available (keeps today's behavior as a last-resort safety net, doesn't
regress anything if a future Doubao build removes this id).

In `effectiveToolbarHeight(int w, int h, int toolbarHeight)`
(`DoubaoLetterLongPressHook.java:1444`), the function currently has no way to
reach a `View` — it only takes primitive ints. Add an overload/parameter that
takes the `ClassLoader` (or the already-resolved `InputView` object, cheaper
since callers already have it via `getInputView(cl)`) so it can attempt the
live lookup:

```java
private static final String RES_ID_NATIVE_CANDIDATE_BAR = "native_candidate_bar";

/** Live-measured toolbar/candidate-bar height, or -1 if unavailable. */
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

Then in `effectiveToolbarHeight()`, try this **before** the ratio fallback
(native `getToolbarHeight()` first as today, then this live-measured lookup,
then the ratio heuristic as the last resort):

```java
private static int effectiveToolbarHeight(ClassLoader cl, int w, int h, int toolbarHeight) {
    if (toolbarHeight > 0) {
        return toolbarHeight;
    }
    int live = readNativeCandidateBarHeight(cl);
    if (live > 0) {
        return live;
    }
    if (w <= 0 || h <= 0) {
        return 0;
    }
    boolean tallToolbar = (h > w * TALL_KBD_H_OVER_W);
    float topRatio = tallToolbar ? TOOLBAR_TOP_TALL : TOOLBAR_TOP_NORMAL;
    return Math.max(1, Math.round(h * topRatio));
}
```

This changes `effectiveToolbarHeight`'s signature (adds a `ClassLoader cl`
first param), which **cascades** — `isLetterZone()` and `computeZone()` both
call `effectiveToolbarHeight()` internally but don't currently take a `cl`
param themselves, so they need one added too. Full call graph
(`grep -n "effectiveToolbarHeight(\|isLetterZone(\|computeZone("` to confirm
line numbers before editing, they may have shifted):

- `effectiveToolbarHeight(int w, int h, int toolbarHeight)` (`:1445`) →
  add `ClassLoader cl` as first param.
- `isLetterZone(int x, int y, int w, int h, int kbdType, int toolbarHeight)`
  (`:1409`) → add `ClassLoader cl` as first param (it calls
  `effectiveToolbarHeight()` at `:1416`). Its only call site is `:372`
  inside Hook 1's `handleMessage` override, which already has `cl` in scope
  (used two lines above for `readToolbarHeight(cl)`) — just thread it through.
- `computeZone(float x, float y, int w, int h, int toolbarHeight)` (`:1457`)
  → add `ClassLoader cl` as first param (it calls `effectiveToolbarHeight()`
  at `:1461`). Two call sites: `:479` inside Hook 2's UP handler (has `cl` in
  scope, used just above for `readToolbarHeight(cl)`), and `:1475` inside
  `maybeUpdateZone` (already takes `cl` as its own first param).
- Direct `effectiveToolbarHeight()` call sites needing the new arg: `:402`
  (Hook 1, has `cl`), `:1487` (`maybeUpdateZone`, has `cl`).

Every call site already has `cl` available in its enclosing scope — this is
a mechanical threading exercise, not a logic change, but touches more lines
than it might look like at first glance. Don't skip any of the 4 call sites
above or it won't compile.

**Do not** cache `readNativeCandidateBarHeight()`'s result the way
`sCachedToolbarHeight` caches the native API's value — the view's live height
can legitimately be `0`/stale briefly right after a layout pass, and re-reading
it each call is cheap (`findViewById` on a shallow tree, not expensive).

## What NOT to touch

Everything else from v1.6.2 (`getInputView()`'s `y`-field self-heal,
`AsrManager.s0/t0`, `KeyboardJni` reflection) is unrelated and verified
correct — leave it alone.

## Acceptance

Machine-checkable:
1. `grep -c 'native_candidate_bar' app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java` → `>= 1`
2. `grep -c 'effectiveToolbarHeight(ClassLoader' app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java` → `>= 1` (signature actually changed, not just added a new unused method)
3. `./gradlew :app:lintDebug` → exit 0
4. `./gradlew :app:assembleDebug` → exit 0
5. Version bump `1.6.2` → `1.6.3` in `app/build.gradle`, README compat/known-limitations notes updated in the same style as the existing v1.3.14 entries

Real-device (orchestrator will do this after Verifier passes, not part of Codex's job):
install, force-stop `com.bytedance.android.doubaoime`, long-press-and-slide
into the toolbar zone in a text field, confirm the badge height now measures
close to the real `native_candidate_bar` height (~200px on this test device)
instead of the old ~283px.
