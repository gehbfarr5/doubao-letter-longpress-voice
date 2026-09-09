package com.bytedance.android.input.speech.view;

import com.bytedance.android.doubaoime.ImeService;
import com.bytedance.android.input.basic.applog.api.IAppLog;
import com.bytedance.android.input.keyboard.InputView;
import com.bytedance.android.input.speech.AsrManager;
import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
final class h extends kotlin.u.c.n implements kotlin.u.b.a<kotlin.p> {
    final /* synthetic */ AsrLongPressView a;

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    h(AsrLongPressView asrLongPressView) {
        super(0);
        this.a = asrLongPressView;
    }

    @Override // kotlin.u.b.a
    public kotlin.p invoke() throws JSONException {
        com.bytedance.android.input.B.j.m(this.a.a, "rollback btn action up");
        AsrManager.a.F();
        InputView inputView = ImeService.z;
        if (inputView != null) {
            inputView.Z(false);
        }
        IAppLog.a aVar = IAppLog.a;
        JSONObject jSONObjectO = f.a.a.a.a.o("actiontype", "rollback");
        if (o.f4406f == null) {
            f.a.a.a.a.r();
        }
        o oVar = o.f4406f;
        kotlin.u.c.m.d(oVar, "null cannot be cast to non-null type com.bytedance.android.input.speech.view.EditorViewInfo");
        jSONObjectO.put("pagename", oVar.c());
        if (o.f4406f == null) {
            f.a.a.a.a.r();
        }
        o oVar2 = o.f4406f;
        kotlin.u.c.m.d(oVar2, "null cannot be cast to non-null type com.bytedance.android.input.speech.view.EditorViewInfo");
        if (oVar2.f().length() > 0) {
            if (o.f4406f == null) {
                f.a.a.a.a.r();
            }
            o oVar3 = o.f4406f;
            kotlin.u.c.m.d(oVar3, "null cannot be cast to non-null type com.bytedance.android.input.speech.view.EditorViewInfo");
            jSONObjectO.put("third_package_name", oVar3.h());
            if (o.f4406f == null) {
                f.a.a.a.a.r();
            }
            o oVar4 = o.f4406f;
            kotlin.u.c.m.d(oVar4, "null cannot be cast to non-null type com.bytedance.android.input.speech.view.EditorViewInfo");
            jSONObjectO.put("third_scene", oVar4.f());
            if (o.f4406f == null) {
                f.a.a.a.a.r();
            }
            o oVar5 = o.f4406f;
            kotlin.u.c.m.d(oVar5, "null cannot be cast to non-null type com.bytedance.android.input.speech.view.EditorViewInfo");
            jSONObjectO.put("third_extra", oVar5.g());
        }
        aVar.b("voiceinput_space_action", jSONObjectO);
        return kotlin.p.a;
    }
}
