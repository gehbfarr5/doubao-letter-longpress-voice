#include "native_api.h"

#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <cstring>
#include <vector>

namespace {

constexpr char kTag[] = "DoubaoLongPressNative";
constexpr char kTargetName[] = "nativeTouch";
constexpr char kTargetSignature[] = "(JIIIJ)V";
constexpr char kBridgeClass[] = "com/jin/doubaolongpressvoice/NativeBridge";
constexpr char kBridgeMethod[] = "onLongPress";
constexpr char kBridgeSignature[] = "(Ljava/lang/Object;II)Z";
constexpr char kDiagnosticMethod[] = "onDiagnostic";
constexpr char kDiagnosticSignature[] = "(Ljava/lang/String;)V";

using NativeTouch = void (*)(JNIEnv *, jobject, jlong, jint, jint, jint, jlong);
using RegisterNatives = jint (*)(JNIEnv *, jclass, const JNINativeMethod *, jint);

HookFunType g_hook = nullptr;
std::atomic<NativeTouch> g_original_native_touch{nullptr};
RegisterNatives g_original_register_natives = nullptr;
std::atomic<bool> g_register_hook_installed{false};
jclass g_bridge_class = nullptr;
jmethodID g_bridge_method = nullptr;
jmethodID g_diagnostic_method = nullptr;

void log_info(const char *message) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", message);
}

void java_diagnostic(JNIEnv *env, const char *message) {
    log_info(message);
    if (env == nullptr || g_bridge_class == nullptr || g_diagnostic_method == nullptr) {
        return;
    }
    jstring value = env->NewStringUTF(message);
    if (value == nullptr) {
        env->ExceptionClear();
        return;
    }
    env->CallStaticVoidMethod(g_bridge_class, g_diagnostic_method, value);
    env->DeleteLocalRef(value);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

void hooked_native_touch(JNIEnv *env, jobject keyboard_view, jlong native_view_id,
                         jint x, jint y, jint action, jlong timestamp) {
    NativeTouch original = g_original_native_touch.load(std::memory_order_acquire);
    if (action == -1 && g_bridge_class != nullptr && g_bridge_method != nullptr) {
        jboolean handled = env->CallStaticBooleanMethod(
                g_bridge_class, g_bridge_method, keyboard_view, x, y);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            log_info("Java long-press callback threw; preserving original nativeTouch");
        } else if (handled == JNI_TRUE) {
            log_info("nativeTouch long-press handled by module");
            return;
        }
    }
    if (original != nullptr) {
        original(env, keyboard_view, native_view_id, x, y, action, timestamp);
    }
}

jint hooked_register_natives(JNIEnv *env, jclass clazz,
                             const JNINativeMethod *methods, jint count) {
    if (g_original_register_natives == nullptr || methods == nullptr || count <= 0) {
        return JNI_ERR;
    }
    std::vector<JNINativeMethod> copy(methods, methods + count);
    bool changed = false;
    for (jint i = 0; i < count; ++i) {
        const char *name = methods[i].name;
        const char *signature = methods[i].signature;
        if (name == nullptr || signature == nullptr
                || std::strcmp(name, kTargetName) != 0
                || std::strcmp(signature, kTargetSignature) != 0
                || methods[i].fnPtr == reinterpret_cast<void *>(hooked_native_touch)) {
            continue;
        }
        NativeTouch candidate = reinterpret_cast<NativeTouch>(methods[i].fnPtr);
        NativeTouch expected = nullptr;
        if (g_original_native_touch.compare_exchange_strong(
                expected, candidate,
                std::memory_order_acq_rel)) {
            copy[i].fnPtr = reinterpret_cast<void *>(hooked_native_touch);
            changed = true;
            java_diagnostic(env, "captured nativeTouch(JIIIJ)V registration");
        } else if (expected == candidate) {
            // ART may register the same table again after an internal reload.
            // Reapply the replacement while preserving the single known original.
            copy[i].fnPtr = reinterpret_cast<void *>(hooked_native_touch);
            changed = true;
            java_diagnostic(env, "reapplied nativeTouch(JIIIJ)V registration hook");
        } else {
            // A distinct implementation cannot safely share one original pointer.
            // Keep it observable and untouched instead of calling the wrong target.
            java_diagnostic(env, "conflicting nativeTouch registration left unchanged");
        }
    }
    return g_original_register_natives(env, clazz,
            changed ? copy.data() : methods, count);
}

void install_register_natives_hook(JNIEnv *env) {
    if (env == nullptr || g_hook == nullptr
            || g_register_hook_installed.exchange(true, std::memory_order_acq_rel)) {
        return;
    }
    int result = g_hook(
            reinterpret_cast<void *>(env->functions->RegisterNatives),
            reinterpret_cast<void *>(hooked_register_natives),
            reinterpret_cast<void **>(&g_original_register_natives));
    if (result == 0 && g_original_register_natives != nullptr) {
        java_diagnostic(env, "RegisterNatives hook installed");
    } else {
        g_register_hook_installed.store(false, std::memory_order_release);
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                "RegisterNatives hook failed result=%d", result);
        java_diagnostic(env, "RegisterNatives hook failed");
    }
}

void on_library_loaded(const char *name, void *) {
    if (name != nullptr && std::strstr(name, "libkeyboard.so") != nullptr) {
        log_info("libkeyboard.so loaded");
    }
}

}  // namespace

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm == nullptr || vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass local_bridge = env->FindClass(kBridgeClass);
    if (local_bridge == nullptr) {
        env->ExceptionClear();
        log_info("NativeBridge class lookup failed");
        return JNI_ERR;
    }
    g_bridge_class = reinterpret_cast<jclass>(env->NewGlobalRef(local_bridge));
    env->DeleteLocalRef(local_bridge);
    g_bridge_method = env->GetStaticMethodID(
            g_bridge_class, kBridgeMethod, kBridgeSignature);
    g_diagnostic_method = env->GetStaticMethodID(
            g_bridge_class, kDiagnosticMethod, kDiagnosticSignature);
    if (g_bridge_method == nullptr || g_diagnostic_method == nullptr) {
        env->ExceptionClear();
        log_info("NativeBridge callback lookup failed");
        return JNI_ERR;
    }
    java_diagnostic(env, g_hook == nullptr
            ? "JNI_OnLoad before native API attachment"
            : "JNI_OnLoad after native API attachment");
    install_register_natives_hook(env);
    return JNI_VERSION_1_6;
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    if (entries != nullptr) {
        g_hook = entries->hook_func;
        log_info("LSPosed native API attached");
    }
    return on_library_loaded;
}
