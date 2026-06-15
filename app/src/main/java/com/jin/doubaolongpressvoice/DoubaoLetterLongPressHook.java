package com.jin.doubaolongpressvoice;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

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
 * 1.2 s suppression window when the user releases off-keyboard:
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
    private static final String KEYBOARD_VIEW = "com.bytedance.android.input.keyboard.KeyboardView";
    private static final String KEYBOARD_JNI = "com.bytedance.android.doubaoime.KeyboardJni";
    private static final String IME_SERVICE = "com.bytedance.android.doubaoime.ImeService";
    private static final String USER_INTERACTIVE_MGR =
            "com.bytedance.android.input.keyboard.UserInteractiveManagerNext";
    private static final String VIBRATION_CONTROLLER =
            "com.bytedance.android.input.common.VibrationController";
    private static final String ASR_MANAGER =
            "com.bytedance.android.input.speech.AsrManager";

    private static final int MSG_LONGPRESS = 1;
    private static final int DO_FUNCTION_KEY_VOICE_START = 6;
    private static final int DO_FUNCTION_KEY_VOICE_STOP = 7;
    private static final int ACTION_CANCEL = MotionEvent.ACTION_CANCEL;
    private static final String ASR_CANCEL_REASON = "cancel";
    private static final long CANCEL_WINDOW_MS = 1200L;
    private static final long COMMIT_TAIL_DELAY_MS = 600L;     // fallback path only
    private static final long ASR_START_VERIFY_DELAY_MS = 80L;

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
    // Top exclusion = toolbar / candidates bar / translation banner area.
    // Doubao normally renders a one-row toolbar (AI / 翻译 / 剪贴板 ...) above the
    // key rows; when the translation feature is on, the toolbar grows another row.
    // We detect this by the KeyboardView h/w ratio (normal ≈ 0.74, tall ≈ 0.90+).
    private static final float LETTER_TOP_NORMAL = 0.08f;
    private static final float LETTER_TOP_TALL = 0.22f;
    private static final float TALL_KBD_H_OVER_W = 0.85f;

    // Swipe detection threshold (dp; converted to px at runtime per device).
    private static final float SWIPE_THRESHOLD_DP = 20f;

    // --- volatile per-session state ---
    private static volatile long sCancelUntilElapsed = 0L;
    private static volatile boolean sSuppressNextUp = false;
    private static volatile float sDownX;
    private static volatile float sDownY;
    private static volatile float sMaxDisplacementSq;
    private static volatile float sSwipeThresholdPxSq = -1f;
    private static volatile Runnable sPendingCommit;

    // --- lazy-resolved Doubao internals ---
    private static volatile Object sUserInteractiveMgr;
    private static volatile Object sKeySoundKeyboard;
    private static volatile Object sKeyVibrateStandard;
    private static volatile Object sVibTypeSpeechStart;
    private static volatile boolean sFeedbackResolveAttempted;
    private static volatile boolean sFeedbackResolveOk;
    private static volatile Object sAsrManager;
    private static volatile boolean sAsrResolveAttempted;
    private static ClassLoader sClassLoader;

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private DoubaoLetterLongPressHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        sClassLoader = cl;
        installHandlerHook(cl);
        installTouchHook(cl);
        installCommitSuppressionHooks(cl);
        installImeLifecycleHook(cl);
    }

    // ===== Hook 1: KeyboardView$c.handleMessage(MSG_LONGPRESS=1) =====
    private static void installHandlerHook(final ClassLoader cl) {
        try {
            Class<?> kvClass = XposedHelpers.findClass(KEYBOARD_VIEW, cl);
            Class<?> handlerInner = findHandlerInnerClass(kvClass);
            if (handlerInner == null) {
                log("ERR: cannot locate KeyboardView inner Handler class");
                return;
            }
            log("located inner Handler class: " + handlerInner.getName());

            XposedHelpers.findAndHookMethod(handlerInner, "handleMessage", Message.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Message msg = (Message) param.args[0];
                                if (msg == null || msg.what != MSG_LONGPRESS) {
                                    return;
                                }
                                Object kv = extractKeyboardView(param.thisObject);
                                if (!(kv instanceof View)) {
                                    return;
                                }
                                View kvView = (View) kv;
                                int x = msg.arg1;
                                int y = msg.arg2;
                                int w = kvView.getWidth();
                                int h = kvView.getHeight();
                                int kbdType = readKbdType(cl);
                                int inputClass = readInputClass(cl);

                                if (isNonTextInputClass(inputClass)) {
                                    log("gate=non_text_input inputClass=0x"
                                            + Integer.toHexString(inputClass)
                                            + " kbdType=" + kbdType + " skip");
                                    return;
                                }
                                if (KBD_TYPE_BLACKLIST.contains(kbdType)) {
                                    log("gate=blacklisted_layer kbdType=" + kbdType + " skip");
                                    return;
                                }
                                if (!modeAllowed(cl, kvView)) {
                                    log("gate=mode_blocked (floating/oneHand) skip");
                                    return;
                                }
                                if (!isLetterZone(x, y, w, h, kbdType)) {
                                    boolean tall = (h > w * TALL_KBD_H_OVER_W);
                                    log("gate=geom_outside x=" + x + " y=" + y
                                            + " w=" + w + " h=" + h + " kbdType=" + kbdType
                                            + " tallTb=" + tall);
                                    return;
                                }
                                float thresholdSq = ensureSwipeThresholdPxSq(kvView);
                                float maxDispSq = sMaxDisplacementSq;
                                if (maxDispSq > thresholdSq) {
                                    log("gate=swipe maxDisp=" + Math.sqrt(maxDispSq)
                                            + "px threshold=" + Math.sqrt(thresholdSq)
                                            + "px (" + SWIPE_THRESHOLD_DP + "dp) skip");
                                    return;
                                }

                                log("HIT letter long-press x=" + x + " y=" + y
                                        + " w=" + w + " h=" + h + " kbdType=" + kbdType
                                        + " -> DoFunctionKey(6)");
                                sCancelUntilElapsed = 0L;
                                cancelPendingCommit();
                                sSuppressNextUp = true;
                                param.setResult(null);
                                triggerVoiceStart(cl);
                                sendCancelToNative(kvView, x, y);
                                performSpeechStartFeedback();
                                scheduleAsrStartVerification(cl);
                            } catch (Throwable t) {
                                log("ERR handleMessage hook: " + Log.getStackTraceString(t));
                            }
                        }
                    });
            log("hooked " + handlerInner.getName() + "#handleMessage(Message)");
        } catch (Throwable t) {
            log("ERR install handler hook: " + Log.getStackTraceString(t));
        }
    }

    // ===== Hook 2: KeyboardView.onTouchEvent — track swipe; suppress UP/CANCEL =====
    private static void installTouchHook(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(KEYBOARD_VIEW, cl, "onTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
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

                                if (!sSuppressNextUp) {
                                    return;
                                }
                                if (action != MotionEvent.ACTION_UP
                                        && action != MotionEvent.ACTION_CANCEL) {
                                    return;
                                }
                                try {
                                    Handler h = (Handler) XposedHelpers.getObjectField(
                                            param.thisObject, "mHandler");
                                    if (h != null) {
                                        h.removeMessages(MSG_LONGPRESS);
                                    }
                                } catch (Throwable ignore) {
                                }
                                sSuppressNextUp = false;

                                boolean cancelIntent = (action == MotionEvent.ACTION_CANCEL);
                                float ux = 0f, uy = 0f;
                                int vw = 0, vh = 0;
                                if (!cancelIntent && param.thisObject instanceof View) {
                                    View vv = (View) param.thisObject;
                                    ux = ev.getX();
                                    uy = ev.getY();
                                    vw = vv.getWidth();
                                    vh = vv.getHeight();
                                    cancelIntent = (ux < 0f || uy < 0f || ux > vw || uy > vh);
                                }
                                if (cancelIntent) {
                                    cancelPendingCommit();
                                    cancelVoice(cl);
                                    log("release action=" + actionName(action)
                                            + " coord=(" + ux + "," + uy + ")/" + vw + "x" + vh
                                            + " -> cancel");
                                } else {
                                    commitVoice(cl);
                                    log("release action=" + actionName(action)
                                            + " coord=(" + ux + "," + uy + ")/" + vw + "x" + vh
                                            + " -> commit");
                                }
                                param.setResult(true);
                            } catch (Throwable t) {
                                log("ERR onTouchEvent hook: " + Log.getStackTraceString(t));
                            }
                        }
                    });
            log("hooked " + KEYBOARD_VIEW + "#onTouchEvent(MotionEvent)");
        } catch (Throwable t) {
            log("ERR install touch hook: " + Log.getStackTraceString(t));
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
        }
        try {
            XposedHelpers.findAndHookMethod(KEYBOARD_JNI, cl, "onAsrCommitPreeditText",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isInCancelWindow()) {
                                log("onAsrCommitPreeditText SWALLOWED");
                                param.setResult(Boolean.TRUE);
                            }
                        }
                    });
            log("hooked " + KEYBOARD_JNI + "#onAsrCommitPreeditText");
        } catch (Throwable t) {
            log("ERR hook onAsrCommitPreeditText: " + Log.getStackTraceString(t));
        }
        try {
            XposedHelpers.findAndHookMethod(KEYBOARD_JNI, cl, "onAsrSetPreedit",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isInCancelWindow()) {
                                log("onAsrSetPreedit SWALLOWED text="
                                        + safeText((String) param.args[0]));
                                param.setResult(Boolean.TRUE);
                            }
                        }
                    });
            log("hooked " + KEYBOARD_JNI + "#onAsrSetPreedit");
        } catch (Throwable t) {
            log("ERR hook onAsrSetPreedit: " + Log.getStackTraceString(t));
        }
    }

    // ===== Hook 4: IME lifecycle — clear state on input session end =====
    private static void installImeLifecycleHook(final ClassLoader cl) {
        XC_MethodHook resetter = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                resetVolatileState("ImeService." + param.method.getName());
            }
        };
        try {
            XposedHelpers.findAndHookMethod(IME_SERVICE, cl, "onFinishInput", resetter);
            log("hooked " + IME_SERVICE + "#onFinishInput");
        } catch (Throwable t) {
            log("ERR hook onFinishInput: " + t.getClass().getSimpleName());
        }
        try {
            XposedHelpers.findAndHookMethod(IME_SERVICE, cl, "onFinishInputView",
                    boolean.class, resetter);
            log("hooked " + IME_SERVICE + "#onFinishInputView(boolean)");
        } catch (Throwable t) {
            log("ERR hook onFinishInputView: " + t.getClass().getSimpleName());
        }
    }

    private static void resetVolatileState(String reason) {
        if (sSuppressNextUp || sCancelUntilElapsed != 0L || sPendingCommit != null
                || sMaxDisplacementSq != 0f) {
            log("resetVolatileState reason=" + reason);
        }
        sSuppressNextUp = false;
        sCancelUntilElapsed = 0L;
        sMaxDisplacementSq = 0f;
        cancelPendingCommit();
    }

    // ===== Action helpers =====

    private static void triggerVoiceStart(ClassLoader cl) {
        try {
            Class<?> jni = XposedHelpers.findClass(KEYBOARD_JNI, cl);
            XposedHelpers.callStaticMethod(jni, "DoFunctionKey", DO_FUNCTION_KEY_VOICE_START);
        } catch (Throwable t) {
            log("ERR DoFunctionKey(6): " + Log.getStackTraceString(t));
        }
    }

    /**
     * Commit path: invoke {@code AsrManager.p0(false, "")} directly — mirrors
     * what {@code AsrEditorLayoutView} does when the user taps the in-panel
     * stop button. This skips the {@code P(0)} interrupt that
     * {@code DoFunctionKey(7)} would otherwise inject, allowing Doubao's ASR
     * engine to finalize naturally (tail buffer + punctuation/post-processing).
     *
     * If the {@code p0} reflection fails, falls back to {@code forceVad()} +
     * delayed {@code DoFunctionKey(7)} for safety.
     */
    private static void commitVoice(final ClassLoader cl) {
        cancelPendingCommit();
        Object mgr = ensureAsrManager(cl);
        if (mgr != null) {
            try {
                XposedHelpers.callMethod(mgr, "p0", false, "");
                log("AsrManager.p0(false,\"\") fired (commit, panel-stop mimic)");
                return;
            } catch (Throwable e) {
                log("ERR p0(false,\"\"): " + e.getClass().getSimpleName()
                        + " -> fallback to forceVad + DoFunctionKey(7)");
            }
        }
        forceVad(cl);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (sPendingCommit != this) {
                    return;
                }
                sPendingCommit = null;
                stopVoiceCommitFallback(cl);
            }
        };
        sPendingCommit = r;
        sMainHandler.postDelayed(r, COMMIT_TAIL_DELAY_MS);
    }

    private static void stopVoiceCommitFallback(ClassLoader cl) {
        try {
            Class<?> jni = XposedHelpers.findClass(KEYBOARD_JNI, cl);
            XposedHelpers.callStaticMethod(jni, "DoFunctionKey", DO_FUNCTION_KEY_VOICE_STOP);
            log("DoFunctionKey(7) fallback fired");
        } catch (Throwable t) {
            log("ERR DoFunctionKey(7): " + Log.getStackTraceString(t));
        }
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
            try {
                XposedHelpers.callMethod(mgr, "p0", true, ASR_CANCEL_REASON);
            } catch (Throwable t) {
                log("ERR AsrManager.p0: " + t.getClass().getSimpleName());
            }
        }
    }

    private static void forceVad(ClassLoader cl) {
        Object mgr = ensureAsrManager(cl);
        if (mgr == null) {
            return;
        }
        try {
            XposedHelpers.callMethod(mgr, "x");
            log("AsrManager.x() forceVad fired (fallback)");
        } catch (Throwable t) {
            log("ERR forceVad: " + t.getClass().getSimpleName());
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
        }
    }

    private static void performSpeechStartFeedback() {
        if (!ensureFeedbackHandles()) {
            return;
        }
        try {
            XposedHelpers.callMethod(sUserInteractiveMgr, "g",
                    sKeySoundKeyboard, sKeyVibrateStandard, sVibTypeSpeechStart, false);
        } catch (Throwable t) {
            log("ERR feedback: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Defense against silent-fail ASR start (mic permission denied, model not
     * loaded, etc.): re-check after {@value #ASR_START_VERIFY_DELAY_MS} ms,
     * and if the engine isn't running, drop the UP-suppression so the next
     * keypress is not eaten.
     */
    private static void scheduleAsrStartVerification(final ClassLoader cl) {
        sMainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!sSuppressNextUp) {
                    return;
                }
                Object mgr = ensureAsrManager(cl);
                if (mgr == null) {
                    return;
                }
                try {
                    Object running = XposedHelpers.callMethod(mgr, "E");
                    if (Boolean.FALSE.equals(running)) {
                        log("ASR did NOT start (E()=false) -> rollback suppress");
                        sSuppressNextUp = false;
                        sMaxDisplacementSq = 0f;
                    }
                } catch (Throwable t) {
                    log("ERR ASR start verify: " + t.getClass().getSimpleName());
                }
            }
        }, ASR_START_VERIFY_DELAY_MS);
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
            sUserInteractiveMgr = XposedHelpers.getStaticObjectField(mgrClass, "a");

            Class<?> soundEnum = XposedHelpers.findClass(USER_INTERACTIVE_MGR + "$KeySound", cl);
            sKeySoundKeyboard = XposedHelpers.getStaticObjectField(soundEnum, "KEYBOARD");

            Class<?> vibrateEnum = XposedHelpers.findClass(USER_INTERACTIVE_MGR + "$KeyVibrate", cl);
            sKeyVibrateStandard = XposedHelpers.getStaticObjectField(vibrateEnum, "STANDARD");

            Class<?> vibTypeEnum = XposedHelpers.findClass(VIBRATION_CONTROLLER + "$VibrationType", cl);
            sVibTypeSpeechStart = XposedHelpers.getStaticObjectField(vibTypeEnum, "SPEECH_START");

            sFeedbackResolveOk = sUserInteractiveMgr != null
                    && sKeySoundKeyboard != null
                    && sKeyVibrateStandard != null
                    && sVibTypeSpeechStart != null;
            log("feedback handles resolved (lazy): ok=" + sFeedbackResolveOk);
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
        if (sAsrResolveAttempted) {
            return null;
        }
        sAsrResolveAttempted = true;
        try {
            Class<?> asrCls = XposedHelpers.findClass(ASR_MANAGER, cl);
            sAsrManager = XposedHelpers.getStaticObjectField(asrCls, "a");
        } catch (Throwable t) {
            log("ERR ensureAsrManager: " + Log.getStackTraceString(t));
        }
        return sAsrManager;
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

    private static boolean isLetterZone(int x, int y, int w, int h, int kbdType) {
        if (w <= 0 || h <= 0) {
            return false;
        }
        // Top exclusion (toolbar / candidates / translation banner). Picks the
        // wider cutoff when the keyboard is taller than a usual 4-row layout,
        // which means Doubao raised the toolbar (translation mode etc.).
        boolean tallToolbar = (h > w * TALL_KBD_H_OVER_W);
        float topRatio = tallToolbar ? LETTER_TOP_TALL : LETTER_TOP_NORMAL;
        if (y < h * topRatio) {
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

    private static boolean isInCancelWindow() {
        return SystemClock.elapsedRealtime() < sCancelUntilElapsed;
    }

    private static String safeText(String text) {
        if (text == null) {
            return "null";
        }
        int n = text.length();
        if (n <= 20) {
            return "'" + text + "'";
        }
        return "'" + text.substring(0, 20) + "...'(len=" + n + ")";
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
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
