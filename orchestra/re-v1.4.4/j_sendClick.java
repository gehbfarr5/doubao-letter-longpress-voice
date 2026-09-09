package com.bytedance.android.input.speech.view;

import com.bytedance.android.doubaoime.ImeService;
import com.bytedance.android.input.keyboard.InputView;
import com.bytedance.android.input.speech.AsrManager;

/* JADX INFO: loaded from: classes.dex */
final class j extends kotlin.u.c.n implements kotlin.u.b.a<kotlin.p> {
    final /* synthetic */ AsrLongPressView a;

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    j(AsrLongPressView asrLongPressView) {
        super(0);
        this.a = asrLongPressView;
    }

    @Override // kotlin.u.b.a
    public kotlin.p invoke() {
        long jCurrentTimeMillis = System.currentTimeMillis();
        com.bytedance.android.input.B.j.m(this.a.a, "send btn action up");
        InputView inputView = ImeService.z;
        if (inputView != null) {
            inputView.Z(false);
        }
        String str = this.a.a;
        StringBuilder sbT2 = f.a.a.a.a.t2("onClick, enterActionType: ");
        sbT2.append(this.a.f4373c);
        com.bytedance.android.input.B.j.i(str, sbT2.toString());
        AsrManager.a.E(this.a.f4373c, jCurrentTimeMillis, true);
        return kotlin.p.a;
    }
}
