package com.jin.doubaolongpressvoice;

import java.lang.reflect.*;
import java.util.*;
import org.luckypray.dexkit.wrap.DexField;
import org.luckypray.dexkit.wrap.DexMethod;

/** Immutable, fully bound capability set. No version-number or obfuscated-name branches. */
final class DoubaoCompatAdapter {
    private final Map<String, Method> methods = new HashMap<>();
    private final Map<String, Field> fields = new HashMap<>();
    private final Class<?> surface;
    private final String diagnostic;
    static final String[] METHOD_KEYS = {"active", "cancel", "commit", "dispatch", "sendFinal", "contextAllBack", "closePanel",
            "inputView", "listenerSetter", "callback", "allBack", "contextDone", "enterAction", "feedback"};
    static final String[] FIELD_KEYS = {"manager", "process", "editor", "feedbackManager", "context"};

    DoubaoCompatAdapter(Properties mapping, ClassLoader loader, String diagnostic) throws Exception {
        this(mapping, loader, diagnostic, Class.forName(SemanticResolver.SURFACE, false, loader));
    }
    DoubaoCompatAdapter(Properties mapping, ClassLoader loader, String diagnostic, Class<?> surface) throws Exception {
        if (!SemanticResolver.RULE_VERSION.equals(mapping.getProperty("rules")))
            throw new IllegalStateException("resolver rule mismatch");
        for (String key : METHOD_KEYS) {
            Method method = new DexMethod(required(mapping, key)).getMethodInstance(loader);
            method.setAccessible(true);
            methods.put(key, method);
        }
        for (String key : FIELD_KEYS) {
            Field field = new DexField(required(mapping, key)).getFieldInstance(loader);
            if (!Modifier.isStatic(field.getModifiers())) throw new IllegalStateException(key + " not static");
            field.setAccessible(true);
            fields.put(key, field);
        }
        this.surface = surface;
        check("active", false, boolean.class);
        check("cancel", false, void.class);
        check("commit", false, void.class);
        check("dispatch", false, void.class, int.class, long.class, boolean.class);
        check("sendFinal", false, void.class, int.class, long.class);
        check("contextAllBack", false, boolean.class);
        requireOwner("contextAllBack", fields.get("context").getType());
        check("closePanel", false, void.class, boolean.class);
        check("inputView", true, methods.get("closePanel").getDeclaringClass());
        check("allBack", false, boolean.class);
        check("contextDone", false, void.class, int.class, boolean.class);
        check("enterAction", false, int.class);
        Class<?> listener = methods.get("callback").getDeclaringClass();
        if (!listener.isInterface()) throw new IllegalStateException("callback not interface");
        check("callback", false, void.class, methods.get("allBack").getDeclaringClass());
        check("listenerSetter", false, void.class, listener);
        for (String key : new String[]{"active", "cancel", "commit", "dispatch", "sendFinal"})
            requireOwner(key, fields.get("manager").getType());
        requireOwner("listenerSetter", fields.get("process").getType());
        requireOwner("enterAction", fields.get("editor").getType());
        requireOwner("feedback", fields.get("feedbackManager").getType());
        this.diagnostic = diagnostic;
    }
    private void requireOwner(String key, Class<?> owner) {
        if (methods.get(key).getDeclaringClass() != owner) throw new IllegalStateException(key + " owner mismatch");
    }
    private void check(String key, boolean isStatic, Class<?> returns, Class<?>... params) {
        Method m = methods.get(key);
        if (m.getReturnType() != returns || !Arrays.equals(m.getParameterTypes(), params)
                || Modifier.isStatic(m.getModifiers()) != isStatic || m.isBridge() || (m.isSynthetic() && !key.equals("inputView")))
            throw new IllegalStateException(key + " signature mismatch");
    }
    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isEmpty()) throw new IllegalStateException("missing capability " + key);
        return value;
    }
    String family() { return "SEMANTIC"; }
    boolean isSupported() { return true; }
    boolean hasNativeSurface() { return true; }
    Class<?> nativeSurfaceClass() { return surface; }
    String diagnostic() { return diagnostic; }
    Method method(String key) { return methods.get(key); }
    Object object(String key) throws IllegalAccessException {
        Object value = fields.get(key).get(null);
        if (value == null) throw new IllegalStateException(key + " not initialized");
        return value;
    }
    Object managerInstance() throws Exception { return object("manager"); }
    Object inputView() throws Exception { return invoke("inputView", null); }
    Class<?> listenerClass() { return methods.get("callback").getDeclaringClass(); }
    String contextDoneMethod() { return methods.get("contextDone").getName(); }
    boolean isAsrActive(Object manager) throws Exception { return (Boolean) invoke("active", manager); }
    void cancel(Object manager) throws Exception { invoke("cancel", manager); }
    void commit(Object manager) throws Exception { invoke("commit", manager); }
    void stop(Object manager, boolean cancel, String from) throws Exception {
        invoke(cancel ? "cancel" : "commit", manager);
    }
    void dispatch(Object manager, int ordinal, long now) throws Exception {
        if (!(Boolean) invoke("contextAllBack", object("context")))
            throw new IllegalStateException("cannot dispatch unfinished ASR/organize result");
        invoke("dispatch", manager, ordinal, now, true);
    }
    Object invoke(String key, Object receiver, Object... args) throws Exception {
        return methods.get(key).invoke(receiver, args);
    }
}
