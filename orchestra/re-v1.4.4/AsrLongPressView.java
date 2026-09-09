package com.bytedance.android.input.speech.view;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.lifecycle.Observer;
import com.bytedance.android.doubaoime.ImeService;
import com.bytedance.android.doubaoime.R;
import com.bytedance.android.input.basic.applog.api.IAppLog;
import com.bytedance.android.input.keyboard.InputView;
import com.bytedance.android.input.keyboard.areacontrol.C1106w;
import com.bytedance.android.input.speech.AsrManager;
import com.bytedance.android.input.speech.long_press.AsrEllipseView;
import com.bytedance.android.input.speech.long_press.AsrNotchedEllipseView;
import com.bytedance.webx.event.EventManager;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public final class AsrLongPressView extends FrameLayout {
    private final String a;
    private boolean b;

    /* JADX INFO: renamed from: c, reason: collision with root package name */
    private int f4373c;

    /* JADX INFO: renamed from: d, reason: collision with root package name */
    private AsrNotchedEllipseView f4374d;

    /* JADX INFO: renamed from: e, reason: collision with root package name */
    private AsrEllipseView f4375e;

    /* JADX INFO: renamed from: f, reason: collision with root package name */
    private AsrWaveView f4376f;

    /* JADX INFO: renamed from: g, reason: collision with root package name */
    private long f4377g;

    static final class a implements Observer, kotlin.u.c.i {
        private final /* synthetic */ kotlin.u.b.l a;

        a(kotlin.u.b.l lVar) {
            kotlin.u.c.m.f(lVar, "function");
            this.a = lVar;
        }

        public final boolean equals(Object obj) {
            if ((obj instanceof Observer) && (obj instanceof kotlin.u.c.i)) {
                return kotlin.u.c.m.a(this.a, ((kotlin.u.c.i) obj).getFunctionDelegate());
            }
            return false;
        }

        @Override // kotlin.u.c.i
        public final kotlin.b<?> getFunctionDelegate() {
            return this.a;
        }

        public final int hashCode() {
            return this.a.hashCode();
        }

        @Override // androidx.lifecycle.Observer
        public final /* synthetic */ void onChanged(Object obj) {
            this.a.invoke(obj);
        }
    }

    /* JADX WARN: 'this' call moved to the top of the method (can break code semantics) */
    public AsrLongPressView(Context context) {
        this(context, null);
        kotlin.u.c.m.f(context, "context");
    }

    public static final void a(AsrLongPressView asrLongPressView) {
        Objects.requireNonNull(asrLongPressView);
        long jCurrentTimeMillis = System.currentTimeMillis();
        if (jCurrentTimeMillis - asrLongPressView.f4377g > com.heytap.mcssdk.constant.a.r) {
            asrLongPressView.f4377g = jCurrentTimeMillis;
            AsrManager.a.I();
        }
    }

    private final void d() throws JSONException {
        InputView inputView = ImeService.z;
        if (inputView != null) {
            inputView.Z(false);
        }
        AsrManager.a.R0();
        IAppLog.a aVar = IAppLog.a;
        JSONObject jSONObjectO = f.a.a.a.a.o("actiontype", "loosen");
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
    }

    public final AsrWaveView e() {
        return this.f4376f;
    }

    @Override // android.widget.FrameLayout, android.view.View
    protected void onMeasure(int i2, int i3) {
        Typeface typefaceCreate;
        super.onMeasure(i2, i3);
        int size = View.MeasureSpec.getSize(i3);
        TextView textView = (TextView) findViewById(R.id.asr_long_press_text);
        ViewGroup.LayoutParams layoutParams = textView.getLayoutParams();
        ConstraintLayout.LayoutParams layoutParams2 = layoutParams instanceof ConstraintLayout.LayoutParams ? (ConstraintLayout.LayoutParams) layoutParams : null;
        if (layoutParams2 != null) {
            layoutParams2.setMargins(0, (int) C1106w.a.W(20), 0, 0);
        }
        textView.setTextSize(1, C1106w.a.l(11.0f));
        if (Build.VERSION.SDK_INT >= 28) {
            typefaceCreate = Typeface.create(Typeface.DEFAULT, EventManager.REGION_SYSTEM, false);
        } else {
            try {
                typefaceCreate = Typeface.create("sans-serif-medium", 0);
            } catch (Exception e2) {
                com.bytedance.android.input.B.j.k(this.a, "Failed to create typeface 'sans-serif-medium'", e2);
                typefaceCreate = Typeface.DEFAULT;
            }
        }
        textView.setTypeface(typefaceCreate);
        AsrWaveView asrWaveView = this.f4376f;
        ViewGroup.LayoutParams layoutParams3 = asrWaveView != null ? asrWaveView.getLayoutParams() : null;
        ConstraintLayout.LayoutParams layoutParams4 = layoutParams3 instanceof ConstraintLayout.LayoutParams ? (ConstraintLayout.LayoutParams) layoutParams3 : null;
        C1106w c1106w = C1106w.a;
        int iW = size - ((int) c1106w.W(getResources().getInteger(R.integer.asr_long_press_notched_ellipse_no_tip_height_draw)));
        Integer numValueOf = layoutParams4 != null ? Integer.valueOf(((ViewGroup.MarginLayoutParams) layoutParams4).height) : null;
        kotlin.u.c.m.c(numValueOf);
        int iIntValue = (iW - numValueOf.intValue()) / 2;
        if (iIntValue != ((ViewGroup.MarginLayoutParams) layoutParams4).topMargin) {
            ((ViewGroup.MarginLayoutParams) layoutParams4).topMargin = iIntValue;
            AsrWaveView asrWaveView2 = this.f4376f;
            if (asrWaveView2 != null) {
                asrWaveView2.requestLayout();
            }
        }
        AsrEllipseView asrEllipseView = this.f4375e;
        ViewGroup.LayoutParams layoutParams5 = asrEllipseView != null ? asrEllipseView.getLayoutParams() : null;
        ConstraintLayout.LayoutParams layoutParams6 = layoutParams5 instanceof ConstraintLayout.LayoutParams ? (ConstraintLayout.LayoutParams) layoutParams5 : null;
        if (layoutParams6 == null) {
            return;
        }
        ((ViewGroup.MarginLayoutParams) layoutParams6).height = (int) c1106w.W(88);
    }

    @Override // android.view.View
    public boolean onTouchEvent(MotionEvent motionEvent) throws JSONException {
        AsrEllipseView asrEllipseView = this.f4375e;
        if (asrEllipseView != null && asrEllipseView.c(motionEvent)) {
            com.bytedance.android.input.B.j.i(this.a, "[asr_view]: bottom");
            AsrNotchedEllipseView asrNotchedEllipseView = this.f4374d;
            if (asrNotchedEllipseView != null) {
                asrNotchedEllipseView.setLeaveAll();
            }
        } else {
            AsrNotchedEllipseView asrNotchedEllipseView2 = this.f4374d;
            if (asrNotchedEllipseView2 != null && asrNotchedEllipseView2.g(motionEvent)) {
                com.bytedance.android.input.B.j.i(this.a, "[asr_view]: left & right");
                return true;
            }
        }
        Integer numValueOf = motionEvent != null ? Integer.valueOf(motionEvent.getAction()) : null;
        if (numValueOf != null && numValueOf.intValue() == 0) {
            com.bytedance.android.input.B.j.i(this.a, "onTouchEvent: ACTION_DOWN");
            return false;
        }
        if (numValueOf != null && numValueOf.intValue() == 1) {
            com.bytedance.android.input.B.j.m(this.a, "onTouchEvent action up");
            d();
            return true;
        }
        if (numValueOf != null && numValueOf.intValue() == 2) {
            return true;
        }
        if (numValueOf == null || numValueOf.intValue() != 3) {
            com.bytedance.android.input.B.j.i(this.a, "onTouchEvent: else");
            return false;
        }
        com.bytedance.android.input.B.j.m(this.a, "onTouchEvent: ACTION_CANCEL showView(false)");
        d();
        return true;
    }

    @Override // android.view.ViewGroup
    public void onViewAdded(View view) {
        super.onViewAdded(view);
        if (this.b) {
            com.bytedance.android.input.B.j.i(this.a, "initView, already initialized");
            return;
        }
        AsrNotchedEllipseView asrNotchedEllipseView = (AsrNotchedEllipseView) findViewById(R.id.asr_action_btn);
        this.f4374d = asrNotchedEllipseView;
        if (asrNotchedEllipseView != null) {
            asrNotchedEllipseView.setActionInLeft(new g(this));
            asrNotchedEllipseView.setActionClickLeft(new h(this));
            asrNotchedEllipseView.setActionInRight(new i(this));
            asrNotchedEllipseView.setActionClickRight(new j(this));
        }
        AsrWaveView asrWaveView = (AsrWaveView) findViewById(R.id.asr_wave_view);
        this.f4376f = asrWaveView;
        if (asrWaveView != null) {
            asrWaveView.setHaveStartTip(false);
        }
        this.f4375e = (AsrEllipseView) findViewById(R.id.asr_ellipse_view);
        l.a.r().observeForever(new a(new k(this)));
        this.b = true;
    }

    /* JADX WARN: Removed duplicated region for block: B:20:0x0064  */
    @Override // android.view.View
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    protected void onVisibilityChanged(android.view.View r4, int r5) {
        /*
            Method dump skipped, instruction units count: 229
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.bytedance.android.input.speech.view.AsrLongPressView.onVisibilityChanged(android.view.View, int):void");
    }

    public final void setMAsrWaveView(AsrWaveView asrWaveView) {
        this.f4376f = asrWaveView;
    }

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    public AsrLongPressView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        kotlin.u.c.m.f(context, "context");
        this.a = "Asr-LongPressView";
        FrameLayout.inflate(context, R.layout.layout_asr_long_press, this);
    }
}
