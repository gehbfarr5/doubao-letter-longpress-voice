package com.jin.doubaolongpressvoice;

/** Maps host diagnostics to fixed event names and numeric/boolean fields only. */
final class NativeAsrTrace {
    static String summarize(String message) {
        if (message == null) return null;
        String[] markers = {"[SmartOrganizeVoice]", "[SmartOrganizeV2]", "DoAsrSend",
                "[ASR-Flow][stopAsr][Android]", "processFinishAsrResult",
                "allAsrBack, mAsrContentList isEmpty", "stopAsrDelay"};
        boolean allowed = false;
        for (String marker : markers) if (message.startsWith(marker)) allowed = true;
        if (!allowed) return null;
        // These are fixed host event labels, never arbitrary trailing text.
        String[] events = {"use organized result", "organized text unchanged", "skip organized replace",
                "skip offline result", "skip space long press", "skip text organize blacklist",
                "send wait timeout", "keep second pass", "show fallback idle tip",
                "cancel request job", "step=", "action#invoke", "waitAllAsrBackEnable",
                "currentAllAsrBack true", "currentAllAsrBack false", "sendFinish costTime",
                "isShowingAsrLongPressView", "noWaitResult", "StopAsr begin", "StopAsr end",
                "StopAsr wait", "don't commit", "skip post process after smart organize",
                "mAsrContentList isEmpty", "stopAsrDelay"};
        String event = "host-stage";
        for (String value : events) if (message.contains(value)) { event = value; break; }
        StringBuilder out = new StringBuilder(event);
        java.util.regex.Matcher fields = java.util.regex.Pattern.compile(
                "(currentAllAsrBack|mHaveVoiceText|smartOrganizeSendWaitTimeout|waitAllAsrBackEnable|noWaitResult|processingShown|sendWait|loosenStop|hasSendAction|originalLength|organizedLength|length|timeoutMs|maxTime|cost-time|isFinish|don't commit)\\s*[:=]?\\s*(true|false|[0-9]+)").matcher(message);
        while (fields.find()) out.append(' ').append(fields.group(1)).append('=').append(fields.group(2));
        return out.toString();
    }
}
