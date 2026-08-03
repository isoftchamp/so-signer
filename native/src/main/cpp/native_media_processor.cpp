#include <jni.h>

namespace {

constexpr char kNativeVersion[] = "so-signer-native/1";

/**
 * JNI implementation name is deliberately unrelated to a Java class. It is
 * associated with the emulated class and signature by RegisterNatives().
 */
jstring NativeVersion(JNIEnv* environment, jclass /* type */) {
    return environment->NewStringUTF(kNativeVersion);
}

const JNINativeMethod kNativeMethods[] = {
    {
        "nativeVersion",
        "()Ljava/lang/String;",
        reinterpret_cast<void*>(NativeVersion)
    }
};

}  // namespace

/**
 * Plain system-library API. Native callers can resolve this symbol directly
 * with dlsym; it has no Java class or JNI signature.
 */
extern "C" JNIEXPORT const char* so_signer_native_version() {
    return kNativeVersion;
}

/**
 * Dynamic JNI registration keeps Java class names out of exported C/C++
 * function names. JNI still needs a class and method signature at runtime.
 */
extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* virtualMachine, void* /* reserved */) {
    JNIEnv* environment = nullptr;
    if (virtualMachine->GetEnv(
            reinterpret_cast<void**>(&environment),
            JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass processorClass = environment->FindClass(
            "com/example/signer/so/NativeMediaProcessor");
    if (processorClass == nullptr) {
        return JNI_ERR;
    }

    constexpr jint methodCount = static_cast<jint>(
            sizeof(kNativeMethods) / sizeof(kNativeMethods[0]));
    if (environment->RegisterNatives(
            processorClass, kNativeMethods, methodCount) != JNI_OK) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
