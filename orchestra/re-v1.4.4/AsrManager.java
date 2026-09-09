package com.bytedance.android.input.speech;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import com.bytedance.android.doubaoime.ImeService;
import com.bytedance.android.doubaoime.KeyboardJni;
import com.bytedance.android.doubaoime.R;
import com.bytedance.android.doubaoime.activity.SettingsActivityNext;
import com.bytedance.android.input.basic.IAppGlobals;
import com.bytedance.android.input.basic.applog.api.IAppLog;
import com.bytedance.android.input.basic.settings.api.IInputSettings;
import com.bytedance.android.input.common.SettingsConfigNext;
import com.bytedance.android.input.common.asr.api.IAsr;
import com.bytedance.android.input.keyboard.InputView;
import com.bytedance.android.input.keyboard.UserInteractiveManagerNext;
import com.bytedance.android.input.keyboard.aiwrite.C1058x;
import com.bytedance.android.input.llm.LLMRequest;
import com.bytedance.android.input.popup.WindowId;
import com.bytedance.android.input.smart_organize.n0;
import com.bytedance.android.input.smart_organize.o0;
import com.bytedance.android.input.speech.AsrManager;
import com.bytedance.common.utility.NetworkUtils;
import com.ss.android.common.applog.AppLog;
import com.ss.android.socialbase.downloader.constants.DBDefinition;
import com.ss.android.socialbase.downloader.constants.DownloadErrorCode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import kotlin.coroutines.intrinsics.CoroutineSingletons;
import kotlin.h;
import kotlinx.coroutines.C2349d;
import kotlinx.coroutines.C2354f0;
import kotlinx.coroutines.S;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public final class AsrManager {
    private static com.bytedance.android.input.speech.R.a F;
    private static Runnable G;
    private static boolean K;
    private static com.bytedance.android.input.popup.k N;
    private static boolean j;
    private static long k;
    private static boolean l;
    private static boolean m;
    private static long n;
    private static JSONObject p;
    private static boolean r;
    private static boolean s;
    private static boolean t;
    private static boolean u;
    private static boolean v;
    private static volatile kotlinx.coroutines.L<? extends n0> w;
    private static int x;
    private static boolean y;
    public static final AsrManager a = new AsrManager();
    private static final kotlin.text.i b = new kotlin.text.i("(\\r\\n|\\r|\\n){2,}");

    /* JADX INFO: renamed from: c, reason: collision with root package name */
    private static final F f4196c = new F();

    /* JADX INFO: renamed from: d, reason: collision with root package name */
    private static final Q f4197d = new Q();

    /* JADX INFO: renamed from: e, reason: collision with root package name */
    private static volatile boolean f4198e = true;

    /* JADX INFO: renamed from: f, reason: collision with root package name */
    private static M f4199f = new M();

    /* JADX INFO: renamed from: g, reason: collision with root package name */
    private static L f4200g = new L();

    /* JADX INFO: renamed from: h, reason: collision with root package name */
    private static String f4201h = "";

    /* JADX INFO: renamed from: i, reason: collision with root package name */
    private static SpeechStatus f4202i = SpeechStatus.KStop;
    private static JSONObject o = new JSONObject();
    private static boolean q = true;
    private static volatile Map<String, a> z = kotlin.collections.v.b();
    private static volatile Map<String, a> A = kotlin.collections.v.b();
    private static String B = "";
    private static final g C = new g();
    private static final kotlin.e D = kotlin.a.c(f.a);
    private static final Handler E = new Handler(Looper.getMainLooper());
    private static final Runnable H = new Runnable() { // from class: com.bytedance.android.input.speech.b
        @Override // java.lang.Runnable
        public final void run() {
            AsrManager.a0();
        }
    };
    private static final Runnable I = new Runnable() { // from class: com.bytedance.android.input.speech.k
        @Override // java.lang.Runnable
        public final void run() {
            AsrManager.d0();
        }
    };

    /* JADX INFO: renamed from: J, reason: collision with root package name */
    private static final Runnable f4195J = new Runnable() { // from class: com.bytedance.android.input.speech.d
        @Override // java.lang.Runnable
        public final void run() {
            AsrManager.b0();
        }
    };
    private static long L = -1;
    private static Runnable M = new Runnable() { // from class: com.bytedance.android.input.speech.a
        @Override // java.lang.Runnable
        public final void run() {
            AsrManager.Z();
        }
    };
    private static final Handler O = new Handler(Looper.getMainLooper());

    public enum SpeechStatus {
        KStart,
        KStop,
        KInAsr,
        KNoVoice,
        KNetErr,
        KTryStart,
        KRecorderErr,
        KResultModifying,
        KStoping,
        KNoVoiceClose,
        KErrorShowState
    }

    public static final class a {
        private final String a;
        private final String b;

        /* JADX INFO: renamed from: c, reason: collision with root package name */
        private final String f4203c;

        public a(String str, String str2, String str3) {
            f.a.a.a.a.c0(str, "src", str2, "dst", str3, "type");
            this.a = str;
            this.b = str2;
            this.f4203c = str3;
        }

        public final String a() {
            return this.b;
        }

        public final String b() {
            return this.a;
        }

        public final String c() {
            return this.f4203c;
        }

        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof a)) {
                return false;
            }
            a aVar = (a) obj;
            return kotlin.u.c.m.a(this.a, aVar.a) && kotlin.u.c.m.a(this.b, aVar.b) && kotlin.u.c.m.a(this.f4203c, aVar.f4203c);
        }

        public int hashCode() {
            return this.f4203c.hashCode() + f.a.a.a.a.c(this.b, this.a.hashCode() * 31, 31);
        }

        public String toString() {
            StringBuilder sbT2 = f.a.a.a.a.t2("FastModifyPairInfo(src=");
            sbT2.append(this.a);
            sbT2.append(", dst=");
            sbT2.append(this.b);
            sbT2.append(", type=");
            return f.a.a.a.a.c2(sbT2, this.f4203c, ')');
        }
    }

    @kotlin.t.i.a.e(c = "com.bytedance.android.input.speech.AsrManager$autoFeedbackLog$1", f = "AsrManager.kt", l = {}, m = "invokeSuspend")
    static final class c extends kotlin.t.i.a.i implements kotlin.u.b.p<kotlinx.coroutines.G, kotlin.t.d<? super kotlin.h<? extends kotlin.p>>, Object> {
        final /* synthetic */ String a;
        final /* synthetic */ String b;

        /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
        c(String str, String str2, kotlin.t.d<? super c> dVar) {
            super(2, dVar);
            this.a = str;
            this.b = str2;
        }

        @Override // kotlin.t.i.a.a
        public final kotlin.t.d<kotlin.p> create(Object obj, kotlin.t.d<?> dVar) {
            return new c(this.a, this.b, dVar);
        }

        @Override // kotlin.u.b.p
        public Object invoke(kotlinx.coroutines.G g2, kotlin.t.d<? super kotlin.h<? extends kotlin.p>> dVar) {
            return new c(this.a, this.b, dVar).invokeSuspend(kotlin.p.a);
        }

        @Override // kotlin.t.i.a.a
        public final Object invokeSuspend(Object obj) throws Throwable {
            Object objZ;
            CoroutineSingletons coroutineSingletons = CoroutineSingletons.COROUTINE_SUSPENDED;
            com.prolificinteractive.materialcalendarview.r.j0(obj);
            String str = this.a;
            String str2 = this.b;
            try {
                LLMRequest lLMRequest = LLMRequest.a;
                kotlin.u.c.m.e(str, "bugDesc");
                lLMRequest.e(str, null);
                com.bytedance.android.input.B.j.f2455f.q(str2, 2);
                objZ = kotlin.p.a;
            } catch (Throwable th) {
                objZ = com.prolificinteractive.materialcalendarview.r.z(th);
            }
            return kotlin.h.a(objZ);
        }
    }

    static final class d extends kotlin.u.c.n implements kotlin.u.b.a<kotlin.p> {
        public static final d a = new d();

        d() {
            super(0);
        }

        @Override // kotlin.u.b.a
        public kotlin.p invoke() {
            AsrManager.V0(AsrManager.a, "", true, false, null, 0.0f, 24);
            return kotlin.p.a;
        }
    }

    public static final class e implements com.bytedance.android.input.speech.R.a {
        final /* synthetic */ kotlin.u.c.B<w> a;
        final /* synthetic */ long b;

        e(kotlin.u.c.B<w> b, long j) {
            this.a = b;
            this.b = j;
        }

        /* JADX WARN: Multi-variable type inference failed */
        @Override // com.bytedance.android.input.speech.R.a
        public void a(w wVar) {
            kotlin.u.c.m.f(wVar, "asrCallBackInfo");
            this.a.a = wVar;
            if (wVar.g()) {
                Handler handler = AsrManager.O;
                final long j = this.b;
                handler.post(new Runnable() { // from class: com.bytedance.android.input.speech.g
                    @Override // java.lang.Runnable
                    public final void run() {
                        long j2 = j;
                        AsrManager asrManager = AsrManager.a;
                        StringBuilder sbT2 = f.a.a.a.a.t2("DoAsrSend IAllAsrBackListener onBack cost-time = ");
                        sbT2.append(System.currentTimeMillis() - j2);
                        asrManager.f0(sbT2.toString());
                        AsrManager.f4196c.A();
                        Runnable runnableK = asrManager.K();
                        if (runnableK != null) {
                            AsrManager.O.removeCallbacks(runnableK);
                            runnableK.run();
                        }
                    }
                });
            }
        }
    }

    static final class f extends kotlin.u.c.n implements kotlin.u.b.a<com.bytedance.android.input.popup.u> {
        public static final f a = new f();

        f() {
            super(0);
        }

        @Override // kotlin.u.b.a
        public com.bytedance.android.input.popup.u invoke() {
            IAppGlobals.a aVar = IAppGlobals.a;
            Objects.requireNonNull(aVar);
            return new com.bytedance.android.input.popup.u(aVar.getContext(), WindowId.CONFIRM_SPEECH_PERMISSION, 1);
        }
    }

    public static final class g extends SettingsConfigNext.b {
        g() {
        }

        @Override // com.bytedance.android.input.common.SettingsConfigNext.b
        public void onReset() {
        }

        @Override // com.bytedance.android.input.common.SettingsConfigNext.b
        public <T> void onSettingsConfigChanged(String str, T t) {
            kotlin.u.c.m.f(str, AppLog.KEY_ENCRYPT_RESP_KEY);
            com.bytedance.android.input.B.j.i("[ASR-Flow]-AsrManager", "onSettingsConfigChanged key = " + str + ", value = " + t);
            IAppGlobals.a aVar = IAppGlobals.a;
            String strE1 = f.a.a.a.a.e1(aVar, R.string.asr_offline_download_way, "IAppGlobals.context.getS…asr_offline_download_way)");
            if (kotlin.u.c.m.a(str, strE1)) {
                Object objF = SettingsConfigNext.f(strE1);
                if (kotlin.u.c.m.a(objF, 0)) {
                    com.bytedance.android.input.speech.V.k.a.l();
                    return;
                }
                if (kotlin.u.c.m.a(objF, 1)) {
                    com.bytedance.android.input.speech.V.k.a.p();
                    return;
                }
                if (kotlin.u.c.m.a(objF, 2)) {
                    com.bytedance.android.input.speech.V.k.a.o();
                } else if (kotlin.u.c.m.a(objF, 3)) {
                    AsrManager asrManager = AsrManager.a;
                    AsrManager.x = 0;
                    aVar.E().edit().putInt(aVar.getContext().getString(R.string.asr_offline_model_download_error_time), AsrManager.x).apply();
                }
            }
        }
    }

    @kotlin.t.i.a.e(c = "com.bytedance.android.input.speech.AsrManager", f = "AsrManager.kt", l = {DownloadErrorCode.ERROR_TIME_OUT}, m = "processAsrResult")
    static final class h extends kotlin.t.i.a.c {
        Object a;
        boolean b;

        /* JADX INFO: renamed from: c, reason: collision with root package name */
        /* synthetic */ Object f4205c;

        /* JADX INFO: renamed from: e, reason: collision with root package name */
        int f4207e;

        h(kotlin.t.d<? super h> dVar) {
            super(dVar);
        }

        @Override // kotlin.t.i.a.a
        public final Object invokeSuspend(Object obj) {
            this.f4205c = obj;
            this.f4207e |= Integer.MIN_VALUE;
            return AsrManager.this.u0(null, false, this);
        }
    }

    @kotlin.t.i.a.e(c = "com.bytedance.android.input.speech.AsrManager", f = "AsrManager.kt", l = {1116}, m = "processFinishAsrResult")
    static final class i extends kotlin.t.i.a.c {
        Object a;
        Object b;

        /* JADX INFO: renamed from: c, reason: collision with root package name */
        Object f4208c;

        /* JADX INFO: renamed from: d, reason: collision with root package name */
        Object f4209d;

        /* JADX INFO: renamed from: e, reason: collision with root package name */
        Object f4210e;

        /* JADX INFO: renamed from: f, reason: collision with root package name */
        /* synthetic */ Object f4211f;

        /* JADX INFO: renamed from: h, reason: collision with root package name */
        int f4213h;

        i(kotlin.t.d<? super i> dVar) {
            super(dVar);
        }

        @Override // kotlin.t.i.a.a
        public final Object invokeSuspend(Object obj) {
            this.f4211f = obj;
            this.f4213h |= Integer.MIN_VALUE;
            return AsrManager.i(AsrManager.this, null, this);
        }
    }

    static final class j extends kotlin.u.c.n implements kotlin.u.b.l<Long, kotlin.p> {
        final /* synthetic */ kotlin.u.c.x a;
        final /* synthetic */ String b;

        /* JADX INFO: renamed from: c, reason: collision with root package name */
        final /* synthetic */ String f4214c;

        /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
        j(kotlin.u.c.x xVar, String str, String str2) {
            super(1);
            this.a = xVar;
            this.b = str;
            this.f4214c = str2;
        }

        @Override // kotlin.u.b.l
        public kotlin.p invoke(Long l) {
            long jLongValue = l.longValue();
            this.a.a = true;
            o0.j(o0.a, this.b, AsrManager.a.A(this.f4214c), true, Boolean.TRUE, null, Boolean.FALSE, Long.valueOf(jLongValue), 16);
            return kotlin.p.a;
        }
    }

    @kotlin.t.i.a.e(c = "com.bytedance.android.input.speech.AsrManager$setCacheFastModifyPairsString$1", f = "AsrManager.kt", l = {}, m = "invokeSuspend")
    static final class k extends kotlin.t.i.a.i implements kotlin.u.b.p<kotlinx.coroutines.G, kotlin.t.d<? super kotlin.p>, Object> {
        final /* synthetic */ String a;

        /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
        k(String str, kotlin.t.d<? super k> dVar) {
            super(2, dVar);
            this.a = str;
        }

        @Override // kotlin.t.i.a.a
        public final kotlin.t.d<kotlin.p> create(Object obj, kotlin.t.d<?> dVar) {
            return new k(this.a, dVar);
        }

        @Override // kotlin.u.b.p
        public Object invoke(kotlinx.coroutines.G g2, kotlin.t.d<? super kotlin.p> dVar) throws Throwable {
            k kVar = new k(this.a, dVar);
            kotlin.p pVar = kotlin.p.a;
            kVar.invokeSuspend(pVar);
            return pVar;
        }

        @Override // kotlin.t.i.a.a
        public final Object invokeSuspend(Object obj) throws Throwable {
            String string;
            CoroutineSingletons coroutineSingletons = CoroutineSingletons.COROUTINE_SUSPENDED;
            com.prolificinteractive.materialcalendarview.r.j0(obj);
            try {
                JSONArray jSONArray = new JSONArray(this.a);
                LinkedHashMap linkedHashMap = new LinkedHashMap();
                LinkedHashMap linkedHashMap2 = new LinkedHashMap();
                int length = jSONArray.length();
                for (int i2 = 0; i2 < length; i2++) {
                    JSONObject jSONObject = jSONArray.getJSONObject(i2);
                    String strOptString = jSONObject.optString("src", "");
                    String strOptString2 = jSONObject.optString("dst", "");
                    kotlin.u.c.m.e(strOptString, "src");
                    boolean z = true;
                    if (strOptString.length() > 0) {
                        kotlin.u.c.m.e(strOptString2, "dst");
                        if (strOptString2.length() > 0) {
                            JSONArray jSONArrayOptJSONArray = jSONObject.optJSONArray("type");
                            if (jSONArrayOptJSONArray == null || (string = jSONArrayOptJSONArray.toString()) == null) {
                                string = "[]";
                            }
                            linkedHashMap.put(strOptString, new a(strOptString, strOptString2, string));
                            String strGetHansOrHant = KeyboardJni.GetHansOrHant(2, strOptString);
                            String strGetHansOrHant2 = KeyboardJni.GetHansOrHant(2, strOptString2);
                            kotlin.u.c.m.e(strGetHansOrHant, "traditionalSrc");
                            if (strGetHansOrHant.length() > 0) {
                                kotlin.u.c.m.e(strGetHansOrHant2, "traditionalDst");
                                if (strGetHansOrHant2.length() <= 0) {
                                    z = false;
                                }
                                if (z) {
                                    linkedHashMap2.put(strGetHansOrHant, new a(strGetHansOrHant, strGetHansOrHant2, string));
                                }
                            }
                        }
                    }
                }
                AsrManager asrManager = AsrManager.a;
                AsrManager.z = linkedHashMap;
                AsrManager.A = linkedHashMap2;
            } catch (Exception e2) {
                f.a.a.a.a.H(e2, f.a.a.a.a.t2("[DeleteAssociate] setCacheFastModifyPairsString error: "), "[ASR-Flow]-AsrManager");
            }
            return kotlin.p.a;
        }
    }

    static final class l extends kotlin.u.c.n implements kotlin.u.b.a<kotlin.p> {
        final /* synthetic */ com.bytedance.common_biz.tool_bar.view.config.j a;

        /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
        l(com.bytedance.common_biz.tool_bar.view.config.j jVar) {
            super(0);
            this.a = jVar;
        }

        @Override // kotlin.u.b.a
        public kotlin.p invoke() {
            com.bytedance.common_biz.tool_bar.view.config.h hVarB = com.bytedance.common_biz.tool_bar.view.config.i.b();
            if (hVarB != null) {
                hVarB.x(1, this.a);
            }
            return kotlin.p.a;
        }
    }

    static final class m extends kotlin.u.c.n implements kotlin.u.b.a<kotlin.p> {
        public static final m a = new m();

        m() {
            super(0);
        }

        @Override // kotlin.u.b.a
        public kotlin.p invoke() {
            AsrManager.f4196c.x();
            return kotlin.p.a;
        }
    }

    static final class n extends kotlin.u.c.n implements kotlin.u.b.a<kotlin.p> {
        public static final n a = new n();

        n() {
            super(0);
        }

        @Override // kotlin.u.b.a
        public kotlin.p invoke() {
            AsrManager.a.g0("[ASR-Flow][stopAsr][Android] stopNotWait callbackInfo is null");
            return kotlin.p.a;
        }
    }

    private AsrManager() {
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final int A(String str) {
        return str.codePointCount(0, str.length());
    }

    private final void B(String str) {
        if (KeyboardJni.getKeyboardJni().onAsrSetPreedit(str)) {
            f0(f.a.a.a.a.J1("processFinishAsrResult onAsrSetPreedit asrText = ", str));
            KeyboardJni.getKeyboardJni().onAsrCommitPreeditText();
            O().g(true);
        } else {
            f0(f.a.a.a.a.J1("processFinishAsrResult updateAndFinishVoiceText asrText = ", str));
            O().d(str);
            z0("Input_voiceinputshow", "");
        }
        com.bytedance.android.input.llm.c.o("", str);
    }

    private final void D0(String str, String str2, boolean z2, Boolean bool, String str3, Long l2) {
        o0 o0Var = o0.a;
        int iA = A(str);
        if (!kotlin.u.c.m.a(bool, Boolean.TRUE)) {
            l2 = null;
        }
        o0Var.i(str2, iA, false, z2, bool, str3, l2);
    }

    private final void E0(String str, boolean z2) {
        if (kotlin.u.c.m.a(com.bytedance.android.input.speech.view.l.a.r().getValue(), Boolean.FALSE)) {
            return;
        }
        JSONObject jSONObject = new JSONObject();
        try {
            jSONObject.put("reason", str);
            jSONObject.put("is_error", z2);
            jSONObject.put("is_key", false);
            jSONObject.put("app_name", IAppGlobals.a.L());
            IAppLog.a.b("input_voiceinput_stopfrom", jSONObject);
        } catch (JSONException e2) {
            e2.printStackTrace();
        }
    }

    public static void F0(AsrManager asrManager, String str, long j2, boolean z2, String str2, int i2) {
        if ((i2 & 1) != 0) {
            str = "";
        }
        long j3 = 0;
        if ((i2 & 2) != 0) {
            j2 = 0;
        }
        if ((i2 & 4) != 0) {
            z2 = false;
        }
        if ((i2 & 8) != 0) {
            str2 = "";
        }
        kotlin.u.c.m.f(str, "timeKey");
        kotlin.u.c.m.f(str2, "from");
        try {
            if (p == null) {
                return;
            }
            com.bytedance.android.input.B.j.i("[ASR-Flow]-AsrManager", "reportStopTime key = " + str + ", time = " + j2);
            boolean zA = true;
            if (str.length() > 0) {
                if (kotlin.u.c.m.a(str, "AsrStopTime")) {
                    com.bytedance.android.input.common.E.h.a("AsrStopTime");
                } else if (kotlin.u.c.m.a(str, "StartSessionFinish")) {
                    long jC = com.bytedance.android.input.common.E.h.c("AsrStopTime");
                    JSONObject jSONObject = p;
                    if (jSONObject != null) {
                        jSONObject.remove(str);
                    }
                    JSONObject jSONObject2 = p;
                    if (jSONObject2 != null) {
                        if (((int) jC) != -1) {
                            j3 = jC;
                        }
                        jSONObject2.put(str, j3);
                    }
                    com.bytedance.android.input.common.E.h.a("StartSessionFinish");
                } else {
                    if (!kotlin.u.c.m.a(str, "ForceStop")) {
                        zA = kotlin.u.c.m.a(str, "LongPressStop");
                    }
                    if (zA) {
                        JSONObject jSONObject3 = p;
                        if (jSONObject3 != null) {
                            jSONObject3.remove(str);
                        }
                        JSONObject jSONObject4 = p;
                        if (jSONObject4 != null) {
                            jSONObject4.put(str, j2);
                        }
                    } else if (kotlin.u.c.m.a(str, "ForceStopFrom")) {
                        JSONObject jSONObject5 = p;
                        if (jSONObject5 != null) {
                            jSONObject5.remove("ForceStopFrom");
                        }
                        JSONObject jSONObject6 = p;
                        if (jSONObject6 != null) {
                            jSONObject6.put(str, str2);
                        }
                    } else {
                        long jD = com.bytedance.android.input.common.E.h.d("StartSessionFinish");
                        JSONObject jSONObject7 = p;
                        if (jSONObject7 != null) {
                            jSONObject7.remove(str);
                        }
                        JSONObject jSONObject8 = p;
                        if (jSONObject8 != null) {
                            jSONObject8.put(str, jD);
                        }
                    }
                }
            }
            if (z2) {
                IAppLog.a aVar = IAppLog.a;
                JSONObject jSONObject9 = p;
                kotlin.u.c.m.c(jSONObject9);
                aVar.b("input_voiceinput_stop_time", jSONObject9);
                p = null;
            }
        } catch (Exception e2) {
            com.bytedance.android.input.B.j.j("[ASR-Flow]-AsrManager", e2.getMessage());
        }
    }

    private final void H0(final kotlin.u.b.a<kotlin.p> aVar) {
        if (kotlin.u.c.m.a(Looper.myLooper(), Looper.getMainLooper())) {
            aVar.invoke();
        } else {
            E.post(new Runnable() { // from class: com.bytedance.android.input.speech.j
                @Override // java.lang.Runnable
                public final void run() {
                    kotlin.u.b.a aVar2 = aVar;
                    kotlin.u.c.m.f(aVar2, "$tmp0");
                    aVar2.invoke();
                }
            });
        }
    }

    private final String J(String str) {
        Object objZ;
        boolean z2 = false;
        if (str.length() == 0) {
            return str;
        }
        String strL = IAppGlobals.a.L();
        if (!(strL == null || strL.length() == 0)) {
            try {
                objZ = IInputSettings.a.b().d();
            } catch (Throwable th) {
                objZ = com.prolificinteractive.materialcalendarview.r.z(th);
            }
            if (objZ instanceof h.a) {
                objZ = null;
            }
            List list = (List) objZ;
            z2 = list != null && list.contains(strL);
        }
        return z2 ? kotlin.text.a.K(kotlin.text.a.K(str, "\n", "", false, 4, null), "\r", "", false, 4, null) : str;
    }

    public static final void L0(boolean z2) {
        y = z2;
        f.a.a.a.a.k0("[reportPairNew] setTextAsrStateForModifyPair isAsrForModifyPair = ", z2, "[ASR-Flow]-AsrManager");
    }

    private final com.bytedance.android.input.popup.u M() {
        return (com.bytedance.android.input.popup.u) D.getValue();
    }

    private final com.bytedance.android.input.speech.T.a O() {
        ImeProcessAsrInputDispatcher imeProcessAsrInputDispatcher = ImeProcessAsrInputDispatcher.a;
        return ImeProcessAsrInputDispatcher.a();
    }

    public static final boolean Q() {
        f.a.a.a.a.F0(f.a.a.a.a.t2("[reportPairNew] getTextAsrStateForModifyPair isAsrForModifyPair = "), y, "[ASR-Flow]-AsrManager");
        return y;
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Failed to restore switch over string. Please report as a decompilation issue */
    /* JADX WARN: Removed duplicated region for block: B:103:0x018e  */
    /* JADX WARN: Removed duplicated region for block: B:110:0x01a3  */
    /* JADX WARN: Removed duplicated region for block: B:111:0x01a6  */
    /* JADX WARN: Removed duplicated region for block: B:84:0x0159  */
    /* JADX WARN: Removed duplicated region for block: B:95:0x017a  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private final void S0(java.lang.String r10) throws org.json.JSONException {
        /*
            Method dump skipped, instruction units count: 644
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.bytedance.android.input.speech.AsrManager.S0(java.lang.String):void");
    }

    public static /* synthetic */ void V0(AsrManager asrManager, String str, boolean z2, boolean z3, String str2, float f2, int i2) {
        if ((i2 & 8) != 0) {
            str2 = null;
        }
        String str3 = str2;
        if ((i2 & 16) != 0) {
            f2 = 1.0f;
        }
        asrManager.U0(str, z2, z3, str3, f2);
    }

    private final void W0(SpeechStatus speechStatus, String str, boolean z2) {
        InputView inputView;
        com.bytedance.android.input.B.j.i("[ASR-Flow]-AsrManager", "[ASR-Flow][UIState][Android] updateUiState status=" + speechStatus + " from = " + str + "---mCurrentUIStatus = " + f4202i + "---forceUpdateState = " + z2);
        SpeechStatus speechStatus2 = f4202i;
        SpeechStatus speechStatus3 = SpeechStatus.KErrorShowState;
        if (speechStatus2 == speechStatus3 && speechStatus == SpeechStatus.KStop && r && !z2) {
            if (!IInputSettings.a.b().g()) {
                KeyboardJni.updateEnterOkTextForSpeech(false, str);
            }
            f0("[ASR-Flow][UIState][Android] updateUiState return status=" + speechStatus + " start, mCurrentUIStatus = " + f4202i + ", status = " + speechStatus);
            return;
        }
        f4202i = speechStatus;
        com.bytedance.android.input.B.j.i("[ASR-Flow]-AsrManager", "[ASR-Flow][UIState][Android] updateUiState status=" + speechStatus);
        if (speechStatus == SpeechStatus.KStop) {
            InputView inputView2 = ImeService.z;
            if (inputView2 != null) {
                inputView2.a0(false);
            }
            KeyboardJni.updateEnterOkTextForSpeech(false, str);
        } else if (speechStatus == SpeechStatus.KTryStart) {
            N.a.a();
            V0(this, f.a.a.a.a.e1(IAppGlobals.a, R.string.asr_start, "IAppGlobals.context.getString(R.string.asr_start)"), false, false, null, 0.0f, 24);
            KeyboardJni.updateEnterOkTextForSpeech(true, str);
        } else if (speechStatus == SpeechStatus.KStoping) {
            N.a.a();
            if (!r && (inputView = ImeService.z) != null) {
                inputView.a0(true);
            }
            V0(this, f.a.a.a.a.e1(IAppGlobals.a, R.string.asr_recognize, "IAppGlobals.context.getS…g(R.string.asr_recognize)"), true, false, "lottie/ime_asr_recognize.json", 0.0f, 16);
            KeyboardJni.updateEnterOkTextForSpeech(false, str);
        } else if (speechStatus == speechStatus3 && IInputSettings.a.b().g()) {
            KeyboardJni.updateEnterOkTextForSpeech(true, str);
        }
        KeyboardJni.getKeyboardJni().updateSpeechTip(f4202i.ordinal(), str);
    }

    static /* synthetic */ void X0(AsrManager asrManager, SpeechStatus speechStatus, String str, boolean z2, int i2) {
        if ((i2 & 2) != 0) {
            str = "";
        }
        if ((i2 & 4) != 0) {
            z2 = false;
        }
        asrManager.W0(speechStatus, str, z2);
    }

    private final boolean Y(int i2) {
        KeyboardJni.EnterActionType enterActionType = KeyboardJni.EnterActionType.kIME_ACTION_SEND_EXPRESSION;
        if (i2 != 8) {
            KeyboardJni.EnterActionType enterActionType2 = KeyboardJni.EnterActionType.kIME_ACTION_SEND;
            if (i2 != 4) {
                return false;
            }
        }
        return true;
    }

    public static void Z() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("mOnFinishInputHandle = ");
            IAsr.b bVar = IAsr.a;
            IAsr.Type type = IAsr.Type.SDK;
            sb.append(bVar.a(type));
            com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", sb.toString());
            AsrManager asrManager = a;
            if (asrManager.X()) {
                if (L != -1) {
                    long jCurrentTimeMillis = System.currentTimeMillis() - L;
                    IAppLog.a aVar = IAppLog.a;
                    JSONObject jSONObject = new JSONObject();
                    ImeService service = KeyboardJni.getService();
                    jSONObject.putOpt("is_keyboard_open", service != null ? Boolean.valueOf(service.isInputViewShown()) : null);
                    jSONObject.putOpt("app_package_name", IAppGlobals.a.L());
                    jSONObject.putOpt("input_life_cycle_timestamp_gap_ms", Long.valueOf(jCurrentTimeMillis));
                    aVar.b("asr_technology_finish_input_handle", jSONObject);
                }
                L = -1L;
                if (IInputSettings.a.b().j()) {
                    asrManager.Q0(true, "onFinishInputHandle");
                }
            }
            IAsr iAsrA = bVar.a(type);
            if (iAsrA != null) {
                iAsrA.disconnect();
            }
        } catch (Exception e2) {
            StringBuilder sbT2 = f.a.a.a.a.t2("mOnFinishInputHandle error=");
            sbT2.append(e2.getMessage());
            com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", sbT2.toString());
        }
    }

    public static void a0() {
        AsrManager asrManager = a;
        asrManager.Q0(true, "stopOneOutTime");
        asrManager.s0(R.string.asr_download_model_tip_title);
        asrManager.y0(12);
        com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "[ASR-Flow]-State mOnStopWaitHandle StopAsr updateUiState kStoping to KStop");
    }

    public static void b0() {
        com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "[ASR-Flow][UIState][Android] mForceUpdateUIHandle prepare forceUpdateUI");
        a.W0(SpeechStatus.KStop, "ForceUpdateUIHandle", true);
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:25:0x00a5  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public static void c0(long r8, kotlin.u.c.B r10, long r11, int r13) throws org.json.JSONException {
        /*
            Method dump skipped, instruction units count: 415
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.bytedance.android.input.speech.AsrManager.c0(long, kotlin.u.c.B, long, int):void");
    }

    public static void d0() {
        AsrManager asrManager = a;
        asrManager.Q0(true, "stopTwoOutTime");
        asrManager.s0(R.string.asr_download_model_tip_title);
        asrManager.y0(13);
        com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "[ASR-Flow]-State mOnStopWaitFinishHandle StopAsr updateUiState kStoping to KStop");
    }

    public static void e0() {
        if (r) {
            InputView inputView = ImeService.z;
            if (inputView != null) {
                inputView.a0(true);
            }
            E.postDelayed(f4195J, com.heytap.mcssdk.constant.a.r);
            return;
        }
        InputView inputView2 = ImeService.z;
        if (inputView2 != null) {
            inputView2.Z(true);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void f0(String str) {
        IAppGlobals.a.e("[ASR-Flow]-AsrManager", str);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void g0(String str) {
        IAppGlobals.a.p("[ASR-Flow]-AsrManager", str);
    }

    public static final /* synthetic */ Object i(AsrManager asrManager, String str, kotlin.t.d dVar) {
        return asrManager.v0(null, dVar);
    }

    public static final void n(AsrManager asrManager) {
        v = true;
        asrManager.H0(D.a);
    }

    private final void s() {
        if (!IInputSettings.a.b().r()) {
            com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "[ASR-Flow][startAsr][Android] audioUnavailableCauseStopTipShow optAsrAudioNotAvailable = false return");
            return;
        }
        f.a.a.a.a.G0(f.a.a.a.a.t2("[ASR-Flow][startAsr][Android] audioUnavailableCauseStopTipShow mIsToolBarStart = "), r, "[ASR-Flow]-AsrManager");
        Handler handler = E;
        handler.removeCallbacks(f4195J);
        handler.post(new Runnable() { // from class: com.bytedance.android.input.speech.l
            @Override // java.lang.Runnable
            public final void run() {
                AsrManager.e0();
            }
        });
    }

    private final void s0(int i2) {
        Object objZ;
        IAppGlobals.a aVar = IAppGlobals.a;
        boolean zBooleanValue = ((Boolean) f.a.a.a.a.X0(aVar, R.string.asr_offline_model_download_tip_show, "IAppGlobals.context.getS…_model_download_tip_show)", "null cannot be cast to non-null type kotlin.Boolean")).booleanValue();
        f.a.a.a.a.l0("popDownloadOfflineTip canShow = ", zBooleanValue, "[ASR-Flow]-AsrManager");
        if (zBooleanValue) {
            if (com.bytedance.android.input.speech.V.k.a.x()) {
                com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "popDownloadOfflineTip OfflineModelManager.modelExist");
                return;
            }
            if (N == null) {
                final com.bytedance.android.input.popup.k kVar = new com.bytedance.android.input.popup.k(aVar.getApplication(), WindowId.CONFIRM_DOWNLOAD_MODEL_TIPS_UI, 2, R.layout.layout_asr_download_model_tip, true);
                kVar.y(new View.OnClickListener() { // from class: com.bytedance.android.input.speech.i
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view) {
                        com.bytedance.android.input.popup.k kVar2 = kVar;
                        kotlin.u.c.m.f(kVar2, "$this_apply");
                        StringBuilder sb = new StringBuilder();
                        sb.append("click right isWifi = ");
                        IAppGlobals.a aVar2 = IAppGlobals.a;
                        sb.append(NetworkUtils.j(aVar2.getApplication()));
                        com.bytedance.android.input.B.j.i("[ASR-Flow]-AsrManager", sb.toString());
                        String string = aVar2.getContext().getString(R.string.asr_offline_download_way);
                        kotlin.u.c.m.e(string, "IAppGlobals.context.getS…asr_offline_download_way)");
                        SettingsConfigNext.m(string, 2);
                        Intent intent = new Intent();
                        intent.setAction(SettingsActivityNext.MAIN_PROCESS_UPDATE_PREFERENCE);
                        intent.putExtra("download_way", 2);
                        aVar2.getContext().sendBroadcast(intent);
                        String string2 = aVar2.getContext().getString(R.string.asr_offline_model_download_tip_show);
                        kotlin.u.c.m.e(string2, "IAppGlobals.context.getS…_model_download_tip_show)");
                        SettingsConfigNext.m(string2, Boolean.FALSE);
                        if (!NetworkUtils.j(aVar2.getApplication())) {
                            C1058x.f3540e.e(R.string.asr_download_model_tip_wifi_choose);
                        }
                        kVar2.dismiss();
                    }
                });
                kVar.v(new View.OnClickListener() { // from class: com.bytedance.android.input.speech.e
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view) {
                        com.bytedance.android.input.popup.k kVar2 = kVar;
                        kotlin.u.c.m.f(kVar2, "$this_apply");
                        IAppGlobals.a aVar2 = IAppGlobals.a;
                        SettingsConfigNext.m(f.a.a.a.a.e1(aVar2, R.string.asr_offline_download_way, "IAppGlobals.context.getS…asr_offline_download_way)"), 1);
                        Intent intent = new Intent(aVar2.getContext(), (Class<?>) SettingsActivityNext.class);
                        intent.addFlags(268468224);
                        intent.putExtra(SettingsActivityNext.EXTRA_OFFLINE_MODEL_DOWNLOAD_WAY, 1);
                        SettingsActivityNext.FragmentType[] fragmentTypeArrValues = SettingsActivityNext.FragmentType.values();
                        SettingsActivityNext.FragmentType fragmentType = SettingsActivityNext.FragmentType.OFFLINE_DOWNLOAD;
                        intent.putExtra(SettingsActivityNext.EXTRA_FRAGMENT_TYPE, fragmentTypeArrValues[14].name());
                        intent.putExtra(SettingsActivityNext.EXTRA_SETTINGS_SOURCE, SettingsActivityNext.SETTINGS_SOURCE_KEYBOARD);
                        aVar2.getContext().startActivity(intent);
                        String string = aVar2.getContext().getString(R.string.asr_offline_model_download_tip_show);
                        kotlin.u.c.m.e(string, "IAppGlobals.context.getS…_model_download_tip_show)");
                        SettingsConfigNext.m(string, Boolean.FALSE);
                        kVar2.dismiss();
                    }
                });
                kVar.q(new View.OnClickListener() { // from class: com.bytedance.android.input.speech.c
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view) {
                        com.bytedance.android.input.popup.k kVar2 = kVar;
                        kotlin.u.c.m.f(kVar2, "$this_apply");
                        IAppGlobals.a aVar2 = IAppGlobals.a;
                        SettingsConfigNext.m(f.a.a.a.a.e1(aVar2, R.string.asr_offline_download_way, "IAppGlobals.context.getS…asr_offline_download_way)"), 3);
                        Intent intent = new Intent();
                        intent.setAction(SettingsActivityNext.MAIN_PROCESS_UPDATE_PREFERENCE);
                        intent.putExtra("download_way", 3);
                        aVar2.getContext().sendBroadcast(intent);
                        C1058x.f3540e.e(R.string.asr_download_model_tip_close);
                        String string = aVar2.getContext().getString(R.string.asr_offline_model_download_tip_show);
                        kotlin.u.c.m.e(string, "IAppGlobals.context.getS…_model_download_tip_show)");
                        SettingsConfigNext.m(string, Boolean.FALSE);
                        kVar2.dismiss();
                    }
                });
                N = kVar;
            }
            com.bytedance.android.input.popup.k kVar2 = N;
            if (kVar2 != null) {
                kVar2.B(i2);
            }
            com.bytedance.android.input.popup.t.f().o(N, 0);
            return;
        }
        try {
            String string = aVar.getContext().getString(R.string.asr_offline_download_way);
            kotlin.u.c.m.e(string, "IAppGlobals.context.getS…asr_offline_download_way)");
            Object objF = SettingsConfigNext.f(string);
            objZ = objF instanceof Integer ? (Integer) objF : null;
        } catch (Throwable th) {
            objZ = com.prolificinteractive.materialcalendarview.r.z(th);
        }
        Integer num = (Integer) (objZ instanceof h.a ? null : objZ);
        if (num == null) {
            com.bytedance.android.input.B.j.j("[ASR-Flow]-AsrManager", "popDownloadOfflineTip invalid download way config");
            return;
        }
        int iIntValue = num.intValue();
        IAppGlobals.a aVar2 = IAppGlobals.a;
        int i3 = aVar2.E().getInt(aVar2.getContext().getString(R.string.asr_offline_model_download_error_time), 0);
        x = i3;
        if (iIntValue == 3) {
            int i4 = i3 + 1;
            x = i4;
            if (i4 >= 1) {
                com.bytedance.android.input.speech.V.k.a.o();
            }
            aVar2.E().edit().putInt(aVar2.getContext().getString(R.string.asr_offline_model_download_error_time), x).apply();
        }
        f.a.a.a.a.o0(f.a.a.a.a.w2("popDownloadOfflineTip downloadWay = ", iIntValue, ", mDownloadErrorCount = "), x, "[ASR-Flow]-AsrManager");
    }

    private final void t(String str, String str2) throws JSONException {
        IAppGlobals.a aVar = IAppGlobals.a;
        if (TextUtils.equals(e.a.b.a.j(aVar), "local_test")) {
            return;
        }
        JSONObject jSONObject = new JSONObject();
        String str3 = "voice_input_android_error_" + str;
        jSONObject.put("category", str3);
        IAppLog.a aVar2 = IAppLog.a;
        Objects.requireNonNull(aVar2);
        jSONObject.put("did", aVar2.getDeviceId());
        jSONObject.put("versionName", "1.4.4.10");
        jSONObject.put("buildTime", "20260907.1307");
        jSONObject.put("report_time", LocalDateTime.now());
        jSONObject.put(DBDefinition.TASK_ID, str2);
        jSONObject.put("errorType", "voice");
        String string = jSONObject.toString();
        kotlin.u.c.m.e(string, "extra.toString()");
        String strEncodeEncrpty = KeyboardJni.getKeyboardJni().encodeEncrpty(jSONObject.toString());
        StringBuilder sbC2 = f.a.a.a.a.C2("feedback report bugDesc = ", string, ", ");
        sbC2.append(e.a.b.a.j(aVar));
        com.bytedance.android.input.B.j.i("[ASR-Flow]-AsrManager", sbC2.toString());
        C2349d.c(C2354f0.a, S.b(), null, new c(strEncodeEncrpty, str3, null), 2, null);
    }

    private final String u(String str, String str2) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(str);
            sb.append("[##]");
            sb.append(str2);
            sb.append("[##]");
            String strL = IAppGlobals.a.L();
            if (strL == null) {
                strL = "";
            }
            sb.append(strL);
            return sb.toString();
        } catch (Exception unused) {
            return f.a.a.a.a.L1(str, "[##]", str2);
        }
    }

    private final com.bytedance.common_biz.tool_bar.view.config.j v(int i2) {
        return new com.bytedance.common_biz.tool_bar.view.config.j(f.a.a.a.a.e1(IAppGlobals.a, i2, "IAppGlobals.context.getString(textResId)"), 0, null, null, null, false, 60);
    }

    /* JADX WARN: Removed duplicated region for block: B:7:0x0019  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private final java.lang.Object v0(java.lang.String r23, kotlin.t.d<? super com.bytedance.android.input.speech.AsrManager.b> r24) throws java.lang.Throwable {
        /*
            Method dump skipped, instruction units count: 1234
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.bytedance.android.input.speech.AsrManager.v0(java.lang.String, kotlin.t.d):java.lang.Object");
    }

    /* JADX WARN: Removed duplicated region for block: B:8:0x0010  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private final void w0(int r3, long r4) {
        /*
            r2 = this;
            com.bytedance.android.doubaoime.KeyboardJni$EnterActionType r0 = com.bytedance.android.doubaoime.KeyboardJni.EnterActionType.kIME_ACTION_SEND_EXPRESSION
            r0 = 4
            r1 = 8
            if (r3 == r1) goto L10
            com.bytedance.android.doubaoime.KeyboardJni$EnterActionType r1 = com.bytedance.android.doubaoime.KeyboardJni.EnterActionType.kIME_ACTION_SEND
            if (r3 != r0) goto Lc
            goto L10
        Lc:
            com.bytedance.android.doubaoime.KeyboardJni.doSendAction()
            goto L22
        L10:
            java.lang.String r3 = "asr_real_do_send"
            com.bytedance.android.input.speech.AsrContext.N(r3)
            com.bytedance.android.doubaoime.ImeService r3 = com.bytedance.android.doubaoime.KeyboardJni.getService()
            com.bytedance.android.input.editor.a r3 = r3.r()
            if (r3 == 0) goto L22
            r3.performEditorAction(r0)
        L22:
            java.lang.String r3 = "DoAsrSend sendFinish costTime = "
            java.lang.StringBuilder r3 = f.a.a.a.a.t2(r3)
            long r0 = java.lang.System.currentTimeMillis()
            long r0 = r0 - r4
            r3.append(r0)
            java.lang.String r3 = r3.toString()
            r2.g0(r3)
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.bytedance.android.input.speech.AsrManager.w0(int, long):void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void x(String str) {
        kotlinx.coroutines.L<? extends n0> l2 = w;
        if (l2 == null) {
            return;
        }
        StringBuilder sbC2 = f.a.a.a.a.C2("[SmartOrganizeVoice] cancel request job reason=", str, " active=");
        sbC2.append(l2.i());
        g0(sbC2.toString());
        l2.a(new CancellationException(str));
        if (w == l2) {
            w = null;
        }
    }

    private final void x0(String str, long j2) throws JSONException {
        if (m) {
            F0(this, null, 0L, true, null, 11);
            if (!kotlin.u.c.m.a(str, "send_clicked")) {
                if (com.bytedance.android.input.common.p.a().length() == 0) {
                    if (kotlin.u.c.m.a(str, "next_asr_start")) {
                        return;
                    }
                    AsrContext.a.D(null);
                    return;
                }
            }
            if (!kotlin.u.c.m.a(str, "next_asr_start")) {
                try {
                    JSONObject jSONObject = new JSONObject();
                    AsrContext.a.D(jSONObject);
                    jSONObject.put("app_name", IAppGlobals.a.L());
                    jSONObject.put("reason", str);
                    jSONObject.put("commit", com.bytedance.android.input.speech.view.l.a.k().length());
                    jSONObject.put("final_lens", com.bytedance.android.input.common.y.e());
                    jSONObject.put("radio", f4197d.f4307i);
                    IAppLog.a.b("Input_voiceinputedit_new", jSONObject);
                    G0();
                } catch (JSONException e2) {
                    e2.printStackTrace();
                }
            }
            try {
                JSONObject jSONObject2 = new JSONObject();
                jSONObject2.put("time", j2);
                jSONObject2.put("app_name", IAppGlobals.a.L());
                jSONObject2.put("reason", str);
                jSONObject2.put("commit", O().h());
                jSONObject2.put("final_lens", com.bytedance.android.input.common.y.e());
                jSONObject2.put("radio", f4197d.f4307i);
                IAppLog.a.b("Input_voiceinputedit", jSONObject2);
            } catch (JSONException e3) {
                e3.printStackTrace();
            }
            J0(false);
        }
    }

    private final void y0(int i2) {
        JSONObject jSONObject = new JSONObject();
        try {
            jSONObject.put("err_code", i2);
            jSONObject.put("app_name", IAppGlobals.a.L());
            IAppLog.a.b("asr_error", jSONObject);
        } catch (JSONException e2) {
            e2.printStackTrace();
        }
    }

    private final void z() throws JSONException {
        JSONObject jSONObject = new JSONObject();
        p = jSONObject;
        if (jSONObject != null) {
            jSONObject.put("StartSessionFinish", 0);
        }
        JSONObject jSONObject2 = p;
        if (jSONObject2 != null) {
            jSONObject2.put("ReceiveStreamResult", 0);
        }
        JSONObject jSONObject3 = p;
        if (jSONObject3 != null) {
            jSONObject3.put("ReceiveSecondResult", 0);
        }
        JSONObject jSONObject4 = p;
        if (jSONObject4 != null) {
            jSONObject4.put("ReceiveSessionResult", 0);
        }
        JSONObject jSONObject5 = p;
        if (jSONObject5 != null) {
            jSONObject5.put("StopCommit", 0);
        }
        JSONObject jSONObject6 = p;
        if (jSONObject6 != null) {
            jSONObject6.put("ForceStop", 0);
        }
        JSONObject jSONObject7 = p;
        if (jSONObject7 != null) {
            jSONObject7.put("LongPressStop", 0);
        }
        com.bytedance.android.input.common.E.h.b("AsrStopTime");
    }

    /* JADX WARN: Removed duplicated region for block: B:25:0x0063  */
    /* JADX WARN: Removed duplicated region for block: B:26:0x0065  */
    /* JADX WARN: Removed duplicated region for block: B:28:0x0068 A[Catch: JSONException -> 0x0073, TryCatch #0 {JSONException -> 0x0073, blocks: (B:3:0x0005, B:5:0x0018, B:23:0x005a, B:28:0x0068, B:12:0x003f, B:15:0x0046, B:19:0x004f, B:29:0x006d), top: B:34:0x0005 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private final void z0(java.lang.String r8, java.lang.String r9) {
        /*
            r7 = this;
            org.json.JSONObject r0 = new org.json.JSONObject
            r0.<init>()
            java.lang.String r1 = "app_name"
            com.bytedance.android.input.basic.IAppGlobals$a r2 = com.bytedance.android.input.basic.IAppGlobals.a     // Catch: org.json.JSONException -> L73
            java.lang.String r2 = r2.L()     // Catch: org.json.JSONException -> L73
            r0.put(r1, r2)     // Catch: org.json.JSONException -> L73
            java.lang.String r1 = "Input_voiceinput"
            boolean r1 = kotlin.u.c.m.a(r8, r1)     // Catch: org.json.JSONException -> L73
            if (r1 == 0) goto L6d
            java.lang.String r1 = "start_type"
            org.json.JSONObject r2 = com.bytedance.android.input.speech.AsrManager.o     // Catch: org.json.JSONException -> L73
            java.lang.String r3 = "from"
            java.lang.String r2 = r2.optString(r3)     // Catch: org.json.JSONException -> L73
            java.lang.String r3 = "mStartTimeParams.optString(\"from\")"
            kotlin.u.c.m.e(r2, r3)     // Catch: org.json.JSONException -> L73
            int r3 = r2.hashCode()     // Catch: org.json.JSONException -> L73
            r4 = -419167685(0xffffffffe704023b, float:-6.233935E23)
            java.lang.String r5 = "space"
            java.lang.String r6 = "asso"
            if (r3 == r4) goto L4f
            r4 = 3003918(0x2dd60e, float:4.209386E-39)
            if (r3 == r4) goto L46
            r4 = 109637894(0x688f106, float:5.1511666E-35)
            if (r3 == r4) goto L3f
            goto L58
        L3f:
            boolean r2 = r2.equals(r5)     // Catch: org.json.JSONException -> L73
            if (r2 != 0) goto L5a
            goto L58
        L46:
            boolean r2 = r2.equals(r6)     // Catch: org.json.JSONException -> L73
            if (r2 != 0) goto L4d
            goto L58
        L4d:
            r5 = r6
            goto L5a
        L4f:
            java.lang.String r3 = "space_speech"
            boolean r2 = r2.equals(r3)     // Catch: org.json.JSONException -> L73
            if (r2 == 0) goto L58
            goto L5a
        L58:
            java.lang.String r5 = "tools"
        L5a:
            r0.put(r1, r5)     // Catch: org.json.JSONException -> L73
            int r1 = r9.length()     // Catch: org.json.JSONException -> L73
            if (r1 <= 0) goto L65
            r1 = 1
            goto L66
        L65:
            r1 = 0
        L66:
            if (r1 == 0) goto L6d
            java.lang.String r1 = "task_id"
            r0.put(r1, r9)     // Catch: org.json.JSONException -> L73
        L6d:
            com.bytedance.android.input.basic.applog.api.IAppLog$a r9 = com.bytedance.android.input.basic.applog.api.IAppLog.a     // Catch: org.json.JSONException -> L73
            r9.b(r8, r0)     // Catch: org.json.JSONException -> L73
            goto L77
        L73:
            r8 = move-exception
            r8.printStackTrace()
        L77:
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.bytedance.android.input.speech.AsrManager.z0(java.lang.String, java.lang.String):void");
    }

    public final void A0(String str, long j2) {
        kotlin.u.c.m.f(str, "event");
        JSONObject jSONObject = new JSONObject();
        try {
            jSONObject.put("time", j2);
            jSONObject.put("app_name", IAppGlobals.a.L());
            IAppLog.a.b(str, jSONObject);
        } catch (JSONException e2) {
            e2.printStackTrace();
        }
    }

    public final void B0(int i2) {
        JSONObject jSONObject = new JSONObject();
        try {
            if (k > 0) {
                jSONObject.put("time", SystemClock.uptimeMillis() - k);
                jSONObject.put("commit", i2);
                jSONObject.put("app_name", IAppGlobals.a.L());
                IAppLog.a.b("input_voiceinputuseduration", jSONObject);
            }
            k = 0L;
        } catch (JSONException e2) {
            e2.printStackTrace();
        }
    }

    public final void C(String str) throws JSONException {
        kotlin.u.c.m.f(str, DBDefinition.TASK_ID);
        E0("error_start_timeout", true);
        h0("create_handle_outTime", str, false);
        y0(11);
    }

    public final void C0(int i2) {
        JSONObject jSONObject = new JSONObject();
        try {
            jSONObject.put("voice_wordcount", i2);
            jSONObject.put("app_name", IAppGlobals.a.L());
            IAppLog.a.b("Input_voicedone", jSONObject);
        } catch (JSONException e2) {
            e2.printStackTrace();
        }
    }

    public final boolean D() {
        long jCurrentTimeMillis = System.currentTimeMillis();
        g0("action down prepare dismissToolbarHint");
        N.a.a();
        f0("dispatchKeyboardActionDown cost time = " + (System.currentTimeMillis() - jCurrentTimeMillis));
        return false;
    }

    public final void E(final int i2, final long j2, boolean z2) {
        boolean z3 = false;
        u = false;
        if (Y(i2)) {
            AsrContext.a.Y(false);
        }
        IInputSettings.a aVar = IInputSettings.a;
        if (!aVar.b().L()) {
            com.bytedance.android.input.B.j.i("[ASR-Flow]-AsrManager", "DoAsrSend waitAllAsrBackEnable = false");
            Q0(true, "send");
            w0(i2, j2);
            return;
        }
        if (z2 && Y(i2) && com.bytedance.android.input.smart_organize.C.d()) {
            z3 = true;
        }
        u = z3;
        long jM = z3 ? aVar.d().m() : aVar.b().M();
        boolean zN = AsrContext.a.n();
        final kotlin.u.c.B b2 = new kotlin.u.c.B();
        F = new e(b2, j2);
        final long j3 = jM;
        G = new Runnable() { // from class: com.bytedance.android.input.speech.f
            @Override // java.lang.Runnable
            public final void run() throws JSONException {
                AsrManager.c0(j2, b2, j3, i2);
            }
        };
        if (zN || !m) {
            f0(f.a.a.a.a.n2(f.a.a.a.a.t2("DoAsrSend currentAllAsrBack true mHaveVoiceText = "), m, ", prepare doSendAction "));
            Runnable runnable = G;
            if (runnable != null) {
                runnable.run();
                return;
            }
            return;
        }
        F f2 = f4196c;
        f2.A();
        f2.w(F);
        Runnable runnable2 = G;
        if (runnable2 != null) {
            a.f0(f.a.a.a.a.z1("DoAsrSend currentAllAsrBack false, waiting timeout maxTime = ", jM));
            Handler handler = O;
            handler.removeCallbacks(runnable2);
            handler.postDelayed(runnable2, jM);
        }
    }

    public final void F() {
        f4198e = true;
        StringBuilder sbT2 = f.a.a.a.a.t2("doUndo mHavePreEdit = ");
        sbT2.append(j);
        sbT2.append(", asrIsStart = ");
        com.bytedance.android.input.speech.view.l lVar = com.bytedance.android.input.speech.view.l.a;
        sbT2.append(lVar.r().getValue());
        sbT2.append(", mDontCommit = ");
        sbT2.append(f4198e);
        com.bytedance.android.input.B.j.i("[ASR-Flow]-AsrManager", sbT2.toString());
        if (kotlin.u.c.m.a(lVar.r().getValue(), Boolean.TRUE)) {
            if (j) {
                StringBuilder sbT22 = f.a.a.a.a.t2("doUndo mPreCommitWordCount = ");
                sbT22.append(O().i());
                com.bytedance.android.input.B.j.i("[ASR-Flow]-AsrManager", sbT22.toString());
                O().d("");
            }
            Q0(true, "undo");
            lVar.A("", false);
        }
    }

    public final void G() {
        if (kotlin.u.c.m.a(com.bytedance.android.input.speech.view.l.a.r().getValue(), Boolean.TRUE)) {
            f.a.a.a.a.X("stoptype", "cursor", IAppLog.a, "voiceinput_panel_stop");
            com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "Asr: onUpdateSelection curse_move, editIsClick");
            Q0(true, "cursorMove");
            InputView inputView = ImeService.z;
            if (inputView != null) {
                inputView.Z(false);
            }
            E0("cursor_move", false);
        }
    }

    public final void G0() {
        if (!m) {
            com.bytedance.android.input.B.j.i("[ASR-Flow]-Reporter", "reportVoiceInputCommit mHaveVoiceText = false");
            return;
        }
        int iB = com.bytedance.android.input.speech.W.a.b();
        f.a.a.a.a.M("reportVoiceInputCommit voiceTextCommitLength = ", iB, "[ASR-Flow]-Reporter");
        if (iB != 0) {
            IAppLog.a aVar = IAppLog.a;
            JSONObject jSONObject = new JSONObject();
            jSONObject.putOpt("voice_len", Integer.valueOf(iB));
            aVar.b("voice_text_commit", jSONObject);
            com.bytedance.android.input.speech.W.a.c();
        }
    }

    public final void H(byte[] bArr, int i2) {
        kotlin.u.c.m.f(bArr, "buffer");
        f4196c.p(bArr, i2);
        InputView inputView = ImeService.z;
        if (inputView != null) {
            inputView.w0(bArr);
        }
    }

    public final void I() {
        com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "forceVad");
        f4196c.q();
    }

    public final void I0(String str) {
        kotlin.u.c.m.f(str, "jsonString");
        com.bytedance.android.input.B.j.i("[ASR-Flow]-AsrManager", "[DeleteAssociate] jsonString = " + str);
        z = kotlin.collections.v.b();
        A = kotlin.collections.v.b();
        C2349d.c(C2354f0.a, S.b(), null, new k(str, null), 2, null);
    }

    public final void J0(boolean z2) {
        m = z2;
        if (z2) {
            l = true;
        }
    }

    public final Runnable K() {
        return G;
    }

    public final void K0(boolean z2) {
        K = z2;
    }

    public final boolean L() {
        return l;
    }

    public final boolean M0() {
        if (f4202i != SpeechStatus.KStoping || !v) {
            return false;
        }
        kotlinx.coroutines.L<? extends n0> l2 = w;
        return l2 != null && l2.i();
    }

    public final boolean N() {
        return r;
    }

    public final void N0(com.bytedance.common_biz.tool_bar.view.config.j jVar) {
        kotlin.u.c.m.f(jVar, "tipConfig");
        H0(new l(jVar));
    }

    public final boolean O0(String str) throws JSONException {
        Object objZ;
        kotlin.u.c.m.f(str, "from");
        E e2 = E.a;
        E.b("[Android][asr] start asr: from = " + str);
        com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "[ASR-Flow][startAsr][Android] from = " + str);
        boolean z2 = true;
        r = TextUtils.equals(str, "tool") || TextUtils.equals(str, "asso");
        Q0(true, "nextStart");
        x("start_asr:" + str);
        s = TextUtils.equals(str, "space");
        t = false;
        if (f4200g.a(true)) {
            Q q2 = f4197d;
            if (!q2.q()) {
                z();
            }
            boolean zR = IInputSettings.a.b().r();
            if (!q2.n() && zR) {
                com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "[ASR-Flow][startAsr][Android] from = " + str + ", check audio not available.01");
                try {
                    if (q2.q()) {
                        q2.b();
                    }
                    objZ = kotlin.p.a;
                } catch (Throwable th) {
                    objZ = com.prolificinteractive.materialcalendarview.r.z(th);
                }
                Throwable thB = kotlin.h.b(objZ);
                if (thB != null) {
                    f.a.a.a.a.M0(thB, f.a.a.a.a.t2("[ASR-Flow][startAsr][Android] mRecorder stop exception = "), "[ASR-Flow]-AsrManager");
                }
                r(false);
                s();
            } else if (q2.a()) {
                f.a.a.a.a.G0(f.a.a.a.a.t2("[ASR-Flow][startAsr][Android]: start mHaveVoiceText = "), m, "[ASR-Flow]-AsrManager");
                AsrContext asrContext = AsrContext.a;
                asrContext.q();
                x0("next_asr_start", n);
                q = true;
                f4201h = "";
                if (q2.n()) {
                    com.bytedance.android.input.A.e.a.l();
                    E.removeCallbacks(f4195J);
                    com.bytedance.android.input.speech.S.a.j();
                    L0(true);
                    com.bytedance.android.input.speech.view.l.a.w(true);
                    X0(this, SpeechStatus.KTryStart, str, false, 4);
                    O().start();
                    asrContext.m0();
                    asrContext.T();
                    asrContext.Y(true);
                    o.put("from", str);
                    f4199f.e();
                    f4198e = false;
                    f4196c.y(asrContext.u(), str);
                    KeyboardJni.getKeyboardJni().setToolbarRevocationButtonVisible(false);
                    f.a.a.a.a.X("reason", str, IAppLog.a, "input_voiceinput_startfrom");
                    z0("asr_start", "");
                    com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "[ASR-Flow][startAsr][Android] start finish-01");
                    E e3 = E.a;
                    E.b("[Android][asr] start asr end");
                    com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "[ASR-Flow][startAsr][Android] end, startSuccess = " + z2);
                    return z2;
                }
                com.bytedance.android.input.B.j.j("[ASR-Flow]-AsrManager", "[ASR-Flow][startAsr][Android]: check audio not available.03");
                C1058x.a.c(C1058x.f3540e, R.string.asr_start_record_error_tip, 0L, 2);
                UserInteractiveManagerNext.a.b();
            } else {
                com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "[ASR-Flow][startAsr][Android] from = " + str + ", mRecorder.Start failed. work thread isAlive audio not available.02");
                r(false);
                s();
            }
        } else {
            com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "[ASR-Flow][startAsr][Android] from = " + str + ", permissionCheck is false.");
        }
        z2 = false;
        E e32 = E.a;
        E.b("[Android][asr] start asr end");
        com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "[ASR-Flow][startAsr][Android] end, startSuccess = " + z2);
        return z2;
    }

    public final a P(String str) {
        kotlin.u.c.m.f(str, "text");
        a aVar = z.get(str);
        return aVar == null ? A.get(str) : aVar;
    }

    public final void P0() {
        com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "startRecordFirst");
        if (f4200g.a(false)) {
            z();
            if (!f4197d.a()) {
            }
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:24:0x00c3  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final void Q0(boolean r15, java.lang.String r16) {
        /*
            Method dump skipped, instruction units count: 483
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.bytedance.android.input.speech.AsrManager.Q0(boolean, java.lang.String):void");
    }

    public final void R() {
        com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "init");
        AsrContext asrContext = AsrContext.a;
        C2349d.m(C2354f0.a, S.b(), null, new x(null), 2, null);
        SettingsConfigNext.a.j(C);
        com.bytedance.android.input.speech.V.k.a.r();
    }

    public final void R0() {
        F0(this, "AsrStopTime", 0L, false, null, 14);
        F0(this, "LongPressStop", 1L, false, null, 12);
        t = s;
        f4196c.u();
        E.postDelayed(new Runnable() { // from class: com.bytedance.android.input.speech.m
            @Override // java.lang.Runnable
            public final void run() {
                AsrManager asrManager = AsrManager.a;
                com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "stopAsrDelay");
                AsrManager.a.Q0(false, "send");
            }
        }, 150L);
    }

    public final boolean S() {
        return f4202i == SpeechStatus.KErrorShowState;
    }

    public final boolean T() {
        StringBuilder sbT2 = f.a.a.a.a.t2("[hand_write] isAsrSpeechingStatus mCurrentUIStatus = ");
        sbT2.append(f4202i);
        com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", sbT2.toString());
        return f4202i == SpeechStatus.KTryStart || f4202i == SpeechStatus.KStart;
    }

    public final void T0() {
        com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "stopRecordFirst");
        if (X()) {
            return;
        }
        f4197d.b();
    }

    public final boolean U() {
        return f4202i == SpeechStatus.KStop;
    }

    public final void U0(String str, boolean z2, boolean z3, String str2, float f2) {
        kotlin.u.c.m.f(str, "text");
        if (r) {
            InputView inputView = ImeService.z;
            if (inputView != null) {
                inputView.v0(str, Boolean.valueOf(z2), Boolean.TRUE, Boolean.valueOf(z3), str2, f2);
                return;
            }
            return;
        }
        InputView inputView2 = ImeService.z;
        if (inputView2 != null) {
            inputView2.v0(str, Boolean.valueOf(z2), Boolean.TRUE, Boolean.valueOf(z3), str2, f2);
        }
        InputView inputView3 = ImeService.z;
        if (inputView3 != null) {
            inputView3.v0(str, Boolean.valueOf(z2), Boolean.valueOf(r), Boolean.valueOf(z3), str2, f2);
        }
    }

    public final boolean V() {
        return f4202i == SpeechStatus.KStoping;
    }

    public final boolean W() {
        return f4198e;
    }

    public final boolean X() {
        return kotlin.u.c.m.a(com.bytedance.android.input.speech.view.l.a.r().getValue(), Boolean.TRUE);
    }

    public final void h0(String str, String str2, boolean z2) throws JSONException {
        kotlin.u.c.m.f(str, "errorName");
        kotlin.u.c.m.f(str2, DBDefinition.TASK_ID);
        if (kotlin.u.c.m.a(com.bytedance.android.input.speech.view.l.a.r().getValue(), Boolean.TRUE) || z2) {
            f.a.a.a.a.F0(f.a.a.a.a.F2("AsrState-netErrorProcess errorName = ", str, ", taskId = ", str2, ", beforeStart = "), z2, "[ASR-Flow]-AsrManager");
            X0(this, SpeechStatus.KErrorShowState, null, false, 6);
            V0(this, f.a.a.a.a.e1(IAppGlobals.a, R.string.asr_net_error_tip, "IAppGlobals.context.getS…string.asr_net_error_tip)"), true, true, null, 0.0f, 24);
            UserInteractiveManagerNext.a.b();
            Q0(true, "netError");
            if (TextUtils.equals("odin_auth", str)) {
                s0(R.string.asr_download_model_tip_title_service);
            } else {
                s0(R.string.asr_download_model_tip_title);
            }
            if (str.length() > 0) {
                t(str, str2);
            }
        }
    }

    public final void i0(String str) {
        kotlin.u.c.m.f(str, DBDefinition.TASK_ID);
        if (kotlin.u.c.m.a(com.bytedance.android.input.speech.view.l.a.r().getValue(), Boolean.TRUE)) {
            com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "mNoVoiceToLong ");
            E0("10s_no_voice", false);
            X0(this, SpeechStatus.KErrorShowState, null, false, 6);
            V0(this, f.a.a.a.a.e1(IAppGlobals.a, R.string.asr_no_voice_error_tip, "IAppGlobals.context.getS…g.asr_no_voice_error_tip)"), true, true, null, 0.0f, 24);
            UserInteractiveManagerNext.a.b();
            Q0(true, "noResponseToLong");
            s0(R.string.asr_download_model_tip_title);
        }
    }

    public final void j0(int i2) {
        f0(f.a.a.a.a.t1("notifyAsrInterrupt interruptType = ", i2));
        IAsr iAsrA = IAsr.a.a(IAsr.Type.SDK);
        if (iAsrA != null) {
            iAsrA.d(i2);
        }
    }

    public final void k0() {
        IInputSettings.a aVar = IInputSettings.a;
        Objects.requireNonNull(aVar);
        if (aVar.b().q()) {
            g0("onFinishInput asr has stopped");
        } else {
            Q0(true, "onFinishInput");
        }
    }

    public final void l0() {
        com.bytedance.android.input.popup.k kVar;
        try {
            com.bytedance.android.input.B.j.i("[ASR-Flow]-AsrManager", "onFinishInputView mHaveVoiceText = " + m + ", mModifyCount = " + n);
            E0("input_window_hide", false);
            q = false;
            boolean z2 = true;
            Q0(true, "onFinishInputView");
            x0("deactive", n);
            Handler handler = E;
            handler.removeCallbacks(f4195J);
            handler.postDelayed(M, com.heytap.mcssdk.constant.a.q);
            com.bytedance.android.input.popup.k kVar2 = N;
            if (kVar2 == null || !kVar2.isShowing()) {
                z2 = false;
            }
            if (z2 && (kVar = N) != null) {
                kVar.dismiss();
            }
            N.a.a();
            E e2 = E.a;
            com.bytedance.android.input.basic.e.b.a(RunnableC1162n.a);
        } catch (Exception e3) {
            StringBuilder sbT2 = f.a.a.a.a.t2("onFinishInputView error=");
            sbT2.append(e3.getMessage());
            com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", sbT2.toString());
        }
        l = false;
    }

    public final void m0(boolean z2) {
        Object objZ;
        Object objZ2;
        Object objZ3;
        boolean zBooleanValue;
        Object objZ4;
        com.bytedance.android.input.basic.settings.api.c.a aVarB;
        List<String> listZ;
        Long l2;
        IInputSettings.a aVar = IInputSettings.a;
        boolean zU = aVar.b().u();
        f.a.a.a.a.F0(f.a.a.a.a.t2("onReStartInputView mHaveVoiceText = "), m, "[ASR-Flow]-AsrManager");
        boolean z3 = false;
        E0("send_action", false);
        q = false;
        boolean z4 = m;
        com.bytedance.android.input.B.j.m("ImeCompatSendSceneAsrStop", "[ASR-Flow][stopAsr][Android] [RestartInputClick] send = " + z2 + ", hasVoiceContent = " + z4);
        try {
            Objects.requireNonNull(aVar);
            objZ = aVar.b().E();
        } catch (Throwable th) {
            objZ = com.prolificinteractive.materialcalendarview.r.z(th);
        }
        kotlin.g gVar = null;
        if (objZ instanceof h.a) {
            objZ = null;
        }
        List list = (List) objZ;
        if (list == null || list.isEmpty() ? false : list.contains(IAppGlobals.a.L())) {
            com.bytedance.android.input.B.j.i("ImeCompatSendSceneAsrStop", "[ASR-Flow][stopAsr][Android] [RestartInputClick] openOpt = false, in blackList");
            zBooleanValue = false;
        } else {
            try {
                IInputSettings.a aVar2 = IInputSettings.a;
                Objects.requireNonNull(aVar2);
                objZ2 = aVar2.b().G();
            } catch (Throwable th2) {
                objZ2 = com.prolificinteractive.materialcalendarview.r.z(th2);
            }
            if (objZ2 instanceof h.a) {
                objZ2 = null;
            }
            List list2 = (List) objZ2;
            if (list2 == null || list2.isEmpty() ? false : list2.contains(IAppGlobals.a.L())) {
                com.bytedance.android.input.B.j.i("ImeCompatSendSceneAsrStop", "[ASR-Flow][stopAsr][Android] [RestartInputClick] openOpt = true, in whiteList");
                zBooleanValue = true;
            } else {
                try {
                    IInputSettings.a aVar3 = IInputSettings.a;
                    Objects.requireNonNull(aVar3);
                    objZ3 = Boolean.valueOf(aVar3.b().F());
                } catch (Throwable th3) {
                    objZ3 = com.prolificinteractive.materialcalendarview.r.z(th3);
                }
                Object obj = Boolean.FALSE;
                if (objZ3 instanceof h.a) {
                    objZ3 = obj;
                }
                zBooleanValue = ((Boolean) objZ3).booleanValue();
                com.bytedance.android.input.B.j.i("ImeCompatSendSceneAsrStop", "[ASR-Flow][stopAsr][Android] [RestartInputClick] openOpt = " + zBooleanValue + ", follow switch");
            }
        }
        if (!zBooleanValue) {
            try {
                IInputSettings.a aVar4 = IInputSettings.a;
                Objects.requireNonNull(aVar4);
                aVarB = aVar4.b();
            } catch (Throwable th4) {
                objZ4 = com.prolificinteractive.materialcalendarview.r.z(th4);
            }
            if (aVarB.q() && ((listZ = aVarB.z()) == null || !listZ.contains(IAppGlobals.a.L()))) {
                if (aVarB.A()) {
                    IAppGlobals.a aVar5 = IAppGlobals.a;
                    if (TextUtils.equals("com.xingin.xhs", aVar5.L())) {
                        Context context = aVar5.getContext();
                        kotlin.u.c.m.f(context, "context");
                        kotlin.u.c.m.f("com.xingin.xhs", DBDefinition.PACKAGE_NAME);
                        try {
                            PackageManager packageManager = context.getPackageManager();
                            int i2 = Build.VERSION.SDK_INT;
                            PackageInfo packageInfo = i2 >= 28 ? packageManager.getPackageInfo("com.xingin.xhs", 128) : packageManager.getPackageInfo("com.xingin.xhs", 0);
                            gVar = new kotlin.g(packageInfo.versionName, Long.valueOf(i2 >= 28 ? packageInfo.getLongVersionCode() : packageInfo.versionCode));
                        } catch (PackageManager.NameNotFoundException unused) {
                        }
                        if (gVar != null && (l2 = (Long) gVar.d()) != null && l2.longValue() >= 9120801) {
                        }
                    }
                }
                objZ4 = kotlin.p.a;
                Throwable thB = kotlin.h.b(objZ4);
                if (thB != null) {
                    IAppGlobals.a.a("AppAudioCompat", "onFailure = " + thB);
                }
                z3 = true;
            }
            if (z3) {
                com.bytedance.android.input.B.j.m("ImeCompatSendSceneAsrStop", "[ASR-Flow][stopAsr][Android] [RestartInputClick] stop Asr 03");
                a.Q0(true, "restartInputSend");
            } else {
                com.bytedance.android.input.B.j.m("ImeCompatSendSceneAsrStop", "[ASR-Flow][stopAsr][Android] [RestartInputClick] skip stop 04");
            }
        } else if (z2 && z4) {
            com.bytedance.android.input.B.j.m("ImeCompatSendSceneAsrStop", "[ASR-Flow][stopAsr][Android] [RestartInputClick] stop Asr 01");
            a.Q0(true, "restartInputSend");
        } else {
            com.bytedance.android.input.B.j.m("ImeCompatSendSceneAsrStop", "[ASR-Flow][stopAsr][Android] [RestartInputClick] skip stop 02");
        }
        L = System.currentTimeMillis();
        if (IInputSettings.a.b().s()) {
            E.removeCallbacks(M);
        }
        if (!zU || z2) {
            O().f();
            x0("send_clicked", n);
        }
        com.bytedance.android.input.speech.view.l.a.b();
    }

    public final void n0() {
        StringBuilder sbT2 = f.a.a.a.a.t2("onStartInputView = ");
        IAsr.b bVar = IAsr.a;
        IAsr.Type type = IAsr.Type.SDK;
        sbT2.append(bVar.a(type));
        com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", sbT2.toString());
        R();
        L = System.currentTimeMillis();
        E.removeCallbacks(M);
        boolean z2 = !com.bytedance.apm.util.j.b(IAppGlobals.a.getContext()) && O().c();
        IAsr iAsrA = bVar.a(type);
        if (iAsrA != null) {
            iAsrA.c(z2);
        }
        l = false;
        J0(false);
        com.bytedance.android.input.speech.view.l.a.b();
        com.bytedance.android.input.speech.V.k.a.C();
    }

    public final void o(com.bytedance.android.input.speech.R.b bVar) {
        kotlin.u.c.m.f(bVar, "audioRecordListener");
        f4197d.m(bVar);
    }

    public final void o0(String str) {
        kotlin.u.c.m.f(str, DBDefinition.TASK_ID);
        E e2 = E.a;
        E.b("[Android][asr] start success");
        z0("Input_voiceinput", str);
        X0(this, SpeechStatus.KStart, null, false, 6);
        k = SystemClock.uptimeMillis();
        com.bytedance.android.input.speech.view.l.a.C();
        f4197d.r(str);
        com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "onStartSuccess");
    }

    public final void p() {
        if (f4199f.c()) {
            com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "audioFocusChange");
            E0("audio_focus_change", false);
            Q0(false, "");
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:21:0x0074  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final void p0(boolean r16, int r17, int r18, int r19, int r20, com.bytedance.android.input.editor.SelectionTracker.ActionType r21) {
        /*
            Method dump skipped, instruction units count: 341
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.bytedance.android.input.speech.AsrManager.p0(boolean, int, int, int, int, com.bytedance.android.input.editor.SelectionTracker$ActionType):void");
    }

    public final void q() {
        StringBuilder sbT2 = f.a.a.a.a.t2("flow[main]: asrStreamFinished start = ");
        com.bytedance.android.input.speech.view.l lVar = com.bytedance.android.input.speech.view.l.a;
        sbT2.append(lVar.r().getValue());
        com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", sbT2.toString());
        if (kotlin.u.c.m.a(lVar.r().getValue(), Boolean.FALSE)) {
            if (Build.VERSION.SDK_INT >= 29) {
                Handler handler = E;
                Runnable runnable = H;
                if (handler.hasCallbacks(runnable)) {
                    handler.removeCallbacks(runnable);
                    handler.postDelayed(I, IInputSettings.a.b().b());
                    return;
                }
            }
            Handler handler2 = E;
            handler2.removeCallbacks(H);
            handler2.postDelayed(I, IInputSettings.a.b().b());
        }
    }

    public final void q0() {
        g0("onWindowShown");
        X();
    }

    public final void r(boolean z2) {
        f.a.a.a.a.l0("StopAsr from AudioStop isSilence ", z2, "[ASR-Flow]-AsrManager");
        if (z2) {
            E0("audio_silence", false);
            y0(8);
        } else {
            E0("error_audio_open", true);
            y0(7);
        }
        E.post(new Runnable() { // from class: com.bytedance.android.input.speech.h
            @Override // java.lang.Runnable
            public final void run() {
                AsrManager asrManager = AsrManager.a;
                AsrManager.X0(asrManager, AsrManager.SpeechStatus.KErrorShowState, null, false, 6);
                AsrManager.V0(asrManager, f.a.a.a.a.e1(IAppGlobals.a, R.string.asr_start_record_error_tip, "IAppGlobals.context.getS…r_start_record_error_tip)"), true, true, null, 0.0f, 24);
                UserInteractiveManagerNext.a.b();
                asrManager.Q0(true, "");
            }
        });
    }

    public final void r0() {
        if (M().isShowing()) {
            M().dismiss();
        }
    }

    public final void t0(IAsr.ErrorType errorType, int i2, String str) throws JSONException {
        kotlin.u.c.m.f(errorType, "errorType");
        kotlin.u.c.m.f(str, DBDefinition.TASK_ID);
        com.bytedance.android.input.B.j.m("[ASR-Flow]-AsrManager", "processAsrError type = " + errorType + ", code = " + i2);
        switch (errorType) {
            case CREATE_HANDLE_ERROR:
                E0("error_create_handle", true);
                Q0(true, "");
                y0(9);
                break;
            case CREATE_HANDLE_ERROR_DID_OFFLINE_MODE_NULL:
                E0("error_create_handle", true);
                kotlin.u.c.m.f("did_offline_mode_null", "errorName");
                kotlin.u.c.m.f(str, DBDefinition.TASK_ID);
                if (kotlin.u.c.m.a(com.bytedance.android.input.speech.view.l.a.r().getValue(), Boolean.TRUE)) {
                    g0("AsrState- did or offline model is null, prompt user");
                    X0(this, SpeechStatus.KErrorShowState, null, false, 6);
                    V0(this, f.a.a.a.a.e1(IAppGlobals.a, R.string.asr_net_error_tip, "IAppGlobals.context.getS…string.asr_net_error_tip)"), true, true, null, 0.0f, 24);
                    UserInteractiveManagerNext.a.b();
                    Q0(true, "netError");
                    if ("did_offline_mode_null".length() > 0) {
                        t("did_offline_mode_null", str);
                    }
                }
                y0(9);
                break;
            case NET_ERROR:
                E0("error_net_error", true);
                h0("net_error", str, false);
                y0(i2);
                break;
            case ODIN_AUTH_FAILED:
                E0("odin_auth_failed", true);
                h0("odin_auth", str, false);
                y0(15);
                break;
            case OFFLINE_RESPONSE_ERROR:
                E0("error_offline_response", true);
                Q0(true, "");
                y0(10);
                break;
            case SERVER_ERROR:
                E0("error_server_error", true);
                if (kotlin.u.c.m.a(com.bytedance.android.input.speech.view.l.a.r().getValue(), Boolean.TRUE)) {
                    X0(this, SpeechStatus.KErrorShowState, null, false, 6);
                    V0(this, f.a.a.a.a.e1(IAppGlobals.a, R.string.asr_service_error_tip, "IAppGlobals.context.getS…ng.asr_service_error_tip)"), true, true, null, 0.0f, 24);
                    UserInteractiveManagerNext.a.b();
                    Q0(true, "serviceError");
                    s0(R.string.asr_download_model_tip_title_service);
                    t("service", str);
                }
                y0(14);
                break;
            case NET_SWITCH:
                IAppGlobals.a aVar = IAppGlobals.a;
                String string = aVar.getContext().getString(R.string.asr_switch_cell_tips_is_shown);
                kotlin.u.c.m.e(string, "IAppGlobals.context.getS…witch_cell_tips_is_shown)");
                Object objF = SettingsConfigNext.f(string);
                Boolean bool = objF instanceof Boolean ? (Boolean) objF : null;
                Boolean bool2 = Boolean.TRUE;
                boolean zA = kotlin.u.c.m.a(bool, bool2);
                f.a.a.a.a.l0("AsrPunctSettingsGuide begin show --switchCellTipsIsShown = ", zA, "AsrPunctSettingsGuide");
                if (!zA) {
                    KeyboardJni.showGuideTips(aVar.getContext().getString(R.string.asr_switch_cell_setting_guide_text), "", WindowId.ASR_SWITCH_CELL_SETTINGS_TIPS_UI);
                    com.bytedance.android.input.B.j.m("AsrPunctSettingsGuide", "setSwitchCellTipsIsShown");
                    String string2 = aVar.getContext().getString(R.string.asr_switch_cell_tips_is_shown);
                    kotlin.u.c.m.e(string2, "IAppGlobals.context.getS…witch_cell_tips_is_shown)");
                    SettingsConfigNext.m(string2, bool2);
                    break;
                }
                break;
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:51:0x017c  */
    /* JADX WARN: Removed duplicated region for block: B:7:0x0013  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final java.lang.Object u0(java.lang.String r10, boolean r11, kotlin.t.d<? super com.bytedance.common_biz.tool_bar.view.config.j> r12) throws java.lang.Throwable {
        /*
            Method dump skipped, instruction units count: 423
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.bytedance.android.input.speech.AsrManager.u0(java.lang.String, boolean, kotlin.t.d):java.lang.Object");
    }

    public final void w(String str) {
        kotlin.u.c.m.f(str, "actionName");
        kotlinx.coroutines.L<? extends n0> l2 = w;
        boolean z2 = l2 != null && l2.i();
        if (v || z2) {
            g0(f.a.a.a.a.J1("[SmartOrganizeVoice] cancel by keyboard action action=", str));
            f4198e = true;
            x(f.a.a.a.a.J1("keyboard_action:", str));
            boolean z3 = v;
            v = false;
            if (z3) {
                H0(d.a);
            }
        }
    }

    public final a y(String str) {
        kotlin.u.c.m.f(str, "text");
        if (!z.isEmpty()) {
            if (!(str.length() == 0)) {
                for (Map.Entry<String, a> entry : z.entrySet()) {
                    String key = entry.getKey();
                    a value = entry.getValue();
                    if (kotlin.text.a.j(str, key, false, 2, null)) {
                        return value;
                    }
                }
                for (Map.Entry<String, a> entry2 : A.entrySet()) {
                    String key2 = entry2.getKey();
                    a value2 = entry2.getValue();
                    if (kotlin.text.a.j(str, key2, false, 2, null)) {
                        return value2;
                    }
                }
            }
        }
        return null;
    }

    private static final class b {
        private final String a;
        private final com.bytedance.common_biz.tool_bar.view.config.j b;

        /* JADX INFO: renamed from: c, reason: collision with root package name */
        private final String f4204c;

        public b(String str, com.bytedance.common_biz.tool_bar.view.config.j jVar, String str2, int i2) {
            int i3 = i2 & 2;
            str2 = (i2 & 4) != 0 ? null : str2;
            kotlin.u.c.m.f(str, "text");
            this.a = str;
            this.b = null;
            this.f4204c = str2;
        }

        public final String a() {
            return this.f4204c;
        }

        public final com.bytedance.common_biz.tool_bar.view.config.j b() {
            return this.b;
        }

        public final String c() {
            return this.a;
        }

        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof b)) {
                return false;
            }
            b bVar = (b) obj;
            return kotlin.u.c.m.a(this.a, bVar.a) && kotlin.u.c.m.a(this.b, bVar.b) && kotlin.u.c.m.a(this.f4204c, bVar.f4204c);
        }

        public int hashCode() {
            int iHashCode = this.a.hashCode() * 31;
            com.bytedance.common_biz.tool_bar.view.config.j jVar = this.b;
            int iHashCode2 = (iHashCode + (jVar == null ? 0 : jVar.hashCode())) * 31;
            String str = this.f4204c;
            return iHashCode2 + (str != null ? str.hashCode() : 0);
        }

        public String toString() {
            StringBuilder sbT2 = f.a.a.a.a.t2("FinishAsrResult(text=");
            sbT2.append(this.a);
            sbT2.append(", idleTipConfig=");
            sbT2.append(this.b);
            sbT2.append(", feedbackOnlineResult=");
            return f.a.a.a.a.c2(sbT2, this.f4204c, ')');
        }

        public b(String str, com.bytedance.common_biz.tool_bar.view.config.j jVar, String str2) {
            kotlin.u.c.m.f(str, "text");
            this.a = str;
            this.b = jVar;
            this.f4204c = str2;
        }
    }
}
