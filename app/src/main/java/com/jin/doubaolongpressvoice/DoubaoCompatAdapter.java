package com.jin.doubaolongpressvoice;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import de.robv.android.xposed.XposedHelpers;

/** Exact-signature adapter for known Doubao ASR API families. */
final class DoubaoCompatAdapter {

    enum Family { V1_4_4, V1_3_17, V1_3_15, V1_3_14, UNSUPPORTED }

    private static final String ASR_MANAGER = "com.bytedance.android.input.speech.AsrManager";
    private static final String ASR_LONG_PRESS_VIEW =
            "com.bytedance.android.input.speech.view.AsrLongPressView";

    private final Family family;
    private final Class<?> asrManagerClass;
    private final Class<?> nativeSurfaceClass;
    private final Method activePrimary;
    private final Method activeSecondary;
    private final Method cancel;
    private final Method commit;
    private final Method dispatch;
    private final String diagnostic;

    private DoubaoCompatAdapter(Family family, Class<?> asrManagerClass,
                                Class<?> nativeSurfaceClass, Method activePrimary,
                                Method activeSecondary, Method cancel, Method commit,
                                Method dispatch, String diagnostic) {
        this.family = family;
        this.asrManagerClass = asrManagerClass;
        this.nativeSurfaceClass = nativeSurfaceClass;
        this.activePrimary = accessible(activePrimary);
        this.activeSecondary = accessible(activeSecondary);
        this.cancel = accessible(cancel);
        this.commit = accessible(commit);
        this.dispatch = accessible(dispatch);
        this.diagnostic = diagnostic;
    }

    static DoubaoCompatAdapter resolve(ClassLoader cl) {
        try {
            Class<?> manager = XposedHelpers.findClass(ASR_MANAGER, cl);
            Class<?> surface = optionalClass(ASR_LONG_PRESS_VIEW, cl);

            Method v144Dispatch = exact(manager, "E", int.class, long.class, boolean.class);
            Method activeT = optional(manager, "T");
            Method undoF = optional(manager, "F");
            Method longPressStopR0 = optional(manager, "R0");
            if (surface != null && activeT != null && undoF != null
                    && longPressStopR0 != null && v144Dispatch != null) {
                return new DoubaoCompatAdapter(Family.V1_4_4, manager, surface,
                        activeT, null, undoF, longPressStopR0, v144Dispatch,
                        "v1.4.4 capabilities: surface/T/F/R0/E(bool)");
            }

            Method dispatch = exact(manager, "t", int.class, long.class);
            Method activeJ = optional(manager, "J");
            Method activeF = optional(manager, "F");
            Method undo = optional(manager, "u");
            Method longPressStop = optional(manager, "w0");
            if (surface != null && activeJ != null && activeF != null
                    && undo != null && longPressStop != null && dispatch != null) {
                return new DoubaoCompatAdapter(Family.V1_3_15, manager, surface,
                        activeJ, activeF, undo, longPressStop, dispatch,
                        "v1.3.15 capabilities: surface/J/F/u/w0/t");
            }

            Method activeActive = optional(manager, "G");
            Method newStop = optional(manager, "w0", boolean.class, String.class);
            if (surface != null && activeActive != null
                    && undo != null && newStop != null && dispatch != null) {
                return new DoubaoCompatAdapter(Family.V1_3_17, manager, surface,
                        activeActive, null, undo, newStop, dispatch,
                        "v1.3.17 capabilities: surface/G/u/w0(bool,String)/t");
            }

            Method activeE = optional(manager, "E");
            Method oldCancel = optional(manager, "s0", boolean.class, String.class);
            if (oldCancel == null) {
                oldCancel = optional(manager, "p0", boolean.class, String.class);
            }
            Method oldCommit = optional(manager, "t0");
            if (oldCommit == null) {
                oldCommit = optional(manager, "q0");
            }
            if (activeE != null && oldCancel != null && oldCommit != null && dispatch != null) {
                return new DoubaoCompatAdapter(Family.V1_3_14, manager, null,
                        activeE, null, oldCancel, oldCommit, dispatch,
                        "v1.3.14 capabilities: E/(s0|p0)/(t0|q0)/t");
            }

            return unsupported(manager, surface,
                    "known exact ASR signatures were not found");
        } catch (Throwable t) {
            return unsupported(null, null,
                    "capability probe failed: " + t.getClass().getSimpleName());
        }
    }

    private static DoubaoCompatAdapter unsupported(Class<?> manager, Class<?> surface,
                                                    String diagnostic) {
        return new DoubaoCompatAdapter(Family.UNSUPPORTED, manager, surface,
                null, null, null, null, null, diagnostic);
    }

    Family family() {
        return family;
    }

    boolean isSupported() {
        return family != Family.UNSUPPORTED;
    }

    boolean hasNativeSurface() {
        return nativeSurfaceClass != null
                && (family == Family.V1_4_4
                || family == Family.V1_3_15 || family == Family.V1_3_17);
    }

    Class<?> nativeSurfaceClass() {
        return nativeSurfaceClass;
    }

    String diagnostic() {
        return diagnostic;
    }

    Object managerInstance() throws ReflectiveOperationException {
        if (asrManagerClass == null) {
            throw new NoSuchFieldException("AsrManager class unavailable");
        }
        java.lang.reflect.Field field = asrManagerClass.getDeclaredField("a");
        field.setAccessible(true);
        Object value = field.get(null);
        if (value == null) {
            throw new IllegalStateException("AsrManager.a is null");
        }
        return value;
    }

    boolean isAsrActive(Object manager) throws ReflectiveOperationException {
        boolean primary = Boolean.TRUE.equals(invoke(activePrimary, manager));
        boolean secondary = activeSecondary != null
                && Boolean.TRUE.equals(invoke(activeSecondary, manager));
        return primary || secondary;
    }

    void cancel(Object manager) throws ReflectiveOperationException {
        if (family == Family.V1_4_4
                || family == Family.V1_3_15 || family == Family.V1_3_17) {
            invoke(cancel, manager);
        } else {
            invoke(cancel, manager, true, "cancel");
        }
    }

    void stop(Object manager, boolean noWaitResult, String from)
            throws ReflectiveOperationException {
        if (family == Family.V1_4_4) {
            if (noWaitResult) {
                invoke(cancel, manager);
            } else {
                invoke(commit, manager);
            }
        } else if (family == Family.V1_3_15) {
            if (noWaitResult) {
                invoke(cancel, manager);
            } else {
                invoke(commit, manager);
            }
        } else if (family == Family.V1_3_17) {
            if (noWaitResult) {
                invoke(cancel, manager);
            } else {
                invoke(commit, manager, false, from);
            }
        } else {
            invoke(cancel, manager, noWaitResult, from);
        }
    }

    void commit(Object manager) throws ReflectiveOperationException {
        if (family == Family.V1_4_4) {
            invoke(commit, manager);
        } else if (family == Family.V1_3_17) {
            invoke(commit, manager, false, "send");
        } else {
            invoke(commit, manager);
        }
    }

    void dispatch(Object manager, int ordinal, long now) throws ReflectiveOperationException {
        if (family == Family.V1_4_4) {
            invoke(dispatch, manager, ordinal, now, true);
        } else {
            invoke(dispatch, manager, ordinal, now);
        }
    }

    private static Method exact(Class<?> cls, String name, Class<?>... parameterTypes) {
        try {
            return cls.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Method optional(Class<?> cls, String name, Class<?>... parameterTypes) {
        return exact(cls, name, parameterTypes);
    }

    private static Class<?> optionalClass(String name, ClassLoader cl) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method accessible(Method method) {
        if (method != null) {
            method.setAccessible(true);
        }
        return method;
    }

    private static Object invoke(Method method, Object receiver, Object... args)
            throws ReflectiveOperationException {
        if (method == null) {
            throw new NoSuchMethodException("required capability is absent");
        }
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ReflectiveOperationException) {
                throw (ReflectiveOperationException) cause;
            }
            throw new InvocationTargetException(cause);
        }
    }
}
