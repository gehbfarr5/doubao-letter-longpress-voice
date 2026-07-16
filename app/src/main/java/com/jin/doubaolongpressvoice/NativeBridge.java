package com.jin.doubaolongpressvoice;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/** Loads the LSPosed native hook and receives exact nativeTouch long-press callbacks. */
final class NativeBridge {

    private static final String TAG = "DoubaoLongPressNative";
    private static volatile boolean loaded;

    private NativeBridge() {
    }

    static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        try {
            System.loadLibrary("doubaolongpress_native");
            loaded = true;
            Log.i(TAG, "native module loaded");
            XposedBridge.log(TAG + ": native module loaded");
        } catch (Throwable t) {
            Log.e(TAG, "native module load failed", t);
            XposedBridge.log(TAG + ": native module load failed: " + t);
        }
    }

    /** Called only for KeyboardView.nativeTouch(..., action=-1, ...). */
    @SuppressWarnings("unused")
    private static boolean onLongPress(Object keyboardView, int x, int y) {
        return DoubaoLetterLongPressHook.onNativeLongPress(keyboardView, x, y);
    }

    @SuppressWarnings("unused")
    private static void onDiagnostic(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
