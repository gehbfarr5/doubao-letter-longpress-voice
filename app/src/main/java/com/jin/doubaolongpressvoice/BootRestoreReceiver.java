package com.jin.doubaolongpressvoice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootRestoreReceiver extends BroadcastReceiver {
    private static final String TAG = "BootRestoreReceiver";
    private static final String COMPONENT =
            "com.jin.doubaolongpressvoice/.DoubaoVoiceSendA11yService";
    private static final String SCRIPT =
            "cur=$(settings get secure enabled_accessibility_services); "
                    + "comp=\\\"" + COMPONENT + "\\\"; "
                    + "if echo \\\"$cur\\\" | grep -qF \\\"$comp\\\" 2>/dev/null; then exit 0; fi; "
                    + "if [ -z \\\"$cur\\\" ] || [ \\\"$cur\\\" = \\\"null\\\" ]; then "
                    + "settings put secure enabled_accessibility_services \\\"$comp\\\"; "
                    + "else "
                    + "settings put secure enabled_accessibility_services "
                    + "\\\"${cur}:$comp\\\"; "
                    + "fi; "
                    + "settings put secure accessibility_enabled 1";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        try {
            Process process = Runtime.getRuntime().exec(new String[] {"su", "-c", SCRIPT});
            int exitCode = process.waitFor();
            Log.i(TAG, "BOOT_COMPLETED restore finished with exitCode=" + exitCode);
        } catch (Exception e) {
            Log.w(TAG, "Failed to restore accessibility service on boot", e);
        }
    }
}
