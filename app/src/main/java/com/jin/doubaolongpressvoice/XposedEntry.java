package com.jin.doubaolongpressvoice;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed/LSPosed entry point.
 *
 * <p>Only runs inside the Doubao IME process; everything else is a no-op.
 */
public final class XposedEntry implements IXposedHookLoadPackage {

    private static final String TAG = "DoubaoLongPress";
    private static final String TARGET_PACKAGE = "com.bytedance.android.doubaoime";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        Log.i(TAG, "loaded into " + lpparam.packageName + " (" + lpparam.processName + ")");
        XposedBridge.log(TAG + ": loaded into " + lpparam.packageName);
        DoubaoLetterLongPressHook.install(lpparam);
    }
}
