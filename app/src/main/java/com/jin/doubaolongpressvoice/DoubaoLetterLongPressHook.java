package com.jin.doubaolongpressvoice;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import com.jin.doubaolongpressvoice.ZoneResolver.Zone;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Long-press any letter key in Doubao IME (com.bytedance.android.doubaoime) to
 * trigger ASR voice input. Hold to record, release to commit, swipe out to
 * cancel. Mirrors the toolbar voice button UX with our own swipe-to-cancel.
 *
 * <h2>Interaction model</h2>
 * <ul>
 *   <li>Long-press a letter key (~500ms, aligned with Doubao's internal
 *       {@code KeyboardView.LONG_PRESS_TIMEOUT}) → start ASR via
 *       {@code KeyboardJni.DoFunctionKey(6)}.</li>
 *   <li>Release while finger is still inside KeyboardView bounds → commit via
 *       {@code AsrManager.p0(false, "")} (mirrors voice-panel stop button so
 *       Doubao's ASR engine finalize/tidy flow runs cleanly).</li>
 *   <li>Drag finger outside the keyboard area then release → cancel: open a
 *       commit-suppression window, clear preedit, swallow ASR commits.</li>
 * </ul>
 *
 * <h2>Gating (ordered, defensive)</h2>
 * <ol>
 *   <li>Skip non-text {@code InputType} classes (NUMBER / PHONE / DATETIME).</li>
 *   <li>Blacklist {@code KeyboardJni.getCurrentKbdType()} values 3 and 5 — the
 *       {@code ?123} number / symbol sub-layers. All other kbdType values
 *       (Ziranma=0, 9-key Pinyin=1, English-26=2/..., shuangpin, etc.) are
 *       allowed; handwriting uses {@code HandWritingBoardView} and never
 *       reaches our hook.</li>
 *   <li>Skip floating mode and one-handed mode (geometric ratio fallback).</li>
 *   <li>Geometric letter-zone exclusion (kbdType-aware):
 *       <ul>
 *         <li>QWERTY-like: bottom row (space + funcs) and row-3 edges
 *             (Shift / Backspace);</li>
 *         <li>9-key: bottom row plus the left-mode and right-backspace columns
 *             (every row).</li>
 *       </ul></li>
 *   <li>Swipe vs long-press: any DOWN→MOVE displacement &gt; 20 dp (density-
 *       aware, computed at runtime) is treated as a cursor-swipe and ignored
 *       so Doubao's native cursor-slide gesture keeps working.</li>
 * </ol>
 *
 * <h2>Cancel path</h2>
 * Doubao's "toolbar voice" entry (case 6/7) has no native cancel. We open a
 * 0.5 s suppression window when the user releases off-keyboard:
 * <ul>
 *   <li>{@code KeyboardJni.commitString(text, _, source)} is swallowed unless
 *       {@code source} is in a user-input whitelist (typing / clipboard /
 *       emoji / common-phrase);</li>
 *   <li>{@code onAsrCommitPreeditText()} returns {@code true} (skips the
 *       caller's own commit path);</li>
 *   <li>{@code onAsrSetPreedit(text)} returns {@code true} (suppresses
 *       streaming preedit updates that would otherwise re-display text);</li>
 *   <li>{@code KeyboardJni.finishPreedit(false)} is called once at cancel
 *       time to clear the InputConnection composing text immediately.</li>
 * </ul>
 *
 * <h2>Critical: lazy resolution of Doubao internal singletons</h2>
 * {@code UserInteractiveManagerNext.a} and {@code AsrManager.a} must NOT be
 * touched at {@code handleLoadPackage} time. They chain into
 * {@code IAppGlobals} which requires {@code ImeApplication.attachBaseContext}
 * to have run. Triggering {@code <clinit>} too early permanently marks the
 * class as errored, killing the Doubao process. All accesses go through
 * lazy-resolve helpers (called on first long-press fire).
 */
public final class DoubaoLetterLongPressHook {

    private static final String TAG = "DoubaoLongPress";
    private static final boolean DEBUG = false;
    private static final String KEYBOARD_VIEW = "com.bytedance.android.input.keyboard.KeyboardView";
    private static final String KEYBOARD_JNI = "com.bytedance.android.doubaoime.KeyboardJni";
    private static final String IME_SERVICE = "com.bytedance.android.doubaoime.ImeService";
    private static final String USER_INTERACTIVE_MGR =
            "com.bytedance.android.input.keyboard.UserInteractiveManagerNext";
    private static final String VIBRATION_CONTROLLER =
            "com.bytedance.android.input.common.VibrationController";
    private static final String ASR_MANAGER =
            "com.bytedance.android.input.speech.AsrManager";
    private static final String ASR_PROCESS_CLS =
            "com.bytedance.android.input.speech.z";
    private static final String ASR_ALL_BACK_LISTENER_CLS =
            "com.bytedance.android.input.speech.L.a";
    private static final String EDITOR_VIEW_INFO =
            "com.bytedance.android.input.speech.view.o";
    private static final String DOUBAO_PACKAGE = "com.bytedance.android.doubaoime";
    private static final String A11Y_SERVICE_COMPONENT =
            "com.jin.doubaolongpressvoice/.DoubaoVoiceSendA11yService";
    private static final String A11Y_SERVICE_COMPONENT_FULL =
            "com.jin.doubaolongpressvoice/com.jin.doubaolongpressvoice.DoubaoVoiceSendA11yService";
    private static final String A11Y_SEND_WARNING_TEXT =
            "豆包语音发送：无障碍服务未启用，发送可能失败";

    private static final int MSG_LONGPRESS = 1;
    private static final int DO_FUNCTION_KEY_VOICE_START = 6;
    private static final int ACTION_CANCEL = MotionEvent.ACTION_CANCEL;
    private static final String ASR_CANCEL_REASON = "cancel";
    private static final long CANCEL_WINDOW_MS = 500L;
    /** Native all-back completion deadline; timeout aborts the terminal action. */
    private static final long NEWLINE_ASR_MAX_WAIT_MS = 2000L;  // overall cap
    /**
     * v1.2.0: Force-send package list — apps whose chat EditText DOES respond to
     * {@code IME_ACTION_SEND} via OnEditorActionListener, but whose declared
     * imeOptions/inputType make Doubao misclassify the editor as newline-class.
     *
     * Source for Nekogram entry: ChatActivityEnterView line 5717-5732
     * (https://github.com/Nekogram/Nekogram, main branch).
     *
     * When detected enterActionType is non-specific AND current editor's package
     * is in this set, override the dispatch ordinal to IME_ACTION_SEND so
     * AsrManager.t(...) routes through InputConnection.performEditorAction
     * (IME_ACTION_SEND) — which the App's registered listener catches and treats
     * as "send message".
     *
     * Adding a new app requires (1) confirming via source/runtime that its
     * OnEditorActionListener unconditionally responds to IME_ACTION_SEND,
     * (2) appending its package name here, (3) bumping minor version.
     * See orchestra/ADAPT-PLAYBOOK.md for the full adaptation flow.
     */
    private static final java.util.Set<String> FORCE_SEND_PACKAGES =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "tw.nekomimi.nekogram", "org.telegram.messenger"));
    private static final java.util.Set<String> A11Y_SEND_PACKAGES =
            SendTargets.A11Y;

    /** {@code EditorInfo.IME_ACTION_SEND} ordinal — used for force-send override. */
    private static final int IME_ACTION_SEND_ORDINAL = 4;

    // Zone-tracking constants for in-recording slide-to-action.
    private static final long ZONE_DEBOUNCE_MS = 50L;
    private static final long SELECTION_ANIM_MS = 180L;
    private static final long HIDE_ANIM_MS = 120L;
    private static final float SELECTION_SCALE = 1.04f;       // 微微放大
    private static final float SELECTION_SCALE_INITIAL = 0.92f;  // pop-in starting scale
    // Full-width strip across toolbar, icon+text in a single row. Margins
    // come from Doubao's own asr_editor_candidate_container_padding_horizontal
    // (visually matches candidate-word to boundary spacing); 8dp fallback
    // if that resource can't be resolved. Applied uniformly on all 4 sides.
    private static final int OVERLAY_ICON_SIZE_DP = 20;
    private static final int OVERLAY_TEXT_SP = 14;
    private static final int OVERLAY_ICON_TEXT_GAP_DP = 6;     // gap between icon and label
    private static final int OVERLAY_MARGIN_FALLBACK_DP = 8;
    private static final String DIMEN_NAME_OVERLAY_MARGIN =
            "asr_editor_candidate_container_padding_horizontal";
    private static final String RES_ID_NATIVE_CANDIDATE_BAR = "native_candidate_bar";
    private static final float OVERLAY_CORNER_RADIUS_DP = 8f;  // candidate-box style
    private static final float OVERLAY_ELEVATION_DP = 3f;
    // Brand-aligned colors (opaque). Matches what Doubao uses for press states
    // (`asr_long_press_navigation_press` blue), with an error-red sibling.
    private static final int COLOR_SEND = 0xFF1A77FF;     // brand blue
    private static final int COLOR_CANCEL = 0xFFFF4D4F;   // error red
    private static final int COLOR_TRANSPARENT = 0x00000000;
    // Per-action overlay labels (kept in sync with AsrLongPressView's right
    // button via Doubao's asr_long_press_*_text resources; we use plain string
    // fallbacks if resource lookup fails).
    private static final String TEXT_NEWLINE = "换行";
    private static final String TEXT_CANCEL = "撤回输入";
    private static final String RES_NAME_NEWLINE = "asr_long_press_enter_text";   // (换行)
    private static final String RES_NAME_GO = "asr_long_press_go_text";           // (前往)
    private static final String RES_NAME_SEARCH = "asr_long_press_search_text";   // (搜索)
    private static final String RES_NAME_SEND = "asr_long_press_send_text";       // (发送)
    private static final String RES_NAME_NEXT = "asr_long_press_next_text";       // (下一项)
    private static final String RES_NAME_DONE = "asr_long_press_done_text";       // (完成)
    private static final String RES_NAME_PREVIOUS = "asr_long_press_previous_text"; // (上一项)
    // Plain-string fallbacks if resource lookup fails.
    private static final String FALLBACK_GO = "前往";
    private static final String FALLBACK_SEARCH = "搜索";
    private static final String FALLBACK_SEND = "发送";
    private static final String FALLBACK_NEXT = "下一项";
    private static final String FALLBACK_DONE = "完成";
    private static final String FALLBACK_PREVIOUS = "上一项";

    // Doubao drawable resource names for the icons (the `oic_*` set is Doubao's
    // toolbar/action icon family; `ic_delete_white` is the trash can shown on
    // backspace swipe-up clear).
    private static final String DRW_NAME_SEND = "oic_send";
    private static final String DRW_NAME_SEARCH = "oic_search";
    private static final String DRW_NAME_ENTER = "oic_enter";
    private static final String DRW_NAME_FINISH = "oic_finish";
    private static final String DRW_NAME_NEXT = "oic_next";
    private static final String DRW_NAME_PREVIOUS = "oic_previous";
    private static final String DRW_NAME_CANCEL = "ic_delete_white";

    // Whitelisted CommitSource values that always flow through, even mid-
    // cancel-window. Sourced from KeyboardJni$CommitSource constants.
    private static final Set<String> USER_INPUT_SOURCES = new HashSet<>();
    static {
        USER_INPUT_SOURCES.add("keyboard_callback");
        USER_INPUT_SOURCES.add("common_phrase");
        USER_INPUT_SOURCES.add("toolbar_clipboard");
        USER_INPUT_SOURCES.add("clipboard");
        USER_INPUT_SOURCES.add("clipboard_segmentation");
        USER_INPUT_SOURCES.add("emoji");
    }

    // Verified empirically from probe logs:
    //   kbdType=3, 5  → ?123 number / symbol sub-layers (block)
    //   kbdType=0     → Ziranma / shuangpin letter layer
    //   kbdType=1     → 9-key Pinyin (key_9)
    //   other         → English-26 and assorted, all allowed
    private static final Set<Integer> KBD_TYPE_BLACKLIST = new HashSet<>();
    static {
        KBD_TYPE_BLACKLIST.add(3);
        KBD_TYPE_BLACKLIST.add(5);
    }
    private static final int KBD_TYPE_9KEY = 1;

    // Geometric letter-zone (ratio-based, scale invariant across screen sizes).
    private static final float LETTER_BOTTOM = 0.75f;
    private static final float LETTER_ROW3_TOP = 0.50f;
    private static final float LETTER_ROW3_X_LEFT = 0.13f;
    private static final float LETTER_ROW3_X_RIGHT = 0.87f;
    private static final float NINE_KEY_X_LEFT = 0.15f;
    private static final float NINE_KEY_X_RIGHT = 0.85f;
    private static final float ONE_HAND_WIDTH_RATIO = 0.85f;
    // Top exclusion = toolbar / candidates bar / ASR slide-action row.
    // Doubao 1.3.14 can report getToolbarHeight() as 0 while still rendering a
    // sizeable top action row. Prefer the live native_candidate_bar height and
    // keep these ratios only as the last-resort fallback.
    private static final float TOOLBAR_TOP_NORMAL = 0.30f;
    private static final float TOOLBAR_TOP_TALL = 0.34f;
    private static final float TALL_KBD_H_OVER_W = 0.85f;

    // Swipe detection threshold (dp; converted to px at runtime per device).
    private static final float SWIPE_THRESHOLD_DP = 20f;

    // --- volatile per-session state ---
    private static volatile long sCancelUntilElapsed = 0L;
    /** Last time onAsrSetPreedit / onAsrCommitPreeditText fired — used by mode-8 ASR-settle poll. */
    private static volatile boolean sSuppressNextUp = false;
    private static volatile boolean sAsrStartConfirmed = false;
    private static volatile float sDownX;
    private static volatile float sDownY;
    private static volatile float sMaxDisplacementSq;
    private static volatile float sSwipeThresholdPxSq = -1f;
    private static volatile Runnable sPendingCommit;
    private static volatile Zone sCurrentZone = Zone.LETTER;
    private static volatile int sRecordingEnterOrdinal = -1;
    private static volatile long sLastZoneChangeTs = 0L;
    private static volatile LinearLayout sOverlay;
    private static volatile ImageView sOverlayIcon;
    private static volatile TextView sOverlayLabel;
    private static volatile int sCurrentOverlayColor = COLOR_TRANSPARENT;
    private static volatile ValueAnimator sColorAnimator;
    private static volatile ViewGroup sOverlayParent;
    private static volatile int sCachedToolbarHeight = -1;
    private static volatile GestureSession sGestureSession;
    private static volatile DoubaoCompatAdapter sCompat;
    private static volatile boolean sNativeTouchProbeInstalled;
    private static final ThreadLocal<Integer> sInternalNativeTouchDepth =
            new ThreadLocal<Integer>() {
                @Override
                protected Integer initialValue() {
                    return 0;
                }
            };

    // --- lazy-resolved Doubao internals ---
    private static volatile Object sUserInteractiveMgr;
    private static volatile Object sKeySoundKeyboard;
    private static volatile Object sKeyVibrateStandard;
    private static volatile Object sVibTypeSpeechStart;
    private static volatile Object sVibTypeConfirm;           // for zone-selection feedback
    private static volatile boolean sFeedbackResolveAttempted;
    private static volatile boolean sFeedbackResolveOk;
    private static volatile Object sAsrManager;
    private static volatile boolean sAsrResolveAttempted;
    private static ClassLoader sClassLoader;

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private DoubaoLetterLongPressHook() {
    }

    private static volatile boolean sReady;
    private static final java.util.concurrent.atomic.AtomicBoolean sResolving =
            new java.util.concurrent.atomic.AtomicBoolean();

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals(lpparam.processName)) return;
        final ClassLoader cl = lpparam.classLoader;
        sClassLoader = cl;
        XposedHelpers.findAndHookMethod(android.app.Application.class, "attach",
                android.content.Context.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!sResolving.compareAndSet(false, true)) return;
                        android.content.Context context = (android.content.Context) param.args[0];
                        new Thread(() -> {
                            try {
                                DoubaoCompatAdapter resolved = AdaptiveLoader.load(context, cl,
                                        DoubaoLetterLongPressHook::diag);
                                sMainHandler.post(() -> {
                                    try {
                                        sCompat = resolved;
                                        installHandlerHook(cl);
                                        installTouchHook(cl);
                                        installNativeTouchProbe(cl);
                                        installNativeAsrSurfaceHook(cl);
                                        installCommitSuppressionHooks(cl);
                                        installImeLifecycleHook(cl);
                                        installFinalSendHook(cl);
                                        installNativeAsrTrace(cl);
                                        sReady = true;
                                        diag("semantic capabilities READY " + resolved.diagnostic());
                                    } catch (Throwable error) {
                                        sReady = false;
                                        diag("resolver installation FAILED: " + Log.getStackTraceString(error));
                                    }
                                });
                            } catch (Throwable error) {
                                diag("resolver FAILED; original input retained: " + Log.getStackTraceString(error));
                            }
                        }, "DoubaoSemanticResolver").start();
                    }
                });
    }

    // ===== Hook 1: KeyboardView$c.handleMessage(MSG_LONGPRESS=1) =====
    private static void installHandlerHook(final ClassLoader cl) {
        try {
            Class<?> kvClass = XposedHelpers.findClass(KEYBOARD_VIEW, cl);
            Class<?> handlerInner = findHandlerInnerClass(kvClass);
            if (handlerInner == null) {
                diag("ERR cannot locate KeyboardView inner Handler class");
                return;
            }
            diag("located inner Handler class: " + handlerInner.getName());

            XposedHelpers.findAndHookMethod(handlerInner, "handleMessage", Message.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!sReady) return;
                            try {
                                Message msg = (Message) param.args[0];
                                if (msg == null || msg.what != MSG_LONGPRESS) {
                                    return;
                                }
                                Object kv = extractKeyboardView(param.thisObject);
                                if (!(kv instanceof View)) {
                                    diag("gate=keyboard_view_unavailable handler="
                                            + param.thisObject.getClass().getName());
                                    return;
                                }
                                View kvView = (View) kv;
                                int x = msg.arg1;
                                int y = msg.arg2;
                                // 1.3.15 uses the JNI long-press sentinel as the authoritative
                                // trigger. Keeping the Handler path for 1.3.14 preserves its
                                // already-proven behavior and avoids two competing start owners.
                                if (!sCompat.hasNativeSurface()
                                        && tryStartGesture(cl, kvView, x, y,
                                        "KeyboardView.Handler")) {
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                diag("ERR handleMessage hook: " + Log.getStackTraceString(t));
                            }
                        }
                    });
            diag("hooked " + handlerInner.getName() + "#handleMessage(Message)");
        } catch (Throwable t) {
            diag("ERR install handler hook: " + Log.getStackTraceString(t));
        }
    }

    private static boolean tryStartGesture(ClassLoader cl, View kvView, int x, int y,
                                           String source) {
        try {
            if (sGestureSession != null && sGestureSession.isActive()) {
                diag("gesture start ignored: active id=" + sGestureSession.id
                        + " source=" + source);
                return false;
            }
            int w = kvView.getWidth();
            int h = kvView.getHeight();
            int kbdType = readKbdType(cl);
            int inputClass = readInputClass(cl);
            int toolbarHeight = (sCachedToolbarHeight > 0)
                    ? sCachedToolbarHeight : readToolbarHeight(cl);
            if (isNonTextInputClass(inputClass)) {
                diag("gate=non_text_input inputClass=0x"
                        + Integer.toHexString(inputClass) + " kbdType=" + kbdType);
                return false;
            }
            if (KBD_TYPE_BLACKLIST.contains(kbdType)) {
                diag("gate=blacklisted_layer kbdType=" + kbdType);
                return false;
            }
            if (!modeAllowed(cl, kvView)) {
                diag("gate=mode_blocked (floating/oneHand)");
                return false;
            }
            boolean inLetterZone = sCompat.hasNativeSurface()
                    ? isLetterZoneWithoutToolbar(x, y, w, h, kbdType)
                    : isLetterZone(cl, x, y, w, h, kbdType, toolbarHeight);
            if (!inLetterZone) {
                diag("gate=geom_outside x=" + x + " y=" + y
                        + " w=" + w + " h=" + h + " kbdType=" + kbdType
                        + " toolbarH=" + toolbarHeight + " source=" + source);
                return false;
            }
            float thresholdSq = ensureSwipeThresholdPxSq(kvView);
            if (sMaxDisplacementSq > thresholdSq) {
                diag("gate=swipe maxDisp=" + Math.sqrt(sMaxDisplacementSq)
                        + "px threshold=" + Math.sqrt(thresholdSq)
                        + "px source=" + source);
                return false;
            }
            ZoneResolver.Geometry geometry = captureGlobalGeometry(cl, kvView);
            if (sCompat.hasNativeSurface()
                    && (geometry == null || !geometry.isUsable())) {
                diag("gesture rejected: unusable global geometry " + geometry);
                return false;
            }

            int enterOrdinal = resolveEffectiveEnterOrdinal(cl);
            sCancelUntilElapsed = 0L;
            cancelPendingCommit();
            sSuppressNextUp = true;
            sAsrStartConfirmed = false;
            sCurrentZone = Zone.LETTER;
            sRecordingEnterOrdinal = enterOrdinal;
            sLastZoneChangeTs = SystemClock.elapsedRealtime();
            GestureSession session = new GestureSession(
                    geometry, sRecordingEnterOrdinal, sLastZoneChangeTs);
            sGestureSession = session;
            ensureA11yReadyIfNeeded(cl, "gesture_start", false);
            triggerVoiceStart(cl);
            sendCancelToNative(kvView, x, y);
            performSpeechStartFeedback();
            int overlayHeight = geometry != null && geometry.toolbar != null
                    ? geometry.toolbar.height()
                    : effectiveToolbarHeight(cl, w, h, toolbarHeight);
            ensureOverlay(cl, overlayHeight);
            diag("gesture start id=" + session.id + " family=" + sCompat.family()
                    + " source=" + source + " ordinal=" + session.enterOrdinal
                    + " " + geometry);
            scheduleAsrStartVerification(cl);
            return true;
        } catch (Throwable t) {
            resetVolatileState("gesture start failed");
            diag("ERR start gesture source=" + source + ": "
                    + Log.getStackTraceString(t));
            return false;
        }
    }

    static boolean onNativeLongPress(Object keyboardView, int x, int y) {
        ClassLoader cl = sClassLoader;
        if (!sReady || !(keyboardView instanceof View) || cl == null || sCompat == null
                || !sCompat.hasNativeSurface()) {
            diag("native bridge rejected: hook state unavailable");
            return false;
        }
        return tryStartGesture(cl, (View) keyboardView, x, y,
                "JNI.RegisterNatives/nativeTouch(-1)");
    }

    // ===== Hook 2: KeyboardView.onTouchEvent — pre-ASR tracking + legacy owner =====
    private static void installTouchHook(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(KEYBOARD_VIEW, cl, "onTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!sReady) return;
                            try {
                                MotionEvent ev = (MotionEvent) param.args[0];
                                if (ev == null) {
                                    return;
                                }
                                int action = ev.getAction() & 255;
                                if (action == MotionEvent.ACTION_DOWN) {
                                    sDownX = ev.getX();
                                    sDownY = ev.getY();
                                    sMaxDisplacementSq = 0f;
                                } else if (action == MotionEvent.ACTION_MOVE) {
                                    float dx = ev.getX() - sDownX;
                                    float dy = ev.getY() - sDownY;
                                    float distSq = dx * dx + dy * dy;
                                    if (distSq > sMaxDisplacementSq) {
                                        sMaxDisplacementSq = distSq;
                                    }
                                }
                                if (!sCompat.hasNativeSurface()) {
                                    handleLegacyKeyboardTouch(cl, param, ev, action);
                                }
                            } catch (Throwable t) {
                                diag("ERR KeyboardView before hook: " + Log.getStackTraceString(t));
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!sReady) return;
                            if (!sCompat.hasNativeSurface()) {
                                return;
                            }
                            try {
                                MotionEvent ev = (MotionEvent) param.args[0];
                                GestureSession session = sGestureSession;
                                if (ev == null || session == null || !session.isActive()
                                        || session.owner() == GestureSession.Owner.NATIVE_ASR_SURFACE) {
                                    return;
                                }
                                if (session.claim(GestureSession.Owner.UNCLAIMED,
                                        GestureSession.Owner.KEYBOARD_VIEW)
                                        || session.owner() == GestureSession.Owner.KEYBOARD_VIEW) {
                                    handleGlobalGestureEvent(cl, ev, session, "KeyboardView-after");
                                }
                            } catch (Throwable t) {
                                diag("ERR KeyboardView after hook: " + Log.getStackTraceString(t));
                            }
                        }
                    });
            diag("hooked " + KEYBOARD_VIEW + "#onTouchEvent(MotionEvent)");
        } catch (Throwable t) {
            diag("ERR install touch hook: " + Log.getStackTraceString(t));
            throw new IllegalStateException("required hook installation failed", t);
        }
    }

    private static void handleLegacyKeyboardTouch(ClassLoader cl,
                                                  XC_MethodHook.MethodHookParam param,
                                                  MotionEvent ev, int action) {
        if (action == MotionEvent.ACTION_MOVE && sSuppressNextUp
                && param.thisObject instanceof View) {
            View vv = (View) param.thisObject;
            maybeUpdateZone(cl, vv, ev.getX(), ev.getY(), vv.getWidth(), vv.getHeight());
        }
        if (!sSuppressNextUp || (action != MotionEvent.ACTION_UP
                && action != MotionEvent.ACTION_CANCEL)) {
            return;
        }
        try {
            Handler h = (Handler) XposedHelpers.getObjectField(param.thisObject, "mHandler");
            if (h != null) {
                h.removeMessages(MSG_LONGPRESS);
            }
        } catch (Throwable ignore) {
        }
        Zone releaseZone;
        if (action == MotionEvent.ACTION_CANCEL) {
            releaseZone = Zone.OUTSIDE;
        } else if (param.thisObject instanceof View) {
            View vv = (View) param.thisObject;
            int tbH = (sCachedToolbarHeight > 0)
                    ? sCachedToolbarHeight : readToolbarHeight(cl);
            releaseZone = computeZone(cl, ev.getX(), ev.getY(),
                    vv.getWidth(), vv.getHeight(), tbH);
        } else {
            releaseZone = sCurrentZone;
        }
        finishGesture(cl, sGestureSession, releaseZone, action, "KeyboardView-legacy");
        param.setResult(true);
    }

    /** 1.3.15 trigger owner plus a read-only probe for later JNI events. */
    private static void installNativeTouchProbe(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(KEYBOARD_VIEW, cl, "nativeTouch",
                    long.class, int.class, int.class, int.class, long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!sReady) return;
                            int action = (Integer) param.args[3];
                            boolean internal = sInternalNativeTouchDepth.get() > 0;
                            if (action == -1 && !internal && sCompat.hasNativeSurface()
                                    && param.thisObject instanceof View) {
                                int x = (Integer) param.args[1];
                                int y = (Integer) param.args[2];
                                diag("nativeTouch longpress sentinel local=(" + x + "," + y + ")");
                                if (tryStartGesture(cl, (View) param.thisObject, x, y,
                                        "KeyboardView.nativeTouch(-1)")) {
                                    // nativeTouch is void; suppress only when our takeover started.
                                    param.setResult(null);
                                    return;
                                }
                            }
                            GestureSession session = sGestureSession;
                            if (session == null || !session.isActive()) {
                                return;
                            }
                            if (action != MotionEvent.ACTION_MOVE || DEBUG) {
                                diag("nativeTouch probe id=" + session.id
                                        + " action=" + actionName(action)
                                        + " local=(" + param.args[1] + "," + param.args[2] + ")"
                                        + " internal=" + internal);
                            }
                        }
                    });
            sNativeTouchProbeInstalled = true;
            diag("Java nativeTouch hook registered; runtime interception unproven");
        } catch (Throwable t) {
            sNativeTouchProbeInstalled = false;
            diag("nativeTouch probe unavailable: " + t.getClass().getSimpleName());
        }
    }

    /** 1.3.15 owner: events forwarded by KeyboardView.preHandleTouchEvent. */
    private static void installNativeAsrSurfaceHook(final ClassLoader cl) {
        try {
            Class<?> surface = sCompat.nativeSurfaceClass();
            XposedHelpers.findAndHookMethod(surface, "onTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!sReady) return;
                            try {
                                MotionEvent ev = (MotionEvent) param.args[0];
                                GestureSession session = sGestureSession;
                                if (ev == null || session == null || !session.isActive()) {
                                    return;
                                }
                                if (session.claim(GestureSession.Owner.UNCLAIMED,
                                        GestureSession.Owner.NATIVE_ASR_SURFACE)) {
                                    diag("gesture owner id=" + session.id
                                            + " -> NATIVE_ASR_SURFACE");
                                }
                                if (session.owner() != GestureSession.Owner.NATIVE_ASR_SURFACE) {
                                    return;
                                }
                                int action = ev.getAction() & 255;
                                handleGlobalGestureEvent(cl, ev, session, "AsrLongPressView");
                                if (action == MotionEvent.ACTION_UP
                                        || action == MotionEvent.ACTION_CANCEL) {
                                    // Our terminal action replaces the built-in horizontal action.
                                    param.setResult(true);
                                }
                            } catch (Throwable t) {
                                diag("ERR native ASR surface hook: " + Log.getStackTraceString(t));
                            }
                        }
                    });
            diag("native ASR surface hook installed: " + surface.getName());
        } catch (Throwable t) {
            diag("ERR install native ASR surface hook: " + Log.getStackTraceString(t));
            throw new IllegalStateException("required hook installation failed", t);
        }
    }

    private static void handleGlobalGestureEvent(ClassLoader cl, MotionEvent ev,
                                                 GestureSession session, String source) {
        if (session == null || !session.isActive()) {
            return;
        }
        int action = ev.getAction() & 255;
        float rawX = ev.getRawX();
        float rawY = ev.getRawY();
        if (action == MotionEvent.ACTION_MOVE) {
            updateGlobalZone(cl, session, rawX, rawY, source);
            return;
        }
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            return;
        }
        Zone releaseZone;
        if (action == MotionEvent.ACTION_CANCEL) {
            // CANCEL is never allowed to commit or send. It is a safe cancellation
            // terminal even when the framework supplies no meaningful final point.
            releaseZone = Zone.OUTSIDE;
        } else {
            releaseZone = resolveGlobalZone(session, rawX, rawY);
            session.recordPoint(rawX, rawY, releaseZone);
        }
        finishGesture(cl, session, releaseZone, action, source);
    }

    private static void updateGlobalZone(ClassLoader cl, GestureSession session,
                                         float rawX, float rawY, String source) {
        Zone next = resolveGlobalZone(session, rawX, rawY);
        session.recordPoint(rawX, rawY, next);
        if (next == sCurrentZone) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - sLastZoneChangeTs < ZONE_DEBOUNCE_MS) {
            return;
        }
        Zone prev = sCurrentZone;
        sCurrentZone = next;
        sLastZoneChangeTs = now;
        int toolbarHeight = session.geometry != null && session.geometry.toolbar != null
                ? session.geometry.toolbar.height() : 0;
        ensureOverlay(cl, toolbarHeight);
        updateOverlayForZone(next, session.enterOrdinal, cl);
        if (next == Zone.TOOLBAR || next == Zone.OUTSIDE) {
            performZoneSelectionFeedback();
        }
        diag("zone id=" + session.id + " " + prev + " -> " + next
                + " raw=(" + rawX + "," + rawY + ") source=" + source
                + " geometry=" + session.geometry);
    }

    private static Zone resolveGlobalZone(GestureSession session, float rawX, float rawY) {
        try {
            return ZoneResolver.resolve(rawX, rawY, session.geometry);
        } catch (Throwable t) {
            diag("ERR resolve global zone id=" + session.id + ": " + t.getMessage());
            return Zone.OUTSIDE;
        }
    }

    private static void finishGesture(ClassLoader cl, GestureSession session,
                                      Zone releaseZone, int action, String source) {
        if (session == null) {
            return;
        }
        GestureSession.Terminal terminal;
        switch (releaseZone) {
            case OUTSIDE:
                terminal = GestureSession.Terminal.CANCEL;
                break;
            case TOOLBAR:
                terminal = GestureSession.Terminal.TOOLBAR_ACTION;
                break;
            default:
                terminal = GestureSession.Terminal.COMMIT;
                break;
        }
        if (!session.finish(terminal)) {
            diag("DUP_ACTION id=" + session.id + " existing=" + session.terminal()
                    + " attempted=" + terminal + " source=" + source);
            return;
        }
        sSuppressNextUp = false;
        cancelPendingCommit();
        try {
            switch (terminal) {
                case CANCEL:
                    cancelVoice(cl);
                    break;
                case TOOLBAR_ACTION:
                    commitAndDispatchToolbarAction(cl);
                    break;
                case COMMIT:
                    commitVoice(cl);
                    break;
                default:
                    break;
            }
        } finally {
            diag("gesture finish id=" + session.id + " terminal=" + terminal
                    + " action=" + actionName(action) + " source=" + source
                    + " raw=(" + session.lastRawX + "," + session.lastRawY + ")");
            sCurrentZone = Zone.LETTER;
            sRecordingEnterOrdinal = -1;
            updateOverlayForZone(Zone.LETTER, 0, cl);
            if (sGestureSession == session) {
                sGestureSession = null;
            }
        }
    }

    // ===== Hook 3: cancel-window commit suppression =====
    private static void installCommitSuppressionHooks(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(KEYBOARD_JNI, cl, "commitString",
                    String.class, boolean.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!sReady) return;
                            try {
                                if (!isInCancelWindow()) {
                                    return;
                                }
                                String source = (String) param.args[2];
                                if (USER_INPUT_SOURCES.contains(source)) {
                                    return;
                                }
                                log("commitString SWALLOWED src=" + source
                                        + " text=" + safeText((String) param.args[0]));
                                param.setResult(null);
                            } catch (Throwable ignore) {
                            }
                        }
                    });
            log("hooked " + KEYBOARD_JNI + "#commitString");
        } catch (Throwable t) {
            log("ERR hook commitString: " + Log.getStackTraceString(t));
            throw new IllegalStateException("required hook installation failed", t);
        }
        try {
            XposedHelpers.findAndHookMethod(KEYBOARD_JNI, cl, "onAsrCommitPreeditText",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!sReady) return;
                            if (isInCancelWindow()) {
                                log("onAsrCommitPreeditText SWALLOWED");
                                param.setResult(Boolean.TRUE);
                                return;
                            }
                        }
                    });
            log("hooked " + KEYBOARD_JNI + "#onAsrCommitPreeditText");
        } catch (Throwable t) {
            log("ERR hook onAsrCommitPreeditText: " + Log.getStackTraceString(t));
            throw new IllegalStateException("required hook installation failed", t);
        }
        try {
            XposedHelpers.findAndHookMethod(KEYBOARD_JNI, cl, "onAsrSetPreedit",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!sReady) return;
                            if (isInCancelWindow()) {
                                log("onAsrSetPreedit SWALLOWED text="
                                        + safeText((String) param.args[0]));
                                param.setResult(Boolean.TRUE);
                                return;
                            }
                            // Confirm ASR actually started on first preedit output.
                            if (sSuppressNextUp && !sAsrStartConfirmed) {
                                sAsrStartConfirmed = true;
                                log("AsrStartConfirmed via onAsrSetPreedit");
                            }
                        }
                    });
            log("hooked " + KEYBOARD_JNI + "#onAsrSetPreedit");
        } catch (Throwable t) {
            log("ERR hook onAsrSetPreedit: " + Log.getStackTraceString(t));
            throw new IllegalStateException("required hook installation failed", t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.bytedance.android.input.speech.AsrContext", cl,
                    sCompat.contextDoneMethod(), int.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!sReady) return;
                            boolean isDone = Boolean.TRUE.equals(param.args[1]);
                            if (isDone) {
                                diag("AsrContext." + sCompat.contextDoneMethod() + " phase=" + param.args[0] + " done=true");
                                sMainHandler.post(() -> dispatchCompletedNativeSend(cl));
                            }
                        }
                    });
            log("hooked AsrContext#" + sCompat.contextDoneMethod());
        } catch (Throwable t) {
            log("ERR hook AsrContext#" + sCompat.contextDoneMethod() + ": " + t.getClass().getSimpleName());
            throw new IllegalStateException("required hook installation failed", t);
        }
    }

    // ===== Hook 4: IME lifecycle — clear state on input session end =====
    private static void installImeLifecycleHook(final ClassLoader cl) {
        XC_MethodHook resetter = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                            if (!sReady) return;
                resetVolatileState("ImeService." + param.method.getName());
            }
        };
        try {
            XposedHelpers.findAndHookMethod(IME_SERVICE, cl, "onFinishInput", resetter);
            log("hooked " + IME_SERVICE + "#onFinishInput");
        } catch (Throwable t) {
            log("ERR hook onFinishInput: " + t.getClass().getSimpleName());
            throw new IllegalStateException("required hook installation failed", t);
        }
        try {
            XposedHelpers.findAndHookMethod(IME_SERVICE, cl, "onFinishInputView",
                    boolean.class, resetter);
            log("hooked " + IME_SERVICE + "#onFinishInputView(boolean)");
        } catch (Throwable t) {
            log("ERR hook onFinishInputView: " + t.getClass().getSimpleName());
            throw new IllegalStateException("required hook installation failed", t);
        }
        try {
            XposedHelpers.findAndHookMethod(IME_SERVICE, cl, "onStartInputView",
                    EditorInfo.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!sReady) return;
                            int h = readToolbarHeight(sClassLoader);
                            if (h > 0) {
                                sCachedToolbarHeight = h;
                            }
                            EditorInfo info = (EditorInfo) param.args[0];
                            if (info != null && A11Y_SEND_PACKAGES.contains(info.packageName)) {
                                ensureA11yReadyIfNeeded(sClassLoader, "onStartInputView", false);
                            }
                        }
                    });
            log("hooked " + IME_SERVICE + "#onStartInputView");
        } catch (Throwable t) {
            log("ERR hook onStartInputView: " + t.getClass().getSimpleName());
            throw new IllegalStateException("required hook installation failed", t);
        }
    }

    private static void resetVolatileState(String reason) {
        cancelNativeSend(reason);
        GestureSession session = sGestureSession;
        if (session != null && session.isActive()) {
            session.finish(GestureSession.Terminal.ABORTED);
            diag("gesture lifecycle abort id=" + session.id + " reason=" + reason);
        }
        sGestureSession = null;
        if (sSuppressNextUp || sCancelUntilElapsed != 0L || sPendingCommit != null
                || sMaxDisplacementSq != 0f) {
            log("resetVolatileState reason=" + reason);
        }
        sSuppressNextUp = false;
        sAsrStartConfirmed = false;
        sCancelUntilElapsed = 0L;
        sMaxDisplacementSq = 0f;
        sCurrentZone = Zone.LETTER;
        sRecordingEnterOrdinal = -1;
        sCachedToolbarHeight = -1;
        cancelPendingCommit();
        LinearLayout ov = sOverlay;
        if (ov != null) {
            try {
                ov.setVisibility(View.GONE);
            } catch (Throwable ignore) {
            }
            // Keep sOverlay, sOverlayIcon, sOverlayLabel references for reuse.
        }
    }

    // ===== Action helpers =====

    private static void triggerVoiceStart(ClassLoader cl) {
        cancelNativeSend("new recording");
        try {
            Class<?> jni = XposedHelpers.findClass(KEYBOARD_JNI, cl);
            XposedHelpers.callStaticMethod(jni, "DoFunctionKey", DO_FUNCTION_KEY_VOICE_START);
        } catch (Throwable t) {
            throw new IllegalStateException("voice start JNI failed", t);
        }
    }

    /**
     * Toolbar release dispatcher. Splits by enterActionType:
     * <ul>
     *   <li><b>Specific send action</b> (GO / SEARCH / SEND / SEND_EXPRESSION):
     *       routes through {@code AsrManager.t(ord, now)} so Doubao's
     *       "wait for all ASR back + 整理 + perform action" flow runs.
     *       Adds latency (~1s) but ensures the action sees the final
     *       polished ASR text — same behavior as Doubao space-long-press
     *       slide-to-send.</li>
     *   <li><b>Newline-like</b> (UNSPECIFIED / NONE / NEXT / DONE / PREVIOUS):
     *       skips {@code t()}'s wait path; commits ASR via {@code p0(false,"")}
     *       (keeps 整理 commit-time semantics) and sends {@code KEYCODE_ENTER}
     *       after a short settle. Result: newline shows up promptly.</li>
     * </ul>
     */
    private static void commitAndDispatchToolbarAction(final ClassLoader cl) {
        cancelPendingCommit();
        Object inputView = getInputView(cl);
        callInputViewCloseAsrUi(inputView, false);

        String pkg = currentEditorPackageName(cl);
        if (isA11yEditor(cl, pkg)) {
            dispatchViaA11ySend(cl, pkg);
            return;
        }

        int enterOrdinal = sRecordingEnterOrdinal >= 0
                ? sRecordingEnterOrdinal
                : resolveEffectiveEnterOrdinal(cl);
        if (enterOrdinal < 0) {
            enterOrdinal = 1;
        }
        boolean specific = isSpecificSendOrdinal(enterOrdinal);
        log("toolbar release enterOrdinal=" + enterOrdinal
                + " specificSend=" + specific);

        if (specific) {
            dispatchViaAsrManagerT(cl, enterOrdinal);
        } else {
            dispatchNewlineFast(cl);
        }
    }

    /**
     * Accessibility-send path: commit ASR text, wait for Doubao's async ASR
     * finalization to settle, then ask our AccessibilityService to click the
     * target app's visible send button.
     */
    private static final java.util.Set<Long> sOwnedSendIds = new java.util.HashSet<>();
    private static NativeSend sNativeSend;
    private static long sLastSendId;
    private static final class NativeSend {
        final long id;
        boolean readyForwarded, dispatchStarted;
        final boolean accessibility;
        final int ordinal;
        final String pkg;
        final EditorInfo editor;
        final android.view.inputmethod.InputConnection connection;
        NativeSend(long id, String pkg, EditorInfo editor, android.view.inputmethod.InputConnection connection, boolean accessibility, int ordinal) {
            this.id=id; this.pkg=pkg; this.editor=editor; this.connection=connection;
            this.accessibility=accessibility; this.ordinal=ordinal;
        }
    }
    private static android.inputmethodservice.InputMethodService imeService(ClassLoader cl) {
        android.content.Context context=getImeContext(cl);
        if(!(context instanceof android.inputmethodservice.InputMethodService))
            throw new IllegalStateException("IME service unavailable");
        return (android.inputmethodservice.InputMethodService)context;
    }
    private static volatile long sLastEnsureA11yBroadcastMs;
    private static final long ENSURE_A11Y_COOLDOWN_MS = 1500L;

    private static boolean isA11yServiceEnabledInSettings(android.content.Context ctx) {
        if (ctx == null) return false;
        String enabled = Settings.Secure.getString(
                ctx.getContentResolver(), "enabled_accessibility_services");
        int master = Settings.Secure.getInt(
                ctx.getContentResolver(), "accessibility_enabled", 0);
        return master == 1 && BootRestoreReceiver.containsOurService(enabled);
    }

    private static boolean isA11yServiceBoundInManager(android.content.Context ctx) {
        if (ctx == null) return false;
        try {
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager)
                            ctx.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
            if (am == null) return false;
            java.util.List<android.accessibilityservice.AccessibilityServiceInfo> list =
                    am.getEnabledAccessibilityServiceList(
                            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (list == null) return false;
            for (android.accessibilityservice.AccessibilityServiceInfo info : list) {
                if (info == null) continue;
                String id = info.getId();
                if (A11Y_SERVICE_COMPONENT_FULL.equals(id) || A11Y_SERVICE_COMPONENT.equals(id)) {
                    return true;
                }
                if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null) {
                    android.content.pm.ServiceInfo si = info.getResolveInfo().serviceInfo;
                    if ("com.jin.doubaolongpressvoice".equals(si.packageName)
                            && "com.jin.doubaolongpressvoice.DoubaoVoiceSendA11yService".equals(si.name)) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            diag("ERR check bound a11y list: " + t.getClass().getSimpleName());
        }
        return false;
    }

    private static boolean isA11yServiceHealthy(android.content.Context ctx) {
        return isA11yServiceEnabledInSettings(ctx) && isA11yServiceBoundInManager(ctx);
    }

    private static void ensureA11yReadyIfNeeded(ClassLoader cl, String reason, boolean force) {
        try {
            if (Build.VERSION.SDK_INT < 34) return;
            String pkg = currentEditorPackageName(cl);
            if (pkg == null || !A11Y_SEND_PACKAGES.contains(pkg)) return;
            android.content.Context ime = getImeContext(cl);
            if (ime == null || isA11yServiceHealthy(ime)) return;
            requestEnsureA11y(ime, reason + ":" + pkg, force);
        } catch (Throwable t) {
            diag("ERR ensureA11yReadyIfNeeded: " + t.getClass().getSimpleName());
        }
    }

    private static void requestEnsureA11y(android.content.Context ctx, String reason, boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && sLastEnsureA11yBroadcastMs > 0
                && now - sLastEnsureA11yBroadcastMs < ENSURE_A11Y_COOLDOWN_MS) {
            return;
        }
        sLastEnsureA11yBroadcastMs = now;
        try {
            android.content.Intent intent = new android.content.Intent(BootRestoreReceiver.ACTION_ENSURE_A11Y)
                    .setClassName("com.jin.doubaolongpressvoice",
                            "com.jin.doubaolongpressvoice.BootRestoreReceiver")
                    .addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                            | android.content.Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(BootRestoreReceiver.EXTRA_REASON, reason);
            if (Build.VERSION.SDK_INT >= 34) {
                ctx.sendBroadcast(intent, null,
                        android.app.BroadcastOptions.makeBasic()
                                .setShareIdentityEnabled(true).toBundle());
            } else {
                ctx.sendBroadcast(intent);
            }
            diag("sent ACTION_ENSURE_A11Y reason=" + reason);
        } catch (Throwable t) {
            diag("ERR send ACTION_ENSURE_A11Y: " + t.getClass().getSimpleName());
        }
    }

    private static void dispatchViaA11ySend(final ClassLoader cl, final String pkg) {
        cancelNativeSend("new request");
        try {
            android.inputmethodservice.InputMethodService ime=imeService(cl);
            if(Build.VERSION.SDK_INT<34) {
                diag("a11y unavailable (sdk<34); text retained pkg="+pkg);
                Toast.makeText(ime,A11Y_SEND_WARNING_TEXT,Toast.LENGTH_SHORT).show();
                callAsrGracefulCommit(ensureAsrManager(cl));
                return;
            }
            boolean healthy=isA11yServiceHealthy(ime);
            if(!healthy) {
                requestEnsureA11y(ime,"dispatch_send:"+pkg,true);
            }
            prepareNativeSend(cl,pkg,true,IME_ACTION_SEND_ORDINAL);
            if(!healthy) {
                final NativeSend recovering=sNativeSend;
                if(recovering!=null) {
                    sMainHandler.postDelayed(()-> {
                        if(sNativeSend==recovering && !recovering.readyForwarded) {
                            broadcastA11ySend(cl,recovering,DoubaoVoiceSendA11yService.PREPARE,null);
                        }
                    },120L);
                    sMainHandler.postDelayed(()-> {
                        if(sNativeSend==recovering && !recovering.readyForwarded) {
                            if(!isA11yServiceHealthy(ime)) {
                                diag("a11y still unhealthy after recovery; text retained pkg="+pkg);
                                Toast.makeText(ime,A11Y_SEND_WARNING_TEXT,Toast.LENGTH_SHORT).show();
                            } else {
                                broadcastA11ySend(cl,recovering,DoubaoVoiceSendA11yService.PREPARE,null);
                            }
                        }
                    },280L);
                }
            }
        } catch(Exception e) {
            cancelNativeSend("dispatch error");
            diag("ERR native a11y dispatch; no send: "+Log.getStackTraceString(e));
        }
    }
    /** Submit the final audio frame first. Completion events, never a settling timer, arm send. */
    private static void prepareNativeSend(ClassLoader cl,String pkg,boolean accessibility,int ordinal) throws Exception {
        android.inputmethodservice.InputMethodService ime=imeService(cl);
        EditorInfo editor=ime.getCurrentInputEditorInfo();
        android.view.inputmethod.InputConnection connection=ime.getCurrentInputConnection();
        if(editor==null || connection==null || !pkg.equals(editor.packageName))
            throw new IllegalStateException("editor unavailable");
        long id=Math.max(System.currentTimeMillis(),sLastSendId+1); sLastSendId=id;
        NativeSend request=new NativeSend(id,pkg,editor,connection,accessibility,ordinal);
        sNativeSend=request; sOwnedSendIds.add(id);
        if(accessibility)broadcastA11ySend(cl,request,DoubaoVoiceSendA11yService.PREPARE,null);
        sMainHandler.postDelayed(()-> {if(sNativeSend==request)cancelNativeSend("native completion deadline");},30_000L);
        sCompat.commit(ensureAsrManager(cl));
        diag("native send prepared id="+id+" pkg="+pkg+" accessibility="+accessibility);
    }
    private static void dispatchCompletedNativeSend(ClassLoader cl) {
        NativeSend request=sNativeSend;
        if(request==null || request.dispatchStarted)return;
        try {
            if(!(Boolean)sCompat.invoke("contextAllBack",sCompat.object("context")))return;
            android.inputmethodservice.InputMethodService ime=imeService(cl);
            if(ime.getCurrentInputEditorInfo()!=request.editor || ime.getCurrentInputConnection()!=request.connection
                    || !request.pkg.equals(currentEditorPackageName(cl))) {
                cancelNativeSend("editor changed before dispatch");return;
            }
            request.dispatchStarted=true;
            diag("native all-back dispatch id="+request.id);
            sCompat.dispatch(ensureAsrManager(cl),request.ordinal,request.id);
        } catch(Exception e) {
            cancelNativeSend("completed dispatch error");
            diag("ERR completed dispatch: "+Log.getStackTraceString(e));
        }
    }

    private static void installNativeAsrTrace(final ClassLoader cl) throws Exception {
        Class<?> facade = Class.forName("com.bytedance.android.input.basic.IAppGlobals$a", false, cl);
        int installed = 0;
        for (java.lang.reflect.Method method : facade.getDeclaredMethods()) {
            if (method.getReturnType() != void.class || !java.util.Arrays.equals(
                    method.getParameterTypes(), new Class<?>[]{String.class, String.class})) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    String tag = (String) param.args[0];
                    if (tag == null || !(tag.contains("ASR") || tag.contains("SmartOrganize"))) return;
                    String event = NativeAsrTrace.summarize((String) param.args[1]);
                    if (event != null) diag("host trace " + event);
                }
            });
            installed++;
        }
        if (installed == 0) throw new IllegalStateException("host trace facade unavailable");
        diag("host trace attached methods=" + installed);
    }

    private static void installFinalSendHook(final ClassLoader cl) {
        XposedBridge.hookMethod(sCompat.method("sendFinal"),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                long id=(Long)param.args[1];
                if(!sOwnedSendIds.contains(id))return;
                // Keep tombstones for this process: a late/duplicate host callback must never
                // escape into performEditorAction after its a11y request was cancelled.
                NativeSend request=sNativeSend;
                if(request==null || request.id!=id || request.readyForwarded) {param.setResult(null);return;}
                if(request.accessibility)param.setResult(null);
                try {
                    android.inputmethodservice.InputMethodService ime=imeService(cl);
                    if(ime.getCurrentInputEditorInfo()!=request.editor ||
                            ime.getCurrentInputConnection()!=request.connection ||
                            !request.pkg.equals(currentEditorPackageName(cl))) {
                        param.setResult(null); cancelNativeSend("editor changed"); return;
                    }
                    boolean completed=(Boolean)sCompat.invoke("contextAllBack",sCompat.object("context"));
                    if(!completed) {param.setResult(null);cancelNativeSend("host reached send without complete result");return;}
                    if(!request.accessibility) {
                        request.readyForwarded=true;
                        diag("native action completed id="+id+" elapsedMs="+(System.currentTimeMillis()-id));
                        return;
                    }
                    android.view.inputmethod.ExtractedTextRequest query=new android.view.inputmethod.ExtractedTextRequest();
                    query.hintMaxChars=65536;
                    android.view.inputmethod.ExtractedText extracted=request.connection.getExtractedText(query,0);
                    if(extracted==null || extracted.text==null || extracted.startOffset!=0 ||
                            extracted.partialStartOffset>=0 || extracted.text.length()>65536 ||
                            extracted.text.toString().trim().isEmpty()) {
                        cancelNativeSend("final editor text unavailable"); return;
                    }
                    broadcastA11ySend(cl,request,DoubaoVoiceSendA11yService.READY,extracted.text.toString());
                    diag("native completed id="+id+" elapsedMs="+(System.currentTimeMillis()-id));
                    request.readyForwarded=true;
                } catch(Exception e) { param.setResult(null); cancelNativeSend("completion error");diag("ERR final send: "+Log.getStackTraceString(e)); }
            }
        });
    }
    private static void cancelNativeSend(String reason) {
        NativeSend request=sNativeSend;
        if(request==null)return;
        sNativeSend=null;
        if(request.accessibility)broadcastA11ySend(sClassLoader,request,DoubaoVoiceSendA11yService.CANCEL,null);
        diag("native send aborted id="+request.id+" reason="+reason);
    }

    /**
     * Specific-action path: {@code AsrManager.t(ord, now)} — Doubao's official
     * "wait for ASR back, then perform action" flow. Has built-in latency but
     * ensures the editor (e.g., WeChat) receives the polished final text.
     */
    private static void dispatchViaAsrManagerT(ClassLoader cl, int enterOrdinal) {
        cancelNativeSend("new native request");
        try {
            prepareNativeSend(cl,currentEditorPackageName(cl),false,enterOrdinal);
        } catch(Exception e) {
            cancelNativeSend("native prepare error");
            diag("ERR native prepare: "+Log.getStackTraceString(e));
        }
    }

    /**
     * Newline-fast path: commit ASR text via Doubao's stop-ASR API and dispatch
     * a {@code KEYCODE_ENTER} key event once Doubao's ASR pipeline has
     * actually finished finalizing.
     *
     * <p><b>v1.3.0:</b> the naive "fixed delay between p0 and ENTER" approach
     * raced with Doubao's async ASR polish — the polished text would be
     * committed once by p0 and again by IME-framework auto-finishComposingText
     * when ENTER dispatched (especially on long text where polish takes longer).
     * The native all-back callback triggers ENTER; timeout aborts it.
     */
    private static void dispatchNewlineFast(final ClassLoader cl) {
        // Register listener BEFORE p0() to avoid missing the all-back callback.
        subscribeAsrAllBackThen(cl, NEWLINE_ASR_MAX_WAIT_MS, () -> sendEnterKey(cl));
        Object mgr = ensureAsrManager(cl);
        if (mgr != null) {
            callAsrStop(mgr, false, "", "newline path");
        }
    }

    /** Broadcasts a request to our AccessibilityService to click the send button. */
    private static void broadcastA11ySend(ClassLoader cl, NativeSend request, String stage, String text) {
        try {
            android.content.Context ctx=getImeContext(cl);
            if(ctx==null)throw new IllegalStateException("IME context missing");
            android.content.Intent intent=new android.content.Intent(DoubaoVoiceSendA11yService.ACTION_A11Y_SEND)
                    .setPackage("com.jin.doubaolongpressvoice")
                    .addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(DoubaoVoiceSendA11yService.EXTRA_TARGET_PKG,request.pkg)
                    .putExtra(DoubaoVoiceSendA11yService.EXTRA_ID,request.id)
                    .putExtra(DoubaoVoiceSendA11yService.EXTRA_STAGE,stage);
            if(text!=null)intent.putExtra(DoubaoVoiceSendA11yService.EXTRA_TEXT,text);
            if(Build.VERSION.SDK_INT>=34) ctx.sendBroadcast(intent,null,
                    android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
            else throw new IllegalStateException("authenticated send requires Android 14");
        } catch(Exception e) {diag("ERR a11y broadcast stage="+stage+": "+Log.getStackTraceString(e));}
    }

    private static android.content.Context getImeContext(ClassLoader cl) {
        Class<?> jniCls = XposedHelpers.findClass(KEYBOARD_JNI, cl);
        Object ime = XposedHelpers.getStaticObjectField(jniCls, "mImeService");
        if (ime instanceof android.content.Context) {
            return (android.content.Context) ime;
        }
        return null;
    }

    /** Sends KEYCODE_ENTER (66) via {@code InputMethodService.sendDownUpKeyEvents}. */
    private static void sendEnterKey(ClassLoader cl) {
        try {
            Class<?> jniCls = XposedHelpers.findClass(KEYBOARD_JNI, cl);
            Object ime = XposedHelpers.getStaticObjectField(jniCls, "mImeService");
            if (ime == null) {
                log("skip sendEnterKey: mImeService null");
                return;
            }
            XposedHelpers.callMethod(ime, "sendDownUpKeyEvents", 66);
            log("sent KEYCODE_ENTER (newline)");
        } catch (Throwable t) {
            log("ERR sendEnterKey: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Letter zone release = ordinary commit, mirrors space long-press "lift
     * anywhere not on a slide button". Calls {@code InputView.R(false)} plus
     * Doubao's graceful long-press stop API.
     */
    private static void commitVoice(final ClassLoader cl) {
        cancelPendingCommit();
        Object inputView = getInputView(cl);
        callInputViewCloseAsrUi(inputView, false);

        Object mgr = ensureAsrManager(cl);
        if (mgr == null) {
            log("skip graceful commit: AsrManager not resolvable");
            return;
        }
        callAsrGracefulCommit(mgr);
    }

    private static void cancelVoice(ClassLoader cl) {
        sCancelUntilElapsed = SystemClock.elapsedRealtime() + CANCEL_WINDOW_MS;
        try {
            Class<?> jni = XposedHelpers.findClass(KEYBOARD_JNI, cl);
            XposedHelpers.callStaticMethod(jni, "finishPreedit", false);
        } catch (Throwable t) {
            log("ERR finishPreedit: " + t.getClass().getSimpleName());
        }
        Object mgr = ensureAsrManager(cl);
        if (mgr != null) {
            callAsrStop(mgr, true, ASR_CANCEL_REASON, "cancel");
        }
    }

    private static void cancelPendingCommit() {
        Runnable r = sPendingCommit;
        if (r != null) {
            sMainHandler.removeCallbacks(r);
            sPendingCommit = null;
        }
    }

    private static void sendCancelToNative(View kvView, int x, int y) {
        int previousDepth = sInternalNativeTouchDepth.get();
        sInternalNativeTouchDepth.set(previousDepth + 1);
        try {
            Long nativeViewId = (Long) XposedHelpers.getObjectField(kvView, "mNativeViewId");
            if (nativeViewId == null || nativeViewId == 0L) {
                return;
            }
            long ts;
            try {
                Object t = XposedHelpers.callStaticMethod(kvView.getClass(),
                        "getCurrentMicrosecond");
                ts = (t instanceof Long)
                        ? (Long) t
                        : (SystemClock.elapsedRealtimeNanos() / 1000L);
            } catch (Throwable ignore) {
                ts = SystemClock.elapsedRealtimeNanos() / 1000L;
            }
            XposedHelpers.callMethod(kvView, "nativeTouch",
                    nativeViewId.longValue(), x, y, ACTION_CANCEL, ts);
        } catch (Throwable t) {
            log("ERR sendCancelToNative: " + t.getClass().getSimpleName());
        } finally {
            sInternalNativeTouchDepth.set(previousDepth);
        }
    }

    private static void performSpeechStartFeedback() {
        if (!ensureFeedbackHandles()) {
            return;
        }
        try {
            sCompat.invoke("feedback", sUserInteractiveMgr,
                    sKeySoundKeyboard, sKeyVibrateStandard, sVibTypeSpeechStart, false);
        } catch (Throwable t) {
            log("ERR feedback: " + t.getClass().getSimpleName());
        }
    }

    /** CONFIRM-type haptic when entering a TOOLBAR / OUTSIDE selection zone. */
    private static void performZoneSelectionFeedback() {
        if (!ensureFeedbackHandles() || sVibTypeConfirm == null) {
            return;
        }
        try {
            sCompat.invoke("feedback", sUserInteractiveMgr,
                    sKeySoundKeyboard, sKeyVibrateStandard, sVibTypeConfirm, false);
        } catch (Throwable ignore) {
        }
    }

    /** Waits through the normal start window; never treats one false probe as failure. */
    private static void scheduleAsrStartVerification(final ClassLoader cl) {
        GestureSession session = sGestureSession;
        if (session == null) {
            return;
        }
        sMainHandler.postDelayed(() -> verifyAsrStart(cl, session, 0), 300L);
    }

    private static void verifyAsrStart(ClassLoader cl, GestureSession session, int attempt) {
        if (session == null || sGestureSession != session || !session.isActive()) {
            return;
        }
        Object mgr = ensureAsrManager(cl);
        if (mgr != null) {
            try {
                if (sCompat.isAsrActive(mgr) || sAsrStartConfirmed) {
                    diag("ASR active id=" + session.id + " attempt=" + attempt
                            + " family=" + sCompat.family());
                    return;
                }
            } catch (Throwable t) {
                diag("ERR ASR active probe id=" + session.id + ": "
                        + Log.getStackTraceString(t));
                return;
            }
        }
        if (attempt < 6) {
            sMainHandler.postDelayed(() -> verifyAsrStart(cl, session, attempt + 1), 150L);
            return;
        }
        if (session.finish(GestureSession.Terminal.ABORTED)) {
            diag("ASR start timeout id=" + session.id + " -> abort takeover");
            sSuppressNextUp = false;
            sMaxDisplacementSq = 0f;
            sGestureSession = null;
            sCurrentZone = Zone.LETTER;
            updateOverlayForZone(Zone.LETTER, 0, cl);
        }
    }

    // ===== Lazy resolution =====

    private static boolean ensureFeedbackHandles() {
        if (sFeedbackResolveOk) {
            return true;
        }
        if (sFeedbackResolveAttempted) {
            return false;
        }
        sFeedbackResolveAttempted = true;
        ClassLoader cl = sClassLoader;
        if (cl == null) {
            return false;
        }
        try {
            Class<?> mgrClass = XposedHelpers.findClass(USER_INTERACTIVE_MGR, cl);
            sUserInteractiveMgr = sCompat.object("feedbackManager");

            Class<?> soundEnum = XposedHelpers.findClass(USER_INTERACTIVE_MGR + "$KeySound", cl);
            sKeySoundKeyboard = XposedHelpers.getStaticObjectField(soundEnum, "KEYBOARD");

            Class<?> vibrateEnum = XposedHelpers.findClass(USER_INTERACTIVE_MGR + "$KeyVibrate", cl);
            sKeyVibrateStandard = XposedHelpers.getStaticObjectField(vibrateEnum, "STANDARD");

            Class<?> vibTypeEnum = XposedHelpers.findClass(VIBRATION_CONTROLLER + "$VibrationType", cl);
            sVibTypeSpeechStart = XposedHelpers.getStaticObjectField(vibTypeEnum, "SPEECH_START");
            try {
                sVibTypeConfirm = XposedHelpers.getStaticObjectField(vibTypeEnum, "CONFIRM");
            } catch (Throwable ignore) {
                // CONFIRM is optional; zone-selection haptic falls silent.
            }

            sFeedbackResolveOk = sUserInteractiveMgr != null
                    && sKeySoundKeyboard != null
                    && sKeyVibrateStandard != null
                    && sVibTypeSpeechStart != null;
            log("feedback handles resolved (lazy): ok=" + sFeedbackResolveOk
                    + " confirm=" + (sVibTypeConfirm != null));
            return sFeedbackResolveOk;
        } catch (Throwable t) {
            log("ERR ensureFeedbackHandles: " + Log.getStackTraceString(t));
            return false;
        }
    }

    private static Object ensureAsrManager(ClassLoader cl) {
        if (sAsrManager != null) {
            return sAsrManager;
        }
        sAsrResolveAttempted = true;
        try {
            DoubaoCompatAdapter adapter = sCompat;
            if (adapter == null || !adapter.isSupported()) {
                return null;
            }
            sAsrManager = adapter.managerInstance();
        } catch (Throwable t) {
            diag("ERR ensureAsrManager: " + Log.getStackTraceString(t));
        }
        return sAsrManager;
    }

    private static boolean callAsrStop(Object mgr, boolean noWaitResult, String from,
                                       String logContext) {
        try {
            sCompat.stop(mgr, noWaitResult, from);
            diag("ASR stop family=" + sCompat.family() + " noWait=" + noWaitResult
                    + " from=" + from + " context=" + logContext);
            return true;
        } catch (Throwable t) {
            diag("ERR ASR stop family=" + sCompat.family() + " context=" + logContext
                    + ": " + Log.getStackTraceString(t));
            return false;
        }
    }

    private static void callAsrGracefulCommit(Object mgr) {
        try {
            sCompat.commit(mgr);
            diag("ASR graceful commit family=" + sCompat.family());
        } catch (Throwable t) {
            diag("ERR ASR graceful commit family=" + sCompat.family()
                    + ": " + Log.getStackTraceString(t));
        }
    }

    private static Object ensureAsrProcess(ClassLoader cl) {
        try { return sCompat.object("process"); }
        catch (Exception e) { throw new IllegalStateException("ASR process unavailable", e); }
    }

    private static void safeW(Object proc, Class<?> listenerClass, Object listener) {
        try { sCompat.invoke("listenerSetter", proc, listener); }
        catch (Exception e) { diag("ERR listener unregister: " + Log.getStackTraceString(e)); }
    }

    /** Await the native all-back event. A timeout aborts the terminal action, never guesses completion. */
    private static void subscribeAsrAllBackThen(
            final ClassLoader cl, final long maxWaitMs, final Runnable terminal) {
        try {
            final Object proc = ensureAsrProcess(cl);
            final Class<?> listenerClass = sCompat.listenerClass();
            final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
            final Runnable[] timeoutRef = new Runnable[1];
            Object listener = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return proxy == args[0];
                            return "DoubaoAllBackListener";
                        }
                        if (!method.equals(sCompat.method("callback")))
                            throw new IllegalStateException("unexpected callback: " + method);
                        boolean allBack = (Boolean) sCompat.invoke("allBack", args[0]);
                        if (allBack && done.compareAndSet(false, true)) sMainHandler.post(() -> {
                            sMainHandler.removeCallbacks(timeoutRef[0]);
                            safeW(proc, listenerClass, null);
                            diag("asr-allback: confirmed -> terminal");
                            if (terminal != null) terminal.run();
                        });
                        return null;
                    });
            timeoutRef[0] = () -> {
                if (!done.compareAndSet(false, true)) return;
                safeW(proc, listenerClass, null);
                diag("ERR asr-allback timeout; terminal action aborted");
            };
            sCompat.invoke("listenerSetter", proc, listener);
            sMainHandler.postDelayed(timeoutRef[0], maxWaitMs);
        } catch (Exception e) {
            diag("ERR asr-allback subscription; terminal aborted: " + Log.getStackTraceString(e));
        }
    }

    // ===== Generic helpers =====

    private static Class<?> findHandlerInnerClass(Class<?> outer) {
        try {
            return Class.forName(outer.getName() + "$c", false, outer.getClassLoader());
        } catch (Throwable ignore) {
        }
        try {
            for (Class<?> c : outer.getDeclaredClasses()) {
                if (Handler.class.isAssignableFrom(c)) {
                    return c;
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private static Object extractKeyboardView(Object handlerInstance) {
        try {
            for (Field f : handlerInstance.getClass().getDeclaredFields()) {
                if (SoftReference.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object ref = f.get(handlerInstance);
                    if (ref instanceof SoftReference) {
                        return ((SoftReference<?>) ref).get();
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private static int readKbdType(ClassLoader cl) {
        try {
            Class<?> jni = XposedHelpers.findClass(KEYBOARD_JNI, cl);
            Object inst = XposedHelpers.callStaticMethod(jni, "getKeyboardJni");
            Object v = XposedHelpers.callMethod(inst, "getCurrentKbdType");
            return (v instanceof Integer) ? (Integer) v : -1;
        } catch (Throwable t) {
            return -2;
        }
    }

    /** Returns the toolbar height in pixels reported by Doubao, or -1 on failure. */
    private static int readToolbarHeight(ClassLoader cl) {
        try {
            Class<?> jni = XposedHelpers.findClass(KEYBOARD_JNI, cl);
            Object inst = XposedHelpers.callStaticMethod(jni, "getKeyboardJni");
            Object v = XposedHelpers.callMethod(inst, "getToolbarHeight");
            return (v instanceof Integer) ? (Integer) v : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int readInputClass(ClassLoader cl) {
        try {
            Class<?> jni = XposedHelpers.findClass(KEYBOARD_JNI, cl);
            Object ime = XposedHelpers.getStaticObjectField(jni, "mImeService");
            if (ime == null) {
                return -1;
            }
            Object ei = XposedHelpers.callMethod(ime, "getCurrentInputEditorInfo");
            if (!(ei instanceof EditorInfo)) {
                return -1;
            }
            return ((EditorInfo) ei).inputType & InputType.TYPE_MASK_CLASS;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Reads the current editor's package name via the IME service. Returns null
     * on any failure (no service, no editor info, missing field) — callers must
     * treat null as "not in any whitelist".
     */
    private static boolean isA11yEditor(ClassLoader cl, String pkg) {
        if(pkg==null || !A11Y_SEND_PACKAGES.contains(pkg))return false;
        try {
            EditorInfo editor=imeService(cl).getCurrentInputEditorInfo();
            if(editor==null || (editor.imeOptions & EditorInfo.IME_MASK_ACTION)==EditorInfo.IME_ACTION_SEARCH)return false;
            if(!pkg.equals("com.google.android.googlequicksearchbox"))return true;
            String viewId=imeService(cl).getPackageManager().getResourcesForApplication(pkg).getResourceName(editor.fieldId);
            return SendTargets.allowedEditor(pkg,viewId);
        } catch(Exception e) {
            diag("a11y editor not identified pkg="+pkg+" cause="+e.getClass().getSimpleName());
            return false;
        }
    }

    private static String currentEditorPackageName(ClassLoader cl) {
        try {
            Class<?> jni = XposedHelpers.findClass(KEYBOARD_JNI, cl);
            Object ime = XposedHelpers.getStaticObjectField(jni, "mImeService");
            if (ime == null) {
                return null;
            }
            Object ei = XposedHelpers.callMethod(ime, "getCurrentInputEditorInfo");
            if (!(ei instanceof EditorInfo)) {
                return null;
            }
            return ((EditorInfo) ei).packageName;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isNonTextInputClass(int inputClass) {
        return inputClass == InputType.TYPE_CLASS_NUMBER
                || inputClass == InputType.TYPE_CLASS_PHONE
                || inputClass == InputType.TYPE_CLASS_DATETIME;
    }

    private static boolean modeAllowed(ClassLoader cl, View kbdView) {
        try {
            Class<?> jni = XposedHelpers.findClass(KEYBOARD_JNI, cl);
            Object floating = XposedHelpers.callStaticMethod(jni, "isFloatingMode");
            if (Boolean.TRUE.equals(floating)) {
                return false;
            }
        } catch (Throwable ignore) {
        }
        try {
            int kbdW = kbdView.getWidth();
            int screenW = kbdView.getResources().getDisplayMetrics().widthPixels;
            if (screenW > 0 && kbdW > 0
                    && (float) kbdW / (float) screenW < ONE_HAND_WIDTH_RATIO) {
                return false;
            }
        } catch (Throwable ignore) {
        }
        return true;
    }

    /** Convert SWIPE_THRESHOLD_DP to px² per device density. Lazy-cached. */
    private static float ensureSwipeThresholdPxSq(View kvView) {
        float cached = sSwipeThresholdPxSq;
        if (cached >= 0f) {
            return cached;
        }
        try {
            float density = kvView.getResources().getDisplayMetrics().density;
            float thresholdPx = SWIPE_THRESHOLD_DP * density;
            float pxSq = thresholdPx * thresholdPx;
            sSwipeThresholdPxSq = pxSq;
            log("swipe threshold: " + SWIPE_THRESHOLD_DP + "dp * density="
                    + density + " = " + thresholdPx + "px");
            return pxSq;
        } catch (Throwable t) {
            float fallback = 60f * 60f;  // xxhdpi empirical
            sSwipeThresholdPxSq = fallback;
            return fallback;
        }
    }

    private static boolean isLetterZone(ClassLoader cl, int x, int y, int w, int h, int kbdType,
                                        int toolbarHeight) {
        if (w <= 0 || h <= 0) {
            return false;
        }
        // Primary top exclusion: native getToolbarHeight(); when that is 0,
        // prefer live native_candidate_bar height and keep ratio as last resort.
        int topExclusion = effectiveToolbarHeight(cl, w, h, toolbarHeight);
        if (y < topExclusion) {
            return false;
        }
        // Bottom row (space + function keys).
        if (y >= h * LETTER_BOTTOM) {
            return false;
        }
        if (kbdType == KBD_TYPE_9KEY) {
            // 9-key: left mode col + right backspace col are excluded for every row.
            if (x < w * NINE_KEY_X_LEFT || x > w * NINE_KEY_X_RIGHT) {
                return false;
            }
        } else {
            // QWERTY-like: only row 3 edges (Shift / Backspace).
            if (y >= h * LETTER_ROW3_TOP
                    && (x < w * LETTER_ROW3_X_LEFT || x > w * LETTER_ROW3_X_RIGHT)) {
                return false;
            }
        }
        return true;
    }

    /** 1.3.15 KeyboardView no longer includes the toolbar sibling in its local bounds. */
    private static boolean isLetterZoneWithoutToolbar(int x, int y, int w, int h, int kbdType) {
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x >= w || y >= h) {
            return false;
        }
        if (y >= h * LETTER_BOTTOM) {
            return false;
        }
        if (kbdType == KBD_TYPE_9KEY) {
            return x >= w * NINE_KEY_X_LEFT && x <= w * NINE_KEY_X_RIGHT;
        }
        return y < h * LETTER_ROW3_TOP
                || (x >= w * LETTER_ROW3_X_LEFT && x <= w * LETTER_ROW3_X_RIGHT);
    }

    private static boolean isInCancelWindow() {
        return SystemClock.elapsedRealtime() < sCancelUntilElapsed;
    }

    // ===== Zone tracking + slide-to-action =====

    private static ZoneResolver.Geometry captureGlobalGeometry(ClassLoader cl, View keyboardView) {
        try {
            ZoneResolver.Bounds keyboard = globalBounds(keyboardView);
            Object inputObject = getInputView(cl);
            if (!(inputObject instanceof ViewGroup)) {
                return new ZoneResolver.Geometry(keyboard, null, null);
            }
            ViewGroup inputView = (ViewGroup) inputObject;
            ZoneResolver.Bounds input = globalBounds(inputView);
            int id = inputView.getResources().getIdentifier(
                    RES_ID_NATIVE_CANDIDATE_BAR, "id", DOUBAO_PACKAGE);
            View toolbarView = id == 0 ? null : inputView.findViewById(id);
            ZoneResolver.Bounds toolbar = globalBounds(toolbarView);
            ZoneResolver.Geometry geometry = new ZoneResolver.Geometry(keyboard, toolbar, input);
            diag("geometry snapshot " + geometry + " usable=" + geometry.isUsable());
            return geometry;
        } catch (Throwable t) {
            diag("ERR capture global geometry: " + Log.getStackTraceString(t));
            return null;
        }
    }

    private static ZoneResolver.Bounds globalBounds(View view) {
        if (view == null) {
            return null;
        }
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0 || !view.isAttachedToWindow()) {
            return null;
        }
        // MotionEvent.getRawX/Y is in physical screen coordinates.  Contrary to
        // its name, getGlobalVisibleRect() is relative to the IME root view on
        // current ColorOS builds; comparing it with rawY shifts every zone by
        // the IME window origin.  getLocationOnScreen() is the matching space.
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new ZoneResolver.Bounds(location[0], location[1],
                location[0] + width, location[1] + height);
    }

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

    private static Zone computeZone(ClassLoader cl, float x, float y, int w, int h,
                                    int toolbarHeight) {
        if (x < 0f || y < 0f || x >= w || y >= h) {
            return Zone.OUTSIDE;
        }
        if (y < effectiveToolbarHeight(cl, w, h, toolbarHeight)) {
            return Zone.TOOLBAR;
        }
        return Zone.LETTER;
    }

    /**
     * Called from {@code KeyboardView.onTouchEvent}'s ACTION_MOVE branch while
     * voice is recording. Re-computes the current zone, applies debounce, and
     * updates UI + haptic on transition.
     */
    private static void maybeUpdateZone(ClassLoader cl, View kvView, float x, float y,
                                        int w, int h) {
        int tbH = (sCachedToolbarHeight > 0) ? sCachedToolbarHeight : readToolbarHeight(cl);
        Zone next = computeZone(cl, x, y, w, h, tbH);
        if (next == sCurrentZone) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - sLastZoneChangeTs < ZONE_DEBOUNCE_MS) {
            return;
        }
        Zone prev = sCurrentZone;
        sCurrentZone = next;
        sLastZoneChangeTs = now;
        // Make sure overlay exists (may be detached after lifecycle reset).
        ensureOverlay(cl, effectiveToolbarHeight(cl, w, h, tbH));
        int enterOrdinal = (sRecordingEnterOrdinal >= 0) ? sRecordingEnterOrdinal : resolveEffectiveEnterOrdinal(cl);
        updateOverlayForZone(next, enterOrdinal, cl);
        if (next == Zone.TOOLBAR || next == Zone.OUTSIDE) {
            performZoneSelectionFeedback();
        }
        log("zone " + prev + " -> " + next
                + " coord=(" + x + "," + y + ")"
                + " tbH=" + tbH
                + " resolved=" + enterOrdinal);
    }

    /**
     * Reads the {@code InputView} singleton (a FrameLayout that hosts toolbar
     * + candidates + keyboard) from {@code ImeService}.
     */
    private static Object getInputView(ClassLoader cl) {
        try { return sCompat.inputView(); }
        catch (Exception e) { throw new IllegalStateException("input view unavailable", e); }
    }

    private static void callInputViewCloseAsrUi(Object inputView, boolean z) {
        if (inputView == null) throw new IllegalStateException("input view not initialized");
        try { sCompat.invoke("closePanel", inputView, z); }
        catch (Exception e) { throw new IllegalStateException("close ASR panel failed", e); }
    }

    /**
     * Resolves the canonical {@code EnterActionType} ordinal Doubao would use.
     * Uses {@code EditorViewInfo.e().d()} as the authoritative source, matching
     * what AsrLongPressView reads when picking its right-button text.
     */
    private static int resolveEnterOrdinal(ClassLoader cl) {
        try { return (Integer) sCompat.invoke("enterAction", sCompat.object("editor")); }
        catch (Exception e) { throw new IllegalStateException("editor action unavailable", e); }
    }

    /**
     * Returns the ordinal Doubao should use for BOTH dispatch AND UI label/icon.
     *
     * Layered judgement:
     * <ol>
     *   <li>If {@link #resolveEnterOrdinal} detected a specific send action
     *       (GO/SEARCH/SEND/SEND_EXPRESSION), trust it — no override.</li>
     *   <li>Else if current editor's package is in {@link #FORCE_SEND_PACKAGES},
     *       override to {@link #IME_ACTION_SEND_ORDINAL} so the App's listener
     *       receives the semantic send command.</li>
     *   <li>Else return the original (newline-class) ordinal unchanged.</li>
     * </ol>
     *
     * Used by both {@link #commitAndDispatchToolbarAction} and
     * {@link #maybeUpdateZone} so the overlay label NEVER says "换行" while
     * behavior is actually "send".
     */
    private static int resolveEffectiveEnterOrdinal(ClassLoader cl) {
        int ord = resolveEnterOrdinal(cl);
        if (isSpecificSendOrdinal(ord)) {
            return ord;
        }
        String pkg = currentEditorPackageName(cl);
        // FORCE_SEND packages dispatch IME_ACTION_SEND; A11Y_SEND packages send
        // via the AccessibilityService. Both are semantically "send", so the
        // overlay label/icon must read 发送, not 换行 — even though the editor
        // only reports a newline-class ordinal.
        if (pkg != null && (FORCE_SEND_PACKAGES.contains(pkg)
                || isA11yEditor(cl, pkg))) {
            log("send-label override: pkg=" + pkg + " original ord=" + ord
                    + " -> IME_ACTION_SEND");
            return IME_ACTION_SEND_ORDINAL;
        }
        return ord;
    }

    /**
     * Whether this enterActionType corresponds to a real "send / submit" action
     * Doubao actually surfaces in its UI. Verified by comparing with Doubao's
     * space-long-press: only {GO, SEARCH, SEND, SEND_EXPRESSION} are treated as
     * a specific action; NEXT / DONE / PREVIOUS / NONE / UNKNOWN all fall back
     * to "换行" (both label and behavior).
     */
    private static boolean isSpecificSendOrdinal(int ord) {
        return ord == 2 || ord == 3 || ord == 4 || ord == 8;
    }

    /** Per-action label, matched to AsrLongPressView's right-button text. */
    private static String labelForEnterOrdinal(int ord, ClassLoader cl) {
        switch (ord) {
            case 2: return resolveDoubaoString(cl, RES_NAME_GO, FALLBACK_GO);
            case 3: return resolveDoubaoString(cl, RES_NAME_SEARCH, FALLBACK_SEARCH);
            case 4:
            case 8: return resolveDoubaoString(cl, RES_NAME_SEND, FALLBACK_SEND);
            default:
                // NONE / NEXT / DONE / PREVIOUS / UNKNOWN all show 换行 to match
                // what Doubao's space-long-press shows in the same editors.
                return resolveDoubaoString(cl, RES_NAME_NEWLINE, TEXT_NEWLINE);
        }
    }

    /** Per-action drawable name. SEND/GO/SEND_EXPRESSION share oic_send. */
    private static String drawableNameForEnterOrdinal(int ord) {
        switch (ord) {
            case 2: return DRW_NAME_SEND;       // GO → use send icon (no go-specific)
            case 3: return DRW_NAME_SEARCH;
            case 4:
            case 8: return DRW_NAME_SEND;
            case 5: return DRW_NAME_NEXT;       // unused in current isSpecificSendOrdinal
            case 6: return DRW_NAME_FINISH;     // unused
            case 7: return DRW_NAME_PREVIOUS;   // unused
            default: return DRW_NAME_ENTER;     // 换行
        }
    }

    /**
     * Resolves a Doubao dimen (in px) by name, falling back to the given dp
     * value times density when unavailable.
     */
    private static int resolveDoubaoDimenPx(ClassLoader cl, String resName, int fallbackDp,
                                            float density) {
        try {
            Class<?> jni = XposedHelpers.findClass(KEYBOARD_JNI, cl);
            Object ime = XposedHelpers.getStaticObjectField(jni, "mImeService");
            if (ime != null) {
                android.content.Context ctx = (android.content.Context)
                        XposedHelpers.callMethod(ime, "getApplicationContext");
                if (ctx != null) {
                    android.content.res.Resources res = ctx.getResources();
                    int id = res.getIdentifier(resName, "dimen", DOUBAO_PACKAGE);
                    if (id != 0) {
                        return res.getDimensionPixelSize(id);
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return (int) (fallbackDp * density);
    }

    /**
     * Loads a Doubao drawable by name via {@code Context.createPackageContext}
     * on the IME's context. Returns null if not found.
     */
    private static Drawable resolveDoubaoDrawable(ClassLoader cl, String resName) {
        try {
            Class<?> jni = XposedHelpers.findClass(KEYBOARD_JNI, cl);
            Object ime = XposedHelpers.getStaticObjectField(jni, "mImeService");
            if (ime == null) return null;
            android.content.Context ctx =
                    (android.content.Context) XposedHelpers.callMethod(ime, "getApplicationContext");
            if (ctx == null) return null;
            android.content.res.Resources res = ctx.getResources();
            int id = res.getIdentifier(resName, "drawable", DOUBAO_PACKAGE);
            if (id == 0) return null;
            if (Build.VERSION.SDK_INT >= 21) {
                return res.getDrawable(id, null);
            }
            return res.getDrawable(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String resolveDoubaoString(ClassLoader cl, String resName,
                                              String fallback) {
        // Best-effort: try to load the string from Doubao's resources via
        // mImeService's context. Falls back to our hard-coded label.
        try {
            Class<?> jni = XposedHelpers.findClass(KEYBOARD_JNI, cl);
            Object ime = XposedHelpers.getStaticObjectField(jni, "mImeService");
            if (ime == null) return fallback;
            android.content.Context ctx =
                    (android.content.Context) XposedHelpers.callMethod(ime, "getApplicationContext");
            if (ctx == null) return fallback;
            android.content.res.Resources res = ctx.getResources();
            int id = res.getIdentifier(resName, "string", DOUBAO_PACKAGE);
            if (id == 0) return fallback;
            String s = res.getString(id);
            return (s == null || s.isEmpty()) ? fallback : s;
        } catch (Throwable t) {
            return fallback;
        }
    }

    // ===== Overlay UI =====

    /**
     * Lazily creates a TextView overlaying the toolbar area inside KeyboardView.
     * Cheap to call repeatedly: returns the cached view if its parent is still
     * the given kvView. Updates layout-height if the toolbar grew/shrank
     * (translation mode transition).
     */
    /**
     * Creates / re-attaches the overlay strip on the Doubao {@code InputView}.
     * The strip spans the toolbar's full width with keyboard-row-style margins
     * (4dp on all sides) and renders an icon + label in a single horizontal
     * row, centered.
     */
    private static LinearLayout ensureOverlay(ClassLoader cl, int toolbarHeight) {
        if (toolbarHeight <= 0) {
            return null;
        }
        // Reuse existing overlay if already attached to the correct parent.
        LinearLayout existing = sOverlay;
        if (existing != null) {
            ViewParent existingParent = existing.getParent();
            Object inputView = getInputView(cl);
            if (existingParent instanceof FrameLayout && existingParent == inputView) {
                if (existing.getVisibility() != View.VISIBLE) {
                    existing.setVisibility(View.VISIBLE);
                }
                updateOverlayLayout(existing, toolbarHeight);
                return existing;
            }
            // Parent changed (rare) — detach old and recreate below.
            try {
                if (existingParent instanceof ViewGroup) {
                    ((ViewGroup) existingParent).removeView(existing);
                }
            } catch (Throwable ignore) {}
            sOverlay = null;
            sOverlayIcon = null;
            sOverlayLabel = null;
            sOverlayParent = null;
        }
        Object inputView = getInputView(cl);
        ViewGroup parent = (inputView instanceof FrameLayout)
                ? (FrameLayout) inputView : null;
        if (parent == null) {
            log("ensureOverlay: InputView not a FrameLayout, overlay skipped");
            return null;
        }
        try {
            float density = parent.getResources().getDisplayMetrics().density;
            android.content.Context ctx = parent.getContext();

            // Root: horizontal LinearLayout = icon + label in a single row.
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER);
            root.setVisibility(View.VISIBLE);
            root.setAlpha(0f);
            root.setScaleX(SELECTION_SCALE_INITIAL);
            root.setScaleY(SELECTION_SCALE_INITIAL);
            root.setClickable(false);
            root.setFocusable(false);
            root.setEnabled(false);
            root.setOnTouchListener((v, e) -> false);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(OVERLAY_CORNER_RADIUS_DP * density);
            bg.setColor(COLOR_TRANSPARENT);
            root.setBackground(bg);
            sCurrentOverlayColor = COLOR_TRANSPARENT;

            if (Build.VERSION.SDK_INT >= 21) {
                root.setElevation(OVERLAY_ELEVATION_DP * density);
            }

            // Icon
            ImageView icon = new ImageView(ctx);
            int iconSize = (int) (OVERLAY_ICON_SIZE_DP * density);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                    iconSize, iconSize);
            iconLp.gravity = Gravity.CENTER_VERTICAL;
            iconLp.rightMargin = (int) (OVERLAY_ICON_TEXT_GAP_DP * density);
            icon.setLayoutParams(iconLp);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
            root.addView(icon);

            // Label (same row as icon)
            TextView label = new TextView(ctx);
            label.setTextSize(OVERLAY_TEXT_SP);
            label.setTextColor(Color.WHITE);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setIncludeFontPadding(false);
            label.setSingleLine(true);
            try {
                if (Build.VERSION.SDK_INT >= 28) {
                    label.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
                } else {
                    label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                }
            } catch (Throwable ignore) {
                label.setTypeface(Typeface.DEFAULT);
            }
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            labelLp.gravity = Gravity.CENTER_VERTICAL;
            label.setLayoutParams(labelLp);
            root.addView(label);

            // Full-width strip; uniform margin sourced from Doubao's own
            // candidate-container padding so it visually matches the spacing
            // between candidate words and the toolbar boundary.
            int margin = resolveDoubaoDimenPx(cl, DIMEN_NAME_OVERLAY_MARGIN,
                    OVERLAY_MARGIN_FALLBACK_DP, density);
            int stripHeight = Math.max(toolbarHeight - 2 * margin, toolbarHeight / 2);
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, stripHeight, Gravity.TOP);
            flp.setMargins(margin, margin, margin, margin);
            parent.addView(root, flp);

            sOverlay = root;
            sOverlayIcon = icon;
            sOverlayLabel = label;
            sOverlayParent = parent;
            log("overlay attached toolbarH=" + toolbarHeight
                    + " stripH=" + stripHeight + "px"
                    + " margin=" + margin + "px (density=" + density + ")");
            return root;
        } catch (Throwable t) {
            log("ERR ensureOverlay: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** Re-applies layout params if the toolbar height changed. */
    private static void updateOverlayLayout(LinearLayout overlay, int toolbarHeight) {
        try {
            float density = overlay.getResources().getDisplayMetrics().density;
            ClassLoader cl = overlay.getContext().getClassLoader();
            int margin = resolveDoubaoDimenPx(cl, DIMEN_NAME_OVERLAY_MARGIN,
                    OVERLAY_MARGIN_FALLBACK_DP, density);
            int newH = Math.max(toolbarHeight - 2 * margin, toolbarHeight / 2);
            ViewGroup.LayoutParams lp = overlay.getLayoutParams();
            if (lp != null && lp.height != newH) {
                lp.height = newH;
                overlay.setLayoutParams(lp);
            }
        } catch (Throwable ignore) {
        }
    }

    private static void updateOverlayForZone(Zone zone, int enterOrdinal, ClassLoader cl) {
        final LinearLayout tv = sOverlay;
        if (tv == null) {
            return;
        }
        Runnable update = () -> applyOverlayState(tv, zone, enterOrdinal, cl);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update.run();
        } else {
            tv.post(update);
        }
    }

    private static void applyOverlayState(LinearLayout tv, Zone zone, int enterOrdinal,
                                          ClassLoader cl) {
        if (zone == Zone.LETTER) {
            tv.animate()
                    .alpha(0f)
                    .scaleX(SELECTION_SCALE_INITIAL).scaleY(SELECTION_SCALE_INITIAL)
                    .setDuration(HIDE_ANIM_MS)
                    .start();
            setOverlayBackgroundColor(tv, COLOR_TRANSPARENT, true);
            return;
        }
        String text;
        String drawableName;
        int targetColor;
        if (zone == Zone.OUTSIDE) {
            // Match AsrNotchedEllipseView's hardcoded "撤回输入" (the
            // asr_long_press_rollback_text resource is actually "松手 撤回",
            // the hover-hint subtitle, not the main label).
            text = TEXT_CANCEL;
            drawableName = DRW_NAME_CANCEL;
            targetColor = COLOR_CANCEL;
        } else {
            text = labelForEnterOrdinal(enterOrdinal, cl);
            drawableName = drawableNameForEnterOrdinal(enterOrdinal);
            targetColor = COLOR_SEND;
        }
        TextView label = sOverlayLabel;
        if (label != null) {
            label.setText(text);
        }
        ImageView icon = sOverlayIcon;
        if (icon != null) {
            Drawable d = resolveDoubaoDrawable(cl, drawableName);
            if (d != null) {
                icon.setImageDrawable(d);
                icon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
                icon.setVisibility(View.VISIBLE);
            } else {
                // No icon → still show text by hiding the missing image slot.
                icon.setImageDrawable(null);
                icon.setVisibility(View.GONE);
            }
        }
        setOverlayBackgroundColor(tv, targetColor, false);
        tv.animate()
                .alpha(1f)
                .scaleX(SELECTION_SCALE).scaleY(SELECTION_SCALE)
                .setInterpolator(new OvershootInterpolator(1.5f))
                .setDuration(SELECTION_ANIM_MS)
                .start();
    }

    /**
     * @param snapInsteadOfAnimate if true, sets color directly (used for the
     *                              hide-out path); else interpolates via
     *                              {@link ArgbEvaluator}.
     */
    private static void setOverlayBackgroundColor(View tv, int targetColor,
                                                  boolean snapInsteadOfAnimate) {
        Drawable bg = tv.getBackground();
        if (!(bg instanceof GradientDrawable)) {
            tv.setBackgroundColor(targetColor);
            sCurrentOverlayColor = targetColor;
            return;
        }
        GradientDrawable gd = (GradientDrawable) bg;
        int from = sCurrentOverlayColor;
        if (from == targetColor) {
            return;
        }
        ValueAnimator prev = sColorAnimator;
        if (prev != null && prev.isRunning()) {
            prev.cancel();
        }
        if (snapInsteadOfAnimate) {
            gd.setColor(targetColor);
            sCurrentOverlayColor = targetColor;
            sColorAnimator = null;
            return;
        }
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, targetColor);
        anim.setDuration(SELECTION_ANIM_MS);
        anim.addUpdateListener(va -> {
            int c = (int) va.getAnimatedValue();
            gd.setColor(c);
            sCurrentOverlayColor = c;
        });
        sColorAnimator = anim;
        anim.start();
    }

    private static String safeText(String text) {
        return text == null ? "null" : "[length=" + text.length() + "]";
    }

    private static String actionName(int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN: return "DOWN";
            case MotionEvent.ACTION_UP: return "UP";
            case MotionEvent.ACTION_MOVE: return "MOVE";
            case MotionEvent.ACTION_CANCEL: return "CANCEL";
            default: return "ACTION_" + action;
        }
    }

    private static void log(String message) {
        if (!DEBUG) {
            return;
        }
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }

    /** Always-on structural diagnostics. Never include recognized or editor text. */
    private static void diag(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
