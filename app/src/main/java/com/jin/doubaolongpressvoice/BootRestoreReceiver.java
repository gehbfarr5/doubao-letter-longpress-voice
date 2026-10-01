package com.jin.doubaolongpressvoice;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

public class BootRestoreReceiver extends BroadcastReceiver {
    public static final String ACTION_ENSURE_A11Y =
            "com.jin.doubaolongpressvoice.ACTION_ENSURE_A11Y";
    public static final String EXTRA_REASON = "reason";
    static final String COMP_SHORT =
            "com.jin.doubaolongpressvoice/.DoubaoVoiceSendA11yService";
    static final String COMP_FULL =
            "com.jin.doubaolongpressvoice/com.jin.doubaolongpressvoice.DoubaoVoiceSendA11yService";

    private static final String TAG = "BootRestoreReceiver";
    private static final long TOGGLE_COOLDOWN_MS = 2500L;
    private static volatile long sLastRepairElapsedMs;

    static boolean containsOurService(String raw) {
        if (raw == null || raw.isEmpty() || "null".equals(raw)) {
            return false;
        }
        for (String part : raw.split(":")) {
            String trimmed = part.trim();
            if (COMP_FULL.equals(trimmed) || COMP_SHORT.equals(trimmed)) {
                return true;
            }
        }
        return false;
    }

    static String withoutOurService(String raw) {
        if (raw == null || raw.isEmpty() || "null".equals(raw)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : raw.split(":")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty() || "null".equals(trimmed)
                    || COMP_FULL.equals(trimmed) || COMP_SHORT.equals(trimmed)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(trimmed);
        }
        return sb.toString();
    }

    static String withOurService(String raw) {
        String base = withoutOurService(raw);
        return base.isEmpty() ? COMP_FULL : (base + ":" + COMP_FULL);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (ACTION_ENSURE_A11Y.equals(action)) {
            String reason = intent.getStringExtra(EXTRA_REASON);
            ensureA11yService(context, "ensure:" + (reason != null ? reason : "unknown"));
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            ensureA11yService(context, action);
        }
    }

    static void ensureA11yService(Context context, String trigger) {
        try {
            ContentResolver cr = context.getContentResolver();
            String cur = Settings.Secure.getString(cr, "enabled_accessibility_services");
            int enabled = Settings.Secure.getInt(cr, "accessibility_enabled", 0);
            boolean inSettings = containsOurService(cur) && enabled == 1;
            boolean bound = DoubaoVoiceSendA11yService.isBound();
            if (inSettings && bound) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (inSettings && sLastRepairElapsedMs > 0
                    && now - sLastRepairElapsedMs < TOGGLE_COOLDOWN_MS) {
                return;
            }
            sLastRepairElapsedMs = now;
            String stripped = withoutOurService(cur);
            String target = withOurService(cur);
            try {
                if (inSettings && !bound) {
                    Settings.Secure.putString(cr, "enabled_accessibility_services", stripped);
                }
                Settings.Secure.putString(cr, "enabled_accessibility_services", target);
                Settings.Secure.putInt(cr, "accessibility_enabled", 1);
                Log.i(TAG, "repaired a11y via WRITE_SECURE_SETTINGS mode="
                        + (inSettings ? "toggle_unbound" : "enable") + " trigger=" + trigger);
                return;
            } catch (SecurityException se) {
                Log.w(TAG, "WRITE_SECURE_SETTINGS unavailable, falling back to su: "
                        + se.getMessage());
            }
            runSuFallbackAsync(inSettings && !bound, stripped, target, trigger);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to ensure accessibility service trigger=" + trigger, t);
        }
    }

    private static void runSuFallbackAsync(final boolean toggleFirst, final String stripped,
                                           final String target, final String trigger) {
        new Thread(() -> {
            try {
                StringBuilder cmd = new StringBuilder();
                if (toggleFirst) {
                    cmd.append("settings put secure enabled_accessibility_services \"")
                            .append(stripped).append("\"; ");
                }
                cmd.append("settings put secure enabled_accessibility_services \"")
                        .append(target).append("\"; ")
                        .append("settings put secure accessibility_enabled 1");
                Process process = Runtime.getRuntime().exec(
                        new String[]{"su", "-c", cmd.toString()});
                int exitCode = process.waitFor();
                Log.i(TAG, "su fallback restore finished exitCode=" + exitCode
                        + " trigger=" + trigger);
            } catch (Exception e) {
                Log.w(TAG, "Failed su fallback restore trigger=" + trigger, e);
            }
        }, "a11y-su-restore").start();
    }
}
