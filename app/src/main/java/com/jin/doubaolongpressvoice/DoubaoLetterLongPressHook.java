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
    private static final String ASR_PROCESS_CLS =
            "com.bytedance.android.input.speech.z";
    private static final String ASR_ALL_BACK_LISTENER_CLS =
            "com.bytedance.android.input.speech.L.a";
    private static final String EDITOR_VIEW_INFO =
            "com.bytedance.android.input.speech.view.o";
    private static final String DOUBAO_PACKAGE = "com.bytedance.android.doubaoime";

    private static final int MSG_LONGPRESS = 1;
    private static final int DO_FUNCTION_KEY_VOICE_START = 6;
    private static final int DO_FUNCTION_KEY_VOICE_STOP = 7;
    private static final int DO_FUNCTION_KEY_SEND_ACTION = 2;   // doSendAction()
    private static final int ACTION_CANCEL = MotionEvent.ACTION_CANCEL;
    private static final String ASR_CANCEL_REASON = "cancel";
    private static final long CANCEL_WINDOW_MS = 500L;
    /**
     * Newline-path ASR-settle parameters. Instead of a fixed delay before
     * dispatching KEYCODE_ENTER (which races with Doubao's async ASR polish
     * and causes the polished text to be committed twice — once by p0() and
     * again by the IME framework's auto-finishComposingText at ENTER), we
     * poll for Doubao's ASR callbacks ({@code onAsrSetPreedit},
     * {@code onAsrCommitPreeditText}) to go quiet, then fire ENTER.
     *
     * Two-phase: (1) wait for first ASR callback after dispatch (so short
     * text doesn't fire ENTER before ASR even responds), (2) wait for
     * {@code NEWLINE_ASR_SETTLE_MS} of post-callback silence.
     */
    private static final long NEWLINE_ASR_POLL_MS = 50L;        // poll interval
    private static final long NEWLINE_ASR_SETTLE_MS = 100L;     // quiet window before ENTER
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
                    "tw.nekomimi.nekogram",
                    "org.telegram.messenger",
                    "com.baidu.ernie"));
    private static final java.util.Set<String> A11Y_SEND_PACKAGES =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "com.anthropic.claude",
                    "com.openai.chatgpt",
                    "com.google.android.apps.bard",
                    "ai.x.grok",
                    "com.moonshot.kimichat"));

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
    // Top exclusion = toolbar / candidates bar / translation banner area.
    // Doubao normally renders a one-row toolbar (AI / 翻译 / 剪贴板 ...) above the
    // key rows; when the translation feature is on, the toolbar grows another row.
    // We detect this by the KeyboardView h/w ratio (normal ≈ 0.74, tall ≈ 0.90+).
    private static final float LETTER_TOP_NORMAL = 0.08f;
    private static final float LETTER_TOP_TALL = 0.22f;
    private static final float TALL_KBD_H_OVER_W = 0.85f;

    // Swipe detection threshold (dp; converted to px at runtime per device).
    private static final float SWIPE_THRESHOLD_DP = 20f;

    /** In-recording finger zone (drives slide-to-action). */
    private enum Zone { LETTER, TOOLBAR, OUTSIDE }

    // --- volatile per-session state ---
    private static volatile long sCancelUntilElapsed = 0L;
    /** Last time onAsrSetPreedit / onAsrCommitPreeditText fired — used by mode-8 ASR-settle poll. */
    private static volatile long sLastAsrCallbackTs = 0L;
    private static volatile boolean sSuppressNextUp = false;
    private static volatile boolean sAsrStartConfirmed = false;
    private static volatile float sDownX;
    private static volatile float sDownY;
    private static volatile float sMaxDisplacementSq;
    private static volatile float sSwipeThresholdPxSq = -1f;
    private static volatile Runnable sPendingCommit;
    private static volatile Zone sCurrentZone = Zone.LETTER;
    private static volatile long sLastZoneChangeTs = 0L;
    private static volatile LinearLayout sOverlay;
    private static volatile ImageView sOverlayIcon;
    private static volatile TextView sOverlayLabel;
    private static volatile int sCurrentOverlayColor = COLOR_TRANSPARENT;
    private static volatile ValueAnimator sColorAnimator;
    private static volatile ViewGroup sOverlayParent;
    private static volatile int sCachedToolbarHeight = -1;

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
    private static volatile Object sAsrProcess;
    private static volatile boolean sAsrProcessResolveAttempted;
    private static volatile Class<?> sListenerCls;
    private static volatile boolean sListenerClsResolveAttempted;
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
                                int toolbarHeight = (sCachedToolbarHeight > 0)
                                        ? sCachedToolbarHeight : readToolbarHeight(cl);

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
                                if (!isLetterZone(x, y, w, h, kbdType, toolbarHeight)) {
                                    log("gate=geom_outside x=" + x + " y=" + y
                                            + " w=" + w + " h=" + h + " kbdType=" + kbdType
                                            + " toolbarH=" + toolbarHeight);
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
                                        + " toolbarH=" + toolbarHeight
                                        + " -> DoFunctionKey(6)");
                                sCancelUntilElapsed = 0L;
                                cancelPendingCommit();
                                sSuppressNextUp = true;
                                sAsrStartConfirmed = false;
                                sCurrentZone = Zone.LETTER;
                                sLastZoneChangeTs = SystemClock.elapsedRealtime();
                                param.setResult(null);
                                triggerVoiceStart(cl);
                                sendCancelToNative(kvView, x, y);
                                performSpeechStartFeedback();
                                ensureOverlay(cl, toolbarHeight);
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
                                    // While voice is active, track zone for slide-to-action.
                                    if (sSuppressNextUp && param.thisObject instanceof View) {
                                        View vv = (View) param.thisObject;
                                        maybeUpdateZone(cl, vv, ev.getX(), ev.getY(),
                                                vv.getWidth(), vv.getHeight());
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

                                // Determine release zone (CANCEL always = OUTSIDE; UP uses coord).
                                Zone releaseZone;
                                float ux = 0f, uy = 0f;
                                int vw = 0, vh = 0;
                                if (action == MotionEvent.ACTION_CANCEL) {
                                    releaseZone = Zone.OUTSIDE;
                                } else if (param.thisObject instanceof View) {
                                    View vv = (View) param.thisObject;
                                    ux = ev.getX();
                                    uy = ev.getY();
                                    vw = vv.getWidth();
                                    vh = vv.getHeight();
                                    int tbH = (sCachedToolbarHeight > 0)
                                            ? sCachedToolbarHeight : readToolbarHeight(cl);
                                    releaseZone = computeZone(ux, uy, vw, vh, tbH);
                                } else {
                                    releaseZone = sCurrentZone;
                                }

                                switch (releaseZone) {
                                    case OUTSIDE:
                                        cancelPendingCommit();
                                        cancelVoice(cl);
                                        log("release action=" + actionName(action)
                                                + " coord=(" + ux + "," + uy + ")/" + vw + "x" + vh
                                                + " zone=OUTSIDE -> cancel");
                                        break;
                                    case TOOLBAR:
                                        cancelPendingCommit();
                                        commitAndDispatchToolbarAction(cl);
                                        log("release action=" + actionName(action)
                                                + " coord=(" + ux + "," + uy + ")/" + vw + "x" + vh
                                                + " zone=TOOLBAR -> action dispatch");
                                        break;
                                    default:
                                        commitVoice(cl);
                                        log("release action=" + actionName(action)
                                                + " coord=(" + ux + "," + uy + ")/" + vw + "x" + vh
                                                + " zone=LETTER -> commit");
                                        break;
                                }
                                // Reset zone state and hide overlay.
                                sCurrentZone = Zone.LETTER;
                                updateOverlayForZone(Zone.LETTER, 0, cl);
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
                                return;
                            }
                            // Update poll-fallback timestamp only when listener path unavailable.
                            if (sAsrProcess == null) {
                                sLastAsrCallbackTs = SystemClock.elapsedRealtime();
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
                                return;
                            }
                            // Update poll-fallback timestamp only when listener path unavailable.
                            if (sAsrProcess == null) {
                                sLastAsrCallbackTs = SystemClock.elapsedRealtime();
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
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.bytedance.android.input.speech.AsrContext", cl,
                    "T", int.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            boolean isDone = Boolean.TRUE.equals(param.args[1]);
                            if (isDone) {
                                if (sAsrProcess == null) {
                                    sLastAsrCallbackTs = SystemClock.elapsedRealtime();
                                }
                                log("AsrContext.T phase=" + param.args[0] + " done=true");
                            }
                        }
                    });
            log("hooked AsrContext#T");
        } catch (Throwable t) {
            log("ERR hook AsrContext#T: " + t.getClass().getSimpleName());
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
        try {
            XposedHelpers.findAndHookMethod(IME_SERVICE, cl, "onStartInputView",
                    EditorInfo.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int h = readToolbarHeight(sClassLoader);
                            if (h > 0) {
                                sCachedToolbarHeight = h;
                            }
                        }
                    });
            log("hooked " + IME_SERVICE + "#onStartInputView");
        } catch (Throwable t) {
            log("ERR hook onStartInputView: " + t.getClass().getSimpleName());
        }
    }

    private static void resetVolatileState(String reason) {
        if (sSuppressNextUp || sCancelUntilElapsed != 0L || sPendingCommit != null
                || sMaxDisplacementSq != 0f) {
            log("resetVolatileState reason=" + reason);
        }
        sSuppressNextUp = false;
        sAsrStartConfirmed = false;
        sCancelUntilElapsed = 0L;
        sMaxDisplacementSq = 0f;
        sCurrentZone = Zone.LETTER;
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
        callInputViewR(inputView, false);

        String pkg = currentEditorPackageName(cl);
        if (pkg != null && A11Y_SEND_PACKAGES.contains(pkg)) {
            dispatchViaA11ySend(cl, pkg);
            return;
        }

        int enterOrdinal = resolveEffectiveEnterOrdinal(cl);
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
    private static void dispatchViaA11ySend(final ClassLoader cl, final String pkg) {
        // Register listener BEFORE p0() to avoid missing the all-back callback.
        subscribeAsrAllBackThen(cl, NEWLINE_ASR_MAX_WAIT_MS, () -> broadcastA11ySend(cl, pkg));
        Object mgr = ensureAsrManager(cl);
        if (mgr != null) {
            try {
                XposedHelpers.callMethod(mgr, "p0", false, "");
                log("p0(false,\"\") fired (a11y send path pkg=" + pkg + ")");
            } catch (Throwable e) {
                log("ERR p0() a11y: " + e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Specific-action path: {@code AsrManager.t(ord, now)} — Doubao's official
     * "wait for ASR back, then perform action" flow. Has built-in latency but
     * ensures the editor (e.g., WeChat) receives the polished final text.
     */
    private static void dispatchViaAsrManagerT(ClassLoader cl, int enterOrdinal) {
        Object mgr = ensureAsrManager(cl);
        if (mgr == null) {
            log("skip t(): AsrManager not resolvable");
            return;
        }
        try {
            XposedHelpers.callMethod(mgr, "t",
                    new Class<?>[]{int.class, long.class},
                    enterOrdinal, System.currentTimeMillis());
            log("AsrManager.t(" + enterOrdinal + ", now) fired (specific send)");
        } catch (Throwable e) {
            log("ERR AsrManager.t(): " + e.getClass().getSimpleName()
                    + " -> " + Log.getStackTraceString(e));
        }
    }

    /**
     * Newline-fast path: commit ASR text via {@code p0(false,"")} and dispatch
     * a {@code KEYCODE_ENTER} key event once Doubao's ASR pipeline has
     * actually finished finalizing.
     *
     * <p><b>v1.3.0:</b> the naive "fixed delay between p0 and ENTER" approach
     * raced with Doubao's async ASR polish — the polished text would be
     * committed once by p0 and again by IME-framework auto-finishComposingText
     * when ENTER dispatched (especially on long text where polish takes longer).
     * We now poll {@link #sLastAsrCallbackTs} (updated by Doubao's own
     * {@code onAsrSetPreedit}/{@code onAsrCommitPreeditText} hooks) and only
     * fire ENTER after callbacks have been quiet for
     * {@link #NEWLINE_ASR_SETTLE_MS}. Adaptive: short text fires fast, long
     * text waits longer. See {@link #pollAsrSettleAndEnter}.
     */
    private static void dispatchNewlineFast(final ClassLoader cl) {
        // Register listener BEFORE p0() to avoid missing the all-back callback.
        subscribeAsrAllBackThen(cl, NEWLINE_ASR_MAX_WAIT_MS, () -> sendEnterKey(cl));
        Object mgr = ensureAsrManager(cl);
        if (mgr != null) {
            try {
                XposedHelpers.callMethod(mgr, "p0", false, "");
                log("p0(false,\"\") fired (newline path)");
            } catch (Throwable e) {
                log("ERR p0(): " + e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Two-phase poll for ASR settle.
     * <ol>
     *   <li>Phase 1 — wait until at least one ASR callback fires AFTER
     *       dispatch start ({@code sLastAsrCallbackTs > startTs}). This
     *       confirms Doubao has actually responded to our {@code p0()}
     *       trigger; without it, short text could fire ENTER before ASR
     *       even commits, producing "ENTER then text" out-of-order
     *       insertion.</li>
     *   <li>Phase 2 — wait until callbacks go quiet for {@code settleMs},
     *       meaning ASR has truly finished finalizing.</li>
     * </ol>
     * Re-schedules itself every {@link #NEWLINE_ASR_POLL_MS}. Capped at
     * {@code maxWaitMs} as a timeout fallback so we never hang.
     */
    private static void pollAsrSettleAndEnter(final ClassLoader cl,
                                              final long settleMs,
                                              final long maxWaitMs,
                                              final long startTs) {
        pollAsrSettleThen(cl, settleMs, maxWaitMs, startTs, () -> sendEnterKey(cl));
    }

    private static void pollAsrSettleThen(final ClassLoader cl,
                                          final long settleMs,
                                          final long maxWaitMs,
                                          final long startTs,
                                          final Runnable terminal) {
        sMainHandler.postDelayed(() -> {
            long now = SystemClock.elapsedRealtime();
            long waited = now - startTs;
            boolean asrRespondedAfterDispatch = sLastAsrCallbackTs > startTs;

            if (waited >= maxWaitMs) {
                log("asr-settle TIMEOUT waited=" + waited
                        + "ms responded=" + asrRespondedAfterDispatch
                        + " -> terminal anyway");
                if (terminal != null) {
                    terminal.run();
                }
                return;
            }

            if (!asrRespondedAfterDispatch) {
                // Phase 1: still waiting for first post-dispatch ASR callback
                pollAsrSettleThen(cl, settleMs, maxWaitMs, startTs, terminal);
                return;
            }

            // Phase 2: ASR has responded — measure quiet time since last callback
            long quiet = now - sLastAsrCallbackTs;
            if (quiet >= settleMs) {
                log("asr-settle quiet=" + quiet + "ms waited=" + waited + "ms -> terminal");
                if (terminal != null) {
                    terminal.run();
                }
            } else {
                pollAsrSettleThen(cl, settleMs, maxWaitMs, startTs, terminal);
            }
        }, NEWLINE_ASR_POLL_MS);
    }

    /** Broadcasts a request to our AccessibilityService to click the send button. */
    private static void broadcastA11ySend(ClassLoader cl, String pkg) {
        try {
            Class<?> jniCls = XposedHelpers.findClass(KEYBOARD_JNI, cl);
            Object ime = XposedHelpers.getStaticObjectField(jniCls, "mImeService");
            if (!(ime instanceof android.content.Context)) {
                log("skip broadcastA11ySend: mImeService not Context");
                return;
            }
            android.content.Context ctx = (android.content.Context) ime;
            android.content.Intent intent = new android.content.Intent(
                    DoubaoVoiceSendA11yService.ACTION_A11Y_SEND)
                    .setPackage("com.jin.doubaolongpressvoice")
                    .putExtra(DoubaoVoiceSendA11yService.EXTRA_TARGET_PKG, pkg);
            ctx.sendBroadcast(intent);
            log("broadcast a11y send pkg=" + pkg);
        } catch (Throwable t) {
            log("ERR broadcastA11ySend: " + Log.getStackTraceString(t));
        }
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
     * anywhere not on a slide button". Calls {@code InputView.R(false)} +
     * {@code AsrManager.q0()} (graceful stop with 150ms delay to {@code p0}).
     */
    private static void commitVoice(final ClassLoader cl) {
        cancelPendingCommit();
        Object inputView = getInputView(cl);
        callInputViewR(inputView, false);

        Object mgr = ensureAsrManager(cl);
        if (mgr == null) {
            log("skip q0(): AsrManager not resolvable");
            return;
        }
        try {
            XposedHelpers.callMethod(mgr, "q0");
            log("AsrManager.q0() fired (commit, graceful)");
        } catch (Throwable e) {
            log("ERR q0(): " + e.getClass().getSimpleName()
                    + " -> fallback p0(false,\"\")");
            try {
                XposedHelpers.callMethod(mgr, "p0", false, "");
            } catch (Throwable ignore) {
            }
        }
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
        // Diagnostic probe: log whether L.a all-back fires on cancel (does NOT change behavior).
        subscribeAsrAllBackThen(cl, 2000L, null);
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

    /** CONFIRM-type haptic when entering a TOOLBAR / OUTSIDE selection zone. */
    private static void performZoneSelectionFeedback() {
        if (!ensureFeedbackHandles() || sVibTypeConfirm == null) {
            return;
        }
        try {
            XposedHelpers.callMethod(sUserInteractiveMgr, "g",
                    sKeySoundKeyboard, sKeyVibrateStandard, sVibTypeConfirm, false);
        } catch (Throwable ignore) {
        }
    }

    /**
     * Defense against silent-fail ASR start (mic permission denied, model not
     * loaded, etc.): if no preedit arrives within 300 ms, drop the
     * UP-suppression so the next keypress is not eaten.
     */
    private static void scheduleAsrStartVerification(final ClassLoader cl) {
        sMainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!sSuppressNextUp) {
                    return;
                }
                // sAsrStartConfirmed (text output) lags 500-1000ms behind ASR start.
                // Use E() (process alive flag) as the authoritative rollback signal instead.
                Object mgr = ensureAsrManager(cl);
                if (mgr != null) {
                    try {
                        Object running = XposedHelpers.callMethod(mgr, "E");
                        if (!Boolean.FALSE.equals(running)) {
                            return; // ASR is running — no rollback
                        }
                    } catch (Throwable t) {
                        log("ERR ASR verify E(): " + t.getClass().getSimpleName());
                        return; // unknown state — don't rollback
                    }
                } else {
                    return; // manager not ready — don't rollback
                }
                log("ASR did NOT start (E()=false at 300ms) -> rollback suppress");
                sSuppressNextUp = false;
                sMaxDisplacementSq = 0f;
            }
        }, 300L);
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

    private static Object ensureAsrProcess(ClassLoader cl) {
        if (sAsrProcess != null) {
            return sAsrProcess;
        }
        if (sAsrProcessResolveAttempted) {
            return null;
        }
        sAsrProcessResolveAttempted = true;
        try {
            Class<?> cls = XposedHelpers.findClass(ASR_MANAGER, cl);
            sAsrProcess = XposedHelpers.getStaticObjectField(cls, "b");
            log("AsrProcess resolved: " + (sAsrProcess != null));
        } catch (Throwable t) {
            log("ERR ensureAsrProcess: " + t.getClass().getSimpleName());
        }
        return sAsrProcess;
    }

    private static Class<?> ensureListenerCls(ClassLoader cl) {
        if (sListenerCls != null) {
            return sListenerCls;
        }
        if (sListenerClsResolveAttempted) {
            return null;
        }
        sListenerClsResolveAttempted = true;
        // Try hardcoded name first.
        try {
            sListenerCls = XposedHelpers.findClass(ASR_ALL_BACK_LISTENER_CLS, cl);
            if (sListenerCls != null) {
                log("L$a resolved by name");
                return sListenerCls;
            }
        } catch (Throwable ignore) {}
        // Fallback: discover the interface by inspecting AsrProcess.w() parameter type.
        // Avoids dependence on obfuscated class name across Doubao versions.
        try {
            Object proc = ensureAsrProcess(cl);
            if (proc != null) {
                for (java.lang.reflect.Method m : proc.getClass().getDeclaredMethods()) {
                    if ("w".equals(m.getName()) && m.getParameterCount() == 1) {
                        Class<?> param = m.getParameterTypes()[0];
                        if (param.isInterface()) {
                            sListenerCls = param;
                            log("L$a discovered via AsrProcess.w() param: " + param.getName());
                            return sListenerCls;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log("ERR ensureListenerCls discover: " + t.getClass().getSimpleName());
        }
        log("ERR ensureListenerCls: not found");
        return null;
    }

    private static void safeW(Object proc, Class<?> lcls, Object listener) {
        try {
            XposedHelpers.callMethod(proc, "w",
                    new Class<?>[]{lcls}, new Object[]{listener});
        } catch (Throwable ignore) {
        }
    }

    /**
     * Replaces {@link #pollAsrSettleThen}: registers a one-shot {@code L$a}
     * all-back listener on {@code AsrManager.b} (AsrProcess). When
     * {@code s.g()==true} fires the terminal action runs on the main thread.
     * Falls back to {@link #pollAsrSettleThen} if listener or AsrProcess
     * cannot be resolved, or if {@code z.w()} throws.
     *
     * <p>Register BEFORE calling {@code p0()} so the all-back event is not missed.
     */
    private static void subscribeAsrAllBackThen(
            final ClassLoader cl, final long maxWaitMs, final Runnable terminal) {
        Object proc = ensureAsrProcess(cl);
        Class<?> lcls = ensureListenerCls(cl);
        if (proc == null || lcls == null) {
            log("subscribeAsrAllBack: fallback poll (proc=" + (proc != null)
                    + " lcls=" + (lcls != null) + ")");
            pollAsrSettleThen(cl, NEWLINE_ASR_SETTLE_MS, maxWaitMs,
                    SystemClock.elapsedRealtime(), terminal);
            return;
        }

        final boolean[] done = {false};
        final Runnable[] timeoutRef = {null};
        Object listener;
        try {
            listener = java.lang.reflect.Proxy.newProxyInstance(
                    cl,
                    new Class<?>[]{lcls},
                    (proxy, method, args) -> {
                        if (!"a".equals(method.getName())
                                || args == null || args.length != 1) {
                            return null;
                        }
                        try {
                            boolean allBack =
                                    (Boolean) XposedHelpers.callMethod(args[0], "g");
                            if (!allBack || done[0]) {
                                return null;
                            }
                            done[0] = true;
                            if (timeoutRef[0] != null) {
                                sMainHandler.removeCallbacks(timeoutRef[0]);
                            }
                            safeW(proc, lcls, null);
                            if (terminal != null) {
                                sMainHandler.post(terminal);
                            }
                            log("asr-allback: s.g()=true -> terminal");
                        } catch (Throwable t) {
                            log("ERR allBack listener: " + t.getClass().getSimpleName());
                        }
                        return null;
                    });
        } catch (Throwable t) {
            log("ERR Proxy L$a: " + t.getClass().getSimpleName() + " -> poll fallback");
            pollAsrSettleThen(cl, NEWLINE_ASR_SETTLE_MS, maxWaitMs,
                    SystemClock.elapsedRealtime(), terminal);
            return;
        }

        Runnable timeout = () -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            safeW(proc, lcls, null);
            log("asr-allback: TIMEOUT " + maxWaitMs + "ms -> terminal");
            if (terminal != null) {
                terminal.run();
            }
        };
        timeoutRef[0] = timeout;
        try {
            XposedHelpers.callMethod(proc, "w",
                    new Class<?>[]{lcls}, new Object[]{listener});
            sMainHandler.postDelayed(timeout, maxWaitMs);
            log("asr-allback: listener registered timeout=" + maxWaitMs + "ms");
        } catch (Throwable t) {
            log("ERR asrProcess.w(): " + t.getClass().getSimpleName() + " -> poll fallback");
            sMainHandler.removeCallbacks(timeout);
            pollAsrSettleThen(cl, NEWLINE_ASR_SETTLE_MS, maxWaitMs,
                    SystemClock.elapsedRealtime(), terminal);
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

    private static boolean isLetterZone(int x, int y, int w, int h, int kbdType,
                                        int toolbarHeight) {
        if (w <= 0 || h <= 0) {
            return false;
        }
        // Primary top exclusion: Doubao's native getToolbarHeight() in pixels.
        // Translation mode raises the toolbar; this value grows accordingly.
        if (toolbarHeight > 0 && y < toolbarHeight) {
            return false;
        }
        // Fallback ratio when native call failed (toolbarHeight <= 0).
        if (toolbarHeight <= 0) {
            boolean tallToolbar = (h > w * TALL_KBD_H_OVER_W);
            float topRatio = tallToolbar ? LETTER_TOP_TALL : LETTER_TOP_NORMAL;
            if (y < h * topRatio) {
                return false;
            }
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

    // ===== Zone tracking + slide-to-action =====

    private static Zone computeZone(float x, float y, int w, int h, int toolbarHeight) {
        if (x < 0f || y < 0f || x >= w || y >= h) {
            return Zone.OUTSIDE;
        }
        if (toolbarHeight > 0 && y < toolbarHeight) {
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
        Zone next = computeZone(x, y, w, h, tbH);
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
        ensureOverlay(cl, tbH);
        int enterOrdinal = resolveEffectiveEnterOrdinal(cl);
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
     * Reads {@code ImeService.x} — the {@code InputView} singleton (a
     * FrameLayout that hosts toolbar + candidates + keyboard).
     */
    private static Object getInputView(ClassLoader cl) {
        try {
            Class<?> imeServiceCls = XposedHelpers.findClass(IME_SERVICE, cl);
            return XposedHelpers.getStaticObjectField(imeServiceCls, "x");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Calls {@code InputView.R(boolean)} — tidies up ASR UI before sending. */
    private static void callInputViewR(Object inputView, boolean z) {
        if (inputView == null) {
            return;
        }
        try {
            XposedHelpers.callMethod(inputView, "R", z);
        } catch (Throwable t) {
            log("ERR InputView.R(): " + t.getClass().getSimpleName());
        }
    }

    /**
     * Resolves the canonical {@code EnterActionType} ordinal Doubao would use.
     * Uses {@code EditorViewInfo.e().d()} as the authoritative source, matching
     * what AsrLongPressView reads when picking its right-button text.
     */
    private static int resolveEnterOrdinal(ClassLoader cl) {
        // EditorViewInfo.e().d() is the authoritative source (used by AsrLongPressView).
        int fromEditorViewInfo = readEnterTypeFromEditorViewInfo(cl);
        if (fromEditorViewInfo >= 2 && fromEditorViewInfo <= 8) {
            return fromEditorViewInfo;
        }
        // Fallback: mCurrentEnterType (may be stale in some editors).
        int fromEnterType = readEnterTypeOrdinal(cl);
        if (fromEnterType >= 2 && fromEnterType <= 8) {
            return fromEnterType;
        }
        // Return best non-negative value for diagnostic clarity.
        return Math.max(0, Math.max(fromEditorViewInfo, fromEnterType));
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
                || A11Y_SEND_PACKAGES.contains(pkg))) {
            log("send-label override: pkg=" + pkg + " original ord=" + ord
                    + " -> IME_ACTION_SEND");
            return IME_ACTION_SEND_ORDINAL;
        }
        return ord;
    }

    private static int readEnterTypeOrdinal(ClassLoader cl) {
        try {
            Class<?> jni = XposedHelpers.findClass(KEYBOARD_JNI, cl);
            Object v = XposedHelpers.getStaticObjectField(jni, "mCurrentEnterType");
            if (v instanceof Enum) {
                return ((Enum<?>) v).ordinal();
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int readEnterTypeFromEditorViewInfo(ClassLoader cl) {
        try {
            Class<?> oClass = XposedHelpers.findClass(EDITOR_VIEW_INFO, cl);
            Object instance = XposedHelpers.callStaticMethod(oClass, "e");
            Object v = XposedHelpers.callMethod(instance, "d");
            return (v instanceof Integer) ? (Integer) v : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int readCurrentEditboxAction(ClassLoader cl) {
        try {
            Class<?> jni = XposedHelpers.findClass(KEYBOARD_JNI, cl);
            Object v = XposedHelpers.getStaticObjectField(jni, "mCurrentEditboxAction");
            return (v instanceof Integer) ? (Integer) v : 0;
        } catch (Throwable t) {
            return 0;
        }
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
