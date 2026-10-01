package com.jin.doubaolongpressvoice;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.*;

/** Same rules run on desktop APK fixtures and on the phone. No version/name tables. */
final class SemanticResolver {
    static final String RULE_VERSION = "3";
    static final String MANAGER = "com.bytedance.android.input.speech.AsrManager";
    static final String SURFACE = "com.bytedance.android.input.speech.view.AsrLongPressView";
    static final String CONTEXT = "com.bytedance.android.input.speech.AsrContext";
    static final String FEEDBACK = "com.bytedance.android.input.keyboard.UserInteractiveManagerNext";
    private final DexKitBridge dex;
    private final Properties result = new Properties();

    SemanticResolver(DexKitBridge dex) { this.dex = dex; }

    Properties resolve() {
        MethodData active = anchored("active", MANAGER, "boolean", "isAsrSpeechingStatus");
        Set<String> states = active.getUsingFields().stream().map(u -> u.getField().getName())
                .collect(Collectors.toSet());
        require(states.contains("KTryStart") && states.contains("KStart"), "active enum evidence");
        method("cancel", anchored("cancel", MANAGER, "void", "doUndo mHavePreEdit"));
        MethodData commit = one("commit", dex.findMethod(FindMethod.create().matcher(
                MethodMatcher.create().declaredClass(MANAGER).returnType("void")
                    .paramTypes(new String[0]).usingStrings("LongPressStop"))), m ->
                m.getCallers().stream().anyMatch(c -> c.getClassName().equals(SURFACE)
                    && c.getUsingStrings().contains("loosen")));
        method("active", active);
        method("commit", commit);
        MethodData dispatch = anchored("dispatch", MANAGER, "void",
                "DoAsrSend currentAllAsrBack", "int", "long", "boolean");
        method("dispatch", dispatch);
        MethodData send = anchored("sendFinal", MANAGER, "void",
                "DoAsrSend sendFinish costTime", "int", "long");
        require(send.getUsingStrings().contains("asr_real_do_send"), "final send marker");
        require(send.getInvokes().stream().anyMatch(m -> m.getName().equals("performEditorAction")
                && signature(m, "boolean", "int")), "final send editor action");
        method("sendFinal", send);
        MethodData completed = anchored("contextAllBack", CONTEXT, "boolean",
                "allAsrBack, mAsrContentList isEmpty");
        require(invokes(dispatch, completed), "dispatch completion relationship");
        method("contextAllBack", completed);
        field("context", one("context singleton", completed.getDeclaredClass().getFields(), f ->
                Modifier.isStatic(f.getModifiers()) && f.getTypeName().equals(CONTEXT)));
        field("manager", one("manager singleton", dex.getClassData(MANAGER).getFields(),
                f -> Modifier.isStatic(f.getModifiers()) && f.getTypeName().equals(MANAGER)));

        MethodData release = one("native release -> commit", dex.getClassData(SURFACE).getMethods(),
                m -> signature(m, "void") && invokes(m, commit)
                        && m.getUsingStrings().contains("loosen"));
        MethodData close = one("native release -> close panel", release.getInvokes(),
                m -> signature(m, "void", "boolean")
                        && m.getClassName().equals("com.bytedance.android.input.keyboard.InputView"));
        method("closePanel", close);
        MethodData inputGetter = one("native input view getter", release.getInvokes(),
                m -> Modifier.isStatic(m.getModifiers()) && signature(m, close.getClassName()));
        method("inputView", inputGetter);

        // The dispatch method registers exactly one listener on its ASR process.
        MethodData setter = one("dispatch listener setter", dispatch.getInvokes(), m ->
                signatureCount(m, "void", 1) && !Modifier.isStatic(m.getModifiers())
                && Modifier.isInterface(m.getParamTypes().get(0).getModifiers()));
        method("listenerSetter", setter);
        FieldData process = one("dispatch process field", dispatch.getUsingFields().stream()
                .map(UsingFieldData::getField).distinct().collect(Collectors.toList()), f ->
                Modifier.isStatic(f.getModifiers()) && f.getTypeName().equals(setter.getClassName()));
        field("process", process);
        ClassData listener = setter.getParamTypes().get(0);
        MethodData callback = one("listener callback", listener.getMethods(), m ->
                signatureCount(m, "void", 1) && Modifier.isAbstract(m.getModifiers()));
        method("callback", callback);
        MethodData implementation = one("manager callback implementation", dex.findMethod(
                FindMethod.create().matcher(MethodMatcher.create().usingStrings("asrCallBackInfo"))), m ->
                m.getClassName().startsWith(MANAGER + "$")
                && m.getParamTypeNames().equals(callback.getParamTypeNames())
                && m.getDeclaredClass().getInterfaces().stream()
                    .anyMatch(c -> c.getName().equals(listener.getName())));
        method("allBack", one("callback -> allBack", implementation.getInvokes(), m ->
                signature(m, "boolean") && m.getClassName().equals(callback.getParamTypeNames().get(0))));
        method("contextDone", one("context completion", dex.getClassData(CONTEXT).getMethods(),
                m -> signature(m, "void", "int", "boolean") && !Modifier.isStatic(m.getModifiers())));

        MethodData editorSetter = anchored("editor action", null, "void", "setEnterActionType", "int");
        FieldData action = one("editor action field", editorSetter.getUsingFields().stream()
                .filter(u -> u.getUsingType() == FieldUsingType.Write).map(UsingFieldData::getField)
                .collect(Collectors.toList()), f -> f.getTypeName().equals("int"));
        method("enterAction", one("editor action getter", action.getReaders(), m ->
                signature(m, "int") && m.getClassName().equals(editorSetter.getClassName())));
        field("editor", one("editor singleton", editorSetter.getDeclaredClass().getFields(), f ->
                Modifier.isStatic(f.getModifiers()) && f.getTypeName().equals(editorSetter.getClassName())));
        method("feedback", one("feedback signature", dex.getClassData(FEEDBACK).getMethods(), m ->
                signature(m, "void", FEEDBACK + "$KeySound", FEEDBACK + "$KeyVibrate",
                    "com.bytedance.android.input.common.VibrationController$VibrationType", "boolean")));
        field("feedbackManager", one("feedback singleton", dex.getClassData(FEEDBACK).getFields(), f ->
                Modifier.isStatic(f.getModifiers()) && f.getTypeName().equals(FEEDBACK)));
        result.setProperty("rules", RULE_VERSION);
        return result;
    }

    private MethodData anchored(String key, String owner, String returns, String anchor, String... params) {
        MethodMatcher matcher = MethodMatcher.create().returnType(returns).paramTypes(params).usingStrings(anchor);
        if (owner != null) matcher.declaredClass(owner);
        return one(key, dex.findMethod(FindMethod.create().matcher(matcher)),
                m -> !Modifier.isStatic(m.getModifiers()) && (m.getAccessFlags() & (0x40 | 0x1000)) == 0);
    }
    private void method(String key, MethodData m) { result.setProperty(key, m.getDescriptor()); }
    private void field(String key, FieldData f) { result.setProperty(key, f.getDescriptor()); }
    private static boolean signature(MethodData m, String returns, String... params) {
        return m.getReturnTypeName().equals(returns) && m.getParamTypeNames().equals(Arrays.asList(params));
    }
    private static boolean signatureCount(MethodData m, String returns, int count) {
        return m.getReturnTypeName().equals(returns) && m.getParamTypeNames().size() == count;
    }
    private static boolean invokes(MethodData caller, MethodData target) {
        return caller.getInvokes().stream().anyMatch(m -> m.getDescriptor().equals(target.getDescriptor()));
    }
    static <T> T one(String label, Collection<T> values, Predicate<T> test) {
        List<T> matches = values.stream().filter(test).distinct().collect(Collectors.toList());
        if (matches.size() != 1) throw new IllegalStateException(label + ": expected 1 candidate, found " + matches.size());
        return matches.get(0);
    }
    private static void require(boolean valid, String label) {
        if (!valid) throw new IllegalStateException(label);
    }
}
