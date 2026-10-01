package com.jin.doubaolongpressvoice;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Explicit app routes; Google Search is restricted to its Gemini editor. */
final class SendTargets {
    static final Set<String> A11Y = new HashSet<>(Arrays.asList(
            "com.openai.chatgpt", "com.anthropic.claude", "com.facebook.aura",
            "com.google.android.googlequicksearchbox", "com.google.android.apps.bard"));
    static boolean allowedEditor(String pkg, String viewId) {
        if (!A11Y.contains(pkg)) return false;
        // This installed package is Muse. Do not treat all com.facebook.aura editors as chat.
        if (pkg.equals("com.facebook.aura")) return "hatch-message-input".equals(viewId);
        if (pkg.equals("com.google.android.googlequicksearchbox"))
            return viewId != null && viewId.startsWith(pkg + ":id/assistant_robin_input_");
        return true;
    }
    static boolean sendLabel(CharSequence label) {
        if (label == null) return false;
        String s = label.toString().trim().toLowerCase(Locale.ROOT);
        return Arrays.asList("send", "send message", "send prompt", "submit", "submit prompt",
                "发送", "发送消息", "发送提示", "提交", "提交提示").contains(s);
    }
    static boolean sendControl(String pkg, String viewId, CharSequence description,
                               CharSequence className, CharSequence text) {
        if ("com.facebook.aura".equals(pkg)) {
            return "hatch-send-button".equals(viewId)
                    && ("发消息".contentEquals(description == null ? "" : description)
                    || sendLabel(description));
        }
        return sendLabel(description)
                || ("android.widget.Button".contentEquals(className == null ? "" : className)
                && sendLabel(text));
    }
}
