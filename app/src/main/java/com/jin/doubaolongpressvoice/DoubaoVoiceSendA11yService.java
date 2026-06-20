package com.jin.doubaolongpressvoice;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Accessibility bridge for apps whose send action is only exposed as a UI
 * button click rather than an IME action.
 */
public class DoubaoVoiceSendA11yService extends AccessibilityService {

    public static final String ACTION_A11Y_SEND =
            "com.jin.doubaolongpressvoice.ACTION_A11Y_SEND";
    public static final String EXTRA_TARGET_PKG = "target_pkg";

    private static final String TAG = "DoubaoVoiceSend";
    private static final int MAX_DUMP_DEPTH = 25;
    private static final int MAX_DUMP_NODES = 200;

    /**
     * Send-button keywords matched against contentDescription / text
     * (case-insensitive substring). Includes Chinese because Claude/ChatGPT
     * localize their button labels, and Compose/WebView nodes commonly carry
     * a localized contentDescription rather than English "send".
     */
    private static final String[] SEND_KEYWORDS = {
            "send", "发送", "提交", "发送消息", "send message", "send prompt"
    };
    /** Keywords that disqualify a node from being the send button. */
    private static final String[] EXCLUDE_KEYWORDS = {
            "图片", "文件", "image", "file", "attachment", "photo", "album", "表情", "emoji"
    };
    /**
     * Known stable resource-id for the send button, keyed by package name.
     * null = no stable id available for this package (fall back to heuristic).
     *
     * NOTE: every entry is currently {@code null} — the per-package viewId
     * fast path in {@link #performSend} is a reserved placeholder and never
     * fires yet. All sends go through the keyword heuristic in
     * {@link #findSendNode}. Populate these from real-device logcat dumps
     * (run with {@code adb logcat -s DoubaoVoiceSend} and read the node dump)
     * to enable exact-match clicking; update when apps change.
     */
    private static final java.util.Map<String, String> PACKAGE_SEND_VIEW_ID;
    static {
        PACKAGE_SEND_VIEW_ID = new java.util.HashMap<>();
        PACKAGE_SEND_VIEW_ID.put("com.anthropic.claude", null);
        PACKAGE_SEND_VIEW_ID.put("com.openai.chatgpt", null);
        PACKAGE_SEND_VIEW_ID.put("com.google.android.apps.bard", null);
        PACKAGE_SEND_VIEW_ID.put("ai.x.grok", null);
        PACKAGE_SEND_VIEW_ID.put("com.moonshot.kimichat", null);
    }

    private BroadcastReceiver mReceiver;
    private boolean mReceiverRegistered;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        registerSendReceiver();
        startKeepAliveForeground();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopForeground(true);
        unregisterSendReceiver();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        try {
            stopForeground(true);
        } catch (Throwable t) {
            // ignore
        }
        unregisterSendReceiver();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    private void registerSendReceiver() {
        try {
            if (mReceiverRegistered) {
                return;
            }
            if (mReceiver == null) {
                mReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        try {
                            if (intent == null || !ACTION_A11Y_SEND.equals(intent.getAction())) {
                                return;
                            }
                            String targetPkg = intent.getStringExtra(EXTRA_TARGET_PKG);
                            performSend(targetPkg);
                        } catch (Throwable t) {
                            Log.w(TAG, "ERR receiver onReceive: " + Log.getStackTraceString(t));
                        }
                    }
                };
            }
            IntentFilter filter = new IntentFilter(ACTION_A11Y_SEND);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(mReceiver, filter);
            }
            mReceiverRegistered = true;
            Log.i(TAG, "a11y send receiver registered");
        } catch (Throwable t) {
            Log.w(TAG, "ERR register receiver: " + Log.getStackTraceString(t));
        }
    }

    private void startKeepAliveForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        "doubao_voice_send",
                        "豆包语音发送",
                        NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                NotificationManager nm =
                        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.createNotificationChannel(ch);
                }
            }
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, "doubao_voice_send");
            } else {
                builder = new Notification.Builder(this);
            }
            Notification n = builder
                    .setContentTitle("豆包语音发送助手运行中")
                    .setSmallIcon(android.R.drawable.ic_menu_send)
                    .setOngoing(true)
                    .build();
            startForeground(1, n);
            Log.i(TAG, "startForeground ok");
        } catch (Throwable t) {
            Log.w(TAG, "ERR startForeground: " + t.getClass().getSimpleName());
        }
    }

    private void unregisterSendReceiver() {
        try {
            if (!mReceiverRegistered || mReceiver == null) {
                return;
            }
            unregisterReceiver(mReceiver);
            mReceiverRegistered = false;
            Log.i(TAG, "a11y send receiver unregistered");
        } catch (Throwable t) {
            Log.w(TAG, "ERR unregister receiver: " + t.getClass().getSimpleName());
        }
    }

    private void performSend(String targetPkg) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                Log.w(TAG, "performSend: root null targetPkg=" + targetPkg);
                return;
            }
            try {
                CharSequence rootPkg = root.getPackageName();
                if (targetPkg != null && rootPkg != null
                        && !targetPkg.contentEquals(rootPkg)) {
                    Log.w(TAG, "performSend: active pkg=" + rootPkg
                            + " targetPkg=" + targetPkg + ", still trying");
                }
            } catch (Throwable t) {
                Log.w(TAG, "ERR read root package: " + t.getClass().getSimpleName());
            }

            // Fast path: per-package stable viewId (if known).
            if (targetPkg != null && PACKAGE_SEND_VIEW_ID.containsKey(targetPkg)) {
                String viewId = PACKAGE_SEND_VIEW_ID.get(targetPkg);
                if (viewId != null) {
                    java.util.List<AccessibilityNodeInfo> byId =
                            root.findAccessibilityNodeInfosByViewId(viewId);
                    if (byId != null && !byId.isEmpty()) {
                        AccessibilityNodeInfo n = byId.get(0);
                        AccessibilityNodeInfo clickable = nearestClickable(n);
                        if (clickable == null) {
                            clickable = n;
                        }
                        boolean ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        Log.i(TAG, "performSend(viewId): ACTION_CLICK ok=" + ok
                                + " viewId=" + viewId);
                        return;
                    }
                }
            }

            AccessibilityNodeInfo node = findSendNode(root, targetPkg);
            if (node != null) {
                AccessibilityNodeInfo clickable = nearestClickable(node);
                if (clickable == null) {
                    clickable = node;
                }
                boolean ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.i(TAG, "performSend: ACTION_CLICK ok=" + ok
                        + " viewId=" + safeViewId(clickable)
                        + " desc=" + safeText(clickable.getContentDescription()));
                return;
            }

            Log.w(TAG, "performSend: no send node targetPkg=" + targetPkg
                    + ", dumping clickables");
            dumpClickables(root);
        } catch (Throwable t) {
            Log.w(TAG, "ERR performSend: " + Log.getStackTraceString(t));
        }
    }

    private AccessibilityNodeInfo findSendNode(AccessibilityNodeInfo root, String targetPkg) {
        try {
            if (root == null) {
                return null;
            }
            java.util.List<AccessibilityNodeInfo> candidates = new java.util.ArrayList<>();
            collectSendCandidates(root, candidates);
            if (candidates.isEmpty()) {
                return null;
            }
            final int screenWidth = getResources().getDisplayMetrics().widthPixels;
            java.util.Collections.sort(candidates, new java.util.Comparator<AccessibilityNodeInfo>() {
                @Override
                public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
                    int scoreA = candidateScore(a, screenWidth);
                    int scoreB = candidateScore(b, screenWidth);
                    return Integer.compare(scoreB, scoreA);
                }
            });
            return candidates.get(0);
        } catch (Throwable t) {
            Log.w(TAG, "ERR findSendNode: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** DFS: collect ALL nodes that match send keywords, are not excluded, and are actionable. */
    private void collectSendCandidates(AccessibilityNodeInfo node,
            java.util.List<AccessibilityNodeInfo> out) {
        try {
            if (node == null) {
                return;
            }
            if (matchesSendCandidate(node)) {
                AccessibilityNodeInfo clickable = nearestClickable(node);
                out.add(clickable != null ? clickable : node);
            }
            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                try {
                    AccessibilityNodeInfo child = node.getChild(i);
                    collectSendCandidates(child, out);
                } catch (Throwable t) {
                    // skip
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ERR collectSendCandidates: " + t.getClass().getSimpleName());
        }
    }

    /**
     * A node is a send candidate if:
     * 1. Its text or description contains a send keyword
     * 2. It does NOT contain an exclude keyword
     * 3. It is actionable (or has an actionable ancestor)
     */
    private boolean matchesSendCandidate(AccessibilityNodeInfo node) {
        try {
            if (!containsSend(node.getContentDescription()) && !containsSend(node.getText())) {
                return false;
            }
            if (isExcluded(node)) {
                return false;
            }
            return supportsClick(node) || nearestClickable(node) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private int candidateScore(AccessibilityNodeInfo node, int screenWidth) {
        int score = 0;
        try {
            if (isExactSendMatch(node)) {
                score += 100;
            }
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (screenWidth > 0 && bounds.centerX() > screenWidth * 0.6f) {
                score += 10;
            }
            score += Math.min(9, (bounds.width() * bounds.height()) / 10000);
        } catch (Throwable t) {
            // ignore
        }
        return score;
    }

    /** Walks up to the nearest ancestor that can receive an ACTION_CLICK. */
    private AccessibilityNodeInfo nearestClickable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        while (cur != null) {
            try {
                if (supportsClick(cur)) {
                    return cur;
                }
                cur = cur.getParent();
            } catch (Throwable t) {
                Log.w(TAG, "ERR nearestClickable: " + t.getClass().getSimpleName());
                return null;
            }
        }
        return null;
    }

    /**
     * True if the node accepts a click — either the classic {@code isClickable()}
     * flag (View-based / WebView) or an {@code ACTION_CLICK} entry in its action
     * list (Jetpack Compose exposes semantic actions this way).
     */
    private boolean supportsClick(AccessibilityNodeInfo node) {
        try {
            if (node == null) {
                return false;
            }
            if (node.isClickable()) {
                return true;
            }
            java.util.List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
            if (actions != null) {
                for (AccessibilityNodeInfo.AccessibilityAction a : actions) {
                    if (a != null && a.getId() == AccessibilityNodeInfo.ACTION_CLICK) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ERR supportsClick: " + t.getClass().getSimpleName());
        }
        return false;
    }

    private boolean containsSend(CharSequence value) {
        if (value == null) {
            return false;
        }
        String lower = value.toString().toLowerCase(java.util.Locale.US);
        for (String kw : SEND_KEYWORDS) {
            if (lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsExclude(CharSequence value) {
        if (value == null) {
            return false;
        }
        String lower = value.toString().toLowerCase(java.util.Locale.US);
        for (String kw : EXCLUDE_KEYWORDS) {
            if (lower.contains(kw.toLowerCase(java.util.Locale.US))) {
                return true;
            }
        }
        return false;
    }

    private boolean isExcluded(AccessibilityNodeInfo node) {
        try {
            return containsExclude(node.getContentDescription())
                    || containsExclude(node.getText());
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isExactSendMatch(AccessibilityNodeInfo node) {
        try {
            for (CharSequence value : new CharSequence[]{
                    node.getContentDescription(), node.getText()}) {
                if (value == null) {
                    continue;
                }
                String s = value.toString().trim().toLowerCase(java.util.Locale.US);
                for (String kw : SEND_KEYWORDS) {
                    if (s.equals(kw.toLowerCase(java.util.Locale.US))) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return false;
    }

    private void dumpClickables(AccessibilityNodeInfo root) {
        try {
            int[] count = new int[]{0};
            dumpClickables(root, 0, count);
        } catch (Throwable t) {
            Log.w(TAG, "ERR dumpClickables: " + Log.getStackTraceString(t));
        }
    }

    private void dumpClickables(AccessibilityNodeInfo node, int depth, int[] count) {
        try {
            if (node == null || depth > MAX_DUMP_DEPTH || count[0] >= MAX_DUMP_NODES) {
                return;
            }
            // Dump anything actionable OR carrying a label — a Compose send button
            // may not be isClickable() yet still be the node we want, so logging
            // only isClickable() nodes (the old behavior) printed nothing.
            boolean actionable = supportsClick(node);
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            boolean hasLabel = (text != null && text.length() > 0)
                    || (desc != null && desc.length() > 0);
            if (actionable || hasLabel) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                Log.i(TAG, "node[" + count[0] + "] depth=" + depth
                        + " clickable=" + node.isClickable()
                        + " actionClick=" + actionable
                        + " | viewId=" + safeViewId(node)
                        + " | text=" + safeText(text)
                        + " | desc=" + safeText(desc)
                        + " | class=" + safeText(node.getClassName())
                        + " | bounds=" + bounds);
                count[0]++;
            }
            int childCount = node.getChildCount();
            for (int i = 0; i < childCount && count[0] < MAX_DUMP_NODES; i++) {
                AccessibilityNodeInfo child = null;
                try {
                    child = node.getChild(i);
                    dumpClickables(child, depth + 1, count);
                } catch (Throwable t) {
                    Log.w(TAG, "ERR dump child: " + t.getClass().getSimpleName());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ERR dumpClickables node: " + t.getClass().getSimpleName());
        }
    }

    private String safeViewId(AccessibilityNodeInfo node) {
        try {
            return String.valueOf(node.getViewIdResourceName());
        } catch (Throwable t) {
            return "";
        }
    }

    private String safeText(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
