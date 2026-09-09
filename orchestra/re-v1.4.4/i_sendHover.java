package com.bytedance.android.input.speech.view;

import com.bytedance.android.input.common.VibrationController;
import com.bytedance.android.input.keyboard.UserInteractiveManagerNext;

/* JADX INFO: loaded from: classes.dex */
final class i extends kotlin.u.c.n implements kotlin.u.b.a<kotlin.p> {
    final /* synthetic */ AsrLongPressView a;

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    i(AsrLongPressView asrLongPressView) {
        super(0);
        this.a = asrLongPressView;
    }

    @Override // kotlin.u.b.a
    public kotlin.p invoke() {
        com.bytedance.android.input.B.j.i(this.a.a, "send button move. set pressed and vibrate");
        UserInteractiveManagerNext.a.g(UserInteractiveManagerNext.KeySound.KEYBOARD, UserInteractiveManagerNext.KeyVibrate.STANDARD, VibrationController.VibrationType.CONFIRM, false);
        AsrLongPressView.a(this.a);
        return kotlin.p.a;
    }
}
