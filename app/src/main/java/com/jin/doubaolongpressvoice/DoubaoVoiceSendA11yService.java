package com.jin.doubaolongpressvoice;

import android.accessibilityservice.AccessibilityService;
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

    private BroadcastReceiver mReceiver;
    private boolean mReceiverRegistered;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        registerSendReceiver();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        unregisterSendReceiver();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
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

    private AccessibilityNodeInfo findSendNode(AccessibilityNodeInfo node, String targetPkg) {
        try {
            if (node == null) {
                return null;
            }
            if (matchesSend(node)) {
                AccessibilityNodeInfo clickable = nearestClickable(node);
                return clickable != null ? clickable : node;
            }
            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo child = null;
                try {
                    child = node.getChild(i);
                    AccessibilityNodeInfo found = findSendNode(child, targetPkg);
                    if (found != null) {
                        return found;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "ERR find child: " + t.getClass().getSimpleName());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ERR findSendNode: " + t.getClass().getSimpleName());
        }
        return null;
    }

    private boolean matchesSend(AccessibilityNodeInfo node) {
        try {
            if (!containsSend(node.getContentDescription()) && !containsSend(node.getText())) {
                return false;
            }
            // Compose/WebView send buttons frequently report isClickable()==false
            // while still exposing ACTION_CLICK as a semantic action. Accept the
            // node if it (or an ancestor) is actionable in either sense.
            return supportsClick(node) || nearestClickable(node) != null;
        } catch (Throwable t) {
            Log.w(TAG, "ERR matchesSend: " + t.getClass().getSimpleName());
            return false;
        }
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
